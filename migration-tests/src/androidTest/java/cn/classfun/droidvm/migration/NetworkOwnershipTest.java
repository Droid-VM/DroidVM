// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import androidx.test.platform.app.InstrumentationRegistry;
import cn.classfun.droidvm.lib.store.base.NetworkConfigOwnership;
import cn.classfun.droidvm.lib.store.base.VmConfigOwnership;
import org.junit.Test;

public final class NetworkOwnershipTest {
    @Test public void nestedWritesAndPersistentMarkersUseIndependentNetworkGate() throws Exception {
        checkGate(NetworkConfigOwnership.FILE_NAME, NetworkConfigOwnership::legacyWrite, NetworkConfigOwnership::requireLegacy);
    }
    interface WriteGate { void run(File directory, VmConfigOwnership.Write action) throws Exception; }
    interface RequireGate { void run(File directory) throws Exception; }
    static void checkGate(String fileName, WriteGate write, RequireGate require) throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getContext();
        var path = Files.createTempDirectory(context.getCacheDir().toPath(), "network-owner-");
        var directory = path.toFile();
        try {
            require.run(directory);
            assertFalse(Files.exists(path.resolve(fileName)));
            int[] writes = {0};
            write.run(directory, () -> write.run(directory, () -> writes[0]++));
            assertEquals(1, writes[0]);
            for (String marker : new String[]{"native-v1\n", "native-", "unknown"}) {
                Files.writeString(path.resolve(fileName), marker);
                assertThrows(IOException.class, () -> write.run(directory, () -> fail("must not write")));
                assertThrows(IOException.class, () -> require.run(directory));
                VmConfigOwnership.legacyWrite(directory, () -> writes[0]++);
                assertEquals(marker, Files.readString(path.resolve(fileName)));
            }
            assertEquals(4, writes[0]);
        } finally {
            Files.deleteIfExists(path.resolve(fileName));
            Files.deleteIfExists(path.resolve(VmConfigOwnership.FILE_NAME));
            Files.delete(path);
        }
    }
}
