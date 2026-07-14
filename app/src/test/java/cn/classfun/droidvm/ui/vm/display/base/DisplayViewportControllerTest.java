package cn.classfun.droidvm.ui.vm.display.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DisplayViewportControllerTest {
    private static final float EPS = 1e-3f;

    /** Captures every emission so tests can assert both the latest geometry and emission counts. */
    private static final class Cap implements DisplayViewportController.Listener {
        int baseW, baseH;
        float viewScale, offsetX, offsetY;
        int viewportCount;
        final List<int[]> guestResizes = new ArrayList<>();

        @Override
        public void onViewportChanged(int baseW, int baseH, float viewScale,
                                      float offsetX, float offsetY) {
            this.baseW = baseW;
            this.baseH = baseH;
            this.viewScale = viewScale;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            viewportCount++;
        }

        @Override
        public void onGuestResizeWanted(int areaW, int areaH) {
            guestResizes.add(new int[]{areaW, areaH});
        }

        /** On-screen content width: base letterbox size x view scale. */
        float onScreenW() {
            return baseW * viewScale;
        }
    }

    private final Cap cap = new Cap();
    private final DisplayViewportController vp = new DisplayViewportController(100, cap);

    private void setup1000x500Content800x800Area() {
        vp.setContentSize(1000, 500);
        vp.setArea(800, 800);
    }

    @Test
    public void fitsOnFirstLayout() {
        setup1000x500Content800x800Area();
        // fit = min(800/1000, 800/500) = 0.8 -> letterbox 800x400, no zoom, centered
        assertEquals(800, cap.baseW);
        assertEquals(400, cap.baseH);
        assertEquals(1f, cap.viewScale, EPS);
        assertEquals(0f, cap.offsetX, EPS);
        assertEquals(0f, cap.offsetY, EPS);
        assertTrue(vp.isFitted());
    }

    @Test
    public void noEmissionBeforeBothSizesKnown() {
        vp.setArea(800, 800);
        assertEquals(0, cap.viewportCount);
        vp.setContentSize(1000, 500);
        assertEquals(1, cap.viewportCount);
    }

    @Test
    public void refitsWhenFittedAndAreaChanges() {
        setup1000x500Content800x800Area();
        vp.setArea(400, 800); // fit = 0.4
        assertEquals(400, cap.baseW);
        assertEquals(200, cap.baseH);
        assertEquals(1f, cap.viewScale, EPS);
        assertTrue(vp.isFitted());
    }

    @Test
    public void sameAreaDoesNotReemit() {
        setup1000x500Content800x800Area();
        int count = cap.viewportCount;
        vp.setArea(800, 800);
        assertEquals(count, cap.viewportCount);
    }

    @Test
    public void zoomedAreaChangeKeepsOnScreenSizeAndOffset() {
        setup1000x500Content800x800Area();
        vp.onZoomPan(2f, 50f, 0f); // scale 0.8 -> 1.6, pan +50 x
        assertEquals(2f, cap.viewScale, EPS);
        assertEquals(50f, cap.offsetX, EPS);
        // content 500*1.6 = 800 = areaH -> no vertical overflow, y locked to 0
        assertEquals(0f, cap.offsetY, EPS);
        float sizeBefore = cap.onScreenW(); // 1600

        vp.setArea(800, 600); // shrink: zoomed -> absolute size and offset preserved
        assertEquals(sizeBefore, cap.onScreenW(), EPS);
        assertEquals(50f, cap.offsetX, EPS);
        assertFalse(vp.isFitted());
    }

    @Test
    public void snapsBackToFitWhenAreaGrowsPastZoom() {
        vp.setContentSize(1000, 500);
        vp.setArea(500, 500); // fit = 0.5
        vp.onZoomPan(1.5f, 0f, 0f); // scale 0.75
        vp.setArea(1000, 1000); // fit = 1.0 > 0.75 -> snap to fit
        assertEquals(1f, cap.viewScale, EPS);
        assertEquals(0f, cap.offsetX, EPS);
        assertEquals(0f, cap.offsetY, EPS);
        assertTrue(vp.isFitted());
    }

    @Test
    public void panClampsToOverflowAndLocksLetterboxedAxis() {
        setup1000x500Content800x800Area();
        vp.onZoomPan(2f, 10000f, 10000f); // scale 1.6
        // x overflow: 1000*1.6 - 800 = 800 -> half 400; y overflow: 500*1.6 - 800 = 0 -> locked
        assertEquals(400f, cap.offsetX, EPS);
        assertEquals(0f, cap.offsetY, EPS);
    }

    @Test
    public void maxZoomIsClamped() {
        setup1000x500Content800x800Area();
        vp.onZoomPan(100f, 0f, 0f);
        assertEquals(DisplayViewportController.MAX_ZOOM, cap.viewScale, EPS);
    }

    @Test
    public void pinchOutSnapsBackToFit() {
        setup1000x500Content800x800Area();
        vp.onZoomPan(1.2f, 30f, 0f);
        vp.onZoomPan(1f / 1.25f, 0f, 0f); // 0.96x of fit -> clamped to fit -> snap
        assertEquals(1f, cap.viewScale, EPS);
        assertEquals(0f, cap.offsetX, EPS);
        assertTrue(vp.isFitted());
    }

    @Test
    public void degenerateAreaFreezesInsteadOfRelayout() {
        setup1000x500Content800x800Area();
        vp.onZoomPan(2f, 50f, 0f);
        int count = cap.viewportCount;

        vp.setArea(800, 50); // below minAreaPx=100 -> freeze
        assertTrue(vp.isFrozen());
        assertEquals(count, cap.viewportCount); // nothing emitted, geometry held

        vp.onZoomPan(2f, 0f, 0f); // gestures ignored while frozen
        assertEquals(count, cap.viewportCount);

        vp.setArea(800, 600); // recover: zoomed rules apply against last good state
        assertFalse(vp.isFrozen());
        assertEquals(2f, cap.viewScale, EPS);
        assertEquals(50f, cap.offsetX, EPS);
    }

    @Test
    public void zeroOrNegativeAreaDoesNotCrashOrEmit() {
        setup1000x500Content800x800Area();
        int count = cap.viewportCount;
        vp.setArea(0, 0);
        vp.setArea(-100, 600);
        assertTrue(vp.isFrozen());
        assertEquals(count, cap.viewportCount);
    }

    @Test
    public void recoveringToTheSameAreaUnfreezesWithoutReemit() {
        setup1000x500Content800x800Area();
        int count = cap.viewportCount;
        vp.setArea(800, 20);
        vp.setArea(800, 800); // same as last good -> nothing changed
        assertFalse(vp.isFrozen());
        assertEquals(count, cap.viewportCount);
    }

    @Test
    public void autoResizeRefitsAndRequestsGuestResize() {
        setup1000x500Content800x800Area();
        vp.setAutoResize(true);
        assertEquals(1, cap.guestResizes.size());
        assertEquals(800, cap.guestResizes.get(0)[0]);
        assertEquals(800, cap.guestResizes.get(0)[1]);

        vp.setArea(600, 400);
        assertEquals(2, cap.guestResizes.size());
        assertEquals(600, cap.guestResizes.get(1)[0]);
        assertEquals(400, cap.guestResizes.get(1)[1]);
        assertEquals(1f, cap.viewScale, EPS); // always fitted

        vp.onZoomPan(2f, 10f, 10f); // zoom disabled in auto-resize
        assertEquals(1f, cap.viewScale, EPS);
        assertEquals(0f, cap.offsetX, EPS);

        // guest applied the resize -> content size ack re-fits 1:1
        vp.setContentSize(600, 400);
        assertEquals(600, cap.baseW);
        assertEquals(400, cap.baseH);
        assertEquals(1f, cap.viewScale, EPS);
    }

    @Test
    public void autoResizeFrozenAreaRequestsNothing() {
        setup1000x500Content800x800Area();
        vp.setAutoResize(true);
        int requests = cap.guestResizes.size();
        vp.setArea(800, 30); // degenerate -> frozen, no guest resize spam
        assertEquals(requests, cap.guestResizes.size());
    }

    @Test
    public void contentSizeChangeRefits() {
        setup1000x500Content800x800Area();
        vp.onZoomPan(2f, 50f, 0f);
        vp.setContentSize(1920, 1080); // guest resolution change -> back to fit
        assertEquals(1f, cap.viewScale, EPS);
        assertEquals(0f, cap.offsetX, EPS);
        // fit = min(800/1920, 800/1080) = 0.41667 -> base 800x450
        assertEquals(800, cap.baseW);
        assertEquals(450, cap.baseH);
    }

    @Test
    public void resetToFitFromZoomed() {
        setup1000x500Content800x800Area();
        vp.onZoomPan(3f, 100f, 0f);
        vp.resetToFit();
        assertEquals(1f, cap.viewScale, EPS);
        assertEquals(0f, cap.offsetX, EPS);
        assertTrue(vp.isFitted());
    }
}
