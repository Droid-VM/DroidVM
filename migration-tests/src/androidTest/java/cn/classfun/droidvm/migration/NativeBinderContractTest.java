// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import cn.classfun.droidvm.display.INativeDisplayRootService;
import cn.classfun.droidvm.display.IPhysicalKeyEcho;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class NativeBinderContractTest {
    private static final String ACTION = "cn.classfun.droidvm.action.NATIVE_DISPLAY_BINDER";
    private static final String SCREEN = "screen-迁移";
    private static final byte[] INPUT = {0, 1, 0x7f, (byte) 0x80, (byte) 0xff, 0, 0, 1};

    private void checkProfile(Context context) throws Exception {
        JSONObject profile;
        try (var input = context.getAssets().open("warsaw-api37.json")) {
            profile = new JSONObject(new String(input.readAllBytes(), UTF_8));
        }
        assertEquals("Unverified firmware", profile.getString("fingerprint"), Build.FINGERPRINT);
        assertEquals(profile.getInt("sdk"), Build.VERSION.SDK_INT);
        var intent = new Intent(ACTION).setPackage(context.getPackageName());
        var parcel = Parcel.obtain();
        try {
            intent.writeToParcel(parcel, 0);
            var hex = new StringBuilder();
            for (byte value : parcel.marshall()) hex.append(String.format("%02x", value & 255));
            assertEquals("Intent layout changed", profile.getString("empty_intent_hex"), hex.toString());
            intent.putExtra("profile_marker", "extras");
            parcel.setDataPosition(0);
            parcel.setDataSize(0);
            intent.writeToParcel(parcel, 0);
            assertEquals("Bundle slot changed", profile.getString("marker_intent_hex"),
                PlatformProfileTest.toHex(parcel.marshall()));
            intent.removeExtra("profile_marker");
            intent.putExtra("extra.bundle", PlatformProfileTest.nestedSample());
            parcel.setDataPosition(0);
            parcel.setDataSize(0);
            intent.writeToParcel(parcel, 0);
            assertEquals("Nested Bundle layout changed", profile.getString("nested_intent_hex"),
                PlatformProfileTest.toHex(parcel.marshall()));
            assertEquals("Bundle trailer changed", profile.getString("bundle_trailer_hex"),
                PlatformProfileTest.bundleTrailer());
        } finally {
            parcel.recycle();
        }
    }

    @Test public void nativeBroadcastCallsCallbacksAndBidirectionalDeath() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var nonce = InstrumentationRegistry.getArguments().getString("probe_nonce");
        Assume.assumeNotNull(nonce); // Requires the paired native probe; see the host runner.
        assertTrue(nonce.matches("[0-9a-f]{32}"));
        var context = instrumentation.getContext();
        checkProfile(context);
        var delivered = new ArrayBlockingQueue<IBinder>(1);
        var receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                var bundle = intent.getBundleExtra("extra.bundle");
                if (bundle != null && nonce.equals(bundle.getString("nonce"))) {
                    var binder = bundle.getBinder("binder");
                    if (binder != null) delivered.offer(binder);
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
        var tokenQueue = new ArrayBlockingQueue<IBinder>(1);
        var connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { tokenQueue.offer(binder); }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        boolean bound = false;
        try {
            var ready = new Bundle();
            ready.putString("probe_ready", nonce);
            instrumentation.sendStatus(2, ready);
            var binder = delivered.poll(30, TimeUnit.SECONDS);
            assertNotNull("Native Binder broadcast not delivered", binder);
            assertEquals(INativeDisplayRootService.DESCRIPTOR, binder.getInterfaceDescriptor());
            assertNull("Must cross a process boundary", binder.queryLocalInterface(INativeDisplayRootService.DESCRIPTOR));
            var nativeDied = new CountDownLatch(1);
            binder.linkToDeath(nativeDied::countDown, 0);
            var broker = INativeDisplayRootService.Stub.asInterface(binder);
            assertEquals(binder, broker.waitForDisplayBinder(nonce));
            assertNull(broker.waitForDisplayBinder("missing"));
            assertTrue(broker.writeInput(nonce, SCREEN, 3, INPUT));
            assertFalse(broker.writeInput(nonce, "other-screen", 3, INPUT));
            assertFalse(broker.writeInput(nonce, SCREEN, -1, new byte[0]));
            assertEquals(0, broker.setKeyboardGrab(nonce, "", false, null));

            var callbacks = new CountDownLatch(2);
            var codes = new AtomicReference<int[]>();
            var values = new AtomicReference<int[]>();
            var leds = new AtomicReference<int[]>();
            broker.setKeyEcho(nonce, SCREEN, new IPhysicalKeyEcho.Stub() {
                @Override public void onKeys(int[] keys, int[] states) {
                    codes.set(keys); values.set(states); callbacks.countDown();
                }
                @Override public void onLeds(int known, int on) {
                    leds.set(new int[]{known, on}); callbacks.countDown();
                }
            });
            assertTrue("Missing native oneway callback", callbacks.await(5, TimeUnit.SECONDS));
            assertArrayEquals(new int[]{1, 30, 125}, codes.get());
            assertArrayEquals(new int[]{1, 0, 1}, values.get());
            assertArrayEquals(new int[]{7, 2}, leds.get());
            broker.setKeyEcho(nonce, SCREEN, null);

            bound = context.bindService(new Intent(context, DeathTokenService.class), connection, Context.BIND_AUTO_CREATE);
            assertTrue(bound);
            var token = tokenQueue.poll(5, TimeUnit.SECONDS);
            assertNotNull("Disposable token service did not bind", token);
            assertEquals(2, broker.setKeyboardGrab(nonce, SCREEN, true, token));
            var data = Parcel.obtain();
            var reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DeathTokenService.DESCRIPTOR);
                assertTrue(token.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0));
                reply.readException();
            } finally {
                data.recycle(); reply.recycle();
            }
            var deadline = SystemClock.elapsedRealtime() + 5000;
            boolean clientDied = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (broker.writeInput(nonce, "", -10, new byte[0])) { clientDied = true; break; }
                SystemClock.sleep(25);
            }
            assertTrue("Native death recipient did not observe Java token death", clientDied);
            try {
                assertTrue(broker.writeInput(nonce, "", -11, new byte[0]));
            } catch (DeadObjectException expected) {
                // The fixture may exit before its last synchronous reply is delivered.
            }
            assertTrue("Java did not observe native process death", nativeDied.await(5, TimeUnit.SECONDS));
        } finally {
            if (bound) context.unbindService(connection);
            context.unregisterReceiver(receiver);
        }
    }
}
