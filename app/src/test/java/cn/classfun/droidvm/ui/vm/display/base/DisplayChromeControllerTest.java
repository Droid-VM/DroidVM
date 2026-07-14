package cn.classfun.droidvm.ui.vm.display.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DisplayChromeControllerTest {
    private static final class Cap implements DisplayChromeController.Host {
        boolean fullscreen, extraKeys;
        int applyCount;

        @Override
        public void applyChrome(boolean fullscreen, boolean extraKeysVisible) {
            this.fullscreen = fullscreen;
            this.extraKeys = extraKeysVisible;
            applyCount++;
        }
    }

    private final Cap cap = new Cap();
    private final DisplayChromeController chrome = new DisplayChromeController(true, cap);

    @Test
    public void fullscreenHidesExtraKeysAndExitRestoresThem() {
        chrome.applyInitial();
        assertTrue(cap.extraKeys);

        chrome.toggleFullscreen();
        assertTrue(cap.fullscreen);
        assertFalse(cap.extraKeys);

        chrome.toggleFullscreen();
        assertFalse(cap.fullscreen);
        assertTrue(cap.extraKeys); // restored to the pre-fullscreen state
    }

    @Test
    public void extraKeysHiddenBeforeFullscreenStayHiddenAfterExit() {
        chrome.toggleExtraKeys(); // hide
        chrome.toggleFullscreen();
        chrome.toggleFullscreen();
        assertFalse(cap.extraKeys);
    }

    @Test
    public void extraKeysCanBeReopenedFromMenuWhileFullscreen() {
        chrome.toggleFullscreen();
        assertFalse(cap.extraKeys);
        chrome.toggleExtraKeys(); // menu re-opens the panel on top of fullscreen
        assertTrue(cap.fullscreen);
        assertTrue(cap.extraKeys);
    }

    @Test
    public void redundantSetFullscreenDoesNotReapply() {
        chrome.setFullscreen(true);
        int count = cap.applyCount;
        chrome.setFullscreen(true);
        assertEquals(count, cap.applyCount);
    }

    @Test
    public void reopenedDuringFullscreenSurvivesExit() {
        // Entering remembered "visible"; user closes the panel mid-fullscreen; exiting restores
        // the remembered pre-fullscreen state by design (fullscreen-scoped changes are scoped).
        chrome.toggleFullscreen();
        chrome.toggleExtraKeys(); // open
        chrome.toggleExtraKeys(); // close again
        chrome.toggleFullscreen();
        assertTrue(cap.extraKeys); // pre-fullscreen state wins on exit
    }
}
