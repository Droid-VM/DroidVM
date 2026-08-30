// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.lxc;

/** Linux-VM entry point backed by the shared LXC image catalogue/download flow. */
public final class CreateLinuxVmActivity extends ImportLxcImagesActivity {
    @Override
    protected boolean isLinuxVmMode() {
        return true;
    }
}
