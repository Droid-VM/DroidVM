// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.action;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.classfun.droidvm.R;

public final class DiskActionPolicyTest {
    @Test
    public void contentMutationsRequireUnlockedImage() {
        assertTrue(DiskActionDialog.modifiesDiskContent(R.id.menu_disk_resize));
        assertTrue(DiskActionDialog.modifiesDiskContent(R.id.menu_disk_convert));
        assertTrue(DiskActionDialog.modifiesDiskContent(R.id.menu_disk_optimize));
        assertTrue(DiskActionDialog.modifiesDiskContent(R.id.menu_disk_change_password));
    }

    @Test
    public void treeAwareAndReadSideActionsStayAvailable() {
        assertFalse(DiskActionDialog.modifiesDiskContent(R.id.menu_disk_create_increment));
        assertFalse(DiskActionDialog.modifiesDiskContent(R.id.menu_disk_merge));
        assertFalse(DiskActionDialog.modifiesDiskContent(R.id.menu_disk_flatten));
        assertFalse(DiskActionDialog.modifiesDiskContent(R.id.menu_disk_show_info));
        assertFalse(DiskActionDialog.modifiesDiskContent(R.id.menu_disk_clone));
        assertFalse(DiskActionDialog.modifiesDiskContent(R.id.menu_disk_delete));
    }
}
