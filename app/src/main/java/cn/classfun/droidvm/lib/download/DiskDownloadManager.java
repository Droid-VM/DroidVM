package cn.classfun.droidvm.lib.download;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;

/**
 * Self-written disk-image download engine. We stream the file ourselves with
 * {@link HttpURLConnection} (following redirects, resuming via HTTP Range, and
 * retrying on read timeouts / dropped connections) instead of the system
 * DownloadManager, whose OEM variants either reject app-private destinations or
 * time out aggressively without resuming on this CDN — while plain `curl` works.
 *
 * <p>Because the app holds MANAGE_EXTERNAL_STORAGE we write straight to the
 * user's folder (a {@code .part} file, renamed on completion). State lives in an
 * in-memory registry; {@link DiskDownloadService} runs {@link #runDownload} on a
 * background thread and renders the notification, and activities poll
 * {@link #query} for the on-screen widget.
 */
public final class DiskDownloadManager {
    private static final String TAG = "DiskDownload";
    private static final int MAX_RETRIES = 10;
    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER = 65536;
    /** Abort + retry if throughput drops below ~0.7 KB/s over this window — i.e.
     * all but stopped (catches a mirror that holds the connection open but sends
     * almost nothing); genuinely slow-but-moving connections are left alone. */
    private static final long STALL_WINDOW_MS = 1500;
    private static final long STALL_MIN_BYTES = 1024;

    public static final int STATE_CONNECTING = 0;
    public static final int STATE_RUNNING = 1;
    public static final int STATE_PAUSED = 2; // between retries
    public static final int STATE_SUCCESS = 3;
    public static final int STATE_FAILED = 4;
    public static final int STATE_CANCELLED = 5;

    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, Download> JOBS = new ConcurrentHashMap<>();

    private DiskDownloadManager() {
    }

    /** Mutable per-download state. Fields are written by the download thread and
     * read by the service / activity poll loops, hence volatile. */
    private static final class Download {
        final long id;
        final String url;
        final String userAgent;
        final String folder;
        final String name;
        /** Activity that started this download, reopened when its notification is tapped. */
        final String sourceActivity;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile int state = STATE_CONNECTING;
        volatile long downloaded = 0;
        volatile long total = -1;
        volatile String reason;
        volatile UUID diskId;
        /** The in-flight connection, disconnected on cancel to unblock read(). */
        volatile HttpURLConnection activeConn;

        Download(long id, String url, String userAgent, String folder, String name,
                 String sourceActivity) {
            this.id = id;
            this.url = url;
            this.userAgent = userAgent;
            this.folder = folder;
            this.name = name;
            this.sourceActivity = sourceActivity;
        }
    }

    /** A snapshot of a download's live state. */
    public static final class Progress {
        public final int state;
        public final long downloaded;
        public final long total;
        public final String reason;

        Progress(int state, long downloaded, long total, String reason) {
            this.state = state;
            this.downloaded = downloaded;
            this.total = total;
            this.reason = reason;
        }
    }

    /** What an activity needs to finish a successful import. */
    public static final class Result {
        public final String folder;
        public final String name;
        public final UUID diskId;

        Result(String folder, String name, UUID diskId) {
            this.folder = folder;
            this.name = name;
            this.diskId = diskId;
        }
    }

    /**
     * Registers a download and returns its id. Drops any earlier job for the same
     * destination and purges finished jobs. The caller then starts
     * {@link DiskDownloadService} for this id, which runs the download.
     */
    public static long enqueue(
        @NonNull Context context,
        @NonNull String url,
        @NonNull String userAgent,
        @NonNull String folder,
        @NonNull String name,
        @NonNull Class<?> sourceActivity
    ) {
        purgeFinishedAndDuplicates(folder, name);
        long id = NEXT_ID.getAndIncrement();
        JOBS.put(id, new Download(id, url, userAgent, folder, name, sourceActivity.getName()));
        Log.i(TAG, fmt("Queued download #%d -> %s/%s", id, folder, name));
        return id;
    }

    /** Class name of the activity that started a download, or {@code null}. */
    @Nullable
    static String sourceActivity(long id) {
        var d = JOBS.get(id);
        return d != null ? d.sourceActivity : null;
    }

    /** True if any download is still in flight (only one is allowed at a time). */
    public static boolean hasActiveDownload() {
        for (var d : JOBS.values()) {
            if (d.state != STATE_SUCCESS && d.state != STATE_FAILED
                && d.state != STATE_CANCELLED)
                return true;
        }
        return false;
    }

    /** Live state of a download, or {@code null} if unknown/removed. */
    @Nullable
    public static Progress query(long id) {
        var d = JOBS.get(id);
        if (d == null) return null;
        return new Progress(d.state, d.downloaded, d.total, d.reason);
    }

