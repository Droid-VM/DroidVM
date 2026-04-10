package cn.classfun.droidvm.ui.widgets.row;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;

public final class TextRowWidget extends FrameLayout {
    private final Context context;
    private ImageView iconView;
    private TextView textView;
    private TextView subtitleView;
    private TextView valueView;

    public TextRowWidget(@NonNull Context context) {
        super(context);
        this.context = context;
        init(null);
    }

    public TextRowWidget(
        @NonNull Context context,
        @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        this.context = context;
        init(attrs);
    }

    public TextRowWidget(
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
        inf.inflate(R.layout.widget_text_row, this, true);
        iconView = findViewById(R.id.ti_icon);
        textView = findViewById(R.id.ti_text);
        subtitleView = findViewById(R.id.ti_subtitle);
        valueView = findViewById(R.id.ti_value);
        initAttrs(attrs);
    }

    private void initAttrs(@Nullable AttributeSet attrs) {
        if (attrs == null) return;
        try (var a = context.obtainStyledAttributes(attrs, R.styleable.TextRowWidget)) {
            var icon = a.getDrawable(R.styleable.TextRowWidget_android_icon);
            if (icon != null) iconView.setImageDrawable(icon);
            var text = a.getString(R.styleable.TextRowWidget_android_text);
            if (text != null) {
                textView.setText(text);
                iconView.setContentDescription(text);
            }
            var subtitle = a.getString(R.styleable.TextRowWidget_android_subtitle);
            if (subtitle != null) {
                subtitleView.setText(subtitle);
                subtitleView.setVisibility(VISIBLE);
            } else {
                subtitleView.setVisibility(GONE);
            }
            var value = a.getString(R.styleable.TextRowWidget_android_value);
            if (value != null) {
                valueView.setText(value);
                valueView.setVisibility(VISIBLE);
            } else {
                valueView.setVisibility(GONE);
            }
        }
    }

    public void setValue(@StringRes int resId) {
        valueView.setText(resId);
        subtitleView.setVisibility(VISIBLE);
    }

    public void setValue(@Nullable CharSequence text) {
        if (text != null) {
            valueView.setText(text);
            valueView.setVisibility(VISIBLE);
        } else {
            valueView.setVisibility(GONE);
        }
    }

    public void setSubtitle(@StringRes int resId) {
        subtitleView.setText(resId);
        subtitleView.setVisibility(VISIBLE);
    }

    public void setSubtitle(@Nullable CharSequence text) {
        if (text != null) {
            subtitleView.setText(text);
            subtitleView.setVisibility(VISIBLE);
        } else {
            subtitleView.setVisibility(GONE);
        }
    }

    @NonNull
    public String getValue() {
        return valueView.getText().toString();
    }

    @NonNull
    public String getTitle() {
        return textView.getText().toString();
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        super.setOnClickListener(l);
        setClickable(l != null);
        setFocusable(l != null);
    }
}

