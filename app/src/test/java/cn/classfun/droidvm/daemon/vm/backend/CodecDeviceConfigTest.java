// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
import cn.classfun.droidvm.lib.store.vm.VpuConfig;

/**
 * The codec devices the video-acceleration switch buys: which {@code --virtio-media} lines a VM
 * gets, how many, and what each one says.
 *
 * <p>These are not peripheral rows -- nobody adds a decoder, it is what having a VPU means -- so
 * unlike {@link CameraDeviceConfigTest} the question "is it emitted at all" is most of the
 * subject, and it is asked here against {@code appendCodecDevices}, the function
 * {@code buildCommand} calls, rather than against the predicates underneath it.</p>
 *
 * <p>The strings are also written to {@code build/vpu-a4/codec-cli.txt} for the scratch Rust
 * harness ({@code deploy/vpu/harness.sh kvt}) to hand to crosvm's own {@code MediaDeviceConfig}
 * parser, which is {@code deny_unknown_fields}: a key name that drifted is a VM that will not
 * start, and only the other side can say so.</p>
 */
public final class CodecDeviceConfigTest {
    private static final int APP_UID = 10123;

    /** A Gunyah VM with video acceleration on, which is when codec devices exist at all. */
    private static DataItem vpuVm() {
        var item = VMConfig.createWithCustomizeDefaults(null).item;
        item.set("memory_mb", 4096L);
        item.set("protected_vm", "protected_without_firmware");
        VpuConfig.setEnabled(item, true);
        VpuConfig.setHostPoolMb(item, VpuConfig.DEFAULT_HOST_POOL_MB);
        VpuConfig.setGuestPoolMb(item, VpuConfig.DEFAULT_GUEST_POOL_MB);
        return item;
    }

    /** Adds a camera row, the way the peripheral editor's picker would. */
    private static DataItem withCamera(DataItem item, String hostDevice, String label) {
        var peripherals = item.opt("peripherals", DataItem.newArray());
        var camera = new VMPeripheralConfig(DataItem.newObject());
        camera.setType(PeripheralType.VIRTIO_CAMERA);
        camera.setHostDevice(hostDevice, label);
        peripherals.append(camera.item);
        item.set("peripherals", peripherals);
        return item;
    }

    /** What {@code buildCommand} would append for {@code item} on {@code hypervisor}. */
    private static List<String> emit(DataItem item, VMHypervisor hypervisor) {
        var args = new ArrayList<String>();
        CrosvmBackendInstance.appendCodecDevices(args, item, hypervisor, () -> APP_UID);
        for (int i = 1; i < args.size(); i += 2) record(args.get(i));
        return args;
    }

    private static List<String> emit(DataItem item) {
        return emit(item, VMHypervisor.GUNYAH);
    }

    /**
     * A decoder and an encoder, named, under the app's uid, in that order. The cards are the same
     * strings crosvm's helper falls back to on its own, so the guest reads one name whichever
     * side wrote it.
     *
     * <p>Written out as two whole lines rather than derived from the table, so that the bytes a
     * VM is started with are pinned here even if the table or {@link VpuConfig#codecCard} is
     * edited: the encoder line is the one {@code logs/vpu_wp/B7-codec.md} booted by hand through
     * {@code extra_options} before the app emitted it.</p>
     */
    @Test
    public void theSwitchOnGivesTheVmADecoderAndAnEncoderUnderTheAppUid() {
        assertEquals(List.of(
                "--virtio-media", "kind=decoder,card=\"droidvm decoder\",uid=10123",
                "--virtio-media", "kind=encoder,card=\"droidvm encoder\",uid=10123"),
            emit(vpuVm()));
    }

