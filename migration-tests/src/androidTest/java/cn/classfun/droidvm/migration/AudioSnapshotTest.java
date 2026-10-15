// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

/** Development baseline only. Native enumeration never calls this helper. */
public final class AudioSnapshotTest {
    private static JSONArray sortedValues(int[] values) {
        var array = new JSONArray();
        java.util.Arrays.stream(values).distinct().sorted().forEach(array::put);
        return array;
    }

    @Test public void reportAudioDevices() throws Exception {
        Assume.assumeTrue("Explicit baseline collection with --no-hidden-api-checks",
            "true".equals(InstrumentationRegistry.getArguments().getString("audio_snapshot")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var manager = instrumentation.getContext().getSystemService(AudioManager.class);
        assertNotNull(manager);
        var internalType = AudioDeviceInfo.class.getDeclaredMethod("getInternalType");
        internalType.setAccessible(true);
        var devices = new JSONArray();
        for (var info : manager.getDevices(AudioManager.GET_DEVICES_ALL)) {
            var device = new JSONObject();
            device.put("id", info.getId());
            device.put("input", info.isSource());
            device.put("internal_type", Integer.toUnsignedLong((int) internalType.invoke(info)));
            device.put("type", info.getType());
            device.put("address", info.getAddress());
            device.put("sample_rates", sortedValues(info.getSampleRates()));
            device.put("channel_counts", sortedValues(info.getChannelCounts()));
            devices.put(device);
        }
        assertTrue("AudioManager returned no devices", devices.length() > 0);
        var result = new Bundle();
        result.putString("audio_devices_json", devices.toString());
        instrumentation.sendStatus(2, result);
    }
}
