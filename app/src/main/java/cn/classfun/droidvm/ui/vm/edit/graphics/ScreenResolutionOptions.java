// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.graphics;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The sizes the resolution dropdown offers, and the only part of that dropdown that can be decided
 * without a device.
 *
 * <p>Two of them are fixed ({@code 1280x720}, {@code 1920x1080}) and two are the phone's own panel
 * -- whole, and halved on each axis -- because a guest that matches the panel is the one case where
 * nothing has to scale, and half of it is the one that costs a quarter as much to draw. All four are
 * landscape: a VM's screen is a desktop's screen, so the larger number is always the width, however
 * the phone reports its own.</p>
 *
 * <p>They are ordered by area rather than in the order they are named, since "smaller or larger
 * than the one I have" is the only comparison between them a user can make at a glance, and the
 * phone-derived pair lands in a different place on every device. A size that two rules produce
 * appears once -- the list is of sizes, and where a size came from is not something it says.</p>
 */
final class ScreenResolutionOptions {
    /** The floor the geometry validator enforces; a rule that would produce less offers nothing. */
    static final int MIN_EDGE = 320;

    private static final int[][] PRESETS = {{1280, 720}, {1920, 1080}};

    static final class Option {
        final int width;
        final int height;

        Option(int width, int height) {
            this.width = width;
            this.height = height;
        }

        long area() {
            return (long) width * height;
        }

        boolean is(int w, int h) {
            return width == w && height == h;
        }
    }

    private ScreenResolutionOptions() {
    }

    /**
     * The dropdown's sizes for a panel of {@code phoneWidth x phoneHeight}, smallest area first.
     *
     * @param phoneWidth  the panel's width in pixels, in whatever rotation it was read; 0 or less
     *                    when there is no panel to ask, which yields the fixed sizes alone rather
     *                    than inventing a device.
     * @param phoneHeight the panel's height, likewise.
     */
    @NonNull
    static List<Option> build(int phoneWidth, int phoneHeight) {
        var out = new ArrayList<Option>(4);
        for (var preset : PRESETS) add(out, preset[0], preset[1]);
        if (phoneWidth > 0 && phoneHeight > 0) {
            var longEdge = Math.max(phoneWidth, phoneHeight);
            var shortEdge = Math.min(phoneWidth, phoneHeight);
            // Halving can land on an odd number, and an odd width is a size no display pipeline
            // wants; round down to even so the offered size is one the guest can actually use.
            add(out, even(longEdge / 2), even(shortEdge / 2));
            add(out, longEdge, shortEdge);
        }
        // Ascending area, and by width when two sizes cover the same area -- a total order, so the
        // list does not depend on which rule happened to produce a size first.
        Collections.sort(out, (a, b) -> a.area() != b.area()
            ? Long.compare(a.area(), b.area()) : Integer.compare(a.width, b.width));
        return Collections.unmodifiableList(out);
    }

    /**
     * Adds one size, unless it is below the validator's floor -- offering a size the editor would
     * then refuse to save is worse than not offering it -- or unless some other rule already
     * produced it, since the list names sizes rather than the rules that found them.
     */
    private static void add(@NonNull List<Option> out, int width, int height) {
        if (width < MIN_EDGE || height < MIN_EDGE) return;
        for (var option : out) if (option.is(width, height)) return;
        out.add(new Option(width, height));
    }

    private static int even(int v) {
        return v - (v % 2);
    }
}
