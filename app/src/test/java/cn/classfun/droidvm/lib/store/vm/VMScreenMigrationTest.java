package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * The one-way fold of the legacy display keys into per-screen bindings. Every case here is a
 * config shape the editor could actually write, including the two the old keys allowed by
 * accident (display off with VNC on; a backend the GPU switch does not back).
 */
public class VMScreenMigrationTest {
    private static DataItem legacy(boolean displayEnabled, DisplayBackend backend,
                                   boolean nativeDisplay, boolean vnc) {
        var item = DataItem.newObject();
        item.set("display_enabled", displayEnabled);
        item.set("display_backend", backend);
        item.set("native_display_enabled", nativeDisplay);
        item.set("vnc_enabled", vnc);
        return item;
    }

    private static VMScreenConfig gpu0(DataItem item) {
        return VMScreenConfig.find(item, VMScreenConfig.ID_GPU0);
    }

    private static VMScreenConfig fb(DataItem item) {
        return VMScreenConfig.find(item, VMScreenConfig.ID_SIMPLEFB);
    }

    @Test
    public void windowsStyleSimplefbNative() {
        var item = legacy(true, DisplayBackend.SIMPLEFB, true, false);
        VMScreenConfig.migrate(item);
        assertFalse(gpu0(item).isEnabled());
        assertEquals(DisplayExporter.NONE, gpu0(item).getExporter());
        assertTrue(fb(item).isEnabled());
        assertEquals(DisplayExporter.NATIVE, fb(item).getExporter());
    }

    @Test
    public void linuxStyleVirtioGpuVnc() {
        var item = legacy(true, DisplayBackend.VIRTIO_GPU, false, true);
        item.set("gpu_enabled", true);
        item.set("vnc_host", "0.0.0.0");
        item.set("vnc_port", 5901);
        item.set("vnc_password_auth", true);
        item.set("vnc_password", "hunter2!");
        VMScreenConfig.migrate(item);
        assertTrue(gpu0(item).isEnabled());
        assertEquals(DisplayExporter.VNC, gpu0(item).getExporter());
        assertEquals("0.0.0.0", gpu0(item).getVncHost());
        assertEquals(5901, gpu0(item).getVncPort());
        assertTrue(gpu0(item).isVncPasswordAuth());
        assertEquals("hunter2!", gpu0(item).getVncPassword());
        assertFalse(fb(item).isEnabled());
    }

    @Test
    public void nativeWinsOverVncOnTheSameScreen() {
        // The old pair could say both; crosvm silently kept VNC and the app's Surface never got a
        // binder. One exporter per screen means the fold has to choose, and it chooses what the
        // editor's own read-back did.
        var item = legacy(true, DisplayBackend.VIRTIO_GPU, true, true);
        item.set("gpu_enabled", true);
        VMScreenConfig.migrate(item);
        assertEquals(DisplayExporter.NATIVE, gpu0(item).getExporter());
        assertEquals(DisplayExporter.NONE, fb(item).getExporter());
    }

    @Test
    public void backendNoneBindsNothing() {
        var item = legacy(true, DisplayBackend.NONE, true, false);
        VMScreenConfig.migrate(item);
        assertFalse(gpu0(item).isEnabled());
        assertFalse(fb(item).isEnabled());
        assertEquals(DisplayExporter.NONE, gpu0(item).getExporter());
        assertEquals(DisplayExporter.NONE, fb(item).getExporter());
    }

    @Test
    public void displayOffWithVncOnKeepsWorking() {
        // buildVncCommand never looked at display_enabled, so this VM had a working VNC server on
        // the GPU device's default display. It keeps one, bound where crosvm's own compat rule
        // for an unscreened exporter would have put it.
        var item = legacy(false, DisplayBackend.NONE, false, true);
        item.set("gpu_enabled", true);
        item.set("vnc_port", 5902);
        VMScreenConfig.migrate(item);
        assertTrue(gpu0(item).isEnabled());
        assertEquals(DisplayExporter.VNC, gpu0(item).getExporter());
        assertEquals(5902, gpu0(item).getVncPort());
    }

    @Test
    public void displayOffWithVncOnAndNoGpuBindsNothingButKeepsSettings() {
        var item = legacy(false, DisplayBackend.NONE, false, true);
        item.set("vnc_port", 5903);
        item.set("vnc_password", "keepme12");
        VMScreenConfig.migrate(item);
        assertEquals(DisplayExporter.NONE, gpu0(item).getExporter());
        assertEquals(DisplayExporter.NONE, fb(item).getExporter());
        assertEquals(5903, fb(item).getVncPort());
        assertEquals("keepme12", fb(item).getVncPassword());
    }

