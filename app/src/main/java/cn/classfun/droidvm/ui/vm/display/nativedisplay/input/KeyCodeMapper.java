package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import android.view.KeyEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps Android {@link KeyEvent} codes to Linux evdev scan codes (KEY_*).
 */
public final class KeyCodeMapper {
    // Linux evdev KEY_* codes (from linux/input-event-codes.h)
    public static final int KEY_ESC = 1;
    public static final int KEY_1 = 2, KEY_2 = 3, KEY_3 = 4, KEY_4 = 5, KEY_5 = 6;
    public static final int KEY_6 = 7, KEY_7 = 8, KEY_8 = 9, KEY_9 = 10, KEY_0 = 11;
    public static final int KEY_MINUS = 12, KEY_EQUAL = 13, KEY_BACKSPACE = 14, KEY_TAB = 15;
    public static final int KEY_Q = 16, KEY_W = 17, KEY_E = 18, KEY_R = 19, KEY_T = 20;
    public static final int KEY_Y = 21, KEY_U = 22, KEY_I = 23, KEY_O = 24, KEY_P = 25;
    public static final int KEY_LEFTBRACE = 26, KEY_RIGHTBRACE = 27, KEY_ENTER = 28;
    public static final int KEY_LEFTCTRL = 29;
    public static final int KEY_A = 30, KEY_S = 31, KEY_D = 32, KEY_F = 33, KEY_G = 34;
    public static final int KEY_H = 35, KEY_J = 36, KEY_K = 37, KEY_L = 38;
    public static final int KEY_SEMICOLON = 39, KEY_APOSTROPHE = 40, KEY_GRAVE = 41;
    public static final int KEY_LEFTSHIFT = 42, KEY_BACKSLASH = 43;
    public static final int KEY_Z = 44, KEY_X = 45, KEY_C = 46, KEY_V = 47, KEY_B = 48;
    public static final int KEY_N = 49, KEY_M = 50;
    public static final int KEY_COMMA = 51, KEY_DOT = 52, KEY_SLASH = 53, KEY_RIGHTSHIFT = 54;
    public static final int KEY_KPASTERISK = 55, KEY_LEFTALT = 56, KEY_SPACE = 57, KEY_CAPSLOCK = 58;
    public static final int KEY_F1 = 59, KEY_F2 = 60, KEY_F3 = 61, KEY_F4 = 62, KEY_F5 = 63;
    public static final int KEY_F6 = 64, KEY_F7 = 65, KEY_F8 = 66, KEY_F9 = 67, KEY_F10 = 68;
    public static final int KEY_NUMLOCK = 69, KEY_SCROLLLOCK = 70;
    public static final int KEY_KP7 = 71, KEY_KP8 = 72, KEY_KP9 = 73, KEY_KPMINUS = 74;
    public static final int KEY_KP4 = 75, KEY_KP5 = 76, KEY_KP6 = 77, KEY_KPPLUS = 78;
    public static final int KEY_KP1 = 79, KEY_KP2 = 80, KEY_KP3 = 81, KEY_KP0 = 82, KEY_KPDOT = 83;
    public static final int KEY_F11 = 87, KEY_F12 = 88, KEY_KPENTER = 96, KEY_RIGHTCTRL = 97;
    public static final int KEY_KPSLASH = 98, KEY_SYSRQ = 99, KEY_RIGHTALT = 100;
    public static final int KEY_HOME = 102, KEY_UP = 103, KEY_PAGEUP = 104, KEY_LEFT = 105;
    public static final int KEY_RIGHT = 106, KEY_END = 107, KEY_DOWN = 108, KEY_PAGEDOWN = 109;
    public static final int KEY_INSERT = 110, KEY_DELETE = 111, KEY_PAUSE = 119;
    public static final int KEY_KPEQUAL = 117, KEY_LEFTMETA = 125, KEY_RIGHTMETA = 126;

    private static final Map<Integer, Integer> KEYCODE_MAP = new HashMap<>();

