package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * The transport ladder: which rungs each (screen, exporter) edge has, which of them this build can
 * honour, and what the app therefore puts on crosvm's command line.
 *
 * <p>Both ends decide the set, which is why every case here names both. The distinction the tests
 * are really holding is between a rung that is <em>unbuilt</em> (offered, refused, and expected to
 * arrive) and one that is <em>unreachable</em> (absent, because it never will).</p>
 *
 * <p>Two of the predicates below belong to the daemon and the editor rather than to this enum, and
 * are held here for the same reason: they decide whether a rung the ladder offers is actually
 * climbed at run time -- one from the VM's bindings, one from a number the user typed -- and
 * neither of their owners can be stood up without a device.</p>
 */
public class DisplayTransportCapTest {
    private static final String GPU0 = VMScreenConfig.ID_GPU0;
    private static final String FB = VMScreenConfig.ID_SIMPLEFB;

    @Test
    public void everyLadderIsBottomRungFirstAndStartsAtCpuCopy() {
        // CPU copy needs nothing from either end, so it is the one rung that is always there --
        // and it is what makes a ceiling always satisfiable.
        for (var screen : new String[]{GPU0, FB})
            for (var exporter : new DisplayExporter[]{DisplayExporter.NATIVE, DisplayExporter.VNC})
                assertEquals(DisplayTransportCap.CPU,
                    DisplayTransportCap.optionsFor(screen, exporter)[0]);
    }

    @Test
    public void theNativeDisplayCanBeLentARenderTargetOnlyByTheGpuScreen() {
        // Zero copy is the sink lending a buffer for the guest's rendering to land in. simplefb's
        // framebuffer is a fixed window of guest memory named in the device tree and no
        // AHardwareBuffer can be made to wrap it, so that rung is not unbuilt there, it is
        // unreachable -- absent from the menu rather than greyed in it.
        assertArrayEquals(
            new DisplayTransportCap[]{
                DisplayTransportCap.CPU, DisplayTransportCap.GPU, DisplayTransportCap.ZERO},
            DisplayTransportCap.optionsFor(GPU0, DisplayExporter.NATIVE));
        assertArrayEquals(
            new DisplayTransportCap[]{DisplayTransportCap.CPU, DisplayTransportCap.GPU},
            DisplayTransportCap.optionsFor(FB, DisplayExporter.NATIVE));
        assertFalse(DisplayTransportCap.isOfferedFor(
            FB, DisplayExporter.NATIVE, DisplayTransportCap.ZERO));
    }

    @Test
    public void vncsTopRungIsAnEncoderRatherThanALentBuffer() {
        // There is nowhere to lend a render target to a remote client, so VNC's rung above a blit
        // is handing the result to a hardware encoder instead of an RFB rectangle. Same ladder on
        // both screens, because it is the exporter's end that supplies it.
        for (var screen : new String[]{GPU0, FB})
            assertArrayEquals(
                new DisplayTransportCap[]{
                    DisplayTransportCap.CPU, DisplayTransportCap.GPU, DisplayTransportCap.GPU_HW},
                DisplayTransportCap.optionsFor(screen, DisplayExporter.VNC));
    }

    @Test
    public void anUnwatchedScreenHasNoLadderAtAll() {
        // No exporter, no edge; a ceiling on nothing is not a smaller menu, it is no menu.
        for (var screen : new String[]{GPU0, FB})
            assertEquals(0, DisplayTransportCap.optionsFor(screen, DisplayExporter.NONE).length);
    }

    @Test
    public void whatThisBuildRefusesIsListedRatherThanHidden() {
        // Offered and greyed, so the ladder reads whole and a rung landing later is recognisably
        // the thing that was already there.
        assertArrayEquals(new DisplayTransportCap[]{DisplayTransportCap.ZERO},
            DisplayTransportCap.unimplementedFor(GPU0, DisplayExporter.NATIVE));
        assertEquals(0,
            DisplayTransportCap.unimplementedFor(FB, DisplayExporter.NATIVE).length);
        assertArrayEquals(
            new DisplayTransportCap[]{DisplayTransportCap.GPU, DisplayTransportCap.GPU_HW},
            DisplayTransportCap.unimplementedFor(GPU0, DisplayExporter.VNC));
    }

