// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.ui.widgets.container.CardItemAdapter;

/**
 * The ordered rules of one layer. Each row is kept in the wire shape ({@code id}, {@code port},
 * {@code vm}; a null vm is "keep on host"), so saving is a straight copy into the rules object.
 * List order is priority, so besides the long-press drag the list widget already provides,
 * every row carries explicit up/down buttons.
 */
public final class UsbRuleAdapter extends CardItemAdapter<UsbRuleViewHolder> {
    public interface Listener {
        /** The layer's add button was pressed; the listener runs the picker, then {@link #addRule}. */
        void onAddRule(@NonNull UsbRuleLayer layer);

        /** A rule was added, removed or moved: the page now has unsaved changes. */
        void onRulesChanged();
    }

    private final UsbRuleLayer layer;
    private final Listener listener;
    private List<UsbHostDeviceInfo> devices = new ArrayList<>();
    private final Map<String, String> vmNames = new HashMap<>();
    private final Map<String, List<String>> vmControllers = new HashMap<>();

    public UsbRuleAdapter(@NonNull Context context, @NonNull UsbRuleLayer layer,
                          @NonNull Listener listener) {
        super(context);
        this.layer = layer;
        this.listener = listener;
    }

    /** What rows describe themselves against: the devices plugged in now and the VMs. */
    @SuppressLint("NotifyDataSetChanged")
    public void setLookups(@NonNull List<UsbHostDeviceInfo> devices, @NonNull List<VmEntry> vms) {
        this.devices = new ArrayList<>(devices);
        vmNames.clear();
        vmControllers.clear();
        for (var vm : vms) {
            vmNames.put(vm.id, vm.name);
            vmControllers.put(vm.id, vm.controllers);
        }
        notifyDataSetChanged();
    }

    public void addRule(@NonNull DataItem rule) {
        appendItem(rule);
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

    /** Moves the row one step; a full rebind follows because every index label shifts. */
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
        holder.tvIndex.setText(fmt("%d.", position + 1));
        bindText(holder, items.get(position));
        boolean canUp = position > 0;
        boolean canDown = position < getItemCount() - 1;
        holder.btnUp.setEnabled(canUp);
        holder.btnUp.setAlpha(canUp ? 1f : 0.3f);
        holder.btnDown.setEnabled(canDown);
        holder.btnDown.setAlpha(canDown ? 1f : 0.3f);
        holder.btnUp.setOnClickListener(v -> nudge(holder, -1));
        holder.btnDown.setOnClickListener(v -> nudge(holder, 1));
        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            removeItem(pos);
            listener.onRulesChanged();
        });
    }

    private void bindText(@NonNull UsbRuleViewHolder holder, @NonNull DataItem rule) {
        var id = rule.optString("id", "");
        var port = rule.optString("port", "");
        var vm = rule.optString("vm", null);
        var controller = rule.optString("controller", null);
        String title;
        String subtitle;
        switch (layer) {
            case EXACT: {
                title = deviceName(id);
                subtitle = context.getString(R.string.usb_rules_desc_exact, id, port);
                break;
            }
            case PORT: {
                title = context.getString(R.string.usb_rules_port_label, port);
                var there = deviceAt(port);
                subtitle = there == null
                    ? context.getString(R.string.usb_rules_port_empty)
                    : context.getString(R.string.usb_rules_port_current, there.displayName(context));
                break;
            }
            case DEVICE: {
                title = deviceName(id);
                subtitle = id;
                break;
            }
            default: {
                title = vmLabel(vm, controller);
                subtitle = "";
                break;
            }
        }
        holder.tvTitle.setText(title);
        holder.tvSubtitle.setText(subtitle);
        holder.tvSubtitle.setVisibility(subtitle.isEmpty() ? GONE : VISIBLE);
        if (layer == UsbRuleLayer.ANY) {
            holder.tvTarget.setVisibility(GONE);
        } else {
            holder.tvTarget.setVisibility(VISIBLE);
            holder.tvTarget.setText(vmLabel(vm, controller));
        }
    }

    /** Product/manufacturer of the plugged-in device with this id, or the id when none is. */
    @NonNull
    private String deviceName(@NonNull String id) {
        for (var device : devices)
            if (device.id.equals(id)) return device.displayName(context);
        return context.getString(R.string.usb_rules_unknown_device, id);
    }

    @Nullable
    private UsbHostDeviceInfo deviceAt(@NonNull String port) {
        for (var device : devices)
            if (device.port.equals(port)) return device;
        return null;
    }

    /**
     * "Attach to name" for a VM, with the controller after it when the rule names one, "keep on
     * host" for null, the uuid for a VM this app cannot see. The last layer's rows are the VM
     * itself, so there the name stands alone.
     *
     * <p>A controller the VM does not list is called out rather than shown as a target like any
     * other: that rule can never fire, and the only place it shows is here.</p>
     */
    @NonNull
    private String vmLabel(@Nullable String vm, @Nullable String controller) {
        if (vm == null) return context.getString(R.string.usb_rules_keep_host);
        var name = vmNames.get(vm);
        var known = name != null;
        if (!known) name = context.getString(R.string.usb_rules_unknown_vm, vm);
        var missing = known && controller != null && !controllersOf(vm).contains(controller);
        if (missing) {
            // The id is already in the sentence, so it is not repeated as a target below.
            name = context.getString(R.string.usb_rules_unknown_controller, name, controller);
            controller = null;
        }
        if (layer == UsbRuleLayer.ANY)
            return controller == null
                ? name : context.getString(R.string.usb_rules_vm_label_fmt, name, controller);
        return controller == null
            ? context.getString(R.string.usb_rules_target_vm, name)
            : context.getString(R.string.usb_rules_target_controller, name, controller);
    }

    @NonNull
    private List<String> controllersOf(@NonNull String vm) {
        var controllers = vmControllers.get(vm);
        return controllers == null ? Collections.emptyList() : controllers;
    }
}
