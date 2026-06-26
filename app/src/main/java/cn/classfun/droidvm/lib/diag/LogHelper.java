package cn.classfun.droidvm.lib.diag;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.VMEventHandler;
import cn.classfun.droidvm.lib.diag.handler.BadSM8650HostKernelHandler;
import cn.classfun.droidvm.lib.diag.handler.HugePageFaultHandler;
import cn.classfun.droidvm.lib.diag.handler.OsKernelWithoutRestrictPoolHandler;
import cn.classfun.droidvm.lib.diag.handler.UnsupportedGunyahVersionHandler;
import cn.classfun.droidvm.lib.diag.handler.UnsupportedSandboxHandler;
import cn.classfun.droidvm.lib.store.base.RingBuffer;

public final class LogHelper implements DaemonConnection.EventListener {
    private final VMEventHandler vmEventHandler;
    private final Map<UUID, LogContext> vmLogContexts = new HashMap<>();

    public LogHelper(VMEventHandler vmEventHandler) {
        this.vmEventHandler = vmEventHandler;
    }

    private static class LogContext {
        final UUID vmId;
        final Map<String, RingBuffer> log = new HashMap<>();
        final Set<LogHelperHandler> disabled = new HashSet<>();

        private LogContext(UUID vmId) {
            this.vmId = vmId;
        }
    }
    private static final LogHelperHandler[] handlers = new LogHelperHandler[]{
        new BadSM8650HostKernelHandler(),
        new HugePageFaultHandler(),
        new OsKernelWithoutRestrictPoolHandler(),
        new UnsupportedGunyahVersionHandler(),
        new UnsupportedSandboxHandler(),
    };

    @Override
    public void onDaemonEvent(@NonNull JSONObject msg) {
        var type = msg.optString("type", "");
        if (!type.equals("event")) return;
        var data = msg.optJSONObject("data");
        if (data == null) return;
        var vmId = UUID.fromString(data.optString("vm_id", ""));
        var vmName = data.optString("vm_name", "");
        var event = data.optString("event", "");
        if (event.equals("exited")) {
            vmLogContexts.remove(vmId);
            return;
        }
        var logs = vmLogContexts.computeIfAbsent(vmId, k -> new LogContext(vmId));
        if (!event.equals("output")) return;
        var text = URLDecoder.decode(data.optString("data", ""), StandardCharsets.UTF_8);
        var stream = data.optString("stream", "");
        if (stream.isEmpty()) return;
        var buff = logs.log.computeIfAbsent(stream, k -> new RingBuffer(4096));
        buff.adds(text.getBytes(StandardCharsets.UTF_8));
        var full = new String(buff.peekAll(), StandardCharsets.UTF_8);
        for (var handler : handlers) {
            if (logs.disabled.contains(handler)) continue;
            if (!handler.match(vmId, stream, full)) continue;
            Runnable show = () -> vmEventHandler.queueActivityTask(act -> handler.show(act, vmId, vmName));
            new Handler(Looper.getMainLooper()).postDelayed(show, handler.getShowDelay().toMillis());
            if (handler.isOnce())
                logs.disabled.add(handler);
        }
    }

    @Override
    public void onDaemonConnected() {

    }

    @Override
    public void onDaemonDisconnected() {

    }

}
