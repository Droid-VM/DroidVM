package cn.classfun.droidvm.ui.vm.display.base;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

import cn.classfun.droidvm.display.INativeDisplayRootService;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;

/**
 * Acquires the daemon's broker binder ({@link INativeDisplayRootService}) for a display activity.
 * The binder can't ride the JSON-RPC channel, so the flow is: send {@code display_attach} with a
 * per-attach random nonce, and the daemon (uid=0) broadcasts the binder back; only the broadcast
 * carrying our nonce is accepted, so another app spoofing the exported action can't slip us a
 * fake broker. Retries while the daemon connection is still coming up. Shared by the native and
 * VNC display activities, which use the binder for the direct evdev input sink (and the native
 * path additionally for the per-VM display binder lookup).
 */
public final class DaemonDisplayAttach {
    private static final String TAG = "DaemonDisplayAttach";
    private static final int MAX_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MS = 500;

    public interface Listener {
        /** The broker binder arrived (fired once, on the main thread). */
        void onAttached(@NonNull INativeDisplayRootService service);

        /** The daemon died after attach; direct paths must fall back (main thread). */
        void onLost();
    }

    private final Activity activity;
    private final Handler mainHandler;
    private final Listener listener;
    private final String nonce = UUID.randomUUID().toString();
    @Nullable
    private INativeDisplayRootService service;
    private boolean receiverRegistered;
    private boolean attached;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            var bundle = intent.getBundleExtra(NativeDisplay.EXTRA_BUNDLE);
            if (bundle == null || !nonce.equals(bundle.getString(NativeDisplay.EXTRA_NONCE))) {
                return;
            }
            var binder = bundle.getBinder(NativeDisplay.EXTRA_BINDER);
            if (binder != null) {
                onBinderReceived(binder);
            }
        }
    };

    private final IBinder.DeathRecipient deathRecipient;

    public DaemonDisplayAttach(@NonNull Activity activity, @NonNull Handler mainHandler,
                               @NonNull Listener listener) {
        this.activity = activity;
        this.mainHandler = mainHandler;
        this.listener = listener;
        this.deathRecipient = () -> mainHandler.post(() -> {
            Log.w(TAG, "daemon broker binder died");
            service = null;
            listener.onLost();
        });
    }

    /** Registers the nonce-matched broadcast receiver and requests the broker binder. */
    public void start() {
        activity.registerReceiver(receiver,
            new IntentFilter(NativeDisplay.BINDER_BROADCAST_ACTION), Context.RECEIVER_EXPORTED);
        receiverRegistered = true;
        request(MAX_ATTEMPTS);
    }

    /** The broker binder, or null before attach / after the daemon died. */
    @Nullable
    public INativeDisplayRootService getService() {
        return service;
    }

    public void stop() {
        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        if (service != null) {
            try {
                service.asBinder().unlinkToDeath(deathRecipient, 0);
            } catch (Exception ignored) {
            }
        }
        service = null;
    }

    private void request(int attemptsLeft) {
        if (attached || activity.isFinishing()) {
            return;
        }
        cn.classfun.droidvm.lib.daemon.DaemonConnection.getInstance().buildRequest("display_attach")
            .put("nonce", nonce)
            .onError(e -> retry(attemptsLeft))
            .onUnsuccessful(r -> retry(attemptsLeft))
            .invoke();
    }

    private void retry(int attemptsLeft) {
        if (attemptsLeft <= 0) {
            Log.w(TAG, "display_attach exhausted retries; daemon broker unavailable");
            return;
        }
        mainHandler.postDelayed(() -> request(attemptsLeft - 1), RETRY_DELAY_MS);
    }

    private void onBinderReceived(@NonNull IBinder binder) {
        if (attached) {
            return; // ignore duplicate broadcasts
        }
        attached = true;
        var svc = INativeDisplayRootService.Stub.asInterface(binder);
        service = svc;
        try {
            binder.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "linkToDeath failed", e);
        }
        listener.onAttached(svc);
    }
}
