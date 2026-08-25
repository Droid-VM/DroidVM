// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.enums.Enums;

/**
 * One entry of a VM config's "screens" object -- one screen, and the one exporter bound to it.
 *
 * <p>A screen is a display device the VM has: {@code gpu-0} is the virtio-gpu device's screen,
 * {@code simplefb} is the simplefb device's, and the two are independent devices rather than two
 * settings of one. The ids are exactly the tokens crosvm's {@code screen=} takes, because they
 * are handed to it verbatim; they are also the {@code stable_id} the app stores when the user
 * picks a screen to open, so they must never be renamed.</p>
 *
 * <p>The entry is keyed by that id rather than sitting in an array, so "one binding per screen"
 * -- which crosvm rejects a command line for violating -- cannot be expressed wrongly here in
 * the first place.</p>
 *
 * <p>{@code enabled} says the VM has that display device at all; the exporter says who watches
 * it. A screen with no exporter is a legal, ordinary state (the guest still has the device);
 * crosvm accepts it too.</p>
 */
public final class VMScreenConfig {
    /** Key of the screens object on the VM config. */
    public static final String KEY = "screens";

    /** The virtio-gpu device's screen. Only exists when the VM has a GPU. */
    public static final String ID_GPU0 = "gpu-0";
    /** The simplefb device's screen; its geometry is fixed by the device tree. */
    public static final String ID_SIMPLEFB = "simplefb";
    /** Every screen id, in the order the UI lists them. Iteration order is part of the schema. */
    public static final String[] IDS = {ID_GPU0, ID_SIMPLEFB};

    /** Sub-object holding this screen's VNC server settings, when its exporter is VNC. */
    private static final String KEY_VNC = "vnc";

    public final String id;
    public final DataItem item;

    public VMScreenConfig(@NonNull String id, @NonNull DataItem item) {
        this.id = id;
        this.item = item;
    }

    /** Whether this screen's display device is configured for the VM. */
    public boolean isEnabled() {
        return item.optBoolean("enabled", false);
    }

    public void setEnabled(boolean enabled) {
        item.set("enabled", enabled);
    }

    @NonNull
    public DisplayExporter getExporter() {
        return Enums.optEnum(item, "exporter", DisplayExporter.NONE);
    }

    public void setExporter(@NonNull DisplayExporter exporter) {
        item.set("exporter", exporter);
    }

    /** True for the screen the virtio-gpu device provides, which needs the GPU enabled. */
    public boolean isGpu() {
        return ID_GPU0.equals(id);
    }

    /** This screen's VNC sub-object, created if it is not there yet. */
    @NonNull
    public DataItem vnc() {
        var vnc = item.opt(KEY_VNC, (DataItem) null);
        if (vnc == null || !vnc.is(DataItem.Type.OBJECT)) {
            item.set(KEY_VNC, DataItem.newObject());
            vnc = item.get(KEY_VNC);
        }
        return vnc;
    }

    /** Listen address; empty means crosvm's own default (the IPv4 wildcard). */
    @NonNull
    public String getVncHost() {
        return str(vnc().optString("host", ""));
    }

    public void setVncHost(@NonNull String host) {
        vnc().set("host", host);
    }

    /** Listen port, or -1 when one has not been assigned yet (the daemon picks one on start). */
    public long getVncPort() {
        return vnc().optLong("port", -1);
    }

    public void setVncPort(long port) {
        vnc().set("port", port);
    }

    public boolean isVncPasswordAuth() {
        return vnc().optBoolean("password_auth", false);
    }

    public void setVncPasswordAuth(boolean auth) {
        vnc().set("password_auth", auth);
    }

    @NonNull
    public String getVncPassword() {
        return str(vnc().optString("password", ""));
    }

    public void setVncPassword(@NonNull String password) {
        vnc().set("password", password);
    }

    @StringRes
    public int getNameStringId() {
        return isGpu() ? R.string.create_vm_screen_gpu0 : R.string.create_vm_screen_simplefb;
    }

    /** Human-facing name of the screen: "Virtio-GPU screen", "SimpleFB screen". */
    @NonNull
    public String getDisplayName(@NonNull Context ctx) {
        return ctx.getString(getNameStringId());
    }

    @NonNull
    private static String str(@Nullable String s) {
        return s == null ? "" : s;
    }

    /** The screens object on {@code config}, created if absent. */
    @NonNull
    private static DataItem screensOf(@NonNull DataItem config) {
        var screens = config.opt(KEY, (DataItem) null);
        if (screens == null || !screens.is(DataItem.Type.OBJECT)) {
            config.set(KEY, DataItem.newObject());
            screens = config.get(KEY);
        }
        return screens;
    }

    /** The entry for [id], created empty if the config has none yet. */
    @NonNull
    public static VMScreenConfig of(@NonNull DataItem config, @NonNull String id) {
        var screens = screensOf(config);
        var entry = screens.opt(id, (DataItem) null);
        if (entry == null || !entry.is(DataItem.Type.OBJECT)) {
            screens.set(id, DataItem.newObject());
            entry = screens.get(id);
        }
        return new VMScreenConfig(id, entry);
    }

    /** The entry for [id], or null when the config does not describe that screen. */
    @Nullable
    public static VMScreenConfig find(@NonNull DataItem config, @NonNull String id) {
        var screens = config.opt(KEY, (DataItem) null);
        if (screens == null || !screens.is(DataItem.Type.OBJECT)) return null;
        var entry = screens.opt(id, (DataItem) null);
        if (entry == null || !entry.is(DataItem.Type.OBJECT)) return null;
        return new VMScreenConfig(id, entry);
    }

