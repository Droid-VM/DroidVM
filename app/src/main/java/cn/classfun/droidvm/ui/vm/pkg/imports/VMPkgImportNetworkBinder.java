// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.pkg.imports;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.pkg.NetworkImportPlan;
import cn.classfun.droidvm.lib.store.network.BridgeType;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkConflicts;
import cn.classfun.droidvm.lib.store.network.UplinkMode;
import cn.classfun.droidvm.lib.ui.IconItemAdapter;
import cn.classfun.droidvm.ui.widgets.row.ChooseRowWidget;
import cn.classfun.droidvm.ui.widgets.row.DropdownRowWidget;

/**
 * One card on the import screen: a network the package carries, and what to do with it.
 *
 * <p>The three choices are offered in the order they are worth having -- join what is already
 * here, build the packaged one, leave it -- and the first one that is actually available is
 * selected. The two that can be refused are refused for opposite reasons: there is nothing of
 * this kind here to join, or there is, and it holds what this one would have to claim. So at
 * most one of them is ever greyed, and the card is never left with only "skip".
 */
final class VMPkgImportNetworkBinder {
    @NonNull
    final View view;
    @NonNull
    final NetworkConfig packaged;
    @NonNull
    final String ref;
    private final TextView tvName;
    private final TextView tvDetail;
    private final TextView tvNote;
    private final ChooseRowWidget chooseMode;
    private final DropdownRowWidget ddTarget;
    private final List<NetworkConfig> candidates = new ArrayList<>();
    private final Runnable onChanged;
    private NetworkImportMode mode = NetworkImportMode.SKIP;
    private int targetIndex = 0;
    @Nullable
    private NetworkConflicts.Conflict conflict;
    /** What creating it would produce, once the screen has worked the names out. */
    @Nullable
    private NetworkConfig prepared;

    VMPkgImportNetworkBinder(
        @NonNull View view,
        @NonNull NetworkConfig packaged,
        @NonNull Runnable onChanged
    ) {
        this.view = view;
        this.packaged = packaged;
        this.onChanged = onChanged;
        ref = packaged.item.optString(NetworkImportPlan.REF_KEY, "");
        tvName = view.findViewById(R.id.tv_net_name);
        tvDetail = view.findViewById(R.id.tv_net_detail);
        tvNote = view.findViewById(R.id.tv_net_note);
        chooseMode = view.findViewById(R.id.choose_mode);
        ddTarget = view.findViewById(R.id.dd_target);
    }

