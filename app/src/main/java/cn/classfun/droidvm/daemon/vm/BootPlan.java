package cn.classfun.droidvm.daemon.vm;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_INITRD;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_KERNEL;
import static cn.classfun.droidvm.lib.Constants.PATH_EDK2_FIRMWARE;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getAssetBinaryPath;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import cn.classfun.droidvm.lib.natives.NativeProcess;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.BootConfig;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

/**
 * A VM's boot configuration resolved into concrete backend inputs:
 * either "run the UEFI firmware" or "direct-boot this kernel/initrd/
 * cmdline". For {@code linux+image} this is where lbx runs against the
 * guest disk: scan entries, match the pinned entry (id, then title,
 * then bootloader default), then extract kernel/initrd into a per-VM
 * cache. The cache is validated by URI + file size (inode metadata, no
 * reads) and then a content MD5 of each source file, so an in-place
 * kernel rebuild that kept the same path and size is still caught. The
 * MD5 reads the source kernel/initrd out of the image, so a cache hit is
 * no longer read-free — but it only happens once the metadata check has
 * matched, and a hit still writes nothing.
 *
 * <p>A distro {@code vmlinuz} is often a compressed kernel a direct-boot
 * VMM can't load as-is (arm64 gzip {@code Image.gz}, or EFI zboot).
 * {@code lbx entries} reports this per entry as {@code kernel_compression}
 * (null = already a raw {@code Image}/x86 bzImage); when set we extract and
 * MD5 the <em>decompressed</em> kernel ({@code lbx cp/md5 --decompress}) so
 * the cached file boots directly. lbx stays faithful by default — the flag
 * is only passed for a kernel lbx flagged as compressed, never the initrd.
 */
public final class BootPlan {
    private static final String TAG = "BootPlan";
    private static final String CACHE_ROOT = pathJoin(DATA_DIR, "cache", "boot");
    private static final long LBX_TIMEOUT_MS = 30_000;

    public final boolean uefi;
    /** Custom UEFI firmware path; empty = builtin (QEMU honors, crosvm ignores). */
    @NonNull
    public final String firmware;
    @NonNull
    public final String kernel;
    @NonNull
    public final String initrd;
    @NonNull
    public final String cmdline;
    /** Image mode: title of the entry actually booted (for events/logs). */
    @Nullable
    public final String entryTitle;
    /** Image mode: a pinned/override entry was not found, default used. */
    public final boolean entryFallback;

    private BootPlan(
        boolean uefi, @NonNull String firmware, @NonNull String kernel,
        @NonNull String initrd, @NonNull String cmdline,
        @Nullable String entryTitle, boolean entryFallback
    ) {
        this.uefi = uefi;
        this.firmware = firmware;
        this.kernel = kernel;
        this.initrd = initrd;
        this.cmdline = cmdline;
        this.entryTitle = entryTitle;
        this.entryFallback = entryFallback;
    }

