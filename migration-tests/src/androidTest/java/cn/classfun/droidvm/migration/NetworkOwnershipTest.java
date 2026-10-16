// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import androidx.test.platform.app.InstrumentationRegistry;
import cn.classfun.droidvm.lib.store.base.NetworkConfigOwnership;
import cn.classfun.droidvm.lib.store.base.VmConfigOwnership;
import org.junit.Test;

public final class NetworkOwnershipTest {
    @Test public void nestedWritesAndPersistentMarkersUseIndependentNetworkGate() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getContext();
        var path = Files.createTempDirectory(context.getCacheDir().toPath(), "network-owner-");
        var directory = path.toFile();
        try {
            NetworkConfigOwnership.requireLegacy(directory);
            assertFalse(Files.exists(path.resolve(NetworkConfigOwnership.FILE_NAME)));
            int[] writes = {0};
            NetworkConfigOwnership.legacyWrite(directory, () ->
                NetworkConfigOwnership.legacyWrite(directory, () -> writes[0]++));
            assertEquals(1, writes[0]);
            for (String marker : new String[]{"native-v1\n", "native-", "unknown"}) {
                Files.writeString(path.resolve(NetworkConfigOwnership.FILE_NAME), marker);
                assertThrows(IOException.class, () -> NetworkConfigOwnership.legacyWrite(directory, () -> fail("must not write")));
                assertThrows(IOException.class, () -> NetworkConfigOwnership.requireLegacy(directory));
                VmConfigOwnership.legacyWrite(directory, () -> writes[0]++);
                assertEquals(marker, Files.readString(path.resolve(NetworkConfigOwnership.FILE_NAME)));
            }
            assertEquals(4, writes[0]);
        } finally {
            Files.deleteIfExists(path.resolve(NetworkConfigOwnership.FILE_NAME));
            Files.deleteIfExists(path.resolve(VmConfigOwnership.FILE_NAME));
            Files.delete(path);
        }
    }
}
