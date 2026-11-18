// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.gnss;

import android.location.Location;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One position as the phone's location stack reported it, in the units NMEA wants it built from.
 *
 * <p>What the app's feeder sends the daemon and what the daemon turns into sentences: a plain
 * value with the {@link Location} fields that matter, so the daemon (which never sees a Location)
 * and the tests (which cannot make one) work on the same thing. A field the source did not give
 * is NaN, never 0 -- 0 m altitude and 0 m/s are positions a guest would believe.</p>
 *
 * <p>Accuracies are Android's: the radius (horizontal) or half-interval (vertical) holding the
 * true position with 68% confidence.</p>
 */
public final class GnssFix {
    /** UTC of the fix, in milliseconds: GNSS time for the receiver, the fix's own for the rest. */
    public final long utcMillis;
    public final double latitude;
    public final double longitude;
    /** Height above the WGS 84 ellipsoid, metres (what Location.getAltitude is). */
    public final double altitudeHae;
    /** Height above mean sea level, metres. */
    public final double altitudeMsl;
    public final double speedMps;
    /** Course over ground, degrees from true north. */
    public final double bearingDeg;
    public final double horizontalAccuracy;
    public final double verticalAccuracy;

    public GnssFix(long utcMillis, double latitude, double longitude, double altitudeHae,
                   double altitudeMsl, double speedMps, double bearingDeg,
                   double horizontalAccuracy, double verticalAccuracy) {
        this.utcMillis = utcMillis;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeHae = altitudeHae;
        this.altitudeMsl = altitudeMsl;
        this.speedMps = speedMps;
        this.bearingDeg = bearingDeg;
        this.horizontalAccuracy = horizontalAccuracy;
        this.verticalAccuracy = verticalAccuracy;
    }

    /**
     * The fix in {@code location}. An altitude without a vertical accuracy is left out: that is
     * a placeholder rather than a height (a fused fix from Wi-Fi reports 0 m), and a guest would
     * take it as a 3D fix at the ellipsoid. A GNSS fix always comes with one.
     */
    @NonNull
    public static GnssFix of(@NonNull Location location) {
        boolean altitude = location.hasAltitude() && location.hasVerticalAccuracy();
        double msl = Double.NaN;
        if (altitude && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            && location.hasMslAltitude())
            msl = location.getMslAltitudeMeters();
        return new GnssFix(
            location.getTime(),
            location.getLatitude(),
            location.getLongitude(),
            altitude ? location.getAltitude() : Double.NaN,
            msl,
            location.hasSpeed() ? location.getSpeed() : Double.NaN,
            location.hasBearing() ? location.getBearing() : Double.NaN,
            location.hasAccuracy() ? location.getAccuracy() : Double.NaN,
            location.hasVerticalAccuracy() ? location.getVerticalAccuracyMeters() : Double.NaN);
    }

    /** The same fix at another time: a location-only receiver reports its last fix every second. */
    @NonNull
    public GnssFix at(long utcMillis) {
        return new GnssFix(utcMillis, latitude, longitude, altitudeHae, altitudeMsl, speedMps,
            bearingDeg, horizontalAccuracy, verticalAccuracy);
    }

    /** The same fix with the motion dropped, for when it is too old for its speed to be true. */
    @NonNull
    public GnssFix withoutMotion() {
        return new GnssFix(utcMillis, latitude, longitude, altitudeHae, altitudeMsl, Double.NaN,
            Double.NaN, horizontalAccuracy, verticalAccuracy);
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        var json = new JSONObject();
        json.put("t", utcMillis);
        json.put("lat", latitude);
        json.put("lon", longitude);
        // JSON has no NaN: an unknown field is simply left out.
        putIfKnown(json, "hae", altitudeHae);
        putIfKnown(json, "msl", altitudeMsl);
        putIfKnown(json, "spd", speedMps);
        putIfKnown(json, "brg", bearingDeg);
        putIfKnown(json, "hacc", horizontalAccuracy);
        putIfKnown(json, "vacc", verticalAccuracy);
        return json;
    }

    @Nullable
    public static GnssFix fromJson(@Nullable JSONObject json) {
        if (json == null || !json.has("lat") || !json.has("lon")) return null;
        double lat = json.optDouble("lat");
        double lon = json.optDouble("lon");
        if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180)) return null;
        return new GnssFix(json.optLong("t"), lat, lon, json.optDouble("hae"),
            json.optDouble("msl"), json.optDouble("spd"), json.optDouble("brg"),
            json.optDouble("hacc"), json.optDouble("vacc"));
    }

    private static void putIfKnown(@NonNull JSONObject json, @NonNull String key, double value)
        throws JSONException {
        if (!Double.isNaN(value)) json.put(key, value);
    }
}
