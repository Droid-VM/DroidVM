// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.storage.dir;

import static android.widget.Toast.LENGTH_LONG;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.ui.SimpleTextWatcher.simpleAfterTextWatcher;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.vm.SharedDirCache;
import cn.classfun.droidvm.lib.store.vm.SharedDirType;
import cn.classfun.droidvm.ui.widgets.container.CardItemAdapter;

public final class VMSharedDirEditAdapter
    extends CardItemAdapter<VMSharedDirEditViewHolder> {
    private OnItemClickListener browseListener;

    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    public VMSharedDirEditAdapter(@NonNull Context context) {
        super(context);
    }

    public void setOnBrowseListener(OnItemClickListener l) {
        this.browseListener = l;
    }

    public void setPathAt(int pos, String path) {
        if (pos < 0 || pos >= items.size()) return;
        items.get(pos).set("path", path);
        try {
            notifyItemChanged(pos);
        } catch (Exception ignored) {
        }
    }

    @NonNull
    @Override
    protected VMSharedDirEditViewHolder createViewHolderInstance(@NonNull View view) {
        return new VMSharedDirEditViewHolder(view);
    }

    @Override
    protected int getLayoutRes() {
        return R.layout.item_vm_shared_dir_edit;
    }

    @Override
    public void onBindViewHolder(@NonNull VMSharedDirEditViewHolder holder, int position) {
        var dir = items.get(position);
        holder.unbindWatchers();
        holder.etPath.setText(dir.optString("path", ""));
        holder.etTag.setText(dir.optString("tag", ""));
        holder.etTimeout.setText(String.valueOf(dir.optLong("timeout", 5)));
        // 9P and DAX are keys the config format still carries but this build cannot honour: the
        // P9 branch of crosvm's `--shared-dir` parser rejects every option the row writes, and the
        // fs device compiles DAX out on arm64. Force both, and coerce the stored value too -- a row
        // that reads "off" while the config says otherwise is the worse of the two lies.
        holder.btnType.setOnValueChangedListener((Runnable) null);
        dir.set("type", SharedDirType.FS);
        holder.btnType.configure(SharedDirType.class, SharedDirType.FS);
        holder.btnType.setUnavailable(() -> Toast.makeText(
            holder.itemView.getContext(),
            R.string.edit_vm_shared_dir_type_unavailable,
            LENGTH_LONG
        ).show());
        holder.btnCache.configure(SharedDirCache.class, optEnum(dir, "cache", SharedDirCache.AUTO));
        holder.switchWriteback.setChecked(dir.optBoolean("writeback", false));
        dir.set("dax", false);
        holder.switchDax.setOnCheckedChangeListener(null);
        holder.switchDax.setChecked(false);
        holder.switchPosixAcl.setChecked(dir.optBoolean("posix_acl", true));
        // Default off: a new share serves as the app, and asking for root is the deliberate act.
        holder.switchRootAccess.setChecked(dir.optBoolean("root_access", false));
        holder.pathWatcher = simpleAfterTextWatcher(s -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            items.get(pos).set("path", s.toString().trim());
        });
        holder.etPath.addTextChangedListener(holder.pathWatcher);
        holder.tagWatcher = simpleAfterTextWatcher(s -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            items.get(pos).set("tag", s.toString().trim());
        });
        holder.etTag.addTextChangedListener(holder.tagWatcher);
        holder.timeoutWatcher = simpleAfterTextWatcher(s -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            try {
                items.get(pos).set("timeout", Integer.parseInt(s.toString().trim()));
            } catch (NumberFormatException ignored) {
            }
        });
        holder.etTimeout.addTextChangedListener(holder.timeoutWatcher);
        holder.switchWriteback.setOnCheckedChangeListener((btn, checked) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            items.get(pos).set("writeback", checked);
        });
        // A CompoundButton reports the click after the state has already flipped, so put it back.
        holder.switchDax.setOnClickListener(v -> {
            holder.switchDax.setChecked(false);
            Toast.makeText(
                holder.itemView.getContext(),
                R.string.edit_vm_shared_dir_dax_unavailable,
                LENGTH_LONG
            ).show();
        });
        holder.switchPosixAcl.setOnCheckedChangeListener((btn, checked) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            items.get(pos).set("posix_acl", checked);
        });
        holder.switchRootAccess.setOnCheckedChangeListener((btn, checked) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            items.get(pos).set("root_access", checked);
        });
        holder.btnCache.setOnValueChangedListener((oldVal, newVal) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            items.get(pos).set("cache", newVal);
        });
        holder.btnBrowse.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || browseListener == null) return;
            browseListener.onItemClick(pos);
        });
        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            removeItem(pos);
        });
    }
}
