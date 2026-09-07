// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.daemon.usb.UsbRules;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.ui.widgets.container.CardItemAdapter;

/**
 * The ordered rules of one zone, one card each, in the shape the management page uses: what the
 * rule is about on the left, where the device goes on the right.
 *
 * <p>Each row is kept in the wire shape ({@code id}, {@code port}, {@code target} and, for a VM
 * target, {@code vm} and {@code controller}), so saving is a straight copy into the rules object
 * and a field the zone does not carry is simply absent.</p>
 *
 * <p>List order is priority. It is changed by dragging -- by long press at any time, and in edit
 * mode by the handle, which is the same drag without the press nobody discovers. A row that the
 * order has left with nothing to decide is dimmed rather than hidden or refused; see
 * {@link UsbRuleShadow}.</p>
 */
public final class UsbRuleAdapter extends CardItemAdapter<UsbRuleViewHolder> {
    /**
     * What a rule that can never be reached is drawn at: a fifth of the contrast taken off the
     * card's contents. Enough to read as "this one is not in play", little enough that the row
     * is still a row -- it is still stored, still editable and still draggable, and dimming is
     * the only thing about it that changes.
     */
    private static final float UNREACHABLE_ALPHA = 0.8f;

    public interface Listener {
        /** The zone's add button was pressed; the listener runs the picker, then {@link #addRule}. */
        void onAddRule(@NonNull UsbRuleLayer layer);

        /** A rule was added, removed, moved or edited: the page now has unsaved changes. */
        void onRulesChanged();
    }

    /** How a card asks the list it lives in to lift it, which only the list can do. */
    public interface DragStarter {
        void startDrag(@NonNull RecyclerView.ViewHolder holder);
    }

    private final UsbRuleLayer layer;
    private final Listener listener;
    private final UsbRuleLines.Slot[] slots;
    /** Resolved once: a card is rebound on every edit and these do not change under it. */
    private final int strongAppearance;
    private final int weakAppearance;
    private final int strongColor;
    private final int weakColor;
    private final int rippleBackground;
    private final int linePadding;
    private List<UsbHostDeviceInfo> devices = new ArrayList<>();
    private List<VmEntry> vms = new ArrayList<>();
    private final Map<String, String> vmNames = new HashMap<>();
    private final Map<String, List<String>> vmControllers = new HashMap<>();
    private DragStarter dragStarter = null;
    private boolean editing = false;

