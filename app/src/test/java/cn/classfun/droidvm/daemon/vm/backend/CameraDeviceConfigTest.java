// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
import cn.classfun.droidvm.lib.store.vm.VpuConfig;

/**
 * The exact {@code --virtio-media} argument a camera row produces.
 *
 * <p>The strings are asserted here and also written to {@code build/vpu-a2/camera-cli.txt}, which
 * is fed to crosvm's own parser by the scratch harness in {@code logs/vpu_wp/scratch-a2}: an
 * assertion in Java can only say the string is what this file expects, and the question that
 * matters is whether the other side accepts it. {@code MediaDeviceConfig} is
 * {@code deny_unknown_fields}, so a key crosvm does not know is a VM that will not start rather
 * than a flag it ignores. See {@code logs/vpu_wp/A2.md} for the parse results.</p>
 *
 * <p>Whether the argument is emitted at all is a separate question with a separate answer: the
 * row is a virtio-media device, so the backend only builds one for a VM whose VPU switch is on
 * ({@code aCameraIsOnlyADeviceWhileTheVpuSwitchIsOn} below). The string itself does not change
 * either way.</p>
 */
public final class CameraDeviceConfigTest {
    private static final int APP_UID = 10123;

    private static VMPeripheralConfig camera(String hostDevice, String label) {
        var peripheral = new VMPeripheralConfig(DataItem.newObject());
        peripheral.setType(PeripheralType.VIRTIO_CAMERA);
        peripheral.setHostDevice(hostDevice, label);
        return peripheral;
    }

    private final List<String> emitted = new ArrayList<>();

    private String build(String hostDevice, String label) {
        var arg = CrosvmBackendInstance.buildCameraConfig(camera(hostDevice, label), APP_UID);
        emitted.add(arg);
        return arg;
    }

    /** A camera the user picked, and a label with the spaces a host label really has. */
    @Test
    public void aPickedCameraNamesItsIdAndItsCard() throws IOException {
        assertEquals("kind=camera,camera_id=\"0\",card=\"Back camera (0)\",uid=10123",
            build("0", "Back camera (0)"));
        assertEquals("kind=camera,camera_id=\"1\",card=\"Front camera (1)\",uid=10123",
            build("1", "Front camera (1)"));
        save();
    }

    /**
     * "Let the host pick" leaves the key out rather than passing an empty one. crosvm parses
     * {@code camera_id=""} -- as {@code Some("")}, an id that is the empty string, which the host
     * would then have to know to read as "unset". {@code None} already says that.
     */
    @Test
    public void theDefaultCameraOmitsTheKeyEntirely() throws IOException {
        assertEquals("kind=camera,card=\"DroidVM camera\",uid=10123", build("", ""));
        assertEquals("kind=camera,card=\"Default camera\",uid=10123",
            build("", "Default camera"));
        save();
    }

    /** A label with a comma in it stays one value: it is quoted, and the quotes round-trip. */
    @Test
    public void aCommaInTheLabelStaysInsideTheValue() throws IOException {
        assertEquals("kind=camera,camera_id=\"0\",card=\"Back camera, wide (0)\",uid=10123",
            build("0", "Back camera, wide (0)"));
        save();
    }

    /**
     * A quote or a backslash would end the value early and turn the rest of the line into keys
     * crosvm refuses, so the character is replaced rather than escaped -- a hostile label costs a
     * character, not the VM's start.
     */
    @Test
    public void aQuoteOrBackslashIsReplacedRatherThanEscaped() throws IOException {
        var arg = build("0", "say \"hi\",kind=simple");
        assertEquals("kind=camera,camera_id=\"0\",card=\"say 'hi',kind=simple\",uid=10123", arg);
        assertTrue(arg.indexOf('"') >= 0);
        assertEquals("kind=camera,camera_id=\"0\",card=\"a b\",uid=10123", build("0", "a\\b"));
        save();
    }

