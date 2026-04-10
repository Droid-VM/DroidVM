package cn.classfun.droidvm.lib.daemon;

import static cn.classfun.droidvm.lib.daemon.Protocol.IPC_REQUEST_TIMEOUT_MS;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class DaemonClient {
    private static final String TAG = "DaemonClient";
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean connected = false;
    private Thread receiveThread;
    private EventCallback eventCallback;
    private Protocol proto;

    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    public interface EventCallback {
        @SuppressWarnings("unused")
        void onEvent(JSONObject event);
    }

    public interface ConnectionCallback {
        void onDisconnected();
    }

    private ConnectionCallback connectionCallback;

    public void setEventCallback(EventCallback cb) {
        this.eventCallback = cb;
    }

    public void setConnectionCallback(ConnectionCallback cb) {
        this.connectionCallback = cb;
    }

    public void connect(int port) throws IOException {
        var addr = InetAddress.getLocalHost();
        var sockAddr = new InetSocketAddress(addr, port);
        socket = new Socket();
        socket.connect(sockAddr);
        in = socket.getInputStream();
        out = socket.getOutputStream();
        this.proto = new Protocol(in, out);
        connected = true;
        receiveThread = new Thread(this::receiveLoop, "DaemonClient-recv");
        receiveThread.setDaemon(true);
        receiveThread.start();
        Log.d(TAG, fmt("Connected to %s", sockAddr.toString()));
    }

    public boolean isConnected() {
        return connected;
    }

    public void close() {
        connected = false;
        var closeErr = new IOException("Connection closed");
        for (var pr : pendingRequests.values()) {
            pr.error.set(closeErr);
            pr.latch.countDown();
        }
        pendingRequests.clear();
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Error closing socket", e);
        }
        socket = null;
        in = null;
        out = null;
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
    }

    public JSONObject request(JSONObject req) throws IOException, JSONException {
        if (!connected) throw new IOException("Not connected");
        var requestId = UUID.randomUUID().toString();
        req.put("type", "request");
        req.put("request_id", requestId);
        var pending = new PendingRequest();
        pendingRequests.put(requestId, pending);
        try {
            proto.sendPacket(req);
            if (!pending.latch.await(IPC_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                throw new IOException(fmt("Request timed out after %dms", IPC_REQUEST_TIMEOUT_MS));
            var err = pending.error.get();
            if (err != null) {
                if (err instanceof IOException) throw (IOException) err;
                throw new IOException(err);
            }
            return pending.response.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        } finally {
            pendingRequests.remove(requestId);
        }
    }

    private void processResponse(@NonNull JSONObject msg) {
        var reqId = msg.optString("request_id", "");
        var pending = pendingRequests.get(reqId);
        if (pending == null) {
            Log.w(TAG, fmt("Response for unknown request_id: %s", reqId));
            return;
        }
        pending.response.set(msg);
        pending.latch.countDown();
    }

    private void processEvent(@NonNull JSONObject msg) {
        if (eventCallback == null) return;
        try {
            eventCallback.onEvent(msg);
        } catch (Exception e) {
            Log.e(TAG, "EventCallback threw", e);
        }
    }

    private void receiveLoop() {
        Log.d(TAG, "Receive thread started");
        try {
            while (connected) {
                var msg = proto.recvPacket();
                if (msg == null) {
                    Log.i(TAG, "Connection closed by peer");
                    break;
                }
                var type = msg.optString("type", "");
                switch (type) {
                    case "response":
                        processResponse(msg);
                        break;
                    case "event":
                        processEvent(msg);
                        break;
                    default:
                        Log.w(TAG, fmt("Unhandled message type: %s", type));
                        Log.d(TAG, fmt("Message content: %s", msg.toString()));
                        break;
                }
            }
        } catch (IOException e) {
            if (connected) Log.w(TAG, "Receive thread I/O error", e);
        } catch (JSONException e) {
            Log.e(TAG, "Receive thread JSON error", e);
        } finally {
            connected = false;
            var err = new IOException("Connection lost");
            for (var pr : pendingRequests.values()) {
                pr.error.compareAndSet(null, err);
                pr.latch.countDown();
            }
            if (connectionCallback != null)
                connectionCallback.onDisconnected();
            Log.d(TAG, "Receive thread exited");
        }
    }
}
