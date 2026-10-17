// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.util.Arrays;

import cn.classfun.droidvm.lib.store.vm.NativeDisplay;

/**
 * This firmware's broadcastIntentWithFeature wire layout, for a daemon without a built-in profile.
 * Mirrors migration-tests PlatformProfileTest: the UI runs on the same framework, so its Parcel
 * output is what system_server expects. Null when the layout cannot be read or does not match
 * the argument list the daemon writes.
 */
final class BroadcastProfile {
    private static final String TAG = "BroadcastProfile";
    private static final String[] SIGNATURE = {
        "IApplicationThread", "String", "Intent", "String", "IIntentReceiver", "int", "String",
        "Bundle", "String[]", "String[]", "String[]", "int", "Bundle", "boolean", "boolean", "int",
    };
    private static JSONObject cached;
    private static boolean captured;

    private BroadcastProfile() {
    }

    @Nullable
    static synchronized JSONObject get(@NonNull Context context) {
        if (!captured) {
            captured = true;
            try {
                cached = capture(context.getPackageName());
            } catch (Throwable e) {
                Log.w(TAG, "cannot capture broadcast profile", e);
            }
        }
        return cached;
    }

    @Nullable
    private static JSONObject capture(@NonNull String pkg) throws Exception {
        // Both the method and the TRANSACTION_ field are hidden from apps.
        if (!HiddenApiBypass.addHiddenApiExemptions("Landroid/app/IActivityManager")) {
            Log.w(TAG, "hidden API exemption refused");
            return null;
        }
        var method = Arrays.stream(Class.forName("android.app.IActivityManager").getMethods())
            .filter(m -> m.getName().equals("broadcastIntentWithFeature"))
            .findFirst().orElse(null);
        if (method == null) {
            Log.w(TAG, "broadcastIntentWithFeature not found");
            return null;
        }
        var types = Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).toArray();
        if (!Arrays.equals(types, SIGNATURE)) {
            Log.w(TAG, "unexpected broadcastIntentWithFeature " + Arrays.toString(types));
            return null;
        }
        var field = Class.forName("android.app.IActivityManager$Stub")
            .getDeclaredField("TRANSACTION_broadcastIntentWithFeature");
        field.setAccessible(true);

        var intent = new Intent(NativeDisplay.BINDER_BROADCAST_ACTION).setPackage(pkg);
        var empty = marshall(intent);
        intent.putExtra("profile_marker", "extras");
        var marker = marshall(intent);
        // Vendor Intent fields may precede extras: find the slot by diff, not by field count.
        int offset = 0;
        while (offset < empty.length && empty[offset] == marker[offset]) offset++;
        offset &= ~3;

        var nested = new Bundle();
        nested.putBinder(NativeDisplay.EXTRA_BINDER, null);
        nested.putString(NativeDisplay.EXTRA_NONCE, "0123456789abcdef0123456789abcdef");
        var bundle = marshallBundle(nested);
        int end = 8 + (bundle[0] & 255 | (bundle[1] & 255) << 8 | (bundle[2] & 255) << 16 | bundle[3] << 24);

        var profile = new JSONObject();
        profile.put("sdk", Build.VERSION.SDK_INT);
        profile.put("broadcast_transaction", field.getInt(null));
        profile.put("extras_offset", offset);
        profile.put("empty_intent_hex", hex(empty));
        profile.put("bundle_trailer_hex", hex(Arrays.copyOfRange(bundle, end, bundle.length)));
        profile.put("target_package", pkg);
        Log.i(TAG, "captured: transaction=" + field.getInt(null) + " extras_offset=" + offset
            + " trailer=" + profile.getString("bundle_trailer_hex"));
        return profile;
    }

    @NonNull
    private static byte[] marshall(@NonNull Intent intent) {
        var parcel = Parcel.obtain();
        try {
            intent.writeToParcel(parcel, 0);
            return parcel.marshall();
        } finally {
            parcel.recycle();
        }
    }

    @NonNull
    private static byte[] marshallBundle(@NonNull Bundle bundle) {
        var parcel = Parcel.obtain();
        try {
            bundle.writeToParcel(parcel, 0);
            return parcel.marshall();
        } finally {
            parcel.recycle();
        }
    }

    @NonNull
    private static String hex(@NonNull byte[] bytes) {
        var out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
}