    /**
     * The emission is a loop over {@link VpuConfig#CODEC_KINDS}, so the table is what says how
     * many lines there are and in which order -- adding the encoder to it was the whole app-side
     * change WP M7 left behind ({@code logs/vpu_wp/B-final.md} section 9 item 1). The order is
     * asserted, not just the membership: the guest numbers its {@code /dev/videoN} nodes in the
     * order the devices appear on the command line, so a VM that already names the decoder's
     * node must keep it.
     */
    @Test
    public void everyKindInTheTableIsEmittedExactlyOnceInOrder() {
        var args = emit(vpuVm());
        assertEquals(2 * VpuConfig.CODEC_KINDS.size(), args.size());
        var kinds = new ArrayList<String>();
        for (int i = 0; i < args.size(); i += 2) {
            assertEquals("--virtio-media", args.get(i));
            var cfg = args.get(i + 1);
            kinds.add(cfg.substring("kind=".length(), cfg.indexOf(',')));
            assertTrue(cfg, cfg.endsWith(",uid=10123"));
            assertTrue(cfg, cfg.contains(",card=\""));
        }
        assertEquals(VpuConfig.CODEC_KINDS, kinds);
        assertEquals(List.of("decoder", "encoder"), VpuConfig.CODEC_KINDS);
        // Decoder first: the encoder was appended, so nothing that had a decoder node moved.
        assertEquals(0, VpuConfig.CODEC_KINDS.indexOf("decoder"));
        assertEquals(1, VpuConfig.CODEC_KINDS.indexOf("encoder"));
    }

    /**
     * Video acceleration off is no pools, no camera and no codec device either -- neither the
     * decoder nor the encoder, which is the whole list.
     */
    @Test
    public void theSwitchOffGivesNoCodecDevice() {
        var item = vpuVm();
        VpuConfig.setEnabled(item, false);
        assertEquals(List.of(), emit(item));
        assertFalse(VpuConfig.codecDevicesAttached(item));
    }

    /**
     * The per-VM override: the pools stay, the camera stays, the codec devices go. That is what
     * an acceptance run needs to boot a VPU VM with no decoder helper in it, and what a phone
     * whose codec store has no usable hardware decoder needs to boot one at all.
     */
    @Test
    public void theCodecOverrideTakesTheDevicesAndNothingElse() {
        var item = withCamera(vpuVm(), "0", "Back camera (0)");
        // Both codec lines, and only they, are what the key takes: two before, none after.
        assertEquals(2 * VpuConfig.CODEC_KINDS.size(), emit(item).size());
        VpuConfig.setCodecEnabled(item, false);
        assertEquals(List.of(), emit(item));
        // Everything else the switch buys is untouched.
        assertTrue(VpuConfig.mediaDevicesAttached(item));
        assertTrue(PeripheralType.VIRTIO_CAMERA.isAttachedTo(item));
        assertEquals(320, VpuConfig.hostPoolMbFor(item));
        assertEquals(192, VpuConfig.bootMediaGuestMb(item));
        // And it is a subtraction from the switch, never a reason of its own: with video
        // acceleration off the override answers nothing.
        VpuConfig.setEnabled(item, false);
        VpuConfig.setCodecEnabled(item, true);
        assertFalse(VpuConfig.codecDevicesAttached(item));
    }

    /**
     * A helper is pool-mode only, and only the Gunyah arm of {@code buildCommand} builds a
     * {@code --pre-alloc}. On anything else a {@code uid=} device is refused by crosvm at device
     * creation and the VM does not start, so the codec devices -- which nobody asked for by name
     * -- are simply not emitted.
     */
    @Test
    public void aVmWithNoPoolsGetsNoCodecDevice() {
        assertEquals(List.of(), emit(vpuVm(), VMHypervisor.KVM));
        assertEquals(List.of(), emit(vpuVm(), VMHypervisor.GENIEZONE));
        assertEquals(List.of(), emit(vpuVm(), null));
        assertFalse(CrosvmBackendInstance.mediaHostPoolPassed(vpuVm(), VMHypervisor.KVM));
        assertTrue(CrosvmBackendInstance.mediaHostPoolPassed(vpuVm(), VMHypervisor.GUNYAH));
    }

    /** No uid is no device rather than a device the codec services would refuse to serve. */
    @Test
    public void anUnresolvedAppUidIsNoDevice() {
        var args = new ArrayList<String>();
        CrosvmBackendInstance.appendCodecDevices(args, vpuVm(), VMHypervisor.GUNYAH, () -> -1);
        assertEquals(List.of(), args);
    }

