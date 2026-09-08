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
 * deleted are taken out wherever they sit, a row whose value was re-picked stays where it was,
 * and rows it added go on the head of their layer.</p>
 *
 * <p>The head, not the tail: order inside a layer is priority, and a layer usually ends in the
 * rule that catches everything left. A binding appended behind one of those would never be
 * reached, which reads as "why does this device not attach"; in front of it, it does what the
 * user just asked for and the rules page is still where a different order is arranged.</p>
 *
 * <p>Three-way rather than "replace all of mine": a rule someone added to this controller while
 * the editor was open is mine by the predicate but is not in the snapshot, so it survives. That
 * is the difference between a page that edits the rules and one that owns them.</p>
 *
 * <p>Deliberately free of org.json and of anything Android: the JSON edge is inert under the
 * stubbed android.jar of a unit test, and this is the part worth testing.</p>
 */
public final class XhciBindingDiff {
    /** The target every row a card mints has; see {@link Row#target}. */
    public static final String TARGET_VM = "vm";

    /**
     * One rule in one layer, in the wire shape.
     *
     * <p>{@code controller} is kept verbatim, null included: null means the target VM's first
     * controller, which is what every rule written before controllers existed says, and rewriting
     * it on the way through would change what an untouched rule means.</p>
     *
     * <p>{@code target} is kept verbatim for the same reason and a stronger one: a save rebuilds
     * every layer, rows this page never spoke for included, so a target it dropped on the way in
     * would be a target it rewrote on the way out -- a sink rule quietly turned into a host rule,
     * and the device it was hiding handed back to Android. A token this build has no word for
     * goes back out as it came in.</p>
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
        /** What the rule does with what it matches: {@code host}, {@code vm} or {@code sink}. */
        @NonNull
        public final String target;
        /**
         * What makes this row the same rule as the one it was edited from.
         *
         * <p>Rows are compared by their fields everywhere else, deliberately: two identical
         * rules in one layer are two rules. But re-picking a row's device is not "delete this
         * rule and write another" -- the rule kept its place in the list the user was looking
         * at, and that place is its priority. Never compared, never written to the wire.</p>
         */
        private final Object identity;

        /**
         * A row this page made, which is by definition a binding to one of its own controllers:
         * a card says "this device goes to this VM's xHCI" and has no way of saying anything
         * else. Rows read from the wire keep whatever target they carried.
         */
        public Row(@Nullable String id, @Nullable String port, @Nullable String vm,
                   @Nullable String controller) {
            this(id, port, vm, controller, TARGET_VM, new Object());
        }

        public Row(@Nullable String id, @Nullable String port, @Nullable String vm,
                   @Nullable String controller, @NonNull String target) {
            this(id, port, vm, controller, target, new Object());
        }

        private Row(@Nullable String id, @Nullable String port, @Nullable String vm,
                    @Nullable String controller, @NonNull String target,
                    @NonNull Object identity) {
            this.id = id;
            this.port = port;
            this.vm = vm;
            this.controller = controller;
            this.target = target;
            this.identity = identity;
        }

        /**
         * The same row with its target VM filled in.
         *
         * <p>A row added while editing carries no VM: a new VM's id is minted by the save
         * itself, so there is nothing to carry until the config has been written.</p>
         */
        @NonNull
        public Row withVm(@NonNull String vmId) {
            return vm != null ? this : new Row(id, port, vmId, controller, target, identity);
        }

        /**
         * The same row pointed at something else: the picker a row's value button reopens edits
         * the rule, it does not replace it, so the result stays the row it was.
         *
         * <p>The controller is written explicitly, whatever the row carried before: a rule
         * touched through a card names the card it was touched on, so deleting that controller
         * leaves the rule visibly dangling instead of quietly moving it to another one.</p>
         */
        @NonNull
        public Row edited(@Nullable String newId, @Nullable String newPort,
                          @Nullable String newController) {
            return new Row(newId, newPort, vm, newController, target, identity);
        }

        /** Whether [other] is this same row before it was edited; see {@link #identity}. */
        public boolean isEditOf(@NonNull Row other) {
            return identity == other.identity;
        }

        /** Field-wise equality, null-safe. Rows are compared, never identified: two identical
         *  rules in one layer are two rules, and deleting one must leave the other. */
        public boolean sameAs(@NonNull Row other) {
            return Objects.equals(id, other.id)
                && Objects.equals(port, other.port)
                && Objects.equals(vm, other.vm)
                && Objects.equals(controller, other.controller)
                && target.equals(other.target);
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
                    // A row that was edited rather than removed takes the place of the one it
                    // was edited from; anything else the page deleted simply goes.
                    var edit = takeEditOf(added, deleted.remove(at));
                    if (edit != null) out.add(edit);
                    continue;
                }
            }
            out.add(row);
        }
        // At the head, in the order the page made them: a layer usually ends in a rule that
        // takes everything left, and a binding written behind one of those never fires.
        out.addAll(0, added);
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

    /** The pending addition that is [row] after an edit of its value, taken out of [added]. */
    @Nullable
    private static Row takeEditOf(@NonNull List<Row> added, @NonNull Row row) {
        for (int i = 0; i < added.size(); i++)
            if (added.get(i).isEditOf(row)) return added.remove(i);
        return null;
    }

    private static int indexOfSame(@NonNull List<Row> rows, @NonNull Row row) {
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).sameAs(row)) return i;
        return -1;
    }
}
