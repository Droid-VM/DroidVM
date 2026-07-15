package cn.classfun.droidvm.lib.pkg;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Slices the compressed data stream across fixed-size sub-volumes
 * {@code <base>.001, <base>.002, ...}. Pure data: no header, manifest or magic
 * -- those live in the metadata master ({@link VolumeIndex}). Tracks each
 * sub-volume's size and CRC32. {@link #close()} is a no-op so an upstream
 * compression stream can be closed without ending the volume set; finalize
 * explicitly with {@link #finish()} and read {@link #entries()}/{@link #dataSize()}.
 */
public final class VolumeSplitOutputStream extends OutputStream {
    private final String basePath;
    private final long volumeSize;
    private final List<VolumeIndex.Entry> entries = new ArrayList<>();
    private final List<File> files = new ArrayList<>();
    private OutputStream current;
    private CRC32 currentCrc;
    private long currentBytes;
    private int index;
    private long position;
    private boolean finished;

    public VolumeSplitOutputStream(@NonNull String basePath, long volumeSize) {
        this.basePath = basePath;
        this.volumeSize = volumeSize;
    }

    @NonNull
    public static String volumeName(@NonNull String basePath, int index) {
        return fmt("%s.%0" + PackageConstants.VOLUME_SUFFIX_DIGITS + "d", basePath, index); // concat-ok
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(@NonNull byte[] b, int off, int len) throws IOException {
        if (finished) throw new IOException("volume stream already finished");
        while (len > 0) {
            if (current == null || currentBytes >= volumeSize) rollVolume();
            int chunk = (int) Math.min(len, volumeSize - currentBytes);
            current.write(b, off, chunk);
            currentCrc.update(b, off, chunk);
            currentBytes += chunk;
            position += chunk;
            off += chunk;
            len -= chunk;
        }
    }

    private void rollVolume() throws IOException {
        if (current != null) closeCurrent();
        index++;
        if (index > PackageConstants.VOLUME_MAX_COUNT)
            throw new IOException(fmt(
                "too many volumes (> %d); use a larger volume size",
                PackageConstants.VOLUME_MAX_COUNT
            ));
        var file = new File(volumeName(basePath, index));
        files.add(file);
        current = new BufferedOutputStream(new FileOutputStream(file), PackageConstants.BUFFER);
        currentCrc = new CRC32();
        currentBytes = 0;
    }

    private void closeCurrent() throws IOException {
        entries.add(new VolumeIndex.Entry(index, currentBytes, currentCrc.getValue()));
        current.flush();
        current.close();
        current = null;
    }

    /** Closes the final sub-volume and records its entry. */
    public void finish() throws IOException {
        if (finished) return;
        if (current != null) closeCurrent();
        finished = true;
    }

    /** Total bytes written across all sub-volumes (the compressed data size). */
    public long dataSize() {
        return position;
    }

    public int currentIndex() {
        return index;
    }

    public int volumeCount() {
        return entries.size();
    }

    @NonNull
    public List<VolumeIndex.Entry> entries() {
        return entries;
    }

    @NonNull
    public List<File> files() {
        return files;
    }

    /** No-op: mirrors RandomAccessFileOutputStream so upstream close() cannot
     * end the volume set prematurely. Use {@link #finish()} to finalize. */
    @Override
    public void close() {
    }

    /** Closes any open sub-volume without recording it, for error cleanup. */
    public void abort() {
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
            current = null;
        }
    }
}
