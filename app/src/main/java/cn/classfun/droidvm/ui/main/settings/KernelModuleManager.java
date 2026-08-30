// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.main.settings;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import cn.classfun.droidvm.lib.utils.RunUtils;

/**
 * Manages the host kernel modules the app ships under {@code usr/lib/modules/<kmi>/} (currently the
 * gunyah_share SHARE-blob module used by GuestAccept). Selects the {@code .ko} whose KMI directory
 * matches the running kernel, loads/unloads it via root {@code insmod}/{@code rmmod}, and can
 * auto-load a user-chosen set at app start. All exec goes through {@link RunUtils} (root/libsu), so
 * every method here touches root and must be called off the main thread.
 */
public final class KernelModuleManager {
    private static final String PREFS = "droidvm_prefs";
    private static final String KEY_AUTOSTART = "kernel_module_autostart";
    private static final String MODULES_ROOT = pathJoin(DATA_DIR, "usr", "lib", "modules");
    // Leading major.minor of `uname -r` (e.g. "6.6.30-android15-..." -> "6.6"), matched against the
    // KMI dir names ("android15-6.6", "android16-6.12") to pick the right build for this kernel.
    private static final Pattern KVER = Pattern.compile("^(\\d+\\.\\d+)");

    /**
     * Modules seen loaded on this device this session -- the gate on arming autostart.
     *
     * Deliberately in memory only. It is a hint, not a setting: the point is that a module must
     * have demonstrably loaded before it is put in the daemon's startup path, and a fresh process
     * can re-establish that for free from /proc/modules (see list()). Persisting it would outlive
     * the kernel it was true for -- an OTA changes the KMI and the claim silently stops holding.
     */
    private static final Set<String> VERIFIED = ConcurrentHashMap.newKeySet();

    private KernelModuleManager() {
    }

    public static final class Module {
        public final String name;    // normalized module name, e.g. "gunyah_host_share_gki_6.6"
        public final String display; // human title for the card; name stays the load/prefs key
        public final String path;    // absolute .ko path for this device's KMI
        public final boolean loaded;

        Module(@NonNull String name, @NonNull String display, @NonNull String path,
               boolean loaded) {
            this.name = name;
            this.display = display;
            this.path = path;
            this.loaded = loaded;
        }
    }

    // The per-KMI suffix disambiguates the .ko files and /sys/module entries, nothing more.
    // The list header already states the KMI, so a module nobody named still displays without it.
    private static final Pattern GKI_SUFFIX = Pattern.compile("_gki_\\d+\\.\\d+$");

    /** Card title: the {@code names} entry from match.json, else the name minus its KMI tag. */
    @NonNull
    private static String displayNameFor(@NonNull String name) {
        var explicit = KernelModuleMatch.displayName(name);
        return explicit != null ? explicit : GKI_SUFFIX.matcher(name).replaceFirst("");
    }

    /** The {@code modules/<kmi>} dir whose name embeds the running kernel's major.minor, else null. */
    @Nullable
    private static File deviceKmiDir() {
        var subs = new File(MODULES_ROOT).listFiles(File::isDirectory);
        if (subs == null || subs.length == 0) return null;
        var m = KVER.matcher(RunUtils.runList("uname", "-r").getOutString().trim());
        if (m.find()) {
            String mmver = m.group(1);
            // Match the version as a whole "-<major.minor>" token, not a substring: a plain
            // contains() would let a 6.1 kernel match an "android16-6.12" dir ("6.12" contains
            // "6.1"). Require the char after the version to be a non-digit or end-of-name.
            var quoted = Pattern.quote(fmt("-%s", mmver));
            var tok = Pattern.compile(fmt("%s(\\D|$)", quoted));
            for (var d : subs)
                if (tok.matcher(d.getName()).find()) return d;
        }
        return subs[0]; // fallback: single/first KMI dir
    }

    /** Name of the KMI dir picked for this device (e.g. "android15-6.6"), or null. Runs root exec. */
    @Nullable
    public static String deviceKmi() {
        var d = deviceKmiDir();
        return d == null ? null : d.getName();
    }

