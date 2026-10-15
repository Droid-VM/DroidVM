// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.migration;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Binder;
import android.os.IBinder;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.Assume;

/** Development-only inspection of the ROM's private wire contract. No daemon uses this helper. */
public final class PlatformProfileTest {
    @Test public void reportBroadcastProfile() throws Exception {
        Assume.assumeTrue("Only run explicitly with --no-hidden-api-checks",
            "true".equals(InstrumentationRegistry.getArguments().getString("inspect_private_profile")));
        var stub = Class.forName("android.app.IActivityManager$Stub");
        var transaction = stub.getDeclaredField("TRANSACTION_broadcastIntentWithFeature");
        transaction.setAccessible(true);
        var report = new Bundle();
        report.putInt("broadcast_transaction", transaction.getInt(null));
        report.putInt("sdk", Build.VERSION.SDK_INT);
        report.putString("fingerprint", Build.FINGERPRINT);
        for (var method : Class.forName("android.app.IActivityManager").getDeclaredMethods()) {
            if (method.getName().equals("broadcastIntentWithFeature"))
                report.putString("broadcast_signature", method.toString());
        }
        var intent = new Intent("cn.classfun.droidvm.action.NATIVE_DISPLAY_BINDER");
        intent.setPackage("cn.classfun.droidvm.migration.test");
        var capture = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                var hex = new StringBuilder();
                for (byte value : data.marshall()) hex.append(String.format("%02x", value & 255));
                report.putString("broadcast_request_hex", hex.toString());
                reply.writeNoException(); reply.writeInt(0);
                return true;
            }
        };
        var proxy = stub.getMethod("asInterface", IBinder.class).invoke(null, capture);
        for (var method : Class.forName("android.app.IActivityManager").getDeclaredMethods()) {
            if (method.getName().equals("broadcastIntentWithFeature"))
                method.invoke(proxy, null, null, intent, null, null, 0, null, null, null, null,
                    null, -1, null, false, false, 0);
        }
        var parcel = Parcel.obtain();
        try {
            intent.writeToParcel(parcel, 0);
            var hex = new StringBuilder();
            for (byte value : parcel.marshall()) hex.append(String.format("%02x", value & 255));
            report.putString("empty_intent_hex", hex.toString());
        } finally {
            parcel.recycle();
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, report);
    }
}
