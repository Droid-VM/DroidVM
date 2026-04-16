package cn.classfun.droidvm.daemon.console;

import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.daemon.server.Server.getDroidVMUid;
import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.StringUtils.urlEncodeBytesAll;

import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import cn.classfun.droidvm.lib.store.base.JSONSerialize;
import cn.classfun.droidvm.lib.store.base.RingBuffer;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

public abstract class ConsoleStream implements Closeable, JSONSerialize {
    private static final String TAG = "ConsoleStream";
    public static final int MAX_BUFFER_SIZE = 1 << 20;
    protected final VMConfig config;
    protected final String name;
    protected final RingBuffer buffer = new RingBuffer(MAX_BUFFER_SIZE);
    private Thread readerThread;
    private OutputStream logWriter = null;
    private boolean disableSave = false;

    public ConsoleStream(@NonNull VMConfig config, @NonNull String name) {
        this.config = config;
        this.name = name;
        loadLog();
    }

    @NonNull
    public String getName() {
        return name;
    }

    public final boolean write(@NonNull String data) {
        if (!isWritable()) {
            Log.w(TAG, fmt("Stream '%s' on VM %s is not writable", name, config.getId()));
            return false;
        }
        var os = getOutputStream();
        if (os == null) {
            Log.w(TAG, fmt("Stream '%s' on VM %s is read-only", name, config.getId()));
            return false;
        }
        try {
            os.write(data.getBytes(StandardCharsets.UTF_8));
            os.flush();
            return true;
        } catch (Exception e) {
            Log.w(TAG, fmt(
                "writeStream '%s' on VM %s failed",
                name, config.getId()
            ), e);
            return false;
        }
    }

    public void appendBuffer(@NonNull String data) {
        if (data.isEmpty()) return;
        appendBuffer(data.getBytes(UTF_8));
    }

    public void appendBuffer(@NonNull byte[] data) {
        appendBuffer(data, 0, data.length);
    }

    public void appendBuffer(@NonNull byte[] data, int off, int len) {
        buffer.adds(data, off, len);
        if (disableSave) return;
        try {
            if (logWriter == null) {
                var path = getPersistentPath();
                var file = new File(path);
                logWriter = new FileOutputStream(file);
                logWriter.write(buffer.peekAll());
                logWriter.flush();
                var uid = getDroidVMUid();
                Os.chown(path, uid, uid);
                Log.v(TAG, fmt(
                    "Created persistent log for VM %s stream %s at %s",
                    config.getName(), name, path
                ));
            } else {
                logWriter.write(data, off, len);
                logWriter.flush();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to write console log to output stream", e);
            disableSave = true;
        }
    }

    public void clear() {
        buffer.clear();
        if (logWriter != null) {
            try {
                logWriter.close();
            } catch (Exception e) {
                Log.w(TAG, "Failed to close console log output stream", e);
            }
            try {
                var path = getPersistentPath();
                var file = new File(path);
                if (file.exists() && !file.delete())
                    Log.w(TAG, fmt("Failed to delete console log file %s", path));
            } catch (Exception e) {
                Log.w(TAG, "Failed to delete console log file", e);
            }
            logWriter = null;
        }
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getBuffer() {
        return new String(buffer.peekAll(), UTF_8);
    }

    @NonNull
    @SuppressWarnings("unused")
    public byte[] getRawBuffer() {
        return buffer.peekAll();
    }

    @NonNull
    @SuppressWarnings("unused")
    public String toSerializedString() {
        return urlEncodeBytesAll(buffer.peekAll());
    }

    @SuppressWarnings("unused")
    public abstract boolean isReadable();

    @SuppressWarnings("unused")
    public abstract boolean isWritable();

    @SuppressWarnings("unused")
    public int getPosixReadFd() {
        return -1;
    }

    @SuppressWarnings("unused")
    public int getPosixWriteFd() {
        return -1;
    }

    @Nullable
    @SuppressWarnings("unused")
    public InputStream getInputStream() {
        return null;
    }

    @Nullable
    @SuppressWarnings("unused")
    public OutputStream getOutputStream() {
        return null;
    }

    @SuppressWarnings("unused")
    public void setInputStream(InputStream stream) {
    }

    @SuppressWarnings("unused")
    public void setOutputStream(OutputStream stream) {
    }

    @Nullable
    @SuppressWarnings("unused")
    public Thread getReaderThread() {
        return readerThread;
    }

    @SuppressWarnings("unused")
    public void setReaderThread(@Nullable Thread thread) {
        this.readerThread = thread;
    }

    @SuppressWarnings("unused")
    public synchronized void saveLog() {
        var path = getPersistentPath();
        byte[] data = buffer.peekAll();
        if (data.length > 0) try (var fo = new FileOutputStream(path)) {
            fo.write(data);
            fo.flush();
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to save console log to %s", path), e);
        }
    }

    @SuppressWarnings("unused")
    public synchronized void loadLog() {
        var path = getPersistentPath();
        var file = new File(path);
        if (!file.exists()) return;
        try (var fi = new FileInputStream(path)) {
            var buff = new byte[4096];
            buffer.clear();
            while (true) {
                int ret = fi.read(buff);
                if (ret < 0) break;
                buffer.adds(buff, 0, ret);
            }
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to load console log from %s", path), e);
        }
    }

    public String getPersistentPath() {
        var name = fmt("console_%s_%s.log", config.getId(), getName());
        return pathJoin(DATA_DIR, "cache", name);
    }

    @Override
    public void close() {
        if (logWriter != null) {
            try {
                logWriter.close();
            } catch (Exception e) {
                Log.w(TAG, "Failed to close console log output stream", e);
            }
            logWriter = null;
        }
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var obj = new JSONObject();
        obj.put("type", getClass().getSimpleName());
        obj.put("name", name);
        obj.put("path", getPersistentPath());
        obj.put("length", buffer.size());
        obj.put("readable", isReadable());
        obj.put("writable", isWritable());
        return obj;
    }
}
