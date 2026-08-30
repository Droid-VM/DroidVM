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
 * <p>{@code enabled} says the VM has that display device at all -- for {@code gpu-0} that is the
 * whole virtio-gpu device, renderer included, and the renderer is a setting inside it rather than
 * a switch of its own. The exporter says who watches the screen; a screen with no exporter is a
 * legal, ordinary state (the guest still has the device), and crosvm accepts it too.</p>
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
    /**
     * Whether this screen gets its own absolute input devices. Absent means on, which is how
     * every config written before this key existed keeps the devices it already had: the default
     * carries the migration, so nothing on disk has to be rewritten to gain it.
     */
    private static final String KEY_INPUT_ENABLED = "input_enabled";
    /** Ceiling on the transport negotiated between this screen and its exporter. */
    private static final String KEY_TRANSPORT_CAP = "transport_cap";

    // Geometry. Every screen has a size; only the virtio-gpu screen has a mode to put a refresh
    // rate and a DPI in, and only simplefb has a poll rate, because that is the difference between
    // a device the guest programs and a block of memory the host watches.
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_REFRESH_RATE = "refresh_rate";
    private static final String KEY_DPI_H = "dpi_h";
    private static final String KEY_DPI_V = "dpi_v";
    private static final String KEY_POLL_HZ = "poll_hz";

    /** The VM-level keys {@link #migrateGeometry} folds into the screens, then drops. */
    private static final String[] LEGACY_GEOMETRY = {
        "display_width", "display_height", "display_refresh_rate", "display_dpi_h", "display_dpi_v"
    };
    /**
     * The VM-level accelerator switch, folded into the gpu-0 screen's own switch by
     * {@link #migrateGpuDevice} and then dropped.
     */
    private static final String LEGACY_GPU_ENABLED = "gpu_enabled";

    public static final long DEFAULT_WIDTH = 1280;
    public static final long DEFAULT_HEIGHT = 720;
    public static final long DEFAULT_REFRESH_RATE = 120;
    public static final long DEFAULT_DPI = 160;
    /**
     * What the simplefb bridge polled at for as long as the rate was not settable, so a screen
     * that never names one behaves as it always did. Mirrors crosvm's DEFAULT_SIMPLEFB_POLL_HZ.
     */
    public static final long DEFAULT_POLL_HZ = 30;
    /** Initial SimpleFB polling rate shown when creating a VM. */
    public static final long NEW_VM_DEFAULT_POLL_HZ = 60;

    /**
     * What a screen on a brand-new VM is bound to, in the one place both writers can see it.
     *
     * <p>Two of them write it and they must agree: {@link VMConfig#createWithCustomizeDefaults}
     * materialises the config the editor is then handed, and the editor's own screen rows carry a
     * default for the case where that config says nothing. They disagreed once -- the config said
     * NONE while the row said NATIVE -- and because the config is loaded over the row, turning
     * virtio-gpu on showed a screen bound to nothing. Neither side is wrong to have a default; the
     * fix is for there to be one value.
     *
     * <p>NATIVE rather than VNC because the viewer for it is this app, already installed: it is
     * the only exporter whose first boot can be looked at without setting something up first. This
     * is not {@link #getExporter}'s fallback, which answers a different question -- what a stored
     * config that does not mention an exporter meant -- and stays NONE.
     */
    public static final DisplayExporter NEW_VM_DEFAULT_EXPORTER = DisplayExporter.NATIVE;
    /**
     * What a brand-new VM's VNC server is told to listen on, in the one place both writers can
     * see it -- {@link VMConfig#createWithCustomizeDefaults} and the editor's own screen row --
     * for exactly the reason {@link #NEW_VM_DEFAULT_EXPORTER} is shared: the config is loaded over
     * the row, so a second opinion here would be the losing one and the disagreement invisible.
     *
     * <p>The loopback rather than the wildcard. A screen that is reachable from whatever network
     * the phone is on the moment the VM first boots is not a default's decision to make; widening
     * it is one pick in the host menu, and the app's own console dials the phone itself either
     * way.</p>
     */
    public static final String NEW_VM_DEFAULT_VNC_HOST = "127.0.0.1";
    /** The virtio-gpu screen's port on a new VM: RFB display :0, where a client looks first. */
    public static final long NEW_VM_DEFAULT_VNC_PORT_GPU0 = 5900;
    /**
     * The simplefb screen's port on a new VM: RFB display :9.
     *
     * <p>Nine displays up rather than one, because the two are defaults and not assignments: both
     * screens can be bound to VNC at once, crosvm refuses a command line whose servers share a
     * port, and the editor refuses the save before that (see {@code validateNoPortCollision}). The
     * gap also leaves :1..:8 to a user handing them out by hand.</p>
     */
    public static final long NEW_VM_DEFAULT_VNC_PORT_SIMPLEFB = 5909;
    public static final long MIN_POLL_HZ = 1;
    /**
     * crosvm's MAX_SIMPLEFB_POLL_HZ. A sanity bound on a knob whose cost is linear in it, not a
     * claim about what a panel can show -- above this the watcher asks for more work than anything
     * downstream can use.
     */
    public static final long MAX_POLL_HZ = 240;
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

    /**
     * Who watches this screen. Absent reads as NONE, and stays reading as NONE even though a new
     * screen now comes up defaulted to NATIVE.
     *
     * <p>Those are two different questions and only one of them is here. What a new VM gets is the
     * picker's default, set where the row is built; this is what a file that does not say means.
     * Nothing this app writes leaves the key out -- {@code ScreenBindingRow.save} calls
     * {@link #setExporter} unconditionally on the entry {@link #of} has just created, and
     * {@link #migrateBindings} writes it for every config from before the screens split -- so
     * moving the fallback would not change what any VM comes up with. It would only change how a
     * hand-edited file, or a token this build cannot parse, is read, and it would change it into
     * a display service and a pair of input devices bound to a screen whose file never asked for
     * either, because everything downstream tests {@code != NONE}.</p>
     */
    @NonNull
    public DisplayExporter getExporter() {
        return Enums.optEnum(item, "exporter", DisplayExporter.NONE);
    }

    public void setExporter(@NonNull DisplayExporter exporter) {
        item.set("exporter", exporter);
    }

    /**
     * Whether this screen gets its own multi-touch and absolute-pointer devices.
     *
     * <p>Absolute coordinates only mean anything under one output's geometry, so those two
     * devices are per screen; the VM-wide keyboard and relative pointer have no output binding at
     * all and are unaffected by this switch. Turning it off is turning the two devices off: the
     * set of {@code --input} devices is fixed when crosvm starts, so this takes effect on the
     * VM's next start, not on the running one.</p>
     *
     * <p>Where the screen is exported over VNC the switch means one thing more, without meaning
     * anything different: those two devices are half crosvm's now -- it builds that binding's
     * tablet and its own keyboard itself -- so the daemon spells the same off/on as
     * {@code view-only=true|false} on that screen's {@code --vnc-server}, and off makes crosvm
     * build neither and drop RFB pointer and key events. Which is the answer the user was already
     * asking for: a screen to watch and not touch, from the app's console and from a third-party
     * client alike.</p>
     *
     * <p>Defaults to on, and to on for a config that predates the key -- the devices existed
     * before it did.</p>
     */
    public boolean isInputEnabled() {
        return item.optBoolean(KEY_INPUT_ENABLED, true);
    }

    public void setInputEnabled(boolean enabled) {
        item.set(KEY_INPUT_ENABLED, enabled);
    }

    /**
     * The ceiling on this screen's transport to its exporter; see {@link DisplayTransportCap}.
     *
     * <p>Read against the exporter, because the ladder is the exporter's: a value stored under one
     * exporter and read back under another may name a rung that exporter does not have, and the
     * answer then is that exporter's default rather than a rung nobody can climb. The stored value
     * is left alone -- switching the exporter to look and back must not lose the choice.</p>
     */
    @NonNull
    public DisplayTransportCap getTransportCap() {
        var exporter = getExporter();
        var stored = DisplayTransportCap.fromToken(item.optString(KEY_TRANSPORT_CAP, ""));
        if (stored != null && DisplayTransportCap.isOfferedFor(id, exporter, stored)) return stored;
        return DisplayTransportCap.defaultFor(id, exporter);
    }

    public void setTransportCap(@NonNull DisplayTransportCap cap) {
        // The lower-case token, not the enum constant: this same string goes on crosvm's command
        // line, and one value must not have two spellings.
        item.set(KEY_TRANSPORT_CAP, cap.getToken());
    }

    /** This screen's width in pixels. */
    public long getWidth() {
        return item.optLong(KEY_WIDTH, DEFAULT_WIDTH);
    }

    public void setWidth(long width) {
        item.set(KEY_WIDTH, width);
    }

    /** This screen's height in pixels. */
    public long getHeight() {
        return item.optLong(KEY_HEIGHT, DEFAULT_HEIGHT);
    }

    public void setHeight(long height) {
        item.set(KEY_HEIGHT, height);
    }

    /**
     * The mode's refresh rate, in Hz. Virtio-GPU only: it reaches the guest through the scanout's
     * mode, and simplefb has no mode to carry one -- the device tree says nothing about time.
     */
    public long getRefreshRate() {
        return item.optLong(KEY_REFRESH_RATE, DEFAULT_REFRESH_RATE);
    }

    public void setRefreshRate(long hz) {
        item.set(KEY_REFRESH_RATE, hz);
    }

    /** Horizontal DPI of the mode. Virtio-GPU only, for the same reason as the refresh rate. */
    public long getDpiH() {
        return item.optLong(KEY_DPI_H, DEFAULT_DPI);
    }

    public void setDpiH(long dpi) {
        item.set(KEY_DPI_H, dpi);
    }

    /** Vertical DPI of the mode. Virtio-GPU only. */
    public long getDpiV() {
        return item.optLong(KEY_DPI_V, DEFAULT_DPI);
    }

    public void setDpiV(long dpi) {
        item.set(KEY_DPI_V, dpi);
    }

    /**
     * How many times a second the host looks at the simplefb framebuffer -- this screen's answer
     * to "refresh rate", and the only one it can give.
     *
     * <p>Nothing announces a frame here: the guest maps the region write-combining and no write
     * traps, so the rate the host samples at is the only thing that decides when a picture exists.
     * It is a property of the host's watcher, not of the device the guest sees -- the guest cannot
     * tell what it is set to -- which is why it lives on this screen and the GPU screen's refresh
     * rate, a real mode field the guest is told about, does not.</p>
     */
    public long getPollHz() {
        return item.optLong(KEY_POLL_HZ, DEFAULT_POLL_HZ);
    }

    public void setPollHz(long hz) {
        item.set(KEY_POLL_HZ, hz);
    }

    /** True for the screen the virtio-gpu device provides, as opposed to simplefb's. */
    public boolean isGpu() {
        return ID_GPU0.equals(id);
    }

    /** The port a brand-new VM's [id] screen is given; see the two constants it picks between. */
    public static long newVmDefaultVncPort(@NonNull String id) {
        return ID_GPU0.equals(id)
            ? NEW_VM_DEFAULT_VNC_PORT_GPU0 : NEW_VM_DEFAULT_VNC_PORT_SIMPLEFB;
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
     * Whether the VM has the virtio-gpu device at all -- which is exactly the gpu-0 screen's
     * switch, because the switch is the device.
     *
     * <p>There used to be a second answer, a VM-level {@code gpu_enabled}, and the pair could
     * disagree: a GPU with the screen off was meant to be a renderer that scans out nothing. It
     * never worked -- no desktop ever came up on it -- so the two collapsed into one, and the
     * renderer became a setting inside the device rather than a switch beside it. Everything that
     * used to ask "is there a GPU" asks here.</p>
     */
    public static boolean hasGpuDevice(@NonNull DataItem config) {
        var gpu0 = find(config, ID_GPU0);
        return gpu0 != null && gpu0.isEnabled();
    }

    /** Every screen that exists and has an exporter bound to it, in {@link #IDS} order. */
    @NonNull
    public static List<VMScreenConfig> boundOf(@NonNull DataItem config) {
        var out = new ArrayList<VMScreenConfig>();
        for (var screen : listOf(config))
            if (screen.isEnabled() && screen.getExporter() != DisplayExporter.NONE)
                out.add(screen);
        return out;
    }

    /**
     *
     * <p>For the host-process settings that belong to that path rather than to one screen -- the
     * Vulkan library the display bridge dlopens is the one -- because an environment variable is
     * process-wide and cannot be set per screen even when the thing it configures runs per screen.
     */
        for (var screen : boundOf(config))
        return false;
    }

    /**
     * Whether this screen gets its own {@code multi-touch} + absolute-pointer pair when the VM
     * starts: the device has to exist, something has to be watching it, and the switch has to be
     * on. A screen nobody exports has no console to send absolute input from, so devices for it
     * would be devices nothing can ever write to.
     *
     * <p>Both devices, but not both from here: on a VNC-exported screen this is what the daemon
     * turns into {@code view-only=false}, and crosvm builds the tablet. Which half is whose is
     * {@code CrosvmBackendInstance.touchscreenScreens}/{@code socketTabletScreens}; this predicate
     * is the same question for both and stays one.</p>
     */
    public boolean hasAbsoluteInput() {
        return isEnabled() && getExporter() != DisplayExporter.NONE && isInputEnabled();
    }

    /** Every screen that gets its own absolute input devices, in {@link #IDS} order. */
    @NonNull
    public static List<VMScreenConfig> absoluteInputOf(@NonNull DataItem config) {
        var out = new ArrayList<VMScreenConfig>();
        for (var screen : listOf(config))
            if (screen.hasAbsoluteInput()) out.add(screen);
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
     * Brings a config up to the current screens schema. Two folds, each gated on its own evidence
     * that it has not run yet, so a config from any generation lands in the same place.
     */
    static void migrate(@NonNull DataItem config) {
        // Asked before anything runs, because the first fold is what makes it false.
        var preScreens = config.opt(KEY, (DataItem) null) == null;
        migrateBindings(config);
        migrateGeometry(config);
        // Last, because the two above still read gpu_enabled to work out what the old config
        // meant, and this is the one that takes it away.
        migrateGpuDevice(config, preScreens);
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
    private static void migrateBindings(@NonNull DataItem config) {
        if (config.opt(KEY, (DataItem) null) != null) return; // already the new shape

        var displayEnabled = config.optBoolean("display_enabled", false);
        var backend = Enums.optEnum(config, "display_backend", DisplayBackend.NONE);
        var wantNative = config.optBoolean("native_display_enabled", false);
        var wantVnc = config.optBoolean("vnc_enabled", false);
        var gpuEnabled = config.optBoolean(LEGACY_GPU_ENABLED, false);

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

    /**
     * Folds the five flat geometry keys onto the screens that have somewhere to put them.
     *
     * <p>The geometry was VM-level because the display was: one size, one refresh rate, one DPI,
     * whichever device was producing the picture. With two devices that is one number answering
     * two questions -- a 1400x1050 virtio-gpu mode and a 1280x720 framebuffer are an ordinary
     * pair, and the old schema could not say it. Both screens inherit the old values, so a VM
     * that is migrated and not touched comes up exactly as it did.</p>
     *
     * <p>What does not carry over is what the flat keys could not hold. simplefb gets a poll rate,
     * written out at the rate the bridge used for as long as it was not settable; the refresh rate
     * and DPI go only to the GPU screen, because simplefb's geometry is fixed by the device tree
     * and the device tree describes neither.</p>
     *
     * <p>Gated on the legacy keys still being there rather than on the new ones being absent: the
     * new ones have defaults equal to the old ones, so "absent" is indistinguishable from "folded
     * and left at the default", and gating on that would re-run the fold over an edited screen.
     * Dropping the keys is what makes this run once, for the same reason the bindings fold drops
     * its own -- a second answer on disk for the same question is worse than no answer.</p>
     */
    private static void migrateGeometry(@NonNull DataItem config) {
        var legacy = false;
        for (var key : LEGACY_GEOMETRY)
            if (config.opt(key, (DataItem) null) != null) {
                legacy = true;
                break;
            }
        if (!legacy) return;

        var width = config.optLong("display_width", DEFAULT_WIDTH);
        var height = config.optLong("display_height", DEFAULT_HEIGHT);

        var gpu0 = of(config, ID_GPU0);
        gpu0.setWidth(width);
        gpu0.setHeight(height);
        gpu0.setRefreshRate(config.optLong("display_refresh_rate", DEFAULT_REFRESH_RATE));
        gpu0.setDpiH(config.optLong("display_dpi_h", DEFAULT_DPI));
        gpu0.setDpiV(config.optLong("display_dpi_v", DEFAULT_DPI));

        var fb = of(config, ID_SIMPLEFB);
        fb.setWidth(width);
        fb.setHeight(height);
        // Written rather than left to the getter's default: this screen gaining a rate of its own
        // is the visible half of the fold, and a value nobody can read out of the file is one the
        // user cannot be shown having inherited.
        fb.setPollHz(DEFAULT_POLL_HZ);

        for (var key : LEGACY_GEOMETRY) config.remove(key);
    }

    /**
     * Folds the VM-level {@code gpu_enabled} away: the gpu-0 switch is the virtio-gpu device now,
     * and the renderer is a setting inside it.
     *
     * <p>The old pair was two answers to one question and they were allowed to disagree, so the
     * fold has to decide what each disagreement meant.</p>
     *
     * <p><b>Accelerator on, screen off.</b> This was supposed to be a GPU that renders and scans
     * out nothing, and it is the shape an acceleration test gets saved in. It never worked -- no
     * desktop ever came up on such a VM -- so there is nothing to preserve: the device is off. A
     * VM that was in that state was not doing anything, and saying so is more honest than carrying
     * a configuration forward on the grounds that it was written down.</p>
     *
     * <p><b>Accelerator off, screen on.</b> The reverse disagreement was a real, ordinary VM in
     * intent -- a display with no 3D -- and the old builder emitted no {@code --gpu} at all for
     * it, so it had no display device either. The new model can say exactly what was meant, so it
     * does: the device is on with the 2D renderer, which is also what repairs the VM.</p>
     *
     * <p>Runs for any config from before the screens split, key or no key: an absent
     * {@code gpu_enabled} read as false is exactly the second case, and gating on the key alone
     * would leave those configs with a virtio-gpu screen and no renderer named.</p>
     */
    private static void migrateGpuDevice(@NonNull DataItem config, boolean preScreens) {
        if (!preScreens && config.opt(LEGACY_GPU_ENABLED, (DataItem) null) == null) return;
        var accelerated = config.optBoolean(LEGACY_GPU_ENABLED, false);
        var gpu0 = find(config, ID_GPU0);
        if (!accelerated && gpu0 != null && gpu0.isEnabled())
            // Whatever gpu_backend said was inert while the accelerator was off -- the old builder
            // never read it -- so this is naming the renderer the VM actually ran, not losing one.
            config.set("gpu_backend", GpuBackend.GPU_2D);
        config.remove(LEGACY_GPU_ENABLED);
    }
}
