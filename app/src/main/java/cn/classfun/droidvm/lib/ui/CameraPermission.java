// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;

/**
 * Asks for CAMERA before a VM is given a camera peripheral, and refuses to add one without it.
 *
 * <p>Unlike {@link RecordAudioPermission}, which runs its action whether or not the user agrees,
 * a denial here stops the peripheral being added. That is not a policy preference, it is what the
 * platform does: CAMERA is a foreground-only runtime permission, so without the grant AppOps
 * resolves the camera op to MODE_IGNORED and {@code ACameraManager_openCamera} fails with
 * ERROR_CAMERA_DISABLED -- measured, not assumed. A camera device added without the grant is a
 * device that can only ever fail to open, and a VM whose config lists hardware it will never get
 * is exactly what {@code PeripheralType.isAvailable} exists to avoid.</p>
 *
 * <p>The grant is necessary but not sufficient: the uid also has to be in a foreground state when
 * the guest actually opens the camera, which is what the peripheral foreground service is for.
 * See {@code PeripheralType.needsForegroundService}.</p>
 *
 * <p>Construct from an activity's {@code onCreate}: the result launcher has to be registered
 * before the activity reaches STARTED.</p>
 */
public final class CameraPermission {
    private final AppCompatActivity activity;
    private final ActivityResultLauncher<String> launcher;
    private Runnable pending;

    public CameraPermission(@NonNull AppCompatActivity activity) {
        this.activity = activity;
        this.launcher = activity.registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                var action = pending;
                pending = null;
                if (granted && action != null) {
                    action.run();
                } else if (!granted) {
                    // Nothing was added; say so, because an add that quietly does nothing reads
                    // as a broken button rather than as a refusal being honoured.
                    Toast.makeText(activity, R.string.camera_permission_declined,
                        Toast.LENGTH_SHORT).show();
                }
            });
    }

    /**
     * Runs {@code action} only once the camera is allowed: immediately when the grant is already
     * held, after the user agrees otherwise, and never if they decline.
     *
     * <p>Straight to the platform dialog, with no rationale of our own in front of it. The
     * platform's own wording already says which app is asking and for what, so a rationale first
     * is two dialogs asking one question. It is shown only on a second attempt, where the user
     * has declined once and the platform will not ask again unaided.</p>
     */
    public void requireThen(@NonNull Runnable action) {
        if (granted()) {
            action.run();
            return;
        }
        pending = action;
        if (activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.camera_permission_title)
                .setMessage(R.string.camera_permission_message)
                .setPositiveButton(R.string.camera_permission_allow,
                    (d, w) -> launcher.launch(Manifest.permission.CAMERA))
                .setNegativeButton(R.string.camera_permission_cancel, (d, w) -> pending = null)
                .setOnCancelListener(d -> pending = null)
                .show();
            return;
        }
        launcher.launch(Manifest.permission.CAMERA);
    }

    public boolean granted() {
        return ContextCompat.checkSelfPermission(activity,
            Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
}
