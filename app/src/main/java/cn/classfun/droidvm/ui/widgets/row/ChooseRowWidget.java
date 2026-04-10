package cn.classfun.droidvm.ui.widgets.row;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.EnumPicker.EnumPickerChanged;
import cn.classfun.droidvm.ui.widgets.tools.PickerButtonWidget;

public final class ChooseRowWidget extends FrameLayout {
    private final Context context;
    private ImageView iconView;
    private TextView textView;
    private TextView subtitleView;
    private PickerButtonWidget buttonView;

    public ChooseRowWidget(@NonNull Context context) {
        super(context);
        this.context = context;
        init(null);
    }

    public ChooseRowWidget(
        @NonNull Context context,
        @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        this.context = context;
        init(attrs);
    }

    public ChooseRowWidget(
        @NonNull Context context,
        @Nullable AttributeSet attrs,
        int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        this.context = context;
        init(attrs);
    }

    private void init(@Nullable AttributeSet attrs) {
        var inf = LayoutInflater.from(context);
        inf.inflate(R.layout.widget_choose_row, this, true);
        iconView = findViewById(R.id.cw_icon);
        textView = findViewById(R.id.cw_text);
        subtitleView = findViewById(R.id.cw_subtitle);
        buttonView = findViewById(R.id.cw_button);
        initAttrs(attrs);
    }

    private void initAttrs(@Nullable AttributeSet attrs) {
        if (attrs == null) return;
        try (var a = context.obtainStyledAttributes(attrs, R.styleable.ChooseRowWidget)) {
            var icon = a.getDrawable(R.styleable.ChooseRowWidget_android_icon);
            if (icon != null) iconView.setImageDrawable(icon);
            var text = a.getString(R.styleable.ChooseRowWidget_android_text);
            if (text != null) {
                textView.setText(text);
                buttonView.setTitle(text);
                iconView.setContentDescription(text);
            }
            var subtitle = a.getString(R.styleable.ChooseRowWidget_android_subtitle);
            if (subtitle != null) {
                subtitleView.setText(subtitle);
                subtitleView.setVisibility(VISIBLE);
            } else {
                subtitleView.setVisibility(GONE);
            }
        }
    }

    public <E extends Enum<E>> void setItems(@NonNull Class<E> cls) {
        buttonView.setItems(cls);
    }

    @SafeVarargs
    public final <E extends Enum<E>> void setItems(@NonNull E... items) {
        buttonView.setItems(items);
    }

    public void setOnValueChangedListener(@Nullable EnumPickerChanged<Enum<?>> listener) {
        buttonView.setOnValueChangedListener(listener);
    }

    public void setOnValueChangedListener(@Nullable Runnable listener) {
        buttonView.setOnValueChangedListener(listener);
    }

    public <E extends Enum<E>> void configure(@NonNull Class<E> cls, @NonNull E value) {
        buttonView.configure(cls, value);
    }

    @SuppressWarnings("unchecked")
    public <E extends Enum<E>> E getSelectedItem() {
        return (E) buttonView.getPicker().getSelectedItem();
    }

    public void setSelectedItem(Enum<?> val) {
        buttonView.setSelectedItem(val);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        buttonView.setEnabled(enabled);
    }
}
