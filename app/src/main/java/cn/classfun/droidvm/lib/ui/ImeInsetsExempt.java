// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.ui;

/**
 * Marker for activities that manage the soft keyboard themselves (e.g. the
 * VNC and native display activities, where the IME must overlay the
 * full-screen surface rather than resize it).
 *
 * <p>{@link ImeInsetsApplier} skips any activity implementing this interface.
 */
public interface ImeInsetsExempt {
}
