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

public final class PickerButtonWidget extends RelativeLayout {
    private final Context context;
    private MaterialButton buttonView;
    private EnumPicker<?> picker = null;
    private EnumPickerChanged<Enum<?>> listener = null;
    private String title = null;
    private Mode mode = Mode.DIALOG;

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
            if (picker == null) return;
            switch (mode) {
                case DIALOG:
                    picker.showDialog(title);
                    break;
                case ROTATE:
                    var idx = picker.getSelectedIndex();
                    idx = (idx + 1) % picker.getItemCount();
                    picker.setSelectedIndex(idx);
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

    public void setSelectedItem(Enum<?> val) {
        if (picker == null)
            throw new IllegalStateException("Items not set");
        if (val.getDeclaringClass() != picker.getEnumClass())
            throw new IllegalArgumentException("Invalid item type");
        setPickerSelectedItem(picker, val);
    }

    private static <E extends Enum<E>> void setPickerSelectedItem(
        @NonNull EnumPicker<E> picker, Enum<?> val
    ) {
        picker.setSelectedItem(picker.getEnumClass().cast(val));
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

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        buttonView.setEnabled(enabled);
    }
}
