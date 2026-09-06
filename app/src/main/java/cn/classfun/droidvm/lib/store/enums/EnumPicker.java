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
    /** Where a refused selection lands; see {@link #setDefaultItem}. */
    private int defaultIndex = 0;

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
     * stands in it.</p>
     *
     * <p>Refused means refused from every direction, a stored config included: a value the picker
     * will not let the user pick is not one it will sit on and hand back to save(). A selection
     * this call refuses moves to {@link #setDefaultItem the default}, the same way
     * {@link #setSelectedItem} answers one. The item stays listed, so the set still reads whole --
     * what it no longer does is leave a VM quietly pointed down a path this build cannot take.</p>
     *
     * <p>Cleared by {@link #setItems} and {@link #autoItems}, since a new item set has its own
     * answer -- the same constant can be reachable under one and not under another.</p>
     */
    public void setDisabledItems(@Nullable CharSequence note, @NonNull Collection<E> refused) {
        disabled.clear();
        disabled.addAll(refused);
        disabledNote = note;
        if (selectedIndex >= 0 && selectedIndex < items.size()
            && disabled.contains(items.get(selectedIndex)))
            setSelectedIndex(fallbackIndex());
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
        defaultIndex = 0;
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
        defaultIndex = 0;
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

    @SuppressWarnings("unused")
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

    /**
     * Selects {@code item}, or the default when this picker will not take it.
     *
     * <p>Stored values outlive the sets that produced them. An option gets retired between
     * releases; several rows build their item set out of another row's value, so a config written
     * under one combination is routinely read back under another. Answering that with an exception
     * made every restore path a crash waiting for the first user whose VM predates the current
     * build -- which is what it was: an old VM naming a GL provider, under a backend whose set no
     * longer lists one, took the editor down as it opened.</p>
     *
     * <p>So an item this picker does not list, or lists only to refuse (see
     * {@link #setDisabledItems}), lands on {@link #setDefaultItem the default} and the call says
     * so. A caller restoring a config does not have to work out which values belong to the set it
     * just installed -- the set already knows, and that is the one copy of the rule.</p>
     *
     * @return whether {@code item} itself was selected
     */
    public boolean setSelectedItem(@NonNull E item) {
        int index = items.indexOf(item);
        if (index < 0 || disabled.contains(item)) {
            setSelectedIndex(fallbackIndex());
            return false;
        }
        setSelectedIndex(index);
        return true;
    }

    /**
     * The item a refused selection falls back to. Defaults to the first one, which is also what a
     * freshly installed set selects.
     *
     * <p>Worth naming wherever the head of the list is not the sensible answer -- a ladder whose
     * bottom rung is the safe one but whose default is the highest rung this build reaches, say.
     * Falling back to the head there would answer a value the build cannot honour with the slowest
     * thing it can do, a downgrade the user never asked for and would have no way to notice.</p>
     *
     * <p>Set it after the {@link #setItems} that installs the set: a new set resets this along
     * with everything else that was true of the old one.</p>
     */
    public void setDefaultItem(@NonNull E item) {
        int index = items.indexOf(item);
        if (index < 0)
            throw new IllegalArgumentException("Default item not found in items");
        defaultIndex = index;
    }

    /** The default's index, or the first selectable item when the default is itself refused. */
    private int fallbackIndex() {
        if (defaultIndex >= 0 && defaultIndex < items.size()
            && !disabled.contains(items.get(defaultIndex)))
            return defaultIndex;
        for (int i = 0; i < items.size(); i++)
            if (!disabled.contains(items.get(i))) return i;
        // Every item refused. Nothing here is selectable, so the head is as good an answer as any
        // -- and better than leaving the picker with no selection for getSelectedItem() to fail on.
        return 0;
    }

    /**
     * Steps to the next item, refused ones skipped: the rotate-mode button's whole gesture.
     *
     * <p>Rotation is the one way in that has no menu to grey a row out in, so the skip has to
     * happen here. Without it the gesture walks onto values the dialog and the popup both refuse,
     * which is the same picker answering the same question two ways.</p>
     */
    public void selectNext() {
        for (int step = 1; step <= items.size(); step++) {
            var index = (selectedIndex + step) % items.size();
            if (disabled.contains(items.get(index))) continue;
            setSelectedIndex(index);
            return;
        }
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

    @SuppressWarnings("unused")
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