    /**
     * The camera is untouched by any of this: the same string, from the same row, and its own
     * gate is still the switch alone -- turning the codec devices off does not take the camera
     * with them.
     */
    @Test
    public void theCameraIsUnchanged() {
        var peripheral = new VMPeripheralConfig(DataItem.newObject());
        peripheral.setType(PeripheralType.VIRTIO_CAMERA);
        peripheral.setHostDevice("0", "Back camera (0)");
        assertEquals("kind=camera,camera_id=\"0\",card=\"Back camera (0)\",uid=10123",
            CrosvmBackendInstance.buildCameraConfig(peripheral, APP_UID));
        var item = withCamera(vpuVm(), "0", "Back camera (0)");
        assertTrue(PeripheralType.VIRTIO_CAMERA.isAttachedTo(item));
        VpuConfig.setCodecEnabled(item, false);
        assertTrue(PeripheralType.VIRTIO_CAMERA.isAttachedTo(item));
        // And the codec devices are not peripheral rows, so nothing about them reaches the
        // foreground-service mask, which counts rows: design 7.4, codecs need no service.
        assertEquals(0, PeripheralType.foregroundServiceTypesOf(List.of()));
    }

    /**
     * The key a hand-edited {@code vms.json} and the daemon API use, and its default.
     *
     * <p>Round-tripped through a fresh {@code DataItem} over a copy of the stored map rather than
     * through {@code JSONObject}: a JVM unit test has the android.jar stub for {@code org.json},
     * so the store's own carrier is as far as the value can be followed here. What it proves is
     * the part that can drift -- that the value is written and read under exactly
     * {@code vpu_codec_enabled} and is not a field on something.</p>
     */
    @Test
    public void theKeyRoundTripsThroughTheStoreAndDefaultsOn() {
        assertEquals("vpu_codec_enabled", VpuConfig.KEY_CODEC_ENABLED);
        // A config that has never heard of the key -- every VM saved before this build.
        var fresh = VMConfig.createWithCustomizeDefaults(null).item;
        assertTrue(VpuConfig.isCodecEnabled(fresh));
        for (boolean stored : new boolean[]{false, true}) {
            var item = vpuVm();
            VpuConfig.setCodecEnabled(item, stored);
            var reloaded = DataItem.newObject(new HashMap<>(item.asObject()));
            assertEquals(stored, reloaded.optBoolean(VpuConfig.KEY_CODEC_ENABLED, !stored));
            assertEquals(stored, VpuConfig.isCodecEnabled(reloaded));
            assertEquals(stored, VpuConfig.codecDevicesAttached(reloaded));
            assertEquals(stored ? 2 * VpuConfig.CODEC_KINDS.size() : 0, emit(reloaded).size());
        }
    }

    /**
     * The card name is derived from the kind, so the encoder got one by joining the table -- and
     * the names it produces are the ones crosvm's own helper falls back to, so a guest reads the
     * same card whether the app wrote the line or somebody ran the helper from the dev rig.
     */
    @Test
    public void everyKindHasACardNameWithoutBeingGivenOne() {
        assertEquals("droidvm decoder", VpuConfig.codecCard("decoder"));
        assertEquals("droidvm encoder", VpuConfig.codecCard("encoder"));
        for (var kind : VpuConfig.CODEC_KINDS)
            assertEquals(fmt("droidvm %s", kind), VpuConfig.codecCard(kind));
    }

    /** Every emitted argument, for the Rust harness that hands them to crosvm's own parser. */
    private static final Set<String> EMITTED = new LinkedHashSet<>();

    private static synchronized void record(String arg) {
        EMITTED.add(arg);
    }

    @AfterClass
    public static void writeEmitted() throws IOException {
        var dir = new File("build/vpu-a4");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        var text = new StringBuilder();
        for (var line : EMITTED) text.append(line).append('\n');
        Files.write(new File(dir, "codec-cli.txt").toPath(),
            text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
