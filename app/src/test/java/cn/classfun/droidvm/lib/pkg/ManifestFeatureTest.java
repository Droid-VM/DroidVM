package cn.classfun.droidvm.lib.pkg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;

/**
 * The version stamp is a function of the features a package uses, never of their order: each
 * feature says when it arrived, the package takes the highest. So a plain package stays at the
 * base version, and the version this build can read follows from the feature list itself.
 */
public class ManifestFeatureTest {
    private static DiskEntry entry(String path) {
        return new DiskEntry(new DiskRef(0, path));
    }

    @Test
    public void aPackageWithNoFeaturesIsBaseVersion() {
        assertEquals(PackageConstants.MANIFEST_VERSION_BASE,
            ManifestFeature.versionFor(EnumSet.noneOf(ManifestFeature.class)));
    }

    @Test
    public void theStampIsTheHighestFeatureUsed() {
        for (var feature : ManifestFeature.values())
            assertEquals(feature.since, ManifestFeature.versionFor(EnumSet.of(feature)));
        assertEquals(ManifestFeature.latest(),
            ManifestFeature.versionFor(EnumSet.allOf(ManifestFeature.class)));
    }

    @Test
    public void everyFeatureIsNewerThanBase() {
        for (var feature : ManifestFeature.values())
            assertTrue(feature.name(), feature.since > PackageConstants.MANIFEST_VERSION_BASE);
        assertEquals(ManifestFeature.latest(), PackageConstants.MANIFEST_VERSION);
    }

    @Test
    public void aPlainDiskUsesNothing() {
        var used = EnumSet.noneOf(ManifestFeature.class);
        entry("/vm/root.img").collectFeatures(used);
        assertTrue(used.isEmpty());
    }

    @Test
    public void anOverlayAndItsBaseBothNeedTheChainFeature() {
        var overlay = entry("/vm/top.qcow2");
        overlay.backingArchive = "base.qcow2";
        var base = entry("/vm/base.qcow2");
        base.attached = false;
        for (var e : new DiskEntry[]{overlay, base}) {
            var used = EnumSet.noneOf(ManifestFeature.class);
            e.collectFeatures(used);
            assertEquals(EnumSet.of(ManifestFeature.BACKING_CHAIN), used);
        }
    }
}
