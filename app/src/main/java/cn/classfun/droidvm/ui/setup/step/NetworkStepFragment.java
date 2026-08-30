// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.setup.step;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.network.BridgeType;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkConfigValidator;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.ui.network.NetworkPresets;
import cn.classfun.droidvm.ui.setup.SetupActivity;
import cn.classfun.droidvm.ui.setup.base.BaseStepFragment;

/**
 * Setup step that creates the user's first network.
 *
 * <p>A VM with no NIC attached comes up with no connectivity and nothing on screen says why, so
 * people reach the VM list without ever visiting the Networks tab and conclude networking is
 * broken. This page makes one exist before that can happen: three presets, the addresses shown up
 * front, and the wizard's forward button creates the chosen one.
 *
 * <p>The network is only written to networks.json here, never started. The daemon is launched by
 * the main screen, which the wizard has not reached yet, so the preset carries {@code auto_up}
 * and the daemon brings it up when it reads the file on start.
 */
public final class NetworkStepFragment extends BaseStepFragment {
    private static final String TAG = "NetworkStepFragment";
    /** Preset names, doubling as bridge interface names. */
    private static final String NAME_WIFI = "br-wifi";
    private static final String NAME_LINUX = "br-net0";
    private static final String NAME_GVISOR = "br-gv0";

    private final NetworkStore store = new NetworkStore();
    private boolean storeLoaded = false;
    private boolean created = false;
    private RadioGroup rgPreset;
    private TextView tvInfo;
    /**
     * The 192.168.N.1/24 and fd00:N::1/64 pair the routed presets will use. Picked once, when the
     * page is first shown, because the page prints it: re-rolling it per redraw would show the
     * user addresses other than the ones they are about to get.
     */
    @Nullable
    private String[] cidrPair;

    public NetworkStepFragment(SetupActivity activity) {
        this.activity = activity;
    }

    @NonNull
    private NetworkStore store() {
        if (!storeLoaded) {
            store.load(activity.getApplicationContext());
            storeLoaded = true;
        }
        return store;
    }

    /**
     * Shown only when the user has no network at all. Anyone who already has one either built it
     * themselves or came through this page already, and neither wants a second one appended on a
     * re-run of the wizard.
     */
    @Override
    public boolean isHiddenStep() {
        return created || !store().isEmpty();
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_setup_step_network, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rgPreset = view.findViewById(R.id.rg_net_preset);
        tvInfo = view.findViewById(R.id.tv_net_info);
        if (cidrPair == null) {
            var used4 = new ArrayList<IPv4Network>();
            var used6 = new ArrayList<IPv6Network>();
            NetworkPresets.collectStoreNetworks(store(), null, used4, used6);
            cidrPair = NetworkPresets.pickFreeCidrPair(used4, used6);
        }
        rgPreset.setOnCheckedChangeListener((g, id) -> updateInfo());
        updateInfo();
        activity.showFab(R.drawable.ic_arrow_forward, this::onNext);
    }

    /** Name the selected preset would take, with a suffix if that one is somehow taken. */
    @NonNull
    private String presetName(int checkedId) {
        String base;
        if (checkedId == R.id.rb_net_wifi) base = NAME_WIFI;
        else if (checkedId == R.id.rb_net_gvisor) base = NAME_GVISOR;
        else base = NAME_LINUX;
        return NetworkPresets.uniqueName(store(), base);
    }

    private void updateInfo() {
        int checkedId = rgPreset.getCheckedRadioButtonId();
        var name = presetName(checkedId);
        if (checkedId == R.id.rb_net_wifi) {
            tvInfo.setText(getString(R.string.setup_network_info_wifi, name));
            return;
        }
        var unavailable = getString(R.string.setup_network_no_subnet);
        var v4 = cidrPair != null ? cidrPair[0] : unavailable;
        var v6 = cidrPair != null ? cidrPair[1] : unavailable;
        tvInfo.setText(getString(checkedId == R.id.rb_net_gvisor
            ? R.string.setup_network_info_gvisor
            : R.string.setup_network_info_linux, name, v4, v6));
    }

    private void onNext() {
        int checkedId = rgPreset.getCheckedRadioButtonId();
        NetworkConfig config;
        if (checkedId == R.id.rb_net_wifi) {
            config = NetworkPresets.wifiPseudoBridge(presetName(checkedId));
        } else {
            if (cidrPair == null) {
                Toast.makeText(activity, R.string.setup_network_no_subnet, LENGTH_LONG).show();
                return;
            }
            var type = checkedId == R.id.rb_net_gvisor ? BridgeType.GVISOR : BridgeType.LINUX;
            config = NetworkPresets.routedNat(type, presetName(checkedId), cidrPair);
        }
        try {
            NetworkConfigValidator.validate(config);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Preset network failed validation", e);
            Toast.makeText(activity, e.getMessage(), LENGTH_LONG).show();
            return;
        }
        store().add(config);
        store().save(activity);
        created = true;
        Toast.makeText(activity,
            getString(R.string.setup_network_created, config.getName()),
            LENGTH_SHORT).show();
        activity.onStepCompleted();
    }
}
