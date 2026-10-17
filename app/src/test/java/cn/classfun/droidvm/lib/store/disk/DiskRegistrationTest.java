// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.store.disk;

import static org.junit.Assert.assertThrows;
import java.io.IOException;
import org.junit.Test;

public final class DiskRegistrationTest {
    private static DiskConfig disk(String folder) {
        var config = new DiskConfig();
        config.setName("same.qcow2");
        config.item.set("folder", folder);
        return config;
    }

    @Test public void sameFilenameInDifferentDirectoriesIsAllowed() throws Exception {
        var store = new DiskStore(); store.add(disk("/a"));
        DiskStore.requireNewRegistration(store, disk("/b"));
    }

    @Test public void sameLogicalPathWithAnotherIdIsRejected() {
        var store = new DiskStore(); store.add(disk("/a/"));
        assertThrows(IOException.class, () -> DiskStore.requireNewRegistration(store, disk("/a")));
    }

    @Test public void reusedIdentityAtAnotherPathIsRejected() {
        var existing = disk("/a");
        var store = new DiskStore(); store.add(existing);
        var candidate = disk("/b"); candidate.setId(existing.getId());
        assertThrows(IOException.class, () -> DiskStore.requireNewRegistration(store, candidate));
    }
}
