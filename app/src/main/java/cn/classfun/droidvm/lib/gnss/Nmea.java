// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.gnss;

import static android.location.GnssStatus.CONSTELLATION_BEIDOU;
import static android.location.GnssStatus.CONSTELLATION_GALILEO;
import static android.location.GnssStatus.CONSTELLATION_GLONASS;
import static android.location.GnssStatus.CONSTELLATION_GPS;
import static android.location.GnssStatus.CONSTELLATION_IRNSS;
import static android.location.GnssStatus.CONSTELLATION_QZSS;
import static android.location.GnssStatus.CONSTELLATION_SBAS;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The virtual GNSS receiver's output: NMEA 0183 4.11, one epoch of sentences per fix.
 *
 * <p>One stream carries every system, the way a real multi-system receiver's one UART does:
 * the position is the one solution the phone's receiver computed from all of them (RMC, GGA, GST
 * with the {@code GN} talker), and the satellites are told apart by talker ({@code GP GL GA GB GQ
 * GI}) in GSV and by system id in GSA. GSV's signal id separates the bands of a dual-frequency
 * receiver, so an L5 signal is its own group rather than a second copy of the L1 satellite.</p>
 *
 * <p>Pure: a fix and a satellite list in, text out. The daemon decides what to feed it (the
 * phone's receiver, or the location-only {@link #syntheticSky}) and when.</p>
 */
public final class Nmea {
    /** NMEA 4.11 system ids: GSA's last field, and what keys a GSV group with its talker. */
    static final int SYSTEM_GPS = 1;
    static final int SYSTEM_GLONASS = 2;
    static final int SYSTEM_GALILEO = 3;
    static final int SYSTEM_BEIDOU = 4;
    static final int SYSTEM_QZSS = 5;
    static final int SYSTEM_NAVIC = 6;

    private static final String[] TALKERS = {null, "GP", "GL", "GA", "GB", "GQ", "GI"};

    private static final double KNOTS_PER_MPS = 3600.0 / 1852.0;

    /**
     * Android's accuracies are 68% radii; GST wants standard deviations. For a circular normal the
     * radius holding 68% is sqrt(-2 ln 0.32) = 1.5096 sigmas.
     */
    private static final double SIGMAS_PER_HORIZONTAL_68 = 1.5096;

    /** NMEA's own limits: GSV's two-digit count and nine-message sequence, 4 signals each. */
    private static final int GSV_PER_MESSAGE = 4;
    private static final int GSV_MAX_MESSAGES = 9;
    private static final int GSA_IDS = 12;

    private static final double GPS_L1_HZ = 1575.42e6;

    /**
     * What a fix says it is, in GGA's quality and RMC's mode: an autonomous GNSS fix (1, A), a
     * position the receiver did not solve itself (6, E: estimated, NMEA's word for dead
     * reckoning and anything else not from the satellites), or a simulated one (8, S).
     */
    public enum FixKind {
        GNSS(1, 'A'),
        ESTIMATED(6, 'E'),
        SIMULATED(8, 'S');

        final int quality;
        final char mode;

        FixKind(int quality, char mode) {
            this.quality = quality;
            this.mode = mode;
        }
    }

    private Nmea() {
    }

    /**
     * One second's sentences: RMC, GGA, a GSA per system with satellites in the solution, the GSV
     * groups, and GST when the fix has an accuracy.
     *
     * @param fix        the position, or null for a receiver without one (it still reports its
     *                   satellites and the time, as a real one does while acquiring)
     * @param kind       what the fix says it is ({@link FixKind}); meaningless without one
     * @param nowUtcMillis the epoch's time when there is no fix to take it from
     */
    @NonNull
    public static String epoch(@Nullable GnssFix fix, @NonNull List<GnssSatellite> satellites,
                               @NonNull FixKind kind, long nowUtcMillis) {
        long time = fix != null ? fix.utcMillis : nowUtcMillis;
        var used = new ArrayList<GnssSatellite>();
        for (var s : satellites)
            if (s.usedInFix && nmeaId(s) > 0) used.add(s);
        var dop = dilution(used);
        boolean threeD = fix != null && !Double.isNaN(fix.altitudeMsl);
        var out = new StringBuilder();
        rmc(out, fix, time, kind);
        gga(out, fix, time, kind, countSatellites(used), dop[1]);
        gsa(out, fix, threeD, used, dop);
        gsv(out, satellites);
        if (fix != null && !Double.isNaN(fix.horizontalAccuracy)) gst(out, fix, time);
        return out.toString();
    }

    /**
     * The sky of a receiver that has only a position: eight GPS L1 satellites spread over the sky,
     * all in the solution. For the location-only mode, which has a fix from the phone's location
     * stack (Wi-Fi, cell, GNSS, whatever it used) and no satellites: a guest's GNSS stack expects a
     * fix to come with the satellites that made it. Fixed rather than computed from orbits --
     * nothing real can be read from them, and the accuracy that matters travels in GST.
     */
    @NonNull
    public static List<GnssSatellite> syntheticSky() {
        int[][] sky = {
            // svid, elevation, azimuth, C/N0
            {2, 68, 40, 44}, {5, 52, 110, 42}, {7, 41, 175, 40}, {12, 33, 230, 39},
            {15, 27, 290, 38}, {19, 20, 330, 36}, {24, 15, 15, 34}, {29, 45, 260, 41},
        };
        var list = new ArrayList<GnssSatellite>(sky.length);
        for (var s : sky)
            list.add(new GnssSatellite(CONSTELLATION_GPS, s[0], s[3], s[1], s[2], true, GPS_L1_HZ));
        return list;
    }

    // ---- sentences ----

    private static void rmc(@NonNull StringBuilder out, @Nullable GnssFix fix, long time,
                            @NonNull FixKind kind) {
        var f = new StringBuilder("GNRMC,").append(clock(time)).append(',');
        f.append(fix != null ? 'A' : 'V').append(',');
        position(f, fix);
        f.append(',');
        if (fix != null && !Double.isNaN(fix.speedMps))
            f.append(fmt("%.3f", fix.speedMps * KNOTS_PER_MPS));
        f.append(',');
        if (fix != null && !Double.isNaN(fix.bearingDeg)) f.append(fmt("%.2f", fix.bearingDeg));
        f.append(',').append(date(time)).append(",,,");
        // Mode, then 4.10's navigational status, which receivers leave "not valid".
        f.append(fix == null ? 'N' : kind.mode).append(",V");
        sentence(out, f);
    }

    private static void gga(@NonNull StringBuilder out, @Nullable GnssFix fix, long time,
                            @NonNull FixKind kind, int satellitesUsed, double hdop) {
        var f = new StringBuilder("GNGGA,").append(clock(time)).append(',');
        position(f, fix);
        f.append(',').append(fix == null ? 0 : kind.quality);
        f.append(',').append(fmt("%02d", Math.min(satellitesUsed, 99))).append(',');
        if (fix != null && !Double.isNaN(hdop)) f.append(fmt("%.2f", hdop));
        f.append(',');
        // GGA's altitude is above sea level, with the geoid's height above the ellipsoid beside
        // it. Without the sea-level figure the field is left empty rather than filled with the
        // ellipsoid height: a reader adds its own geoid table to whatever is there, and would be
        // wrong by tens of metres.
        if (fix != null && !Double.isNaN(fix.altitudeMsl)) {
            f.append(fmt("%.1f", fix.altitudeMsl)).append(",M,");
            if (!Double.isNaN(fix.altitudeHae))
                f.append(fmt("%.1f", fix.altitudeHae - fix.altitudeMsl)).append(",M");
            else f.append(',');
        } else {
            f.append(",,,");
        }
        f.append(",,");
        sentence(out, f);
    }

    private static void gsa(@NonNull StringBuilder out, @Nullable GnssFix fix, boolean threeD,
                            @NonNull List<GnssSatellite> used, @NonNull double[] dop) {
        int mode = fix == null ? 1 : threeD ? 3 : 2;
        var bySystem = new TreeMap<Integer, List<Integer>>();
        for (var s : used) {
            var ids = bySystem.computeIfAbsent(systemOf(s.constellation), k -> new ArrayList<>());
            int id = nmeaId(s);
            // A dual-frequency satellite is in the solution once, whatever its signals.
            if (!ids.contains(id)) ids.add(id);
        }
        if (bySystem.isEmpty()) bySystem.put(SYSTEM_GPS, new ArrayList<>());
        for (var entry : bySystem.entrySet()) {
            var ids = entry.getValue();
            ids.sort(null);
            int at = 0;
            do {
                var f = new StringBuilder("GNGSA,A,").append(mode);
                for (int i = 0; i < GSA_IDS; i++) {
                    f.append(',');
                    if (at + i < ids.size()) f.append(fmt("%02d", ids.get(at + i)));
                }
                f.append(',').append(dopField(fix, dop[0]));
                f.append(',').append(dopField(fix, dop[1]));
                f.append(',').append(dopField(fix, dop[2]));
                f.append(',').append(entry.getKey());
                sentence(out, f);
                at += GSA_IDS;
            } while (at < ids.size());
        }
    }

    private static void gsv(@NonNull StringBuilder out, @NonNull List<GnssSatellite> satellites) {
        // Keyed system first, then signal, so the groups come out GPS L1, GPS L5, GLONASS G1...
        var groups = new TreeMap<Integer, List<GnssSatellite>>();
        for (var s : satellites) {
            if (nmeaId(s) <= 0) continue;
            int key = systemOf(s.constellation) * 16 + signalId(s);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        if (groups.isEmpty()) {
            // A receiver that sees nothing says so.
            sentence(out, new StringBuilder("GPGSV,1,1,00,1"));
            return;
        }
        for (Map.Entry<Integer, List<GnssSatellite>> entry : groups.entrySet()) {
            var list = entry.getValue();
            list.sort((a, b) -> Integer.compare(nmeaId(a), nmeaId(b)));
            int count = Math.min(list.size(), GSV_PER_MESSAGE * GSV_MAX_MESSAGES);
            int messages = (count + GSV_PER_MESSAGE - 1) / GSV_PER_MESSAGE;
            var talker = TALKERS[entry.getKey() / 16];
            int signal = entry.getKey() % 16;
            for (int m = 0; m < messages; m++) {
                var f = new StringBuilder(talker).append("GSV,").append(messages).append(',')
                    .append(m + 1).append(',').append(fmt("%02d", count));
                for (int i = m * GSV_PER_MESSAGE; i < Math.min(count, (m + 1) * GSV_PER_MESSAGE);
                     i++) {
                    var s = list.get(i);
                    f.append(',').append(fmt("%02d", nmeaId(s))).append(',');
                    if (s.hasPosition()) {
                        f.append(fmt("%02d", Math.round(s.elevationDeg))).append(',');
                        f.append(fmt("%03d", Math.round(s.azimuthDeg) % 360));
                    } else {
                        f.append(',');
                    }
                    f.append(',');
                    if (s.cn0DbHz > 0) f.append(fmt("%02d", Math.min(99, Math.round(s.cn0DbHz))));
                }
                f.append(',').append(Integer.toHexString(signal).toUpperCase());
                sentence(out, f);
            }
        }
    }

    private static void gst(@NonNull StringBuilder out, @NonNull GnssFix fix, long time) {
        double sigma = fix.horizontalAccuracy / SIGMAS_PER_HORIZONTAL_68;
        var f = new StringBuilder("GNGST,").append(clock(time)).append(",,");
        f.append(fmt("%.1f,%.1f,0.0,%.1f,%.1f,", sigma, sigma, sigma, sigma));
        // The vertical accuracy is already one-dimensional: its 68% half-interval is one sigma.
        if (!Double.isNaN(fix.verticalAccuracy)) f.append(fmt("%.1f", fix.verticalAccuracy));
        sentence(out, f);
    }

    // ---- fields ----

    private static void position(@NonNull StringBuilder f, @Nullable GnssFix fix) {
        if (fix == null) {
            f.append(",,,");
            return;
        }
        f.append(angle(fix.latitude, 2)).append(',').append(fix.latitude < 0 ? 'S' : 'N');
        f.append(',');
        f.append(angle(fix.longitude, 3)).append(',').append(fix.longitude < 0 ? 'W' : 'E');
    }

    /** Degrees as NMEA's dddmm.mmmmm, rounded once so 59.999999' carries into the degree. */
    @NonNull
    static String angle(double degrees, int degreeDigits) {
        long units = Math.round(Math.abs(degrees) * 60 * 100_000);
        long whole = units / (60 * 100_000);
        long minuteUnits = units % (60 * 100_000);
        return fmt("%0" + degreeDigits + "d%02d.%05d", whole, minuteUnits / 100_000,
            minuteUnits % 100_000);
    }

    @NonNull
    private static String clock(long utcMillis) {
        var t = Instant.ofEpochMilli(utcMillis).atOffset(ZoneOffset.UTC);
        return fmt("%02d%02d%02d.%02d", t.getHour(), t.getMinute(), t.getSecond(),
            t.getNano() / 10_000_000);
    }

    @NonNull
    private static String date(long utcMillis) {
        var t = Instant.ofEpochMilli(utcMillis).atOffset(ZoneOffset.UTC);
        return fmt("%02d%02d%02d", t.getDayOfMonth(), t.getMonthValue(), t.getYear() % 100);
    }

    @NonNull
    private static String dopField(@Nullable GnssFix fix, double value) {
        return fix == null || Double.isNaN(value) ? "" : fmt("%.2f", value);
    }

    private static void sentence(@NonNull StringBuilder out, @NonNull CharSequence fields) {
        int sum = 0;
        for (int i = 0; i < fields.length(); i++) sum ^= fields.charAt(i);
        out.append('$').append(fields).append('*').append(fmt("%02X", sum)).append("\r\n");
    }

    // ---- satellites ----

    private static int countSatellites(@NonNull List<GnssSatellite> used) {
        var seen = new ArrayList<Integer>();
        for (var s : used) {
            int key = systemOf(s.constellation) * 1000 + nmeaId(s);
            if (!seen.contains(key)) seen.add(key);
        }
        return seen.size();
    }

    static int systemOf(int constellation) {
        switch (constellation) {
            case CONSTELLATION_GPS:
            case CONSTELLATION_SBAS:
                return SYSTEM_GPS;
            case CONSTELLATION_GLONASS:
                return SYSTEM_GLONASS;
            case CONSTELLATION_GALILEO:
                return SYSTEM_GALILEO;
            case CONSTELLATION_BEIDOU:
                return SYSTEM_BEIDOU;
            case CONSTELLATION_QZSS:
                return SYSTEM_QZSS;
            case CONSTELLATION_IRNSS:
                return SYSTEM_NAVIC;
            default:
                return 0;
        }
    }

    /**
     * The satellite's id as NMEA 4.11 numbers it inside its system's talker, or 0 when it has
     * none. Android numbers SBAS by PRN (120-158), GLONASS by orbital slot (1-24) or, when the
     * slot is unknown, by frequency channel (93-106), and QZSS by PRN (193-202).
     */
    static int nmeaId(@NonNull GnssSatellite s) {
        int svid = s.svid;
        switch (s.constellation) {
            case CONSTELLATION_GPS:
                return svid >= 1 && svid <= 32 ? svid : 0;
            case CONSTELLATION_SBAS:
                // Reported under GP as 33-64.
                return svid >= 120 && svid <= 151 ? svid - 87 : 0;
            case CONSTELLATION_GLONASS:
                // 65-96 by slot; a channel number alone names no satellite.
                return svid >= 1 && svid <= 32 ? svid + 64 : 0;
            case CONSTELLATION_GALILEO:
                return svid >= 1 && svid <= 36 ? svid : 0;
            case CONSTELLATION_BEIDOU:
                return svid >= 1 && svid <= 63 ? svid : 0;
            case CONSTELLATION_QZSS:
                return svid >= 193 && svid <= 202 ? svid - 192 : 0;
            case CONSTELLATION_IRNSS:
                return svid >= 1 && svid <= 14 ? svid : 0;
            default:
                return 0;
        }
    }

    /** NMEA 4.11's signal id for the satellite's band; 1, its system's primary, when unknown. */
    static int signalId(@NonNull GnssSatellite s) {
        double mhz = s.carrierHz / 1e6;
        if (Double.isNaN(mhz)) return 1;
        boolean l1 = near(mhz, 1575.42), l2 = near(mhz, 1227.60), l5 = near(mhz, 1176.45);
        boolean e5b = near(mhz, 1207.14), e6 = near(mhz, 1278.75);
        switch (s.constellation) {
            case CONSTELLATION_GPS:
            case CONSTELLATION_SBAS:
                return l5 ? 8 : l2 ? 6 : 1; // L1 C/A, L2C-L, L5-Q
            case CONSTELLATION_GLONASS:
                return mhz < 1400 ? 3 : 1; // G2 C/A, G1 C/A
            case CONSTELLATION_GALILEO:
                return l1 ? 7 : l5 ? 1 : e5b ? 2 : e6 ? 5 : 7; // E1-BC, E5a, E5b, E6-BC
            case CONSTELLATION_BEIDOU:
                if (near(mhz, 1561.098)) return 1; // B1I
                if (near(mhz, 1268.52)) return 8; // B3I
                return l1 ? 3 : l5 ? 5 : e5b ? 11 : 1; // B1C, B2a, B2I
            case CONSTELLATION_QZSS:
                return l5 ? 8 : l2 ? 6 : e6 ? 9 : 1; // L1 C/A, L2C-L, L5-Q, L6D
            default:
                return 1;
        }
    }

    private static boolean near(double mhz, double band) {
        return Math.abs(mhz - band) < 3;
    }

    /**
     * PDOP, HDOP and VDOP of the satellites in the solution, NaN when fewer than four have a
     * position or their geometry has none to give. One receiver clock for every system: the
     * per-system offsets a multi-system solution also estimates move these figures little, and
     * Android does not report the receiver's own.
     */
    @NonNull
    static double[] dilution(@NonNull List<GnssSatellite> used) {
        var nan = new double[]{Double.NaN, Double.NaN, Double.NaN};
        var rows = new ArrayList<double[]>();
        var seen = new ArrayList<Integer>();
        for (var s : used) {
            if (!s.hasPosition()) continue;
            int key = systemOf(s.constellation) * 1000 + nmeaId(s);
            if (seen.contains(key)) continue;
            seen.add(key);
            double el = Math.toRadians(s.elevationDeg), az = Math.toRadians(s.azimuthDeg);
            rows.add(new double[]{
                Math.cos(el) * Math.sin(az), Math.cos(el) * Math.cos(az), Math.sin(el), 1});
        }
        if (rows.size() < 4) return nan;
        var n = new double[4][8];
        for (var g : rows)
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++) n[i][j] += g[i] * g[j];
        for (int i = 0; i < 4; i++) n[i][4 + i] = 1;
        // Gauss-Jordan on [N | I]: the right half becomes N's inverse.
        for (int col = 0; col < 4; col++) {
            int pivot = col;
            for (int r = col + 1; r < 4; r++)
                if (Math.abs(n[r][col]) > Math.abs(n[pivot][col])) pivot = r;
            if (Math.abs(n[pivot][col]) < 1e-9) return nan;
            var tmp = n[col];
            n[col] = n[pivot];
            n[pivot] = tmp;
            double p = n[col][col];
            for (int j = 0; j < 8; j++) n[col][j] /= p;
            for (int r = 0; r < 4; r++) {
                if (r == col) continue;
                double factor = n[r][col];
                for (int j = 0; j < 8; j++) n[r][j] -= factor * n[col][j];
            }
        }
        double east = n[0][4], north = n[1][5], up = n[2][6];
        return new double[]{Math.sqrt(east + north + up), Math.sqrt(east + north), Math.sqrt(up)};
    }
}
