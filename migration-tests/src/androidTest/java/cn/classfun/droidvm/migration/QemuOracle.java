// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Builds the production QEMU argv and environment for one fixture, as
 * QemuBackendInstance.start() would after VMInstance prepared it. The host is
 * the real one; only per-launch state is injected. See RootOracle.
 */
final class QemuOracle {
    private static final String VM = "cn.classfun.droidvm.lib.store.vm.";

    private QemuOracle() {
    }

    static void evaluate(JSONObject test, JSONObject row) throws Exception {
        var item = Class.forName("cn.classfun.droidvm.lib.store.base.DataItem");
        var instance = CrosvmOracle.instance(test);
        var config = RootOracle.get(instance, "item");
        // VMInstance.resolveVncConfig's result, written back the way it writes it.
        var run = test.optJSONObject("run") == null ? new JSONObject() : test.getJSONObject("run");
        var vnc = run.optJSONObject("vnc");
        if (vnc != null) {
            var screenType = Class.forName(VM + "VMScreenConfig");
            for (var ids = vnc.keys(); ids.hasNext(); ) {
                var id = ids.next();
                var screen = screenType.getMethod("find", item, String.class).invoke(null, config, id);
                if (screen == null) continue;
                var values = vnc.getJSONObject(id);
                if (values.opt("host") instanceof String) screenType.getMethod("setVncHost", String.class).invoke(screen, values.getString("host"));
                if (values.opt("port") instanceof Integer || values.opt("port") instanceof Long) screenType.getMethod("setVncPort", long.class).invoke(screen, values.getLong("port"));
                if (values.opt("password") instanceof String) screenType.getMethod("setVncPassword", String.class).invoke(screen, values.getString("password"));
            }
        }
        var backend = CrosvmOracle.backend("QemuBackendInstance", CrosvmOracle.context(test), instance);
        if ("start".equals(test.opt("run"))) {
            // The paths start() assigns before calling buildCommand.
            var runPath = (String) RootOracle.get(backend, "RUN_PATH");
            var name = String.valueOf(item.getMethod("optString", String.class, String.class).invoke(config, "name", null));
            var join = Class.forName("cn.classfun.droidvm.lib.utils.StringUtils").getMethod("pathJoin", String.class, String.class);
            RootOracle.set(backend, "qmpSocketPath", join.invoke(null, runPath, name + "-qmp.sock"));
            RootOracle.set(backend, "uartSocketPath", join.invoke(null, runPath, name + "-uart.sock"));
            if ((boolean) RootOracle.get(backend, "agentMode"))
                RootOracle.set(backend, "agentSocketPath", join.invoke(null, runPath, name + "-agent.sock"));
        } else {
            for (var field : new String[][]{{"qmp_socket_path", "qmpSocketPath"}, {"uart_socket_path", "uartSocketPath"}, {"agent_socket_path", "agentSocketPath"}})
                if (run.opt(field[0]) instanceof String) RootOracle.set(backend, field[1], run.getString(field[0]));
        }
        // getStraceFolder would recreate the folder under the production app's cache.
        RootOracle.set(backend, "stracePrepared", true);
        var build = backend.getClass().getDeclaredMethod("buildCommand");
        build.setAccessible(true);
        var argv = (List<?>) build.invoke(backend);
        // A null name reaches NativeProcess as a null argv element; start() cannot launch it.
        if (argv.contains(null)) throw new IllegalArgumentException("VM name is not a string");
        row.put("argv", new JSONArray(argv));
        row.put("env", CrosvmOracle.environment(test, backend));
    }
}
