// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.pkg;

import androidx.annotation.NonNull;

import java.util.EnumSet;
import java.util.Set;

/**
 * Everything a package can contain that a reader from before it would misread, each with the
 * manifest version that introduced it. A package is stamped with the highest {@link #since} among
 * the features it actually uses ({@link #versionFor}), so one that uses none stays readable by
 * every build that ever wrote a package, and what a build can read is simply {@link #latest()}.
 *
 * <p>Adding a feature is one constant here plus the {@link Carrier} that reports using it. The
 * version arithmetic never changes and no feature has to know about any other, which is the
 * point: a chain of "if this then 3, else if that then 2" would have to be kept in the right
 * order by hand, and a wrong order silently stamps a package lower than it needs.
 */
public enum ManifestFeature {
    /**
     * Files the VM does not attach, and overlay-to-base links ({@code attached} and
     * {@code backing_archive} on a disk entry). A reader without it attaches every file as a
     * disk and never re-points the overlays at their copied bases.
     */
    BACKING_CHAIN(2);

    /** The manifest version that introduced the feature. */
    public final int since;

    ManifestFeature(int since) {
        this.since = since;
    }

    /** A part of a manifest that can use features; it says which ones it actually does. */
    public interface Carrier {
        void collectFeatures(@NonNull Set<ManifestFeature> into);
    }

    /** The version a package using exactly {@code used} must be stamped with. */
    public static int versionFor(@NonNull Set<ManifestFeature> used) {
        int version = PackageConstants.MANIFEST_VERSION_BASE;
        for (var feature : used) version = Math.max(version, feature.since);
        return version;
    }

    /** The newest version any feature needs: the most a build with this list can read. */
    public static int latest() {
        return versionFor(EnumSet.allOf(ManifestFeature.class));
    }
}
