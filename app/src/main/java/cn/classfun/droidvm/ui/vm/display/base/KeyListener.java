// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

public interface KeyListener {
    @SuppressWarnings("unused")
    void onKeyRepeat(int androidKeyCode);

    @SuppressWarnings("unused")
    void onCharRepeat(char ch);

    @SuppressWarnings("unused")
    void onCapsToggle(boolean active);

    @SuppressWarnings("unused")
    void onModifierClick(int androidKeyCode);

    @SuppressWarnings("unused")
    void onModifierLongClick(int androidKeyCode);
}
