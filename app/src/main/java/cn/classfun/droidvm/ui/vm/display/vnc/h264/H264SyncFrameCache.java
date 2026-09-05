// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * The one rect a decoder can start on, kept for as long as the connection that sent it.
 *
 * <p>The server sends the parameter sets exactly once per client: on the reset-flagged rect that
 * starts its stream, {@code SPS PPS IDR} in one body. Every IDR after that is bare, and a decoder
 * that never saw the first rect has nothing to decode any of them against -- it buffers forever
 * and the screen stays black. Nothing on the wire brings that rect back: the connection is what
 * enrolled the client, the connection is still fine, and the server has no reason to think anything
 * was missed.</p>
 *
 * <p>So the rect has to outlive everything on this side that can come and go while the connection
 * stays up. That is three things. The decoder goes with its surface when the console is
 * backgrounded; the pipeline's generation goes with the decoder; and the pipeline itself does not
 * exist until there is a view to draw into, which for the presentation console means until a
 * display has been chosen -- a choice the connection does not wait for, so the rect that starts
 * the stream can arrive before there is anything to hand it to. This is where it waits.</p>
 *
 * <p>One entry, keyed by geometry. A reset-flagged rect at a new size replaces it -- the parameter
 * sets describe a coded size, so an entry at the old size is exactly the wrong thing to prime a
 * decoder with -- and a decoder is only ever primed with an entry at its own size.</p>
 *
 * <p>Written on the RFB message-loop thread, which is the one thread rects arrive on, and read
 * there. The connection ending clears it from that thread too. The field is volatile only so that
 * a reconnect's fresh message loop, which is a different thread, starts from the cleared state.</p>
 */
public final class H264SyncFrameCache {
    private static final class Entry {
        final int width;
        final int height;
        @NonNull
        final byte[] annexB;

        Entry(int width, int height, @NonNull byte[] annexB) {
            this.width = width;
            this.height = height;
            this.annexB = annexB;
        }
    }

    @Nullable
    private volatile Entry entry;

    /** Keeps [annexB] as the sync frame for a stream at [width] x [height], replacing any other. */
    public void remember(int width, int height, @NonNull byte[] annexB) {
        entry = new Entry(width, height, annexB);
    }

    /**
     * Reads one rect body the way the pipeline would, and keeps it if it is a sync frame.
     *
     * <p>For a rect that arrives while there is no pipeline to hand it to. A body that will not
     * parse is neither kept nor reported: the connection is fine -- the reader took exactly the
     * bytes the length declared -- and there is no pipeline to bring down over it, so the honest
     * thing to do with it is nothing. The pipeline that eventually exists will judge the next one
     * for itself.</p>
     */
    public void rememberIfSync(@NonNull byte[] rectBody, int width, int height) {
        if (width <= 0 || height <= 0) return;
        H264RectProtocol.StreamRect rect;
        try {
            rect = H264RectProtocol.parseStreamRect(rectBody);
        } catch (IOException e) {
            return;
        }
        if (rect.resetsDecoder()) remember(width, height, rect.annexB);
    }

    /** The sync frame for a stream at exactly [width] x [height], or null when none was kept. */
    @Nullable
    public byte[] forGeometry(int width, int height) {
        var e = entry;
        return e != null && e.width == width && e.height == height ? e.annexB : null;
    }

    /** Forgets it: the connection that sent it is over, and the next one is served its own. */
    public void clear() {
        entry = null;
    }
}
