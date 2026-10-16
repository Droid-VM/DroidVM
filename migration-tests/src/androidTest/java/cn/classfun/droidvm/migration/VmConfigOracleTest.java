// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.zip.ZipFile;

import dalvik.system.InMemoryDexClassLoader;

/** Runs the production VMConfig load-time migration from the built APK. */
public class VmConfigOracleTest {
    @Test public void evaluateSharedFixtures() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        var dex = new ArrayList<ByteBuffer>();
        try (var apk = new ZipFile(args.getString("oracle_apk"))) {
            var entries = apk.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().matches("classes([0-9]+)?\\.dex")) continue;
                try (var input = apk.getInputStream(entry)) { dex.add(ByteBuffer.wrap(input.readAllBytes())); }
            }
        }
        assertFalse(dex.isEmpty());
        var loader = new InMemoryDexClassLoader(dex.toArray(new ByteBuffer[0]), getClass().getClassLoader());
        var configType = loader.loadClass("cn.classfun.droidvm.lib.store.vm.VMConfig");
        assertSame(loader, configType.getClassLoader());
        var fixtures = new JSONArray(Files.readString(Path.of(args.getString("fixtures")), StandardCharsets.UTF_8));
        var report = new JSONArray();
        for (int i = 0; i < fixtures.length(); ++i) {
            var test = fixtures.getJSONObject(i);
            var row = new JSONObject().put("name", test.getString("name"));
            try {
                var config = configType.getConstructor(JSONObject.class).newInstance(test.getJSONObject("config"));
                var json = (JSONObject) configType.getMethod("toJson").invoke(config);
                // A second load must be a fixed point: the daemon re-runs it on every push.
                var again = configType.getConstructor(JSONObject.class).newInstance(new JSONObject(json.toString()));
                row.put("config", json).put("reloaded", configType.getMethod("toJson").invoke(again)).put("status", "ok");
            } catch (Exception error) {
                Throwable cause = error;
                while (cause instanceof InvocationTargetException) cause = cause.getCause();
                row.put("status", "error").put("message", String.valueOf(cause.getMessage()));
            }
            report.put(row);
        }
        Files.writeString(Path.of(args.getString("report")), report.toString(), StandardCharsets.UTF_8);
        assertEquals(fixtures.length(), report.length());
    }
}
