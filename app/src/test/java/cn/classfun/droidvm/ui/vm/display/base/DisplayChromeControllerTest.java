package cn.classfun.droidvm.ui.vm.display.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DisplayChromeControllerTest {
    /** Records the last chrome set the controller pushed. */
    private static final class Cap implements DisplayChromeController.Host {
        boolean fullscreen, extra, fnx;
        KeyboardMode mode;
        int applyCount;

        @Override
        public void applyChrome(
            boolean fullscreen, KeyboardMode mode, boolean extraVisible, boolean fnxVisible) {
            this.fullscreen = fullscreen;
            this.mode = mode;
            this.extra = extraVisible;
            this.fnx = fnxVisible;
            applyCount++;
        }
    }

    /** Records what the persistence hook was told, and how often. */
    private static final class Listener implements DisplayChromeController.StateListener {
        KeyboardMode mode;
        Boolean extra, fnx;
        int calls;

        @Override
        public void onUserStateChanged(KeyboardMode mode, boolean extraVisible, boolean fnxVisible) {
            this.mode = mode;
            this.extra = extraVisible;
            this.fnx = fnxVisible;
            calls++;
        }
    }

    private final Cap cap = new Cap();

    private DisplayChromeController laptop(boolean extra, boolean fnx) {
        return new DisplayChromeController(KeyboardMode.LAPTOP, extra, fnx, cap);
    }

    @Test
    public void initialStatePushesEverythingAtOnce() {
        laptop(true, true).applyInitial();
        assertFalse(cap.fullscreen);
        assertEquals(KeyboardMode.LAPTOP, cap.mode);
        assertTrue(cap.extra);
        assertTrue(cap.fnx);
    }

    @Test
    public void fullscreenHidesKeyboardAndExitRestoresIt() {
        var chrome = laptop(true, true);
        chrome.applyInitial();

        chrome.toggleFullscreen();
        assertTrue(cap.fullscreen);
        // Fullscreen hides the whole keyboard, not just the bars.
        assertEquals(KeyboardMode.NONE, cap.mode);
        assertFalse(cap.extra);
        assertFalse(cap.fnx);

        chrome.toggleFullscreen();
        assertFalse(cap.fullscreen);
        assertEquals(KeyboardMode.LAPTOP, cap.mode);
        assertTrue(cap.extra); // restored to the pre-fullscreen state
        assertTrue(cap.fnx);
    }

    @Test
    public void zonesTurnedOffBeforeFullscreenStayOffAfterExit() {
        var chrome = laptop(true, true);
        chrome.toggleExtraZone();
        chrome.toggleFnxZone();
        chrome.toggleFullscreen();
        chrome.toggleFullscreen();
        assertFalse(cap.extra);
        assertFalse(cap.fnx);
    }

    /**
     * Fullscreen-scoped changes are scoped: the remembered pre-fullscreen state
     * wins on exit, whatever was toggled in between.
     */
    @Test
    public void zoneChangesDuringFullscreenDoNotSurviveExit() {
        var chrome = laptop(true, true);
        chrome.toggleFullscreen();
        chrome.toggleExtraZone();
        chrome.toggleFnxZone();
        chrome.toggleFullscreen();
        assertTrue(cap.extra);
        assertTrue(cap.fnx);
    }

    /**
     * The Extra zone is the whole point of the system-IME mode -- it carries the
     * keys an IME cannot send -- so it stays up there even when its flag is off.
     */
    @Test
    public void systemModeAlwaysShowsExtraZone() {
        var chrome = new DisplayChromeController(KeyboardMode.SYSTEM, false, false, cap);
        chrome.applyInitial();
        assertTrue(cap.extra);

        chrome.toggleExtraZone();
        assertTrue(cap.extra); // cannot be toggled away in this mode
    }

    /** The laptop Main block already carries most of those keys, so Extra is optional. */
    @Test
    public void laptopModeCanToggleExtraZoneOff() {
        var chrome = laptop(true, true);
        chrome.applyInitial();
        assertTrue(cap.extra);
        chrome.toggleExtraZone();
        assertFalse(cap.extra);
        chrome.toggleExtraZone();
        assertTrue(cap.extra);
    }

    /** Hiding the keyboard is not the same as turning its zones off. */
    @Test
    public void noneModeHidesZonesWithoutForgettingThem() {
        var chrome = laptop(true, true);
        chrome.setKeyboardMode(KeyboardMode.NONE);
        assertFalse(cap.extra);
        assertFalse(cap.fnx);
        assertTrue(chrome.isExtraVisible()); // flag kept
        assertTrue(chrome.isFnxVisible());

        chrome.setKeyboardMode(KeyboardMode.LAPTOP);
        assertTrue(cap.extra);
        assertTrue(cap.fnx);
    }

    @Test
    public void fnxTogglesIndependentlyOfExtra() {
        var chrome = laptop(true, true);
        chrome.toggleFnxZone();
        assertFalse(cap.fnx);
        assertTrue(cap.extra);
    }

    @Test
    public void redundantSetFullscreenDoesNotReapply() {
        var chrome = laptop(true, true);
        chrome.setFullscreen(true);
        int count = cap.applyCount;
        chrome.setFullscreen(true);
        assertEquals(count, cap.applyCount);
    }

    @Test
    public void redundantSetKeyboardModeDoesNotReapply() {
        var chrome = laptop(true, true);
        chrome.setKeyboardMode(KeyboardMode.SYSTEM);
        int count = cap.applyCount;
        chrome.setKeyboardMode(KeyboardMode.SYSTEM);
        assertEquals(count, cap.applyCount);
    }

    @Test
    public void stateListenerSeesUserActionsButNotFullscreen() {
        var chrome = laptop(true, true);
        var listener = new Listener();
        chrome.setStateListener(listener);

        chrome.toggleExtraZone();
        assertEquals(1, listener.calls);
        assertEquals(KeyboardMode.LAPTOP, listener.mode);
        assertEquals(Boolean.FALSE, listener.extra);

        // Entering fullscreen is not a persistable user preference, and neither are
        // the zone toggles made while inside it.
        chrome.toggleFullscreen();
        chrome.toggleExtraZone();
        chrome.toggleFnxZone();
        assertEquals(1, listener.calls);
    }

    @Test
    public void listenerNotCalledBeforeItIsSet() {
        var chrome = laptop(true, true);
        var listener = new Listener();
        chrome.toggleExtraZone();
        chrome.setStateListener(listener);
        assertEquals(0, listener.calls);
        assertNull(listener.mode);
    }

    @Test
    public void gettersTrackTheAppliedState() {
        var chrome = laptop(true, false);
        assertFalse(chrome.isFullscreen());
        assertEquals(KeyboardMode.LAPTOP, chrome.getKeyboardMode());
        assertTrue(chrome.isExtraVisible());
        assertFalse(chrome.isFnxVisible());

        chrome.toggleFullscreen();
        assertTrue(chrome.isFullscreen());
        assertEquals(KeyboardMode.NONE, chrome.getKeyboardMode());
    }
}
