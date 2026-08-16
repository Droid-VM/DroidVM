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

    private final SurfaceView mainView;
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
    private boolean hasSavedFrame = false;
    private CursorPositionStream cursorStream;
    private CursorPositionStream.Listener cursorListener;
    private SurfaceView cursorView;
    private boolean cursorSurfaceSent = false;

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
            // The display service lives in crosvm. When the guest reboots (or crosvm otherwise
            // restarts) the daemon relaunches crosvm, which registers a NEW display service under
            // the same name -- but nothing re-fetches it, so the activity sat on "waiting for the
            // VM screen" until the user closed and reopened it (seen on every provisioning reboot).
            // Re-arm: the surface we hold is still valid, so mark it for re-delivery and poll for
            // the new binder the same way the first attach did. shutdown() has already stopped
            // the executor when the activity is going away, so a real teardown does not re-poll.
            if (executor.isShutdown()) {
                needsSend = false;
                return;
            }
            needsSend = false;
            cursorSurfaceSent = false;
            hasSavedFrame = false;
            if (cursorStream != null) {
                cursorStream.close();
                cursorStream = null;
            }
            // Do NOT hand the old Surface to the new crosvm. Its BufferQueue still carries the
            // state the dead producer left behind (buffers dequeued and never returned): the CPU
            // console path can still post to it, but the GPU-blit scanout path silently stops --
            // observed as the early-boot console frozen on screen with a live cursor. Closing and
            // reopening the activity fixed it because that creates fresh surfaces, so do exactly
            // that here: bounce both SurfaceViews so surfaceDestroyed/surfaceCreated run and the
            // usual send-once-per-surface path delivers brand-new surfaces once the binder is back.
            recreateSurface(mainView);
            if (cursorView != null) recreateSurface(cursorView);
            Log.i(TAG, "re-fetching the display binder for the restarted VM (fresh surfaces)");
            fetchBinder();
        });

        mainView.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
        mainView.getHolder().addCallback(new Callback());

        var surface = mainView.getHolder().getSurface();
        if (surface != null && surface.isValid()) {
            mainView.getHolder().setFixedSize(width, height);
            needsSend = true;
        }
        fetchBinder();
    }

    /* Tear down and re-create a SurfaceView's surface (fresh BufferQueue). The main view runs with
     * SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT, so visibility does not govern the surface (a GONE/VISIBLE
     * bounce was seen to do nothing, or to destroy the surface tens of seconds later and never bring
     * it back). Detaching the view from its parent and re-adding it is what the lifecycle actually
     * follows: surfaceDestroyed fires on detach and the next traversal creates a brand-new surface
     * (surfaceCreated -> the usual send-once-per-surface path delivers it once the binder is back). */
    private void recreateSurface(@NonNull SurfaceView view) {
        var parent = view.getParent();
        if (!(parent instanceof android.view.ViewGroup)) {
            Log.w(TAG, "recreateSurface: view has no ViewGroup parent");
            return;
        }
        var group = (android.view.ViewGroup) parent;
        int idx = group.indexOfChild(view);
        var lp = view.getLayoutParams();
        Log.i(TAG, "recreateSurface: detaching and re-attaching " + view.getClass().getSimpleName());
        group.removeView(view);
        group.addView(view, idx, lp);
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
        cursorView = view;
        if (view == null) {
            return;
        }
        // Above the scanout SurfaceView. Both are surfaces in their own layers, so ordinary view
        // z-order does not apply -- without this the cursor is composited BEHIND the display.
        view.setZOrderMediaOverlay(true);
        view.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        view.getHolder().setFixedSize(CURSOR_PLANE_PX, CURSOR_PLANE_PX);
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(@NonNull SurfaceHolder h) {
                cursorSurfaceSent = false;
                trySendCursorSurface(h);
            }
            @Override public void surfaceChanged(@NonNull SurfaceHolder h, int f, int w, int hh) {
                // Same rule as the main surface: setSurface at most once per surface lifetime.
                trySendCursorSurface(h);
            }
            @Override public void surfaceDestroyed(@NonNull SurfaceHolder h) {
                cursorSurfaceSent = false;
                var svc = displayService;
                if (svc == null) return;
                try {
                    svc.removeSurface(true);
                } catch (Exception e) {
                    Log.w(TAG, "removeSurface(cursor) failed", e);
                }
            }
        });
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
