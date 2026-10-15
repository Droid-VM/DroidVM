// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import cn.classfun.droidvm.lib.daemon.Protocol;
import org.json.JSONObject;
import org.junit.Test;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public final class NativeDaemonContractTest {
    @Test public void javaProtocolAndNativeLock() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        try (var channel = FileChannel.open(Paths.get(args.getString("lock_path")),
                    StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var lock = channel.tryLock();
            if (lock != null) lock.release();
            assertNull("Java lock must conflict with the native POSIX lock", lock);
        }
        try (var socket = new Socket("127.0.0.1", Integer.parseInt(args.getString("port")))) {
            socket.setSoTimeout(5000);
            var protocol = new Protocol(socket.getInputStream(), socket.getOutputStream());
            String id = UUID.randomUUID().toString();
            protocol.sendPacket(new JSONObject().put("type", "request").put("request_id", id)
                .put("command", "ping").put("request", "PING"));
            var pong = protocol.recvPacket();
            assertEquals(id, pong.getString("request_id"));
            assertEquals("PONG", pong.getString("response"));
            assertTrue(pong.getBoolean("success"));
            protocol.sendPacket(new JSONObject().put("type", "request").put("request_id", UUID.randomUUID().toString())
                .put("command", "version"));
            var version = protocol.recvPacket();
            assertEquals(args.getString("apk_hash"), version.getString("daemon_hash"));
            assertEquals("cn.classfun.droidvm", version.getString("pkg_name"));
            UUID.fromString(version.getString("client_id"));
        }
    }

    @Test public void javaOwnsLock() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        var path = Paths.get(args.getString("lock_path"));
        var release = path.resolveSibling("release-java-lock").toFile();
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            assertNotNull(lock);
            var ready = new Bundle();
            ready.putBoolean("java_lock_ready", true);
            InstrumentationRegistry.getInstrumentation().sendStatus(2, ready);
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (!release.exists() && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue("Host did not release the lock fixture", release.exists());
        }
    }
}
