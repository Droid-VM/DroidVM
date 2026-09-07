// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

import cn.classfun.droidvm.daemon.server.RequestException;

/**
 * The automatic USB attach rules: one global file of four ordered layers, each an ordered list.
 * Order is priority, within a layer and across them. Rules are not per VM on purpose -- which VM
 * gets a device when two of them want it is part of the rule, and a per-VM list has nowhere to
 * say it.
 *
 * <p>Immutable once built, and validated on the way in: a rule the daemon holds is always one it
 * could act on. The org.json conversion is kept at the edge ({@link #fromJson}, {@link #toJson})
 * so the model and its validation run under the stubbed android.jar of a unit test.</p>
 */
public final class UsbRules {
    /**
     * The container's shape, not the rules'. It stayed at 1 when {@code controller}, then
     * {@code target}, then {@code enabled} were added: every one of those keys is optional on
     * the way in and an absent one means what it always meant, so a file written before any of
     * them existed is still exactly what it says, and a bump would only have made a new daemon
     * refuse one.
     */
    public static final int VERSION = 1;
    /**
     * {@code vid:pid}, lowercase hex, then optionally {@code :serial}; a serial may hold anything.
     *
     * <p>Public because the editor checks what the user typed against it before pushing: being
     * told about a typo by the daemon, after a save that also wrote the VM config, is later than
     * it needs to be, and a second copy of the pattern is a second answer.</p>
     */
    public static final Pattern ID_PATTERN =
        Pattern.compile("^[0-9a-fA-F]{4}:[0-9a-fA-F]{4}(:.+)?$");
    /** A port chain: {@code 1.2.2}, {@code 1.4}, or a root port {@code 3}. */
    public static final Pattern PORT_PATTERN = Pattern.compile("^\\d+(\\.\\d+)*$");

    /** The four layers, in the order they are consulted. */
    public enum Layer {
        EXACT("exact"),
        PORT("port"),
        DEVICE("device"),
        ANY("any");

        /** The key this layer goes under in the JSON file and on the wire. */
        public final String key;

        Layer(@NonNull String key) {
            this.key = key;
        }

        @Nullable
        public static Layer fromKey(@NonNull String key) {
            for (var layer : values())
                if (layer.key.equals(key)) return layer;
            return null;
        }
    }

    /** What a rule does with the device it matches. */
    public enum Target {
        /** Leave it where it is; the last word on the device, so the search stops here. */
        HOST("host"),
        /** Hand it to the rule's VM, once that VM is running and has the controller. */
        VM("vm"),
        /**
         * Leave it to nobody: the search stops and nothing is done, so a device the autoprobe
         * gate handed over driverless stays driverless. It is not a write -- "hidden" is a state
         * a device is left in, not one it is put into -- so a device the host already has stays
         * the host's until it is unplugged and comes back through the gate.
         */
        SINK("sink");

        /** The key this target goes under in the JSON file and on the wire. */
        public final String key;

        Target(@NonNull String key) {
            this.key = key;
        }

        @Nullable
        public static Target fromKey(@NonNull String key) {
            for (var target : values())
                if (target.key.equals(key)) return target;
            return null;
        }
    }

    /**
     * One entry. Which fields are set is decided by the layer it sits in; {@code target} says
     * what happens to the device it matches, and {@code vm} is set for -- and only for -- a
     * {@link Target#VM} rule.
     *
     * <p>{@code controller} names one xHCI controller inside that VM. Null means the VM's first
     * one, which is what every rule written before controllers existed meant and still means --
     * so an old file needs no rewriting. The editor writes the id out, because deleting a
     * controller should leave its rules visibly dangling rather than silently re-point them at
     * whichever controller became the first.</p>
     */
    public static final class Rule {
        @Nullable
        public final String id;
        @Nullable
        public final String port;
        @Nullable
        public final String vm;
        @Nullable
        public final String controller;
        @NonNull
        public final Target target;

        /**
         * The four-field form, where the VM says the target: that is what a rule written before
         * targets existed meant, and what every caller that cannot mean a sink means.
         */
        public Rule(@Nullable String id, @Nullable String port, @Nullable String vm,
                    @Nullable String controller) {
            this(id, port, vm, controller, vm == null ? Target.HOST : Target.VM);
        }

        public Rule(@Nullable String id, @Nullable String port, @Nullable String vm,
                    @Nullable String controller, @NonNull Target target) {
            this.id = id;
            this.port = port;
            this.vm = vm;
            this.controller = controller;
            this.target = target;
        }

        /** Whether the device with [deviceId] at [devicePort] is one this rule speaks about. */
        public boolean matches(@NonNull String deviceId, @NonNull String devicePort) {
            if (id != null && !id.equals(deviceId)) return false;
            return port == null || port.equals(devicePort);
        }
    }

    private final boolean enabled;
    private final Map<Layer, List<Rule>> layers;

    private UsbRules(boolean enabled, @NonNull Map<Layer, List<Rule>> layers) {
        this.enabled = enabled;
        this.layers = layers;
    }

    @NonNull
    public static UsbRules empty() {
        return build(new EnumMap<>(Layer.class), null);
    }

