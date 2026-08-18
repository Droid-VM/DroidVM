package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Geometry of the guest hardware-cursor overlay. Casting makes this worth pinning down: the phone
 * and the external display have unrelated areas and viewport transforms, so the same guest position
 * must land correctly under both.
 */
public class CursorOverlayPlacerTest {
    private static final float EPS = 1e-3f;

    /** Fitted 1280x720 guest in a 1280x720 area: the transform is the identity. */
    @Test
    public void fittedOneToOne_mapsGuestPositionDirectly() {
        var p = CursorOverlayPlacer.compute(100, 50, 1280, 720,
            1280, 720, 1f, 0, 0, 1280, 720);
        assertNotNull(p);
        assertEquals(100f, p.translationX, EPS);
        assertEquals(50f, p.translationY, EPS);
        assertEquals(1f, p.scale, EPS);
    }

    /**
     * Guest centre stays at the area centre when letterboxed. 1280x720 fitted into a 1280x1000 area
     * leaves 140px bars top and bottom, so the centre is at y=500, not y=360.
     */
    @Test
    public void letterboxed_guestCentreLandsAtAreaCentre() {
        var p = CursorOverlayPlacer.compute(640, 360, 1280, 720,
            1280, 720, 1f, 0, 0, 1280, 1000);
        assertNotNull(p);
        assertEquals(640f, p.translationX, EPS);
        assertEquals(500f, p.translationY, EPS);
    }

    /**
     * The cursor scales with the image. A 1280-wide guest fitted to a 640-wide base is at 0.5 px per
     * guest px, so the 64x64 plane must be halved or the pointer is twice the size it should be
     * relative to what it points at.
     */
    @Test
    public void downscaledFit_scalesCursorToMatch() {
        var p = CursorOverlayPlacer.compute(0, 0, 1280, 720,
            640, 360, 1f, 0, 0, 640, 360);
        assertNotNull(p);
        assertEquals(0.5f, p.scale, EPS);
        assertEquals(0f, p.translationX, EPS);
        assertEquals(0f, p.translationY, EPS);
    }

    /** Zoom multiplies the fit scale and magnifies displacement from the centre about that centre. */
    @Test
    public void zoomed_scalesAboutAreaCentre() {
        // Fitted base 640x360 in a 640x360 area, zoomed 2x. The guest's right edge (x=1280) sits
        // 320 base px right of centre, so at 2x it is 640 px right of centre: off-screen right.
        var p = CursorOverlayPlacer.compute(1280, 360, 1280, 720,
            640, 360, 2f, 0, 0, 640, 360);
        assertNotNull(p);
        assertEquals(320f + 640f, p.translationX, EPS);
        assertEquals(180f, p.translationY, EPS);
        assertEquals(1f, p.scale, EPS);
    }

    /** Pan offset displaces the overlay by exactly the same amount as the scanout. */
    @Test
    public void panOffset_shiftsOverlayByOffset() {
        var base = CursorOverlayPlacer.compute(640, 360, 1280, 720,
            1280, 720, 2f, 0, 0, 1280, 720);
        var panned = CursorOverlayPlacer.compute(640, 360, 1280, 720,
            1280, 720, 2f, -100, 40, 1280, 720);
        assertNotNull(base);
        assertNotNull(panned);
        assertEquals(base.translationX - 100f, panned.translationX, EPS);
        assertEquals(base.translationY + 40f, panned.translationY, EPS);
    }

    /**
     * A guest resolution change re-maps existing positions. The same guest coordinate is a different
     * fraction of a 1920x1080 framebuffer than of a 1280x720 one.
     */
    @Test
    public void guestResize_remapsSamePosition() {
        var small = CursorOverlayPlacer.compute(640, 360, 1280, 720,
            1280, 720, 1f, 0, 0, 1280, 720);
        var large = CursorOverlayPlacer.compute(640, 360, 1920, 1080,
            1280, 720, 1f, 0, 0, 1280, 720);
        assertNotNull(small);
        assertNotNull(large);
        assertEquals(640f, small.translationX, EPS);
        // 640/1920 of a 1280-wide base = 426.67, i.e. a third across rather than halfway.
        assertEquals(1280f / 3f, large.translationX, EPS);
    }

    /** The hidden-pointer sentinel (crosvm's u32::MAX, arriving as -1) produces no placement. */
    @Test
    public void hiddenPointerSentinel_yieldsNoPlacement() {
        assertNull(CursorOverlayPlacer.compute(-1, -1, 1280, 720,
            1280, 720, 1f, 0, 0, 1280, 720));
    }

    /** Degenerate inputs are rejected rather than emitting NaN/Infinity into view properties. */
    @Test
    public void degenerateInputs_yieldNoPlacement() {
        // No guest size yet.
        assertNull(CursorOverlayPlacer.compute(10, 10, 0, 0,
            1280, 720, 1f, 0, 0, 1280, 720));
        // No viewport emitted yet.
        assertNull(CursorOverlayPlacer.compute(10, 10, 1280, 720,
            0, 0, 1f, 0, 0, 1280, 720));
        // Area not laid out yet (Presentation before its first layout pass).
        assertNull(CursorOverlayPlacer.compute(10, 10, 1280, 720,
            1280, 720, 1f, 0, 0, 0, 0));
    }
}