    @Test
    public void legacyKeysAreGoneAfterTheFold() {
        var item = legacy(true, DisplayBackend.SIMPLEFB, true, false);
        item.set("vnc_port", 5900);
        VMScreenConfig.migrate(item);
        for (var key : new String[]{"display_enabled", "display_backend", "native_display_enabled",
            "vnc_enabled", "vnc_host", "vnc_port", "vnc_password_auth", "vnc_password"})
            assertNull(item.opt(key, (DataItem) null));
    }

    @Test
    public void aConfigAlreadyInTheNewShapeIsLeftAlone() {
        var item = DataItem.newObject();
        var fb = VMScreenConfig.of(item, VMScreenConfig.ID_SIMPLEFB);
        fb.setEnabled(true);
        fb.setExporter(DisplayExporter.NATIVE);
        // A stray legacy key next to it must not re-run the fold and overwrite the binding.
        item.set("display_backend", DisplayBackend.VIRTIO_GPU);
        VMScreenConfig.migrate(item);
        assertTrue(fb(item).isEnabled());
        assertEquals(DisplayExporter.NATIVE, fb(item).getExporter());
    }

    @Test
    public void gpuScreenIsNotActiveWithoutAGpu() {
        var item = legacy(true, DisplayBackend.VIRTIO_GPU, true, false);
        VMScreenConfig.migrate(item);
        assertTrue(gpu0(item).isEnabled());
        assertFalse(gpu0(item).isActive(item));
        assertTrue(VMScreenConfig.boundOf(item).isEmpty());
        item.set("gpu_enabled", true);
        assertTrue(gpu0(item).isActive(item));
        assertEquals(1, VMScreenConfig.boundOf(item).size());
    }

    @Test
    public void serviceNamesAreOnePerScreenAndReverseToTheVm() {
        var vmId = "7f3c1c22-0a11-4d55-9f0e-2b0d5a6e1234";
        var gpuName = NativeDisplay.serviceNameFromId(vmId, VMScreenConfig.ID_GPU0);
        var fbName = NativeDisplay.serviceNameFromId(vmId, VMScreenConfig.ID_SIMPLEFB);
        assertEquals("droidvm_disp_7f3c1c22-0a11-4d55-9f0e-2b0d5a6e1234_gpu-0", gpuName);
        assertEquals("droidvm_disp_7f3c1c22-0a11-4d55-9f0e-2b0d5a6e1234_simplefb", fbName);
        assertEquals(vmId, NativeDisplay.vmIdFromServiceName(gpuName));
        assertEquals(vmId, NativeDisplay.vmIdFromServiceName(fbName));
        // The pre-screens name still resolves, so a console left over from an older build does
        // not make the daemon refuse to wait.
        assertEquals(vmId, NativeDisplay.vmIdFromServiceName(NativeDisplay.channelKeyFromId(vmId)));
        assertEquals("", NativeDisplay.vmIdFromServiceName("android.hardware.something"));
    }

    @Test
    public void inputIsOnForAConfigThatNeverHeardOfTheKey() {
        // The whole migration of this attribute is the default: a config written before the key
        // existed had the devices, and reading it must not take them away. Nothing rewrites the
        // file to say so, so the absent key has to answer for itself.
        var item = legacy(true, DisplayBackend.SIMPLEFB, true, false);
        VMScreenConfig.migrate(item);
        assertTrue(fb(item).isInputEnabled());
        assertNull(fb(item).item.opt("input_enabled", (DataItem) null));
        assertTrue(fb(item).hasAbsoluteInput(item));
        assertEquals(1, VMScreenConfig.absoluteInputOf(item).size());
        assertEquals(VMScreenConfig.ID_SIMPLEFB, VMScreenConfig.absoluteInputOf(item).get(0).id);
    }

    @Test
    public void switchingInputOffDropsOnlyThatScreensAbsoluteDevices() {
        var item = DataItem.newObject();
        item.set("gpu_enabled", true);
        for (var id : VMScreenConfig.IDS) {
            var screen = VMScreenConfig.of(item, id);
            screen.setEnabled(true);
            screen.setExporter(DisplayExporter.NATIVE);
        }
        assertEquals(2, VMScreenConfig.absoluteInputOf(item).size());
        VMScreenConfig.of(item, VMScreenConfig.ID_GPU0).setInputEnabled(false);
        var left = VMScreenConfig.absoluteInputOf(item);
        assertEquals(1, left.size());
        assertEquals(VMScreenConfig.ID_SIMPLEFB, left.get(0).id);
        // The screen is still a screen with an exporter -- only its two absolute devices went.
        // The keyboard and the relative pointer are the VM's, so nothing here can speak for them.
        assertTrue(VMScreenConfig.find(item, VMScreenConfig.ID_GPU0).isActive(item));
        assertEquals(2, VMScreenConfig.boundOf(item).size());
    }

