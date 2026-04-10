package cn.classfun.droidvm.daemon.vm;

import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.utils.NetUtils.generateRandomAvailablePort;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.generateRandomPassword;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import cn.classfun.droidvm.daemon.console.ConsoleStream;
import cn.classfun.droidvm.daemon.network.backend.LinuxNetwork;
import cn.classfun.droidvm.lib.natives.NativeProcess;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.NetworkState;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.lib.utils.JsonUtils;

public final class VMInstance extends VMConfig {
    private static final String TAG = "VMInstance";
    private VMState state = VMState.STOPPED;
    private NativeProcess process;
    private boolean stoppedByUser = false;
    private int exitCode = -1;
    private final VMBackendInstance backendInstance;
    private Thread workerThread;
    private final VMInstanceStore store;

    public interface VMEventCallback {
        @SuppressWarnings("unused")
        void onVMEvent(@NonNull String vmId, @NonNull String event, @NonNull JSONObject data);
    }

    public VMInstance(@NonNull VMInstanceStore store) {
        super();
        this.store = store;
        this.backendInstance = store.backend.create(this);
    }

    public VMInstance(@NonNull VMInstanceStore store, @NonNull JSONObject obj) throws JSONException {
        super(obj);
        this.store = store;
        this.backendInstance = store.backend.create(this);
        if (obj.has("state")) state = VMState.valueOf(obj.getString("state"));
    }

    @NonNull
    public VMState getState() {
        return state;
    }

    public long getPid() {
        if (process != null) return process.pid();
        return -1;
    }

    private void setState(@NonNull VMState newState) {
        this.state = newState;
        Log.i(TAG, fmt("VM %s [%s] -> %s", getName(), getId().toString(), newState.name()));
        fireEvent("state", null);
    }

    private void fireEvent(@NonNull String event, @Nullable JSONObject extra) {
        var cb = store.eventCallback;
        if (cb == null) return;
        try {
            var data = new JSONObject();
            data.put("vm_id", getId().toString());
            data.put("vm_name", getName());
            data.put("state", state.name().toLowerCase());
            data.put("event", event);
            if (event.equals("exited")) {
                data.put("exit_code", exitCode);
                var sio = backendInstance.streams.get("stdio");
                if (sio != null) data.put("stdio", sio.getBuffer());
            }
            if (extra != null) JsonUtils.mergeJSONObject(data, extra);
            cb.onVMEvent(getId().toString(), event, data);
        } catch (JSONException e) {
            Log.w(TAG, "Failed to build event data", e);
        }
    }

