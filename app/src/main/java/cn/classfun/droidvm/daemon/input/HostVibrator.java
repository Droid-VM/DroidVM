// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.input;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import cn.classfun.droidvm.daemon.display.DaemonSystemContext;

/**
 * Plays the VMs' vibrators on the phone's motor.
 *
 * <p>What a guest sends its vibrator (crosvm {@code --input vibrator}) arrives here as 8-byte
 * evdev records: EV_FF, code FF_RUMBLE, value the strong magnitude in the high 16 bits and the weak
 * in the low, sent as each effect starts and stops (both 0 is stop). The guest does the timing
 * itself (Linux's memless force feedback), so this only follows the magnitude it is told: the
 * stronger of the two, since a phone has one motor. Several VMs rumbling at once get the strongest.</p>
 *
 * <p>Played from the daemon, which is root and whose vibrations the platform takes without a
 * package check, as {@link VibrationAttributes#USAGE_PHYSICAL_EMULATION}: what a game controller's
 * rumble is, and one of the usages the platform plays from a process in the background, which is
 * what the daemon always is. Each request is a one-shot of at most {@link #HOLD_MS}, so a guest that
 * stops talking mid-rumble (crashed, or its VM killed) cannot leave the motor running.</p>
 */
public final class HostVibrator {
    private static final String TAG = "HostVibrator";
    private static final HostVibrator INSTANCE = new HostVibrator();

    private static final int EV_FF = 0x15;
    private static final int FF_RUMBLE = 0x50;
    private static final int RECORD_BYTES = 8;
    /** The longest one request runs: a rumble the guest never stops ends by itself. */
    private static final long HOLD_MS = 5000;

    private static final VibrationAttributes ATTRIBUTES = new VibrationAttributes.Builder()
        .setUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION).build();

    /** Each rumbling VM's magnitude (0..0xffff); a VM that stopped is not in it. */
    private final Map<String, Integer> magnitudes = new HashMap<>();
    private int playing = 0;
    private boolean resolved;
    @Nullable
    private Vibrator vibrator;

    private HostVibrator() {
    }

    @NonNull
    public static HostVibrator get() {
        return INSTANCE;
    }

    /** What [vmId]'s guest sent its vibrator: whole evdev records. */
    public synchronized void onGuestStatus(@NonNull String vmId, @NonNull byte[] records) {
        var buffer = ByteBuffer.wrap(records).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.remaining() >= RECORD_BYTES) {
            int type = buffer.getShort() & 0xffff;
            int code = buffer.getShort() & 0xffff;
            int value = buffer.getInt();
            if (type != EV_FF || code != FF_RUMBLE) continue;
            int magnitude = Math.max(value >>> 16, value & 0xffff);
            if (magnitude == 0) magnitudes.remove(vmId);
            else magnitudes.put(vmId, magnitude);
        }
        apply();
    }

    /** [vmId] has stopped: whatever it was playing stops with it. */
    public synchronized void release(@NonNull String vmId) {
        if (magnitudes.remove(vmId) != null) apply();
    }

    private void apply() {
        int magnitude = 0;
        for (int m : magnitudes.values()) magnitude = Math.max(magnitude, m);
        if (magnitude == playing) return;
        playing = magnitude;
        var motor = motor();
        if (motor == null) return;
        try {
            if (magnitude == 0) {
                motor.cancel();
                return;
            }
            int amplitude = motor.hasAmplitudeControl()
                ? Math.max(1, Math.min(255, Math.round(magnitude * 255f / 0xffff)))
                : VibrationEffect.DEFAULT_AMPLITUDE;
            motor.vibrate(VibrationEffect.createOneShot(HOLD_MS, amplitude), ATTRIBUTES);
        } catch (RuntimeException e) {
            Log.w(TAG, "the phone's vibrator refused", e);
        }
    }

    /** The phone's vibrator, or null when it has none; looked up once. */
    @Nullable
    private Vibrator motor() {
        if (resolved) return vibrator;
        var context = DaemonSystemContext.get();
        if (context == null) return null;
        resolved = true;
        var manager = context.getSystemService(VibratorManager.class);
        var found = manager == null ? null : manager.getDefaultVibrator();
        if (found == null || !found.hasVibrator()) {
            Log.i(TAG, "the phone has no vibrator; VM vibrators play nothing");
            return null;
        }
        Log.i(TAG, fmt("playing VM vibrators on the phone's motor (amplitude control %s)",
            found.hasAmplitudeControl()));
        vibrator = found;
        return vibrator;
    }
}
