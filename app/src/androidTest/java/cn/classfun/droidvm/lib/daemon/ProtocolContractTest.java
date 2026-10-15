// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.daemon;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs against Android's real org.json, not the no-op unit-test android.jar. */
@RunWith(AndroidJUnit4.class)
public final class ProtocolContractTest {
    private JSONArray fixtures() throws Exception {
        var assets = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        try (var input = assets.open("migration/protocol-frames.json")) {
            return new JSONObject(new String(input.readAllBytes(), UTF_8)).getJSONArray("cases");
        }
    }

    private static byte[] hex(String text) {
        assertEquals(0, text.length() % 2);
        var bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    private static Protocol reader(byte[] bytes, boolean fragmented) {
        var input = new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int count) {
                return super.read(buffer, offset, fragmented ? Math.min(count, 1) : count);
            }
        };
        return new Protocol(input, new ByteArrayOutputStream());
    }

    private static byte[] header(int length) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(length).array();
    }

    @Test
    public void readsJavaAndCppFramesIncludingOneByteFragmentsAndCoalescing() throws Exception {
        var cases = fixtures();
        for (var field : new String[]{"java_frame_hex", "cpp_frame_hex"}) {
            var combined = new ByteArrayOutputStream();
            for (int i = 0; i < cases.length(); i++)
                combined.write(hex(cases.getJSONObject(i).getString(field)));
            for (boolean fragmented : new boolean[]{false, true}) {
                var protocol = reader(combined.toByteArray(), fragmented);
                for (int i = 0; i < cases.length(); i++) {
                    var fixture = cases.getJSONObject(i);
                    var expected = new JSONObject(fixture.getString("json"));
                    var actual = protocol.recvPacket();
                    assertNotNull(fixture.getString("name"), actual);
                    // Fixtures have one field so key iteration order is not a comparison oracle.
                    var key = expected.keys().next();
                    assertEquals(1, actual.length());
                    if (expected.get(key) instanceof JSONArray)
                        assertEquals(expected.getJSONArray(key).toString(), actual.getJSONArray(key).toString());
                    else
                        assertEquals(expected.get(key), actual.get(key));
                }
                assertNull(protocol.recvPacket());
            }
        }
    }

    @Test
    public void javaSenderMatchesGoldenBytesIncludingUtf8LengthAndTrailingNul() throws Exception {
        var cases = fixtures();
        for (int i = 0; i < cases.length(); i++) {
            var fixture = cases.getJSONObject(i);
            var output = new ByteArrayOutputStream();
            new Protocol(new ByteArrayInputStream(new byte[0]), output)
                .sendPacket(new JSONObject(fixture.getString("json")));
            assertArrayEquals(fixture.getString("name"),
                hex(fixture.getString("java_frame_hex")), output.toByteArray());
        }
    }

    @Test
    public void eofAtEveryPartialFramePositionReturnsNull() throws Exception {
        var cases = fixtures();
        for (int i = 0; i < cases.length(); i++) {
            var frame = hex(cases.getJSONObject(i).getString("java_frame_hex"));
            for (int end = 0; end < frame.length; end++)
                assertNull(reader(java.util.Arrays.copyOf(frame, end), true).recvPacket());
        }
    }

    @Test
    public void rejectsInvalidLengthBeforeAttemptingToReadPayload() {
        for (int size : new int[]{0, -1, Integer.MIN_VALUE, Protocol.IPC_MAX_PAYLOAD + 1}) {
            var protocol = reader(header(size), false);
            assertThrows(IOException.class, protocol::recvPacket);
        }
    }

    @Test
    public void acceptsExactlyEightMiBAndCountsTheOptionalNul() throws Exception {
        var payload = new byte[Protocol.IPC_MAX_PAYLOAD];
        java.util.Arrays.fill(payload, (byte) ' ');
        payload[0] = '{';
        payload[1] = '}';
        for (boolean nul : new boolean[]{false, true}) {
            payload[payload.length - 1] = nul ? 0 : (byte) ' ';
            var frame = new ByteArrayOutputStream();
            frame.write(header(payload.length));
            frame.write(payload);
            assertEquals(0, reader(frame.toByteArray(), false).recvPacket().length());
        }
    }

    @Test
    public void rejectsNonObjectJsonAndMalformedJson() throws Exception {
        for (var text : new String[]{"[]", "null", "{", "\"text\""}) {
            var frame = new ByteArrayOutputStream();
            var payload = text.getBytes(UTF_8);
            frame.write(header(payload.length));
            frame.write(payload);
            assertThrows(JSONException.class, reader(frame.toByteArray(), false)::recvPacket);
        }
    }

    @Test
    public void concurrentResponsesRemainWholeFrames() throws Exception {
        var output = new ByteArrayOutputStream();
        var protocol = new Protocol(new ByteArrayInputStream(new byte[0]), output);
        var pool = Executors.newFixedThreadPool(4);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 64; i++) {
                final int id = i;
                futures.add(pool.submit(() -> {
                    protocol.sendPacket(new JSONObject().put("id", id));
                    return null;
                }));
            }
            for (var future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        var received = reader(output.toByteArray(), false);
        var ids = new HashSet<Integer>();
        for (int i = 0; i < 64; i++) assertTrue(ids.add(received.recvPacket().getInt("id")));
        assertNull(received.recvPacket());
        assertEquals(64, ids.size());
    }
}
