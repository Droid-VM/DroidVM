// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class WebUiClientTest {
    private static WebUiClient.Settings settings() { return new WebUiClient.Settings(false, 8080, "lan", List.of()); }
    private static JSONObject status() throws Exception {
        return new JSONObject().put("success", true).put("settings", settings().json()).put("running", false)
            .put("state", "disabled").put("error", JSONObject.NULL).put("addresses", new JSONArray()).put("sessions", new JSONArray());
    }
    private static JSONObject caps() throws Exception {
        return new JSONObject().put("success", true).put("webui", "http-session-v1").put("commands",
            new JSONArray(List.of("web_status", "web_configure", "web_reload", "web_pair", "web_revoke")));
    }
    private interface Answer { JSONObject get(JSONObject request) throws Exception; }
    private static final class Fixture {
        final List<JSONObject> requests = new ArrayList<>();
        Answer answer = request -> status();
        final WebUiClient client = new WebUiClient(request -> {
            requests.add(new JSONObject(request.toString()));
            try { return "capabilities".equals(request.getString("command")) ? caps() : answer.get(request); }
            catch (org.json.JSONException | IOException e) { throw e; }
            catch (Exception e) { throw new IOException(e); }
        });
        long count(String command) { return requests.stream().filter(r -> command.equals(r.optString("command"))).count(); }
    }
    @Test public void absentOrPartialCapabilitiesNeverFallBackToFiles() throws Exception {
        WebUiClient absent = new WebUiClient(request -> new JSONObject().put("success", true));
        assertThrows(WebUiClient.Unsupported.class, () -> absent.read(false));
        WebUiClient partial = new WebUiClient(request -> {
            try { return caps().put("commands", new JSONArray(List.of("web_status"))); }
            catch (Exception e) { throw new IOException(e); }
        });
        assertThrows(WebUiClient.Unsupported.class, () -> partial.read(false));
    }
    @Test public void settingsAreImmutableAndTheExactEditingBaselineIsSent() throws Exception {
        Fixture f = new Fixture();
        ArrayList<String> addresses = new ArrayList<>(List.of("http://host.example:8080"));
        WebUiClient.Settings baseline = new WebUiClient.Settings(false, 8080, "lan", addresses);
        addresses.clear();
        WebUiClient.Settings next = new WebUiClient.Settings(true, 8081, "all", baseline.publicOrigins);
        f.client.configure(baseline, next);
        JSONObject sent = f.requests.stream().filter(r -> "web_configure".equals(r.optString("command"))).findFirst().orElseThrow();
        assertEquals("http://host.example:8080", sent.getJSONObject("expected").getJSONArray("publicOrigins").getString(0));
        assertFalse(sent.getJSONObject("expected").getBoolean("enabled"));
        assertEquals(8081, sent.getJSONObject("settings").getInt("port"));
        assertThrows(UnsupportedOperationException.class, () -> baseline.publicOrigins.clear());
    }
    @Test public void changedDaemonCapabilitiesBlockTheWriteBeforeSubmission() throws Exception {
        boolean[] compatible = {true};
        int[] writes = {0};
        WebUiClient client = new WebUiClient(request -> {
            try {
                if ("capabilities".equals(request.getString("command")))
                    return compatible[0] ? caps() : new JSONObject().put("success", true).put("webui", "future-version");
                if ("web_configure".equals(request.getString("command"))) ++writes[0];
                return status();
            } catch (Exception e) { throw new IOException(e); }
        });
        client.read(false); compatible[0] = false;
        assertThrows(WebUiClient.Unsupported.class, () -> client.configure(settings(), settings()));
        assertEquals(0, writes[0]); assertFalse(client.needsReload());
    }
    @Test public void legacySettingsHideLanAutoLoginAndPreserveTheirWireShape() throws Exception {
        Fixture f = new Fixture();
        WebUiClient.Settings old = f.client.read(false).settings;
        assertFalse(old.lanAutoLogin); assertFalse(old.lanAutoLoginSupported);
        WebUiClient.Settings edited = old.withConnection(true, 8081, "all", List.of("http://host.example:8081"));
        assertFalse(edited.json().has("lanAutoLogin"));
        assertThrows(IllegalStateException.class, () -> old.withLanAutoLogin(true));
        f.client.configure(old, edited);
        JSONObject sent = f.requests.stream().filter(r -> "web_configure".equals(r.optString("command"))).findFirst().orElseThrow();
        assertFalse(sent.getJSONObject("expected").has("lanAutoLogin"));
        assertFalse(sent.getJSONObject("settings").has("lanAutoLogin"));
    }
    @Test public void connectionEditsPreserveLanAutoLoginAndToggleUsesTheLoadedBaseline() throws Exception {
        Fixture f = new Fixture();
        f.answer = request -> status().put("settings", settings().json().put("lanAutoLogin", true));
        WebUiClient.Settings old = f.client.read(false).settings;
        assertTrue(old.lanAutoLoginSupported); assertTrue(old.lanAutoLogin);
        WebUiClient.Settings edited = old.withConnection(true, 8081, "all", List.of("http://host.example:8081"));
        assertTrue(edited.lanAutoLogin); assertTrue(edited.json().getBoolean("lanAutoLogin"));
        f.answer = request -> status().put("settings", request.getJSONObject("settings"));
        WebUiClient.Settings saved = f.client.configure(old, edited).settings;
        assertTrue(saved.lanAutoLogin); assertEquals(8081, saved.port);
        assertFalse(f.client.configure(saved, saved.withLanAutoLogin(false)).settings.lanAutoLogin);
        List<JSONObject> writes = f.requests.stream().filter(r -> "web_configure".equals(r.optString("command")))
            .collect(java.util.stream.Collectors.toList());
        assertTrue(writes.get(1).getJSONObject("expected").getBoolean("lanAutoLogin"));
        assertFalse(writes.get(1).getJSONObject("settings").getBoolean("lanAutoLogin"));
    }
    @Test public void lanAutoLoginCannotReportSuccessWhenServiceDropsTheSetting() throws Exception {
        Fixture f = new Fixture();
        WebUiClient.Settings supported = new WebUiClient.Settings(false, 8080, "lan", List.of(), false);
        assertThrows(IOException.class, () -> f.client.configure(supported, supported.withLanAutoLogin(true)));
        assertTrue(f.client.needsReload()); assertEquals(1, f.count("web_configure"));
        f.client.read(true);
        f.answer = request -> status().put("settings", supported.json());
        assertThrows(IOException.class, () -> f.client.configure(supported, supported.withLanAutoLogin(true)));
        assertTrue(f.client.needsReload()); assertEquals(2, f.count("web_configure"));
    }
    @Test public void lostMutationReplyRequiresExplicitReloadAndIsNeverReplayed() throws Exception {
        Fixture f = new Fixture(); f.answer = request -> { throw new IOException("lost reply"); };
        assertThrows(IOException.class, () -> f.client.configure(settings(), settings()));
        assertTrue(f.client.needsReload());
        f.answer = request -> status();
        f.client.read(false);
        assertTrue(f.client.needsReload());
        assertThrows(WebUiClient.ReloadRequired.class, () -> f.client.configure(settings(), settings()));
        assertEquals(1, f.count("web_configure"));
        f.client.read(true);
        assertFalse(f.client.needsReload()); assertEquals(1, f.count("web_reload"));
        assertEquals(1, f.count("web_configure"));
    }
    @Test public void malformedSuccessCannotUnlockAnotherWrite() throws Exception {
        Fixture f = new Fixture(); f.answer = request -> new JSONObject().put("success", true);
        assertThrows(Exception.class, () -> f.client.configure(settings(), settings()));
        assertTrue(f.client.needsReload()); assertEquals(1, f.count("web_configure"));
        f.answer = request -> status().put("error", "configuration_write_failed").put("state", "error");
        f.client.read(true); assertTrue(f.client.needsReload());
    }
    @Test public void conflictsAndUncertainCommitsKeepTheirNativeErrorCodes() throws Exception {
        for (String code : List.of("CONFLICT", "COMMIT_UNCERTAIN")) {
            Fixture f = new Fixture();
            f.answer = request -> new JSONObject().put("success", false).put("error", new JSONObject().put("code", code));
            VmConfigClient.RemoteFailure failure = assertThrows(VmConfigClient.RemoteFailure.class, () -> f.client.configure(settings(), settings()));
            assertEquals(code, failure.code); assertTrue(f.client.needsReload());
        }
    }
    @Test public void rejectedPairingDoesNotPretendThatASecretWasIssued() throws Exception {
        Fixture f = new Fixture();
        f.answer = request -> new JSONObject().put("success", false).put("error", new JSONObject().put("code", "RESOURCE_EXHAUSTED"));
        assertThrows(VmConfigClient.RemoteFailure.class, () -> f.client.pair());
        assertFalse(f.client.needsReload());
        f.answer = request -> new JSONObject().put("success", true).put("pairingToken", "a".repeat(64));
        assertEquals("a".repeat(64), f.client.pair());
        f.answer = request -> new JSONObject().put("success", true).put("pairingToken", "bad");
        assertThrows(IOException.class, () -> f.client.pair()); assertTrue(f.client.needsReload());
    }
    @Test public void revokeUsesPublicIdAndLostReplyCannotBeRetriedAutomatically() throws Exception {
        Fixture f = new Fixture(); f.client.revoke("public-session-id");
        assertEquals("public-session-id", f.requests.stream().filter(r -> "web_revoke".equals(r.optString("command"))).findFirst().orElseThrow().getString("session_id"));
        f.answer = request -> { throw new IOException("lost revoke reply"); };
        assertThrows(IOException.class, () -> f.client.revoke("public-session-id"));
        assertThrows(WebUiClient.ReloadRequired.class, () -> f.client.revoke("public-session-id"));
        assertEquals(2, f.count("web_revoke"));
    }
    @Test public void strictStatusRejectsCoercedFieldsAndUntrustedAddressSchemes() throws Exception {
        Fixture f = new Fixture();
        f.answer = request -> status().put("running", "false");
        assertThrows(IOException.class, () -> f.client.read(false));
        f.answer = request -> status().put("addresses", new JSONArray(List.of("javascript:alert(1)")));
        assertThrows(IOException.class, () -> f.client.read(false));
        f.answer = request -> status().put("settings", settings().json().put("port", 8080.0));
        assertThrows(IOException.class, () -> f.client.read(false));
        f.answer = request -> status().put("settings", settings().json().put("lanAutoLogin", "false"));
        assertThrows(IOException.class, () -> f.client.read(false));
        f.answer = request -> status().put("sessions", new JSONArray(List.of("duplicate", "duplicate")));
        assertThrows(IOException.class, () -> f.client.read(false));
    }
    @Test public void originAndPortValidationAcceptsIPv6AndRejectsCredentialsPathsAndQueries() {
        new WebUiClient.Settings(true, 1024, "all", List.of("http://[::1]:8080", "http://vm.example", "http://192.0.2.1:9000"));
        for (String value : List.of("https://vm.example", "http://user:pass@host", "http://host/path", "http://host?code=secret",
                "http://host#fragment", "http://host:0", "http://host:65536", "http://host/")) {
            assertThrows(IllegalArgumentException.class, () -> new WebUiClient.Settings(true, 8080, "lan", List.of(value)));
        }
        assertThrows(IllegalArgumentException.class, () -> new WebUiClient.Settings(false, 80, "lan", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WebUiClient.Settings(false, 8080, "unknown", List.of()));
    }
}
