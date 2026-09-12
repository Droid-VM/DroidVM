// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.boot;

import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.vm.BootConfig;

/**
 * UI-side view of an image's boot entries: scans via the daemon's
 * {@code vm_bootscan} (disk images are usually only readable by the
 * daemon process) and mirrors {@code BootPlan}'s pin-matching rules so
 * the edit tab and the boot menu preview exactly what would boot.
 */
public final class BootEntries {
    @NonNull
    public final List<Entry> entries;

    private BootEntries(@NonNull List<Entry> entries) {
        this.entries = entries;
    }

    public interface OnScan {
        /** Called on the daemon executor thread; post to main as needed. */
        void onScan(@Nullable BootEntries result, @Nullable String error);
    }

    /** Live download progress from lbx's {@code progress:} lines. */
    public interface OnProgress {
        /** Chunks downloaded so far, and the file's total (-1 if unknown). */
        void onProgress(int loaded, int total);
    }

    /** One entry of `lbx entries --json`. */
    public static final class Entry {
        @Nullable
        public final String id;
        @Nullable
        public final String title;
        @Nullable
        public final String version;
        public final boolean isDefault;
        @NonNull
        public final String source;
        /**
         * What booting this entry means: {@code linux} (kernel + initrd, which is what direct
         * boot loads) or {@code windows} (an install that only firmware can start). An entry
         * from an older lbx carries no type and is a Linux one.
         */
        @NonNull
        public final String type;
        @NonNull
        public final String kernel;
        @NonNull
        public final List<String> initrd;
        @NonNull
        public final String cmdline;
        @NonNull
        public final String cmdlineFixed;
        /** CONFIG_DMA_RESTRICTED_POOL: TRUE / FALSE, or null when lbx
         *  could not determine it (no config-* file in the image). */
        @Nullable
        public final Boolean dmaRestrictedPool;

        Entry(@NonNull JSONObject o) {
            id = emptyToNull(optStr(o, "id"));
            title = emptyToNull(optStr(o, "title"));
            version = emptyToNull(optStr(o, "version"));
            isDefault = o.optBoolean("default", false);
            source = optStr(o, "source");
            var kind = optStr(o, "type");
            type = kind.isEmpty() ? TYPE_LINUX : kind;
            kernel = optStr(o, "kernel");
            cmdline = optStr(o, "cmdline");
            cmdlineFixed = optStr(o, "cmdline_fixed");
            dmaRestrictedPool = o.isNull("dma_restricted_pool")
                ? null : o.optBoolean("dma_restricted_pool", false);
            initrd = new ArrayList<>();
            var arr = o.optJSONArray("initrd");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                var s = arr.optString(i, "");
                if (!s.isEmpty()) initrd.add(s);
            }
        }

        @NonNull
        public String label() {
            if (title != null) return title;
            if (version != null) return version;
            if (id != null) return id;
            return "(untitled)";
        }

        @NonNull
        public String displayLabel(@NonNull Context ctx) {
            return isDefault
                ? ctx.getString(R.string.edit_vm_boot_entry_default_mark, label())
                : label();
        }

        /** The selection key sent as the start request's boot_entry. */
        @NonNull
        public String selectionKey() {
            return id != null ? id : label();
        }

        @NonNull
        public BootConfig.ImageEntry toImageEntry() {
            return new BootConfig.ImageEntry(id, title, version);
        }

        /** The cmdline that would boot, honoring the vdafix switch. */
        @NonNull
        public String effectiveCmdline(boolean vdafix) {
            return vdafix && !cmdlineFixed.isEmpty() ? cmdlineFixed : cmdline;
        }

        /**
         * Kernel is known to lack CONFIG_DMA_RESTRICTED_POOL, so its virtio
         * DMA cannot reach the host-shared pool and the devices fail under a
         * gunyah protected VM. Unknown (null) does not warn.
         */
        public boolean lacksRestrictedDmaPool() {
            return Boolean.FALSE.equals(dmaRestrictedPool);
        }

