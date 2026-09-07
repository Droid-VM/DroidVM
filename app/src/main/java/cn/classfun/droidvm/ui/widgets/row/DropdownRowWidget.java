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
     * Takes the popup off the filter, which is the only thing that ever opened it by itself.
     *
     * <p>Material's exposed-dropdown delegate calls {@code setThreshold(0)}, so
     * {@code enoughToFilter()} is true for every value including none, and
     * {@code AutoCompleteTextView.updateDropDownForFilter} then calls {@code showDropDown()}
     * whenever a filter completes and the field happens to hold focus. That is a text box's
     * behaviour and this is a menu: it opened itself as its dialog appeared, and once the
     * dialog's root took the focus away instead, it opened itself again on the way out, as the
     * focus fell back to it while the window was closing -- the flash on dismiss.</p>
     *
     * <p>A threshold nothing can reach ends both. Nothing is lost: the adapter these rows carry
     * returns every entry whatever the constraint, so filtering never did anything, and the
     * delegate's own show is a plain {@code showDropDown()} that no threshold gates -- the
     * touch still opens it.</p>
     */
    private void refuseFiltering() {
        dropdownView.setThreshold(Integer.MAX_VALUE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Again here: the delegate sets its threshold when the field is attached to the layout,
        // and which of the two runs last is the library's business rather than ours.
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
