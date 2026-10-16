// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import cn.classfun.droidvm.lib.store.base.VmConfigOwnership;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class VmOwnershipTest {
    private File directory() { return new File(InstrumentationRegistry.getArguments().getString("directory")); }
    @org.junit.Test public void holdLegacyWrite() throws Exception {
        File directory = directory();
        VmConfigOwnership.requireLegacy(directory);
        VmConfigOwnership.legacyWrite(directory, () -> {
            Bundle ready = new Bundle(); ready.putBoolean("ownership_ready", true);
            InstrumentationRegistry.getInstrumentation().sendStatus(2, ready);
            File release = new File(directory, "release");
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (!release.exists() && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue("Host must release legacy writer", release.exists());
            Files.writeString(new File(directory, "vms.json").toPath(), "legacy committed");
        });
    }
    @org.junit.Test public void rejectsPersistentNativeOwnership() throws Exception {
        File directory = directory();
        assertEquals("native-v1\n", Files.readString(new File(directory, VmConfigOwnership.FILE_NAME).toPath()));
        try { VmConfigOwnership.legacyWrite(directory, () -> fail("Owned config must never be overwritten")); fail(); }
        catch (IOException expected) { /* no daemon process or socket is needed */ }
        try { VmConfigOwnership.requireLegacy(directory); fail("Legacy daemon must refuse startup"); }
        catch (IOException expected) { /* also applies before force takeover */ }
        assertEquals("legacy committed", Files.readString(new File(directory, "vms.json").toPath()));
        // Invalid/partial markers must also remain fail-closed for Java.
        Files.writeString(new File(directory, VmConfigOwnership.FILE_NAME).toPath(), "native-");
        try { VmConfigOwnership.legacyWrite(directory, () -> fail()); fail(); }
        catch (IOException expected) { /* interrupted transition cannot restore legacy permission */ }
    }
}
