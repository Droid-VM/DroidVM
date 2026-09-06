// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.classfun.droidvm.ui.usb.UsbRuleLayer;
import cn.classfun.droidvm.ui.vm.edit.peripheral.XhciBindingDiff.Row;

/**
 * The attach rules of one edit session, grouped the way the cards show them: per controller, per
 * layer.
 *
 * <p>Two copies are kept. The snapshot is what the daemon held when the page opened; the working
 * copy is what the zones show now. The difference between them is the whole of what a save
 * pushes -- see {@link XhciBindingDiff} -- which is why the snapshot is not simply re-read: it
 * is the evidence of what this page changed, and anything read later would have someone else's
 * edits folded into it.</p>
 *
 * <p>Which controller a rule belongs to is decided once, at load: a rule that names none means
 * the VM's first, and re-resolving that later would silently move rows around as cards are
 * added and deleted. The row itself keeps its wire fields untouched.</p>
 */
final class XhciBindingStore {
    private final Map<String, EnumMap<UsbRuleLayer, List<Row>>> snapshot = new LinkedHashMap<>();
    private final Map<String, EnumMap<UsbRuleLayer, List<Row>>> working = new LinkedHashMap<>();
    /**
     * Every controller id this page has spoken for, deleted ones included.
     *
     * <p>A card the user removed takes its rules with it, and the merge can only delete a rule
     * it recognises as this page's -- so an id stays owned after its card is gone.</p>
     */
    private final Set<String> owned = new LinkedHashSet<>();
    private boolean loaded = false;

    /**
     * Takes the rules the daemon holds and keeps the ones this VM's controllers own.
     *
     * @param rules       every layer's rules, as read
     * @param controllers this VM's controller ids in array order; the first is what a rule that
     *                    names none is pointing at
     * @param vmId        this VM, or null for one that does not exist yet -- then nothing is
     *                    this page's, and every row it shows is an addition
     */
    void load(@NonNull Map<UsbRuleLayer, List<Row>> rules, @NonNull List<String> controllers,
              @Nullable String vmId) {
        snapshot.clear();
        working.clear();
        owned.clear();
        owned.addAll(controllers);
        for (var id : controllers) {
            snapshot.put(id, emptyLayers());
            working.put(id, emptyLayers());
        }
        var first = controllers.isEmpty() ? null : controllers.get(0);
        for (var layer : UsbRuleLayer.values()) {
            var rows = rules.get(layer);
            if (rows == null) continue;
            for (var row : rows) {
                if (vmId == null || row.vm == null || !vmId.equals(row.vm)) continue;
                var owner = row.controller == null ? first : row.controller;
                if (owner == null || !snapshot.containsKey(owner)) continue;
                snapshot.get(owner).get(layer).add(row);
                working.get(owner).get(layer).add(row);
            }
        }
        loaded = true;
    }

    /** Whether the rules were read at all; nothing is pushed at save until they were. */
    boolean isLoaded() {
        return loaded;
    }

    /** A controller added during this session: its zones start empty and it is ours from now. */
    void addController(@NonNull String controllerId) {
        owned.add(controllerId);
        working.putIfAbsent(controllerId, emptyLayers());
    }

    /**
     * A card the user deleted. Its rows go with it -- they point at a controller that will not
     * exist after this save, and the merge deletes them because the id stays owned.
     */
    void removeController(@NonNull String controllerId) {
        working.remove(controllerId);
    }

    /** One zone's rows, in the order they are shown. */
    @NonNull
    List<Row> rows(@NonNull String controllerId, @NonNull UsbRuleLayer layer) {
        var layers = working.get(controllerId);
        if (layers == null) return Collections.emptyList();
        return Collections.unmodifiableList(layers.get(layer));
    }

    void add(@NonNull String controllerId, @NonNull UsbRuleLayer layer, @NonNull Row row) {
        addController(controllerId);
        // At the head, which is where the merge will put it in the file: a layer usually ends in
        // the rule that takes everything left, a sink most of all, and a row shown behind one of
        // those would read as a binding that never fires.
        working.get(controllerId).get(layer).add(0, row);
    }

    void replace(@NonNull String controllerId, @NonNull UsbRuleLayer layer, int index,
                 @NonNull Row row) {
        var rows = mutableRows(controllerId, layer);
        if (rows == null || index < 0 || index >= rows.size()) return;
        rows.set(index, row);
    }

    void remove(@NonNull String controllerId, @NonNull UsbRuleLayer layer, int index) {
        var rows = mutableRows(controllerId, layer);
        if (rows == null || index < 0 || index >= rows.size()) return;
        rows.remove(index);
    }

    /**
     * Names [vmId] on every row that carries no VM yet: the rows the user added this session.
     *
     * <p>Done to the working copy rather than to a copy of it on the way out, so that what the
     * next snapshot holds is what the daemon was actually sent. A row snapshotted without its VM
     * could not be matched against the daemon's copy of itself afterwards, and deleting it later
     * in the same session would quietly do nothing.</p>
     */
    void stampVm(@NonNull String vmId) {
        for (var layers : working.values())
            for (var rows : layers.values())
                for (int i = 0; i < rows.size(); i++) rows.set(i, rows.get(i).withVm(vmId));
    }

    /** The rows of one layer as the page would have them now, across every card. */
    @NonNull
    List<Row> desired(@NonNull UsbRuleLayer layer) {
        return flatten(working, layer);
    }

    /** The rows of one layer that were this page's when it opened. */
    @NonNull
    List<Row> snapshot(@NonNull UsbRuleLayer layer) {
        return flatten(snapshot, layer);
    }

    /** Whether a rule the daemon holds is one this page speaks for; the merge's predicate. */
    boolean owns(@NonNull Row row, @NonNull String vmId) {
        if (row.vm == null || !vmId.equals(row.vm)) return false;
        // A rule that names no controller means the VM's first one, and the page shows the
        // first one -- so it speaks for it whichever card that turned out to be.
        return row.controller == null || owned.contains(row.controller);
    }

    /** Whether anything changed since the page opened; what decides if a save pushes at all. */
    boolean isDirty() {
        for (var layer : UsbRuleLayer.values())
            if (!XhciBindingDiff.sameRows(snapshot(layer), desired(layer))) return true;
        return false;
    }

    /** The push landed: what the page now shows is what the daemon holds. */
    void markSaved() {
        snapshot.clear();
        for (var entry : working.entrySet()) {
            var layers = emptyLayers();
            for (var layer : UsbRuleLayer.values())
                layers.get(layer).addAll(entry.getValue().get(layer));
            snapshot.put(entry.getKey(), layers);
        }
    }

    @Nullable
    private List<Row> mutableRows(@NonNull String controllerId, @NonNull UsbRuleLayer layer) {
        var layers = working.get(controllerId);
        return layers == null ? null : layers.get(layer);
    }

    @NonNull
    private static List<Row> flatten(@NonNull Map<String, EnumMap<UsbRuleLayer, List<Row>>> from,
                                     @NonNull UsbRuleLayer layer) {
        var out = new ArrayList<Row>();
        for (var layers : from.values()) out.addAll(layers.get(layer));
        return out;
    }

    @NonNull
    private static EnumMap<UsbRuleLayer, List<Row>> emptyLayers() {
        var layers = new EnumMap<UsbRuleLayer, List<Row>>(UsbRuleLayer.class);
        for (var layer : UsbRuleLayer.values()) layers.put(layer, new ArrayList<>());
        return layers;
    }
}
