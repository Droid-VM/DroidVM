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
 *
 * <p>A VM's trackpad (crosvm {@code trackpad[haptics=true]}) plays haptic feedback here too, for
 * the guest drivers written for it (a Windows Precision Touchpad's haptics, a macOS guest's
 * trackpad actuator): EV_FF, the code a waveform of the HID Haptics page by the low byte of its
 * usage, the value its intensity in percent (bits 0-7, 0 for full), how many times more to play it
 * (bits 8-15) and the milliseconds from one start to the next (bits 16-31). A click, a press and a
 * release are the motor's own short effects (Android's haptic primitives where it has them); the
 * continuous ones join the rumbles, stopped by a stop, the strongest of everything winning. A click
 * while something rumbles is dropped: replacing the rumble with it would cut the rumble short.</p>
 */
public final class HostVibrator {
    private static final String TAG = "HostVibrator";
    private static final HostVibrator INSTANCE = new HostVibrator();

    private static final int EV_FF = 0x15;
    private static final int FF_RUMBLE = 0x50;
    private static final int RECORD_BYTES = 8;
    /** The longest one request runs: a rumble the guest never stops ends by itself. */
    private static final long HOLD_MS = 5000;

    // The trackpad's haptic waveforms: the HID Haptics page's (0x0e) usages 0x1002..0x1007 by their
    // low byte, as crosvm declares them (devices/src/virtio/input/defaults.rs, TRACKPAD_HAPTIC_*).
    private static final int HAPTIC_STOP = 0x02;
    private static final int HAPTIC_CLICK = 0x03;
    private static final int HAPTIC_BUZZ = 0x04;
    private static final int HAPTIC_RUMBLE = 0x05;
    private static final int HAPTIC_PRESS = 0x06;
    private static final int HAPTIC_RELEASE = 0x07;
    /** A VM's trackpad among the rumbles: its own key beside the VM's vibrator. */
    private static final String TRACKPAD_KEY = "/trackpad";
    /** A click without the primitives, as a one-shot: about what one of them lasts. */
    private static final int CLICK_MS = 12;
    private static final int MAX_REPEATS = 20;
    private static final int DEFAULT_PERIOD_MS = 100;
    private static final int MAX_PERIOD_MS = 1000;

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

    /** What [vmId]'s guest sent its trackpad: whole evdev records of haptic feedback. */
    public synchronized void onTrackpadStatus(@NonNull String vmId, @NonNull byte[] records) {
        var buffer = ByteBuffer.wrap(records).order(ByteOrder.LITTLE_ENDIAN);
        boolean rumbles = false;
        while (buffer.remaining() >= RECORD_BYTES) {
            int type = buffer.getShort() & 0xffff;
            int code = buffer.getShort() & 0xffff;
            int value = buffer.getInt();
            if (type != EV_FF) continue;
            int intensity = value & 0xff;
            float scale = intensity == 0 ? 1f : Math.min(100, intensity) / 100f;
            switch (code) {
                case HAPTIC_STOP:
                    rumbles |= magnitudes.remove(vmId + TRACKPAD_KEY) != null;
                    break;
                case HAPTIC_BUZZ:
                case HAPTIC_RUMBLE:
                    magnitudes.put(vmId + TRACKPAD_KEY, Math.max(1, Math.round(scale * 0xffff)));
                    rumbles = true;
                    break;
                case HAPTIC_CLICK:
                case HAPTIC_PRESS:
                case HAPTIC_RELEASE:
                    int repeats = Math.min(MAX_REPEATS, (value >>> 8) & 0xff);
                    int period = value >>> 16;
                    period = period == 0 ? DEFAULT_PERIOD_MS : Math.min(MAX_PERIOD_MS, period);
                    Log.d(TAG, fmt("%s's trackpad: waveform %#x at %.2f, %d more every %d ms",
                        vmId, code, scale, repeats, period));
                    click(code == HAPTIC_RELEASE, scale, repeats, period);
                    break;
                default:
                    break;
            }
        }
        if (rumbles) apply();
    }

    /** [vmId] has stopped: whatever it was playing stops with it. */
    public synchronized void release(@NonNull String vmId) {
        boolean was = magnitudes.remove(vmId) != null;
        was |= magnitudes.remove(vmId + TRACKPAD_KEY) != null;
        if (was) apply();
    }

    /**
     * One click, press or release, [repeats] more times [periodMs] apart. A release is the lighter
     * tick a haptic touchpad gives as the finger lets go; a click and a press the full click.
     */
    private void click(boolean release, float scale, int repeats, int periodMs) {
        if (playing != 0) return;
        var motor = motor();
        if (motor == null) return;
        int primitive = release ? VibrationEffect.Composition.PRIMITIVE_TICK
            : VibrationEffect.Composition.PRIMITIVE_CLICK;
        VibrationEffect effect;
        if (motor.areAllPrimitivesSupported(primitive)) {
            var composition = VibrationEffect.startComposition();
            for (int i = 0; i <= repeats; i++)
                composition.addPrimitive(primitive, scale, i == 0 ? 0 : periodMs);
            effect = composition.compose();
        } else {
            int amplitude = motor.hasAmplitudeControl()
                ? Math.max(1, Math.round(scale * (release ? 160 : 255)))
                : VibrationEffect.DEFAULT_AMPLITUDE;
            var timings = new long[2 * (repeats + 1)];
            var amplitudes = new int[timings.length];
            for (int i = 0; i <= repeats; i++) {
                timings[2 * i] = i == 0 ? 0 : Math.max(0, periodMs - CLICK_MS);
                timings[2 * i + 1] = CLICK_MS;
                amplitudes[2 * i + 1] = amplitude;
            }
            effect = VibrationEffect.createWaveform(timings, amplitudes, -1);
        }
        try {
            motor.vibrate(effect, ATTRIBUTES);
        } catch (RuntimeException e) {
            Log.w(TAG, "the phone's vibrator refused", e);
        }
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