    @Test
    public void theDefaultIsTheHighestRungThatActuallyWorks() {
        // Not the highest offered: defaulting every VM to a ceiling nothing can satisfy is a
        // promise the negotiation would quietly break. This one restricts nothing that works, and
        // it rises on its own as the rungs land.
        assertEquals(DisplayTransportCap.GPU,
            DisplayTransportCap.defaultFor(GPU0, DisplayExporter.NATIVE));
        assertEquals(DisplayTransportCap.GPU,
            DisplayTransportCap.defaultFor(FB, DisplayExporter.NATIVE));
        assertEquals(DisplayTransportCap.CPU,
            DisplayTransportCap.defaultFor(GPU0, DisplayExporter.VNC));
        assertEquals(DisplayTransportCap.CPU,
            DisplayTransportCap.defaultFor(FB, DisplayExporter.VNC));
    }

    @Test
    public void theStoredTokenIsTheOneCrosvmTakes() {
        // Written into the config and onto a command line, so one value must not have two
        // spellings. Read back case-insensitively, because a hand-edited file is still a file.
        assertEquals("cpu", DisplayTransportCap.CPU.getToken());
        assertEquals("gpu-hw", DisplayTransportCap.GPU_HW.getToken());
        for (var cap : DisplayTransportCap.values())
            assertEquals(cap, DisplayTransportCap.fromToken(cap.getToken()));
        assertEquals(DisplayTransportCap.GPU_HW, DisplayTransportCap.fromToken("GPU-HW"));
        assertNull(DisplayTransportCap.fromToken(""));
        assertNull(DisplayTransportCap.fromToken(null));
        assertNull(DisplayTransportCap.fromToken("zero-copy"));
    }

    @Test
    public void aScreenWithNoStoredCeilingAnswersWithItsEdgeDefault() {
        var item = DataItem.newObject();
        var fb = VMScreenConfig.of(item, VMScreenConfig.ID_SIMPLEFB);
        fb.setEnabled(true);
        fb.setExporter(DisplayExporter.VNC);
        assertEquals(DisplayTransportCap.CPU, fb.getTransportCap());
        fb.setExporter(DisplayExporter.NATIVE);
        assertEquals(DisplayTransportCap.GPU, fb.getTransportCap());
    }

    @Test
    public void aCeilingTheEdgeDoesNotHaveFallsBackWithoutBeingErased() {
        // Reading under a different exporter must not lose the answer: the user is allowed to look
        // at the other exporter's settings and come back.
        var item = DataItem.newObject();
        var gpu0 = VMScreenConfig.of(item, VMScreenConfig.ID_GPU0);
        gpu0.setEnabled(true);
        gpu0.setExporter(DisplayExporter.NATIVE);
        gpu0.setTransportCap(DisplayTransportCap.ZERO);
        assertEquals(DisplayTransportCap.ZERO, gpu0.getTransportCap());
        gpu0.setExporter(DisplayExporter.VNC);
        assertEquals(DisplayTransportCap.CPU, gpu0.getTransportCap());
        gpu0.setExporter(DisplayExporter.NATIVE);
        assertEquals(DisplayTransportCap.ZERO, gpu0.getTransportCap());
    }

    @Test
    public void onlyTheBottomRungIsWorthPuttingOnTheCommandLine() {
        // The emission rule, held here because the arg builder itself needs a live daemon: capping
        // at a CPU copy asks the host to skip a blit it could have done, and every rung above it
        // is at or above what any sink can reach today -- so naming those would restrict nothing,
        // and a flag whose presence and absence mean the same thing is worse than no flag.
        var item = DataItem.newObject();
        var gpu0 = VMScreenConfig.of(item, VMScreenConfig.ID_GPU0);
        gpu0.setEnabled(true);
        gpu0.setExporter(DisplayExporter.NATIVE);
        assertFalse(emitsCap(gpu0));
        gpu0.setTransportCap(DisplayTransportCap.CPU);
        assertTrue(emitsCap(gpu0));
        gpu0.setTransportCap(DisplayTransportCap.GPU);
        assertFalse(emitsCap(gpu0));

        // A VNC binding caps at CPU by default, because that is the only rung its sink has, so it
        // is the one ordinary configuration where the flag does get written out.
        var fb = VMScreenConfig.of(item, VMScreenConfig.ID_SIMPLEFB);
        fb.setEnabled(true);
        fb.setExporter(DisplayExporter.VNC);
        assertTrue(emitsCap(fb));
    }

