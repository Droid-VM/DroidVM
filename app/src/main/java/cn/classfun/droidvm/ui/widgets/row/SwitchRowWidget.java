package cn.classfun.droidvm.ui.widgets.row;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.materialswitch.MaterialSwitch;

import cn.classfun.droidvm.R;

public final class SwitchRowWidget extends FrameLayout {
    private final Context context;
    private ImageView iconView;
    private TextView textView;
    private TextView subtitleView;
    private MaterialSwitch switchView;

    public SwitchRowWidget(@NonNull Context context) {
        super(context);
        this.context = context;
        init(null);
    }

    public SwitchRowWidget(
        @NonNull Context context,
        @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        this.context = context;
        init(attrs);
    }

    public SwitchRowWidget(
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
        inf.inflate(R.layout.widget_switch_row, this, true);
        iconView = findViewById(R.id.sw_icon);
        textView = findViewById(R.id.sw_text);
        subtitleView = findViewById(R.id.sw_subtitle);
        switchView = findViewById(R.id.sw_switch);
        initAttrs(attrs);
        if (isInEditMode()) return;
        setOnClickListener(v -> switchView.toggle());
    }

    private void initAttrs(@Nullable AttributeSet attrs) {
        if (attrs == null) return;
        try (var a = context.obtainStyledAttributes(attrs, R.styleable.SwitchRowWidget)) {
            var icon = a.getDrawable(R.styleable.SwitchRowWidget_android_icon);
            if (icon != null) iconView.setImageDrawable(icon);
            var text = a.getString(R.styleable.SwitchRowWidget_android_text);
            if (text != null) {
                textView.setText(text);
                iconView.setContentDescription(text);
            }
            var subtitle = a.getString(R.styleable.SwitchRowWidget_android_subtitle);
            if (subtitle != null) {
                subtitleView.setText(subtitle);
                subtitleView.setVisibility(VISIBLE);
            } else {
                subtitleView.setVisibility(GONE);
            }
            var checked = a.getBoolean(R.styleable.SwitchRowWidget_android_checked, false);
            switchView.setChecked(checked);
        }
    }

    public boolean isChecked() {
        return switchView.isChecked();
    }

    public void setChecked(boolean checked) {
        switchView.setChecked(checked);
    }

    public void setOnCheckedChangeListener(
        @Nullable CompoundButton.OnCheckedChangeListener listener
    ) {
        switchView.setOnCheckedChangeListener(listener);
    }

    public void setOnCheckedChangeListener(
        @Nullable Runnable listener
    ) {
        switchView.setOnCheckedChangeListener(
            listener == null ? null : (b, c) -> listener.run()
        );
    }
}
