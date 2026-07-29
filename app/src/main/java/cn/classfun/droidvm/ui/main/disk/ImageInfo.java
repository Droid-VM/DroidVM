// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.main.disk;

final class ImageInfo {
    final long virtualSize;
    final long actualSize;

    ImageInfo(long virtualSize, long actualSize) {
        this.virtualSize = virtualSize;
        this.actualSize = actualSize;
    }
}