    /**
     * Whether the VM actually has this screen's device.
     *
     * <p>The switch says the user asked for it; the GPU screen additionally needs the GPU,
     * because its frames come out of the virtio-gpu device. This is the app's half of the rule
     * crosvm enforces when it refuses an exporter bound to a screen with no device behind it, so
     * every caller -- the arg builder, the console chooser, the editor -- asks it here.</p>
     */
    public boolean isActive(@NonNull DataItem config) {
        if (!isEnabled()) return false;
        return !isGpu() || config.optBoolean("gpu_enabled", false);
    }

    /** Every screen that exists and has an exporter bound to it, in {@link #IDS} order. */
    @NonNull
    public static List<VMScreenConfig> boundOf(@NonNull DataItem config) {
        var out = new ArrayList<VMScreenConfig>();
        for (var screen : listOf(config))
            if (screen.isActive(config) && screen.getExporter() != DisplayExporter.NONE)
                out.add(screen);
        return out;
    }

    /** Every screen the config describes, in {@link #IDS} order. */
    @NonNull
    public static List<VMScreenConfig> listOf(@NonNull DataItem config) {
        var out = new ArrayList<VMScreenConfig>();
        for (var id : IDS) {
            var screen = find(config, id);
            if (screen != null) out.add(screen);
        }
        return out;
    }

    /**
     * Folds the legacy VM-level display keys into per-screen bindings, once, on load.
     *
     * <p>The old model had one display: {@code display_backend} chose which device produced it
     * and {@code native_display_enabled} / {@code vnc_enabled} chose who consumed it, with the
     * two consumers mutually exclusive because crosvm kept whichever sink opened first. The new
     * model has two independent devices, each carrying its own binding, so the enum becomes two
     * enables and the consumer booleans become one exporter per screen.</p>
     *
     * <p>Unlike {@link VMConfig#migrateBoot} this drops the legacy keys rather than leaving them
     * for an older build to read: they no longer describe a state this schema can be in (two
     * screens bound at once has no {@code display_backend} value), so leaving them would leave a
     * second, disagreeing answer on disk for the same question.</p>
     */
    static void migrate(@NonNull DataItem config) {
        if (config.opt(KEY, (DataItem) null) != null) return; // already the new shape

        var displayEnabled = config.optBoolean("display_enabled", false);
        var backend = Enums.optEnum(config, "display_backend", DisplayBackend.NONE);
        var wantNative = config.optBoolean("native_display_enabled", false);
        var wantVnc = config.optBoolean("vnc_enabled", false);
        var gpuEnabled = config.optBoolean("gpu_enabled", false);

        // Which screen the old config named. display_enabled gated everything, so a VM with it
        // off named no screen no matter what the backend said.
        String bound = null;
        if (displayEnabled && backend == DisplayBackend.VIRTIO_GPU) bound = ID_GPU0;
        else if (displayEnabled && backend == DisplayBackend.SIMPLEFB) bound = ID_SIMPLEFB;

        // `--vnc-server` was emitted from vnc_enabled alone -- buildVncCommand never looked at
        // display_enabled -- so "display off, VNC on, GPU on" was a working VM: crosvm gave the
        // GPU device its default display and the VNC server showed it. Keep it working by
        // binding to the screen crosvm's own compat rule would have picked for an exporter that
        // named none: gpu-0 when there is a GPU. With no GPU there was no device to open and the
        // server never served anything, so there is nothing to carry over.
        if (bound == null && wantVnc && !wantNative && gpuEnabled) bound = ID_GPU0;

        var gpu0 = of(config, ID_GPU0);
        var fb = of(config, ID_SIMPLEFB);
        gpu0.setEnabled(ID_GPU0.equals(bound));
        fb.setEnabled(ID_SIMPLEFB.equals(bound));
        gpu0.setExporter(DisplayExporter.NONE);
        fb.setExporter(DisplayExporter.NONE);

        var exporter = wantNative ? DisplayExporter.NATIVE
            : wantVnc ? DisplayExporter.VNC : DisplayExporter.NONE;
        if (bound != null && exporter != DisplayExporter.NONE)
            of(config, bound).setExporter(exporter);

        // The VNC settings follow the binding, and when nothing is bound they still have to land
        // somewhere or a port and a password the user chose are lost. Park them on the screen the
        // old backend named, or -- with no backend at all -- on the one crosvm would have
        // defaulted an unscreened exporter to, so re-enabling the exporter finds them in place.
        var vncHome = bound != null ? bound : gpuEnabled ? ID_GPU0 : ID_SIMPLEFB;
        var home = of(config, vncHome);
        home.setVncHost(str(config.optString("vnc_host", "")));
        home.setVncPort(config.optLong("vnc_port", -1));
        home.setVncPasswordAuth(config.optBoolean("vnc_password_auth", false));
        home.setVncPassword(str(config.optString("vnc_password", "")));

        config.remove("display_enabled");
        config.remove("display_backend");
        config.remove("native_display_enabled");
        config.remove("vnc_enabled");
        config.remove("vnc_host");
        config.remove("vnc_port");
        config.remove("vnc_password_auth");
        config.remove("vnc_password");
    }
}
