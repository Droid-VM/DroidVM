// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.pkg;

import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.safeFileName;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which files a package must carry for one VM's disks. A qcow2 overlay is only half a disk: the
 * guest reads its backing image too, so a package holding the overlay alone imports as a VM that
 * cannot boot - the copied header still names a path that exists only on the machine it came
 * from. This walks each selected disk down its backing chain and returns every file involved,
 * child before parent, with:
 * <ul>
 *   <li>one entry per file, so two disks sharing a base image ship it once;</li>
 *   <li>unique archive names, so two disks named {@code disk.qcow2} in different folders do not
 *       collide inside the tar (they did before backing images made that likely);</li>
 *   <li>the parent link recorded as an archive name, which is what the importer can act on -
 *       the on-disk path is meaningless once the files land somewhere else.</li>
 * </ul>
 *
 * <p>Pure by design: it reads no files itself. The caller supplies the {@link BackingLookup},
 * which is what makes the whole plan testable and lets the export UI predict, with the same
 * code, exactly what the daemon will pack.
 */
public final class DiskChainPlan {
    /**
     * Chain length cap. Far above the depth the overlay UI allows (see {@code DiskTree}); this
     * is only here so a corrupt header cannot spin the walk forever, hence the loud failure
     * rather than a silent truncation - a truncated chain is exactly the broken package this
     * class exists to prevent.
     */
    public static final int MAX_CHAIN = 64;

    private DiskChainPlan() {
    }

    /** Resolves one image's backing file to an absolute path, or null when it has none. */
    public interface BackingLookup {
        @Nullable
        String backingOf(@NonNull String path) throws Exception;
    }

    /** One file the package has to carry. */
    public static final class Member {
        /** Absolute path on the exporting device. */
        public final String path;
        /** Name inside the archive; unique across the package. */
        public final String archivePath;
        /**
         * The VM disk slot this file fills, or null when it is in the package only because
         * something else backs onto it. A file can start out as a backing image and turn out to
         * be an attached disk as well, which is why this is not final.
         */
        @Nullable
        public DiskRef attachment = null;
        /** {@link #archivePath} of this file's own backing image, or "" when it has none. */
        public String backingArchive = "";

        private Member(@NonNull String path, @NonNull String archivePath) {
            this.path = path;
            this.archivePath = archivePath;
        }
    }

    /**
     * Expand {@code tops} - the VM disks the user chose, in slot order - into every file the
     * package needs.
     *
     * @throws IOException when a chain loops or runs deeper than {@link #MAX_CHAIN}; whatever
     *     {@code lookup} throws for an unreadable image or a missing backing file propagates as
     *     it is, so the export fails with the path that caused it.
     */
    @NonNull
    public static List<Member> build(
        @NonNull List<DiskRef> tops,
        @NonNull BackingLookup lookup
    ) throws Exception {
        var order = new ArrayList<Member>();
        var byPath = new HashMap<String, Member>();
        var archives = new HashSet<String>();
        for (var top : tops) {
            if (top.path == null || top.path.isEmpty()) continue;
            var known = byPath.get(top.path);
            Member member;
            if (known == null) {
                member = add(order, byPath, archives, top.path);
            } else if (known.attachment == null) {
                member = known; // already packed as a backing image; it is a disk of its own too
            } else {
                // The same file in two slots. Give the second slot its own copy rather than
                // dropping it: a package that silently loses a disk is worse than a duplicate.
                member = new Member(top.path, uniqueArchive(archives, basename(top.path)));
                order.add(member);
            }
            member.attachment = top;
            walkUp(order, byPath, archives, member, lookup);
        }
        return order;
    }

    /** Follow {@code start}'s backing chain upward, adding each file it reaches. */
    private static void walkUp(
        @NonNull List<Member> order,
        @NonNull Map<String, Member> byPath,
        @NonNull Set<String> archives,
        @NonNull Member start,
        @NonNull BackingLookup lookup
    ) throws Exception {
        var seen = new HashSet<String>();
        seen.add(start.path);
        var child = start;
        for (int depth = 0; depth < MAX_CHAIN; depth++) {
            var backing = lookup.backingOf(child.path);
            if (backing == null || backing.isEmpty()) return;
            if (!seen.add(backing)) throw new IOException(fmt(
                "backing chain of %s loops at %s", basename(start.path), backing
            ));
            var known = byPath.get(backing);
            var parent = known != null ? known : add(order, byPath, archives, backing);
            child.backingArchive = parent.archivePath;
            // A file already in the plan brought its own parents with it when it was added,
            // so there is nothing above this point left to walk.
            if (known != null) return;
            child = parent;
        }
        throw new IOException(fmt(
            "backing chain of %s is deeper than %d images", basename(start.path), MAX_CHAIN
        ));
    }

    @NonNull
    private static Member add(
        @NonNull List<Member> order,
        @NonNull Map<String, Member> byPath,
        @NonNull Set<String> archives,
        @NonNull String path
    ) {
        var member = new Member(path, uniqueArchive(archives, basename(path)));
        order.add(member);
        byPath.put(path, member);
        return member;
    }

    /** {@code name} as an archive entry name no other member has taken. */
    @NonNull
    private static String uniqueArchive(@NonNull Set<String> taken, @NonNull String name) {
        var base = safeFileName(name, "disk.img");
        if (taken.add(base)) return base;
        int dot = base.lastIndexOf('.');
        var stem = dot > 0 ? base.substring(0, dot) : base;
        var ext = dot > 0 ? base.substring(dot) : "";
        for (int i = 1; ; i++) {
            var candidate = fmt("%s_%d%s", stem, i, ext);
            if (taken.add(candidate)) return candidate;
        }
    }
}
