// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;

/**
 * How much room an input socket name has left, and what happens to the screen that runs out.
 *
 * <p>{@code sun_path} is 108 bytes and bind(2) truncates silently past it, so the name is checked
 * before the syscall. The two screens this build has fit easily -- they are spelt {@code g0} and
 * {@code sfb} for that reason -- but a screen id with no tag falls back to the id itself, and a
 * third screen is something virtio-gpu multi-display would bring. This pins the budget so that
 * arrives as a number here rather than as a socket nobody connects to.</p>
 */
public final class NativeDisplaySocketPathTest {
    /** A real one: the id is what makes the name long, so a made-up short one would prove nothing. */
    private static final String VM_ID = "5f2c1b70-9a4e-4c0d-8b31-6de7f0a2c845";

    @Test
    public void theScreensThisBuildHasFitWithRoomToSpare() {
        for (var screenId : VMScreenConfig.IDS)
            for (int ch = 0; ch < NativeDisplay.CHANNEL_COUNT; ch++) {
                var path = NativeDisplay.inputSocketPath(VM_ID, screenId, ch);
                assertTrue(fmt("%s is over sun_path", path),
                    path.length() <= NativeDisplay.MAX_UNIX_PATH);
                NativeDisplay.requireBindablePath(path);
            }
    }

    @Test
    public void everyChannelOnEveryScreenIsItsOwnInode() {
        // Two channels sharing a name would have the guest's typing and its touches arriving on one
        // device, and the truncation this guards against is one way to produce exactly that.
        var seen = new HashSet<String>();
        for (var screenId : VMScreenConfig.IDS)
            for (int ch = 0; ch < NativeDisplay.CHANNEL_COUNT; ch++)
                seen.add(NativeDisplay.inputSocketPath(VM_ID, screenId, ch));
        // The VM-wide channel collapses onto one name whatever screen is passed, so the count is
        // one per screen per per-screen channel, plus that single shared one.
        int perScreen = 0;
        for (int ch = 0; ch < NativeDisplay.CHANNEL_COUNT; ch++)
            if (NativeDisplay.isPerScreen(ch)) perScreen++;
        assertEquals(VMScreenConfig.IDS.length * perScreen + 1, seen.size());
    }

    @Test
    public void aScreenWithNoTagOfItsOwnSpendsItsWholeIdOnTheName() {
        // What a third screen gets before somebody adds it to SCREEN_TAGS: the sanitized id.
        var tagged = NativeDisplay.inputSocketPath(VM_ID, "gpu-0", NativeDisplay.KEYBOARD);
        var untagged = NativeDisplay.inputSocketPath(VM_ID, "gpu-1", NativeDisplay.KEYBOARD);
        assertNotEquals(tagged, untagged);
        assertTrue("a short id still fits", untagged.length() <= NativeDisplay.MAX_UNIX_PATH);
    }

    @Test
    public void aLongScreenIdIsRefusedRatherThanTruncated() {
        // The failure this whole check exists for. It is refused at the name, before any fd is
        // bound -- and the start that trips it has already bound the screens ahead of this one,
        // which is why NativeDisplayInputBridge closes what it built on the way out.
        var path = NativeDisplay.inputSocketPath(
            VM_ID, "virtio-gpu-secondary-display", NativeDisplay.KEYBOARD);
        assertTrue(path.length() > NativeDisplay.MAX_UNIX_PATH);
        assertThrows(IllegalArgumentException.class,
            () -> NativeDisplay.requireBindablePath(path));
    }

    @Test
    public void theBudgetLeftForAScreenIdIsWorthKnowing() {
        // Twenty characters, on the longest channel tag. Not a rule, a measurement: it moves when
        // the prefix or the run directory does, and a third screen id is chosen against it.
        var name = NativeDisplay.inputSocketPath(VM_ID, "", NativeDisplay.KEYBOARD);
        int spare = NativeDisplay.MAX_UNIX_PATH - name.length();
        assertEquals(20, spare);
        NativeDisplay.requireBindablePath(NativeDisplay.inputSocketPath(
            VM_ID, "x".repeat(spare), NativeDisplay.KEYBOARD));
        assertThrows(IllegalArgumentException.class, () -> NativeDisplay.requireBindablePath(
            NativeDisplay.inputSocketPath(VM_ID, "x".repeat(spare + 1), NativeDisplay.KEYBOARD)));
    }
}