    /**
     * The gate the camera arm of {@code buildPeripheralCommand} reads before it emits any of the
     * strings above: {@link PeripheralType#needsVpu()} against the VM's own switch. A camera row
     * on a VM with video acceleration off is skipped with a log line -- crosvm will not create a
     * virtio-media device without the media_host pool the switch buys, so attaching one anyway
     * would be a VM that refuses to start.
     *
     * <p>{@code buildPeripheralCommand} itself needs a {@code ServerContext}, which loads
     * on-device state in its constructor, so what is asserted is the predicate it branches on --
     * the same call, off the same config. See {@code logs/vpu_wp/A2.md} section 4.</p>
     */
    @Test
    public void aCameraIsOnlyADeviceWhileTheVpuSwitchIsOn() {
        assertTrue(PeripheralType.VIRTIO_CAMERA.needsVpu());
        assertFalse(PeripheralType.VIRTIO_SOUND.needsVpu());
        var item = VMConfig.createWithCustomizeDefaults(null).item;
        var peripherals = DataItem.newArray();
        peripherals.append(camera("0", "Back camera (0)").item);
        item.set("peripherals", peripherals);
        // The predicate itself, both halves and in the order the arm reads them.
        assertFalse(VpuConfig.mediaDevicesAttached(item));
        assertFalse(PeripheralType.VIRTIO_CAMERA.isAttachedTo(item));
        VpuConfig.setEnabled(item, true);
        assertTrue(VpuConfig.mediaDevicesAttached(item));
        assertTrue(PeripheralType.VIRTIO_CAMERA.isAttachedTo(item));
        // A row this build cannot serve is not attached whatever the switch says, and a row that
        // does not ride the transport is not touched by it in either direction.
        assertFalse(PeripheralType.INTEL_HDA.isAttachedTo(item));
        assertTrue(PeripheralType.VIRTIO_SOUND.isAttachedTo(item));
        VpuConfig.setEnabled(item, false);
        assertFalse(PeripheralType.INTEL_HDA.isAttachedTo(item));
        assertTrue(PeripheralType.VIRTIO_SOUND.isAttachedTo(item));
    }

    /**
     * The {@code camera_keep_screen_on} key, its default, and that it survives the store.
     *
     * <p>Round-tripped through a fresh {@code DataItem} over a copy of the stored map rather than
     * through {@code JSONObject}, for the reason {@code CodecDeviceConfigTest} gives: a JVM unit
     * test has the android.jar stub for {@code org.json}. What it proves is the part that can
     * drift -- that the value is written and read under exactly the key a hand-edited
     * {@code vms.json} and {@code vm_modify} would use, and that a config which has never heard
     * of the key gets the mitigation rather than the limitation.</p>
     *
     * <p>Default on is the decision (2026-09-13): D76 costs a user a dead capture and a log line
     * they have to know how to read, while the switch costs a display that stays on for as long
     * as their own VM is using the camera. Off restores the documented behaviour exactly.</p>
     */
    @Test
    public void theKeepScreenOnKeyRoundTripsThroughTheStoreAndDefaultsOn() {
        assertEquals("camera_keep_screen_on", VpuConfig.KEY_CAMERA_KEEP_SCREEN_ON);
        // Every VM saved before this build, and every VM the creation defaults produce: on.
        var fresh = VMConfig.createWithCustomizeDefaults(null).item;
        assertTrue(VpuConfig.isCameraKeepScreenOn(fresh));
        for (boolean stored : new boolean[]{false, true}) {
            var item = VMConfig.createWithCustomizeDefaults(null).item;
            VpuConfig.setCameraKeepScreenOn(item, stored);
            var reloaded = DataItem.newObject(new HashMap<>(item.asObject()));
            assertEquals(stored,
                reloaded.optBoolean(VpuConfig.KEY_CAMERA_KEEP_SCREEN_ON, !stored));
            assertEquals(stored, VpuConfig.isCameraKeepScreenOn(reloaded));
        }
        // It is not the VPU switch and does not touch it: a VM can keep the screen for its camera
        // with the codec devices off, and turning it off takes nothing else away.
        var item = VMConfig.createWithCustomizeDefaults(null).item;
        VpuConfig.setEnabled(item, true);
        VpuConfig.setCameraKeepScreenOn(item, false);
        assertTrue(VpuConfig.mediaDevicesAttached(item));
        assertTrue(VpuConfig.codecDevicesAttached(item));
        assertEquals(VpuConfig.DEFAULT_HOST_POOL_MB, (int) VpuConfig.hostPoolMbFor(item));
    }

    /** Appends this test's strings to the file the Rust harness parses, one per line. */
    private void save() throws IOException {
        var dir = new File("build/vpu-a2");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        var out = new File(dir, "camera-cli.txt");
        var text = new StringBuilder();
        for (var arg : emitted) text.append(arg).append('\n');
        // Truncate once per run, then append: JUnit gives each test its own instance, so the
        // file is the union of the methods rather than whichever ran last.
        var mode = STARTED.getAndSet(true)
            ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING;
        Files.write(out.toPath(), text.toString().getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, mode);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
        new java.util.concurrent.atomic.AtomicBoolean(false);
}
