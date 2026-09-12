package cn.classfun.droidvm.display;

/**
 * The console's copy of what the grabbed physical keyboard is doing. A grab sends its keys
 * straight from the daemon into the guest's keyboard socket and never through the app, which is
 * what keeps the path short -- but it also means the UI has no idea a key was pressed. This is how
 * it finds out, and it exists for the on-screen keyboard: in the laptop-keyboard mode the key the
 * user pressed on the real keyboard lights up on the drawn one.
 *
 * Oneway, and always called after the keys have gone to the guest, so nothing the console does
 * with them can slow typing down. A console registers it only while it has something to show.
 */
oneway interface IPhysicalKeyEcho {
    /**
     * One read's worth of keys, in order. [codes] are Linux KEY_* scan codes and [values] the
     * matching 1 (down) or 0 (up); auto-repeat is not sent, the same as to the guest.
     */
    void onKeys(in int[] codes, in int[] values);

    /**
     * The guest's keyboard lamps, as the guest's own driver reports them -- authoritative, unlike
     * counting key presses, because software inside the guest can toggle them on its own.
     *
     * Both are bitmasks over the Linux LED_* codes (1 << LED_NUML, 1 << LED_CAPSL,
     * 1 << LED_SCROLLL). [known] says which lamps the guest has reported at all: until it changes
     * one, its state is genuinely unknown here, because virtio-input has no way to ask.
     */
    void onLeds(int known, int on);
}
