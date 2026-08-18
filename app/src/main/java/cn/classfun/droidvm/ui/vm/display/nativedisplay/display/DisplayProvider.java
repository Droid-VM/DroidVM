// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import android.crosvm.DisplayConfig;
import android.crosvm.ICrosvmAndroidDisplayService;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Hands crosvm the main display {@link Surface} for one VM. MVP: main surface only (no cursor
 * overlay). gfxstream/virtio-gpu renders the guest framebuffer straight into this Surface.
 *
 * Invariants (mirrored from the reference DisplayProvider):
 *  - Binder fetch on a background thread; the main thread never blocks.
 *  - setSurface is called AT MOST ONCE per surface lifetime (surfaceCreated -> surfaceDestroyed).
 *    Layout-driven surfaceChanged must NOT re-call it (crosvm throws if called twice).
 *  - surfaceDestroyed -> save frame + removeSurface; the next surfaceCreated starts a new session.
 *
 * The scanout target is not fixed for the provider's lifetime: {@link #retarget} moves it to a
 * SurfaceView on another Android display (external-screen casting). crosvm is indifferent to which
 * display the consumer layer lives on -- a Surface is just a BufferQueue producer handle -- so
 * casting is a pure consumer-side re-attach that needs no daemon or crosvm involvement.
 */
final class DisplayProvider {
    private static final String TAG = "NativeDisplayProvider";
    // Stop polling for the display binder after this many 1s rounds (the daemon-side
    // waitForService already blocks up to 5s each), so a dead broker doesn't spin forever.
    private static final int MAX_BINDER_ATTEMPTS = 30;
    // Interval for re-reading the guest display size. crosvm's binder is pull-only (no resize
    // push callback, and SELinux blocks crosvm from calling back into an untrusted_app), so the
    // receiver polls the single source of truth -- getDisplayConfig() reflects the current scanout
    // size, updated by C++ configure() on EVERY resolution change (UEFI modeset, Linux boot, and
    // Linux runtime xrandr all flow through it). This is what lets the aspect ratio follow a guest
    // resolution change mid-session. A resize is rare and user-driven, so 1s lag is fine.
    private static final long CONFIG_POLL_MS = 1000;
    /** virtio-gpu's cursor plane size; a smaller cursor image lands in its top-left. */
    static final int CURSOR_PLANE_PX = 64;