        /** Direct boot loads a kernel, so only a Linux entry is one it can start. */
        public boolean isLinux() {
            return TYPE_LINUX.equals(type);
        }

        public boolean isWindows() {
            return TYPE_WINDOWS.equals(type);
        }
    }

    public static final String TYPE_LINUX = "linux";
    public static final String TYPE_WINDOWS = "windows";

    /** The entries direct boot can actually start, in listing order. */
    @NonNull
    public List<Entry> linuxEntries() {
        var out = new ArrayList<Entry>();
        for (var e : entries)
            if (e.isLinux()) out.add(e);
        return out;
    }

    /** True when the image holds a Windows install (bootable only under firmware). */
    public boolean hasWindows() {
        for (var e : entries)
            if (e.isWindows()) return true;
        return false;
    }

    @Nullable
    private static String emptyToNull(@NonNull String s) {
        return s.isEmpty() ? null : s;
    }

    /**
     * Null-safe string read: org.json's optString turns a JSON null into
     * the literal string "null" instead of the fallback.
     */
    @NonNull
    static String optStr(@NonNull JSONObject o, @NonNull String key) {
        return o.isNull(key) ? "" : o.optString(key, "");
    }

    /** Asynchronous scan of {@code image} through the daemon. */
    public static void scan(@NonNull String image, @NonNull OnScan callback) {
        DaemonConnection.getInstance().buildRequest("vm_bootscan")
            .put("image", image)
            .onResponse(resp -> callback.onScan(
                parse(resp.optJSONArray("entries")), null))
            .onUnsuccessful(resp -> callback.onScan(
                null, resp.optString("message", "scan failed")))
            .onError(e -> callback.onScan(null, fmt("%s", e.getMessage())))
            .invoke();
    }

    /** Hard cap in case lbx wedges; its own --timeout handles normal stalls. */
    private static final long ANALYZE_HARD_TIMEOUT_MS = 5 * 60_000;

    /**
     * Analyze a remote (URL) image by running lbx directly from the UI -- a URL
     * fetch needs no root, and lbx ships as {@code liblbx.so} in the app's
     * nativeLibraryDir, which the app may execute (unlike the daemon's
     * data-dir copy). So we skip the daemon and stream lbx's
     * {@code progress: <n>/<m>} lines for a live count instead of a spinner.
     * Runs on a background thread; both callbacks fire there, so post to main.
     * {@code timeoutSecs} becomes lbx's per-connection {@code --timeout}, so a
     * stalled chunk download aborts on its own rather than hanging the UI.
     */
    public static void analyzeUrl(
        @NonNull Context ctx, @NonNull String url, int timeoutSecs,
        @NonNull OnProgress onProgress, @NonNull OnScan onScan
    ) {
        var lbx = pathJoin(ctx.getApplicationInfo().nativeLibraryDir, "liblbx.so");
        var thread = new Thread(
            () -> runAnalyze(lbx, url, timeoutSecs, onProgress, onScan), "lbx-analyze");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runAnalyze(
        @NonNull String lbx, @NonNull String url, int timeoutSecs,
        @NonNull OnProgress onProgress, @NonNull OnScan onScan
    ) {
        Process process = null;
        try {
            // --quick: this URL preview only needs the entry list + cmdline +
            // dma warning, so skip the per-entry kernel size/compression reads
            // (extra range fetches a listing never uses). The local scan
            // (vm_bootscan) stays on the full command for BootPlan.
            process = new ProcessBuilder(
                lbx, "--timeout", String.valueOf(timeoutSecs),
                "entries", url, "--json", "--quick"
            ).start();
            var proc = process;
            // Drain stdout (the JSON) on its own thread so the pipe never
            // blocks while we read stderr line by line.
            var stdout = new ByteArrayOutputStream();
            var stdoutThread = new Thread(() -> {
                try {
                    proc.getInputStream().transferTo(stdout);
                } catch (IOException ignored) {
                }
            });
            stdoutThread.start();
            var errBuf = new StringBuilder();
            try (var br = new BufferedReader(new InputStreamReader(
                proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!reportProgress(line, onProgress))
                        errBuf.append(line).append('\n');
                }
            }
            stdoutThread.join();
            if (!proc.waitFor(ANALYZE_HARD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                onScan.onScan(null, "analysis timed out");
                return;
            }
            int code = proc.exitValue();
            if (code == 0) {
                onScan.onScan(parse(new JSONArray(stdout.toString(StandardCharsets.UTF_8))), null);
            } else {
                // Surface lbx's real stderr rather than a generic error.
                var msg = errBuf.toString().trim();
                onScan.onScan(null, msg.isEmpty() ? fmt("lbx exited with %d", code) : msg);
            }
        } catch (Exception e) {
            onScan.onScan(null, e.getMessage() != null ? e.getMessage() : "analysis failed");
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** Parse a {@code progress: <n>/<m>} line, reporting it; false otherwise. */
    private static boolean reportProgress(@NonNull String line, @NonNull OnProgress cb) {
        var t = line.trim();
        if (!t.startsWith("progress:")) return false;
        var rest = t.substring("progress:".length()).trim();
        int slash = rest.indexOf('/');
        try {
            int loaded = Integer.parseInt((slash < 0 ? rest : rest.substring(0, slash)).trim());
            int total = slash < 0 ? -1 : Integer.parseInt(rest.substring(slash + 1).trim());
            cb.onProgress(loaded, total);
        } catch (NumberFormatException ignored) {
            return false; // not actually a progress line
        }
        return true;
    }

    @NonNull
    private static BootEntries parse(@Nullable JSONArray arr) {
        var list = new ArrayList<Entry>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            var o = arr.optJSONObject(i);
            if (o != null) list.add(new Entry(o));
        }
        return new BootEntries(list);
    }

    /**
     * The entry a boot would use right now: pinned entry by exact id,
     * then exact title, falling back to the bootloader default -- the
     * same cascade as the daemon's BootPlan.
     *
     * <p>Only Linux entries are candidates, there as here. A Windows entry
     * is in the list because the image boots it -- on a dual-boot disk it
     * may even be what the bootloader would pick -- but direct boot loads
     * a kernel, and that entry has none.</p>
     */
    @Nullable
    public Entry resolve(@Nullable BootConfig.ImageEntry pinned) {
        if (pinned != null) {
            for (var e : linuxEntries())
                if (pinned.id != null && pinned.id.equals(e.id)) return e;
            for (var e : linuxEntries())
                if (pinned.title != null && pinned.title.equals(e.title)) return e;
        }
        return defaultEntry();
    }

    /** True when {@code pinned} no longer matches any entry direct boot can start. */
    public boolean isFallback(@Nullable BootConfig.ImageEntry pinned) {
        if (pinned == null) return false;
        for (var e : linuxEntries()) {
            if (pinned.id != null && pinned.id.equals(e.id)) return false;
            if (pinned.title != null && pinned.title.equals(e.title)) return false;
        }
        return true;
    }

    /**
     * What the image's own bootloader would start, whatever type it is -- lbx resolves that the
     * way the bootloader would (GRUB's {@code set default}, a BLS pin, version order).
     *
     * <p>This is the entry that comes up under UEFI, where the guest boots itself and nothing
     * here picks anything. {@link #defaultEntry()} is the direct-boot answer instead: it takes
     * over from the bootloader, so it can only pick an entry that has a kernel.</p>
     */
    @Nullable
    public Entry bootloaderDefault() {
        for (var e : entries)
            if (e.isDefault) return e;
        return entries.isEmpty() ? null : entries.get(0);
    }

    @Nullable
    public Entry defaultEntry() {
        var bootable = linuxEntries();
        for (var e : bootable)
            if (e.isDefault) return e;
        return bootable.isEmpty() ? null : bootable.get(0);
    }
}
