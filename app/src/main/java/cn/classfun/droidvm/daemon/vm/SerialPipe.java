package cn.classfun.droidvm.daemon.vm;

import java.io.Closeable;

import cn.classfun.droidvm.daemon.console.FDPipeConsoleStream;
import cn.classfun.droidvm.lib.natives.UnixHelper;

public final class SerialPipe implements Closeable {
    private final String name;
    private FDPipeConsoleStream stream;
    private int[] pipe_in;
    private int[] pipe_out;

    public SerialPipe(
        FDPipeConsoleStream stream,
        String name
    ) {
        this.name = name;
        this.pipe_out = UnixHelper.nativePipe();
        this.pipe_in = UnixHelper.nativePipe();
        this.stream = stream;
        if (
            pipe_in == null || pipe_in.length != 2 ||
            pipe_out == null || pipe_out.length != 2
        ) {
            close();
            throw new RuntimeException("Failed to create pipes");
        }
        stream.setReadFd(getOutputLocalFd());
        stream.setWriteFd(getInputLocalFd());
    }

    @SuppressWarnings("unused")
    public FDPipeConsoleStream getStream() {
        return stream;
    }

    public boolean isReady() {
        return stream != null && stream.isReady();
    }

    public int getOutputLocalFd() {
        if (pipe_out == null || pipe_out[0] == -1)
            throw new IllegalStateException("output local fd invalid");
        return pipe_out[0];
    }

    public int getOutputRemoteFd() {
        if (pipe_out == null || pipe_out[1] == -1)
            throw new IllegalStateException("output remote fd invalid");
        return pipe_out[1];
    }

    public int getInputLocalFd() {
        if (pipe_in == null || pipe_in[1] == -1)
            throw new IllegalStateException("input local fd invalid");
        return pipe_in[1];
    }

    public int getInputRemoteFd() {
        if (pipe_in == null || pipe_in[0] == -1)
            throw new IllegalStateException("input remote fd invalid");
        return pipe_in[0];
    }

    public void closeRemoteFd() {
        if (pipe_out != null) {
            closeFd(pipe_in[0]);
            pipe_in[0] = -1;
        }
        if (pipe_out != null) {
            closeFd(pipe_out[1]);
            pipe_out[1] = -1;
        }
    }

    @Override
    public void close() {
        if (stream != null) {
            stream.close();
            stream = null;
        }
        if (pipe_in != null) {
            closeFd(pipe_in[0]);
            closeFd(pipe_in[1]);
            pipe_in = null;
        }
        if (pipe_out != null) {
            closeFd(pipe_out[0]);
            closeFd(pipe_out[1]);
            pipe_out = null;
        }
    }

    private static void closeFd(int fd) {
        if (fd >= 0)
            UnixHelper.nativeCloseFd(fd);
    }

    @SuppressWarnings("unused")
    public String getName() {
        return name;
    }
}
