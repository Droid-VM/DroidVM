// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.data;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;

/**
 * The host cameras a camera peripheral can be pinned to.
 *
 * <p>Unlike {@link HostAudioDevices}, the key stored in the VM config is the platform's own camera
 * id, because that one is already stable: AudioDeviceInfo ids are handed out per boot, camera ids
 * are a property of the device. The label is kept alongside it only so a row can still name a
 * camera the current phone does not have -- a config copied between phones, or an external USB
 * camera that is unplugged.</p>
 *
 * <p>Enumeration needs no CAMERA permission (the platform lets any uid read characteristics;
 * measured on device), so the picker can be populated before the grant is asked for. Opening one
 * does need it, and needs the uid to be foreground besides -- see {@code CameraPermission} and
 * {@code PeripheralType.needsForegroundService}.</p>
 */
public final class HostCameraDevices {
    private static final String TAG = "HostCameraDevices";

    /** "let the host pick", stored when no particular camera was chosen. */
    public static final String DEFAULT_KEY = "";

    public static final class Entry {
        /** Platform camera id, stored in the VM config. */
        public final String key;
        /** Human-readable name for the picker. */
        public final String label;
        /** {@link CameraCharacteristics#LENS_FACING_FRONT} and friends, -1 when unknown. */
        public final int facing;

        Entry(@NonNull String key, @NonNull String label, int facing) {
            this.key = key;
            this.label = label;
            this.facing = facing;
        }
    }

    private HostCameraDevices() {
    }

    /** Every camera the platform reports, in its own order. Empty when CameraManager is
     *  unreachable, which the picker shows as "no camera on this host". */
    @NonNull
    public static List<Entry> list(@NonNull Context context) {
        var out = new ArrayList<Entry>();
        var manager = context.getSystemService(CameraManager.class);
        if (manager == null) return out;
        try {
            for (var id : manager.getCameraIdList()) {
                int facing = -1;
                try {
                    var facingValue = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                    if (facingValue != null) facing = facingValue;
                } catch (Exception e) {
                    // A camera the platform lists but will not describe is usually one this uid
                    // may not touch. Keep it in the list under its id rather than dropping it:
                    // the guest may still be able to open it, and a missing row looks like a bug.
                    Log.w(TAG, fmt("characteristics for camera %s unavailable", id), e);
                }
                out.add(new Entry(id, labelFor(context, id, facing), facing));
            }
        } catch (Exception e) {
            Log.w(TAG, "camera enumeration failed", e);
        }
        return out;
    }

    /** The label to show for a stored key, falling back to the stored label for a camera this
     *  host does not have. */
    @NonNull
    public static String labelOf(@NonNull Context context, @Nullable String key,
                                 @NonNull String storedLabel) {
        if (key == null || key.isEmpty()) return context.getString(R.string.host_camera_default);
        for (var entry : list(context)) {
            if (entry.key.equals(key)) return entry.label;
        }
        return storedLabel.isEmpty() ? key : storedLabel;
    }

    @NonNull
    private static String labelFor(@NonNull Context context, @NonNull String id, int facing) {
        int nameId;
        switch (facing) {
            case CameraCharacteristics.LENS_FACING_FRONT:
                nameId = R.string.host_camera_front;
                break;
            case CameraCharacteristics.LENS_FACING_BACK:
                nameId = R.string.host_camera_back;
                break;
            case CameraCharacteristics.LENS_FACING_EXTERNAL:
                nameId = R.string.host_camera_external;
                break;
            default:
                return context.getString(R.string.host_camera_unknown, id);
        }
        return context.getString(nameId, id);
    }
}
