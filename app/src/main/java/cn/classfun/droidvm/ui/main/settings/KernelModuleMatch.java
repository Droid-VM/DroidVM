// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.main.settings;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import cn.classfun.droidvm.lib.data.SocIdentity;

/**
 * Decides which host kernel modules apply to <b>this</b> device.
 *
 * <p>The KMI directory already answers "which kernel", and that is only half the question: every
 * module shipped so far is Gunyah/Qualcomm work that does nothing on a MediaTek or Tensor phone,
 * and a module written for those later would be just as wrong here. So each module carries a match
 * rule, and only modules whose rule passes are listed at all.
 *
 * <h3>Rules</h3>
 * Rules live in {@code usr/lib/modules/match.json}, shipped next to the {@code .ko} files by the
 * repo that builds them, so a new module for a new vendor needs no app change:
 * <pre>
 * { "version": 1,
 *   "modules": {
 *     "gunyah_host_share": { "soc_vendor": ["qualcomm"] },
 *     "mtk_whatever":      { "soc_vendor": ["mediatek"], "soc_model_prefix": ["MT69"] } },
 *   "names": {
 *     "gunyah_host_share": "Gunyah Host Share" } }
 * </pre>
 * The key is a module-name <b>prefix</b> (so {@code udmabuf} covers {@code udmabuf_gki_6.6}) and
 * the longest matching key wins. Within a field the values are alternatives (OR); across fields
 * every field must pass (AND). An empty rule matches everything.
 *
 * <p>{@code names} maps the same prefixes to the human title the module's card shows
 * ({@link #displayName}). It is a top-level section, not a rule field, on purpose: an unknown
 * field <em>inside</em> a rule makes the rule fail (below), so an older app reading a newer
 * file would stop listing every named module -- whereas it never looks at extra top-level keys.
 *
 * <p>Supported fields: {@code soc_vendor} (tokens from {@link SocIdentity}), {@code soc_model}
 * (exact, case-insensitive), {@code soc_model_prefix}. <b>A field this app does not know makes the
 * rule fail</b>, rather than being skipped: a newer rule file narrowing a module by something we
 * cannot evaluate must not end up listing it anyway -- an insmod on the wrong device is a kernel
 * panic, so the safe default is to say no.
 *
 * <p>The app also ships {@code assets/match.json} in the same format. It carries the rule for
 * GH-Hugepage-Reserve -- which has no {@code .ko} and so no prebuilt to ride on -- and doubles as
 * the answer for prebuilts older than {@code match.json}, which only ever contained Qualcomm
 * modules. The device file wins where both describe a module.
 */
public final class KernelModuleMatch {
    private static final String TAG = "KernelModuleMatch";
    private static final String DEVICE_RULES =
        pathJoin(DATA_DIR, "usr", "lib", "modules", "match.json");
    private static final String BUILTIN_RULES = "match.json";
    /** GH-Hugepage-Reserve's key in the built-in rules; it ships no .ko to name it. */
    static final String HUGEPAGE = "gh_hugepage_reserve";

    @Nullable
    private static volatile JSONObject deviceRules;
    @Nullable
    private static volatile JSONObject builtinRules;
    @Nullable
    private static volatile JSONObject deviceNames;
    @Nullable
    private static volatile JSONObject builtinNames;
    private static volatile boolean loadTried;

    private KernelModuleMatch() {
    }

    /** Read both rule files once. Does file I/O and getprop: call off the main thread. */
    static synchronized void preload(@NonNull Context ctx) {
        if (loadTried) return;
        loadTried = true;
        var builtin = parse(readAsset(ctx));
        var device = parse(readDeviceFile());
        builtinRules = builtin == null ? null : builtin.optJSONObject("modules");
        deviceRules = device == null ? null : device.optJSONObject("modules");
        builtinNames = builtin == null ? null : builtin.optJSONObject("names");
        deviceNames = device == null ? null : device.optJSONObject("names");
        SocIdentity.vendor(); // warm the cache while we are off the main thread anyway
    }

    /** Does this module (normalized .ko basename) apply to this device? */
    static boolean allows(@NonNull String moduleName) {
        var rule = ruleFor(moduleName);
        if (rule == null) {
            // Every shipped module has a rule (the build refuses otherwise), so this is a .ko we
            // know nothing about. Saying no keeps a stray file out of a list whose buttons insmod.
            Log.w(TAG, fmt("no match rule for %s; not listing it", moduleName));
            return false;
        }
        return eval(rule, moduleName);
    }