    public UsbRuleAdapter(@NonNull Context context, @NonNull UsbRuleLayer layer,
                          @NonNull Listener listener) {
        super(context);
        this.layer = layer;
        this.listener = listener;
        this.slots = UsbRuleLines.of(layer);
        this.strongAppearance = styleAttr(context,
            com.google.android.material.R.attr.textAppearanceTitleSmall);
        this.weakAppearance = styleAttr(context,
            com.google.android.material.R.attr.textAppearanceBodySmall);
        this.strongColor = MaterialColors.getColor(context,
            com.google.android.material.R.attr.colorOnSurface, 0);
        this.weakColor = MaterialColors.getColor(context,
            com.google.android.material.R.attr.colorOnSurfaceVariant, 0);
        this.rippleBackground = styleAttr(context, android.R.attr.selectableItemBackground);
        this.linePadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
            context.getResources().getDisplayMetrics()));
    }

    /** The resource a theme attribute points at, 0 when the theme does not define it. */
    private static int styleAttr(@NonNull Context context, int attr) {
        var value = new TypedValue();
        return context.getTheme().resolveAttribute(attr, value, true) ? value.resourceId : 0;
    }

    /**
     * What rows describe themselves against: the devices plugged in now, the VMs, and each VM's
     * controller ids as vms.json has them -- the only copy that is current, see {@link VmEntry}.
     */
    @SuppressLint("NotifyDataSetChanged")
    public void setLookups(@NonNull List<UsbHostDeviceInfo> devices, @NonNull List<VmEntry> vms,
                           @NonNull Map<String, List<String>> controllers) {
        this.devices = new ArrayList<>(devices);
        this.vms = new ArrayList<>(vms);
        vmNames.clear();
        vmControllers.clear();
        vmControllers.putAll(controllers);
        for (var vm : vms) vmNames.put(vm.id, vm.name);
        notifyDataSetChanged();
    }

    /** How this adapter's cards lift themselves; set by the page that owns the list widget. */
    public void setDragStarter(@Nullable DragStarter starter) {
        this.dragStarter = starter;
    }

    /** Shows or hides the handle and the delete button on every card of this zone. */
    @SuppressLint("NotifyDataSetChanged")
    public void setEditing(boolean editing) {
        if (this.editing == editing) return;
        this.editing = editing;
        notifyDataSetChanged();
    }

    public void addRule(@NonNull DataItem rule) {
        prependItem(rule);
        listener.onRulesChanged();
    }

    @Override
    public void onAddRequested(@NonNull View anchor) {
        listener.onAddRule(layer);
    }

    @Override
    public void onReorderFinished() {
        super.onReorderFinished();
        listener.onRulesChanged();
    }

    @NonNull
    @Override
    protected UsbRuleViewHolder createViewHolderInstance(@NonNull View view) {
        return new UsbRuleViewHolder(view);
    }

    @Override
    protected int getLayoutRes() {
        return R.layout.item_usb_rule;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull UsbRuleViewHolder holder, int position) {
        var rule = items.get(position);
        var id = rule.optString("id", "");
        var port = rule.optString("port", "");
        // One lookup for the whole card: the name and the context line are two things said
        // about the same device, and resolving it twice is how they come to disagree.
        var device = UsbDeviceNames.deviceFor(layer, id, port, devices);
        for (var i = 0; i < UsbRuleLines.LINES; i++)
            bindLine(holder, i, slots[i], id, port, device);
        bindTarget(holder, rule);
        // Recomputed here rather than kept: every edit rebinds the rows it can have changed --
        // an add and a drop rebind the lot, a delete and a target change everything below them
        // -- so the one array nobody can leave stale is the one nobody stores.
        var unreachable = UsbRuleShadow.unreachable(layer, items.asArray())[position];
        holder.content.setAlpha(unreachable ? UNREACHABLE_ALPHA : 1f);
        holder.ivDrag.setVisibility(editing ? VISIBLE : GONE);
        holder.btnDelete.setVisibility(editing ? VISIBLE : GONE);
        // The touch and not a click: a drag has to begin while the finger is still down, and a
        // click arrives when it comes back up, by which time there is nothing left to drag.
        holder.ivDrag.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
            if (dragStarter != null) dragStarter.startDrag(holder);
            return false;
        });
        holder.btnTarget.setOnClickListener(v -> pickTarget(holder));
        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            removeItem(pos);
            listener.onRulesChanged();
        });
    }

    /** One line of the left column: what it says, how it is drawn, and what a tap on it opens. */
    private void bindLine(@NonNull UsbRuleViewHolder holder, int index,
                          @NonNull UsbRuleLines.Slot slot, @NonNull String id,
                          @NonNull String port, @Nullable UsbHostDeviceInfo device) {
        var view = holder.lines[index];
        if (slot == UsbRuleLines.Slot.NONE) {
            view.setVisibility(GONE);
            view.setOnClickListener(null);
            view.setClickable(false);
            return;
        }
        view.setVisibility(VISIBLE);
        view.setText(lineText(slot, id, port, device));
        view.setTextAppearance(slot.strong ? strongAppearance : weakAppearance);
        view.setTextColor(slot.strong ? strongColor : weakColor);
        // Monospace for the two lines that are a value read off the device, as the management
        // page prints the same two; a name and a matcher sentence are prose and are not.
        view.setTypeface(slot == UsbRuleLines.Slot.INFO_ID || slot == UsbRuleLines.Slot.INFO_PORT
            ? Typeface.MONOSPACE : Typeface.DEFAULT);
        if (slot.edits == null) {
            view.setOnClickListener(null);
            view.setClickable(false);
            view.setBackground(null);
        } else {
            var wantsId = slot.edits == UsbRuleLines.Field.ID;
            view.setBackgroundResource(rippleBackground);
            view.setOnClickListener(v -> pickSubject(holder, wantsId));
        }
        // After the background either way: setting one takes the drawable's padding, which for
        // a ripple is none, and a line that lost its padding sits a pixel off the ones beside it.
        applyLinePadding(view);
    }

    private void applyLinePadding(@NonNull TextView view) {
        view.setPadding(0, linePadding, 0, linePadding);
    }

    @NonNull
    private String lineText(@NonNull UsbRuleLines.Slot slot, @NonNull String id,
                            @NonNull String port, @Nullable UsbHostDeviceInfo device) {
        switch (slot) {
            case MATCH_ID:
                return context.getString(R.string.usb_rules_match_id_fmt, orAbsent(id));
            case MATCH_PORT:
                return context.getString(R.string.usb_rules_match_port_fmt, orAbsent(port));
            case MATCH_ANY:
                return context.getString(R.string.usb_rules_match_any);
            case INFO_ID:
                return context.getString(R.string.usb_rules_info_id_fmt,
                    orAbsent(device == null ? "" : device.id));
            case INFO_PORT:
                return context.getString(R.string.usb_rules_info_port_fmt,
                    orAbsent(device == null ? "" : device.port));
            case NAME:
            default:
                return UsbDeviceNames.cardTitle(context, layer, id, port, devices);
        }
    }

    /**
     * A dash for a value there is none of, which on a context line means the rule's device is
     * not plugged in and on a matcher means a rule saved without one. Never an empty line: a
     * card that silently loses a row is a card whose shape says something it does not mean.
     */
    @NonNull
    private String orAbsent(@NonNull String value) {
        return value.isEmpty() ? context.getString(R.string.usb_rules_value_absent) : value;
    }

    private void bindTarget(@NonNull UsbRuleViewHolder holder, @NonNull DataItem rule) {
        var target = UsbDeviceTarget.of(rule.optString("target", null),
            rule.optString("vm", null), rule.optString("controller", null));
        holder.btnTarget.setText(targetLabel(target));
        var warning = targetWarning(target);
        holder.tvWarning.setText(warning);
        holder.tvWarning.setVisibility(warning == null ? GONE : VISIBLE);
    }

    /**
     * The picker for one field, writing back only that field.
     *
     * <p>An exact rule's subject picker answers with both a device and a port, and taking both
     * would move a rule to another socket because its device was re-picked.</p>
     */
    private void pickSubject(@NonNull UsbRuleViewHolder holder, boolean wantsId) {
        if (holder.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
        UsbSubjectPickerDialog.pick(context, layer, devices, (picked, id, port) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            var rule = items.get(pos);
            if (wantsId) rule.set("id", id);
            else rule.set("port", port);
            // Down to the end of the list: the row now matches something else, so which of the
            // rows below it a host or sink rule shadows has moved with it.
            notifyItemRangeChanged(pos, getItemCount() - pos);
            listener.onRulesChanged();
        });
    }

    private void pickTarget(@NonNull UsbRuleViewHolder holder) {
        if (holder.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
        UsbTargetPickerDialog.pick(context, layer, vms, vmControllers, target -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            applyTarget(items.get(pos), target);
            // A row that becomes a host or a sink shadows the rows below it with its matcher,
            // and one that stops being either hands them back; both are a rebind of the rest.
            notifyItemRangeChanged(pos, getItemCount() - pos);
            listener.onRulesChanged();
        });
    }

    /** The wire shape of a target: the key, and the VM pair for -- and only for -- a VM one. */
    private static void applyTarget(@NonNull DataItem rule, @NonNull UsbDeviceTarget target) {
        rule.set("target", target.toRuleTarget());
        if (target.kind != UsbRules.Target.VM) {
            rule.remove("vm");
            rule.remove("controller");
            return;
        }
        rule.set("vm", target.vmId);
        if (target.controller == null) rule.remove("controller");
        else rule.set("controller", target.controller);
    }

    /** What the target button says: where the device goes, in the rule's own words. */
    @NonNull
    private String targetLabel(@NonNull UsbDeviceTarget target) {
        if (target.kind == UsbRules.Target.HOST)
            return context.getString(R.string.usb_rules_keep_host);
        if (target.kind == UsbRules.Target.SINK)
            return context.getString(R.string.usb_rules_target_sink);
        var name = vmName(target.vmId);
        return target.controller == null
            ? context.getString(R.string.usb_rules_target_vm, name)
            : context.getString(R.string.usb_rules_target_controller, name, target.controller);
    }

    /**
     * Why this rule can never fire, or null when nothing says it cannot.
     *
     * <p>A rule whose VM or controller is not there is called out rather than shown as a target
     * like any other: this is the only place it shows. That covers a VM this app cannot see -- a
     * typed-in id, or one deleted since -- a rule naming a controller the VM does not have, and
     * equally a rule naming none, which means the VM's first controller, against a VM that has
     * no controller at all. The last is what every rule written before controllers existed looks
     * like.</p>
     */
    @Nullable
    private String targetWarning(@NonNull UsbDeviceTarget target) {
        if (target.kind != UsbRules.Target.VM || target.vmId == null) return null;
        var name = vmNames.get(target.vmId);
        if (name == null) return context.getString(R.string.usb_rules_unknown_vm, target.vmId);
        // Only vms.json can answer this, and only for a VM it still holds.
        var listed = vmControllers.get(target.vmId);
        if (listed == null) return null;
        if (target.controller == null)
            return listed.isEmpty()
                ? context.getString(R.string.usb_rules_no_controller, name) : null;
        return listed.contains(target.controller)
            ? null : context.getString(R.string.usb_rules_unknown_controller,
            name, target.controller);
    }

    /** The VM's name, or the bare id for one this app cannot see; the warning explains that. */
    @NonNull
    private String vmName(@Nullable String vmId) {
        if (vmId == null) return "";
        var name = vmNames.get(vmId);
        return name == null ? vmId : name;
    }
}
