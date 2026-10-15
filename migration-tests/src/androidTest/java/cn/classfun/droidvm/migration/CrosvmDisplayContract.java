// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import android.crosvm.ICrosvmAndroidDisplayService;
import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.view.Surface;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real isolated crosvm exporter through a Binder passed by the native broker. */
final class CrosvmDisplayContract {
    private static void attach(ICrosvmAndroidDisplayService display, Surface surface,
                               boolean cursor, Bundle result) throws RemoteException {
        try {
            display.setSurface(surface, cursor);
        } catch (IllegalArgumentException knownReplyError) {
            // Match the existing DisplayProvider's documented reply-path quirk.
            // For scanout we still require a real frame below; this exception
            // alone is never evidence of successful Surface delivery.
            result.putBoolean(cursor ? "cursor_surface_reply_quirk" : "scanout_surface_reply_quirk", true);
        }
    }

    static void verify(IBinder binder) throws Exception {
        assertNotNull("crosvm service not found", binder);
        assertEquals(ICrosvmAndroidDisplayService.DESCRIPTOR, binder.getInterfaceDescriptor());
        assertNull(binder.queryLocalInterface(ICrosvmAndroidDisplayService.DESCRIPTOR));
        var display = ICrosvmAndroidDisplayService.Stub.asInterface(binder);
        var result = new Bundle();
        try {
            var config = display.getDisplayConfig();
            result.putBoolean("display_config_available", config != null);
            if (config != null) {
                assertEquals(320, config.width);
                assertEquals(240, config.height);
            }
        } catch (RemoteException unsupported) {
            // The existing UI supports exporters that implement only the first
            // five transactions. Record that optional getter as unavailable.
            result.putBoolean("display_config_available", false);
        }
        var thread = new HandlerThread("MigrationDisplayFrames");
        thread.start();
        var frame = new CountDownLatch(1);
        var dimensions = new AtomicReference<int[]>();
        var pipe = ParcelFileDescriptor.createPipe();
        try (var reader = ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 2);
             var cursor = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2);
             var readEnd = pipe[0]; var writeEnd = pipe[1]) {
            reader.setOnImageAvailableListener(source -> {
                try (var image = source.acquireLatestImage()) {
                    if (image != null) {
                        dimensions.set(new int[]{image.getWidth(), image.getHeight()});
                        frame.countDown();
                    }
                }
            }, new Handler(thread.getLooper()));
            try {
                display.setCursorStream(writeEnd);
                attach(display, reader.getSurface(), false, result);
                assertTrue("No frame arrived from the real crosvm Surface producer", frame.await(10, TimeUnit.SECONDS));
                assertArrayEquals(new int[]{320, 240}, dimensions.get());
                display.saveFrameForSurface(false);
                display.drawSavedFrameForSurface(false);
                attach(display, cursor.getSurface(), true, result);
                display.saveFrameForSurface(true);
                display.drawSavedFrameForSurface(true);
                result.putBoolean("real_display_frame_received", true);
                result.putBoolean("real_display_transactions_passed", true);
                InstrumentationRegistry.getInstrumentation().sendStatus(2, result);
            } finally {
                display.removeSurface(false);
                display.removeSurface(true);
            }
        } finally {
            thread.quitSafely();
            thread.join(2000);
            assertFalse("Display callback thread leaked", thread.isAlive());
        }
    }
}
