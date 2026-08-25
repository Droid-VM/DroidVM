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
        // VNC's GPU copy landed, so what is left greyed on that ladder is the encoder above it --
        // and it is greyed on both screens, since that rung is the exporter's rather than either
        // screen's.
        for (var screen : new String[]{GPU0, FB})
            assertArrayEquals(new DisplayTransportCap[]{DisplayTransportCap.GPU_HW},
                DisplayTransportCap.unimplementedFor(screen, DisplayExporter.VNC));
    }

    @Test
    public void bothSinksBlitAndNeitherHasTheRungAboveItsOwn() {
        // The two exporters reached the same rung by different routes -- one blits into a Surface,
        // the other into a headless target -- so what separates them now is only what sits above:
        // a lent render target on one side, a hardware encoder on the other, neither built.
        for (var exporter : new DisplayExporter[]{DisplayExporter.NATIVE, DisplayExporter.VNC}) {
            assertTrue(DisplayTransportCap.isImplemented(exporter, DisplayTransportCap.CPU));
            assertTrue(DisplayTransportCap.isImplemented(exporter, DisplayTransportCap.GPU));
        }
        assertFalse(DisplayTransportCap.isImplemented(
            DisplayExporter.NATIVE, DisplayTransportCap.ZERO));
        assertFalse(DisplayTransportCap.isImplemented(
            DisplayExporter.VNC, DisplayTransportCap.GPU_HW));
        // A screen nobody watches has no edge, so there is nothing for a build to have reached.
        for (var cap : DisplayTransportCap.values())
            assertFalse(DisplayTransportCap.isImplemented(DisplayExporter.NONE, cap));
    }

    @Test
    public void theDefaultIsTheHighestRungThatActuallyWorks() {
        // Not the highest offered: defaulting every VM to a ceiling nothing can satisfy is a
        // promise the negotiation would quietly break. This one restricts nothing that works, and
        // it rises on its own as the rungs land -- which is exactly what VNC's did when its GPU
        // half was built. Nothing here was edited to make that happen; the rule produced it.
        for (var screen : new String[]{GPU0, FB})
            for (var exporter : new DisplayExporter[]{DisplayExporter.NATIVE, DisplayExporter.VNC})
                assertEquals(DisplayTransportCap.GPU,
                    DisplayTransportCap.defaultFor(screen, exporter));
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
        assertEquals(DisplayTransportCap.GPU, fb.getTransportCap());
        fb.setExporter(DisplayExporter.NATIVE);
        assertEquals(DisplayTransportCap.GPU, fb.getTransportCap());
    }

    @Test
    public void onlyTheUnsaidCeilingFollowsTheDefaultUp() {
        // The default rising to the GPU rung is for screens that never named one. A config that
        // says "cpu" said it on purpose -- to keep a screen off the blit -- and reads back the
        // same after the rung above it was built, which is also what keeps the flag being written
        // for it. Nothing rewrites the file, so this holds for a config saved by any older build.
        var item = DataItem.newObject();
        var fb = VMScreenConfig.of(item, VMScreenConfig.ID_SIMPLEFB);
        fb.setEnabled(true);
        fb.setExporter(DisplayExporter.VNC);
        fb.setTransportCap(DisplayTransportCap.CPU);
        assertEquals(DisplayTransportCap.CPU, fb.getTransportCap());
        assertTrue(emitsCap(fb));
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
        // Zero copy is a rung VNC's ladder does not have at all, so under that exporter the
        // stored value is not a ceiling but a word from another ladder, and the answer is VNC's
        // own default.
        gpu0.setExporter(DisplayExporter.VNC);
        assertEquals(DisplayTransportCap.GPU, gpu0.getTransportCap());
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

        // A VNC binding no longer names one either: its sink reached the blit, so its default is
        // the GPU rung and the flag it used to be written out with is gone from every default
        // configuration. The flag is now what an explicit opt-out looks like, on either exporter.
        var fb = VMScreenConfig.of(item, VMScreenConfig.ID_SIMPLEFB);
        fb.setEnabled(true);
        fb.setExporter(DisplayExporter.VNC);
        assertFalse(emitsCap(fb));
        fb.setTransportCap(DisplayTransportCap.CPU);
        assertTrue(emitsCap(fb));
    }

    /** The predicate {@code CrosvmBackendInstance.transportCapArg} branches on. */
    private static boolean emitsCap(VMScreenConfig screen) {
        return screen.getTransportCap() == DisplayTransportCap.CPU;
    }

    @Test
    public void theBlitDriverIsPointedAtAnyBindingThatCouldClimbToIt() {
        // CROSVM_DISPLAY_VULKAN_LIBRARY is process-wide and both sinks dlopen it now -- the native
        // bridge to blit into a Surface, the VNC sink into a headless target -- so the question it
        // is set from is "could any binding this VM has blit", not "has this VM a native binding".
        // Asking the narrower one left a VNC-only VM with no driver to load, which is a CPU copy
        // for a config whose ceiling says otherwise.
        var item = DataItem.newObject();
        assertFalse(VMScreenConfig.hasGpuBlitBinding(item));

        var fb = VMScreenConfig.of(item, VMScreenConfig.ID_SIMPLEFB);
        fb.setEnabled(true);
        fb.setExporter(DisplayExporter.NATIVE);
        assertTrue(VMScreenConfig.hasGpuBlitBinding(item));

        // The switch is the device, so a binding on a screen the VM does not have is not one.
        fb.setEnabled(false);
        assertFalse(VMScreenConfig.hasGpuBlitBinding(item));

        // A VNC binding at its default ceiling is one of them, which is the whole change: this is
        // the ordinary VM a new config comes up as.
        fb.setEnabled(true);
        fb.setExporter(DisplayExporter.VNC);
        assertTrue(VMScreenConfig.hasGpuBlitBinding(item));

        // Capped at the CPU copy on purpose, it is not: crosvm skips the blit outright there, so
        // naming a driver would be describing a load that never happens.
        fb.setTransportCap(DisplayTransportCap.CPU);
        assertFalse(VMScreenConfig.hasGpuBlitBinding(item));

        // One capped screen does not answer for the VM. The env is process-wide, so any one
        // binding that could blit is enough to need it.
        var gpu0 = VMScreenConfig.of(item, VMScreenConfig.ID_GPU0);
        gpu0.setEnabled(true);
        gpu0.setExporter(DisplayExporter.VNC);
        assertTrue(VMScreenConfig.hasGpuBlitBinding(item));

        // A native binding answers yes whatever its ceiling says: the bridge is pointed at a
        // driver it may not dlopen, which costs nothing, where the reverse mistake costs the GPU
        // path in silence. So the widened question still contains the one it used to ask.
        gpu0.setExporter(DisplayExporter.NATIVE);
        gpu0.setTransportCap(DisplayTransportCap.CPU);
        assertTrue(VMScreenConfig.hasGpuBlitBinding(item));

        // And a screen nobody watches is not a binding at all, whatever ceiling it remembers.
        gpu0.setExporter(DisplayExporter.NONE);
        assertFalse(VMScreenConfig.hasGpuBlitBinding(item));
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

        // VNC's GPU half imports the same dma-buf under the same rule, so the warning appeared on
        // that edge the moment the rung was implemented -- the condition asks isImplemented rather
        // than naming the native display, and was not touched to make this true.
        assertTrue(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.VNC, DisplayTransportCap.GPU, 1400));
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.VNC, DisplayTransportCap.GPU, 1408));
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.VNC, DisplayTransportCap.CPU, 1400));

        // A screen nobody is watching has no edge for a transport to run along at all, so there is
        // no rung for a width to cost it.
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.NONE, DisplayTransportCap.GPU, 1400));

        // What the editor hands over for a field that is empty or half-typed: not yet a width, so
        // not yet anything to warn about. The geometry validator is what has something to say.
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.NATIVE, DisplayTransportCap.GPU, 0));
    }

    @Test
    public void theSideChannelPortIsDerivedUnlessItWasNamed() {
        // The port the app's own console connects to for H.264. Nobody writes the derived value
        // down: the host derives it the same way from the same RFB port, and a copy of a rule
        // stored beside the rule is a copy that can go stale.
        var item = DataItem.newObject();
        var gpu0 = VMScreenConfig.of(item, VMScreenConfig.ID_GPU0);
        gpu0.setEnabled(true);
        gpu0.setExporter(DisplayExporter.VNC);
        gpu0.setVncPort(5900);
        assertEquals(-1, gpu0.getVncH264Port());
        assertFalse(gpu0.hasVncH264PortOverride());
        assertEquals(5900 + VMScreenConfig.H264_PORT_OFFSET, gpu0.effectiveVncH264Port());

        // Named, and then it is what it says -- and the flag on the command line exists only for
        // this case, which is the predicate the arg builder branches on.
        gpu0.setVncH264Port(7000);
        assertTrue(gpu0.hasVncH264PortOverride());
        assertEquals(7000, gpu0.effectiveVncH264Port());

        // Cleared by the editor the way an empty field clears the RFB port, and back to derived.
        gpu0.setVncH264Port(-1);
        assertFalse(gpu0.hasVncH264PortOverride());
        assertEquals(6000, gpu0.effectiveVncH264Port());
    }

    @Test
    public void thereIsNoSideChannelToTryWhenTheDerivationHasNoAnswer() {
        // Both of these are -1 rather than a number the console would spend a connect timeout
        // learning was wrong: a port the daemon has not assigned yet, and a derived one that would
        // not fit in a port number.
        var item = DataItem.newObject();
        var fb = VMScreenConfig.of(item, VMScreenConfig.ID_SIMPLEFB);
        fb.setEnabled(true);
        fb.setExporter(DisplayExporter.VNC);
        assertEquals(-1, fb.getVncPort());
        assertEquals(-1, fb.effectiveVncH264Port());

        fb.setVncPort(VMScreenConfig.MAX_PORT);
        assertEquals(-1, fb.effectiveVncH264Port());
        // One below the point where the derivation stops fitting still works.
        fb.setVncPort(VMScreenConfig.MAX_PORT - VMScreenConfig.H264_PORT_OFFSET);
        assertEquals(VMScreenConfig.MAX_PORT, fb.effectiveVncH264Port());
        // And an explicit port is not a derivation, so nothing about the RFB port bounds it.
        fb.setVncPort(-1);
        fb.setVncH264Port(9999);
        assertEquals(9999, fb.effectiveVncH264Port());
    }
}
