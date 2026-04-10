package cn.classfun.droidvm.lib.daemon;

import static cn.classfun.droidvm.lib.daemon.DaemonHelper.getPortFile;
import static cn.classfun.droidvm.lib.daemon.Protocol.IPC_REQUEST_TIMEOUT_MS;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.threadSleep;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class DaemonConnection {
    private static final String TAG = "DaemonConnection";
    private static DaemonConnection instance;
    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private Thread connectionThread;
    private DaemonClient client;
    private boolean connectedCalled = false;

    public interface EventListener {
        void onDaemonEvent(JSONObject data);

        void onDaemonConnected();

        void onDaemonDisconnected();
    }

    public interface OnResponse {
        void onResponse(JSONObject response);
    }

    public interface OnError {
        void onError(Exception e);
    }

    public interface OnUnsuccessful {
        void onUnsuccessful(JSONObject response);
    }

    @NonNull
    public RequestContext buildRequest() {
        return new RequestContext(this);
    }

    @NonNull
    public RequestContext buildRequest(String command) {
        return buildRequest().put("command", command);
    }

    private DaemonConnection() {
    }

    public static synchronized DaemonConnection getInstance() {
        if (instance == null) instance = new DaemonConnection();
        return instance;
    }

    public void addListener(EventListener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        ensureRunning();
    }

    public void removeListener(EventListener listener) {
        listeners.remove(listener);
    }

    public void connect() {
        consecutiveFailures.set(0);
        if (connectionThread == null || !running) {
            ensureRunning();
        } else try {
            connectionThread.interrupt();
        } catch (Exception ignored) {
        }
    }

    private synchronized void ensureRunning() {
        if (running) return;
        running = true;
        connectionThread = new Thread(this::connectionLoop, "DaemonConnection");
        connectionThread.setDaemon(true);
        connectionThread.start();
    }

    public synchronized void disconnect() {
        running = false;
        if (client != null) client.close();
        if (connectionThread != null)
            connectionThread.interrupt();
    }

    public JSONObject request(JSONObject req) throws IOException, JSONException {
        if (client == null || !client.isConnected())
            throw new IOException("Not connected to daemon");
        return client.request(req);
    }

    private boolean authenticate(@NonNull DaemonClient client) {
        var token = DaemonHelper.readToken();
        if (token == null) {
            Log.e(TAG, "Failed to read auth token");
            return false;
        }
        try {
            var authReq = new JSONObject();
            authReq.put("command", "auth");
            authReq.put("token", token);
            var resp = client.request(authReq);
            if (resp.optBoolean("success", false)) {
                Log.i(TAG, "Authentication successful");
                return true;
            }
            Log.e(TAG, fmt("Authentication failed: %s", resp.optString("message", "Unknown error")));
            return false;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Authentication request failed", e);
            return false;
        }
    }

    private void callConnected() {
        if (connectedCalled) return;
        for (var l : listeners) {
            try {
                l.onDaemonConnected();
            } catch (Exception e) {
                Log.e(TAG, "Listener threw", e);
            }
        }
        connectedCalled = true;
    }

    private void callDisconnected() {
        if (!connectedCalled) return;
        for (var l : listeners) {
            try {
                l.onDaemonDisconnected();
            } catch (Exception e) {
                Log.e(TAG, "Listener threw", e);
            }
        }
        connectedCalled = false;
    }

    private void waitForPortFile() {
        if (!running) return;
        var file = new File(getPortFile());
        if (!file.exists())
            Log.i(TAG, "Waiting for daemon port file...");
        while (running && !file.exists())
            threadSleep(300);
        if (file.exists())
            Log.i(TAG, "Daemon port file found");
    }

    private void connectionLoop() {
        while (running) {
            var newClient = new DaemonClient();
            var disconnectLatch = new CountDownLatch(1);
            newClient.setEventCallback(this::dispatchEvent);
            newClient.setConnectionCallback(disconnectLatch::countDown);
            try {
                waitForPortFile();
                newClient.connect(DaemonHelper.readPort());
                client = newClient;
                Log.i(TAG, "Connected to daemon");
                if (!authenticate(newClient)) {
                    Log.e(TAG, "Authentication failed, disconnecting");
                    newClient.close();
                    throw new IOException("Authentication failed");
                }
                consecutiveFailures.set(0);
                callConnected();
                disconnectLatch.await();
            } catch (IOException e) {
                if (running) {
                    int failures = consecutiveFailures.incrementAndGet();
                    if (failures <= 3) {
                        Log.w(TAG, fmt("Daemon not available (%d)", failures));
                    } else if (failures == 4) {
                        Log.w(TAG, "Daemon still not available, suppressing further logs");
                    }
                }
            } catch (InterruptedException ignored) {
                continue;
            } finally {
                if (newClient.isConnected()) newClient.close();
                client = null;
                callDisconnected();
            }
            if (running) {
                var factor = 1L << Math.min(consecutiveFailures.get(), 15);
                var delay = Math.min(IPC_REQUEST_TIMEOUT_MS / 100 * factor, IPC_REQUEST_TIMEOUT_MS);
                threadSleep(delay);
            }
        }
        connectionThread = null;
        running = false;
        Log.d(TAG, "Connection loop exited");
    }

    private void dispatchEvent(JSONObject msg) {
        for (var l : listeners) {
            try {
                l.onDaemonEvent(msg);
            } catch (Exception e) {
                Log.e(TAG, "Listener threw exception", e);
            }
        }
    }

    public void shutdown() {
        disconnect();
        executor.shutdownNow();
    }
}
