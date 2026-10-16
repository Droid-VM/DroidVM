// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The production ExportHandler/ImportHandler and VMExportTask/VMImportTask, run
 * as root through app_process against a ServerContext whose stores hold only
 * the step's VMs and networks. Broadcasts reach one captured client.
 * Usage: CLASSPATH=app.apk:test.apk app_process / cn.classfun.droidvm.migration.PkgOracle steps report
 */
public final class PkgOracle {
    private static final String SERVER = "cn.classfun.droidvm.daemon.server.";
    private static final String STORE = "cn.classfun.droidvm.lib.store.";

    private PkgOracle() {
    }

    /** Decodes Protocol frames as they are written. */
    static final class Capture extends OutputStream {
        final LinkedBlockingQueue<JSONObject> events = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            pending.write(b);
            drain();
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            pending.write(b, off, len);
            drain();
        }

        private void drain() {
            var bytes = pending.toByteArray();
            int offset = 0;
            while (bytes.length - offset >= 4) {
                int length = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (bytes.length - offset - 4 < length) break;
                int text = length > 0 && bytes[offset + 4 + length - 1] == 0 ? length - 1 : length;
                try {
                    events.add(new JSONObject(new String(bytes, offset + 4, text, StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                offset += 4 + length;
            }
            pending.reset();
            pending.write(bytes, offset, bytes.length - offset);
        }
    }

    static Object store(String type, Object context) throws Exception {
        var store = RootOracle.allocate(Class.forName(type));
        RootOracle.set(store, "dataMap", new ArrayList<>());
        RootOracle.set(store, "idMap", new HashMap<>());
        RootOracle.set(store, "nameMap", new HashMap<>());
        RootOracle.set(store, "ioLock", new ReentrantLock());
        RootOracle.set(store, "context", context);
        return store;
    }

    static List<Object> items(Object store) throws Exception {
        var out = new ArrayList<Object>();
        int size = (int) store.getClass().getMethod("size").invoke(store);
        for (int i = 0; i < size; i++) out.add(store.getClass().getMethod("get", int.class).invoke(store, i));
        return out;
    }

    static JSONArray dump(Object store) throws Exception {
        var out = new JSONArray();
        for (var item : items(store)) out.put(item.getClass().getMethod("toJson").invoke(item));
        return out;
    }

    static JSONObject step(JSONObject test) throws Exception {
        var context = RootOracle.allocate(Class.forName(SERVER + "ServerContext"));
        var vms = store("cn.classfun.droidvm.daemon.vm.VMInstanceStore", context);
        var networks = store("cn.classfun.droidvm.daemon.network.NetworkInstanceStore", context);
        var vmType = Class.forName("cn.classfun.droidvm.daemon.vm.VMInstance");
        var netType = Class.forName("cn.classfun.droidvm.daemon.network.NetworkInstance");
        var add = Class.forName(STORE + "base.DataStore").getMethod("add", Class.forName(STORE + "base.DataConfig"));
        var seededNets = test.optJSONArray("networks");
        if (seededNets != null) for (int i = 0; i < seededNets.length(); i++)
            add.invoke(networks, netType.getConstructor(networks.getClass(), JSONObject.class)
                .newInstance(networks, new JSONObject(seededNets.getJSONObject(i).toString())));
        vms.getClass().getMethod("setNetworkStore", networks.getClass()).invoke(vms, networks);
        var seededVms = test.optJSONArray("vms");
        if (seededVms != null) for (int i = 0; i < seededVms.length(); i++)
            add.invoke(vms, vmType.getConstructor(vms.getClass(), JSONObject.class)
                .newInstance(vms, new JSONObject(seededVms.getJSONObject(i).toString())));
        RootOracle.set(context, "vms", vms);
        RootOracle.set(context, "networks", networks);
        RootOracle.set(context, "exportTasks", new ConcurrentHashMap<>());
        RootOracle.set(context, "importTasks", new ConcurrentHashMap<>());
        var itemType = Class.forName(STORE + "base.DataItem");
        var app = test.optJSONObject("app_config");
        RootOracle.set(context, "appConfig", itemType.getMethod("from", Object.class).invoke(null, app == null ? new JSONObject() : app));
        var server = RootOracle.allocate(Class.forName(SERVER + "Server"));
        RootOracle.set(server, "context", context);
        var capture = new Capture();
        var client = RootOracle.allocate(Class.forName(SERVER + "ClientHandler"));
        RootOracle.set(client, "server", server);
        RootOracle.set(client, "proto", Class.forName("cn.classfun.droidvm.lib.daemon.Protocol")
            .getConstructor(InputStream.class, OutputStream.class).newInstance(InputStream.nullInputStream(), capture));
        var clients = new ArrayList<Object>();
        clients.add(client);
        RootOracle.set(server, "clients", clients);
        var requestType = Class.forName(SERVER + "ClientRequest");
        var responseType = Class.forName(SERVER + "ClientResponse");
        var request = RootOracle.allocate(requestType);
        var command = test.getString("command");
        RootOracle.set(request, "params", new JSONObject(test.getJSONObject("request").toString()));
        RootOracle.set(request, "command", command);
        RootOracle.set(request, "client", client);
        RootOracle.set(request, "response", responseType.getConstructor(requestType).newInstance(request));
        var handler = Class.forName("cn.classfun.droidvm.daemon.ipc.vm." + (command.equals("vm_export") ? "Export" : "Import") + "Handler")
            .getConstructor().newInstance();
        var row = new JSONObject();
        try {
            handler.getClass().getMethod("handle", requestType).invoke(handler, request);
        } catch (InvocationTargetException error) {
            var cause = error.getCause();
            if (cause != null && cause.getClass().getName().equals(SERVER + "RequestException")) row.put("refused", cause.getMessage());
            else row.put("internal", RootOracle.message(error));
            return row;
        }
        var response = (JSONObject) requestType.getMethod("res").invoke(request);
        row.put("response", response);
        var taskId = response.optString("task_id");
        var events = new JSONArray();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(test.optLong("timeout_s", 600));
        while (true) {
            var wait = deadline - System.nanoTime();
            var event = wait > 0 ? capture.events.poll(wait, TimeUnit.NANOSECONDS) : null;
            if (event == null) throw new IllegalStateException("task did not finish: " + taskId);
            var data = event.getJSONObject("data");
            if (!taskId.equals(data.optString("task_id"))) continue;
            events.put(data);
            var phase = data.optString("phase");
            if (phase.equals("done") || phase.equals("error")) break;
        }
        // The pool thread removes the task from its store after the last event.
        var tasks = (java.util.Map<?, ?>) RootOracle.get(context, command.equals("vm_export") ? "exportTasks" : "importTasks");
        for (int i = 0; i < 500 && !tasks.isEmpty(); i++) Thread.sleep(10);
        row.put("task_store_empty", tasks.isEmpty());
        row.put("events", events);
        row.put("vms", dump(vms));
        row.put("networks", dump(networks));
        return row;
    }

    public static void main(String[] args) throws Exception {
        // app_process has no app library path: load the APK's zstd-jni the way the app's linker would.
        var zstd = System.getenv("PKG_ORACLE_ZSTD_JNI");
        if (zstd != null) {
            System.load(zstd);
            Class.forName("com.github.luben.zstd.util.Native").getMethod("assumeLoaded").invoke(null);
        }
        var steps = new JSONArray(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
        var report = new JSONArray();
        for (int i = 0; i < steps.length(); i++) {
            var test = steps.getJSONObject(i);
            JSONObject row;
            try {
                row = step(test);
                row.put("status", "ok");
            } catch (Throwable error) {
                row = new JSONObject().put("status", "error").put("message", RootOracle.message(error));
            }
            row.put("name", test.getString("name"));
            report.put(row);
            System.out.println("step " + test.getString("name") + " " + row.optString("status"));
        }
        Files.writeString(Path.of(args[1]), report.toString(), StandardCharsets.UTF_8);
        System.out.println("oracle rows " + report.length());
        System.exit(0);
    }
}
