// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.tree;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

/**
 * Where one VM disk slot sits on an overlay tree. Every slot that points into a family has one;
 * tree operations move them (a deleted node's cursors climb to the nearest surviving ancestor,
 * a leaf's writable cursors follow its first overlay down) and the rules about which ones may
 * move silently, which must be announced, and which must not move at all hang off {@link #kind}
 * and {@link #pinned}.
 */
public final class AttachmentCursor {
    public enum Kind {
        /** The editor row this panel was opened from. In memory; applied when the panel closes. */
        ACTIVE,
        /** Another unsaved row of the same editor. In memory; follows silently. */
        EDITOR,
        /**
         * A persisted slot of the VM being edited. Rewritten on disk like PERSISTED (a discarded
         * edit must not leave it dangling) but never announced: the editor's rows shadow it.
         */
        SHADOW,
        /** A persisted slot of any other VM. Rewritten on disk and announced before it happens. */
        PERSISTED,
    }

    @NonNull
    public final Kind kind;
    /** Null for a VM that has never been saved. */
    @Nullable
    public final UUID vmId;
    @NonNull
    public final String vmName;
    /** Index in the VM's disk list (or in the editor's rows). */
    public final int slot;
    /** Null once the whole tree under the cursor is gone. */
    @Nullable
    public final UUID nodeId;
    @Nullable
    public final String path;
    public final boolean readonly;
    /** The VM is not stopped: the slot's file is open, so the cursor must not change. */
    public final boolean pinned;

    public AttachmentCursor( // arity-ok: a value object; these parameters are its fields
        @NonNull Kind kind,
        @Nullable UUID vmId,
        @NonNull String vmName,
        int slot,
        @Nullable UUID nodeId,
        @Nullable String path,
        boolean readonly,
        boolean pinned
    ) {
        this.kind = kind;
        this.vmId = vmId;
        this.vmName = vmName;
        this.slot = slot;
        this.nodeId = nodeId;
        this.path = path;
        this.readonly = readonly;
        this.pinned = pinned;
    }

    /** The same slot at another position. */
    @NonNull
    public AttachmentCursor at(@Nullable UUID nodeId, @Nullable String path, boolean readonly) {
        return new AttachmentCursor(kind, vmId, vmName, slot, nodeId, path, readonly, pinned);
    }

    /** The same cursor with its VM's run state re-read. */
    @NonNull
    public AttachmentCursor withPinned(boolean pinned) {
        return new AttachmentCursor(kind, vmId, vmName, slot, nodeId, path, readonly, pinned);
    }

    /** In-memory editor rows, applied by the editor when the panel closes. */
    public boolean isLive() {
        return kind == Kind.ACTIVE || kind == Kind.EDITOR;
    }

    /** Written to the VM store by the operation itself. */
    public boolean isPersisted() {
        return kind == Kind.SHADOW || kind == Kind.PERSISTED;
    }

    /** Listed in the confirmation before the operation runs. */
    public boolean isAnnounced() {
        return kind == Kind.PERSISTED;
    }

    /**
     * Whether this cursor counts towards "two attachments on one disk". A shadow is the stale
     * twin of an editor row and would double-count it.
     */
    public boolean countsForSharing() {
        return kind != Kind.SHADOW;
    }

    @NonNull
    @Override
    public String toString() {
        return fmt("%s:%s#%d@%s%s%s", kind, vmName, slot, path == null ? "-" : path,
            readonly ? " ro" : " rw", pinned ? " pinned" : "");
    }
}
