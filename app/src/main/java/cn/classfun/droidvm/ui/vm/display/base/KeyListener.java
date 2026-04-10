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
