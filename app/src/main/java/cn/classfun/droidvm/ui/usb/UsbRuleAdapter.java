// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.daemon.usb.UsbRules;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.ui.widgets.container.CardItemAdapter;

/**
 * The ordered rules of one layer, one card each. Each row is kept in the wire shape ({@code id},
 * {@code port}, {@code target} and, for a VM target, {@code vm} and {@code controller}), so
 * saving is a straight copy into the rules object and a field the layer does not carry is simply
 * absent.
 *
 * <p>List order is priority, so besides the long-press drag the list widget already provides,
 * every row carries explicit up/down buttons.</p>
 */
public final class UsbRuleAdapter extends CardItemAdapter<UsbRuleViewHolder> {
    public interface Listener {
        /** The layer's add button was pressed; the listener runs the picker, then {@link #addRule}. */
        void onAddRule(@NonNull UsbRuleLayer layer);

        /** A rule was added, removed, moved or edited: the page now has unsaved changes. */
        void onRulesChanged();
    }

    private final UsbRuleLayer layer;
    private final Listener listener;
    private List<UsbHostDeviceInfo> devices = new ArrayList<>();
    private List<VmEntry> vms = new ArrayList<>();
    private final Map<String, String> vmNames = new HashMap<>();
    private final Map<String, List<String>> vmControllers = new HashMap<>();

    public UsbRuleAdapter(@NonNull Context context, @NonNull UsbRuleLayer layer,
                          @NonNull Listener listener) {
        super(context);
        this.layer = layer;
        this.listener = listener;
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

    /** Moves the row one step; a full rebind follows because every arrow's enablement shifts. */
    private void nudge(@NonNull UsbRuleViewHolder holder, int delta) {
        int from = holder.getBindingAdapterPosition();
        if (from == RecyclerView.NO_POSITION) return;
        int to = from + delta;
        if (to < 0 || to >= getItemCount()) return;
        moveItem(from, to);
        onReorderFinished();
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

    @Override
    public void onBindViewHolder(@NonNull UsbRuleViewHolder holder, int position) {
        var rule = items.get(position);
        var id = rule.optString("id", "");
        var port = rule.optString("port", "");
        holder.tvTitle.setText(UsbDeviceNames.cardTitle(context, layer, id, port, devices));
        holder.rowId.setVisibility(layer.hasId ? VISIBLE : GONE);
        holder.rowPort.setVisibility(layer.hasPort ? VISIBLE : GONE);
        holder.btnId.setText(id);
        holder.btnPort.setText(port);
        bindTarget(holder, rule);
        boolean canUp = position > 0;
        boolean canDown = position < getItemCount() - 1;
        holder.btnUp.setEnabled(canUp);
        holder.btnUp.setAlpha(canUp ? 1f : 0.3f);
        holder.btnDown.setEnabled(canDown);
        holder.btnDown.setAlpha(canDown ? 1f : 0.3f);
        holder.btnUp.setOnClickListener(v -> nudge(holder, -1));
        holder.btnDown.setOnClickListener(v -> nudge(holder, 1));
        holder.btnId.setOnClickListener(v -> pickSubject(holder, true));
        holder.btnPort.setOnClickListener(v -> pickSubject(holder, false));
        holder.btnTarget.setOnClickListener(v -> pickTarget(holder));
        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            removeItem(pos);
            listener.onRulesChanged();
        });
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
            notifyItemChanged(pos);
            listener.onRulesChanged();
        });
    }

    private void pickTarget(@NonNull UsbRuleViewHolder holder) {
        if (holder.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
        UsbTargetPickerDialog.pick(context, layer, vms, vmControllers, target -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            applyTarget(items.get(pos), target);
            notifyItemChanged(pos);
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
