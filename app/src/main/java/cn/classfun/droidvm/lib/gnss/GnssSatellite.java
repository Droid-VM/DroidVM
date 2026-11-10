// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.gnss;

import android.location.GnssStatus;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * One satellite signal the phone's receiver tracks: one entry of a {@link GnssStatus}.
 *
 * <p>A signal rather than a satellite, as GnssStatus has it: a dual-frequency receiver lists a
 * GPS satellite once for L1 and once for L5, and NMEA reports them in separate GSV groups.
 * {@link #constellation} uses GnssStatus's numbering ({@code CONSTELLATION_GPS} and so on) and
 * {@link #svid} its per-system ids; {@link Nmea} maps both to NMEA's.</p>
 */
public final class GnssSatellite {
    public final int constellation;
    public final int svid;
    /** Carrier-to-noise density at the antenna, dB-Hz; 0 when not tracked. */
    public final double cn0DbHz;
    public final double elevationDeg;
    public final double azimuthDeg;
    public final boolean usedInFix;
    /** The signal's carrier, Hz, or NaN when the receiver did not say. */
    public final double carrierHz;

    public GnssSatellite(int constellation, int svid, double cn0DbHz, double elevationDeg,
                         double azimuthDeg, boolean usedInFix, double carrierHz) {
        this.constellation = constellation;
        this.svid = svid;
        this.cn0DbHz = cn0DbHz;
        this.elevationDeg = elevationDeg;
        this.azimuthDeg = azimuthDeg;
        this.usedInFix = usedInFix;
        this.carrierHz = carrierHz;
    }

    @NonNull
    public static List<GnssSatellite> listOf(@NonNull GnssStatus status) {
        var list = new ArrayList<GnssSatellite>(status.getSatelliteCount());
        for (int i = 0; i < status.getSatelliteCount(); i++) {
            list.add(new GnssSatellite(
                status.getConstellationType(i),
                status.getSvid(i),
                status.getCn0DbHz(i),
                status.getElevationDegrees(i),
                status.getAzimuthDegrees(i),
                status.usedInFix(i),
                status.hasCarrierFrequencyHz(i) ? status.getCarrierFrequencyHz(i) : Double.NaN));
        }
        return list;
    }

    /** Whether the receiver knows where in the sky it is (no almanac yet reads as 0, 0). */
    public boolean hasPosition() {
        return (elevationDeg != 0 || azimuthDeg != 0)
            && elevationDeg >= -90 && elevationDeg <= 90;
    }

    @NonNull
    public static JSONArray toJson(@NonNull List<GnssSatellite> satellites) throws JSONException {
        // A compact row per signal: a dual-frequency fix lists 60-odd of them every second.
        var array = new JSONArray();
        for (var s : satellites) {
            var row = new JSONArray();
            row.put(s.constellation).put(s.svid).put(s.cn0DbHz).put(s.elevationDeg)
                .put(s.azimuthDeg).put(s.usedInFix ? 1 : 0)
                .put(Double.isNaN(s.carrierHz) ? 0 : s.carrierHz);
            array.put(row);
        }
        return array;
    }

    @NonNull
    public static List<GnssSatellite> fromJson(@Nullable JSONArray array) {
        var list = new ArrayList<GnssSatellite>();
        if (array == null) return list;
        for (int i = 0; i < array.length(); i++) {
            var row = array.optJSONArray(i);
            if (row == null || row.length() < 7) continue;
            double carrier = row.optDouble(6, 0);
            list.add(new GnssSatellite(row.optInt(0), row.optInt(1), row.optDouble(2, 0),
                row.optDouble(3, 0), row.optDouble(4, 0), row.optInt(5) != 0,
                carrier > 0 ? carrier : Double.NaN));
        }
        return list;
    }
}
