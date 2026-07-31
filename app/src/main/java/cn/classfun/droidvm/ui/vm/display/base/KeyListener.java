// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

public interface KeyListener {
    /**
     * A non-modifier key went down ({@code down=true}) or up. The guest sees the real hold, so
     * auto-repeat and hold semantics (e.g. WASD movement) are the guest's own; several keys may
     * be held at once.
     */
    void onKey(int androidKeyCode, boolean down);

    void onModifierClick(int androidKeyCode);

    void onModifierLongClick(int androidKeyCode);
}
