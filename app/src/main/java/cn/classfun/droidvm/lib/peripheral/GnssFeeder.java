// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.peripheral;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.location.altitude.AltitudeConverter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executor;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.gnss.GnssFix;
import cn.classfun.droidvm.lib.gnss.GnssSatellite;
import cn.classfun.droidvm.lib.store.vm.Gnss;

/**
 * Feeds the daemon's GNSS receivers from the phone, in the app's process, for as long as a
 * running VM has a VirtIO GNSS row.
 *
 * <p>Here rather than in the daemon because Android's location is Java-only and gated on the
 * caller's uid: the app's, holding location as a foreground capability through
 * {@link PeripheralForegroundService}, which owns this. What it reads depends on what the rows
 * resolved to ({@link Gnss#SOURCE_RECEIVER}, {@link Gnss#SOURCE_LOCATION}): the GPS provider and
 * the satellite status for the first, the fused provider for the second, both when VMs disagree.
 * Everything goes to the daemon's {@code gnss_feed} as it arrives; the daemon does the timing.</p>
 *
 * <p>The receiver's fixes are given a sea-level altitude when Android has not already (an
 * {@link AltitudeConverter}, Android 14 and later): NMEA's altitude is above sea level, and a
 * guest without one has no altitude at all.</p>
 */
final class GnssFeeder {
    private static final String TAG = "GnssFeeder";
    private static final long INTERVAL_MS = 1000;
    /**
     * How often the daemon hears from this when nothing else is said: a receiver indoors or a
     * position that is not moving can go quiet, and the daemon takes a quiet feeder for a dead
     * one (GnssHub re-raises the service).
     */
    private static final long HEARTBEAT_MS = 5000;

    private final Context context;
    private final LocationManager locations;
    private final HandlerThread thread = new HandlerThread("GnssFeeder");
    private final Handler handler;
    private final Executor executor;
    /** Keeps the app's daemon connection running while this needs it. */
    private final DaemonConnection.EventListener connection = new DaemonConnection.EventListener() {
        @Override
        public void onDaemonEvent(JSONObject data) {
        }

        @Override
        public void onDaemonConnected() {
        }

        @Override
        public void onDaemonDisconnected() {
        }
    };