    /** Works out what this network can do here, and picks the best of it. */
    void bind(@NonNull NetworkImportPlan plan) {
        var ctx = view.getContext();
        candidates.clear();
        candidates.addAll(plan.candidates(packaged));
        conflict = plan.createConflict(packaged);
        targetIndex = 0;

        var name = packaged.getName();
        tvName.setText(name == null || name.isEmpty()
            ? ctx.getString(R.string.vmpkg_import_networks) : name);
        tvDetail.setText(summary(ctx, packaged));

        mode = defaultMode();
        chooseMode.configure(NetworkImportMode.class, mode);
        if (candidates.isEmpty()) chooseMode.setDisabledItems(
            ctx.getString(R.string.vmpkg_import_network_nothing_to_join),
            NetworkImportMode.JOIN);
        else if (conflict != null) chooseMode.setDisabledItems(
            ctx.getString(R.string.vmpkg_import_network_conflicts),
            NetworkImportMode.CREATE);
        chooseMode.setOnValueChangedListener(() -> {
            mode = chooseMode.getSelectedItem();
            applyMode();
            onChanged.run();
        });

        var labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) labels[i] = candidateLabel(ctx, i);
        ddTarget.setAdapter(IconItemAdapter.create(ctx, labels, R.drawable.ic_switch));
        ddTarget.setOnItemClickListener((p, v, pos, id) -> {
            targetIndex = pos;
            ddTarget.setText(candidateLabel(ctx, pos));
        });
        if (!candidates.isEmpty()) ddTarget.setText(candidateLabel(ctx, 0));
        applyMode();
    }

    /**
     * The first choice this network can actually take. Joining comes first because it is the one
     * that keeps the packaged VM on a network that already works here, and every setting the two
     * share -- the DHCP pool, the addressing, the SNAT -- comes along untouched.
     */
    @NonNull
    private NetworkImportMode defaultMode() {
        if (!candidates.isEmpty()) return NetworkImportMode.JOIN;
        if (conflict == null) return NetworkImportMode.CREATE;
        return NetworkImportMode.SKIP;
    }

    /** Sets what the screen has settled this network would be created as. */
    void setPrepared(@Nullable NetworkConfig cfg) {
        prepared = cfg;
        applyNote();
    }

    @NonNull
    NetworkImportMode mode() {
        return mode;
    }

    /** This card's decision, ready for the import request. */
    @NonNull
    NetworkImportPlan.Entry toEntry() {
        switch (mode) {
            case JOIN:
                var target = candidates.isEmpty() ? null
                    : candidates.get(Math.min(targetIndex, candidates.size() - 1));
                return new NetworkImportPlan.Entry(
                    ref, NetworkImportPlan.Action.JOIN,
                    target == null ? null : target.getId().toString(), null);
            case CREATE:
                return new NetworkImportPlan.Entry(
                    ref, NetworkImportPlan.Action.CREATE, null, prepared);
            default:
                return new NetworkImportPlan.Entry(
                    ref, NetworkImportPlan.Action.SKIP, null, null);
        }
    }

    void setEnabled(boolean enabled) {
        chooseMode.setEnabled(enabled);
        ddTarget.setEnabled(enabled && mode == NetworkImportMode.JOIN);
    }

    private void applyMode() {
        ddTarget.setVisibility(mode == NetworkImportMode.JOIN ? VISIBLE : GONE);
        applyNote();
    }

    /**
     * The line under the card: why creating is refused when it is, and otherwise what the chosen
     * mode is about to do.
     */
    private void applyNote() {
        var ctx = view.getContext();
        String note = null;
        if (conflict != null && mode != NetworkImportMode.JOIN) {
            note = conflictMessage(ctx, conflict);
        } else if (mode == NetworkImportMode.CREATE && prepared != null) {
            var bridge = prepared.getBridgeName();
            note = ctx.getString(R.string.vmpkg_import_network_will_create,
                prepared.getName(), bridge == null ? "" : bridge);
        } else if (mode == NetworkImportMode.SKIP) {
            note = ctx.getString(R.string.vmpkg_import_network_skipped);
        }
        tvNote.setText(note == null ? "" : note);
        tvNote.setVisibility(note == null ? GONE : VISIBLE);
    }

    @NonNull
    private String candidateLabel(@NonNull Context ctx, int index) {
        var net = candidates.get(index);
        var name = net.getName();
        var detail = addresses(net);
        if (detail.isEmpty()) return name == null ? "" : name;
        return ctx.getString(R.string.network_item_subtitle, name, detail);
    }

    /** "Linux bridge | L3 routing | 192.168.50.1/24", as much of it as the network has. */
    @NonNull
    static String summary(@NonNull Context ctx, @NonNull NetworkConfig net) {
        var type = ctx.getString(net.getBridgeType() == BridgeType.GVISOR
            ? R.string.network_edit_bridge_type_gvisor
            : R.string.network_edit_bridge_type_linux);
        var mode = ctx.getString(net.getUplinkMode() == UplinkMode.L2
            ? R.string.network_edit_uplink_l2
            : R.string.network_edit_uplink_l3);
        var head = ctx.getString(R.string.network_item_subtitle, type, mode);
        var detail = addresses(net);
        return detail.isEmpty() ? head
            : ctx.getString(R.string.network_item_subtitle, head, detail);
    }

    /** What identifies this network at a glance: its uplink (L2) or its first subnets (L3). */
    @NonNull
    private static String addresses(@NonNull NetworkConfig net) {
        if (net.getUplinkMode() == UplinkMode.L2) {
            var uplink = net.getL2Uplink();
            return uplink == null ? "" : uplink;
        }
        var parts = new ArrayList<String>();
        for (var vlan : net.getVlans()) {
            var v4 = vlan.getIpv4Cidr();
            if (v4 != null && !parts.contains(v4)) parts.add(v4);
            var v6 = vlan.getIpv6Cidr();
            if (v6 != null && !parts.contains(v6)) parts.add(v6);
            if (parts.size() >= 2) break;
        }
        return String.join(", ", parts);
    }

    /** The same wording the network editor refuses a save with. */
    @NonNull
    private static String conflictMessage(
        @NonNull Context ctx, @NonNull NetworkConflicts.Conflict conflict
    ) {
        switch (conflict.kind) {
            case IPV4:
                return ctx.getString(R.string.network_edit_error_ipv4_overlap,
                    conflict.mine, conflict.otherName(), conflict.theirs);
            case IPV6:
                return ctx.getString(R.string.network_edit_error_ipv6_overlap,
                    conflict.mine, conflict.otherName(), conflict.theirs);
            default:
                return ctx.getString(R.string.network_edit_error_uplink_taken,
                    conflict.mine, conflict.otherName());
        }
    }
}