    /** The predicate {@code CrosvmBackendInstance.transportCapArg} branches on. */
    private static boolean emitsCap(VMScreenConfig screen) {
        return screen.getTransportCap() == DisplayTransportCap.CPU;
    }

    @Test
    public void theBlitDriverIsPointedAtAnyNativeBinding() {
        // CROSVM_DISPLAY_VULKAN_LIBRARY is process-wide and the bridge dlopens it for whichever
        // screen reaches the native display, so the question it is set from is "has this VM a
        // native binding", not "has the GPU screen one". The simplefb bridge does the same dma-buf
        // import and the same blit; asking about gpu-0 left it with no driver to load, which is a
        // CPU copy for a VM whose ceiling says otherwise.
        var item = DataItem.newObject();
        assertFalse(VMScreenConfig.hasNativeExporter(item));

        var fb = VMScreenConfig.of(item, VMScreenConfig.ID_SIMPLEFB);
        fb.setEnabled(true);
        fb.setExporter(DisplayExporter.NATIVE);
        assertTrue(VMScreenConfig.hasNativeExporter(item));

        // The switch is the device, so a binding on a screen the VM does not have is not one.
        fb.setEnabled(false);
        assertFalse(VMScreenConfig.hasNativeExporter(item));

        // VNC never reaches the bridge on either screen, and the GPU screen's own native binding
        // still answers yes -- the widened question includes the one it used to ask.
        fb.setEnabled(true);
        fb.setExporter(DisplayExporter.VNC);
        var gpu0 = VMScreenConfig.of(item, VMScreenConfig.ID_GPU0);
        gpu0.setEnabled(true);
        gpu0.setExporter(DisplayExporter.VNC);
        assertFalse(VMScreenConfig.hasNativeExporter(item));
        gpu0.setExporter(DisplayExporter.NATIVE);
        assertTrue(VMScreenConfig.hasNativeExporter(item));
    }

    @Test
    public void aSimplefbWidthOffTheGrainSpendsTheGpuCopy() {
        // The editor's warning condition. simplefb's pitch is width*4 with nothing padding it and
        // the blit's LINEAR dma-buf import wants 64 bytes, so the rule is a width that is a
        // multiple of 16. Measured on device: 1400 falls back to the CPU copy, 1408 does not.
        assertTrue(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.NATIVE, DisplayTransportCap.GPU, 1400));
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.NATIVE, DisplayTransportCap.GPU, 1408));

        // A ceiling already at the bottom rung loses nothing to the width, so there is nothing to
        // tell the user -- the CPU copy is what was asked for.
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.NATIVE, DisplayTransportCap.CPU, 1400));

        // The virtio-gpu scanout is allocated on the host and rounded up to what the importer
        // wants, so it meets the rule by construction and the same width costs it nothing.
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            GPU0, DisplayExporter.NATIVE, DisplayTransportCap.GPU, 1400));

        // VNC has no GPU half to spend: that rung is offered and refused, so a ceiling naming it
        // is already on the CPU copy for a reason the width has nothing to do with. Same for a
        // screen nobody is watching, which has no edge for a transport to run along at all.
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.VNC, DisplayTransportCap.GPU, 1400));
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.NONE, DisplayTransportCap.GPU, 1400));

        // What the editor hands over for a field that is empty or half-typed: not yet a width, so
        // not yet anything to warn about. The geometry validator is what has something to say.
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.NATIVE, DisplayTransportCap.GPU, 0));
    }
}
