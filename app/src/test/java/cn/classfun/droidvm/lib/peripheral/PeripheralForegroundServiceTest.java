// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.peripheral;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import cn.classfun.droidvm.BuildConfig;

/**
 * How the peripheral foreground service is addressed -- defect D11.
 *
 * <p>The service used to be reached with {@code new Intent(context, PeripheralForegroundService
 * .class)} from the root daemon, and {@code Intent(Context, Class)} takes the package from
 * {@code context.getPackageName()}. The daemon's Context is the system Context, so the intent
 * named {@code android/cn.classfun.droidvm.lib.peripheral.PeripheralForegroundService} -- a
 * component in a package that does not contain it -- and the camera service was never raised.
 *
 * <p>What can be asserted off a device is the half that is arithmetic on two strings, which is
 * exactly the half that was wrong. The rest of {@code apply} -- the ActivityManager call with a
 * null caller -- is a platform call and is tested on the phone.</p>
 */
public final class PeripheralForegroundServiceTest {
    private static final String CLASS_NAME =
        "cn.classfun.droidvm.lib.peripheral.PeripheralForegroundService";

    /** The class half of the component is the service's own name, whoever asks. */
    @Test
    public void theComponentNamesThisService() {
        assertEquals("cn.classfun.droidvm/" + CLASS_NAME,
            PeripheralForegroundService.componentString("cn.classfun.droidvm"));
    }

    /** The bug itself: the caller's package must never end up in the component. */
    @Test
    public void theSystemContextsPackageIsNotWhereTheServiceLives() {
        assertNotEquals(PeripheralForegroundService.componentString("android"),
            PeripheralForegroundService.componentString(BuildConfig.APPLICATION_ID));
    }

    /** And the package half, which is what {@code apply} actually puts on the intent. */
    @Test
    public void theServiceIsAddressedInTheAppsOwnPackage() {
        assertEquals(BuildConfig.APPLICATION_ID + "/" + CLASS_NAME,
            PeripheralForegroundService.componentString(BuildConfig.APPLICATION_ID));
        assertEquals("cn.classfun.droidvm", BuildConfig.APPLICATION_ID);
    }
}