    @Test
    public void aScreenNobodyWatchesGetsNoAbsoluteDevices() {
        // Input on, device present, and still nothing to emit: with no exporter there is no
        // console to send absolute events from, so the devices would be unreachable.
        var item = DataItem.newObject();
        var fb = VMScreenConfig.of(item, VMScreenConfig.ID_SIMPLEFB);
        fb.setEnabled(true);
        fb.setInputEnabled(true);
        fb.setExporter(DisplayExporter.NONE);
        assertFalse(fb.hasAbsoluteInput(item));
        assertTrue(VMScreenConfig.absoluteInputOf(item).isEmpty());
    }

    @Test
    public void absoluteSocketsAreOnePerScreenAndTheOthersIgnoreTheScreenEntirely() {
        var vmId = "7f3c1c22-0a11-4d55-9f0e-2b0d5a6e1234";
        var run = "/data/data/cn.classfun.droidvm/run/dvmin_" + vmId;
        // Exact names, because these are a contract between two processes: the daemon binds the
        // inode and crosvm is handed the path on its command line. A rename that only one side
        // learns about is a VM that does not start, so it should fail here first.
        assertEquals(run + "_sfb_mt.sock", NativeDisplay.inputSocketPath(
            vmId, VMScreenConfig.ID_SIMPLEFB, NativeDisplay.MULTITOUCH));
        assertEquals(run + "_g0_tab.sock", NativeDisplay.inputSocketPath(
            vmId, VMScreenConfig.ID_GPU0, NativeDisplay.TABLET));
        assertEquals(run + "_kbd.sock",
            NativeDisplay.inputSocketPath(vmId, "", NativeDisplay.KEYBOARD));
        assertEquals(run + "_ms.sock",
            NativeDisplay.inputSocketPath(vmId, "", NativeDisplay.MOUSE));
        // Two screens' absolute devices are different inodes, which is the whole point of them
        // being per screen: an absolute coordinate only means anything under one output.
        for (var ch : new int[]{NativeDisplay.MULTITOUCH, NativeDisplay.TABLET}) {
            assertTrue(NativeDisplay.isPerScreen(ch));
            assertNotEquals(NativeDisplay.inputSocketPath(vmId, VMScreenConfig.ID_GPU0, ch),
                NativeDisplay.inputSocketPath(vmId, VMScreenConfig.ID_SIMPLEFB, ch));
        }
        // The keyboard and the relative pointer have no output binding, so which screen the
        // console is showing must not reach their socket names at all.
        for (var ch : new int[]{NativeDisplay.KEYBOARD, NativeDisplay.MOUSE}) {
            assertFalse(NativeDisplay.isPerScreen(ch));
            assertEquals(NativeDisplay.inputSocketPath(vmId, "", ch),
                NativeDisplay.inputSocketPath(vmId, VMScreenConfig.ID_GPU0, ch));
            assertEquals(NativeDisplay.inputSocketPath(vmId, VMScreenConfig.ID_SIMPLEFB, ch),
                NativeDisplay.inputSocketPath(vmId, VMScreenConfig.ID_GPU0, ch));
        }
        // Six sockets for a two-screen VM -- two absolute devices per screen plus the VM's two --
        // and no two of them the same file.
        var seen = new HashSet<String>();
        for (int ch = 0; ch < NativeDisplay.CHANNEL_COUNT; ch++)
            for (var id : VMScreenConfig.IDS)
                seen.add(NativeDisplay.inputSocketPath(vmId, id, ch));
        assertEquals(6, seen.size());
    }

    @Test
    public void socketFilenamesAreCompactEnoughToBindWithMarginLeft() {
        // The names this asserts on are short because the long ones did not work. sun_path holds
        // 107 bytes plus a NUL; /data/data/cn.classfun.droidvm/run/ spends 35 of them before a
        // name starts; the pre-screens droidvm_disp_<uuid>_input_multitouch.sock came to 106, one
        // byte of headroom, so inserting the screen id made it 115 (111 for the tablet) and crosvm
        // refused the command line with "path must be shorter than SUN_LEN".
        //
        // So measure against the real run directory rather than a stub -- half the budget is the
        // base dir, and a test that supplies its own would not have caught this -- and hold the
        // result to 100, well under the limit, so the next name that grows has somewhere to grow.
        var vmId = UUID.randomUUID().toString();
        assertEquals(36, vmId.length());
        var sample = NativeDisplay.inputSocketPath(vmId, VMScreenConfig.ID_GPU0,
            NativeDisplay.MULTITOUCH);
        assertEquals("/data/data/cn.classfun.droidvm/run/",
            sample.substring(0, sample.lastIndexOf('/') + 1));
        var worst = "";
        for (int ch = 0; ch < NativeDisplay.CHANNEL_COUNT; ch++) {
            for (var id : VMScreenConfig.IDS) {
                var path = NativeDisplay.inputSocketPath(vmId, id, ch);
                if (bytes(path) > bytes(worst)) worst = path;
            }
        }
        assertEquals(90, bytes(worst));
        assertTrue(worst, bytes(worst) <= 100);
        assertTrue(worst, bytes(worst) <= NativeDisplay.MAX_UNIX_PATH);
        // And the check the daemon runs before binding agrees with all of them.
        for (int ch = 0; ch < NativeDisplay.CHANNEL_COUNT; ch++)
            for (var id : VMScreenConfig.IDS)
                NativeDisplay.requireBindablePath(NativeDisplay.inputSocketPath(vmId, id, ch));
    }