    /** Requests cancellation; the download thread aborts and deletes its partial file. */
    public static void cancel(long id) {
        var d = JOBS.get(id);
        if (d == null) return;
        d.cancelled.set(true);
        // Break any blocked read() so the cancel takes effect immediately.
        var conn = d.activeConn;
        if (conn != null) conn.disconnect();
    }

    /** Result of a finished download, or {@code null} if it isn't successfully done. */
    @Nullable
    public static Result getResult(long id) {
        var d = JOBS.get(id);
        if (d == null || d.state != STATE_SUCCESS) return null;
        return new Result(d.folder, d.name, d.diskId);
    }

    /** Ids of all known (in-flight or just-finished) downloads. */
    @NonNull
    public static long[] activeIds() {
        var ids = new ArrayList<>(JOBS.keySet());
        long[] out = new long[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    /** Filename of a download, or {@code null} if unknown. */
    @Nullable
    public static String downloadName(long id) {
        var d = JOBS.get(id);
        return d != null ? d.name : null;
    }

    /**
     * Id of the in-flight download started by {@code sourceActivityClass}, or
     * {@code -1}. Lets a re-created import screen re-attach to its running
     * download and restore the progress UI.
     */
    public static long activeDownloadId(@NonNull String sourceActivityClass) {
        for (var d : JOBS.values()) {
            if (sourceActivityClass.equals(d.sourceActivity)
                && d.state != STATE_SUCCESS && d.state != STATE_FAILED
                && d.state != STATE_CANCELLED)
                return d.id;
        }
        return -1;
    }

    static boolean isTerminal(long id) {
        var d = JOBS.get(id);
        return d == null || d.state == STATE_SUCCESS
            || d.state == STATE_FAILED || d.state == STATE_CANCELLED;
    }

    /**
     * Streams the download to completion: resolves redirects, then retries with
     * HTTP Range resume on timeouts / dropped connections. On success the file is
     * moved into place and registered as a disk. Runs on a background thread.
     */
    static void runDownload(@NonNull Context context, long id) {
        var ctx = context.getApplicationContext();
        var d = JOBS.get(id);
        if (d == null) return;
        var part = new File(d.folder, d.name + ".part");
        var dest = new File(d.folder, d.name);
        try {
            var parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs())
                throw new IOException(fmt("Cannot create folder: %s", parent.getAbsolutePath()));
            // Start fresh — stale .part from a prior failed attempt may be corrupt.
            if (part.exists() && !part.delete())
                Log.w(TAG, fmt("Could not clear stale part file: %s", part.getAbsolutePath()));
            String lastError = null;
            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                if (d.cancelled.get()) {
                    abort(d, part);
                    return;
                }
                try {
                    downloadOnce(d, part);
                    finishSuccess(ctx, d, part, dest);
                    return;
                } catch (CancelledException e) {
                    abort(d, part);
                    return;
                } catch (IOException e) {
                    lastError = e.getMessage() != null ? e.getMessage() : "I/O error";
                    Log.w(TAG, fmt("Download #%d attempt %d failed: %s", id, attempt + 1, lastError));
                }
                if (d.cancelled.get()) {
                    abort(d, part);
                    return;
                }
                if (attempt < MAX_RETRIES) {
                    d.reason = ctx.getString(R.string.download_retrying, attempt + 1, MAX_RETRIES);
                    d.state = STATE_PAUSED;
                    sleep(Math.min(2000L * (attempt + 1), 10000L));
                }
            }
            fail(d, part, lastError != null ? lastError : "Download failed");
        } catch (Exception e) {
            Log.e(TAG, fmt("Download #%d failed", id), e);
            fail(d, part, e.getMessage() != null ? e.getMessage() : "Unknown error");
        }
    }

