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
        mainView.getHolder().addCallback(new Callback());

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
        onConnected.accept(true);
        applyPendingSurface();
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

    void shutdown() {
        if (displayService != null) {
            try {
                displayService.asBinder().unlinkToDeath(deathRecipient, 0);
            } catch (Exception ignored) {
            }
        }
        executor.shutdownNow();
        displayService = null;
        needsSend = false;
    }
}
