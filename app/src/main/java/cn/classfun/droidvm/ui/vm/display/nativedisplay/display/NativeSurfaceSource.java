package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import android.os.Handler;
import android.os.IBinder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import java.util.function.Supplier;

import cn.classfun.droidvm.ui.vm.display.base.DisplaySource;

/**
 * {@link DisplaySource} for the native display path: crosvm renders straight into the
 * SurfaceView's Surface via the per-VM ICrosvmAndroidDisplayService. Wraps {@link DisplayProvider}
 * behind the pluggable source interface, so a future source (e.g. zero-copy AHardwareBuffer)
 * slots into the same console. Frames start flowing as soon as the display-binder supplier can
 * resolve the service, so {@link #start()} is a no-op. No guest-resize channel on this path yet.
 */
public final class NativeSurfaceSource implements DisplaySource {
    private final DisplayProvider provider;

    /**
     * @param displayBinderSupplier resolves the per-VM display binder (blocking, called off the
     *                              main thread); null while the daemon broker isn't attached yet.
     */
    public NativeSurfaceSource(@NonNull SurfaceView surfaceView, int guestWidth, int guestHeight,
                               @NonNull Supplier<IBinder> displayBinderSupplier,
                               @NonNull Handler mainHandler, @NonNull Callbacks callbacks) {
        provider = new DisplayProvider(surfaceView, guestWidth, guestHeight, displayBinderSupplier,
            connected -> mainHandler.post(() -> callbacks.onStateChanged(
                connected ? State.CONNECTED : State.CONNECTING)),
            config -> mainHandler.post(() -> callbacks.onContentSize(config.width, config.height)));
    }

    @Override
    public void start() {
    }

    @Override
    public void shutdown() {
        provider.shutdown();
    }

    @Override
    public boolean supportsGuestResize() {
        return false;
    }

    @Override
    public void requestGuestResize(int width, int height) {
    }
}
