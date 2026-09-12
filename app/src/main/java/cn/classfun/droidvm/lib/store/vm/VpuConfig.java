// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * Whether a VM is given hardware video acceleration.
 *
 * <p>One switch for both directions. In the guest they are two devices -- a virtio-media device is
 * one V4L2 node is one function, so a decoder and an encoder cannot share one -- but they are one
 * piece of hardware to whoever ticks the box, and there is no host on which one would work and the
 * other would not.</p>
 *
 * <p>What the switch buys is the whole virtio-media transport: with it on, the crosvm command
 * line carries the two pools ({@code media-host-mb} / {@code media-guest-mb}), the huge-page
 * preflight budgets for the guest one, every media device the VM's peripheral list asks for -- a
 * camera row -- and the codec devices, which are in no list because they are not something a user
 * adds: a hardware decoder and a hardware encoder are what having a VPU means
 * ({@link #CODEC_KINDS}). With it off none of
 * that is passed, because none of it works on its own: crosvm will not create a virtio-media
 * device on Gunyah without the host pool, so a device without the switch is a VM that does not
 * start. One switch, one answer; see {@link #mediaDevicesAttached} and
 * {@code plans/VPU_DESIGN.md} sections 2.2, 3.3, 7.4 and 8.</p>
 */
public final class VpuConfig {
    public static final String KEY_ENABLED = "vpu_enabled";
    public static final String KEY_HOST_POOL_MB = "vpu_host_pool_mb";
    public static final String KEY_GUEST_POOL_MB = "vpu_guest_pool_mb";
    public static final String KEY_CODEC_ENABLED = "vpu_codec_enabled";

    /**
     * The {@code media_host} pool a VM gets when nobody chose a size.
     *
     * <p>320 MiB, raised from 256 by B12's D68: {@code ffmpeg -f v4l2 -video_size 3840x2160 -i
     * /dev/video0} asks the camera device for 22 buffers of 12,441,600 B = 273,715,200 B = 261
     * MiB, and the VMM correctly refused the 22nd out of a 268,435,456 B pool -- an honest
     * capacity limit met by a client that has no way to ask for fewer buffers
     * ({@code libavdevice}'s v4l2 input device has no buffer-count option). 320 MiB is
     * 335,544,320 B, which holds 26 such buffers: the 22 ffmpeg wants plus four, so a second
     * client or a slightly greedier one does not land straight back on the limit. The host pool
     * is {@code consume_system_mem} on the crosvm side, so those 64 extra MiB come out of the
     * VM's {@code --mem} and cost the huge-page reserve nothing
     * ({@link cn.classfun.droidvm.lib.hugepage.PoolPreflight#neededPages}).</p>
     *
     * <p>A multiple of the 2 MiB huge-page granule, like every pool size here. See
     * {@code plans/VPU_DESIGN.md} section 8 and {@code logs/vpu_wp/B12-acceptance.md} D68.</p>
     */
    public static final int DEFAULT_HOST_POOL_MB = 320;

    /**
     * The {@code media_guest} pool a protected VM gets when nobody chose a size.
     *
     * <p>192 MiB, raised from 128 by B12's D67: a 4K hardware encode never even reached its
     * encoder session, because the encoder's OUTPUT queue is driver-owned and its 11 buffers of
     * 12,441,600 B = 136,857,600 B = 130.5 MiB do not fit a 134,217,728 B pool -- buffer 10 (the
     * eleventh) failed with {@code -12} and 4K encoding was simply impossible. 192 MiB is
     * 201,326,592 B, which holds 16 such buffers: the 11 the 4K OUTPUT set needs plus five, so a
     * second driver-owned queue in the same VM still fits.</p>
     *
     * <p>Unlike the host pool this one is paid for: it is memory beside the VM's RAM, so it is
     * added to the huge-page preflight, and the extra 64 MiB is 32 more 2 MiB pages served out
     * of the reserve (2624 -> 2656 on the lab VM's 5120 MiB, against a 3072-page
     * {@code pool_want}). A multiple of the 2 MiB granule for that reason. See
     * {@code plans/VPU_DESIGN.md} section 8 and {@code logs/vpu_wp/B12-acceptance.md} D67.</p>
     */
    public static final int DEFAULT_GUEST_POOL_MB = 192;

    /**
     * The codec devices the video-acceleration switch attaches, spelt as the {@code kind=} value
     * crosvm's {@code --virtio-media} parses.
     *
     * <p>One table, because three things read the same list: the loop in the crosvm backend that
     * emits a {@code --virtio-media} line per entry, {@link #codecCard} which names each one for
     * the guest, and the tests. {@code "encoder"} has joined it, which was the condition this
     * comment used to state: crosvm's {@code MediaDeviceKind::support()} answers
     * {@code HelperOnly} for {@code Encoder} as well as {@code Decoder}
     * ({@code devices/src/virtio/media.rs}), so the VMM no longer refuses the kind by name before
     * it forks a helper, and WP M7's encoder was accepted end to end on the device -- H.264 and
     * HEVC, PSNR 46 dB against a software decode ({@code logs/vpu_wp/B7-codec.md} sections 2 and
     * 3). Adding the token here was the whole app-side change that was left
     * ({@code logs/vpu_wp/B-final.md} section 9 item 1): without it the switch bought a hardware
     * decoder and no hardware encoder, and {@code ffmpeg -c:v h264_v4l2m2m} in the guest answered
     * {@code Could not find a valid device}.</p>
     *
     * <p>Order is the order the devices appear on the command line, and so the order the guest
     * numbers their {@code /dev/videoN} nodes among themselves. Append rather than insert: the
     * decoder stays first so a guest that already names the decoder's node keeps it when the
     * encoder arrives ({@code plans/VPU_DESIGN.md} sections 7.3 and 7.4).</p>
     */
    public static final List<String> CODEC_KINDS = List.of("decoder", "encoder");

    private VpuConfig() {
    }

    public static boolean isEnabled(@NonNull DataItem config) {
        return config.optBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(@NonNull DataItem config, boolean enabled) {
        config.set(KEY_ENABLED, enabled);
    }

    /**
     * Where the host puts the buffers it allocates for the guest to map.
     *
     * <p>Always present: host-allocated buffers are how virtio-media works on every hypervisor,
     * and the pool is only a different base for the offset the host already returns.</p>
     */
    public static int getHostPoolMb(@NonNull DataItem config) {
        return (int) config.optLong(KEY_HOST_POOL_MB, DEFAULT_HOST_POOL_MB);
    }

    public static void setHostPoolMb(@NonNull DataItem config, int mb) {
        config.set(KEY_HOST_POOL_MB, mb);
    }

    /** The stored guest pool size, whether or not this VM can use one. */
    public static int getGuestPoolMb(@NonNull DataItem config) {
        return (int) config.optLong(KEY_GUEST_POOL_MB, DEFAULT_GUEST_POOL_MB);
    }

    public static void setGuestPoolMb(@NonNull DataItem config, int mb) {
        config.set(KEY_GUEST_POOL_MB, mb);
    }

    /**
     * Whether a guest-side pool means anything for {@code pvm}.
     *
     * <p>It only does when the host cannot read guest memory. Everywhere else the guest driver's
     * ordinary allocation is already reachable, and declaring a pool would replace a working path
     * with a bounded one for no gain. The value stays in the config either way, so switching the
     * protection mode back does not lose it.</p>
     */
    public static boolean guestPoolApplies(@Nullable ProtectedVM pvm) {
        return pvm == ProtectedVM.PROTECTED_PROTECTED
            || pvm == ProtectedVM.PROTECTED_WITHOUT_FIRMWARE;
    }

    /**
     * Whether this VM attaches the codec devices, given that video acceleration is on at all.
     *
     * <p>Default on: the switch's own text promises a hardware encoder and decoder, so a VM that
     * has it gets them without a second box to find. The key exists for the case where the
     * devices are the problem rather than the point -- a host whose codec store has no usable
     * hardware decoder ends the helper by name, and the VMM reports a helper that exits as a
     * device crash, so on such a phone every VM with video acceleration on would stop booting
     * (logs/vpu_wp/M6-backend.md section 6). Turning this off leaves the VM its pools and its
     * camera and takes only the codec nodes away, which is also what lets an acceptance run boot
     * a VPU VM with no decoder helper in it to compare against.</p>
     *
     * <p>It is deliberately not a second "video acceleration" switch: with video acceleration off
     * this answers nothing at all ({@link #codecDevicesAttached}), so there is no combination in
     * which it is the reason a VM has no VPU.</p>
     */
    public static boolean isCodecEnabled(@NonNull DataItem config) {
        return config.optBoolean(KEY_CODEC_ENABLED, true);
    }

    public static void setCodecEnabled(@NonNull DataItem config, boolean enabled) {
        config.set(KEY_CODEC_ENABLED, enabled);
    }

    /**
     * Whether this VM is given the {@link #CODEC_KINDS} devices.
     *
     * <p>The switch first, then the override -- and in that order, because the override is only
     * a subtraction. The codec devices are not rows in the peripheral list: nobody adds a decoder,
     * it is what turning video acceleration on <em>is</em>, so the rule that decides them is here
     * beside the pools they are served from rather than on {@link PeripheralType}.</p>
     */
    public static boolean codecDevicesAttached(@NonNull DataItem config) {
        return mediaDevicesAttached(config) && isCodecEnabled(config);
    }

    /**
     * The V4L2 card name the guest reads for the codec device of {@code kind}.
     *
     * <p>The same string crosvm's helper falls back to when no {@code card=} is passed
     * ({@code devices/src/virtio/vhost/user/device/media/sys/linux.rs}: {@code "droidvm decoder"}),
     * so the name a guest sees does not depend on whether the app built the command line or
     * somebody ran the helper by hand from the dev rig. It is passed explicitly all the same:
     * what the guest calls its devices should be decided where the command line is written, not
     * inherited from whichever crosvm happens to be installed.</p>
     *
     * <p>Not the VM's name. The card name is read inside the guest, where which VM this is was
     * never in question, and naming it after the VM would rename a guest device -- something a
     * guest script may well match on -- because somebody renamed the VM in the editor.</p>
     */
    @NonNull
    public static String codecCard(@NonNull String kind) {
        return "droidvm " + kind;
    }

    /**
     * Whether this VM attaches the media devices its peripheral list asks for.
     *
     * <p>The switch is the whole answer, and that is the design: a virtio-media device and the
     * pools it is served out of are one thing, not two. Turning video acceleration on gives the
     * VM the {@code media_host} pool crosvm needs before it will create any virtio-media device
     * at all on Gunyah ({@code virtio-media on gunyah needs --pre-alloc media-host-mb},
     * VPU_DESIGN.md 3.3); with it off there is no pool, so there is nothing to attach a camera
     * to. A row that asks for one on a VM with the switch off is skipped with a log line rather
     * than quietly buying it a pool nobody ticked a box for -- and the editor turns the switch
     * on for the user at the moment they add such a row, so the skip is what a hand-edited
     * {@code vms.json} gets, not what the UI produces.</p>
     *
     * <p>Which rows this covers is {@link PeripheralType#needsVpu()}, not a constant named
     * here.</p>
     */
    public static boolean mediaDevicesAttached(@NonNull DataItem config) {
        return isEnabled(config);
    }

    /**
     * The {@code media-host-mb} this VM must be passed, or 0 for "no host pool".
     *
     * <p>The switch alone: with it off there is no host pool, no guest pool and no media device
     * ({@link #mediaDevicesAttached}), so a VM that never asked for video acceleration is passed
     * nothing media-related whatever its peripheral list says.</p>
     *
     * <p>The size is the configured one, falling back to {@link #DEFAULT_HOST_POOL_MB} when the
     * switch is on and the config says zero or less. That fallback is not cosmetic: on Gunyah
     * crosvm refuses to create a virtio-media device with no {@code media_host} pool and fails
     * the whole VM, so {@code media-host-mb=0} with the switch on would be a VM that stops
     * booting the moment its camera row becomes a device. {@link #hostPoolIsDefaulted} says when
     * it happened, so the daemon can log it.</p>
     *
     * <p>It is a fallback for old and hand-written configs only: the editor refuses to save a
     * host pool below 1 MB with the switch on, so nothing the UI produces reaches it. Passing
     * the pool whenever the switch is on -- even with no media row in the list -- is the
     * decision and not an oversight: the switch is what says devices are coming, and a pool that
     * appeared and disappeared with the peripheral list would be the invisible second switch
     * this design removed.</p>
     */
    public static long hostPoolMbFor(@NonNull DataItem config) {
        if (!isEnabled(config)) return 0;
        long mb = getHostPoolMb(config);
        return mb > 0 ? mb : DEFAULT_HOST_POOL_MB;
    }

    /**
     * True when {@link #hostPoolMbFor} had to substitute the default because the VM asked for
     * video acceleration and stored a host pool size of zero or less. The daemon says so in the log: the
     * VM is being passed a pool of a size nobody chose.
     */
    public static boolean hostPoolIsDefaulted(@NonNull DataItem config) {
        return isEnabled(config) && getHostPoolMb(config) <= 0;
    }

    /**
     * The guest pool size to pass to crosvm, or 0 for "do not create one".
     *
     * <p>0 is not a smaller pool, it is no {@code media_guest} node at all: with nothing in
     * /reserved-memory to find, the guest driver falls back to its stock behaviour of allocating
     * from system RAM.</p>
     */
    public static int guestPoolMbFor(@NonNull DataItem config, @Nullable ProtectedVM pvm) {
        // A VM with the switch off gets neither pool. The sizes stay in the config so that
        // turning it back on restores what was set, but a stored size is not a request: without
        // this a VM that never asked for video acceleration would still pay 192 MB of the
        // huge-page reserve for a node its guest has no driver to look for.
        if (!isEnabled(config)) return 0;
        return guestPoolApplies(pvm) ? getGuestPoolMb(config) : 0;
    }

    /**
     * The {@code media-guest-mb} a VM will be passed at boot, for the huge-page preflight.
     *
     * <p>Only the guest pool is counted. The host pool is tagged {@code consume_system_mem} on
     * the crosvm side like the renderer host pools are, so it comes out of {@code --mem} and is
     * already inside what the preflight budgets; the guest pool is memory taken beside the RAM
     * rather than out of it. So a VM that gains a camera gains no huge-page reserve to find --
     * it gains no pool at all unless the switch is already on, and the pool the switch buys is
     * the same one whether or not anything is plugged into it. Same rule as
     * {@link GuestPoolSizing#bootGuestPreallocMb}, and the same reason it lives next to the
     * getter rather than being read inline at the call site.
     * See {@code plans/VPU_DESIGN.md} section 2.2.</p>
     *
     * <p>The protection mode defaults the way {@link GuestPoolSizing#hostVisibleRam} defaults it:
     * a config with no {@code protected_vm} key is a Gunyah VM, which is the only hypervisor the
     * pools exist on.</p>
     */
    public static long bootMediaGuestMb(@NonNull DataItem config) {
        var pvm = optEnum(config, "protected_vm", ProtectedVM.PROTECTED_WITHOUT_FIRMWARE);
        return guestPoolMbFor(config, pvm);
    }
}
