package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
    public void absoluteSocketsRideTheScreenIdentityAndTheOthersTheVms() {
        var vmId = "7f3c1c22-0a11-4d55-9f0e-2b0d5a6e1234";
        // The two absolute channels are keyed by the screen's display-service name -- the same
        // string the binder is registered under -- so a screen has one identity, not two.
        assertEquals(NativeDisplay.serviceNameFromId(vmId, VMScreenConfig.ID_SIMPLEFB),
            NativeDisplay.inputSocketKey(vmId, VMScreenConfig.ID_SIMPLEFB,
                NativeDisplay.MULTITOUCH));
        assertEquals(NativeDisplay.serviceNameFromId(vmId, VMScreenConfig.ID_SIMPLEFB),
            NativeDisplay.inputSocketKey(vmId, VMScreenConfig.ID_SIMPLEFB, NativeDisplay.TABLET));
        // The keyboard and the relative pointer have no output binding, so which screen the
        // console is showing must not reach their socket names at all.
        for (var ch : new int[]{NativeDisplay.KEYBOARD, NativeDisplay.MOUSE}) {
            assertFalse(NativeDisplay.isPerScreen(ch));
            assertEquals(NativeDisplay.channelKeyFromId(vmId),
                NativeDisplay.inputSocketKey(vmId, VMScreenConfig.ID_GPU0, ch));
            assertEquals(NativeDisplay.inputSocketKey(vmId, VMScreenConfig.ID_SIMPLEFB, ch),
                NativeDisplay.inputSocketKey(vmId, VMScreenConfig.ID_GPU0, ch));
        }
        // Two screens' touch sockets are different inodes, which is the whole point.
        assertNotEquals(
            NativeDisplay.inputSocketPath(NativeDisplay.inputSocketKey(
                vmId, VMScreenConfig.ID_GPU0, NativeDisplay.MULTITOUCH), NativeDisplay.MULTITOUCH),
            NativeDisplay.inputSocketPath(NativeDisplay.inputSocketKey(
                vmId, VMScreenConfig.ID_SIMPLEFB, NativeDisplay.MULTITOUCH),
                NativeDisplay.MULTITOUCH));
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
}
