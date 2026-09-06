// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * How one edit of an xHCI card's four zones is folded back into the app-global attach rules.
 *
 * <p>The rules file is not this page's to own: the global rules page edits the same object, and
 * both read-modify-write the whole of it. So the card does not push the list it is showing --
 * it pushes what changed. Rows it never spoke for keep their place and their order, rows it
 * deleted are taken out wherever they sit, and rows it added go on the end of their layer, which
 * is where a new rule belongs when order is priority.</p>
 *
 * <p>Three-way rather than "replace all of mine": a rule someone added to this controller while
 * the editor was open is mine by the predicate but is not in the snapshot, so it survives. That
 * is the difference between a page that edits the rules and one that owns them.</p>
 *
 * <p>Deliberately free of org.json and of anything Android: the JSON edge is inert under the
 * stubbed android.jar of a unit test, and this is the part worth testing.</p>
 */
public final class XhciBindingDiff {
    /**
     * One rule in one layer, in the wire shape.
     *
     * <p>{@code controller} is kept verbatim, null included: null means the target VM's first
     * controller, which is what every rule written before controllers existed says, and rewriting
     * it on the way through would change what an untouched rule means.</p>
     */
    public static final class Row {
        @Nullable
        public final String id;
        @Nullable
        public final String port;
        @Nullable
        public final String vm;
        @Nullable
        public final String controller;

        public Row(@Nullable String id, @Nullable String port, @Nullable String vm,
                   @Nullable String controller) {
            this.id = id;
            this.port = port;
            this.vm = vm;
            this.controller = controller;
        }

        /**
         * The same row with its target VM filled in.
         *
         * <p>A row added while editing carries no VM: a new VM's id is minted by the save
         * itself, so there is nothing to carry until the config has been written.</p>
         */
        @NonNull
        public Row withVm(@NonNull String vmId) {
            return vm != null ? this : new Row(id, port, vmId, controller);
        }

        /** Field-wise equality, null-safe. Rows are compared, never identified: two identical
         *  rules in one layer are two rules, and deleting one must leave the other. */
        public boolean sameAs(@NonNull Row other) {
            return Objects.equals(id, other.id)
                && Objects.equals(port, other.port)
                && Objects.equals(vm, other.vm)
                && Objects.equals(controller, other.controller);
        }
    }

    private XhciBindingDiff() {
    }

    /**
     * One layer's list after this page's edits.
     *
     * @param current  the layer as the daemon holds it right now, re-read at save time
     * @param snapshot the rows of this layer that belonged to this page when it opened
     * @param desired  the rows of this layer that belong to it now
     * @param mine     whether a row is one this page speaks for -- this VM, one of the
     *                 controllers it has seen. Rows it does not speak for are never touched,
     *                 even when they look exactly like one it deleted.
     */
    @NonNull
    public static List<Row> merge(@NonNull List<Row> current, @NonNull List<Row> snapshot,
                                  @NonNull List<Row> desired, @NonNull Predicate<Row> mine) {
        // What the page removed, and what it added, as multisets: the same rule twice in a layer
        // is two rows, and an edit that removes one of them removes exactly one.
        var added = new ArrayList<>(desired);
        var deleted = new ArrayList<Row>();
        for (var row : snapshot) {
            int at = indexOfSame(added, row);
            if (at >= 0) added.remove(at);
            else deleted.add(row);
        }
        var out = new ArrayList<Row>(current.size() + added.size());
        for (var row : current) {
            if (mine.test(row)) {
                int at = indexOfSame(deleted, row);
                if (at >= 0) {
                    deleted.remove(at);
                    continue;
                }
            }
            out.add(row);
        }
        // At the tail, in the order the page made them: within a layer, order is priority, and a
        // rule the user just wrote has no claim on anyone else's.
        out.addAll(added);
        return out;
    }

    /** Whether the two lists hold the same rows, in any order; what "nothing to push" means. */
    public static boolean sameRows(@NonNull List<Row> a, @NonNull List<Row> b) {
        if (a.size() != b.size()) return false;
        var rest = new ArrayList<>(b);
        for (var row : a) {
            int at = indexOfSame(rest, row);
            if (at < 0) return false;
            rest.remove(at);
        }
        return true;
    }

    private static int indexOfSame(@NonNull List<Row> rows, @NonNull Row row) {
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).sameAs(row)) return i;
        return -1;
    }
}
