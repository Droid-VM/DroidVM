package cn.classfun.droidvm.lib.ui;

/**
 * Marker for activities that manage the soft keyboard themselves (e.g. the
 * terminal/serial console and VNC display, where the IME must overlay the
 * full-screen surface rather than resize it).
 *
 * <p>{@link ImeInsetsApplier} skips any activity implementing this interface.
 */
public interface ImeInsetsExempt {
}
