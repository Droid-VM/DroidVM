package cn.classfun.droidvm.daemon.server;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.UUID;

import cn.classfun.droidvm.lib.daemon.Protocol;

public final class ClientHandler implements Closeable {
    private static final String TAG = "ClientHandler";
    private final UUID id;
    private Server server;
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private Thread thread = null;
    private volatile boolean running = false;
    private volatile boolean closed = false;
    public final Protocol proto;
    public boolean authorized = false;

    public ClientHandler(@NonNull Server server, @NonNull Socket socket) throws IOException {
        this.id = UUID.randomUUID();
        this.server = server;
        this.socket = socket;
        this.input = socket.getInputStream();
        this.output = socket.getOutputStream();
        this.proto = new Protocol(input, output);
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, toString());
        thread.start();
    }

    @SuppressWarnings("unused")
    public void stop() {
        running = false;
        var t = thread;
        if (t != null) {
            t.interrupt();
            thread = null;
        }
    }

    public void run() {
        try {
            var cid = id.toString();
            while (running && !closed) {
                var s = socket;
                if (s == null || s.isClosed()) break;
                try {
                    processOnce();
                } catch (IOException e) {
                    Log.w(TAG, fmt("IO error on %s", cid), e);
                    break;
                } catch (Exception e) {
                    Log.w(TAG, fmt("error on %s", cid), e);
                }
            }
        } finally {
            running = false;
            close();
        }
    }

    private void processOnce() throws Exception {
        var json = proto.recvPacket();
        if (json == null) {
            Log.i(TAG, fmt("Client %s disconnected", id));
            running = false;
            close();
            return;
        }
        try {
            var type = json.getString("type");
            switch (type) {
                case "request":
                    var req = new ClientRequest(this, json);
                    runOnPool(() -> handleRequest(req));
                    break;
                case "event":
                    break;
                default:
                    Log.w(TAG, fmt("Unknown packet type: %s", type));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse packet", e);
        }
    }

    private void handleRequest(@NonNull ClientRequest req) {
        var res = req.getResponse();
        var cmd = req.getCommand();
        var cid = id.toString();
        try {
            var handler = req.findHandler();
            if (handler == null) throw new RequestException(fmt(
                "command handler %s found", cmd
            ));
            if (!authorized && handler.needAuthorization())
                throw new RequestException("client is not authorized to perform this action");
            handler.handle(req);
        } catch (RequestException exc) {
            Log.i(TAG, fmt("Request %s from %s failed: %s", cmd, cid, exc.getMessage()));
            res.setException(exc);
        } catch (Exception exc) {
            Log.w(TAG, fmt("Request %s from %s error", cmd, cid), exc);
            res.setException(exc);
        }
        try {
            var data = res.pack();
            proto.sendPacket(data);
        } catch (Exception exc) {
            Log.w(TAG, fmt(
                "Failed to send response for request %s",
                req.getCommand()
            ), exc);
        }
    }

    @Nullable
    @SuppressWarnings("unused")
    public Socket getSocket() {
        return socket;
    }

    @NonNull
    public UUID getId() {
        return id;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        running = false;
        if (server != null) {
            server.removeClient(this);
            server = null;
        }
        if (input != null) try {
            input.close();
        } catch (IOException ignored) {
        }
        input = null;
        if (output != null) try {
            output.close();
        } catch (IOException ignored) {
        }
        output = null;
        if (socket != null) try {
            socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
    }

    public Server getServer() {
        return server;
    }

    @NonNull
    @Override
    public String toString() {
        var s = socket;
        if (s == null) return fmt("ClientHandler-%s", id);
        return fmt("ClientHandler-%s-%d", s.getInetAddress(), s.getPort());
    }
}