    // Not final: retarget() moves the scanout to a SurfaceView on another display.
    private SurfaceView mainView;
    private int width;
    private int height;
    private final Supplier<IBinder> binderProvider;
    private final Consumer<Boolean> onConnected;
    private final Consumer<DisplayConfig> onDisplayConfig;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "NativeDisplayProvider-bg"));

    // All fields below touched only on the main thread.
    private ICrosvmAndroidDisplayService displayService;
    private boolean needsSend = false;
    // Whether crosvm currently HOLDS our scanout surface. Distinct from !needsSend, which only says
    // "nothing pending": retarget() has to know whether a removeSurface is owed before handing over
    // a different surface, and during a move both flags are briefly false.
    private boolean surfaceAttached = false;
    private boolean hasSavedFrame = false;
    private CursorPositionStream cursorStream;
    private CursorPositionStream.Listener cursorListener;
    private SurfaceView cursorView;
    private boolean cursorSurfaceSent = false;
    // Held so retarget() can detach them from the outgoing views' holders; a callback left attached
    // would fire surfaceDestroyed later and remove the surface crosvm has just been given.
    private final Callback mainCallback = new Callback();
    private SurfaceHolder.Callback cursorCallback;

    private final IBinder.DeathRecipient deathRecipient;

    DisplayProvider(@NonNull SurfaceView mainView, int width, int height,
                    @NonNull Supplier<IBinder> binderProvider,
                    @NonNull Consumer<Boolean> onConnected,
                    @NonNull Consumer<DisplayConfig> onDisplayConfig) {
        this.mainView = mainView;
        this.width = width;
        this.height = height;
        this.binderProvider = binderProvider;
        this.onConnected = onConnected;
        this.onDisplayConfig = onDisplayConfig;
        this.deathRecipient = () -> mainHandler.post(() -> {
            Log.w(TAG, "display service died - connection lost");
            onConnected.accept(false);
            displayService = null;
            needsSend = false;
        });

        mainView.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
        mainView.getHolder().addCallback(mainCallback);

        var surface = mainView.getHolder().getSurface();
        if (surface != null && surface.isValid()) {
            mainView.getHolder().setFixedSize(width, height);
            needsSend = true;
        }
        fetchBinder();
    }

    private void fetchBinder() {
        executor.submit(() -> {
            IBinder binder = null;
            int attempts = 0;
            while (!Thread.currentThread().isInterrupted() && attempts < MAX_BINDER_ATTEMPTS) {
                try {
                    binder = binderProvider.get();
                } catch (Exception e) {
                    Log.e(TAG, "binderProvider threw", e);
                    binder = null;
                }
                if (binder != null) break;
                attempts++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            final IBinder got = binder;
            if (got == null) {
                // Give up rather than poll forever: the daemon broker may have died (the supplier
                // returns null once rootService is dropped), so the display can't be reached.
                Log.e(TAG, fmt("display binder unavailable after %d attempts", MAX_BINDER_ATTEMPTS));
                mainHandler.post(() -> onConnected.accept(false));
                return;
            }
            mainHandler.post(() -> onBinderReady(got));
        });
    }

    private void onBinderReady(@NonNull IBinder binder) {
        Log.i(TAG, "got crosvm display binder");
        displayService = ICrosvmAndroidDisplayService.Stub.asInterface(binder);
        try {
            binder.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "linkToDeath failed", e);
        }
        try {
            DisplayConfig config = displayService.getDisplayConfig();
            if (config != null && config.width > 0 && config.height > 0) {
                width = config.width;
                height = config.height;
                mainView.getHolder().setFixedSize(width, height);
                onDisplayConfig.accept(config);
            }
        } catch (Exception e) {
            Log.w(TAG, fmt("getDisplayConfig unavailable, using default %dx%d", width, height));
        }
        // Attach the cursor position pipe, if anybody wants it. crosvm has always written guest
        // cursor moves to this fd; nothing ever read it, so the backend dropped them all.
        attachCursorStream();
        if (cursorView != null) {
            trySendCursorSurface(cursorView.getHolder());
        }

        onConnected.accept(true);
        applyPendingSurface();
        startConfigPoll(displayService);
    }

    // Watches for guest resolution changes: crosvm has no push channel, so re-read the current
    // scanout size on the bg thread and, when it changes, re-lay-out the surface. Runs on the
    // single background executor; a getDisplayConfig() failure (dead service) ends the loop.
    private void startConfigPoll(@NonNull ICrosvmAndroidDisplayService svc) {
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CONFIG_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                DisplayConfig config;
                try {
                    config = svc.getDisplayConfig();
                } catch (Exception e) {
                    return; // service gone; deathRecipient handles teardown
                }
                if (config == null || config.width <= 0 || config.height <= 0) continue;
                mainHandler.post(() -> applyConfigIfChanged(config));
            }
        });
    }

    // Main thread: apply a newly observed guest size. width/height stay main-thread-only.
    private void applyConfigIfChanged(@NonNull DisplayConfig config) {
        if (config.width == width && config.height == height) return;
        Log.i(TAG, fmt("guest display size changed %dx%d -> %dx%d",
            width, height, config.width, config.height));
        width = config.width;
        height = config.height;
        // Keep the app-side buffer size in step with crosvm's setBuffersGeometry; guarded by
        // needsSend, so the layout-driven surfaceChanged this triggers won't re-send the surface.
        mainView.getHolder().setFixedSize(width, height);
        onDisplayConfig.accept(config);
    }

    private void applyPendingSurface() {
        if (displayService == null || !needsSend) return;
        trySendSurface(mainView.getHolder());
    }

    private void trySendSurface(@NonNull SurfaceHolder holder) {
        var svc = displayService;
        if (svc == null) return;
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) {
            Log.w(TAG, "main surface not valid - skipping");
            return;
        }
        try {
            svc.setSurface(surface, false);
            markSent();
        } catch (IllegalArgumentException e) {
            // KNOWN/BENIGN: the reference TerminalApp DisplayProvider documents that setSurface
            // throws IllegalArgumentException from the binder reply path even though crosvm DID
            // receive and configure the surface. Treat as delivered.
            Log.w(TAG, "setSurface: IllegalArgumentException (known/benign) - treating as delivered");
            markSent();
        } catch (Exception e) {
            Log.e(TAG, "setSurface failed", e);
        }
    }

    private void markSent() {
        needsSend = false;
        surfaceAttached = true;
        Log.i(TAG, "main surface sent");
        if (hasSavedFrame && displayService != null) {
            // The surface was recreated (e.g. rotation/fullscreen). Repaint the captured frame so
            // a lazy-redraw guest compositor doesn't leave it black.
            try {
                displayService.drawSavedFrameForSurface(false);
            } catch (Exception e) {
                Log.w(TAG, "drawSavedFrameForSurface failed", e);
            }
        }
    }

    private final class Callback implements SurfaceHolder.Callback {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            holder.setFixedSize(width, height);
            needsSend = true; // fresh surface - send on next surfaceChanged
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int w, int h) {
            if (displayService == null || !needsSend) return;
            trySendSurface(holder);
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            needsSend = false;
            releaseMainSurface();
        }
    }

    /**
     * Hand the scanout surface back to crosvm: save the frame (so a lazy-redraw guest compositor
     * doesn't leave the next surface black) then remove it. No-op unless crosvm actually holds one.
     */
    private void releaseMainSurface() {
        if (!surfaceAttached) return;
        surfaceAttached = false;
        var svc = displayService;
        if (svc == null) return;
        try {
            svc.saveFrameForSurface(false);
            hasSavedFrame = true;
        } catch (Exception e) {
            Log.w(TAG, "saveFrameForSurface failed", e);
        }
        try {
            svc.removeSurface(false);
        } catch (DeadObjectException e) {
            Log.w(TAG, "display service already dead on surfaceDestroyed");
        } catch (RemoteException e) {
            Log.e(TAG, "removeSurface failed", e);
        }
    }

    /**
     * Move the guest scanout (and hardware cursor plane) onto a different pair of SurfaceViews,
     * typically ones living in a {@code Presentation} on an external display.
     *
     * Ordering is the whole point of this method. crosvm throws if setSurface arrives while it still
     * holds a surface, and the outgoing views' surfaceDestroyed fires asynchronously (whenever the
     * old hierarchy gets around to detaching), so it cannot be relied on to have run first. So:
     * detach the callbacks (a detached callback can no longer remove the NEW surface behind our
     * back), release the old surfaces synchronously, and only then attach and send the new ones.
     *
     * @param newCursor may be null to drop cursor-plane delivery for this target.
     */
    void retarget(@NonNull SurfaceView newMain, SurfaceView newCursor) {
        if (newMain == mainView) return;
        mainView.getHolder().removeCallback(mainCallback);
        releaseMainSurface();
        detachCursorView();

        mainView = newMain;
        needsSend = true;
        newMain.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
        newMain.getHolder().addCallback(mainCallback);
        newMain.getHolder().setFixedSize(width, height);
        // An already-live surface gets no surfaceCreated, so send it here; markSent replays the
        // saved frame either way.
        var surface = newMain.getHolder().getSurface();
        if (surface != null && surface.isValid()) trySendSurface(newMain.getHolder());

        setCursorView(newCursor);
    }

    // Drops the cursor plane: detach our callback and take the surface back off crosvm. Leaving it
    // attached would let the old view's surfaceDestroyed remove the new target's cursor surface.
    private void detachCursorView() {
        if (cursorView != null && cursorCallback != null) {
            cursorView.getHolder().removeCallback(cursorCallback);
        }
        if (cursorSurfaceSent) {
            cursorSurfaceSent = false;
            var svc = displayService;
            if (svc != null) {
                try {
                    svc.removeSurface(true);
                } catch (Exception e) {
                    Log.w(TAG, "removeSurface(cursor) on retarget failed", e);
                }
            }
        }
        cursorView = null;
        cursorCallback = null;
    }

    /**
     * Ask for guest cursor positions. Safe to call before or after the display binder arrives:
     * whichever happens second does the attaching, so a caller does not have to know which.
     */
    void setCursorListener(CursorPositionStream.Listener listener) {
        cursorListener = listener;
        attachCursorStream();
    }

    private void attachCursorStream() {
        if (cursorListener == null || displayService == null || cursorStream != null) {
            return;
        }
        cursorStream = CursorPositionStream.attach(displayService, cursorListener);
    }

    /**
     * Give crosvm a Surface for the guest's HARDWARE cursor.
     *
     * Until something calls setSurface(_, forCursor=true), the native backend's cursor surface has
     * no native window: lock() hands crosvm a sink buffer whose own comment says it "is never
     * displayed on the physical screen", and unlockAndPost() returns without posting. The pointer
     * is rendered perfectly and thrown away.
     *
     * Linux guests can be told to draw the pointer into the framebuffer instead, which is why this
     * went unnoticed; Windows' virtio-gpu driver and UEFI have no equivalent and use the cursor
     * plane unconditionally, so without this they have no visible pointer at all.
     */
    void setCursorView(SurfaceView view) {
        if (view == cursorView) {
            return;
        }
        detachCursorView();
        cursorView = view;
        if (view == null) {
            return;
        }
        // Above the scanout SurfaceView. Both are surfaces in their own layers, so ordinary view
        // z-order does not apply -- without this the cursor is composited BEHIND the display.
        view.setZOrderMediaOverlay(true);
        view.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        view.getHolder().setFixedSize(CURSOR_PLANE_PX, CURSOR_PLANE_PX);
        cursorCallback = new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(@NonNull SurfaceHolder h) {
                cursorSurfaceSent = false;
                trySendCursorSurface(h);
            }
            @Override public void surfaceChanged(@NonNull SurfaceHolder h, int f, int w, int hh) {
                // Same rule as the main surface: setSurface at most once per surface lifetime.
                trySendCursorSurface(h);
            }
            @Override public void surfaceDestroyed(@NonNull SurfaceHolder h) {
                if (!cursorSurfaceSent) return;
                cursorSurfaceSent = false;
                var svc = displayService;
                if (svc == null) return;
                try {
                    svc.removeSurface(true);
                } catch (Exception e) {
                    Log.w(TAG, "removeSurface(cursor) failed", e);
                }
            }
        };
        view.getHolder().addCallback(cursorCallback);
        trySendCursorSurface(view.getHolder());
    }

    private void trySendCursorSurface(@NonNull SurfaceHolder holder) {
        var svc = displayService;
        if (svc == null || cursorSurfaceSent) return;
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) return;
        try {
            svc.setSurface(surface, true);
            cursorSurfaceSent = true;
            Log.i(TAG, "cursor surface delivered");
        } catch (IllegalArgumentException e) {
            // Same known/benign binder reply behaviour as the main surface.
            cursorSurfaceSent = true;
        } catch (Exception e) {
            Log.w(TAG, "setSurface(cursor) failed", e);
        }
    }

    void shutdown() {
        if (displayService != null) {
            try {
                displayService.asBinder().unlinkToDeath(deathRecipient, 0);
            } catch (Exception ignored) {
            }
        }
        if (cursorStream != null) {
            cursorStream.close();
            cursorStream = null;
        }
        executor.shutdownNow();
        displayService = null;
        needsSend = false;
    }
}
