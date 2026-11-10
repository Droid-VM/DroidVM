// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.gnss;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.daemon.vm.PeripheralForegroundControl;
import cn.classfun.droidvm.lib.gnss.GnssFix;
import cn.classfun.droidvm.lib.gnss.GnssSatellite;
import cn.classfun.droidvm.lib.gnss.Nmea;
import cn.classfun.droidvm.lib.store.vm.GnssSource;

/**
 * Turns what the app's feeder reports into every running VM's NMEA, once a second.
 *
 * <p>Two streams come in, and each port takes the one its row resolved to. The receiver's fixes
 * are written as they arrive, stamped with their own GNSS time, so the guest sees the phone's
 * epochs rather than this thread's; a second with none (no fix yet, a tunnel, the feeder not
 * running) is filled with a no-fix epoch carrying whatever satellites are in view. The location
 * stream is reported every second as of now, from the newest location for as long as it is
 * {@link #LOCATION_HOLD_MS} old: a fused provider updates when it has something new, not on a
 * clock, and a receiver that blinked out between updates would be a worse lie than a position
 * held a few seconds.</p>
 *
 * <p>One thread for every port, created with the first and stopped with the last.</p>
 */
public final class GnssHub {
    private static final String TAG = "GnssHub";
    private static final GnssHub INSTANCE = new GnssHub();

    private static final long TICK_MS = 1000;
    /** A receiver second with no epoch in it this long is filled with a no-fix one. */
    private static final long GAP_MS = 1500;
    /** Satellites older than this are not reported: the receiver has stopped telling us. */
    private static final long SKY_STALE_MS = 3000;
    /** The longest a location-only receiver keeps reporting the last position. */
    private static final long LOCATION_HOLD_MS = 60_000;
    /** ...and the longest it keeps that position's speed and course, which age much faster. */
    private static final long MOTION_HOLD_MS = 3000;
    /**
     * A feeder silent this long is gone (it beats every 5 s): the app's process was killed or
     * replaced, taking the foreground service with it, and nothing else would bring it back while
     * the VMs keep running. The service is raised again, then again at twice the interval each
     * time up to {@link #RERAISE_MAX_MS}, until the feeder speaks: one that never can (location
     * refused) must not be restarted every few seconds for the life of the VM.
     */
    private static final long FEEDER_LOST_MS = 15_000;
    private static final long RERAISE_MAX_MS = 300_000;

    private final List<GnssPort> ports = new ArrayList<>();
    private ScheduledExecutorService thread;

    // Newest of each, with the elapsedRealtime it was true at. Guarded by this. The receiver's
    // fixes are not kept: they are written as they come.
    private List<GnssSatellite> receiverSky = new ArrayList<>();
    private long receiverSkyAt = Long.MIN_VALUE / 2;
    private GnssFix locationFix;
    private long locationFixAt;
    private long lastFeedAt;
    private long lastReraiseAt;
    private long reraiseInterval = FEEDER_LOST_MS;

    private GnssHub() {
    }

    @NonNull
    public static GnssHub get() {
        return INSTANCE;
    }

    public synchronized void register(@NonNull GnssPort port) {
        ports.add(port);
        // The service is being raised for it right now; give that its time before missing it.
        lastFeedAt = SystemClock.elapsedRealtime();
        if (thread == null) {
            thread = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "GnssHub");
                t.setDaemon(true);
                return t;
            });
            thread.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void unregister(@NonNull GnssPort port) {
        ports.remove(port);
        if (ports.isEmpty() && thread != null) {
            thread.shutdownNow();
            thread = null;
        }
    }

    /** Any word from the feeder, a heartbeat included. */
    public synchronized void onFeed() {
        lastFeedAt = SystemClock.elapsedRealtime();
        reraiseInterval = FEEDER_LOST_MS;
    }

    /** How many ports want {@code source}; what the feeder hears back, for its log. */
    public synchronized int portsFor(@NonNull GnssSource source) {
        int n = 0;
        for (var port : ports) if (port.source == source) n++;
        return n;
    }

    /**
     * The phone's receiver, from the feeder: a fix (null when it has none; it carries its own
     * time) and the satellites in view (null when unchanged) with how long ago they were.
     */
    public void onReceiver(@Nullable GnssFix fix, @Nullable List<GnssSatellite> sky,
                           long skyAgeMs) {
        long now = SystemClock.elapsedRealtime();
        List<GnssPort> targets;
        synchronized (this) {
            if (sky != null) {
                receiverSky = sky;
                receiverSkyAt = now - skyAgeMs;
            }
            if (fix == null) return;
            targets = new ArrayList<>(ports);
        }
        // Written on the hub's own thread, which owns every port's state.
        var t = thread;
        if (t != null) t.execute(() -> {
            for (var port : targets)
                if (port.source == GnssSource.RECEIVER) writeReceiverFix(port, fix);
        });
    }

    /** The phone's location (fused), from the feeder. */
    public synchronized void onLocation(@NonNull GnssFix fix, long ageMs) {
        locationFix = fix;
        locationFixAt = SystemClock.elapsedRealtime() - ageMs;
    }

    private void writeReceiverFix(@NonNull GnssPort port, @NonNull GnssFix fix) {
        if (fix.utcMillis == port.lastFixUtc) return;
        port.lastFixUtc = fix.utcMillis;
        port.write(Nmea.epoch(fix, freshSky(SystemClock.elapsedRealtime()), port.markSimulated,
            System.currentTimeMillis()), SystemClock.elapsedRealtime());
    }

    private void tick() {
        long now = SystemClock.elapsedRealtime();
        long utc = System.currentTimeMillis();
        List<GnssPort> targets;
        GnssFix location;
        boolean reraise = false;
        synchronized (this) {
            targets = new ArrayList<>(ports);
            if (now - lastFeedAt > FEEDER_LOST_MS && now - lastReraiseAt > reraiseInterval) {
                if (lastReraiseAt > lastFeedAt)
                    reraiseInterval = Math.min(reraiseInterval * 2, RERAISE_MAX_MS);
                lastReraiseAt = now;
                reraise = true;
            }
            location = null;
            if (locationFix != null && now - locationFixAt <= LOCATION_HOLD_MS) {
                location = locationFix.at(utc);
                if (now - locationFixAt > MOTION_HOLD_MS) location = location.withoutMotion();
            }
        }
        if (reraise) {
            Log.w(TAG, "no word from the app's GNSS feeder; raising its service again");
            PeripheralForegroundControl.reraise();
        }
        for (var port : targets) {
            if (port.source == GnssSource.RECEIVER) {
                if (now - port.lastEpochAt < GAP_MS) continue;
                port.write(Nmea.epoch(null, freshSky(now), port.markSimulated, utc), now);
            } else {
                var sky = location != null ? Nmea.syntheticSky() : new ArrayList<GnssSatellite>();
                port.write(Nmea.epoch(location, sky, port.markSimulated, utc), now);
            }
        }
    }

    @NonNull
    private synchronized List<GnssSatellite> freshSky(long now) {
        return now - receiverSkyAt <= SKY_STALE_MS ? receiverSky : new ArrayList<>();
    }
}
