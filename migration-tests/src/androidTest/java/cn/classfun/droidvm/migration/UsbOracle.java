// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Runs the production daemon/usb code over the fixture's fake sysfs trees and
 * values; see RootOracle. The manager's sysfs paths are compile-time constants
 * (/sys/bus/usb/...), so only paths that read the fixture's tree, or refuse
 * before touching the real one, are exercised. A RequestException is a row
 * value ("error"), so the legacy message is compared too.
 */
final class UsbOracle {
    private static final String USB = "cn.classfun.droidvm.daemon.usb.";
    private static final String IPC = "cn.classfun.droidvm.daemon.ipc.usb.";
    private static final String SERVER = "cn.classfun.droidvm.daemon.server.";

    private UsbOracle() {
    }

    static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name.contains(".") ? name : USB + name);
    }

    static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        var result = type.getDeclaredMethod(name, args);
        result.setAccessible(true);
        return result;
    }

    static Object call(Method method, Object target, Object... args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** RequestException is the legacy message; anything else fails the row. */
    static boolean refused(Throwable error, JSONObject row) throws Exception {
        if (!error.getClass().getName().equals(SERVER + "RequestException")) return false;
        row.put("error", error.getMessage());
        return true;
    }

    static Object nul(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    static BiPredicate<String, String> exists(JSONObject test) {
        var vms = test.optJSONArray("vms");
        if (vms == null) return null;
        return (vm, controller) -> {
            for (int i = 0; i < vms.length(); ++i) if (vms.optString(i).equals(vm)) return true;
            return false;
        };
    }

    static void rules(JSONObject test, JSONObject row) throws Throwable {
        var rulesType = type("UsbRules");
        try {
            var rules = call(method(rulesType, "fromJson", JSONObject.class, BiPredicate.class), null, test.getJSONObject("rules"), exists(test));
            row.put("rules", call(method(rulesType, "toJson"), rules));
            row.put("enabled", call(method(rulesType, "isEnabled"), rules));
            row.put("empty", call(method(rulesType, "isEmpty"), rules));
        } catch (Throwable error) {
            if (!refused(error, row)) throw error;
        }
    }

    static Object state(String key) throws Throwable {
        var stateType = type("UsbHostDevice$State");
        return call(method(stateType, "fromKey", String.class), null, key);
    }

    static Object device(JSONObject spec) throws Throwable {
        var deviceType = type("UsbRuleEngine$Device");
        return deviceType.getConstructor(String.class, String.class, String.class, int.class, type("UsbHostDevice$State"))
            .newInstance(spec.getString("sysfs"), spec.getString("id"), spec.getString("port"), spec.getInt("devnum"), state(spec.getString("state")));
    }

    static Object decisionJson(Object decision) throws Throwable {
        if (decision == null) return JSONObject.NULL;
        return call(method(type("UsbPassthroughManager"), "decisionJson", type("UsbRuleEngine$Decision")), null, decision);
    }

    static void engine(JSONObject test, JSONObject row) throws Throwable {
        var engineType = type("UsbRuleEngine");
        var rulesType = type("UsbRules");
        var engine = engineType.getConstructor().newInstance();
        var fromJson = method(rulesType, "fromJson", JSONObject.class, BiPredicate.class);
        method(engineType, "setRules", rulesType).invoke(engine, call(fromJson, null, test.getJSONObject("rules"), null));
        var stateEnum = Class.forName("cn.classfun.droidvm.lib.store.vm.VMState");
        var states = test.getJSONObject("states");
        var controllers = test.getJSONObject("controllers");
        Function<String, Object> stateOf = vm -> {
            if (!states.has(vm)) return null;
            try {
                return stateEnum.getMethod("valueOf", String.class).invoke(null, states.getString(vm));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        BiPredicate<String, String> has = (vm, controller) -> {
            var list = controllers.optJSONArray(vm);
            if (list == null || list.length() == 0) return false;
            if (controller == null) return true;
            for (int i = 0; i < list.length(); ++i) if (list.optString(i).equals(controller)) return true;
            return false;
        };
        for (var op : iterate(test.getJSONArray("ops"))) {
            var args = (JSONArray) op;
            switch (args.getString(0)) {
                case "lock": method(engineType, "lock", String.class, int.class).invoke(engine, args.getString(1), args.getInt(2)); break;
                case "unlock": method(engineType, "unlock", String.class).invoke(engine, args.getString(1)); break;
                case "fail": method(engineType, "markFailed", String.class, String.class).invoke(engine, args.getString(1), args.getString(2)); break;
                case "forget_failures": method(engineType, "forgetFailuresFor", String.class).invoke(engine, args.getString(1)); break;
                case "forget": method(engineType, "forget", String.class).invoke(engine, args.getString(1)); break;
                case "clear_locks": method(engineType, "clearLocks").invoke(engine); break;
                default: throw new IllegalArgumentException("engine op");
            }
        }
        var devices = new ArrayList<Object>();
        var decisions = new JSONArray();
        var locked = new JSONArray();
        var candidate = new JSONArray();
        var deviceType = type("UsbRuleEngine$Device");
        var decide = method(engineType, "decide", deviceType, Function.class, BiPredicate.class);
        for (var spec : iterate(test.getJSONArray("devices"))) {
            var device = device((JSONObject) spec);
            devices.add(device);
            decisions.put(decisionJson(call(decide, engine, device, stateOf, has)));
            locked.put(call(method(engineType, "isLocked", String.class, int.class), engine, ((JSONObject) spec).getString("sysfs"), ((JSONObject) spec).getInt("devnum")));
            candidate.put(call(method(engineType, "isCandidate", deviceType), engine, device));
        }
        var plan = new JSONArray();
        for (var decision : (List<?>) call(method(engineType, "plan", List.class, Function.class, BiPredicate.class), engine, devices, stateOf, has)) {
            var item = (JSONObject) decisionJson(decision);
            item.put("sysfs", deviceType.getField("sysfs").get(decision.getClass().getField("device").get(decision)));
            item.put("where", call(method(decision.getClass(), "where"), decision));
            plan.put(item);
        }
        row.put("decisions", decisions).put("locked", locked).put("candidate", candidate).put("plan", plan);
        if (!test.isNull("next"))
            row.put("save_owes", call(method(engineType, "saveOwes", rulesType), engine, call(fromJson, null, test.getJSONObject("next"), null)).toString());
        row.put("recovers", new JSONArray().put(call(method(engineType, "recoversIdleDevices", boolean.class), engine, false))
            .put(call(method(engineType, "recoversIdleDevices", boolean.class), engine, true)));
    }

    static List<Object> iterate(JSONArray array) throws Exception {
        var out = new ArrayList<Object>();
        for (int i = 0; i < array.length(); ++i) out.add(array.get(i));
        return out;
    }

    static void scan(JSONObject test, JSONObject row) throws Throwable {
        var root = test.getString("root");
        var devicesRoot = root + "/sys/bus/usb/devices";
        var devRoot = root + "/dev/bus/usb";
        var inventory = type("UsbHostInventory").getConstructor(String.class, String.class).newInstance(devicesRoot, devRoot);
        var deviceType = type("UsbHostDevice");
        var out = new JSONArray();
        for (var device : (List<?>) call(method(inventory.getClass(), "scan"), inventory)) {
            var item = (JSONObject) call(method(deviceType, "toJson"), device);
            item.put("hub", call(method(deviceType, "isHub"), device));
            item.put("authorized", deviceType.getField("authorized").get(device));
            out.put(item);
        }
        row.put("devices", out);
        var reads = new JSONArray();
        for (var name : iterate(test.optJSONArray("read") == null ? new JSONArray() : test.getJSONArray("read"))) {
            var item = new JSONObject();
            try {
                var device = call(method(deviceType, "fromSysfs", File.class, String.class), null, new File(devicesRoot, (String) name), devRoot);
                item.put("device", call(method(deviceType, "toJson"), device));
            } catch (java.io.IOException e) {
                item.put("io_error", e.getMessage());
            }
            reads.put(item);
        }
        row.put("read", reads);
    }

    static void probe(JSONObject test, JSONObject row) throws Throwable {
        var root = test.getString("root") + "/sys/bus/usb/devices";
        var writerType = type("UsbProbe$Writer");
        var writes = new JSONArray();
        var writer = Proxy.newProxyInstance(writerType.getClassLoader(), new Class<?>[]{writerType}, (proxy, m, args) -> {
            if (m.getName().equals("probe")) writes.put(args[0]);
            return null;
        });
        var probeType = type("UsbProbe");
        var ctor = probeType.getDeclaredConstructor(File.class, writerType, long.class, long.class);
        ctor.setAccessible(true);
        var probe = ctor.newInstance(new File(root), writer, 50L, 10L);
        var results = new JSONArray();
        for (var op : iterate(test.getJSONArray("calls"))) {
            var args = (JSONArray) op;
            var name = args.getString(0);
            var arg = args.getString(1);
            Object value;
            switch (name) {
                case "interfacesOf": value = new JSONArray((List<?>) call(method(probeType, name, String.class), probe, arg)); break;
                default: value = call(method(probeType, name, String.class), probe, arg); break;
            }
            results.put(value);
        }
        row.put("results", results).put("writes", writes);
    }

    static void uevent(JSONObject test, JSONObject row) throws Throwable {
        var bytes = new java.io.ByteArrayOutputStream();
        for (var field : iterate(test.getJSONArray("fields"))) {
            bytes.write(((String) field).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bytes.write(0);
        }
        var data = bytes.toByteArray();
        var ueventType = type("UsbUevent");
        var event = call(method(ueventType, "parse", byte[].class, int.class), null, data, data.length);
        if (event == null) {
            row.put("event", JSONObject.NULL);
            return;
        }
        var out = new JSONObject();
        for (var field : new String[]{"action", "name", "devtype", "driver"}) out.put(field, RootOracle.field(ueventType, field).get(event));
        out.put("driver_change", call(method(ueventType, "isDriverChange"), event));
        out.put("text", event.toString());
        row.put("event", out);
    }

    static void control(JSONObject test, JSONObject row) throws Throwable {
        var controlType = type("CrosvmUsbControl");
        var text = test.optString("text", "");
        try {
            switch (test.getString("kind")) {
                case "ok_port":
                    row.put("port", call(method(controlType, "parseAttach", String.class), null, text));
                    break;
                case "list": {
                    var out = new JSONArray();
                    for (var entry : (List<?>) call(method(controlType, "parseList", String.class), null, text)) {
                        var entryType = entry.getClass();
                        out.put(new JSONObject().put("port", entryType.getField("port").get(entry))
                            .put("vid", entryType.getField("vid").get(entry)).put("pid", entryType.getField("pid").get(entry)));
                    }
                    row.put("entries", out);
                    break;
                }
                case "first_error":
                    row.put("line", nul(call(method(controlType, "firstErrorLine", String.class), null, text)));
                    break;
                case "failure_message":
                    row.put("message", call(method(controlType, "attachFailureMessage", String.class, String.class, String.class), null,
                        test.getString("token"), test.getString("stderr"), test.getString("vmm_log")));
                    break;
                case "require_output": {
                    var outputType = type("CrosvmUsbControl$Output");
                    var ctor = outputType.getDeclaredConstructor(String.class, String.class);
                    ctor.setAccessible(true);
                    call(method(controlType, "requireOutput", outputType), null, ctor.newInstance(text, test.getString("stderr")));
                    row.put("passed", true);
                    break;
                }
                default:
                    throw new IllegalArgumentException("control kind");
            }
        } catch (Throwable error) {
            if (error.getClass().getName().equals(USB + "UsbControlException")) row.put("token", error.getClass().getField("token").get(error));
            else if (error.getClass().getName().equals(USB + "UsbVmmUnreachableException")) row.put("unreachable", error.getMessage());
            else if (error instanceof java.io.IOException) row.put("io_error", error.getMessage());
            else throw error;
        }
    }

    static void leftovers(JSONObject test, JSONObject row) throws Throwable {
        var type = type("UsbLeftovers");
        var ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        var left = ctor.newInstance();
        var results = new JSONArray();
        for (var op : iterate(test.getJSONArray("ops"))) {
            var args = (JSONArray) op;
            switch (args.getString(0)) {
                case "leave": call(method(type, "leave", String.class, String.class), left, args.getString(1), args.getString(2)); results.put(JSONObject.NULL); break;
                case "contains": results.put(call(method(type, "contains", String.class), left, args.getString(1))); break;
                case "left_by": results.put(new JSONArray((List<?>) call(method(type, "leftBy", String.class), left, args.getString(1)))); break;
                case "take": {
                    var names = new ArrayList<String>();
                    for (var name : iterate(args.getJSONArray(1))) names.add((String) name);
                    var taken = (Map<?, ?>) call(method(type, "take", java.util.Collection.class), left, names);
                    var out = new JSONArray();
                    for (var e : taken.entrySet()) out.put(new JSONArray().put(e.getKey()).put(e.getValue()));
                    results.put(out);
                    break;
                }
                case "scan": {
                    var drivers = new HashMap<String, String>();
                    var spec = args.getJSONObject(1);
                    for (var it = spec.keys(); it.hasNext(); ) { var k = it.next(); drivers.put(k, spec.getString(k)); }
                    var scan = call(method(type, "scan", Map.class), left, drivers);
                    var reclaimed = new JSONArray();
                    for (var e : ((LinkedHashMap<?, ?>) RootOracle.get(scan, "reclaimed")).entrySet())
                        reclaimed.put(new JSONArray().put(e.getKey()).put(e.getValue()));
                    results.put(new JSONObject().put("free", new JSONArray((List<?>) RootOracle.get(scan, "free"))).put("reclaimed", reclaimed));
                    break;
                }
                case "forget_device": results.put(new JSONArray((List<?>) call(method(type, "forgetDevice", String.class), left, args.getString(1)))); break;
                case "size": results.put(call(method(type, "size"), left)); break;
                case "device_of": results.put(call(method(type, "deviceOf", String.class), null, args.getString(1))); break;
                default: throw new IllegalArgumentException("leftovers op");
            }
        }
        row.put("results", results);
    }

    /** A ServerContext whose VM store holds the fixture's VMs and whose manager reads the fixture's tree. */
    static Object context(JSONObject test) throws Throwable {
        var context = RootOracle.allocate(Class.forName(SERVER + "ServerContext"));
        var storeType = Class.forName("cn.classfun.droidvm.daemon.vm.VMInstanceStore");
        var store = RootOracle.allocate(storeType);
        RootOracle.set(store, "dataMap", new ArrayList<>());
        RootOracle.set(store, "idMap", new HashMap<>());
        RootOracle.set(store, "nameMap", new HashMap<>());
        RootOracle.set(store, "context", context);
        var instanceType = Class.forName("cn.classfun.droidvm.daemon.vm.VMInstance");
        var add = storeType.getMethod("add", Class.forName("cn.classfun.droidvm.lib.store.base.DataConfig"));
        for (var spec : iterate(test.optJSONArray("vm_list") == null ? new JSONArray() : test.getJSONArray("vm_list"))) {
            var vm = (JSONObject) spec;
            var obj = new JSONObject(vm.getJSONObject("config").toString()).put("id", vm.getString("id")).put("name", vm.getString("name")).put("state", vm.getString("state"));
            add.invoke(store, instanceType.getConstructor(storeType, JSONObject.class).newInstance(store, obj));
        }
        RootOracle.set(context, "vms", store);
        var root = test.getString("root");
        var managerType = type("UsbPassthroughManager");
        var manager = RootOracle.allocate(managerType);
        RootOracle.set(manager, "attachments", new HashMap<>());
        RootOracle.set(manager, "stopEpochs", new HashMap<>());
        var leftoversCtor = type("UsbLeftovers").getDeclaredConstructor();
        leftoversCtor.setAccessible(true);
        RootOracle.set(manager, "leftovers", leftoversCtor.newInstance());
        RootOracle.set(manager, "lock", new Object());
        RootOracle.set(manager, "inventory", type("UsbHostInventory").getConstructor(String.class, String.class)
            .newInstance(root + "/sys/bus/usb/devices", root + "/dev/bus/usb"));
        // Every drivers_probe goes through this writer, which writes nothing.
        var writerType = type("UsbProbe$Writer");
        var writer = Proxy.newProxyInstance(writerType.getClassLoader(), new Class<?>[]{writerType}, (proxy, m, args) -> null);
        var probeCtor = type("UsbProbe").getDeclaredConstructor(File.class, writerType, long.class, long.class);
        probeCtor.setAccessible(true);
        RootOracle.set(manager, "probe", probeCtor.newInstance(new File(root + "/sys/bus/usb/devices"), writer, 50L, 10L));
        var engine = type("UsbRuleEngine").getConstructor().newInstance();
        var rules = call(method(type("UsbRulesFile"), "load", File.class), null, new File(root + "/files/usb_rules.json"));
        method(engine.getClass(), "setRules", type("UsbRules")).invoke(engine, rules);
        RootOracle.set(manager, "engine", engine);
        RootOracle.set(manager, "context", context);
        RootOracle.set(context, "usb", manager);
        // Attachments as UsbPassthroughManager.attach files them.
        var attachmentType = type("UsbPassthroughManager$Attachment");
        var attachmentCtor = attachmentType.getDeclaredConstructor(String.class, String.class, int.class, String.class, String.class,
            String.class, String.class, String.class, type("UsbRuleEngine$Decision"));
        attachmentCtor.setAccessible(true);
        var deviceType = type("UsbHostDevice");
        var xhci = Class.forName("cn.classfun.droidvm.lib.store.vm.VMXhciConfig");
        var itemType = Class.forName("cn.classfun.droidvm.lib.store.base.DataItem");
        @SuppressWarnings("unchecked")
        var attachments = (Map<String, Object>) RootOracle.get(manager, "attachments");
        for (var spec : iterate(test.optJSONArray("attachments") == null ? new JSONArray() : test.getJSONArray("attachments"))) {
            var a = (JSONObject) spec;
            var inst = storeType.getMethod("findById", String.class).invoke(store, a.getString("vm"));
            var device = call(method(deviceType, "fromSysfs", File.class, String.class), null, new File(root + "/sys/bus/usb/devices", a.getString("sysfs")), root + "/dev/bus/usb");
            var controller = xhci.getMethod("findController", itemType, String.class).invoke(null, RootOracle.get(inst, "item"), null);
            var controllerId = (String) controller.getClass().getMethod("getControllerId").invoke(controller);
            attachments.put(a.getString("sysfs"), attachmentCtor.newInstance(
                instanceType.getMethod("getId").invoke(inst).toString(), instanceType.getMethod("getName").invoke(inst), a.getInt("port"), a.getString("sysfs"),
                deviceType.getField("vid").get(device), deviceType.getField("pid").get(device), deviceType.getField("node").get(device),
                controllerId.isEmpty() ? null : controllerId, null));
        }
        return context;
    }

    /** The ipc/usb handlers against the fixture's context. */
    static void handlers(JSONObject test, JSONObject row) throws Throwable {
        var context = context(test);
        var server = RootOracle.allocate(Class.forName(SERVER + "Server"));
        RootOracle.set(server, "context", context);
        var client = RootOracle.allocate(Class.forName(SERVER + "ClientHandler"));
        RootOracle.set(client, "server", server);
        var requestType = Class.forName(SERVER + "ClientRequest");
        var responseType = Class.forName(SERVER + "ClientResponse");
        var results = new JSONArray();
        for (var spec : iterate(test.getJSONArray("calls"))) {
            var call = (JSONArray) spec;
            var command = call.getString(0);
            var params = new JSONObject(call.getJSONObject(1).toString());
            var request = RootOracle.allocate(requestType);
            RootOracle.set(request, "params", params);
            RootOracle.set(request, "command", command);
            RootOracle.set(request, "client", client);
            RootOracle.set(request, "response", responseType.getConstructor(requestType).newInstance(request));
            var name = new StringBuilder();
            for (var part : command.split("_")) name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            var handler = Class.forName(IPC + name + "Handler").getConstructor().newInstance();
            var out = new JSONObject();
            try {
                call(method(handler.getClass(), "handle", requestType), handler, request);
                out.put("result", requestType.getMethod("res").invoke(request));
            } catch (Throwable error) {
                if (!refused(error, out)) out.put("internal", true);
            }
            results.put(out);
        }
        row.put("results", results);
    }

    static void evaluate(JSONObject test, JSONObject row) throws Throwable {
        switch (test.getString("op")) {
            case "usb_rules": rules(test, row); break;
            case "usb_engine": engine(test, row); break;
            case "usb_scan": scan(test, row); break;
            case "usb_probe": probe(test, row); break;
            case "usb_uevent": uevent(test, row); break;
            case "usb_control": control(test, row); break;
            case "usb_leftovers": leftovers(test, row); break;
            case "usb_handlers": handlers(test, row); break;
            default: throw new IllegalArgumentException("unknown op");
        }
    }
}
