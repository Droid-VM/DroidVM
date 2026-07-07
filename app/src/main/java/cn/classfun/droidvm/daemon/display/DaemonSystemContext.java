package cn.classfun.droidvm.daemon.display;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;

/**
 * Builds a system {@link Context} inside the daemon's bare app_process so it can {@code sendBroadcast}
 * - the channel used to hand the native-display binder to the UI (a live IBinder can't cross the
 * daemon's TCP/JSON-RPC socket).
 *
 * We construct an {@code ActivityThread} directly and ask it for the system Context, rather than
 * {@code ActivityThread.systemMain()}: systemMain()'s {@code attach(true)} runs app/Instrumentation
 * init that throws in a bare daemon process. The ActivityThread ctor only needs a prepared main
 * looper (for its internal Handler), so {@link #init()} must run on the daemon's main thread (see
 * Daemon.main). We never loop it; a fire-and-forget broadcast is a plain binder call to AMS.
 */
public final class DaemonSystemContext {
    private static final String TAG = "DaemonSysContext";
    private static Context context;

    private DaemonSystemContext() {
    }

    /** Creates the system Context once, on the daemon main thread. Safe to call repeatedly. */
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    public static synchronized void init() {
        if (context != null) return;
        try {
            if (Looper.myLooper() == null) Looper.prepare();
        } catch (Throwable t) {
            Log.w(TAG, fmt("prepare looper: %s", t));
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Constructor<?> ctor = at.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object thread = ctor.newInstance();
            context = (Context) at.getMethod("getSystemContext").invoke(thread);
            if (context != null) {
                Log.i(TAG, "system context ready");
                return;
            }
            Log.e(TAG, "getSystemContext returned null");
        } catch (Throwable t) {
            Log.e(TAG, "failed to create system context", t);
        }
    }

    /** The system Context, or null if {@link #init()} hasn't run or failed. */
    @Nullable
    public static Context get() {
        return context;
    }
}
