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
     * <p>Today: the native display has the CPU copy and the Vulkan blit, and zero copy is a later
     * step; VNC has only the CPU copy, because nobody has written its GPU half or the hardware
     * encoder behind it. Unimplemented rungs are still offered -- see the class comment -- so this
     * is what decides which of them the picker refuses.</p>
     */
    public static boolean isImplemented(@NonNull DisplayExporter exporter,
                                        @NonNull DisplayTransportCap cap) {
        switch (exporter) {
            case NATIVE:
                return cap == CPU || cap == GPU;
            case VNC:
                return cap == CPU;
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
     * The ceiling a screen gets when it has not named one: the highest rung this build can
     * actually reach on that edge.
     *
     * <p>Not the highest rung offered -- that would default every VM to a ceiling nothing can
     * satisfy today, which is a promise the negotiation would quietly break. It is the highest
     * <em>implemented</em> one, so the default never restricts anything that works, and it rises
     * on its own as the rungs land.</p>
     */
    @NonNull
    public static DisplayTransportCap defaultFor(@NonNull String screenId,
                                                 @NonNull DisplayExporter exporter) {
        var best = CPU;
        for (var option : optionsFor(screenId, exporter))
            if (isImplemented(exporter, option)) best = option;
        return best;
    }
}
