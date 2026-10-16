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
import java.util.Objects;
import java.util.function.Consumer;

import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.base.VmConfigOwnership;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;

public final class VMStore extends DataStore<VMConfig> {
    /** Worker-only guarded field edit. Session-only changes to other fields are never saved. */
    public static void updatePersistedField(@NonNull Context context, @NonNull VMConfig expected,
        @NonNull String field, @NonNull Consumer<VMConfig> change) throws Exception {
        updatePersistedField(context, expected, field, (changed, observed) -> change.accept(changed));
    }

    @FunctionalInterface
    public interface FieldChange {
        void apply(VMConfig changed, VMStore observed) throws Exception;
    }

    /** The planner observes the same VM inventory whose revision/legacy lock guards the save. */
    public static void updatePersistedField(@NonNull Context context, @NonNull VMConfig expected,
        @NonNull String field, @NonNull FieldChange change) throws Exception {
        var before = new VMConfig(expected.toJson());
        var changed = new VMConfig(expected.toJson());
        var client = new VmConfigClient(request -> DaemonConnection.getInstance().request(request));
        if (client.supported()) {
            var session = client.edit();
            var store = new VMStore(session.document());
            var current = store.findById(before.getId());
            if (current == null) throw new IOException("VM no longer exists; reload before starting");
            change.apply(changed, store);
            applyFieldUpdate(current.item, before.item, changed.item, field);
            session.saveVm(current.toJson(), false);
        } else {
            VmConfigOwnership.legacyWrite(context.getFilesDir(), () -> {
                var store = new VMStore();
                if (!store.load(context)) throw new IOException("Cannot read VM configuration");
                var current = store.findById(before.getId());
                if (current == null) throw new IOException("VM no longer exists; reload before starting");
                change.apply(changed, store);
                applyFieldUpdate(current.item, before.item, changed.item, field);
                if (!store.save(context)) throw new IOException("Cannot save VM configuration");
            });
        }
        // Publish to this start's config only after persistence is acknowledged.
        copyField(expected.item, changed.item, field);
    }

    static void applyFieldUpdate(DataItem current, DataItem expected, DataItem changed, String field) throws IOException {
        if ("id".equals(field)) throw new IOException("VM identity cannot be changed");
        if (!Objects.equals(current.opt(field, null), expected.opt(field, null)))
            throw new IOException("VM configuration changed; reload before starting");
        copyField(current, changed, field);
    }

    private static void copyField(DataItem target, DataItem source, String field) {
        var value = source.opt(field, null);
        if (value == null) target.remove(field);
        else target.set(field, value);
    }

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

    /** Worker-only settings import; preserve unrelated VMs and root extensions. */
    public static void importPersisted(@NonNull Context context, @NonNull JSONObject document) throws Exception {
        document.getJSONArray("vms");
        var imported = new VMStore();
        if (!imported.load(document)) throw new IOException("Cannot read imported VM configuration");
        var rows = new org.json.JSONArray();
        // Validate/migrate through the same VMConfig parser used by legacy import.
        var configs = new java.util.ArrayList<VMConfig>();
        imported.forEach((id, vm) -> configs.add(vm));
        for (var vm : configs) rows.put(vm.toJson());
        var client = new VmConfigClient(request -> DaemonConnection.getInstance().request(request));
        if (client.supported()) client.edit().mergeVms(rows);
        else VmConfigOwnership.legacyWrite(context.getFilesDir(), () -> {
            var current = new VMStore();
            if (!current.load(context)) throw new IOException("Cannot read current VM configuration");
            imported.forEach((id, vm) -> {
                if (current.findById(id) == null) current.add(vm);
                else current.update(vm);
            });
            if (!current.save(context)) throw new IOException("Cannot save imported VM configuration");
        });
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
