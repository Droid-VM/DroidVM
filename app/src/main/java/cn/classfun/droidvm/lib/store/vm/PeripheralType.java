// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import android.content.pm.ServiceInfo;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * Kind of virtual peripheral attached to a VM -- one entry here is one device the guest sees.
 *
 * <p>The list deliberately names hardware rather than roles. An earlier version offered
 * "Speaker" and "Microphone", which reads well but does not survive contact with the devices:
 * a virtio-snd card is one direction with one host endpoint, while an Intel HDA codec is a
 * single card carrying both. Anything that maps roles onto devices has to guess, and the guess
 * is wrong for one of the two. Naming the device and putting the role inside it keeps the UI
 * and the command line the same shape.</p>
 */
public enum PeripheralType implements StringEnum {
    /** virtio-snd, one PCM direction per device. Served by an unprivileged vhost-user helper. */
    VIRTIO_SOUND(R.string.edit_vm_peripheral_type_virtio_sound, R.drawable.ic_speaker, true, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE),
    /**
     * Intel HD Audio codec: one card, playback and capture together. Present so the model is
     * honest about what a guest could have -- Windows has an in-box driver for it, which
     * virtio-snd does not -- but crosvm emulates no HDA controller, so nothing can serve it yet.
     */
    INTEL_HDA(R.string.edit_vm_peripheral_type_intel_hda, R.drawable.ic_microphone, false, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE),
    /**
     * virtio-media capture device: one host camera, seen by the guest as one {@code /dev/videoX}.
     *
     * <p>One entry is one camera, unlike the sound card above. That is the driver's shape rather
     * than a UI choice: a virtio-media device registers exactly one {@code video_device} whose
     * capabilities come from a single config word, so a second camera is a second device. A VM
     * that wants front and back carries two of these.</p>
     *
     * <p>Unavailable until crosvm carries the device; the host half (Camera2 NDK through
     * {@code android_camera}) exists, the virtio-media capture device on top of it does not.</p>
     */
    VIRTIO_CAMERA(R.string.edit_vm_peripheral_type_virtio_camera, R.drawable.ic_camera, false, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);

    private final @StringRes int titleId;
    private final @DrawableRes int iconId;
    private final boolean available;
    private final int foregroundServiceType;

    PeripheralType(@StringRes int titleId, @DrawableRes int iconId, boolean available,
                   int foregroundServiceType) {
        this.titleId = titleId;
        this.iconId = iconId;
        this.available = available;
        this.foregroundServiceType = foregroundServiceType;
    }

    @Override
    public int getStringId() {
        return titleId;
    }

    @DrawableRes
    public int getIconId() {
        return iconId;
    }

    /** False when nothing on the host can serve this device yet; the UI says so and the
     *  backends skip it rather than starting a VM that lies about its hardware. */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Whether a running VM carrying this device needs the app to hold a foreground service.
     *
     * <p>Some host APIs are only open to a uid the platform considers foreground, and the state
     * is a property of the <em>uid</em>, not of the process that calls: crosvm is forked by the
     * root daemon and ActivityManager does not know it exists, so nothing it does can put its uid
     * in that state. Only a process ActivityManager manages -- the app's own -- can, and a
     * foreground service is how it does so without a visible activity.</p>
     *
     * <p>Camera is the first such device: {@code CAMERA} is a foreground-only runtime permission,
     * so AppOps resolves it to MODE_IGNORED unless the uid carries
     * {@code PROCESS_CAPABILITY_FOREGROUND_CAMERA}, which comes from a foreground service typed
     * {@code camera}. Microphone works the same way, through
     * {@code PROCESS_CAPABILITY_FOREGROUND_MICROPHONE}, and will want a type here once anyone
     * checks whether guest capture survives the app going background.</p>
     *
     * <p>A type rather than a yes/no, because a foreground service has to declare which kind it
     * is and the two above need different ones -- a boolean would leave the service guessing.
     * Naming the type here is still the whole switch: nothing else tests for a device kind, and
     * the service unions the types of whatever is running.</p>
     */
    public int getForegroundServiceType() {
        return foregroundServiceType;
    }

    /** Convenience for the common question; the type is the source of truth. */
    public boolean needsForegroundService() {
        return foregroundServiceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
    }

    /** The service types {@code types} need together, or 0 when none do. */
    public static int foregroundServiceTypesOf(@NonNull Iterable<PeripheralType> types) {
        int mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
        for (var type : types) mask |= type.getForegroundServiceType();
        return mask;
    }
}