    private int sources;
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (sources == 0) return;
            send(new JSONObject());
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };
    private LocationListener receiverListener;
    private GnssStatus.Callback statusCallback;
    private LocationListener locationListener;
    @Nullable
    private AltitudeConverter altitudes;

    GnssFeeder(@NonNull Context context) {
        this.context = context;
        this.locations = context.getSystemService(LocationManager.class);
        thread.start();
        handler = new Handler(thread.getLooper());
        executor = handler::post;
        DaemonConnection.getInstance().addListener(connection);
    }

    /** Runs exactly the sources in {@code wanted} (Gnss.SOURCE_* bits). Any thread. */
    void apply(int wanted) {
        handler.post(() -> {
            if (locations == null) return;
            if ((wanted & Gnss.SOURCE_RECEIVER) != (sources & Gnss.SOURCE_RECEIVER)) {
                if ((wanted & Gnss.SOURCE_RECEIVER) != 0) startReceiver();
                else stopReceiver();
            }
            if ((wanted & Gnss.SOURCE_LOCATION) != (sources & Gnss.SOURCE_LOCATION)) {
                if ((wanted & Gnss.SOURCE_LOCATION) != 0) startLocation();
                else stopLocation();
            }
            boolean wasRunning = sources != 0;
            sources = wanted;
            if (sources != 0 && !wasRunning) heartbeat.run();
        });
    }

    void close() {
        apply(0);
        handler.post(() -> {
            DaemonConnection.getInstance().removeListener(connection);
            thread.quitSafely();
        });
    }

    private void startReceiver() {
        if (!locations.hasProvider(LocationManager.GPS_PROVIDER)) {
            Log.w(TAG, "the phone has no GNSS receiver; its VMs' receivers stay without a fix");
            return;
        }
        receiverListener = this::onReceiverFix;
        statusCallback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                onReceiverSky(GnssSatellite.listOf(status));
            }
        };
        try {
            locations.registerGnssStatusCallback(executor, statusCallback);
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                new LocationRequest.Builder(INTERVAL_MS)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build(),
                executor, receiverListener);
            Log.i(TAG, "GNSS receiver on");
        } catch (SecurityException e) {
            Log.w(TAG, "no location permission: the GNSS receiver stays off", e);
            stopReceiver();
        }
    }

    private void stopReceiver() {
        if (receiverListener != null) locations.removeUpdates(receiverListener);
        if (statusCallback != null) locations.unregisterGnssStatusCallback(statusCallback);
        if (receiverListener != null) Log.i(TAG, "GNSS receiver off");
        receiverListener = null;
        statusCallback = null;
    }

    private void startLocation() {
        var provider = locations.hasProvider(LocationManager.FUSED_PROVIDER)
            ? LocationManager.FUSED_PROVIDER : LocationManager.NETWORK_PROVIDER;
        locationListener = this::onLocation;
        try {
            // What the phone already knows, so a guest has a position before the first update.
            var last = locations.getLastKnownLocation(provider);
            if (last != null) onLocation(last);
            locations.requestLocationUpdates(provider,
                new LocationRequest.Builder(INTERVAL_MS)
                    .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY).build(),
                executor, locationListener);
            Log.i(TAG, fmt("location from the %s provider on", provider));
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, fmt("location from the %s provider unavailable", provider), e);
            stopLocation();
        }
    }

    private void stopLocation() {
        if (locationListener != null) {
            locations.removeUpdates(locationListener);
            Log.i(TAG, "location off");
        }
        locationListener = null;
    }

    private void onReceiverSky(@NonNull List<GnssSatellite> sky) {
        try {
            var receiver = new JSONObject();
            receiver.put("sky", GnssSatellite.toJson(sky));
            receiver.put("sky_age", 0);
            send(new JSONObject().put("receiver", receiver));
        } catch (Exception e) {
            Log.w(TAG, "could not encode the satellites", e);
        }
    }

    private void onReceiverFix(@NonNull Location location) {
        try {
            var receiver = new JSONObject();
            receiver.put("fix", GnssFix.of(withSeaLevel(location)).toJson());
            send(new JSONObject().put("receiver", receiver));
        } catch (Exception e) {
            Log.w(TAG, "could not encode a receiver fix", e);
        }
    }

    private void onLocation(@NonNull Location location) {
        try {
            var part = new JSONObject();
            part.put("fix", GnssFix.of(withSeaLevel(location)).toJson());
            part.put("age", ageMs(location));
            send(new JSONObject().put("location", part));
        } catch (Exception e) {
            Log.w(TAG, "could not encode a location", e);
        }
    }

    @NonNull
    private Location withSeaLevel(@NonNull Location location) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return location;
        if (location.hasMslAltitude() || !location.hasAltitude()) return location;
        try {
            if (altitudes == null) altitudes = new AltitudeConverter();
            var copy = new Location(location);
            altitudes.addMslAltitudeToLocation(context, copy);
            return copy;
        } catch (Exception e) {
            // Off the geoid model's grid, or no model on this build: the fix goes without one.
            return location;
        }
    }

    private static long ageMs(@NonNull Location location) {
        long nanos = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return Math.max(0, nanos / 1_000_000);
    }

    /** One request to the daemon, on this thread; dropped while the daemon is unreachable. */
    private void send(@NonNull JSONObject params) {
        try {
            params.put("command", "gnss_feed");
            DaemonConnection.getInstance().request(params);
        } catch (Exception e) {
            // Not connected yet, or the daemon restarted: the next update tries again.
        }
    }
}