    public boolean runControlCommand(@NonNull String command) {
        if (backendInstance == null) {
            Log.w(TAG, fmt("No backend instance for VM %s, cannot run %s", getId().toString(), command));
            return false;
        }
        return backendInstance.runControlCommand(command) == 0;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean start() {
        if (state != VMState.STOPPED) {
            Log.w(TAG, fmt("VM %s is not stopped (state=%s), cannot start", getId(), state.name()));
            return false;
        }
        joinThreads(1000);
        if (!setupTaps()) return false;
        if (item.optBoolean("vnc_enabled", false)) resolveVncConfig();
        stoppedByUser = false;
        exitCode = -1;
        setState(VMState.STARTING);
        var vmIdStr = getId().toString();
        workerThread = new Thread(this::runVM, fmt("VM-%s", vmIdStr));
        workerThread.setDaemon(true);
        workerThread.start();
        Log.i(TAG, fmt("Start requested for VM: %s [%s] via %s", getName(), vmIdStr, store.backend.name()));
        return true;
    }

    private void setupTap(int index, List<String> createdTaps, @NonNull DataItem netCfg, String vmId) {
        var netId = netCfg.optString("network_id", "");
        if (netId == null || netId.isEmpty()) return;
        var netInst = store.networkStore.findById(UUID.fromString(netId));
        if (netInst == null) throw new RuntimeException(fmt("Network %s not found", netId));
        var br = netInst.item.optString("bridge_name", "");
        var netState = netInst.getState();
        if (netState != NetworkState.RUNNING) {
            Log.i(TAG, fmt("Network %s is %s, attempting to start it", netId, netState));
            if (!netInst.start())
                throw new RuntimeException(fmt("Failed to start network %s", netId));
            netState = netInst.getState();
            if (netState != NetworkState.RUNNING)
                throw new RuntimeException(fmt("Network %s still not running after start (state=%s)", netId, netState));
            Log.i(TAG, fmt("Network %s started successfully", netId));
        }
        var tapName = requireNonNull(netCfg.optString("tap_name", ""));
        if (tapName.isEmpty()) {
            tapName = fmt("vm%s-%d", vmId.substring(0, 8), index);
            netCfg.set("tap_name", tapName);
        }
        var bridge = store.networkStore.backend;
        if (bridge.isInterfaceExists(tapName)) bridge.deleteTap(tapName);
        if (!bridge.createTap(tapName))
            throw new RuntimeException(fmt("Failed to create TAP %s", tapName));
        createdTaps.add(tapName);
        if (!netInst.addInterface(tapName))
            throw new RuntimeException(fmt("Failed to add TAP %s to bridge %s", tapName, br));
        var tapMac = requireNonNull(netCfg.optString("mac_address", ""));
        if (!tapMac.isEmpty() && !bridge.setMacAddress(tapName, tapMac))
            Log.w(TAG, fmt("Failed to set MAC %s on TAP %s", tapMac, tapName));
        if (!bridge.setLinkState(tapName, true))
            Log.w(TAG, fmt("Failed to bring up TAP %s", tapName));
        Log.i(TAG, fmt("TAP %s attached to bridge %s for VM %s", tapName, br, vmId));
    }

    private boolean setupTaps() {
        var nets = item.opt("networks", DataItem.newArray());
        if (nets.isEmpty()) return true;
        if (store.networkStore == null) {
            Log.e(TAG, fmt("VM %s has networks but no network store", getId()));
            return false;
        }
        var vmId = getId().toString();
        var createdTaps = new ArrayList<String>();
        try {
            var arr = nets.asArray();
            for (int i = 0; i < arr.size(); i++) {
                setupTap(i, createdTaps, arr.get(i), vmId);
            }
        } catch (Exception e) {
            Log.e(TAG, fmt("Error setting up TAP for VM %s: %s", vmId, e.getMessage()), e);
            cleanupCreatedTaps(createdTaps);
            return false;
        }
        return true;
    }

    private void cleanupCreatedTaps(@NonNull List<String> taps) {
        var net = store.networkStore.backend;
        for (var tap : taps) {
            net.removeInterface(tap);
            net.deleteTap(tap);
        }
    }

    private void resolveVncConfig() {
        if (item.optLong("vnc_port", -1) <= 0) {
            int port = generateRandomAvailablePort();
            if (port > 0) {
                item.set("vnc_port", port);
                Log.i(TAG, fmt("VM %s: auto-assigned VNC port %d", getName(), port));
            } else {
                Log.e(TAG, fmt("VM %s: failed to find available VNC port", getName()));
            }
        }
        if (item.optBoolean("vnc_password_auth", false) && item.optString("vnc_password", "").isEmpty()) {
            var password = generateRandomPassword(8);
            item.set("vnc_password", password);
            Log.i(TAG, fmt("VM %s: auto-generated VNC password", getName()));
        }
    }

    public boolean stop() {
        if (state != VMState.RUNNING && state != VMState.STARTING && state != VMState.SUSPENDED) {
            Log.w(TAG, fmt("Cannot stop VM %s: state=%s", getId(), state.name()));
            return false;
        }
        stoppedByUser = true;
        setState(VMState.STOPPING);
        if (backendInstance != null && backendInstance.hasControlSocket()) {
            Log.i(TAG, fmt("Stopping VM %s via control socket", getName()));
            if (runControlCommand("stop")) return true;
            Log.w(TAG, fmt("Control stop failed for VM %s, falling back to destroy", getName()));
        }
        if (process != null && process.isAlive()) {
            Log.i(TAG, fmt("Destroying VM %s process", getName()));
            process.destroy();
        }
        return true;
    }

    public boolean suspend() {
        if (state != VMState.RUNNING) {
            Log.w(TAG, fmt("Cannot suspend VM %s: state=%s", getId(), state.name()));
            return false;
        }
        if (runControlCommand("suspend")) {
            setState(VMState.SUSPENDED);
            return true;
        }
        return false;
    }

    public boolean resume() {
        if (state != VMState.SUSPENDED) {
            Log.w(TAG, fmt("Cannot resume VM %s: state=%s", getId(), state.name()));
            return false;
        }
        if (runControlCommand("resume")) {
            setState(VMState.RUNNING);
            return true;
        }
        return false;
    }

    @NonNull
    public String getStreamBuffer(@NonNull String streamName) {
        var stream = backendInstance.streams.get(streamName);
        return stream != null ? stream.getBuffer() : "";
    }

    @NonNull
    public List<String> getStreamNames() {
        return new ArrayList<>(backendInstance.streams.keySet());
    }

    public boolean writeStream(@NonNull String streamName, @NonNull String data) {
        var stream = backendInstance.streams.get(streamName);
        if (stream == null) return false;

        OutputStream os = stream.getOutputStream();
        if (os == null) {
            Log.w(TAG, fmt("Stream '%s' on VM %s is read-only", streamName, getId()));
            return false;
        }
        try {
            os.write(data.getBytes(StandardCharsets.UTF_8));
            os.flush();
            return true;
        } catch (Exception e) {
            Log.w(TAG, fmt("writeStream failed: %s", e.getMessage()));
            return false;
        }
    }

    public void joinThreads(long timeoutMs) {
        if (workerThread != null && workerThread.isAlive()) {
            try {
                workerThread.join(timeoutMs);
            } catch (InterruptedException ignored) {
            }
        }
        for (var stream : backendInstance.streams.values()) {
            var reader = stream.getReaderThread();
            if (reader != null && reader.isAlive()) {
                try {
                    reader.join(timeoutMs);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void runVM() {
        var vmId = getId().toString();
        var result = backendInstance.start();
        if (!result.isSuccess()) {
            exitCode = -1;
            setState(VMState.STOPPED);
            fireEvent("exited", null);
            return;
        }
        process = result.getProcess();
        for (var entry : backendInstance.streams.entrySet()) {
            var name = entry.getKey();
            var stream = entry.getValue();
            if (stream.isReadable() && stream.getInputStream() != null) {
                startReaderThread(vmId, name, stream);
            }
        }
        setState(VMState.RUNNING);
        int code = process.waitFor();
        for (var stream : backendInstance.streams.values()) {
            var reader = stream.getReaderThread();
            if (reader != null && reader.isAlive()) {
                try {
                    reader.join(5000);
                } catch (InterruptedException ignored) {
                }
            }
        }
        process = null;
        exitCode = code;
        for (var stream : backendInstance.streams.values()) {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
        if (stoppedByUser && code != 0) {
            Log.i(TAG, fmt("VM %s stopped by user", getName()));
            exitCode = 0;
        } else {
            Log.i(TAG, fmt("VM %s exited with code %d", getName(), code));
        }
        cleanupTap();
        backendInstance.cleanup();
        setState(VMState.STOPPED);
        fireEvent("exited", null);
    }

    private void cleanupTap() {
        var nets = requireNonNull(item.opt("networks", DataItem.newArray()));
        if (nets.isEmpty()) return;
        var bridge = new LinuxNetwork();
        for (var iter : nets) {
            var tapVal = iter.getValue().optString("tap_name", "");
            if (tapVal == null || tapVal.isEmpty()) continue;
            Log.i(TAG, fmt("Cleaning up TAP %s for VM %s", tapVal, getName()));
            bridge.removeInterface(tapVal);
            bridge.deleteTap(tapVal);
        }
    }

    private void startReaderThread(@NonNull String vmId, @NonNull String streamName, @NonNull ConsoleStream stream) {
        var is = stream.getInputStream();
        if (is == null) return;
        var reader = new Thread(() -> readStream(vmId, streamName, is), fmt("Reader-%s-%s", vmId, streamName));
        reader.setDaemon(true);
        stream.setReaderThread(reader);
        reader.start();
    }

    private void addStreamData(@NonNull ConsoleStream stream, @NonNull String data) {
        stream.appendBuffer(data);
        try {
            var extra = new JSONObject();
            extra.put("stream", stream.getName());
            extra.put("data", data);
            fireEvent("output", extra);
        } catch (JSONException ignored) {
        }
    }
    private void addStreamData(@NonNull String name, @NonNull String data) {
        var stream = backendInstance.streams.get(name);
        if (stream != null) addStreamData(stream, data);
    }

    private void readStream(@NonNull String vmId, @NonNull String streamName, @NonNull InputStream inputStream) {
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = inputStream.read(buf)) > 0) {
                var chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                addStreamData(streamName, chunk);
                if (streamName.equals("stdout") || streamName.equals("stderr"))
                    addStreamData("stdio", chunk);
            }
        } catch (Exception e) {
            Log.d(TAG, fmt("Stream reader %s for VM %s ended: %s", streamName, vmId, e.getMessage()));
        }
    }

    @NonNull
    public JSONObject toInfoJson() throws JSONException {
        var obj = item.toJson();
        obj.put("state", state.name());
        obj.put("pid", getPid());
        var streamNames = new JSONArray();
        for (var name : backendInstance.streams.keySet())
            streamNames.put(name);
        obj.put("streams", streamNames);
        return obj;
    }

    @NonNull
    static VMInstance getVMInstance(@NonNull VMInstanceStore store, @NonNull VMConfig config, @NonNull UUID vmId) {
        var inst = new VMInstance(store);
        inst.item.set(config.item);
        inst.setId(vmId.toString());
        return inst;
    }
}
