package cn.classfun.droidvm.daemon.network.backend.gvisor;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal HTTP/1.1 client for the gvswitch REST API over its unix control
 * socket. One request per connection (Connection: close); responses are
 * small JSON documents framed by Content-Length.
 */
public final class GvswitchClient {
    private final String socketPath;
    private final String token;

    public GvswitchClient(@NonNull String socketPath, @NonNull String token) {
        this.socketPath = socketPath;
        this.token = token;
    }

    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, @NonNull String body) {
            this.code = code;
            this.body = body;
        }

        public boolean isSuccess() {
            return code >= 200 && code < 300;
        }

        @NonNull
        public JSONObject json() throws Exception {
            return new JSONObject(body);
        }

        @NonNull
        public JSONArray jsonArray() throws Exception {
            return new JSONArray(body);
        }
    }

    @NonNull
    public Response get(@NonNull String path) throws IOException {
        return request("GET", path, null);
    }

    @NonNull
    public Response post(@NonNull String path, @Nullable Object body) throws IOException {
        return request("POST", path, body);
    }

    @NonNull
    public Response put(@NonNull String path, @Nullable Object body) throws IOException {
        return request("PUT", path, body);
    }

    @NonNull
    public Response delete(@NonNull String path) throws IOException {
        return request("DELETE", path, null);
    }

    @NonNull
    public Response request(
        @NonNull String method, @NonNull String path, @Nullable Object body
    ) throws IOException {
        var payload = body == null
            ? new byte[0]
            : body.toString().getBytes(StandardCharsets.UTF_8);
        try (var socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(
                socketPath, LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(10_000);
            var sb = new StringBuilder();
            sb.append(fmt("%s %s HTTP/1.1\r\n", method, path));
            sb.append("Host: gvswitch\r\n");
            sb.append(fmt("Authorization: Bearer %s\r\n", token));
            sb.append("Connection: close\r\n");
            if (payload.length > 0)
                sb.append("Content-Type: application/json\r\n");
            sb.append(fmt("Content-Length: %d\r\n", payload.length));
            sb.append("\r\n");
            var out = socket.getOutputStream();
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            if (payload.length > 0) out.write(payload);
            out.flush();
            return readResponse(socket.getInputStream());
        }
    }

    @NonNull
    private static Response readResponse(@NonNull InputStream in) throws IOException {
        var head = readHead(in);
        var lines = head.split("\r\n");
        if (lines.length == 0)
            throw new IOException("Empty HTTP response");
        var status = lines[0].split(" ", 3);
        if (status.length < 2)
            throw new IOException(fmt("Bad HTTP status line: %s", lines[0]));
        int code;
        try {
            code = Integer.parseInt(status[1]);
        } catch (NumberFormatException e) {
            throw new IOException(fmt("Bad HTTP status code: %s", lines[0]));
        }
        int contentLength = -1;
        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            var idx = lines[i].indexOf(':');
            if (idx < 0) continue;
            var name = lines[i].substring(0, idx).trim().toLowerCase();
            var value = lines[i].substring(idx + 1).trim();
            if (name.equals("content-length")) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                }
            } else if (name.equals("transfer-encoding")
                && value.toLowerCase().contains("chunked")) {
                chunked = true;
            }
        }
        byte[] body;
        if (chunked) body = readChunked(in);
        else if (contentLength >= 0) body = readN(in, contentLength);
        else body = readAll(in);
        return new Response(code, new String(body, StandardCharsets.UTF_8));
    }

    @NonNull
    private static String readHead(@NonNull InputStream in) throws IOException {
        var buf = new ByteArrayOutputStream();
        int state = 0; // counts \r\n\r\n progress
        int c;
        while ((c = in.read()) >= 0) {
            buf.write(c);
            if (c == '\r' && (state == 0 || state == 2)) state++;
            else if (c == '\n' && (state == 1 || state == 3)) state++;
            else state = 0;
            if (state == 4) break;
        }
        var head = buf.toString(StandardCharsets.UTF_8);
        return head.endsWith("\r\n\r\n")
            ? head.substring(0, head.length() - 4) : head;
    }

    @NonNull
    private static byte[] readN(@NonNull InputStream in, int n) throws IOException {
        var buf = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(buf, off, n - off);
            if (read < 0) break;
            off += read;
        }
        return buf;
    }

    @NonNull
    private static byte[] readAll(@NonNull InputStream in) throws IOException {
        var buf = new ByteArrayOutputStream();
        var chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) >= 0)
            buf.write(chunk, 0, read);
        return buf.toByteArray();
    }

    @NonNull
    private static byte[] readChunked(@NonNull InputStream in) throws IOException {
        var out = new ByteArrayOutputStream();
        while (true) {
            var line = readLine(in);
            int size;
            try {
                var semi = line.indexOf(';');
                size = Integer.parseInt(
                    (semi >= 0 ? line.substring(0, semi) : line).trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException(fmt("Bad chunk size: %s", line));
            }
            if (size == 0) break;
            out.write(readN(in, size));
            readLine(in); // trailing CRLF
        }
        return out.toByteArray();
    }

    @NonNull
    private static String readLine(@NonNull InputStream in) throws IOException {
        var buf = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') buf.append((char) c);
        }
        return buf.toString();
    }
}