    @Test
    public void anOverlongSocketPathIsRefusedInsteadOfBeingTruncated() {
        // bind(2) copies the path into a 108-byte sun_path and truncates without a word, so the
        // daemon would listen on one inode while crosvm connects to the name it was given -- the
        // two stubs the failing build left in run/, "..._simplefb_input_multito" and
        // "..._simplefb_input_tablet.", were the only evidence it had happened. The check has to
        // fire before the syscall, and it has to say which path and how long.
        var longest = "/x".repeat(53) + "y";
        assertEquals(NativeDisplay.MAX_UNIX_PATH, longest.length());
        assertEquals(longest, NativeDisplay.requireBindablePath(longest));
        var overlong = longest + "z";
        var e = assertThrows(IllegalArgumentException.class,
            () -> NativeDisplay.requireBindablePath(overlong));
        assertTrue(e.getMessage(), e.getMessage().contains(overlong));
        assertTrue(e.getMessage(), e.getMessage().contains("108"));
        // Bytes, not chars: sun_path is a byte array, and a name that is short in code points can
        // still be too long for it.
        var wide = "/" + "é".repeat(NativeDisplay.MAX_UNIX_PATH - 1);
        assertEquals(NativeDisplay.MAX_UNIX_PATH, wide.length());
        assertThrows(IllegalArgumentException.class,
            () -> NativeDisplay.requireBindablePath(wide));
    }

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    public void touchDeviceNamesAreDerivedFromTheScreenAndNothingElse() {
        // The guest maps a touchscreen to an output by this string and stores it by this string,
        // so it must be a pure function of the screen id -- no VM, no index, no ordering.
        assertEquals("DroidVM Touch (gpu-0)",
            NativeDisplay.touchDeviceName(VMScreenConfig.ID_GPU0));
        assertEquals("DroidVM Touch (simplefb)",
            NativeDisplay.touchDeviceName(VMScreenConfig.ID_SIMPLEFB));
        assertNotEquals(NativeDisplay.touchDeviceName(VMScreenConfig.ID_GPU0),
            NativeDisplay.touchDeviceName(VMScreenConfig.ID_SIMPLEFB));
    }

    @Test
    public void tabletDeviceNamesAreDerivedTooAndNeverCollideWithTheTouchscreen() {
        // The tablet is as much a per-output device as the touchscreen, so it is derived the same
        // way. It also has to be distinguishable from its own screen's touchscreen: the two sit
        // side by side in the guest's device list and the user picks between them there.
        assertEquals("DroidVM Tablet (gpu-0)",
            NativeDisplay.tabletDeviceName(VMScreenConfig.ID_GPU0));
        assertEquals("DroidVM Tablet (simplefb)",
            NativeDisplay.tabletDeviceName(VMScreenConfig.ID_SIMPLEFB));
        assertNotEquals(NativeDisplay.tabletDeviceName(VMScreenConfig.ID_GPU0),
            NativeDisplay.tabletDeviceName(VMScreenConfig.ID_SIMPLEFB));
        for (var id : new String[] {VMScreenConfig.ID_GPU0, VMScreenConfig.ID_SIMPLEFB}) {
            assertNotEquals(NativeDisplay.touchDeviceName(id), NativeDisplay.tabletDeviceName(id));
        }
    }

    @Test
    public void perScreenNamesCarryNoCommaThatWouldSplitAnInputOption() {
        // Both names are interpolated into a crosvm `--input kind[path=...,name=...]` option, whose
        // key-value parser runs an unquoted value to the next ',' or ']'. Spaces and parentheses
        // survive that; a ',' or a bracket in a screen id would silently truncate the name into a
        // different device, so assert the characters that would do it never appear.
        for (var id : new String[] {VMScreenConfig.ID_GPU0, VMScreenConfig.ID_SIMPLEFB}) {
            for (var name : new String[] {
                NativeDisplay.touchDeviceName(id), NativeDisplay.tabletDeviceName(id)}) {
                assertEquals(-1, name.indexOf(','));
                assertEquals(-1, name.indexOf('['));
                assertEquals(-1, name.indexOf(']'));
            }
        }
    }
}
