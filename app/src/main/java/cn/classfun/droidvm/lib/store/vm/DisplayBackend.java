// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

/**
 * The legacy either/or display producer: one display, and this said which device made it.
 *
 * <p>Superseded by {@link VMScreenConfig} -- the two devices are independent screens now, and a
 * VM can have both -- so nothing reads this except {@link VMScreenConfig#migrate}, which reads
 * each old config's value exactly once and then drops the key. It is no longer a
 * {@code StringEnum} and carries no labels: it is never shown, only decoded.</p>
 */
public enum DisplayBackend {
    NONE,
    SIMPLEFB,
    VIRTIO_GPU,
}
