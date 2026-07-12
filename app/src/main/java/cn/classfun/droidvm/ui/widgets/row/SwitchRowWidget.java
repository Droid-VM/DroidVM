package cn.classfun.droidvm.ui.widgets.row;

import android.annotation.SuppressLint;
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
        setOnClickListener(v -> {
            if (switchView.isEnabled()) switchView.toggle();
        });
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

    /**
     * Locks interaction without greying the switch: it keeps its normal
     * on/off colour (so a forced-on state reads as on, not disabled) but
     * can't be tapped or dragged. Pass true to restore normal interaction.
     */
    @SuppressLint("ClickableViewAccessibility")
    public void setSwitchEnabled(boolean enabled) {
        switchView.setOnTouchListener(enabled ? null : (v, e) -> true);
        setOnClickListener(!enabled ? null : v -> {
            if (switchView.isEnabled()) switchView.toggle();
        });
        setClickable(enabled);
    }

    /**
     * FrameLayout.setEnabled alone leaves the child MaterialSwitch enabled and
     * directly draggable, so a "disabled" row could still fire its change
     * listener. Propagate to the switch so disabling actually greys and locks it.
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        switchView.setEnabled(enabled);
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
