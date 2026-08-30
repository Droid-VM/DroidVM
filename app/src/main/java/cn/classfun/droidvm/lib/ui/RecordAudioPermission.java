// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
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
 * Asks for RECORD_AUDIO when a VM is given a microphone peripheral.
 *
 * <p>Whether this is what actually unlocks the mic is not certain: crosvm is forked by the root
 * daemon, so its capture streams reach AudioFlinger as uid 0, which is checked separately from
 * (and more leniently than) an app's runtime grant -- RECORD_AUDIO is an appops/uid permission,
 * not one of the few that map to a supplementary GID, so it cannot be handed to a child process
 * by putting it in a group. We ask anyway: it costs one dialog, it is what a user-visible
 * "this VM listens to your mic" ought to look like, and it is the prerequisite for the fallback
 * of proxying capture through an unprivileged app-side helper.</p>
 *
 * <p>Construct from an activity's {@code onCreate}: the result launcher has to be registered
 * before the activity reaches STARTED. The pending action runs either way -- a denied permission
 * is not a reason to refuse to save the config.</p>
 */
public final class RecordAudioPermission {
    private final AppCompatActivity activity;
    private final ActivityResultLauncher<String> launcher;
    private Runnable pending;

    public RecordAudioPermission(@NonNull AppCompatActivity activity) {
        this.activity = activity;
        this.launcher = activity.registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> runPending());
    }

    /**
     * Runs {@code action} immediately when the mic is already allowed; otherwise shows a
     * rationale, requests the permission, and runs it once the user has responded.
     */
    public void ensureThen(@NonNull Runnable action) {
        if (granted()) {
            action.run();
            return;
        }
        pending = action;
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.record_audio_permission_title)
            .setMessage(R.string.record_audio_permission_message)
            .setPositiveButton(R.string.record_audio_permission_allow,
                (d, w) -> launcher.launch(Manifest.permission.RECORD_AUDIO))
            .setNegativeButton(R.string.record_audio_permission_skip, (d, w) -> runPending())
            .setOnCancelListener(d -> runPending())
            .show();
    }

    private void runPending() {
        var action = pending;
        pending = null;
        if (action != null) action.run();
    }

    public boolean granted() {
        return ContextCompat.checkSelfPermission(activity,
            Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
}
