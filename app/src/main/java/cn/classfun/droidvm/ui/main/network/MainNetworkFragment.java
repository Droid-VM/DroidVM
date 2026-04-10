package cn.classfun.droidvm.ui.main.network;

import static android.widget.Toast.LENGTH_LONG;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkState;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.ui.main.base.stateful.MainStatefulFragment;
import cn.classfun.droidvm.ui.network.NetworkActions;
import cn.classfun.droidvm.ui.network.edit.NetworkEditActivity;
import cn.classfun.droidvm.ui.network.info.NetworkInfoActivity;

public final class MainNetworkFragment
    extends MainStatefulFragment<NetworkConfig, NetworkStore, NetworkAdapter, NetworkState> {

    public MainNetworkFragment() {
        super(NetworkAdapter.class);
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_main_network;
    }

    @Override
    public int getTitleResId() {
        return R.string.nav_network;
    }

    @Override
    protected @MenuRes int getCustomMenuResId() {
        return R.menu.menu_main_network;
    }

    @Override
    public void onFabClick(@NonNull View v) {
        startActivity(new Intent(requireContext(), NetworkEditActivity.class));
    }

    @NonNull
    @Override
    protected String getListCallName() {
        return "network_list";
    }

    @NonNull
    @Override
    protected Class<? extends Activity> getInfoActivity() {
        return NetworkInfoActivity.class;
    }

    @Override
    protected int getItemMenuResId(@NonNull NetworkConfig config) {
        return R.menu.menu_network_actions;
    }

    @Override
    protected boolean onMenuClicked(@NonNull NetworkConfig config, @NonNull MenuItem item) {
        var id = item.getItemId();
        if (id == R.id.menu_network_edit) {
            var intent = new Intent(requireContext(), NetworkEditActivity.class);
            intent.putExtra(NetworkEditActivity.EXTRA_NETWORK_ID, config.getId().toString());
            startActivity(intent);
        } else if (id == R.id.menu_network_delete) {
            deleteNetwork(config);
        }
        return true;
    }

    @Override
    protected void onActionClicked(NetworkConfig config, NetworkState currentState) {
        var conn = DaemonConnection.getInstance();
        Runnable cb = () -> mainHandler.post(() -> {
            if (isAdded()) refreshView();
        });
        if (currentState == NetworkState.STOPPED) {
            NetworkActions.createAndStart(config, mainHandler, uiContext, cb);
        } else if (currentState == NetworkState.RUNNING) {
            DaemonConnection.OnError err = e -> mainHandler.post(() -> {
                if (isAdded())
                    Toast.makeText(requireContext(), R.string.vm_daemon_error, LENGTH_LONG).show();
            });
            DaemonConnection.OnUnsuccessful f = resp -> mainHandler.post(() -> {
                var msg = resp.optString("message", "Unknown error");
                if (isAdded())
                    Toast.makeText(requireContext(), msg, LENGTH_LONG).show();
            });
            conn.buildRequest("network_stop")
                .put("network_id", config.getId().toString())
                .onResponse(resp -> cb.run())
                .onUnsuccessful(f)
                .onError(err)
                .invoke();
        }
    }

    private void deleteNetwork(@NonNull NetworkConfig config) {
        var ctx = requireContext();
        NetworkActions.Callback cb = success -> {
            if (isAdded() && success) refreshView();
        };
        DialogInterface.OnClickListener performDeleteNetwork = (dialog, which) ->
            NetworkActions.deleteNetwork(ctx, mainHandler, config.getId(), adapter.items, cb);
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(config.getName())
            .setMessage(R.string.network_delete_confirm)
            .setPositiveButton(R.string.network_delete, performDeleteNetwork)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
