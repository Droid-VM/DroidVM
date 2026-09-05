// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import static android.view.HapticFeedbackConstants.KEYBOARD_TAP;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hold-to-press keys with glide, for one keyboard widget: finger down on a key sends its key-down;
 * sliding onto another registered key while held sends that key's down and the previous key's up
 * (down-before-up, so a W-to-A roll never has a gap); release - or sliding off every key - sends
 * key-up. The guest sees real holds, so auto-repeat and hold semantics are its own, and several
 * pointers can hold several keys at once (each pointer's stream stays with its origin view; this
 * group just retargets which key that pointer currently presses).
 *
 * Sticky modifiers are deliberately NOT registered here: gliding across one must not toggle it.
 */
public final class HoldKeyGroup {
    public interface Sink {
        void onKey(int androidKeyCode, boolean down);
    }

    private static final class Entry {
        final View view;
        final int keyCode;

        Entry(View view, int keyCode) {
            this.view = view;
            this.keyCode = keyCode;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    @NonNull
    private final Sink sink;

    public HoldKeyGroup(@NonNull Sink sink) {
        this.sink = sink;
    }

    @SuppressLint("ClickableViewAccessibility")
    public void register(@NonNull View view, int keyCode) {
        var entry = new Entry(view, keyCode);
        entries.add(entry);
        view.setOnTouchListener(new PointerTracker(entry));
    }

    private void press(@NonNull Entry e) {
        e.view.setPressed(true);
        e.view.performHapticFeedback(KEYBOARD_TAP);
        sink.onKey(e.keyCode, true);
    }

    private void release(@NonNull Entry e) {
        e.view.setPressed(false);
        sink.onKey(e.keyCode, false);
    }

    /** Tracks the single pointer whose ACTION_DOWN landed on one origin key. */
    private final class PointerTracker implements View.OnTouchListener {
        private final Entry origin;
        // Screen-space bounds of every visible registered key, cached per gesture (layout does
        // not change mid-touch).
        private final Map<Entry, Rect> bounds = new HashMap<>();
        @Nullable
        private Entry current;

        PointerTracker(@NonNull Entry origin) {
            this.origin = origin;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cacheBounds();
                    current = origin;
                    press(origin);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    onMove(event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (current != null) {
                        release(current);
                        current = null;
                    }
                    return true;
            }
            return false;
        }

        private void onMove(float rawX, float rawY) {
            int x = Math.round(rawX), y = Math.round(rawY);
            if (current != null) {
                var rect = bounds.get(current);
                if (rect != null && rect.contains(x, y)) return;
            }
            var target = hitTest(x, y);
            if (target == current) return;
            // Down-before-up on a roll so the guest never sees a hold gap.
            if (target != null) press(target);
            if (current != null) release(current);
            current = target;
        }

        @Nullable
        private Entry hitTest(int x, int y) {
            for (var e : entries) {
                var rect = bounds.get(e);
                // A key another pointer is holding can't be glided onto.
                if (rect != null && rect.contains(x, y) && !e.view.isPressed()) return e;
            }
            return null;
        }

        private void cacheBounds() {
            bounds.clear();
            int[] loc = new int[2];
            for (var e : entries) {
                if (!e.view.isShown()) continue;
                e.view.getLocationOnScreen(loc);
                bounds.put(e, new Rect(
                    loc[0], loc[1], loc[0] + e.view.getWidth(), loc[1] + e.view.getHeight()));
            }
        }
    }
}
