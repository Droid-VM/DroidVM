package cn.classfun.droidvm.ui.vm.display.base;

import androidx.annotation.NonNull;

/**
 * The seam between the shared display control bar (system keyboard, extra keys, input-mode selector,
 * fullscreen, rotate) and a concrete display backend. Both backends implement this so the control
 * bar is backend-agnostic:
 *
 * <ul>
 *   <li>VNC — maps to {@code VncClient}: Android key codes to X11 keysyms, pointer to RFB events.</li>
 *   <li>Native — maps to {@code InputForwarder}/{@code EvdevEncoder}: key codes to Linux evdev
 *       scan codes, pointer to the mode-appropriate virtio-input device.</li>
 * </ul>
 *
 * Only event delivery lives here. Fullscreen and screen rotation are window-level concerns the
 * control bar drives on the host Activity directly, not through the backend.
 */
public interface DisplayInputBackend {
    /** Sends a key by Android key code; the backend translates to its wire representation. */
    void sendKey(int androidKeyCode, boolean down);

    /**
     * Sends a printable character, synthesizing whatever the guest needs for it (e.g. Shift-wrapping
     * uppercase and shifted symbols on the evdev backend). Used by the soft keyboard's commit path.
     */
    void sendChar(char c);

    /**
     * The pointer input mode changed. The backend adjusts how it turns subsequent on-screen touches
     * into guest input (multi-touch, relative mouse, or absolute single-pointer tablet). Backends
     * that route to distinct guest input devices switch the target device here.
     */
    void setInputMode(@NonNull InputMode mode);
}
