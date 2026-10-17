// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.disk.action;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import org.junit.Test;
import cn.classfun.droidvm.lib.store.disk.DiskStore;

public final class BackingChainLinkerTest {
    private static class Images implements BackingChainLinker.Images {
        boolean missing, unreadable, failRebase, cycle;
        int rebases;
        public BackingChainLinker.Header inspect(String path) throws Exception {
            if (unreadable) throw new IOException("unreadable");
            var backing = path.equals("/images/child") ? "parent" : cycle ? "child" : "";
            return new BackingChainLinker.Header(backing, backing);
        }
        public boolean exists(String path) { return !missing; }
        public void rebase(String path, String backing) throws Exception {
            rebases++;
            if (failRebase) throw new IOException("rebase failed");
        }
    }

    @Test public void unreadableImageIsNotAnEmptyChain() {
        var images = new Images(); images.unreadable = true;
        assertThrows(IOException.class, () -> BackingChainLinker.walkChain(
            new DiskStore(), "/images/child", new ArrayList<>(), images));
    }

    @Test public void missingParentDoesNotRewriteHeader() throws Exception {
        var images = new Images(); images.missing = true;
        var hops = new ArrayList<BackingChainLinker.Hop>();
        assertEquals("/images/parent", BackingChainLinker.walkChain(
            new DiskStore(), "/images/child", hops, images));
        assertEquals(0, images.rebases);
        assertTrue(hops.isEmpty());
    }

    @Test public void failedRebaseDoesNotPublishHop() {
        var images = new Images(); images.failRebase = true;
        var hops = new ArrayList<BackingChainLinker.Hop>();
        assertThrows(IOException.class, () -> BackingChainLinker.walkChain(
            new DiskStore(), "/images/child", hops, images));
        assertTrue(hops.isEmpty());
    }

    @Test public void cycleIsRejected() {
        var images = new Images(); images.cycle = true;
        assertThrows(IOException.class, () -> BackingChainLinker.walkChain(
            new DiskStore(), "/images/child", new ArrayList<>(), images));
    }

    @Test public void completeChainResolvesRelativeParent() throws Exception {
        var images = new Images();
        var hops = new ArrayList<BackingChainLinker.Hop>();
        assertNull(BackingChainLinker.walkChain(new DiskStore(), "/images/child", hops, images));
        assertEquals(1, hops.size());
        assertEquals("/images/parent", hops.get(0).parentPath);
        assertEquals(1, images.rebases);
    }
}