    /** GH-Hugepage-Reserve is a Magisk module, not a .ko, but the same rules decide it. */
    static boolean allowsHugepage() {
        var rule = ruleFor(HUGEPAGE);
        return rule != null && eval(rule, HUGEPAGE);
    }

    /**
     * Is there anything here for this device at all? The setup wizard skips its kernel-module page
     * entirely when there is not -- on a phone none of these modules were written for, the page
     * would be a list of nothing with instructions to load it. Settings still opens the list; it
     * just shows the empty state. Does I/O: call off the main thread.
     */
    public static boolean anyApplicable(@NonNull Context ctx) {
        preload(ctx);
        return allowsHugepage() || !KernelModuleManager.list(ctx).isEmpty();
    }

    /**
     * Human title for this module from the files' {@code names} sections, resolved like the
     * rules (longest prefix, device file first), or null when neither file names it -- the
     * caller then falls back to something derived from the module name itself.
     */
    @Nullable
    static String displayName(@NonNull String moduleName) {
        var fromDevice = longestPrefixString(deviceNames, moduleName);
        return fromDevice != null ? fromDevice : longestPrefixString(builtinNames, moduleName);
    }

    /** Longest-prefix rule for this module: device file first, then the app's built-in. */
    @Nullable
    private static JSONObject ruleFor(@NonNull String moduleName) {
        var fromDevice = longestPrefix(deviceRules, moduleName);
        return fromDevice != null ? fromDevice : longestPrefix(builtinRules, moduleName);
    }

    @Nullable
    private static String longestPrefixString(@Nullable JSONObject names, @NonNull String name) {
        if (names == null) return null;
        String best = null;
        int bestLen = -1;
        for (var it = names.keys(); it.hasNext(); ) {
            var key = it.next();
            if (key.length() > bestLen && name.startsWith(key)) {
                var value = names.optString(key);
                if (!value.isEmpty()) {
                    best = value;
                    bestLen = key.length();
                }
            }
        }
        return best;
    }

    @Nullable
    private static JSONObject longestPrefix(@Nullable JSONObject rules, @NonNull String name) {
        if (rules == null) return null;
        JSONObject best = null;
        int bestLen = -1;
        for (var it = rules.keys(); it.hasNext(); ) {
            var key = it.next();
            if (key.length() > bestLen && name.startsWith(key)) {
                var rule = rules.optJSONObject(key);
                if (rule != null) {
                    best = rule;
                    bestLen = key.length();
                }
            }
        }
        return best;
    }

    private static boolean eval(@NonNull JSONObject rule, @NonNull String moduleName) {
        for (var it = rule.keys(); it.hasNext(); ) {
            var field = it.next();
            switch (field) {
                case "soc_vendor":
                    if (!anyEquals(rule.optJSONArray(field), SocIdentity.vendor())) return false;
                    break;
                case "soc_model":
                    if (!anyEquals(rule.optJSONArray(field), SocIdentity.model())) return false;
                    break;
                case "soc_model_prefix":
                    if (!anyPrefixOf(rule.optJSONArray(field), SocIdentity.model())) return false;
                    break;
                case "comment":
                    break;
                default:
                    Log.w(TAG, fmt("rule for %s uses unknown field '%s'; this app cannot"
                        + " judge it, so not listing the module", moduleName, field));
                    return false;
            }
        }
        return true;
    }

    private static boolean anyEquals(@Nullable JSONArray values, @NonNull String actual) {
        if (values == null) return false;
        for (int i = 0; i < values.length(); i++)
            if (actual.equalsIgnoreCase(values.optString(i))) return true;
        return false;
    }

    private static boolean anyPrefixOf(@Nullable JSONArray values, @NonNull String actual) {
        if (values == null) return false;
        var lower = actual.toLowerCase(Locale.ROOT);
        for (int i = 0; i < values.length(); i++) {
            var p = values.optString(i);
            if (!p.isEmpty() && lower.startsWith(p.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    @Nullable
    private static JSONObject parse(@Nullable String json) {
        if (json == null) return null;
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            Log.w(TAG, "unparseable match rules", e);
            return null;
        }
    }

    @Nullable
    private static String readAsset(@NonNull Context ctx) {
        try (var in = ctx.getAssets().open(BUILTIN_RULES)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "no built-in match rules", e);
            return null;
        }
    }

    @Nullable
    private static String readDeviceFile() {
        try (var in = new FileInputStream(DEVICE_RULES)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null; // prebuilt predates match.json, or is not extracted yet
        }
    }
}
