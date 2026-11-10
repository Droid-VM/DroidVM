// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.gnss;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.location.GnssStatus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class NmeaTest {
    // 2026-11-10 08:30:15.25 UTC
    private static final long T = 1794299415250L;

    private static GnssFix fix() {
        return new GnssFix(T, 25.0520833, 121.5, 120.0, 100.0, 1.0, 90.0, 5.0, 8.0);
    }

    private static List<String> lines(String epoch) {
        var list = new ArrayList<String>();
        for (var line : epoch.split("\r\n")) if (!line.isEmpty()) list.add(line);
        return list;
    }

    private static List<String> starting(String epoch, String prefix) {
        var list = new ArrayList<String>();
        for (var line : lines(epoch)) if (line.startsWith(prefix)) list.add(line);
        return list;
    }

    private static void assertWellFormed(String epoch) {
        assertTrue(epoch.endsWith("\r\n"));
        for (var line : lines(epoch)) {
            // NMEA's 82-character limit counts the $ and the CR LF.
            assertTrue(line, line.length() + 2 <= 82);
            assertTrue(line, line.startsWith("$"));
            int star = line.indexOf('*');
            int sum = 0;
            for (int i = 1; i < star; i++) sum ^= line.charAt(i);
            assertEquals(line, String.format("%02X", sum), line.substring(star + 1));
        }
    }

    private static GnssSatellite sat(int constellation, int svid, double el, double az,
                                     boolean used, double mhz) {
        return new GnssSatellite(constellation, svid, 40, el, az, used, mhz * 1e6);
    }

    @Test
    public void anglesCarryTheMinutesIntoTheDegree() {
        assertEquals("2503.12500", Nmea.angle(25.0520833, 2));
        assertEquals("12130.00000", Nmea.angle(121.5, 3));
        assertEquals("00100.00000", Nmea.angle(-1.0, 3));
        // 59.9999999 minutes rounds to the next degree, never to "60.00000".
        assertEquals("2600.00000", Nmea.angle(25.99999999999, 2));
    }

    @Test
    public void aFixWithEverySystem() {
        var sky = new ArrayList<GnssSatellite>();
        sky.add(sat(GnssStatus.CONSTELLATION_GPS, 3, 60, 10, true, 1575.42));
        sky.add(sat(GnssStatus.CONSTELLATION_GPS, 3, 60, 10, true, 1176.45));
        sky.add(sat(GnssStatus.CONSTELLATION_GPS, 8, 30, 120, true, 1575.42));
        sky.add(sat(GnssStatus.CONSTELLATION_GLONASS, 5, 45, 200, true, 1602.0));
        sky.add(sat(GnssStatus.CONSTELLATION_GALILEO, 11, 20, 300, true, 1575.42));
        sky.add(sat(GnssStatus.CONSTELLATION_BEIDOU, 23, 70, 250, true, 1561.098));
        sky.add(sat(GnssStatus.CONSTELLATION_QZSS, 194, 50, 160, false, 1575.42));
        var epoch = Nmea.epoch(fix(), sky, false, 0);
        assertWellFormed(epoch);
        var rmc = starting(epoch, "$GNRMC").get(0);
        assertTrue(rmc, rmc.startsWith("$GNRMC,083015.25,A,2503.12500,N,12130.00000,E,1.944,90.00,101126,,,A,V*"));
        var gga = starting(epoch, "$GNGGA").get(0);
        // Five satellites in the solution (GPS 3 counted once for its two bands), MSL altitude with
        // the geoid's height beside it.
        assertTrue(gga, gga.startsWith("$GNGGA,083015.25,2503.12500,N,12130.00000,E,1,05,"));
        assertTrue(gga, gga.contains(",100.0,M,20.0,M,,*"));
        // One GSA per system in the solution, its id last; QZSS is visible but unused.
        var gsa = starting(epoch, "$GNGSA");
        assertEquals(4, gsa.size());
        assertTrue(gsa.get(0), gsa.get(0).startsWith("$GNGSA,A,3,03,08,,"));
        assertTrue(gsa.get(1), gsa.get(1).startsWith("$GNGSA,A,3,69,,"));
        assertTrue(gsa.get(3), gsa.get(3).contains(",4*"));
        // GPS L1 and L5 are separate groups; GLONASS slot 5 is 69; QZSS 194 is 2.
        assertTrue(starting(epoch, "$GPGSV").get(0).contains(",02,03,60,010,40,08,30,120,40,1*"));
        assertTrue(starting(epoch, "$GPGSV").get(1).contains(",01,03,60,010,40,8*"));
        assertTrue(starting(epoch, "$GLGSV").get(0).contains(",69,45,200,40,1*"));
        assertTrue(starting(epoch, "$GAGSV").get(0).contains(",11,20,300,40,7*"));
        assertTrue(starting(epoch, "$GBGSV").get(0).contains(",23,70,250,40,1*"));
        assertTrue(starting(epoch, "$GQGSV").get(0).contains(",02,50,160,40,1*"));
        // GST: 68% radius 5 m = 3.3 m sigma per axis; vertical 8 m is already one sigma.
        var gst = starting(epoch, "$GNGST").get(0);
        assertTrue(gst, gst.startsWith("$GNGST,083015.25,,3.3,3.3,0.0,3.3,3.3,8.0*"));
    }

    @Test
    public void noFixStillReportsTheSky() {
        var sky = new ArrayList<GnssSatellite>();
        sky.add(sat(GnssStatus.CONSTELLATION_GPS, 3, 0, 0, false, 1575.42));
        var epoch = Nmea.epoch(null, sky, false, T);
        assertWellFormed(epoch);
        assertTrue(starting(epoch, "$GNRMC").get(0).startsWith("$GNRMC,083015.25,V,,,,,,,101126,,,N,V*"));
        assertTrue(starting(epoch, "$GNGGA").get(0).startsWith("$GNGGA,083015.25,,,,,0,00,,"));
        // No almanac yet: no elevation or azimuth, just the signal.
        assertTrue(starting(epoch, "$GPGSV").get(0).contains(",01,03,,,40,1*"));
        assertTrue(starting(epoch, "$GNGSA").get(0).startsWith("$GNGSA,A,1,,"));
        assertTrue(starting(epoch, "$GNGST").isEmpty());
    }

    @Test
    public void anEmptySkySaysSo() {
        var epoch = Nmea.epoch(null, new ArrayList<>(), false, T);
        assertWellFormed(epoch);
        assertEquals("$GPGSV,1,1,00,1*64", starting(epoch, "$GPGSV").get(0));
    }

    @Test
    public void aLocationOnlyFixIsCarriedBySyntheticSatellites() {
        var noAltitude = new GnssFix(T, 25, 121, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            40, Double.NaN);
        var epoch = Nmea.epoch(noAltitude, Nmea.syntheticSky(), false, 0);
        assertWellFormed(epoch);
        var gga = starting(epoch, "$GNGGA").get(0);
        assertTrue(gga, gga.contains(",1,08,"));
        // No altitude: the fields stay empty and the fix is 2D.
        assertTrue(gga, gga.contains(",,,,,,*"));
        assertTrue(starting(epoch, "$GNGSA").get(0).startsWith("$GNGSA,A,2,02,05,07,12,15,19,24,29,"));
        var dop = Nmea.dilution(Nmea.syntheticSky());
        assertTrue(dop[1] > 0.5 && dop[1] < 2);
        assertEquals(2, starting(epoch, "$GPGSV").size());
    }

    @Test
    public void simulatedFixesSayTheyAre() {
        var epoch = Nmea.epoch(fix(), Nmea.syntheticSky(), true, 0);
        assertTrue(starting(epoch, "$GNGGA").get(0).contains(",8,08,"));
        assertTrue(starting(epoch, "$GNRMC").get(0).contains(",S,V*"));
    }

    @Test
    public void satellitesNmeaCannotNumberAreLeftOut() {
        var sky = new ArrayList<GnssSatellite>();
        // GLONASS known only by frequency channel, an SBAS PRN past NMEA's range.
        sky.add(sat(GnssStatus.CONSTELLATION_GLONASS, 96, 40, 40, false, 1602.0));
        sky.add(sat(GnssStatus.CONSTELLATION_SBAS, 158, 40, 40, false, 1575.42));
        sky.add(sat(GnssStatus.CONSTELLATION_SBAS, 133, 40, 40, false, 1575.42));
        var epoch = Nmea.epoch(null, sky, false, T);
        assertTrue(starting(epoch, "$GLGSV").isEmpty());
        assertTrue(starting(epoch, "$GPGSV").get(0).contains(",01,46,40,040,40,1*"));
        assertFalse(epoch.contains(",71,"));
    }
}
