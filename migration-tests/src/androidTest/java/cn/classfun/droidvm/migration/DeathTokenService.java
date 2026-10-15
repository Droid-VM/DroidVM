// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.migration;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;

/** A disposable test process: never kills the instrumentation or the user's app. */
public final class DeathTokenService extends Service {
    static final String DESCRIPTOR = "cn.classfun.droidvm.migration.DeathToken";

    @Override public IBinder onBind(Intent intent) {
        return new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != FIRST_CALL_TRANSACTION) return false;
                data.enforceInterface(DESCRIPTOR);
                reply.writeNoException();
                new Handler(Looper.getMainLooper()).postDelayed(
                    () -> Process.killProcess(Process.myPid()), 100);
                return true;
            }
        };
    }
}
