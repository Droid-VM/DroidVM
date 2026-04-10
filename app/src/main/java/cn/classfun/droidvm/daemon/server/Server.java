package cn.classfun.droidvm.daemon.server;

import static android.system.Os.stat;
import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.daemon.DaemonHelper.getPortFile;
import static cn.classfun.droidvm.lib.utils.NetUtils.generateRandomAvailablePort;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.utils.FileUtils;

public final class Server {
    private static final String TAG = "Server";
    private volatile boolean running = false;
    private Thread runningThread = null;
    private final List<ClientHandler> clients = new ArrayList<>();
    public final RequestHandlerStore handlers;
    private final ServerContext context;

    public Server() {
        context = new ServerContext();
        handlers = new RequestHandlerStore();
        context.getVMs().setEventCallback((vmId, event, data) -> {
            try {
                var notification = new JSONObject();
                notification.put("type", "event");
                notification.put("data", data);
                broadcastEvent(notification);
            } catch (Exception e) {
                Log.w(TAG, "Failed to broadcast VM event", e);
            }
        });
    }

    @NonNull
    public ServerContext getContext() {
        return context;
    }

    public void broadcastEvent(@NonNull JSONObject event) {
        synchronized (clients) {
            var it = clients.iterator();
            while (it.hasNext()) {
                var client = it.next();
                try {
                    client.proto.sendPacket(event);
                } catch (IOException e) {
                    Log.w(TAG, "Removing dead client on broadcast failure");
                    it.remove();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to send broadcast to client", e);
                }
            }
        }
    }

    private void writePortFile(int port) {
        var portFile = new File(getPortFile());
        var dir = portFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs())
            Log.w(TAG, fmt("Failed to create run directory: %s", dir));
        try (var writer = new FileWriter(portFile)) {
            writer.write(String.valueOf(port));
            writer.flush();
            Log.i(TAG, fmt("Port file written: %s (port=%d)", portFile, port));
        } catch (IOException e) {
            Log.e(TAG, fmt("Failed to write port file: %s", portFile), e);
        }
    }

    @SuppressWarnings("BusyWait")
    public void run() {
        if (running)
            throw new IllegalStateException("Server is already running");
        ServerSocket serverSocket;
        InetSocketAddress sockAddr;
        try {
            var addr = InetAddress.getLocalHost();
            var port = generateRandomAvailablePort();
            if (port < 0) {
                Log.e(TAG, "Failed to find an available port");
                return;
            }
            sockAddr = new InetSocketAddress(addr, port);
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(sockAddr);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start server socket", e);
            return;
        }
        writePortFile(sockAddr.getPort());
        try {
            Log.d(TAG, fmt("DroidVM Daemon is listening on %s", sockAddr.toString()));
            runningThread = Thread.currentThread();
            running = true;
            while (running) try {
                var client = serverSocket.accept();
                Log.d(TAG, "Accepted new client connection");
                runOnPool(() -> handleClient(client));
                Thread.sleep(0);
            } catch (InterruptedException ignored) {
            }
            Log.i(TAG, "Daemon is shutting down");
        } catch (Exception e) {
            Log.e(TAG, "Daemon encountered an error", e);
        } finally {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            try {
                FileUtils.deleteFile(getPortFile());
            } catch (Exception ignored) {
            }
        }
    }

    public void stop() {
        if (!running) return;
        running = false;
        if (runningThread != null)
            runningThread.interrupt();
    }

    @SuppressWarnings("unused")
    public boolean isRunning() {
        return running;
    }

    private void handleClient(@NonNull Socket client) {
        try {
            var handler = new ClientHandler(this, client);
            synchronized (clients) {
                clients.add(handler);
            }
            handler.start();
        } catch (Exception e) {
            Log.e(TAG, "Error handling client connection", e);
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    public void removeClient(@NonNull ClientHandler handler) {
        synchronized (clients) {
            clients.remove(handler);
        }
    }

    public static int getDroidVMUid() {
        try {
            var stat = stat(DATA_DIR);
            return stat.st_uid;
        } catch (Exception e) {
            Log.w(TAG, "Failed to get DroidVM UID", e);
        }
        return -1;
    }
}