    /** Names of currently-loaded modules, from {@code /proc/modules} (needs root). */
    @NonNull
    private static Set<String> loadedNames() {
        var set = new HashSet<String>();
        var r = RunUtils.run("cat /proc/modules");
        if (r.isSuccess()) {
            for (var line : r.getOutString().split("\n")) {
                int sp = line.indexOf(' ');
                if (sp > 0) set.add(line.substring(0, sp).trim());
            }
        }
        return set;
    }

    /**
     * Shipped modules that apply to this device: the KMI directory selects the ones built for this
     * kernel, {@link KernelModuleMatch} then drops the ones built for a different SoC. Both halves
     * are needed -- a Gunyah module is a matching KMI and a wrong device on a MediaTek phone.
     */
    @NonNull
    public static List<Module> list(@NonNull Context ctx) {
        var out = new ArrayList<Module>();
        var dir = deviceKmiDir();
        if (dir == null) return out;
        var kos = dir.listFiles((d, n) -> n.endsWith(".ko"));
        if (kos == null) return out;
        KernelModuleMatch.preload(ctx);
        var loaded = loadedNames();
        for (var ko : kos) {
            String base = ko.getName().substring(0, ko.getName().length() - 3);
            String name = base.replace('-', '_'); // /proc/modules uses underscores
            if (!KernelModuleMatch.allows(name)) continue;
            boolean isLoaded = loaded.contains(name);
            // Already loaded -- however it got there, autostart is answerable for it now.
            if (isLoaded) VERIFIED.add(name);
            out.add(new Module(name, displayNameFor(name), ko.getAbsolutePath(), isLoaded));
        }
        return out;
    }

    /** {@code insmod} the module; true on success. */
    public static boolean load(@NonNull String path) {
    }

    /**
     * {@code insmod} and, on success, record that this module loads on this device -- which is
     * what {@link #setAutostart} requires before it will arm one. Use this for user-initiated
     * loads; {@link #applyAutostart} deliberately does not, since a module can only get there by
     * having been verified already.
     */
    public static boolean loadAndVerify(@NonNull Module mod) {
        if (!load(mod.path)) return false;
        VERIFIED.add(mod.name);
        return true;
    }

    /** Has this module been seen loaded this session? Arming autostart requires it. */
    public static boolean isVerified(@NonNull String name) {
        return VERIFIED.contains(name);
    }

    /** {@code rmmod} the module by name; true on success. */
    public static boolean unload(@NonNull String name) {
        return RunUtils.run("rmmod %s", name).isSuccess();
    }

    // ---- autostart persistence ----

    @NonNull
    private static SharedPreferences prefs(@NonNull Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    private static Set<String> autostartSet(@NonNull Context ctx) {
        // getStringSet's result must not be mutated; copy it.
        return new HashSet<>(prefs(ctx).getStringSet(KEY_AUTOSTART, new HashSet<>()));
    }

    public static boolean isAutostart(@NonNull Context ctx, @NonNull String name) {
        return autostartSet(ctx).contains(name);
    }

    /**
     * Arm or disarm autostart. Arming is refused for a module that has never loaded here: the
     * caller is expected to have greyed the control out, and this is the backstop that keeps an
     * unverified module out of the boot path regardless. Returns false when refused.
     */
    public static boolean setAutostart(@NonNull Context ctx, @NonNull String name, boolean enabled) {
        if (enabled && !isVerified(name)) return false;
        var set = autostartSet(ctx);
        if (enabled) set.add(name);
        else set.remove(name);
        prefs(ctx).edit().putStringSet(KEY_AUTOSTART, set).apply();
        return true;
    }

    /**
     * Loads every autostart-enabled module that isn't already loaded. Call off the main thread.
     * Safe to call repeatedly (skips already-loaded modules).
     */
    public static void applyAutostart(@NonNull Context ctx) {
        var enabled = autostartSet(ctx);
        if (enabled.isEmpty()) return;
        for (var mod : list(ctx)) {
            if (enabled.contains(mod.name) && !mod.loaded)
                load(mod.path);
        }
    }
}
