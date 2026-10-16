// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import cn.classfun.droidvm.lib.store.base.DiskConfigOwnership;
import org.junit.Test;

public final class DiskOwnershipTest {
    @Test public void nestedWritesAndPersistentMarkersUseIndependentDiskGate() throws Exception {
        NetworkOwnershipTest.checkGate(DiskConfigOwnership.FILE_NAME,
            DiskConfigOwnership::legacyWrite, DiskConfigOwnership::requireLegacy);
    }
}
