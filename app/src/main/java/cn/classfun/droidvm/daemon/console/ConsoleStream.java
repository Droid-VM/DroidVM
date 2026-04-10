package cn.classfun.droidvm.daemon.console;

import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.daemon.server.Server.getDroidVMUid;
import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import cn.classfun.droidvm.lib.utils.FileUtils;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

public abstract class ConsoleStream implements Closeable {
    private static final String TAG = "ConsoleStream";
    public static final int MAX_BUFFER_SIZE = 1 << 20;
    protected final VMConfig config;
    protected final String name;
    protected final StringBuilder buffer = new StringBuilder();
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

    public synchronized void appendBuffer(@NonNull String data) {
        if (data.isEmpty()) return;
        if (buffer.length() + data.length() > MAX_BUFFER_SIZE) {
            int excess = (buffer.length() + data.length()) - MAX_BUFFER_SIZE;
            if (excess >= buffer.length()) {
                buffer.setLength(0);
            } else {
                buffer.delete(0, excess);
            }
        }
        buffer.append(data);
        flushLog(data);
    }

    private void flushLog(@NonNull String data) {
        if (disableSave) return;
        try {
            if (logWriter == null) {
                var path = getPersistentPath();
                var file = new File(path);
                logWriter = new FileOutputStream(file);
                logWriter.write(buffer.toString().getBytes(UTF_8));
                logWriter.flush();
                var uid = getDroidVMUid();
                Os.chown(path, uid, uid);
                Log.v(TAG, fmt(
                    "Created persistent log for VM %s stream %s at %s",
                    config.getName(), name, path
                ));
            } else {
                logWriter.write(data.getBytes(UTF_8));
                logWriter.flush();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to write console log to output stream", e);
            disableSave = true;
        }
    }

    @NonNull
    @SuppressWarnings("unused")
    public synchronized String getBuffer() {
        return buffer.toString();
    }

    @SuppressWarnings("unused")
    public abstract boolean isReadable();

    @SuppressWarnings("unused")
    public abstract boolean isWritable();

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
        var data = buffer.toString();
        if (!data.isEmpty()) try {
            FileUtils.writeFile(path, data);
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to save console log to %s", path), e);
        }
    }

    @SuppressWarnings("unused")
    public synchronized void loadLog() {
        var path = getPersistentPath();
        var file = new File(path);
        if (!file.exists()) return;
        try {
            buffer.setLength(0);
            appendBuffer(FileUtils.readFile(file));
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
}
