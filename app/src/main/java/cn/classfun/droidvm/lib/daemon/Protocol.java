package cn.classfun.droidvm.lib.daemon;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public final class Protocol {
    public static final int IPC_MAX_PAYLOAD = 1 << 20; // 1 MiB
    public static final long IPC_REQUEST_TIMEOUT_MS = 30_000;
    private final InputStream in;
    private final OutputStream out;

    public Protocol(@NonNull InputStream in, @NonNull OutputStream out) {
        this.in = in;
        this.out = out;
    }

    @Nullable
    public JSONObject recvPacket() throws IOException, JSONException {
        var headerBuf = readExact(4);
        if (headerBuf == null) return null;
        var header = ByteBuffer.wrap(headerBuf);
        header.order(LITTLE_ENDIAN);
        int len = header.getInt();
        if (len <= 0 || len > IPC_MAX_PAYLOAD)
            throw new IOException(fmt("Invalid payload length: %d", len));
        var data = readExact(len);
        if (data == null) return null;
        int strLen = len;
        if (data[strLen - 1] == 0) strLen--;
        var jsonStr = new String(data, 0, strLen, UTF_8);
        return new JSONObject(jsonStr);
    }

    public synchronized void sendPacket(@NonNull JSONObject json) throws IOException {
        var jsonBytes = json.toString().getBytes(UTF_8);
        int len = jsonBytes.length + 1;
        var header = ByteBuffer.allocate(4);
        header.order(LITTLE_ENDIAN);
        header.putInt(len);
        out.write(header.array());
        out.write(jsonBytes);
        out.write(0);
        out.flush();
    }

    @Nullable
    private byte[] readExact(int n) throws IOException {
        var buf = new byte[n];
        int offset = 0;
        while (offset < n) {
            int r = in.read(buf, offset, n - offset);
            if (r <= 0) return null;
            offset += r;
        }
        return buf;
    }
}
