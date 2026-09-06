// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.widgets.tools;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.button.MaterialButton;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.EnumPicker;
import cn.classfun.droidvm.lib.store.enums.EnumPicker.EnumPickerChanged;

import java.util.List;

public final class PickerButtonWidget extends RelativeLayout {
    private final Context context;
    private MaterialButton buttonView;
    private EnumPicker<?> picker = null;
    private EnumPickerChanged<Enum<?>> listener = null;
    private String title = null;
    private Mode mode = Mode.DIALOG;
    private Runnable unavailable = null;

    public enum Mode {
        DIALOG,
        ROTATE,
        POPUP,
    }

    public PickerButtonWidget(@NonNull Context context) {
        super(context);
        this.context = context;
        init(null);
    }

    public PickerButtonWidget(
        @NonNull Context context,
        @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        this.context = context;
        init(attrs);
    }

    public PickerButtonWidget(
        @NonNull Context context,
        @Nullable AttributeSet attrs,
        int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        this.context = context;
        init(attrs);
    }

    private void init(@Nullable AttributeSet attrs) {
        var dp = context.getResources().getDisplayMetrics().density;
        setMinimumHeight((int) (56 * dp));
        var inf = LayoutInflater.from(context);
        inf.inflate(R.layout.widget_picker_button, this, true);
        buttonView = findViewById(R.id.pb_button);
        initAttrs(attrs);
        if (isInEditMode()) return;
        buttonView.setOnClickListener(v -> {
            if (unavailable != null) {
                unavailable.run();
                return;
            }
            if (picker == null) return;
            switch (mode) {
                case DIALOG:
                    picker.showDialog(title);
                    break;
                case ROTATE:
                    picker.selectNext();
                    break;
                case POPUP:
                    picker.showPopup(buttonView);
                    break;
            }
        });
    }

    private void initAttrs(@Nullable AttributeSet attrs) {
        if (attrs == null) return;
        try (var a = context.obtainStyledAttributes(attrs, R.styleable.PickerButtonWidget)) {
            var text = a.getString(R.styleable.PickerButtonWidget_android_title);
            if (text != null) title = text;
            buttonView.setText("");
            var mode = a.getInt(
                R.styleable.PickerButtonWidget_pb_mode,
                Mode.POPUP.ordinal()
            );
            this.mode = Mode.values()[mode];
        }
    }

    @NonNull
    public <E extends Enum<E>> EnumPicker<E> setItems(@NonNull Class<E> cls) {
        var picker = new EnumPicker<>(context, cls);
        picker.setOnValueChangedListener(this::onValueChanged);
        this.picker = picker;
        return picker;
    }

    public <E extends Enum<E>> void setItems(@NonNull E begin, @NonNull E end) {
        if (begin.getDeclaringClass() != end.getDeclaringClass())
            throw new IllegalArgumentException("Invalid enum range");
        var picker = setItems(begin.getDeclaringClass());
        picker.setItems(begin, end);
    }

    @SafeVarargs
    public final <E extends Enum<E>> void setItems(@NonNull E... items) {
        if (items.length == 0)
            throw new IllegalArgumentException("Items cannot be empty");
        var cls = items[0].getDeclaringClass();
        for (E item : items)
            if (item.getDeclaringClass() != cls)
                throw new IllegalArgumentException("All items must be of the same enum type");
        var picker = setItems(cls);
        picker.setItems(items);
    }

    /**
     * Items the picker lists but refuses, annotated with {@code note}; see
     * {@link EnumPicker#setDisabledItems}. Call after the {@code setItems} that installs them --
     * a new item set clears the refusals, because the same constant can be reachable under one
     * set and not under another.
     */
    @SafeVarargs
    public final <E extends Enum<E>> void setDisabledItems(
        @Nullable CharSequence note, @NonNull E... refused
    ) {
        if (picker == null)
            throw new IllegalStateException("Items not set");
        this.<E>getPicker().setDisabledItems(note, List.of(refused));
    }

    private void onValueChanged(Enum<?> oldVal, Enum<?> newVal) {
        this.buttonView.setText(picker.getSelectedString());
        if (listener != null)
            listener.onChanged(oldVal, newVal);
    }

    public void setOnValueChangedListener(@Nullable EnumPickerChanged<Enum<?>> listener) {
        this.listener = listener;
    }

    public void setOnValueChangedListener(@Nullable Runnable listener) {
        this.listener = listener == null ? null : (o, n) -> listener.run();
    }

    public <E extends Enum<E>> void configure(@NonNull Class<E> cls, @NonNull E value) {
        setItems(cls);
        setSelectedItem(value);
    }

    @SuppressWarnings("unchecked")
    public <E extends Enum<E>> E getSelectedItem() {
        if (picker == null)
            throw new IllegalStateException("Items not set");
        return (E) picker.getSelectedItem();
    }

    /**
     * Selects {@code val}, or this row's default when the picker will not take it; see
     * {@link EnumPicker#setSelectedItem}. A wrong enum type is still a throw -- that is a
     * miswiring, not a value that arrived from a config.
     *
     * @return whether {@code val} itself was selected
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean setSelectedItem(Enum<?> val) {
        if (picker == null)
            throw new IllegalStateException("Items not set");
        if (val.getDeclaringClass() != picker.getEnumClass())
            throw new IllegalArgumentException("Invalid item type");
        return setPickerSelectedItem(picker, val);
    }

    private static <E extends Enum<E>> boolean setPickerSelectedItem(
        @NonNull EnumPicker<E> picker, Enum<?> val
    ) {
        return picker.setSelectedItem(picker.getEnumClass().cast(val));
    }

    /**
     * The value a refused selection falls back to; see {@link EnumPicker#setDefaultItem}. Call
     * after the {@code setItems} that installs the set, like {@link #setDisabledItems}.
     */
    public void setDefaultItem(Enum<?> val) {
        if (picker == null)
            throw new IllegalStateException("Items not set");
        if (val.getDeclaringClass() != picker.getEnumClass())
            throw new IllegalArgumentException("Invalid item type");
        setPickerDefaultItem(picker, val);
    }

    private static <E extends Enum<E>> void setPickerDefaultItem(
        @NonNull EnumPicker<E> picker, Enum<?> val
    ) {
        picker.setDefaultItem(picker.getEnumClass().cast(val));
    }

    @SuppressWarnings("unchecked")
    public <E extends Enum<E>> EnumPicker<E> getPicker() {
        return (EnumPicker<E>) picker;
    }

    @SuppressWarnings("unused")
    public void setMode(Mode mode) {
        this.mode = mode;
    }

    @SuppressWarnings("unused")
    public void setTitle(@Nullable CharSequence title) {
        this.title = title == null ? null : title.toString();
    }

    @SuppressWarnings("unused")
    public void setTitle(@StringRes int title) {
        this.title = context.getString(title);
    }

    /**
     * Marks the control present but inert: the current value stays readable, the picker never
     * opens, and a tap runs {@code onTap} instead -- for an option the config format still
     * carries but this build cannot honour. {@code null} restores normal picker behaviour.
     *
     * <p>Deliberately not {@link #setEnabled(boolean)}: a disabled button swallows the tap, so
     * there is nowhere left to explain why the option does nothing.
     */
    public void setUnavailable(@Nullable Runnable onTap) {
        this.unavailable = onTap;
        buttonView.setAlpha(onTap == null ? 1f : 0.5f);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        buttonView.setEnabled(enabled);
    }
}
