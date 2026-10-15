// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import androidx.test.platform.app.InstrumentationRegistry;
import dalvik.system.InMemoryDexClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Loads actual production classes from the built APK without its Application. */
public class NetworkConfigOracleTest {
    private static Object invoke(Object instance, String method) throws Exception {
        return instance.getClass().getMethod(method).invoke(instance);
    }
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
        var configType = loader.loadClass("cn.classfun.droidvm.lib.store.network.NetworkConfig");
        var validator = loader.loadClass("cn.classfun.droidvm.lib.store.network.NetworkConfigValidator");
        var vlanType = loader.loadClass("cn.classfun.droidvm.lib.store.network.VlanConfig");
        assertSame(loader, configType.getClassLoader());
        var fixtures = new JSONArray(Files.readString(Path.of(args.getString("fixtures")), StandardCharsets.UTF_8));
        var report = new JSONArray();
        for (int i = 0; i < fixtures.length(); ++i) {
            var test = fixtures.getJSONObject(i);
            var row = new JSONObject().put("name", test.getString("name"));
            try {
                if (test.getString("op").equals("default_vlan")) {
                    var vlan = vlanType.getMethod("createDefault", int.class).invoke(null, test.getInt("vlan_id"));
                    var item = vlanType.getField("item").get(vlan);
                    row.put("config", invoke(item, "toJson"));
                } else {
                    var value = test.getJSONObject("config");
                    if (test.getString("op").equals("migrate")) {
                        value = (JSONObject) configType.getMethod("migrate", JSONObject.class).invoke(null, value);
                        if (value == null) { row.put("status", "unsupported"); report.put(row); continue; }
                    }
                    var config = configType.getConstructor(JSONObject.class).newInstance(value);
                    validator.getMethod("validate", configType).invoke(null, config);
                    var view = new JSONObject();
                    view.put("id", invoke(config, "getId").toString());
                    view.put("name", invoke(config, "getName"));
                    view.put("bridge_type", invoke(invoke(config, "getBridgeType"), "key"));
                    view.put("uplink_mode", invoke(invoke(config, "getUplinkMode"), "key"));
                    view.put("stp", invoke(config, "isStp"));
                    view.put("auto_up", invoke(config, "isAutoUp"));
                    var mac = invoke(config, "getBridgeMacAddress");
                    view.put("bridge_mac", mac == null ? JSONObject.NULL : mac);
                    row.put("view", view);
                    row.put("config", invoke(config, "toJson"));
                    try {
                        var reloaded = configType.getConstructor(JSONObject.class).newInstance(
                            new JSONObject(invoke(config, "toJson").toString()));
                        validator.getMethod("validate", configType).invoke(null, reloaded);
                        row.put("roundtrip_status", "ok");
                    } catch (Exception error) {
                        Throwable cause = error;
                        while (cause instanceof InvocationTargetException) cause = cause.getCause();
                        row.put("roundtrip_status", "error").put("roundtrip_message", cause.getMessage());
                    }
                }
                row.put("status", "ok");
            } catch (Exception error) {
                Throwable cause = error;
                while (cause instanceof InvocationTargetException) cause = cause.getCause();
                row.put("status", "error").put("message", cause.getMessage());
            }
            report.put(row);
        }
        Files.writeString(Path.of(args.getString("report")), report.toString(), StandardCharsets.UTF_8);
        assertEquals(fixtures.length(), report.length());
    }
}
