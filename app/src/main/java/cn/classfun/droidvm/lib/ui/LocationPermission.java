// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Map;

import cn.classfun.droidvm.R;

/**
 * Asks for the phone's location before a VM is given a GNSS receiver, and refuses to add one
 * without it -- the {@link CameraPermission} rule, for the same reason: a receiver the app can
 * never feed is hardware the VM lists and never gets.
 *
 * <p>Precise and approximate are asked for together, the way Android wants them asked; either
 * one lets the row be added. Approximate is enough for a receiver fed from the phone's location
 * (rounded to a few kilometres by Android, and reported with that accuracy); the phone's GNSS
 * receiver needs precise, and with only approximate it stays without a fix -- said in a toast,
 * because the row cannot say it later.</p>
 *
 * <p>Only while-in-use location: the running VM's foreground service is what lets the app's uid
 * use it with no activity in front, so "all the time" is never needed.</p>
 *
 * <p>Construct from an activity's {@code onCreate}: the result launcher has to be registered
 * before the activity reaches STARTED.</p>
 */
public final class LocationPermission {
    private static final String[] PERMISSIONS = {
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    };

    private final AppCompatActivity activity;
    private final ActivityResultLauncher<String[]> launcher;
    private Runnable pending;

    public LocationPermission(@NonNull AppCompatActivity activity) {
        this.activity = activity;
        this.launcher = activity.registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), this::onResult);
    }

    private void onResult(@NonNull Map<String, Boolean> result) {
        var action = pending;
        pending = null;
        if (!isGranted(activity)) {
            Toast.makeText(activity, R.string.location_permission_declined,
                Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isPrecise(activity))
            Toast.makeText(activity, R.string.location_permission_approximate,
                Toast.LENGTH_LONG).show();
        if (action != null) action.run();
    }

    /**
     * Runs {@code action} only once location is allowed, precise or approximate: immediately when
     * it already is, after the user agrees otherwise, and never if they decline. The platform's
     * dialog first; a rationale of ours only on a second attempt, as {@link CameraPermission}.
     */
    public void requireThen(@NonNull Runnable action) {
        if (isGranted(activity)) {
            action.run();
            return;
        }
        pending = action;
        if (activity.shouldShowRequestPermissionRationale(
            Manifest.permission.ACCESS_FINE_LOCATION)) {
            new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.location_permission_title)
                .setMessage(R.string.location_permission_message)
                .setPositiveButton(R.string.location_permission_allow,
                    (d, w) -> launcher.launch(PERMISSIONS))
                .setNegativeButton(R.string.location_permission_cancel, (d, w) -> pending = null)
                .setOnCancelListener(d -> pending = null)
                .show();
            return;
        }
        launcher.launch(PERMISSIONS);
    }

    /** Whether the app may use the phone's location at all (precise or approximate). */
    public static boolean isGranted(@NonNull Context context) {
        return isPrecise(context) || ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Whether it may use precise location, which the phone's GNSS receiver needs. */
    public static boolean isPrecise(@NonNull Context context) {
        return ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}
