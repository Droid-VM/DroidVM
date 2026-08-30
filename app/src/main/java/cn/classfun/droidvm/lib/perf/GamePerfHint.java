// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.perf;

import android.app.GameManager;
import android.app.GameState;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Declares a heavy, uninterruptible 3D workload to the platform while a VM display is in the
 * foreground, so the device's own power policy raises CPU/GPU clocks -- the sanctioned way.
 *
 * <p>Why this exists: the Adreno {@code msm-adreno-tz} governor parks the GPU at its minimum clock
 * under the bursty, latency-coupled gfxstream render pattern. Measured on an 8 Elite: a guest 3D
 * workload registers only ~55% GPU busy at 160MHz (of 1100MHz), so the throughput-oriented
 * governor never ramps up -- the GPU runs ~7x slower than it could, and a guest benchmark scores
 * ~1800 instead of ~3900. Writing {@code /sys/class/kgsl/kgsl-3d0/devfreq/min_freq} fixes it, but
 * that needs root and leaves a device-wide clock override that must be restored by hand. The
 * platform path is a *declaration* instead: {@code android:appCategory="game"} in the manifest
 * plus the {@link GameState} below, which feeds the OEM's game power profile.
 *
 * <p>Note on ADPF: the finer-grained {@link android.os.PerformanceHintManager} is deliberately not
 * used here. It only accepts thread ids owned by the caller's uid, but crosvm is spawned by the
 * root daemon (uid 0) while this code runs in the normal app process, so its threads cannot be
 * registered. {@code GameState} is a device-level declaration, so the root-owned crosvm process
 * still benefits from it. (A future option is for crosvm itself to open an ADPF session over its
 * own render threads and report real frame durations, which is where per-frame accuracy would
 * come from.)
 */
public final class GamePerfHint {
    private static final String TAG = "GamePerfHint";

    private GamePerfHint() {
    }

    /** Declares sustained heavy gameplay (a VM display is in the foreground and rendering). */
    public static void enterGameplay(@NonNull Context context) {
        setState(context, GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE, "gameplay");
    }

    /** Clears the declaration when no VM display is in the foreground anymore. */
    public static void exitGameplay(@NonNull Context context) {
        setState(context, GameState.MODE_NONE, "none");
    }

    private static void setState(@NonNull Context context, int mode, @NonNull String what) {
        // GameState landed in API 33, which is also our minSdk; keep the guard so a lower-API
        // build (or a stripped OEM image without the service) degrades to a no-op.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            var manager = context.getSystemService(GameManager.class);
            if (manager == null) return;
            manager.setGameState(new GameState(false, mode));
        } catch (Exception e) {
            // Not fatal: without it we simply run at whatever clocks the governor picks.
        }
    }
}
