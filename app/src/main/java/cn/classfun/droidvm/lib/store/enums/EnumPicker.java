// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class EnumPicker<E extends Enum<E>> {
    private final Context context;
    private final Class<E> enumClass;
    private final List<E> items = new ArrayList<>();
    /** Items listed but refused; see {@link #setDisabledItems}. */
    private final Set<E> disabled = new LinkedHashSet<>();
    private CharSequence disabledNote = null;
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
        for (int i = 0; i < items.size(); i++)
            labels[i] = menuLabel(items.get(i));
        var b = new MaterialAlertDialogBuilder(context);
        if (title != null)
            b.setTitle(title);
        b.setSingleChoiceItems(labels, selectedIndex, (dialog, which) -> {
            // A refused row leaves the dialog open rather than closing on a value it did not
            // apply, which would read as having been accepted.
            if (disabled.contains(items.get(which))) return;
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
            // MaterialMenu's adapter already honours isEnabled(): it greys the row and swallows
            // the tap, so nothing here has to re-check on the way back out.
            menu.add(0, i, i, menuLabel(item)).setEnabled(!disabled.contains(item));
        }
        popup.setOnMenuItemClickListener(menuItem -> {
            setSelectedIndex(menuItem.getItemId());
            return true;
        });
        popup.show();
    }

    /** This item's label, plus the disabled note when it is one of the refused ones. */
    @NonNull
    private String menuLabel(@NonNull E item) {
        var label = item instanceof StringEnum
            ? ((StringEnum) item).getDisplayString(context) : item.name();
        if (disabledNote == null || !disabled.contains(item)) return label;
        return fmt("%s (%s)", label, disabledNote);
    }

    /**
     * Items the picker lists but will not select.
     *
     * <p>For a set of choices that is easier to read whole than pruned -- a ladder whose upper
     * rungs are designed but not built, say. Hiding them makes the remaining values look like the
     * entire vocabulary and makes each one that lands later look like a feature out of nowhere;
     * listing them greyed, with {@code note} saying why, says what the set is and where this build
     * stands in it. A disabled item can still be the current value: that is how a choice stored
     * before its rung was refused survives being looked at.</p>
     *
     * <p>Cleared by {@link #setItems} and {@link #autoItems}, since a new item set has its own
     * answer -- the same constant can be reachable under one and not under another.</p>
     */
    public void setDisabledItems(@Nullable CharSequence note, @NonNull Collection<E> refused) {
        disabled.clear();
        disabled.addAll(refused);
        disabledNote = note;
    }

    public void autoItems() {
        items.clear();
        disabled.clear();
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
        disabled.clear();
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
