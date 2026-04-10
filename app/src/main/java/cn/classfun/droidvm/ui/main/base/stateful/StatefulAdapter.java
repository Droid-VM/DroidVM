package cn.classfun.droidvm.ui.main.base.stateful;

import static android.view.View.VISIBLE;
import static java.util.Objects.requireNonNull;

import static cn.classfun.droidvm.lib.store.enums.Enums.applyText;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataConfig;
import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.store.enums.ColorEnum;
import cn.classfun.droidvm.lib.store.enums.StringEnum;
import cn.classfun.droidvm.ui.main.base.BaseViewHolder;
import cn.classfun.droidvm.ui.main.base.list.DataAdapter;

public abstract class StatefulAdapter<
    D extends DataConfig,
    S extends DataStore<D>,
    E extends Enum<E> & StringEnum & ColorEnum
> extends DataAdapter<D, S> {
    protected final Map<UUID, E> stateMap = new HashMap<>();
    protected final Class<E> stateEnumClass;
    protected OnActionClickListener<D, E> actionClickListener;

    public StatefulAdapter(
        @NonNull Class<S> storeClass,
        @NonNull Class<E> stateEnumClass
    ) {
        super(storeClass);
        this.stateEnumClass = stateEnumClass;
    }

    public void updateState(UUID id, E state) {
        stateMap.put(id, state);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId().equals(id)) {
                try {
                    notifyItemChanged(i);
                    break;
                } catch (Exception ignored) {
                }
            }
        }
    }

    protected E getState(UUID id) {
        var state = stateMap.get(id);
        return state != null ? state : findEnum("STOPPED");
    }

    protected E findEnum(String value) {
        var consts = requireNonNull(stateEnumClass.getEnumConstants());
        for (E e : consts) if (e.name().equals(value)) return e;
        throw new IllegalArgumentException(fmt("Invalid enum value: %s", value));
    }

    public void setOnActionClickListener(OnActionClickListener<D, E> listener) {
        this.actionClickListener = listener;
    }

    public interface OnActionClickListener<D extends DataConfig, E extends Enum<E>> {
        @SuppressWarnings("unused")
        void onActionClick(D config, E currentState);
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder holder, int position) {
        var config = items.get(position);
        var state = getState(config.getId());
        boolean actionable = isActionable(state);
        holder.itemAction.setVisibility(VISIBLE);
        holder.itemAction.setEnabled(actionable);
        holder.itemAction.setAlpha(actionable ? 1f : 0.4f);
        if (state == findEnum("STOPPED")) {
            holder.itemAction.setImageResource(R.drawable.ic_vm_start);
        } else {
            holder.itemAction.setImageResource(R.drawable.ic_vm_stop);
        }
        holder.itemAction.setOnClickListener(v -> {
            if (actionClickListener != null)
                actionClickListener.onActionClick(config, state);
        });
        holder.itemState.setVisibility(VISIBLE);
        applyText(holder.itemState, state);
        super.onBindViewHolder(holder, position);
    }
    protected boolean isActionable(E state) {
        return state == findEnum("STOPPED") || state == findEnum("RUNNING");
    }
}
