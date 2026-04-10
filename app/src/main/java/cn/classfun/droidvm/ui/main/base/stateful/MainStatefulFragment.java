package cn.classfun.droidvm.ui.main.base.stateful;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.base.DataConfig;
import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.store.enums.ColorEnum;
import cn.classfun.droidvm.lib.store.enums.StringEnum;
import cn.classfun.droidvm.ui.main.base.list.MainListFragment;

public abstract class MainStatefulFragment<
    D extends DataConfig,
    S extends DataStore<D>,
    A extends StatefulAdapter<D, S, E>,
    E extends Enum<E> & StringEnum & ColorEnum
> extends MainListFragment<D, S, A> {

    public MainStatefulFragment(@NonNull Class<A> adapterClass) {
        super(adapterClass);
    }

    @Override
    protected final void onRefreshDone() {
        syncStatesFromDaemon();
    }

    private void syncStatesFromDaemon() {
        DaemonConnection.OnResponse res = resp -> {
            var arr = resp.optJSONArray("data");
            if (arr == null) return;
            mainHandler.post(() -> {
                if (!isAdded()) return;
                for (int i = 0; i < arr.length(); i++) {
                    var net = arr.optJSONObject(i);
                    if (net == null || !net.has("id")) continue;
                    var id = UUID.fromString(net.optString("id"));
                    var stopped = adapter.findEnum("STOPPED");
                    adapter.updateState(id, optEnum(net, "state", stopped));
                }
            });
        };
        DaemonConnection.getInstance()
            .buildRequest(getListCallName())
            .onResponse(res)
            .onUnsuccessful(r -> {
            })
            .onError(e -> Log.w(TAG, "Failed to sync states", e))
            .invoke();
    }

    @Override
    public final void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adapter.setOnActionClickListener(this::onActionClicked);
    }

    protected abstract void onActionClicked(D config, E currentState);

    protected abstract String getListCallName();
}
