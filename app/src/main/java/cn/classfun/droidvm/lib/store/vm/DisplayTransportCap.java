// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * How far up the pipeline one screen's frames are allowed to travel to reach its exporter.
 *
 * <p>The transport is not a property of the screen or of the exporter but of the edge between
 * them, and it is negotiated: the source says what it can produce (CPU bytes, a dmabuf), the
 * exporter says what it can consume, and the highest rung both reach wins. So it is not a thing
 * the user picks outright. What the user can have is a <em>ceiling</em>, and that distinction is
 * the whole reason this enum reads the way it does.</p>
 *
 * <p><b>It only restricts downward.</b> The transport is settled at or below the rung named here,
 * so every value is always satisfiable -- CPU copy is the bottom of the ladder and needs nothing
 * from either end. "At least GPU copy" would not be: a source that cannot export a dmabuf leaves
 * only a silent downgrade (which looks like success and is not) or a loud failure (a VM that
 * refuses to start over a preference). A ceiling has neither failure mode. It is also why there is
 * no separate "automatic" entry -- the top rung of the offered set already is automatic.</p>
 *
 * <p>The ladder is not the same on every edge, because the rung above a copy is a different
 * mechanism on each. The native display can be lent a render target the guest draws straight into
 * ({@link #ZERO}) -- but only when the guest's rendering can be aimed at it, which rules simplefb
 * out: its framebuffer is a fixed window of guest memory named in the device tree, and no
 * AHardwareBuffer can be made to wrap that. VNC has nowhere to lend a target at all, and its rung
 * above a GPU blit is handing the frame to a hardware video encoder instead of an RFB rectangle
 * ({@link #GPU_HW}). So the offered set is a function of both ends, and {@link #optionsFor} is
 * where that lives.</p>
 *
 * <p>Rungs that are designed but not built are offered and refused rather than hidden: the ladder
 * is easier to understand whole, and a value that appears later must not look like a new feature
 * arriving out of nowhere. {@link #isImplemented} says which is which today.</p>
 */
public enum DisplayTransportCap implements StringEnum {
    /** Always reachable: host memcpy into the sink's own buffer. The bottom of every ladder. */
    CPU(0, "cpu", R.string.create_vm_screen_transport_cpu),
    /** A Vulkan blit on the host, which gets a format conversion thrown in for free. */
    GPU(1, "gpu", R.string.create_vm_screen_transport_gpu),
    /** Native display only: the sink lends its render target and the content never crosses. */
    ZERO(2, "zero", R.string.create_vm_screen_transport_zero),
    /** VNC only: the blit's result goes to a hardware video encoder rather than into RFB. */
    GPU_HW(3, "gpu-hw", R.string.create_vm_screen_transport_gpu_hw);

    /**
     * The width granularity a screen whose stride is exactly {@code width * 4} needs before the
     * GPU copy can take it, in pixels.
     *
     * <p>The blit imports the frame as a LINEAR dma-buf and turnip accepts one only when its row
     * pitch is 64-byte aligned. A virtio-gpu scanout is allocated by the host and rounded up to
     * whatever the importer wants, so it never meets this rule by accident -- it meets it by
     * construction. simplefb's framebuffer is a window of guest memory the device tree already
     * described, {@code width * 4} bytes per row with nothing to pad it with, so there the whole
     * rule collapses to {@code width * 4 % 64 == 0}, which is this. Measured on device: 1400 falls
     * back to the CPU copy, 1408 does not.</p>
     */
    public static final long GPU_COPY_WIDTH_ALIGN = 16;

    private final int value;
    private final String token;
    private final @StringRes int stringId;

    DisplayTransportCap(int value, String token, @StringRes int stringId) {
        this.value = value;
        this.token = token;
        this.stringId = stringId;
    }

    @SuppressWarnings("unused")
    public int getValue() {
        return value;
    }

    /**
     * The stored spelling, which is also the token crosvm's {@code transport-cap=} takes. Lower
     * case and hyphenated, unlike the other persisted enums, because this one is written onto a
     * command line as well as into the config and one value must not have two spellings. That is
     * also why it is parsed by {@link #fromToken} rather than by the generic enum helper, whose
     * upper-casing cannot round-trip a hyphen.
     */
    @NonNull
    public String getToken() {
        return token;
    }

    @Override
    @StringRes
    public int getStringId() {
        return stringId;
    }

    @Override
    public boolean isDisplay() {
        return stringId != R.string.nullptr;
    }

    /** The stored token back to a constant, or null for absent, empty or unrecognised. */
    @Nullable
    public static DisplayTransportCap fromToken(@Nullable String stored) {
        if (stored == null || stored.isEmpty()) return null;
        for (var cap : values())
            if (cap.token.equalsIgnoreCase(stored)) return cap;
        return null;
    }

    /**
     * The ladder this (screen, exporter) edge has, bottom rung first.
     *
     * <p>Both ends decide it, which is why the screen is a parameter. The native display's top
     * rung is a render target it lends the guest to draw into -- and simplefb cannot be drawn into
     * that way at all: its framebuffer is a fixed window of guest memory the guest was told about
     * in the device tree, and an AHardwareBuffer cannot be made to wrap it. That rung is not
     * "unbuilt" there, it is unreachable, so it is absent rather than greyed. Offering it would be
     * describing a choice nobody will ever be able to make.</p>
     *
     * <p>An exporter with no edge -- nobody is watching the screen -- has no ladder, and the empty
     * array is how callers know not to offer one rather than having to ask the question twice.</p>
     */
    @NonNull
    public static DisplayTransportCap[] optionsFor(@NonNull String screenId,
                                                   @NonNull DisplayExporter exporter) {
        switch (exporter) {
            case NATIVE:
                return VMScreenConfig.ID_GPU0.equals(screenId)
                    ? new DisplayTransportCap[]{CPU, GPU, ZERO}
                    : new DisplayTransportCap[]{CPU, GPU};
            case VNC:
                return new DisplayTransportCap[]{CPU, GPU, GPU_HW};
            default:
                return new DisplayTransportCap[0];
        }
    }

    /** Whether [cap] is one of the rungs this edge has at all. */
    public static boolean isOfferedFor(@NonNull String screenId,
                                       @NonNull DisplayExporter exporter,
                                       @NonNull DisplayTransportCap cap) {
        for (var option : optionsFor(screenId, exporter))
            if (option == cap) return true;
        return false;
    }

    /**
     * Whether this build can actually reach [cap] on this edge.
     *
     * <p>VNC's ladder is now built to the top: the same blit that feeds an RFB rectangle can feed a
     * hardware H.264 encoder instead, and the app's own console reads the result off a side channel
     * beside the RFB port. What is left unbuilt is zero copy on the native display. Unimplemented
     * rungs are still offered -- see the class comment -- so this is what decides which of them the
     * picker refuses.</p>
     *
     * <p>The two exporters no longer answer the same way, which is the point: they climbed to
     * different heights by different mechanisms, and writing that as one shared list of caps would
     * have made the day VNC overtook the native display look like a typo.</p>
     */
    public static boolean isImplemented(@NonNull DisplayExporter exporter,
                                        @NonNull DisplayTransportCap cap) {
        switch (exporter) {
            case NATIVE:
                return cap == CPU || cap == GPU;
            case VNC:
                return cap == CPU || cap == GPU || cap == GPU_HW;
            default:
                return false;
        }
    }

    /** The rungs this edge shows but cannot honour yet, in {@link #optionsFor} order. */
    @NonNull
    public static DisplayTransportCap[] unimplementedFor(@NonNull String screenId,
                                                         @NonNull DisplayExporter exporter) {
        var options = optionsFor(screenId, exporter);
        var n = 0;
        for (var option : options)
            if (!isImplemented(exporter, option)) n++;
        var out = new DisplayTransportCap[n];
        var i = 0;
        for (var option : options)
            if (!isImplemented(exporter, option)) out[i++] = option;
        return out;
    }

    /**
     * Whether a GPU-copy ceiling on this edge will settle on the CPU copy anyway, because the
     * screen is [width] pixels wide and the blit cannot import a frame that shape.
     *
     * <p>This is the negotiation working exactly as designed -- the ceiling only restricts
     * downward, so nothing here is a misconfiguration and nothing needs refusing. But it is the one
     * downgrade whose cause is a number the user typed rather than a rung the build has not
     * reached, so it is the one worth saying out loud in the editor: a width off by eight pixels
     * costs the whole GPU path and there is no other way to find that out.</p>
     *
     * <p>Only simplefb has the constraint, and only where the ceiling actually asks for a blit --
     * see {@link #GPU_COPY_WIDTH_ALIGN}. Asking {@link #isImplemented} rather than naming the
     * native display is what made VNC's GPU half inherit the rule the day it landed: it imports the
     * same dma-buf under the same 64-byte pitch rule, and this condition did not have to be found
     * and changed for the warning to start appearing there.</p>
     *
     * <p>The encoder rung asks for the same import -- it is the same blit with a different
     * destination -- so it is named here too. It had to be: the moment VNC's default rose to it,
     * a condition that only knew about {@link #GPU} would have gone quiet for exactly the
     * configuration it was written for, and a warning that disappears when the default moves is
     * indistinguishable from one that was never right.</p>
     */
    public static boolean cpuFallbackFromWidth(@NonNull String screenId,
                                               @NonNull DisplayExporter exporter,
                                               @NonNull DisplayTransportCap ceiling,
                                               long width) {
        if (!VMScreenConfig.ID_SIMPLEFB.equals(screenId)) return false;
        if (ceiling != GPU && ceiling != GPU_HW) return false;
        if (!isImplemented(exporter, ceiling)) return false;
        return width % GPU_COPY_WIDTH_ALIGN != 0;
    }

    /**
     * The ceiling a screen gets when it has not named one: the highest rung this build can
     * actually reach on that edge.
     *
     * <p>Not the highest rung offered -- that would default every VM to a ceiling nothing can
     * satisfy today, which is a promise the negotiation would quietly break. It is the highest
     * <em>implemented</em> one, so the default never restricts anything that works, and it rises
     * on its own as the rungs land.</p>
     *
     * <p><b>Which is how VNC's default became the hardware encoder</b>, and that reads more
     * expensive than it is. A ceiling is not a request: the encoder is built when a client opens
     * the H.264 side channel and never otherwise, so a VM at this default that nobody watches over
     * that channel does exactly what the same VM did at the GPU rung -- one blit, an RFB rectangle,
     * no encoder. Every ordinary RFB client keeps working unchanged; what the top rung buys is that
     * the app's own console can ask for H.264 instead of pixels.</p>
     */
    @NonNull
    public static DisplayTransportCap defaultFor(@NonNull String screenId,
                                                 @NonNull DisplayExporter exporter) {
        var best = CPU;
        for (var option : optionsFor(screenId, exporter))
            if (isImplemented(exporter, option)) best = option;
        return best;
    }

    /**
     * The token {@code transport-cap=} should carry for this binding, or null to send no flag.
     *
     * <p>A ceiling at the top of what this build can reach is the same instruction as no ceiling at
     * all, so the flag is written only when it says something the host would not work out on its
     * own: the user asked for <em>less</em> than the pipeline could have given. The absence of the
     * flag is therefore not "unspecified", it is the top rung -- which is also what makes the
     * default configuration emit nothing, on either exporter.</p>
     *
     * <p>Position in {@link #optionsFor}, not the enum's own order, decides what "below" means. The
     * two ladders diverge above the blit -- {@link #ZERO} on one, {@link #GPU_HW} on the other --
     * so an ordinal comparison would be comparing rungs from different ladders. It also keeps a
     * ceiling stored under another exporter from ever being emitted: {@link VMScreenConfig} has
     * already resolved such a value to this edge's default, and this refuses to name anything the
     * edge does not offer.</p>
     */
    @Nullable
    public static String emittedToken(@NonNull String screenId,
                                      @NonNull DisplayExporter exporter,
                                      @NonNull DisplayTransportCap ceiling) {
        var options = optionsFor(screenId, exporter);
        var top = defaultFor(screenId, exporter);
        var ceilingAt = -1;
        var topAt = -1;
        for (var i = 0; i < options.length; i++) {
            if (options[i] == ceiling) ceilingAt = i;
            if (options[i] == top) topAt = i;
        }
        if (ceilingAt < 0 || ceilingAt >= topAt) return null;
        return ceiling.token;
    }
}