    static {
        // Letters
        put(KeyEvent.KEYCODE_A, KEY_A); put(KeyEvent.KEYCODE_B, KEY_B);
        put(KeyEvent.KEYCODE_C, KEY_C); put(KeyEvent.KEYCODE_D, KEY_D);
        put(KeyEvent.KEYCODE_E, KEY_E); put(KeyEvent.KEYCODE_F, KEY_F);
        put(KeyEvent.KEYCODE_G, KEY_G); put(KeyEvent.KEYCODE_H, KEY_H);
        put(KeyEvent.KEYCODE_I, KEY_I); put(KeyEvent.KEYCODE_J, KEY_J);
        put(KeyEvent.KEYCODE_K, KEY_K); put(KeyEvent.KEYCODE_L, KEY_L);
        put(KeyEvent.KEYCODE_M, KEY_M); put(KeyEvent.KEYCODE_N, KEY_N);
        put(KeyEvent.KEYCODE_O, KEY_O); put(KeyEvent.KEYCODE_P, KEY_P);
        put(KeyEvent.KEYCODE_Q, KEY_Q); put(KeyEvent.KEYCODE_R, KEY_R);
        put(KeyEvent.KEYCODE_S, KEY_S); put(KeyEvent.KEYCODE_T, KEY_T);
        put(KeyEvent.KEYCODE_U, KEY_U); put(KeyEvent.KEYCODE_V, KEY_V);
        put(KeyEvent.KEYCODE_W, KEY_W); put(KeyEvent.KEYCODE_X, KEY_X);
        put(KeyEvent.KEYCODE_Y, KEY_Y); put(KeyEvent.KEYCODE_Z, KEY_Z);
        // Numbers
        put(KeyEvent.KEYCODE_0, KEY_0); put(KeyEvent.KEYCODE_1, KEY_1);
        put(KeyEvent.KEYCODE_2, KEY_2); put(KeyEvent.KEYCODE_3, KEY_3);
        put(KeyEvent.KEYCODE_4, KEY_4); put(KeyEvent.KEYCODE_5, KEY_5);
        put(KeyEvent.KEYCODE_6, KEY_6); put(KeyEvent.KEYCODE_7, KEY_7);
        put(KeyEvent.KEYCODE_8, KEY_8); put(KeyEvent.KEYCODE_9, KEY_9);
        // Function keys
        put(KeyEvent.KEYCODE_F1, KEY_F1); put(KeyEvent.KEYCODE_F2, KEY_F2);
        put(KeyEvent.KEYCODE_F3, KEY_F3); put(KeyEvent.KEYCODE_F4, KEY_F4);
        put(KeyEvent.KEYCODE_F5, KEY_F5); put(KeyEvent.KEYCODE_F6, KEY_F6);
        put(KeyEvent.KEYCODE_F7, KEY_F7); put(KeyEvent.KEYCODE_F8, KEY_F8);
        put(KeyEvent.KEYCODE_F9, KEY_F9); put(KeyEvent.KEYCODE_F10, KEY_F10);
        put(KeyEvent.KEYCODE_F11, KEY_F11); put(KeyEvent.KEYCODE_F12, KEY_F12);
        // Modifiers
        put(KeyEvent.KEYCODE_SHIFT_LEFT, KEY_LEFTSHIFT);
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, KEY_RIGHTSHIFT);
        put(KeyEvent.KEYCODE_CTRL_LEFT, KEY_LEFTCTRL);
        put(KeyEvent.KEYCODE_CTRL_RIGHT, KEY_RIGHTCTRL);
        put(KeyEvent.KEYCODE_ALT_LEFT, KEY_LEFTALT);
        put(KeyEvent.KEYCODE_ALT_RIGHT, KEY_RIGHTALT);
        put(KeyEvent.KEYCODE_META_LEFT, KEY_LEFTMETA);
        put(KeyEvent.KEYCODE_META_RIGHT, KEY_RIGHTMETA);
        // Special keys
        put(KeyEvent.KEYCODE_ENTER, KEY_ENTER); put(KeyEvent.KEYCODE_TAB, KEY_TAB);
        put(KeyEvent.KEYCODE_SPACE, KEY_SPACE); put(KeyEvent.KEYCODE_DEL, KEY_BACKSPACE);
        put(KeyEvent.KEYCODE_ESCAPE, KEY_ESC); put(KeyEvent.KEYCODE_FORWARD_DEL, KEY_DELETE);
        // Navigation
        put(KeyEvent.KEYCODE_DPAD_UP, KEY_UP); put(KeyEvent.KEYCODE_DPAD_DOWN, KEY_DOWN);
        put(KeyEvent.KEYCODE_DPAD_LEFT, KEY_LEFT); put(KeyEvent.KEYCODE_DPAD_RIGHT, KEY_RIGHT);
        put(KeyEvent.KEYCODE_PAGE_UP, KEY_PAGEUP); put(KeyEvent.KEYCODE_PAGE_DOWN, KEY_PAGEDOWN);
        put(KeyEvent.KEYCODE_MOVE_HOME, KEY_HOME); put(KeyEvent.KEYCODE_MOVE_END, KEY_END);
        put(KeyEvent.KEYCODE_INSERT, KEY_INSERT);
        // Punctuation
        put(KeyEvent.KEYCODE_MINUS, KEY_MINUS); put(KeyEvent.KEYCODE_EQUALS, KEY_EQUAL);
        put(KeyEvent.KEYCODE_LEFT_BRACKET, KEY_LEFTBRACE);
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, KEY_RIGHTBRACE);
        put(KeyEvent.KEYCODE_BACKSLASH, KEY_BACKSLASH);
        put(KeyEvent.KEYCODE_SEMICOLON, KEY_SEMICOLON);
        put(KeyEvent.KEYCODE_APOSTROPHE, KEY_APOSTROPHE);
        put(KeyEvent.KEYCODE_GRAVE, KEY_GRAVE); put(KeyEvent.KEYCODE_COMMA, KEY_COMMA);
        put(KeyEvent.KEYCODE_PERIOD, KEY_DOT); put(KeyEvent.KEYCODE_SLASH, KEY_SLASH);
        // Numpad
        put(KeyEvent.KEYCODE_NUMPAD_0, KEY_KP0); put(KeyEvent.KEYCODE_NUMPAD_1, KEY_KP1);
        put(KeyEvent.KEYCODE_NUMPAD_2, KEY_KP2); put(KeyEvent.KEYCODE_NUMPAD_3, KEY_KP3);
        put(KeyEvent.KEYCODE_NUMPAD_4, KEY_KP4); put(KeyEvent.KEYCODE_NUMPAD_5, KEY_KP5);
        put(KeyEvent.KEYCODE_NUMPAD_6, KEY_KP6); put(KeyEvent.KEYCODE_NUMPAD_7, KEY_KP7);
        put(KeyEvent.KEYCODE_NUMPAD_8, KEY_KP8); put(KeyEvent.KEYCODE_NUMPAD_9, KEY_KP9);
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, KEY_KPSLASH);
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, KEY_KPASTERISK);
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, KEY_KPMINUS);
        put(KeyEvent.KEYCODE_NUMPAD_ADD, KEY_KPPLUS);
        put(KeyEvent.KEYCODE_NUMPAD_DOT, KEY_KPDOT);
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, KEY_KPENTER);
        put(KeyEvent.KEYCODE_NUMPAD_EQUALS, KEY_KPEQUAL);
        put(KeyEvent.KEYCODE_NUM_LOCK, KEY_NUMLOCK);
        // Other
        put(KeyEvent.KEYCODE_CAPS_LOCK, KEY_CAPSLOCK);
        put(KeyEvent.KEYCODE_SCROLL_LOCK, KEY_SCROLLLOCK);
        put(KeyEvent.KEYCODE_BREAK, KEY_PAUSE);
        put(KeyEvent.KEYCODE_SYSRQ, KEY_SYSRQ);
    }

    private KeyCodeMapper() {
    }

    private static void put(int androidKey, int evdev) {
        KEYCODE_MAP.put(androidKey, evdev);
    }

    /** Linux evdev KEY_* for an Android key code, or -1 if unmapped. */
    public static int androidToEvdev(int keyCode) {
        Integer v = KEYCODE_MAP.get(keyCode);
        return v == null ? -1 : v;
    }

    /**
     * Some Android keys need Shift synthesized (e.g. {@code @} = Shift+2). Returns the base Android
     * key code to hold with Shift, or -1 if no synthesis is needed.
     */
    public static int shiftSynthBase(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_AT:
                return KeyEvent.KEYCODE_2;     // @
            case KeyEvent.KEYCODE_POUND:
                return KeyEvent.KEYCODE_3;     // #
            case KeyEvent.KEYCODE_STAR:
                return KeyEvent.KEYCODE_8;     // *
            case KeyEvent.KEYCODE_PLUS:
                return KeyEvent.KEYCODE_EQUALS; // +
            default:
                return -1;
        }
    }
}