    /**
     * The master switch: whether the rules below it run at all. Off, every trigger is a no-op
     * and the devices are Android's, while the rules themselves are kept exactly as they are --
     * the page stays editable so a user can prepare them before turning it on.
     *
     * <p>It lives beside the layers rather than in a preference because it is saved by the same
     * button as the rules, and the daemon has to read it in the same breath: a rule set whose
     * switch arrived separately would run for as long as the two files disagreed.</p>
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Builds a rule set, refusing anything the engine could not act on.
     *
     * @param targetExists says whether a rule's target -- a VM id and, when it names one, a
     *                     controller inside it -- is one the daemon can act on; null skips that
     *                     check, for a file read back at start-up, where a rule for a VM or a
     *                     controller deleted since is kept -- it can never match, and dropping
     *                     the user's entry silently would be worse.
     * @throws RequestException naming the layer, the index and what is wrong.
     */
    @NonNull
    public static UsbRules build(@NonNull Map<Layer, List<Rule>> layers,
                                 @Nullable BiPredicate<String, String> targetExists) {
        // The form every caller that cannot mean the switch uses, and what an absent "enabled"
        // reads as: the rules run, which is what every rule set meant before there was a switch
        // to say otherwise.
        return build(true, layers, targetExists);
    }

    /** As {@link #build(Map, BiPredicate)}, with the master switch the file carries. */
    @NonNull
    public static UsbRules build(boolean enabled, @NonNull Map<Layer, List<Rule>> layers,
                                 @Nullable BiPredicate<String, String> targetExists) {
        var result = new EnumMap<Layer, List<Rule>>(Layer.class);
        for (var layer : Layer.values()) {
            var rules = layers.get(layer);
            if (rules == null) rules = Collections.emptyList();
            var checked = new ArrayList<Rule>(rules.size());
            for (var i = 0; i < rules.size(); i++)
                checked.add(validate(layer, i, rules.get(i), targetExists));
            result.put(layer, Collections.unmodifiableList(checked));
        }
        return new UsbRules(enabled, result);
    }

    @NonNull
    private static Rule validate(@NonNull Layer layer, int index, @NonNull Rule rule,
                                 @Nullable BiPredicate<String, String> targetExists) {
        var where = fmt("%s[%d]", layer.key, index);
        var wantsId = layer == Layer.EXACT || layer == Layer.DEVICE;
        var wantsPort = layer == Layer.EXACT || layer == Layer.PORT;
        if (wantsId && rule.id == null)
            throw new RequestException(fmt("%s: missing id", where));
        if (!wantsId && rule.id != null)
            throw new RequestException(fmt("%s: a %s rule takes no id", where, layer.key));
        if (wantsPort && rule.port == null)
            throw new RequestException(fmt("%s: missing port", where));
        if (!wantsPort && rule.port != null)
            throw new RequestException(fmt("%s: a %s rule takes no port", where, layer.key));
        // The any layer is the last one, so nothing follows a rule in it: "keep it on the host"
        // there is what already happens, and a row that does nothing reads as one that does.
        if (layer == Layer.ANY && rule.target == Target.HOST)
            throw new RequestException(fmt("%s: the any layer takes no host rule", where));
        if (rule.target == Target.SINK && rule.vm != null)
            throw new RequestException(fmt("%s: a sink rule takes no vm", where));
        if (rule.target == Target.VM && rule.vm == null)
            throw new RequestException(fmt("%s: a vm rule needs a vm", where));
        if (rule.target == Target.HOST && rule.vm != null)
            throw new RequestException(fmt("%s: a host rule takes no vm", where));
        if (rule.controller != null) {
            // A host rule keeps the device on the host; there is no controller for it to name.
            if (rule.vm == null)
                throw new RequestException(fmt("%s: controller needs a vm", where));
            if (rule.controller.isEmpty())
                throw new RequestException(fmt(
                    "%s: controller must be a controller id or null", where));
        }
        String id = null;
        if (rule.id != null) {
            if (!ID_PATTERN.matcher(rule.id).matches())
                throw new RequestException(fmt(
                    "%s: bad id %s (want vid:pid or vid:pid:serial)", where, rule.id));
            // vid:pid is hex and the device side lowercases it; the serial is a string and is
            // compared as the device reports it.
            id = rule.id.substring(0, 9).toLowerCase(Locale.ROOT) + rule.id.substring(9);
        }
        if (rule.port != null && !PORT_PATTERN.matcher(rule.port).matches())
            throw new RequestException(fmt(
                "%s: bad port %s (want a port chain such as 1.2.2)", where, rule.port));
        if (rule.vm != null) {
            if (rule.vm.isEmpty())
                throw new RequestException(fmt("%s: vm must be a VM id or null", where));
            if (targetExists != null && !targetExists.test(rule.vm, rule.controller))
                throw new RequestException(rule.controller == null
                    ? fmt("%s: VM not found: %s", where, rule.vm)
                    : fmt("%s: VM %s has no USB controller %s", where, rule.vm, rule.controller));
        }
        return new Rule(id, rule.port, rule.vm, rule.controller, rule.target);
    }

