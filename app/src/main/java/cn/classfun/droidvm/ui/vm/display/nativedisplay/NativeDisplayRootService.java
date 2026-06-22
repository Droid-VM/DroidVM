package cn.classfun.droidvm.ui.vm.display.nativedisplay;

import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.ipc.RootService;

import java.lang.reflect.Method;

import cn.classfun.droidvm.display.INativeDisplayRootService;

/**
 * Runs as root (uid=0) via libsu RootService. Because it runs as root it can call the hidden
 * ServiceManager APIs and sits in a permissive su/magisk domain.
 *
 * The UI binds this and calls {@link INativeDisplayRootService#waitForDisplayBinder(String)} to
 * obtain the per-VM ICrosvmAndroidDisplayService binder that crosvm registers directly via
 * {@code --android-display-service <serviceName>}. Input sockets are owned by the daemon, not here.
 */
public final class NativeDisplayRootService extends RootService {
    private static final String TAG = "NativeDisplayRootSvc";

    private static IBinder smCall(@NonNull String method, @NonNull String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getMethod(method, String.class);
            return (IBinder) m.invoke(null, name);
        } catch (Exception e) {
            Log.w(TAG, method + " reflection failed: " + e.getMessage());
            return null;
        }
    }

    private static IBinder waitForServiceWithTimeout(@NonNull String name, long timeoutMs) {
        var holder = new IBinder[1];
        var t = new Thread(() -> holder[0] = smCall("waitForService", name), "WaitSvc-" + name);
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ignored) {
        }
        return holder[0];
    }

    @Override
    public IBinder onBind(@NonNull Intent intent) {
        Log.i(TAG, "onBind: root service starting, uid=" + Process.myUid()
            + ", pid=" + Process.myPid());
        return new INativeDisplayRootService.Stub() {
            @Override
            public IBinder waitForDisplayBinder(String serviceName) {
                return doWaitForDisplayBinder(serviceName);
            }
        };
    }

    private IBinder doWaitForDisplayBinder(@NonNull String serviceName) {
        Log.i(TAG, "waitForDisplayBinder('" + serviceName + "'): uid=" + Process.myUid());
        var direct = smCall("checkService", serviceName);
        if (direct != null) {
            Log.i(TAG, "OK: got display binder directly from ServiceManager");
            return direct;
        }
        Log.i(TAG, "Not found, waiting up to 5s...");
        var waited = waitForServiceWithTimeout(serviceName, 5000L);
        if (waited != null) {
            Log.i(TAG, "OK: got display binder via waitForService");
            return waited;
        }
        Log.e(TAG, "'" + serviceName + "' not found — is crosvm running with "
            + "--android-display-service " + serviceName + "?");
        return null;
    }
}