    private static void downloadOnce(Download d, File part) throws IOException {
        long existing = part.exists() ? part.length() : 0;
        // Re-resolve the redirect each attempt so a flaky mirror can be swapped
        // out (the server load-balances), like re-running `curl -L`.
        var url = resolveFinalUrl(d.url, d.userAgent);
        var conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", d.userAgent);
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");
        d.activeConn = conn;
        try {
            int code = conn.getResponseCode();
            boolean resumed = code == HttpURLConnection.HTTP_PARTIAL; // 206
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL)
                throw new IOException("HTTP " + code);
            Log.i(TAG, fmt("Download #%d %s from %s (HTTP %d)",
                d.id, existing > 0 ? "resuming@" + existing : "starting", conn.getURL(), code));
            // Server ignored our Range and is sending the whole file again.
            long start = resumed ? existing : 0;
            d.total = resolveTotal(conn, resumed, start);
            d.downloaded = start;
            d.state = STATE_RUNNING;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(part, resumed)) {
                long done = start;
                long windowStart = System.currentTimeMillis();
                long windowBytes = done;
                var buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (d.cancelled.get()) throw new CancelledException();
                    out.write(buf, 0, n);
                    done += n;
                    d.downloaded = done;
                    long now = System.currentTimeMillis();
                    if (now - windowStart >= STALL_WINDOW_MS) {
                        if (done - windowBytes < STALL_MIN_BYTES)
                            throw new IOException("Throughput too low");
                        windowStart = now;
                        windowBytes = done;
                    }
                }
                out.flush();
                // EOF before the expected size means the connection dropped early.
                if (d.total > 0 && done < d.total)
                    throw new IOException(fmt("Incomplete: %d/%d bytes", done, d.total));
            }
        } finally {
            d.activeConn = null;
            conn.disconnect();
        }
    }

    /**
     * Total file size for the progress bar. On a 206 resume the body may be
     * chunked (no Content-Length), so prefer the absolute total from the
     * {@code Content-Range: bytes a-b/total} header; otherwise derive it from
     * Content-Length. Returns -1 if genuinely unknown.
     */
    private static long resolveTotal(HttpURLConnection conn, boolean resumed, long start) {
        if (resumed) {
            var range = conn.getHeaderField("Content-Range"); // "bytes a-b/total"
            if (range != null) {
                int slash = range.lastIndexOf('/');
                if (slash >= 0) {
                    var total = range.substring(slash + 1).trim();
                    if (!total.equals("*")) {
                        try {
                            return Long.parseLong(total);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            long len = conn.getContentLengthLong();
            return len >= 0 ? start + len : -1;
        }
        long len = conn.getContentLengthLong();
        return len >= 0 ? len : -1;
    }

    private static void finishSuccess(Context ctx, Download d, File part, File dest)
        throws IOException {
        if (d.cancelled.get()) {
            abort(d, part);
            return;
        }
        if (dest.exists() && !dest.delete())
            throw new IOException(fmt("Cannot overwrite: %s", dest.getAbsolutePath()));
        if (!part.renameTo(dest)) {
            copyFile(part, dest);
            if (!part.delete()) Log.w(TAG, fmt("Failed to delete part: %s", part.getAbsolutePath()));
        }
        var config = registerDisk(ctx, d.name, d.folder);
        d.diskId = config.getId();
        d.reason = null;
        d.state = STATE_SUCCESS;
        Log.i(TAG, fmt("Download #%d complete -> %s", d.id, dest.getAbsolutePath()));
    }

    private static void abort(Download d, File part) {
        if (part.exists() && !part.delete())
            Log.w(TAG, fmt("Failed to delete part: %s", part.getAbsolutePath()));
        d.state = STATE_CANCELLED;
    }

    private static void fail(Download d, File part, String reason) {
        if (part.exists() && !part.delete())
            Log.w(TAG, fmt("Failed to delete part: %s", part.getAbsolutePath()));
        d.reason = reason;
        d.state = STATE_FAILED;
    }

    /**
     * Follows redirects (including cross-protocol http&harr;https) and returns the
     * final URL. Returns the original URL if resolution fails.
     */
    @NonNull
    static String resolveFinalUrl(@NonNull String url, @NonNull String userAgent) {
        var current = url;
        try {
            for (int i = 0; i < 6; i++) {
                var conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(CONNECT_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("Accept-Encoding", "identity");
                int code = conn.getResponseCode();
                String location = (code >= 300 && code < 400)
                    ? conn.getHeaderField("Location") : null;
                conn.disconnect();
                if (location == null || location.isEmpty()) break;
                current = new URL(new URL(current), location).toString();
            }
        } catch (Exception e) {
            Log.w(TAG, fmt("Redirect resolution failed for %s; using it as-is", url), e);
            return url;
        }
        if (!current.equals(url)) Log.i(TAG, fmt("Resolved %s -> %s", url, current));
        return current;
    }

    private static DiskConfig registerDisk(Context ctx, String name, String folder) {
        var store = new DiskStore();
        store.load(ctx);
        var config = new DiskConfig();
        config.setName(name);
        config.item.set("folder", folder);
        store.add(config);
        store.save(ctx);
        return config;
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            var buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        }
    }

    /** Drops finished jobs and any earlier job aimed at the same destination. */
    private static void purgeFinishedAndDuplicates(String folder, String name) {
        for (var e : JOBS.entrySet()) {
            var d = e.getValue();
            boolean duplicate = d.folder.equals(folder) && d.name.equals(name);
            boolean finished = d.state == STATE_SUCCESS
                || d.state == STATE_FAILED || d.state == STATE_CANCELLED;
            if (duplicate) d.cancelled.set(true);
            if (duplicate || finished) JOBS.remove(e.getKey());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Thrown to unwind the read loop on cancellation (not retried). */
    private static final class CancelledException extends IOException {
    }
}