    /** The rules of [layer], in priority order; never null. */
    @NonNull
    public List<Rule> layer(@NonNull Layer layer) {
        var rules = layers.get(layer);
        return rules == null ? Collections.emptyList() : rules;
    }

    public boolean isEmpty() {
        for (var layer : Layer.values())
            if (!layer(layer).isEmpty()) return false;
        return true;
    }

    /**
     * Reads the wire/file form. Unknown keys are refused rather than ignored: a misspelt layer
     * would otherwise be a rule set that silently does nothing.
     */
    @NonNull
    public static UsbRules fromJson(@NonNull JSONObject obj,
                                    @Nullable BiPredicate<String, String> targetExists) {
        var layers = new EnumMap<Layer, List<Rule>>(Layer.class);
        // Absent reads as on, which is what every file written before the switch existed means.
        var enabled = optBoolean(obj, "enabled", true);
        var keys = obj.keys();
        while (keys.hasNext()) {
            var key = keys.next();
            if ("enabled".equals(key)) continue;
            if ("version".equals(key)) {
                var version = obj.opt("version");
                if (!(version instanceof Number) || ((Number) version).intValue() != VERSION)
                    throw new RequestException(fmt("unsupported rules version %s", version));
                continue;
            }
            var layer = Layer.fromKey(key);
            if (layer == null)
                throw new RequestException(fmt("unknown rules key: %s", key));
            var array = obj.optJSONArray(key);
            if (array == null) {
                if (obj.isNull(key)) continue;
                throw new RequestException(fmt("%s must be a list", key));
            }
            var rules = new ArrayList<Rule>(array.length());
            for (var i = 0; i < array.length(); i++) {
                var item = array.optJSONObject(i);
                if (item == null)
                    throw new RequestException(fmt("%s[%d] must be an object", key, i));
                rules.add(ruleFromJson(key, i, item));
            }
            layers.put(layer, rules);
        }
        return build(enabled, layers, targetExists);
    }

    @NonNull
    private static Rule ruleFromJson(@NonNull String layer, int index, @NonNull JSONObject item) {
        var keys = item.keys();
        while (keys.hasNext()) {
            var key = keys.next();
            if ("id".equals(key) || "port".equals(key) || "vm".equals(key)
                || "controller".equals(key) || "target".equals(key)) continue;
            throw new RequestException(fmt("%s[%d]: unknown field %s", layer, index, key));
        }
        var vm = optString(item, "vm");
        var wanted = optString(item, "target");
        // A row with no target is one written before there was a target to write: the vm field
        // is the whole of what it said, and it goes on saying it.
        var target = wanted == null ? (vm == null ? Target.HOST : Target.VM)
            : Target.fromKey(wanted);
        // Not a fallback to host: a misspelt target would be a rule that quietly does the
        // opposite of what it says.
        if (target == null)
            throw new RequestException(fmt("%s[%d]: unknown target %s", layer, index, wanted));
        return new Rule(optString(item, "id"), optString(item, "port"), vm,
            optString(item, "controller"), target);
    }

    /**
     * A boolean field, where an absent key and a JSON null both read as [fallback]. Anything
     * else is refused rather than read as false: a switch that quietly turned itself off would
     * hand every device back without a word.
     */
    private static boolean optBoolean(@NonNull JSONObject obj, @NonNull String key,
                                      boolean fallback) {
        if (!obj.has(key) || obj.isNull(key)) return fallback;
        var value = obj.opt(key);
        if (!(value instanceof Boolean))
            throw new RequestException(fmt("%s must be true or false", key));
        return (Boolean) value;
    }

    /** A string field, where an absent key and a JSON null both read as null. */
    @Nullable
    private static String optString(@NonNull JSONObject item, @NonNull String key) {
        if (!item.has(key) || item.isNull(key)) return null;
        var value = item.opt(key);
        if (!(value instanceof String))
            throw new RequestException(fmt("%s must be a string or null", key));
        return (String) value;
    }

    /** The wire/file form: each rule carries exactly the fields its layer reads. */
    @NonNull
    public JSONObject toJson() throws JSONException {
        var obj = new JSONObject();
        obj.put("version", VERSION);
        // Always written, the way a rule's target is: a file that leaves the switch to be
        // inferred is one a reader has to know the default of.
        obj.put("enabled", enabled);
        for (var layer : Layer.values()) {
            var array = new JSONArray();
            for (var rule : layer(layer)) {
                var item = new JSONObject();
                if (rule.id != null) item.put("id", rule.id);
                if (rule.port != null) item.put("port", rule.port);
                // Always written, because a reader that has to infer the outcome from the other
                // fields is a second answer to a question this key already answers.
                item.put("target", rule.target.key);
                // Left out when there is none: a host and a sink rule have no VM, and a rule
                // that means the VM's first controller names none.
                if (rule.vm != null) item.put("vm", rule.vm);
                if (rule.controller != null) item.put("controller", rule.controller);
                array.put(item);
            }
            obj.put(layer.key, array);
        }
        return obj;
    }
}
