// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
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
        refuseFiltering();
    }

    /**
     * Takes the popup off the filter. Material's exposed-dropdown delegate calls
     * {@code setThreshold(0)}, so {@code enoughToFilter()} is true for every value including
     * none, and {@code AutoCompleteTextView.updateDropDownForFilter} then shows the popup on any
     * completed filter. These rows are menus and their adapter returns every entry whatever the
     * constraint, so a threshold nothing reaches loses nothing: the delegate's own show is a
     * plain {@code showDropDown()} that no threshold gates.
     */
    private void refuseFiltering() {
        dropdownView.setThreshold(Integer.MAX_VALUE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refuseFiltering();
    }

    private void initAttrs(@Nullable AttributeSet attrs) {
        if (attrs == null) return;
        try (var a = context.obtainStyledAttributes(attrs, R.styleable.DropdownRowWidget)) {
            var icon = a.getDrawable(R.styleable.DropdownRowWidget_android_icon);
            if (icon != null) {
                iconView.setImageDrawable(icon);
            } else {
                iconView.setVisibility(GONE);
                // MarginLayoutParams, not this view's own: the field is a child of the
                // LinearLayout inflated inside, so its params are that layout's, and casting
                // them to the FrameLayout's threw for every row that carries no icon.
                iconView.setContentDescription(null);
                var lp = (MarginLayoutParams) textInputLayout.getLayoutParams();
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

    /**
     * Closes the popup now, for an owner that knows its window is about to go.
     *
     * <p>A popup is a window of its own and does not leave with the one that anchors it. A
     * dialog cancelled by a touch outside starts leaving on the DOWN -- {@code shouldCloseOnTouch}
     * is true for a touch past its bounds -- one event earlier than a button's UP, and a popup
     * still open at that point is drawn for a frame or two over a window that is already fading.
     * The owner calls this before the window animates away; {@link #onDetachedFromWindow} is the
     * same close for an owner that does not.</p>
     */
    public void dismissPopup() {
        dropdownView.dismissDropDown();
    }

    @Override
    protected void onDetachedFromWindow() {
        dismissPopup();
        super.onDetachedFromWindow();
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
