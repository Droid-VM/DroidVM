package cn.classfun.droidvm.ui.vm.edit.storage.dir;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.ui.SimpleTextWatcher.simpleAfterTextWatcher;

import android.content.Context;
import android.view.View;

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
        holder.btnType.configure(SharedDirType.class, optEnum(dir, "type", SharedDirType.FS));
        holder.btnCache.configure(SharedDirCache.class, optEnum(dir, "cache", SharedDirCache.AUTO));
        holder.switchWriteback.setChecked(dir.optBoolean("writeback", false));
        holder.switchDax.setChecked(dir.optBoolean("dax", false));
        holder.switchPosixAcl.setChecked(dir.optBoolean("posix_acl", true));
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
        holder.switchDax.setOnCheckedChangeListener((btn, checked) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            items.get(pos).set("dax", checked);
        });
        holder.switchPosixAcl.setOnCheckedChangeListener((btn, checked) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            items.get(pos).set("posix_acl", checked);
        });
        holder.btnType.setOnValueChangedListener((oldVal, newVal) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            items.get(pos).set("type", newVal);
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
