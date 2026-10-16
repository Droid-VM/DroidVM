// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import cn.classfun.droidvm.display.INativeDisplayRootService;
import cn.classfun.droidvm.display.IPhysicalKeyEcho;

import org.junit.Assume;
import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** The native daemon's display_attach broker, driven by the host runner over TCP. */
public final class DaemonDisplayBrokerTest {
    private static final String ACTION = "cn.classfun.droidvm.action.NATIVE_DISPLAY_BINDER";

    private static void status(String key, String value) {
        var bundle = new Bundle();
        bundle.putString(key, value);
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle);
    }

    @Test public void brokerServesDisplayInputAndKeyboardGrab() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        var nonce = args.getString("broker_nonce");
        Assume.assumeNotNull(nonce); // Requires the host runner and a test daemon.
        var running = args.getString("running_vm");
        var stopped = args.getString("stopped_vm");
        assertNotNull(running);
        assertNotNull(stopped);
        var context = InstrumentationRegistry.getInstrumentation().getContext();
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
        var tokens = new ArrayBlockingQueue<IBinder>(1);
        var connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { tokens.offer(binder); }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        boolean bound = false;
        try {
            status("broker_ready", nonce);
            var binder = delivered.poll(30, TimeUnit.SECONDS);
            assertNotNull("display_attach broadcast not delivered", binder);
            assertEquals(INativeDisplayRootService.DESCRIPTOR, binder.getInterfaceDescriptor());
            assertNull(binder.queryLocalInterface(INativeDisplayRootService.DESCRIPTOR));
            var broker = INativeDisplayRootService.Stub.asInterface(binder);

            // A stopped VM never registers: answered at once, not after the 5 s poll.
            var started = SystemClock.elapsedRealtime();
            assertNull(broker.waitForDisplayBinder("droidvm_disp_" + stopped + "_simplefb"));
            assertTrue("stopped VM lookup waited", SystemClock.elapsedRealtime() - started < 2000);
            started = SystemClock.elapsedRealtime();
            assertNull(broker.waitForDisplayBinder("droidvm_test_missing"));
            assertTrue("unknown name polls about five seconds", SystemClock.elapsedRealtime() - started >= 4500);
            var display = broker.waitForDisplayBinder("droidvm_disp_" + running + "_simplefb");
            assertNotNull("running VM display binder not returned", display);
            CrosvmDisplayContract.verify(display);

            var event = new byte[24];
            assertTrue(broker.writeInput(running, "simplefb", 2, event));
            assertFalse(broker.writeInput(stopped, "simplefb", 2, event));
            assertFalse(broker.writeInput(running, "simplefb", 2, new byte[0]));
            assertFalse(broker.writeInput("", "simplefb", 2, event));
            assertEquals(0, broker.setKeyboardGrab(running, "", false, null));
            broker.setKeyEcho(running, "simplefb", new IPhysicalKeyEcho.Stub() {
                @Override public void onKeys(int[] keys, int[] states) { }
                @Override public void onLeds(int known, int on) { }
            });
            broker.setKeyEcho(running, "simplefb", null);

            bound = context.bindIsolatedService(new Intent(context, DeathTokenService.class),
                Context.BIND_AUTO_CREATE, nonce, context.getMainExecutor(), connection);
            assertTrue(bound);
            var token = tokens.poll(5, TimeUnit.SECONDS);
            assertNotNull(token);
            // No keyboard is attached: armed with nothing held.
            assertEquals(0, broker.setKeyboardGrab(running, "simplefb", true, token));
            status("token_grab", "armed");
            SystemClock.sleep(3000); // the host reads kbd_status while armed
            var data = Parcel.obtain();
            var reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DeathTokenService.DESCRIPTOR);
                assertTrue(token.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0));
                reply.readException();
            } finally {
                data.recycle();
                reply.recycle();
            }
            SystemClock.sleep(2000);
            status("token_grab", "dead");
            SystemClock.sleep(3000); // the host reads kbd_status after the death
        } finally {
            if (bound) context.unbindService(connection);
            context.unregisterReceiver(receiver);
        }
    }
}
