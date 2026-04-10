package cn.classfun.droidvm.ui.widgets.row;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.AdapterView;
import android.widget.AutoCompleteTextView;
import android.widget.Filterable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.TextInputLayout;

import cn.classfun.droidvm.R;

public final class DropdownRowWidget extends FrameLayout {
    private final Context context;
    private ImageView iconView;
    private TextInputLayout textInputLayout;
    private AutoCompleteTextView dropdownView;

    public DropdownRowWidget(@NonNull Context context) {
        super(context);
        this.context = context;
        init(null);
    }

    public DropdownRowWidget(
        @NonNull Context context,
        @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        this.context = context;
        init(attrs);
    }

    public DropdownRowWidget(
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
        inf.inflate(R.layout.widget_dropdown_row, this, true);
        iconView = findViewById(R.id.dd_icon);
        textInputLayout = findViewById(R.id.dd_layout);
        dropdownView = findViewById(R.id.dd_dropdown);
        initAttrs(attrs);
    }

    private void initAttrs(@Nullable AttributeSet attrs) {
        if (attrs == null) return;
        try (var a = context.obtainStyledAttributes(attrs, R.styleable.DropdownRowWidget)) {
            var icon = a.getDrawable(R.styleable.DropdownRowWidget_android_icon);
            if (icon != null) {
                iconView.setImageDrawable(icon);
            } else {
                iconView.setVisibility(GONE);
                var lp = (LayoutParams) textInputLayout.getLayoutParams();
                lp.setMarginStart(0);
                textInputLayout.setLayoutParams(lp);
            }
            var hint = a.getString(R.styleable.DropdownRowWidget_android_hint);
            if (hint != null) {
                textInputLayout.setHint(hint);
                iconView.setContentDescription(hint);
            }
        }
    }

    public <T extends ListAdapter & Filterable> void setAdapter(@Nullable T adapter) {
        dropdownView.setAdapter(adapter);
    }

    @NonNull
    public String getText() {
        var e = dropdownView.getText();
        return e != null ? e.toString() : "";
    }

    public void setText(@Nullable CharSequence text) {
        dropdownView.setText(text, false);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        textInputLayout.setEnabled(enabled);
        dropdownView.setEnabled(enabled);
        dropdownView.setFocusable(enabled);
        dropdownView.setFocusableInTouchMode(false);
        if (!enabled) dropdownView.dismissDropDown();
    }

    public void setOnItemClickListener(
        @Nullable AdapterView.OnItemClickListener listener
    ) {
        dropdownView.setOnItemClickListener(listener);
    }
}
