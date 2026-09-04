// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;

/**
 * The exact {@code --virtio-media} argument a camera row produces.
 *
 * <p>The strings are asserted here and also written to {@code build/vpu-a2/camera-cli.txt}, which
 * is fed to crosvm's own parser by the scratch harness in {@code logs/vpu_wp/scratch-a2}: an
 * assertion in Java can only say the string is what this file expects, and the question that
 * matters is whether the other side accepts it. {@code MediaDeviceConfig} is
 * {@code deny_unknown_fields}, so a key crosvm does not know is a VM that will not start rather
 * than a flag it ignores. See {@code logs/vpu_wp/A2.md} for the parse results.</p>
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
