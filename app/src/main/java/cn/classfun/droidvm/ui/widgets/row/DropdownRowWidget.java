// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.widgets.row;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.accessibility.AccessibilityEvent;
import android.widget.AutoCompleteTextView;
import android.widget.AdapterView;
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
     * Keeps an accessibility event that belongs to somebody else from opening this menu.
     *
     * <p>Material's exposed-dropdown delegate implements "a screen reader clicked the field" by
     * watching for a {@code TYPE_VIEW_CLICKED} in {@code onPopulateAccessibilityEvent} and
     * calling {@code showHideDropdown()} when it sees one. But populating is a walk DOWN from
     * whichever view sent the event, through every child it has, so an event sent by an
     * ancestor is offered to every dropdown beneath it. A dialog cancelled by a touch outside
     * sends one from its root, and every row in that dialog opens at once -- two popups, for a
     * click on neither of them, on the frame the dialog is already fading out.
     *
     * <p>Only the walk from above is refused. An event this row sends for itself starts at the
     * field, or at the layout the delegate is attached to, and never passes through here, so a
     * screen reader clicking the field still opens the menu. The cost is that the row's text is
     * not gathered into an event somebody else sent, which is what was going wrong.</p>
     *
     * <p>Only reachable with an accessibility service running, which is why it looks like a race
     * on one phone and never happens on another.</p>
     */
    @Override
    public boolean dispatchPopulateAccessibilityEvent(@NonNull AccessibilityEvent event) {
        return false;
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
