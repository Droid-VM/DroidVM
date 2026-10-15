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
    static String toHex(byte[] bytes) {
        var hex = new StringBuilder();
        for (byte value : bytes) hex.append(String.format("%02x", value & 255));
        return hex.toString();
    }

    static Bundle nestedSample() {
        var nested = new Bundle();
        nested.putBinder("binder", null);
        nested.putString("nonce", "0123456789abcdef0123456789abcdef");
        return nested;
    }

    static String bundleTrailer() {
        var parcel = Parcel.obtain();
        try {
            nestedSample().writeToParcel(parcel, 0);
            byte[] bytes = parcel.marshall();
            parcel.setDataPosition(0);
            int end = 8 + parcel.readInt();
            return toHex(java.util.Arrays.copyOfRange(bytes, end, bytes.length));
        } finally { parcel.recycle(); }
    }
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
        report.putString("bundle_trailer_hex", bundleTrailer());
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
            byte[] empty = parcel.marshall();
            var hex = new StringBuilder();
            for (byte value : empty) hex.append(String.format("%02x", value & 255));
            report.putString("empty_intent_hex", hex.toString());
            // Derive the Bundle slot from this ROM, not an AOSP field count.
            // Vendor Intent fields may be inserted before extras.
            intent.putExtra("profile_marker", "extras");
            parcel.setDataPosition(0);
            parcel.setDataSize(0);
            intent.writeToParcel(parcel, 0);
            byte[] withExtras = parcel.marshall();
            int offset = 0;
            while (offset < empty.length && empty[offset] == withExtras[offset]) offset++;
            report.putInt("extras_offset", offset & ~3);
            hex.setLength(0);
            for (byte value : withExtras) hex.append(String.format("%02x", value & 255));
            report.putString("marker_intent_hex", hex.toString());
            intent.removeExtra("profile_marker");
            intent.putExtra("extra.bundle", nestedSample());
            parcel.setDataPosition(0);
            parcel.setDataSize(0);
            intent.writeToParcel(parcel, 0);
            hex.setLength(0);
            for (byte value : parcel.marshall()) hex.append(String.format("%02x", value & 255));
            report.putString("nested_intent_hex", hex.toString());
        } finally {
            parcel.recycle();
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, report);
    }
}
