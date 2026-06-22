package cn.classfun.droidvm.lib.ui;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;

/**
 * Asks for the runtime POST_NOTIFICATIONS permission (required since Android 13,
 * the app's minSdk) at the moment a background download is about to start,
 * after a short rationale. The download proceeds either way -- the permission
 * only controls whether the foreground-service progress notification is shown.
 *
 * Construct from an activity's {@code onCreate}: the result launcher has to be
 * registered before the activity reaches STARTED.
 */
public final class NotificationPermission {
    private final AppCompatActivity activity;
    private final ActivityResultLauncher<String> launcher;
    private Runnable pending;

    public NotificationPermission(@NonNull AppCompatActivity activity) {
        this.activity = activity;
        this.launcher = activity.registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> runPending());
    }

    /**
     * Runs {@code action} immediately when notifications are already allowed;
     * otherwise shows a rationale, requests the permission, and runs it once the
     * user has responded (whether or not they granted it).
     */
    public void ensureThen(@NonNull Runnable action) {
        if (granted()) {
            action.run();
            return;
        }
        pending = action;
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.notification_permission_title)
            .setMessage(R.string.notification_permission_message)
            .setPositiveButton(R.string.notification_permission_allow,
                (d, w) -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS))
            .setNegativeButton(R.string.notification_permission_skip, (d, w) -> runPending())
            .setOnCancelListener(d -> runPending())
            .show();
    }

    private void runPending() {
        var action = pending;
        pending = null;
        if (action != null) action.run();
    }

    private boolean granted() {
        return ContextCompat.checkSelfPermission(activity,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }
}
