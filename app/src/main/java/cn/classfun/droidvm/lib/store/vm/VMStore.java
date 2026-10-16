// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.store.base.VmConfigOwnership;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;

public final class VMStore extends DataStore<VMConfig> {
    /** Worker-only creation. Never retries with a new ID/revision after an uncertain commit. */
    public static void createPersisted(@NonNull Context context, @NonNull VMConfig config) throws Exception {
        var client = new VmConfigClient(request -> DaemonConnection.getInstance().request(request));
        if (client.supported()) {
            client.edit().saveVm(config.toJson(), true);
        } else {
            VmConfigOwnership.legacyWrite(context.getFilesDir(), () -> {
                var store = new VMStore();
                if (!store.load(context)) throw new IOException("Cannot read VM configuration");
                store.add(config);
                if (!store.save(context)) throw new IOException("Cannot save VM configuration");
            });
        }
    }

    public VMStore() {
        super();
    }

    @SuppressWarnings("unused")
    public VMStore(@NonNull JSONObject obj) {
        super(obj);
    }

    @SuppressWarnings("unused")
    public VMStore(@NonNull File file) {
        super(file);
    }

    @SuppressWarnings("unused")
    public VMStore(@NonNull Context context) {
        super(context);
    }

    @NonNull
    @Override
    protected VMConfig create() {
        return new VMConfig();
    }

    @NonNull
    @Override
    protected VMConfig create(@NonNull JSONObject obj) throws JSONException {
        return new VMConfig(obj);
    }

    @NonNull
    @Override
    protected DataStore<VMConfig> createEmpty() {
        return new VMStore();
    }

    @NonNull
    @Override
    protected String getTypeName() {
        return "vms";
    }
}
