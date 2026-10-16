// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs production daemon code as root through app_process, the way the legacy
 * daemon runs, so host-dependent code sees the same host as droidvmd.
 * Usage: CLASSPATH=app.apk:test.apk app_process / cn.classfun.droidvm.migration.RootOracle fixtures report
 */
public final class RootOracle {
    private RootOracle() {
    }

    /** Allocates an instance without running any constructor. */
    static Object allocate(Class<?> type) throws Exception {
        var unsafeType = Class.forName("sun.misc.Unsafe");
        var field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = field.get(null);
        return unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (var current = type; current != null; current = current.getSuperclass()) {
            try {
                var result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    static void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }

    static Object get(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    static String message(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error.getClass().getName() + ": " + error.getMessage();
    }

    /** VMNicConfig.sanitizedFor then validate against a NetworkConfig. */
    static void nic(JSONObject test, JSONObject row) throws Exception {
        var item = Class.forName("cn.classfun.droidvm.lib.store.base.DataItem");
        var nicType = Class.forName("cn.classfun.droidvm.lib.store.vm.VMNicConfig");
        var networkType = Class.forName("cn.classfun.droidvm.lib.store.network.NetworkConfig");
        var network = networkType.getConstructor(JSONObject.class).newInstance(test.getJSONObject("network"));
        var nic = nicType.getConstructor(item).newInstance(item.getMethod("from", Object.class).invoke(null, test.getJSONObject("nic")));
        var dropped = new java.util.ArrayList<String>();
        var sane = nicType.getMethod("sanitizedFor", networkType, java.util.List.class).invoke(nic, network, dropped);
        row.put("dropped", new JSONArray(dropped));
        row.put("sanitized", item.getMethod("toJson").invoke(nicType.getField("item").get(sane)));
        try {
            nicType.getMethod("validate", networkType).invoke(sane, network);
            row.put("valid", true);
        } catch (Throwable error) {
            row.put("valid", false).put("validate_message", raw(error));
        }
        row.put("validated", item.getMethod("toJson").invoke(nicType.getField("item").get(sane)));
    }

    /** BootPlan.resolve on a VMConfig; never reaches lbx in these fixtures. */
    static void boot(JSONObject test, JSONObject row) throws Exception {
        var configType = Class.forName("cn.classfun.droidvm.lib.store.vm.VMConfig");
        var config = configType.getConstructor(JSONObject.class).newInstance(test.getJSONObject("config"));
        var planType = Class.forName("cn.classfun.droidvm.daemon.vm.BootPlan");
        var override = test.isNull("override") ? null : test.getString("override");
        var plan = planType.getMethod("resolve", configType, String.class).invoke(null, config, override);
        var out = new JSONObject();
        for (var name : new String[]{"uefi", "firmware", "vars", "varsEnabled", "kernel", "initrd", "cmdline", "entryTitle", "entryFallback"}) {
            var value = planType.getField(name).get(plan);
            out.put(name, value == null ? JSONObject.NULL : value);
        }
        row.put("plan", out);
        row.put("config", configType.getMethod("toJson").invoke(config));
    }

    static String raw(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return String.valueOf(error.getMessage());
    }

    public static void main(String[] args) throws Exception {
        var fixtures = new JSONArray(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
        var report = new JSONArray();
        for (int i = 0; i < fixtures.length(); ++i) {
            var test = fixtures.getJSONObject(i);
            var row = new JSONObject().put("name", test.getString("name"));
            try {
                switch (test.getString("op")) {
                    case "ping":
                        var type = Class.forName("cn.classfun.droidvm.lib.store.vm.VMConfig");
                        var config = type.getConstructor(JSONObject.class).newInstance(new JSONObject());
                        row.put("config", type.getMethod("toJson").invoke(config));
                        row.put("uid", android.os.Process.myUid());
                        break;
                    case "crosvm":
                        CrosvmOracle.evaluate(test, row);
                        break;
                    case "nic":
                        nic(test, row);
                        break;
                    case "boot":
                        boot(test, row);
                        break;
                    default:
                        throw new IllegalArgumentException("unknown op");
                }
                row.put("status", "ok");
            } catch (Throwable error) {
                row.put("status", "error").put("message", message(error));
            }
            report.put(row);
        }
        Files.writeString(Path.of(args[1]), report.toString(), StandardCharsets.UTF_8);
        System.out.println("oracle rows " + report.length());
        System.exit(0);
    }
}
