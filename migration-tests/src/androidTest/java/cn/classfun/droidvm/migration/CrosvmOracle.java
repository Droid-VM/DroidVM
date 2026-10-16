// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Builds the production crosvm argv and environment for one fixture, as
 * CrosvmBackendInstance.start() would after VMInstance prepared it. The host
 * is the real one; only per-launch state is injected. See RootOracle.
 */
final class CrosvmOracle {
    private static final String VM = "cn.classfun.droidvm.lib.store.vm.";

    private CrosvmOracle() {
    }

    /** A VMInstance without its store, carrying the fixture's config and prepared boot plan. */
    static Object instance(JSONObject test) throws Exception {
        var configType = Class.forName(VM + "VMConfig");
        var config = configType.getConstructor(JSONObject.class).newInstance(test.getJSONObject("config"));
        // A VMInstance without its store: BootPlan.of reads the prepared plan from it.
        var instanceType = Class.forName("cn.classfun.droidvm.daemon.vm.VMInstance");
        var instance = RootOracle.allocate(instanceType);
        RootOracle.set(instance, "item", RootOracle.get(config, "item"));
        var boot = test.getJSONObject("boot");
        var planType = Class.forName("cn.classfun.droidvm.daemon.vm.BootPlan");
        Object plan;
        if (boot.optBoolean("uefi", false)) {
            var ctor = planType.getDeclaredConstructor(String.class, String.class, boolean.class);
            ctor.setAccessible(true);
            plan = ctor.newInstance(boot.optString("firmware", ""), boot.optString("vars", ""), boot.optBoolean("vars_enabled", false));
        } else {
            var ctor = planType.getDeclaredConstructor(String.class, String.class, String.class, String.class, boolean.class);
            ctor.setAccessible(true);
            plan = ctor.newInstance(boot.optString("kernel", ""), boot.optString("initrd", ""), boot.optString("cmdline", ""), null, false);
        }
        RootOracle.set(instance, "bootPlan", plan);
        return instance;
    }

    /** A ServerContext holding only the fixture's app config. */
    static Object context(JSONObject test) throws Exception {
        var item = Class.forName("cn.classfun.droidvm.lib.store.base.DataItem");
        var context = RootOracle.allocate(Class.forName("cn.classfun.droidvm.daemon.server.ServerContext"));
        RootOracle.set(context, "appConfig", item.getMethod("from", Object.class).invoke(null, test.optJSONObject("app_config") == null ? new JSONObject() : test.getJSONObject("app_config")));
        return context;
    }

    /** The backend constructed the way VMInstance constructs it. */
    static Object backend(String name, Object context, Object instance) throws Exception {
        var backendType = Class.forName("cn.classfun.droidvm.daemon.vm.backend." + name);
        return backendType.getConstructor(Class.forName("cn.classfun.droidvm.daemon.server.ServerContext"), Class.forName(VM + "VMConfig")).newInstance(context, instance);
    }

    /** prepareProcess (or only its configured part) followed by the backend's own env methods. */
    static JSONArray environment(JSONObject test, Object backend, String... methods) throws Exception {
        var builderType = Class.forName("cn.classfun.droidvm.lib.natives.NativeProcess$Builder");
        var builder = builderType.getConstructor(String[].class).newInstance((Object) new String[]{"vmm"});
        var baseType = Class.forName("cn.classfun.droidvm.daemon.vm.VMBackendInstance");
        // prepareProcess also drops caches and compacts memory; run it for one fixture only.
        var prepare = baseType.getDeclaredMethod(test.optBoolean("prepare_process", false) ? "prepareProcess" : "applyConfiguredEnvironment", builderType);
        prepare.setAccessible(true);
        prepare.invoke(backend, builder);
        for (var name : methods) {
            var method = backend.getClass().getDeclaredMethod(name, builderType);
            method.setAccessible(true);
            method.invoke(backend, builder);
        }
        var env = new JSONArray();
        for (var entry : (List<?>) RootOracle.get(builder, "envList")) {
            var text = (String) entry;
            int eq = text.indexOf('=');
            env.put(new JSONArray().put(text.substring(0, eq)).put(text.substring(eq + 1)));
        }
        return env;
    }

    static void evaluate(JSONObject test, JSONObject row) throws Exception {
        var item = Class.forName("cn.classfun.droidvm.lib.store.base.DataItem");
        var instance = instance(test);
        var backend = backend("CrosvmBackendInstance", context(test), instance);
        var backendType = backend.getClass();
        var run = test.optJSONObject("run") == null ? new JSONObject() : test.getJSONObject("run");
        if (!run.isNull("control_socket_path")) RootOracle.set(backend, "controlSocketPath", run.optString("control_socket_path"));
        if (!run.isNull("gpu_cgroup_path")) RootOracle.set(backend, "gpuCgroupPath", run.optString("gpu_cgroup_path"));
        // resolveSerialPorts: one entry per configured port; pipes are not created here.
        var serialType = Class.forName(VM + "VMSerialConfig");
        var resolvedType = Class.forName("cn.classfun.droidvm.daemon.vm.backend.CrosvmBackendInstance$ResolvedSerial");
        var resolvedCtor = resolvedType.getDeclaredConstructor(serialType);
        resolvedCtor.setAccessible(true);
        @SuppressWarnings("unchecked")
        var resolved = (List<Object>) RootOracle.get(backend, "resolvedSerials");
        var ports = (List<?>) serialType.getMethod("listOf", item).invoke(null, RootOracle.get(instance, "item"));
        var serials = run.optJSONArray("serials");
        for (int i = 0; i < ports.size(); ++i) {
            var rs = resolvedCtor.newInstance(ports.get(i));
            var spec = serials == null ? null : serials.optJSONObject(i);
            if (spec != null && !spec.isNull("acm_dev_path")) RootOracle.set(rs, "acmDevPath", spec.getString("acm_dev_path"));
            resolved.add(rs);
        }
        var build = backendType.getDeclaredMethod("buildCommand");
        build.setAccessible(true);
        row.put("argv", new JSONArray((List<?>) build.invoke(backend)));
        row.put("env", environment(test, backend, "applyGfxstreamEnv", "applyDisplayBlitEnv", "applyGpuRtPrioEnv"));
    }
}
