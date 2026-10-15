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
import cn.classfun.droidvm.lib.data.HostAudioDeviceTypes;

/** Development baseline only. Native enumeration never calls this helper. */
public final class AudioSnapshotTest {
    private static JSONObject asset(String name) throws Exception {
        try (var stream = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            return new JSONObject(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
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
            device.put("key", HostAudioDeviceTypes.typeName(info.getType()) + "|" + info.getAddress());
            device.put("selectable", HostAudioDeviceTypes.isSelectable(info.getType()));
            device.put("kind", HostAudioDeviceTypes.kindOf(info.getType(), info.isSource()));
            device.put("sample_rates", sortedValues(info.getSampleRates()));
            device.put("channel_counts", sortedValues(info.getChannelCounts()));
            devices.put(device);
        }
        assertTrue("AudioManager returned no devices", devices.length() > 0);
        var result = new Bundle();
        result.putString("audio_devices_json", devices.toString());
        var mapping = AudioDeviceInfo.class.getDeclaredField("INT_TO_EXT_DEVICE_MAPPING");
        mapping.setAccessible(true);
        var types = (android.util.SparseIntArray) mapping.get(null);
        var typeMap = new JSONObject();
        var profiledMap = asset("audio-warsaw-api37.json").getJSONObject("internal_to_external");
        assertEquals("Audio type mapping count changed", profiledMap.length(), types.size());
        for (int i = 0; i < types.size(); ++i)
            typeMap.put(Integer.toUnsignedString(types.keyAt(i)), types.valueAt(i));
        for (int i = 0; i < types.size(); ++i)
            assertEquals("Audio type mapping changed", profiledMap.getInt(Integer.toUnsignedString(types.keyAt(i))), types.valueAt(i));
        result.putString("internal_to_external_json", typeMap.toString());
        var policies = new JSONObject();
        var expectedTypes = asset("host-audio-types.json").getJSONObject("types");
        for (int type = 0; type <= 64; ++type) {
            var policy = new JSONObject();
            policy.put("name", HostAudioDeviceTypes.typeName(type));
            policy.put("selectable", HostAudioDeviceTypes.isSelectable(type));
            policy.put("input_kind", HostAudioDeviceTypes.kindOf(type, true));
            policy.put("output_kind", HostAudioDeviceTypes.kindOf(type, false));
            policies.put(Integer.toString(type), policy);
            var expected = expectedTypes.getJSONObject(Integer.toString(type));
            assertEquals(expected.getString("name"), policy.getString("name"));
            assertEquals(expected.getBoolean("selectable"), policy.getBoolean("selectable"));
            assertEquals(expected.getInt("input_kind"), policy.getInt("input_kind"));
            assertEquals(expected.getInt("output_kind"), policy.getInt("output_kind"));
        }
        result.putString("legacy_audio_types_json", policies.toString());
        var table = new StringBuilder();
        for (boolean input : new boolean[]{false, true}) {
            String direction = input ? "in" : "out";
            table.append("0\t").append(direction).append("\tDEFAULT|system default\t0\t0\t")
                .append(input ? 6 : 1).append('\n');
            var keys = new java.util.HashSet<String>();
            for (int i = 0; i < devices.length(); ++i) {
                var device = devices.getJSONObject(i);
                if (device.getBoolean("input") != input || !device.getBoolean("selectable")) continue;
                if (!keys.add(device.getString("key"))) continue;
                var rates = device.getJSONArray("sample_rates");
                var channels = device.getJSONArray("channel_counts");
                table.append(device.getInt("id")).append('\t').append(direction).append('\t')
                    .append(device.getString("key")).append('\t')
                    .append(rates.length() == 1 ? rates.getInt(0) : 0).append('\t')
                    .append(channels.length() == 1 ? channels.getInt(0) : 0).append('\t')
                    .append(device.getInt("kind")).append('\n');
            }
        }
        result.putString("audio_table_json", new JSONObject().put("table", table.toString()).toString());
        instrumentation.sendStatus(2, result);
    }
}