    /**
     * The plan prepared by {@link VMInstance} before backend start; falls
     * back to resolving in place (UEFI/manual never block, and image mode
     * resolution throws on scan failure).
     */
    @NonNull
    public static BootPlan of(@NonNull VMConfig config) {
        if (config instanceof VMInstance) {
            var plan = ((VMInstance) config).getBootPlan();
            if (plan != null) return plan;
        }
        try {
            return resolve(config, null);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @NonNull
    public static BootPlan resolve(
        @NonNull VMConfig config,
        @Nullable String entryOverrideId
    ) throws IOException {
        var boot = BootConfig.of(config);
        // one-shot "boot this disk with DroidVM's built-in kernel": a manual
        // direct-boot of the bundled kernel/initrd, with root= carried over
        // from the image's entry so the same rootfs comes up (see resolveBuiltin)
        if (BootConfig.BUILTIN_ENTRY_KEY.equals(entryOverrideId))
            return resolveBuiltin(config, boot);
        if (boot.getProtocol() == BootConfig.Protocol.UEFI)
            return new BootPlan(true, boot.getUefiFirmware(), "", "", "", null, false);
        if (boot.getLinuxSource() == BootConfig.LinuxSource.MANUAL) {
            var kernel = boot.getKernel();
            // pre-boot{} configs stored the EDK2 path as the kernel
            if (kernel.equals(PATH_EDK2_FIRMWARE))
                return new BootPlan(true, "", "", "", "", null, false);
            return new BootPlan(
                false, "", kernel, boot.getInitrd(), boot.getCmdline(), null, false
            );
        }
        return resolveImage(config, boot, entryOverrideId);
    }

    /**
     * "Boot this disk with DroidVM's built-in kernel": a direct boot of the
     * bundled {@code vmlinuz}/{@code initramfs} rather than the guest's own
     * kernel. The cmdline is the image cmdline override if set, else the
     * resolved entry's effective (vdafix-adjusted) cmdline so the right
     * {@code root=} comes up, falling back to {@link BootConfig#DEFAULT_MANUAL_CMDLINE}
     * when the image can't be scanned. Persisting this choice instead
     * rewrites the config to a plain manual source (see {@code VMActions}).
     */
    @NonNull
    private static BootPlan resolveBuiltin(
        @NonNull VMConfig config, @NonNull BootConfig boot) {
        return new BootPlan(
            false, "", PATH_BUILTIN_KERNEL, PATH_BUILTIN_INITRD,
            builtinCmdline(config, boot), "DroidVM built-in kernel", false
        );
    }

    @NonNull
    private static String builtinCmdline(
        @NonNull VMConfig config, @NonNull BootConfig boot) {
        var override = boot.getImageCmdline();
        if (!override.isEmpty()) return override;
        try {
            var entries = scanEntries(findImagePath(config, boot.getImageDisk()));
            if (entries.length() > 0) {
                var pinned = boot.getImageEntry();
                var entry = pinned != null
                    ? matchEntry(entries, pinned.id, pinned.title) : null;
                if (entry == null) entry = defaultEntry(entries);
                if (entry != null) {
                    var fixed = boot.isVdafix() ? optStr(entry, "cmdline_fixed") : "";
                    var cmdline = !fixed.isEmpty() ? fixed : optStr(entry, "cmdline");
                    if (!cmdline.isEmpty()) return cmdline;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, fmt(
                "builtin-kernel cmdline scan failed, using default: %s", e.getMessage()));
        }
        return BootConfig.DEFAULT_MANUAL_CMDLINE;
    }

    // --- linux+image ---

    @NonNull
    private static BootPlan resolveImage(
        @NonNull VMConfig config,
        @NonNull BootConfig boot,
        @Nullable String entryOverrideId
    ) throws IOException {
        var image = findImagePath(config, boot.getImageDisk());
        var entries = scanEntries(image);
        if (entries.length() == 0)
            throw new IOException(fmt("no boot entries found in %s", image));

        var pinned = boot.getImageEntry();
        JSONObject entry = null;
        var fallback = false;
        if (entryOverrideId != null)
            entry = matchEntry(entries, entryOverrideId, entryOverrideId);
        else if (pinned != null)
            entry = matchEntry(entries, pinned.id, pinned.title);
        if (entry == null) {
            fallback = entryOverrideId != null || pinned != null;
            entry = defaultEntry(entries);
        }
        if (fallback)
            Log.w(TAG, fmt(
                "VM %s: pinned boot entry not found in %s, using bootloader default",
                config.getName(), image
            ));

        var kernelUri = optStr(entry, "kernel");
        if (kernelUri.isEmpty())
            throw new IOException("selected boot entry has no kernel");
        var cacheDir = new File(pathJoin(CACHE_ROOT, config.getId().toString()));
        extractCached(image, entry, cacheDir);

        var cmdline = boot.getImageCmdline();
        if (cmdline.isEmpty()) {
            var fixed = boot.isVdafix() ? optStr(entry, "cmdline_fixed") : "";
            cmdline = !fixed.isEmpty() ? fixed : optStr(entry, "cmdline");
        }
        var initrd = new File(cacheDir, "initrd");
        var title = optStr(entry, "title");
        return new BootPlan(
            false, "",
            new File(cacheDir, "kernel").getAbsolutePath(),
            initrd.exists() ? initrd.getAbsolutePath() : "",
            cmdline,
            title.isEmpty() ? null : title,
            fallback
        );
    }

    @NonNull
    private static String findImagePath(@NonNull VMConfig config, int diskIndex) throws IOException {
        var disks = config.item.opt("disks", null);
        if (disks != null && disks.is(DataItem.Type.ARRAY)) {
            var arr = disks.asArray();
            // configured index first, then any disk with a path
            if (diskIndex >= 0 && diskIndex < arr.size()) {
                var path = arr.get(diskIndex).optString("path", "");
                if (!path.isEmpty()) return path;
            }
            for (var disk : arr) {
                var path = disk.optString("path", "");
                if (!path.isEmpty()) return path;
            }
        }
        throw new IOException("boot from disk image: VM has no disk with a path");
    }

    /** All entries of the image, as reported by {@code lbx entries --json}. */
    @NonNull
    public static JSONArray scanEntries(@NonNull String image) throws IOException {
        var out = runLbx("entries", image, "--json");
        try {
            return new JSONArray(out);
        } catch (Exception e) {
            throw new IOException(fmt("bad lbx output for %s: %s", image, e.getMessage()));
        }
    }

    /**
     * Whether {@code image} stores zlib-compressed qcow2 clusters, which the
     * crosvm backend cannot read: the guest gets I/O errors and an
     * unreadable partition table, so every {@code root=} form hangs waiting
     * for a root device that never appears. Runs {@code lbx compat --json};
     * any lbx failure returns {@code false} so a scan hiccup never blocks a
     * start (a real boot would surface the problem anyway).
     */
    public static boolean hasCompressedClusters(@NonNull String image) {
        try {
            var out = runLbx("compat", image, "--json").trim();
            return new JSONObject(out).optBoolean("compressed_clusters", false);
        } catch (Exception e) {
            Log.w(TAG, fmt("compat check failed for %s: %s", image, e.getMessage()));
            return false;
        }
    }

    @Nullable
    private static JSONObject matchEntry(
        @NonNull JSONArray entries,
        @Nullable String id,
        @Nullable String title
    ) {
        for (int i = 0; i < entries.length(); i++) {
            var e = entries.optJSONObject(i);
            if (e == null) continue;
            if (id != null && !id.isEmpty() && id.equals(optStr(e, "id")))
                return e;
        }
        for (int i = 0; i < entries.length(); i++) {
            var e = entries.optJSONObject(i);
            if (e == null) continue;
            if (title != null && !title.isEmpty() && title.equals(optStr(e, "title")))
                return e;
        }
        return null;
    }

    /**
     * Null-safe string read: org.json's optString turns a JSON null into
     * the literal string "null" instead of the fallback.
     */
    @NonNull
    private static String optStr(@NonNull JSONObject o, @NonNull String key) {
        return o.isNull(key) ? "" : o.optString(key, "");
    }

    @NonNull
    private static JSONObject defaultEntry(@NonNull JSONArray entries) {
        for (int i = 0; i < entries.length(); i++) {
            var e = entries.optJSONObject(i);
            if (e != null && e.optBoolean("default", false)) return e;
        }
        return entries.optJSONObject(0);
    }

    /**
     * Ensures {@code cacheDir} holds this entry's kernel and (concatenated)
     * initrd, re-extracting unless {@link #cacheUpToDate} confirms the
     * cache still matches the guest (URI+size, then a content MD5).
     */
    private static void extractCached(
        @NonNull String image,
        @NonNull JSONObject entry,
        @NonNull File cacheDir
    ) throws IOException {
        var manifest = new File(cacheDir, "manifest.json");
        var kernelFile = new File(cacheDir, "kernel");
        if (manifest.exists() && kernelFile.exists()
            && entry.optLong("kernel_size", -1) > 0
            && cacheUpToDate(image, entry, manifest)) {
            Log.i(TAG, fmt("boot cache hit: %s", cacheDir));
            return;
        }

        Log.i(TAG, fmt("extracting boot files from %s to %s", image, cacheDir));
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs())
            throw new IOException(fmt("cannot create %s", cacheDir));
        deleteQuiet(manifest); // invalidate while files are in flux
        var kernelDest = new File(cacheDir, "kernel").getAbsolutePath();
        if (kernelCompressed(entry))
            runLbx("cp", image, optStr(entry, "kernel"), kernelDest, "--decompress");
        else
            runLbx("cp", image, optStr(entry, "kernel"), kernelDest);
        var initrds = entry.optJSONArray("initrd");
        var initrdFile = new File(cacheDir, "initrd");
        deleteQuiet(initrdFile);
        if (initrds != null && initrds.length() > 0) {
            // microcode early-cpio + initramfs: concatenate in order
            try (var out = new FileOutputStream(initrdFile)) {
                for (int i = 0; i < initrds.length(); i++) {
                    var part = new File(cacheDir, fmt("initrd.%d", i));
                    runLbx("cp", image, initrds.optString(i, ""), part.getAbsolutePath());
                    Files.copy(part.toPath(), out);
                    deleteQuiet(part);
                }
            }
        }
        Files.write(
            manifest.toPath(),
            buildManifest(image, entry).toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether the cache described by {@code manifest} still reflects the
     * guest. Cheap identity first — image, URIs and sizes, all from inode
     * metadata with no data reads — then a content MD5 of each source file
     * via {@code lbx md5}. The MD5 is what catches an in-place kernel
     * rebuild that kept the same path and byte size; it reads the source
     * kernel/initrd out of the image, so it only runs once the metadata
     * check has already matched (i.e. on an otherwise-unchanged guest).
     */
    private static boolean cacheUpToDate(
        @NonNull String image,
        @NonNull JSONObject entry,
        @NonNull File manifestFile
    ) {
        JSONObject old;
        try {
            old = new JSONObject(new String(
                Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
        if (!image.equals(old.optString("image", null))) return false;
        var kernelUri = optStr(entry, "kernel");
        if (!kernelUri.equals(old.optString("kernel", null))) return false;
        if (entry.optLong("kernel_size", -1) != old.optLong("kernel_size", Long.MIN_VALUE))
            return false;
        if (!sameArray(entry.optJSONArray("initrd"), old.optJSONArray("initrd")))
            return false;
        if (!sameArray(entry.optJSONArray("initrd_size"), old.optJSONArray("initrd_size")))
            return false;
        try {
            if (!lbxMd5(image, kernelUri, kernelCompressed(entry))
                    .equals(old.optString("kernel_md5", "")))
                return false;
            var initrds = entry.optJSONArray("initrd");
            var oldMd5 = old.optJSONArray("initrd_md5");
            int n = initrds == null ? 0 : initrds.length();
            if (oldMd5 == null || oldMd5.length() != n) return false;
            for (int i = 0; i < n; i++)
                if (!lbxMd5(image, initrds.optString(i, "")).equals(oldMd5.optString(i, "")))
                    return false;
        } catch (IOException e) {
            // lbx md5 unavailable or failed: don't trust the cache
            Log.w(TAG, fmt("cache md5 check failed, re-extracting: %s", e.getMessage()));
            return false;
        }
        return true;
    }

    /** Metadata key plus a content MD5 of each source file. */
    @NonNull
    private static JSONObject buildManifest(
        @NonNull String image, @NonNull JSONObject entry) throws IOException {
        var key = new JSONObject();
        try {
            var kernelUri = optStr(entry, "kernel");
            key.put("image", image);
            key.put("kernel", kernelUri);
            key.put("kernel_size", entry.optLong("kernel_size", -1));
            key.put("kernel_md5", lbxMd5(image, kernelUri, kernelCompressed(entry)));
            var initrds = entry.optJSONArray("initrd");
            key.put("initrd", initrds);
            key.put("initrd_size", entry.optJSONArray("initrd_size"));
            var md5s = new JSONArray();
            if (initrds != null)
                for (int i = 0; i < initrds.length(); i++)
                    md5s.put(lbxMd5(image, initrds.optString(i, "")));
            key.put("initrd_md5", md5s);
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
        return key;
    }

    /**
     * Whether this entry's kernel is a compressed {@code vmlinuz} (gzip
     * {@code Image.gz} or EFI zboot) that a direct-kernel-boot VMM can't
     * load as-is, per lbx's {@code kernel_compression} field (empty/null
     * when the kernel is already a raw {@code Image} or x86 bzImage). When
     * set, the kernel is extracted and MD5'd in its decompressed form.
     */
    private static boolean kernelCompressed(@NonNull JSONObject entry) {
        return !optStr(entry, "kernel_compression").isEmpty();
    }

    /** The hex digest from {@code lbx md5 <image> <uri>} ("<hex>  <name>"). */
    @NonNull
    private static String lbxMd5(@NonNull String image, @NonNull String uri)
        throws IOException {
        return lbxMd5(image, uri, false);
    }

    /**
     * As {@link #lbxMd5(String, String)}, but with {@code decompress} the
     * digest is of the kernel as {@code cp --decompress} would write it, so
     * it matches the extracted cache file rather than the raw stored bytes.
     */
    @NonNull
    private static String lbxMd5(@NonNull String image, @NonNull String uri, boolean decompress)
        throws IOException {
        if (uri.isEmpty()) return "";
        var out = (decompress
            ? runLbx("md5", image, uri, "--decompress")
            : runLbx("md5", image, uri)).trim();
        int sp = out.indexOf(' ');
        return sp > 0 ? out.substring(0, sp) : out;
    }

    private static boolean sameArray(
        @Nullable JSONArray a, @Nullable JSONArray b) {
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static void deleteQuiet(@NonNull File f) {
        if (f.exists() && !f.delete())
            Log.w(TAG, fmt("failed to delete %s", f));
    }

    /** Runs lbx, returning stdout; non-zero exit becomes an IOException. */
    @NonNull
    private static String runLbx(@NonNull String... args) throws IOException {
        var argv = new String[args.length + 1];
        argv[0] = getAssetBinaryPath("lbx");
        System.arraycopy(args, 0, argv, 1, args.length);
        var process = new NativeProcess.Builder(argv).start();
        try {
            var stdout = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var stderr = new String(
                process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int code;
            try {
                if (!process.waitFor(LBX_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroy();
                    throw new IOException(fmt("lbx timed out: %s", String.join(" ", argv)));
                }
                code = process.exitValue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while running lbx");
            }
            if (code != 0)
                throw new IOException(fmt(
                    "lbx failed (%d): %s", code, stderr.trim().isEmpty()
                        ? String.join(" ", argv) : stderr.trim()
                ));
            return stdout;
        } finally {
            process.close();
        }
    }
}
