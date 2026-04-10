package cn.classfun.droidvm.lib.store.enums;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import cn.classfun.droidvm.lib.ui.MaterialMenu;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public final class EnumPicker<E extends Enum<E>> {
    private final Context context;
    private final Class<E> enumClass;
    private final List<E> items = new ArrayList<>();
    private EnumPickerChanged<E> onValueChanged = null;
    private int selectedIndex = -1;

    public interface EnumPickerChanged<E> {
        @SuppressWarnings("unused")
        void onChanged(E oldVal, E newVal);
    }

    public EnumPicker(@NonNull Context context, @NonNull Class<E> enumClass) {
        this.context = context;
        this.enumClass = enumClass;
        autoItems();
    }

    @SuppressWarnings("UnusedReturnValue")
    public AlertDialog showDialog(@Nullable CharSequence title) {
        if (items.isEmpty())
            throw new IllegalStateException("Items cannot be empty");
        var labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var label = item.toString();
            if (item instanceof StringEnum) {
                var se = (StringEnum) item;
                label = se.getDisplayString(context);
            }
            labels[i] = label;
        }
        var b = new MaterialAlertDialogBuilder(context);
        if (title != null)
            b.setTitle(title);
        b.setSingleChoiceItems(labels, selectedIndex, (dialog, which) -> {
            setSelectedIndex(which);
            dialog.dismiss();
        });
        return b.show();
    }

    public void showPopup(@NonNull View anchor) {
        if (items.isEmpty())
            throw new IllegalStateException("Items cannot be empty");
        var popup = new MaterialMenu(context, anchor);
        var menu = popup.getMenu();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var label = item.name();
            if (item instanceof StringEnum) {
                var se = (StringEnum) item;
                label = se.getDisplayString(context);
            }
            menu.add(0, i, i, label);
        }
        popup.setOnMenuItemClickListener(menuItem -> {
            setSelectedIndex(menuItem.getItemId());
            return true;
        });
        popup.show();
    }

    public void autoItems() {
        items.clear();
        for (var item : getConstants()) {
            if (item instanceof StringEnum) {
                var se = (StringEnum) item;
                if (!se.isDisplay()) continue;
            }
            items.add(item);
        }
        if (items.isEmpty())
            throw new IllegalStateException("No displayable constants found");
        selectedIndex = -1;
        setSelectedIndex(0);
    }

    public void setItems(@NonNull E begin, @NonNull E end) {
        var beginIndex = begin.ordinal();
        var endIndex = end.ordinal();
        if (beginIndex > endIndex)
            throw new IllegalArgumentException("Begin index cannot be greater than end index");
        setItems(List.of(getConstants()).subList(beginIndex, endIndex + 1));
    }

    public void setItems(@NonNull List<E> constants) {
        if (constants.isEmpty())
            throw new IllegalArgumentException("Constants cannot be empty");
        items.clear();
        items.addAll(constants);
        selectedIndex = -1;
        setSelectedIndex(0);
    }

    public void setItems(@NonNull E[] constants) {
        setItems(List.of(constants));
    }

    @NonNull
    private E[] getConstants() {
        var c = enumClass.getEnumConstants();
        if (c == null) throw new IllegalStateException("Enum class must have constants");
        return c;
    }

    @SuppressWarnings("unused")
    public void setOnValueChangedListener(@Nullable EnumPickerChanged<E> listener) {
        this.onValueChanged = listener;
    }

    @SuppressWarnings("unused")
    public void setOnValueChangedListener(@Nullable Runnable listener) {
        setOnValueChangedListener(listener == null ? null : (o, n) -> listener.run());
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int index) {
        if (index < 0 || index >= items.size())
            throw new ArrayIndexOutOfBoundsException(fmt("Index out of bounds: %d", index));
        var old = selectedIndex;
        selectedIndex = index;
        if (onValueChanged != null) {
            var newItem = items.get(selectedIndex);
            var oldItem = old < 0 || old >= items.size() ? null : items.get(old);
            onValueChanged.onChanged(oldItem, newItem);
        }
    }

    public E getSelectedItem() {
        if (selectedIndex < 0 || selectedIndex >= items.size())
            throw new IllegalStateException("Selected index is out of bounds");
        return items.get(selectedIndex);
    }

    public void setSelectedItem(@NonNull E item) {
        int index = items.indexOf(item);
        if (index < 0)
            throw new IllegalArgumentException("Item not found in items");
        setSelectedIndex(index);
    }

    @NonNull
    public String getSelectedString() {
        var item = getSelectedItem();
        if (item instanceof StringEnum) {
            var se = (StringEnum) item;
            return se.getDisplayString(context);
        }
        return item.name();
    }

    public int getItemCount() {
        return items.size();
    }

    @NonNull
    public Class<E> getEnumClass() {
        return enumClass;
    }

    @NonNull
    public List<E> getItems() {
        return items;
    }
}
