package cn.classfun.droidvm.ui.widgets.container;

import android.content.Context;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;

@SuppressWarnings({"unused", "FieldCanBeLocal"})
public final class CollapsibleContainer extends LinearLayout {
    private static final int TRANSITION_DURATION = 200;
    private final Context context;
    private LinearLayout header;
    private LinearLayout contentContainer;
    private ImageView chevron;
    private TextView titleView;
    private boolean expanded = true;
    private boolean collapsible = true;

    public CollapsibleContainer(@NonNull Context context) {
        super(context);
        this.context = context;
        init(null);
    }

    public CollapsibleContainer(
        @NonNull Context context,
        @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        this.context = context;
        init(attrs);
    }

    public CollapsibleContainer(
        @NonNull Context context,
        @Nullable AttributeSet attrs,
        int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        this.context = context;
        init(attrs);
    }

    private void init(@Nullable AttributeSet attrs) {
        setOrientation(VERTICAL);
        var inf = LayoutInflater.from(context);
        inf.inflate(R.layout.widget_collapsible_container, this, true);
        header = findViewById(R.id.cc_header);
        titleView = findViewById(R.id.cc_title);
        chevron = findViewById(R.id.cc_chevron);
        contentContainer = findViewById(R.id.cc_content);
        initAttrs(attrs);
        applyExpandedState(false);
        if (isInEditMode()) return;
        if (collapsible)
            header.setOnClickListener(v -> toggle());
        chevron.setVisibility(collapsible ? VISIBLE : GONE);
    }

    private void initAttrs(@Nullable AttributeSet attrs) {
        if (attrs == null) return;
        try (var a = context.obtainStyledAttributes(attrs, R.styleable.CollapsibleContainer)) {
            var title = a.getString(R.styleable.CollapsibleContainer_android_title);
            if (title != null) titleView.setText(title);
            expanded = a.getBoolean(R.styleable.CollapsibleContainer_cc_expanded, true);
            collapsible = a.getBoolean(R.styleable.CollapsibleContainer_cc_collapsible, true);
            chevron.setVisibility(collapsible ? VISIBLE : GONE);
        }
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (contentContainer != null) {
            contentContainer.addView(child, index, params);
        } else {
            super.addView(child, index, params);
        }
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        if (!collapsible && !expanded)
            throw new IllegalStateException("Container is not collapsible");
        if (this.expanded == expanded) return;
        this.expanded = expanded;
        applyExpandedState(true);
    }

    public void toggle() {
        if (!collapsible) return;
        expanded = !expanded;
        applyExpandedState(true);
    }

    public void setTitle(@StringRes int resId) {
        titleView.setText(resId);
    }

    public void setTitle(@Nullable CharSequence text) {
        titleView.setText(text);
    }

    @NonNull
    public LinearLayout getContentContainer() {
        return contentContainer;
    }

    private void applyExpandedState(boolean animate) {
        if (!collapsible) return;
        if (animate) {
            var parent = getParent();
            ViewGroup transitionRoot = (parent instanceof ViewGroup) ? (ViewGroup) parent : this;
            var transition = new AutoTransition();
            transition.setDuration(TRANSITION_DURATION);
            TransitionManager.beginDelayedTransition(transitionRoot, transition);
        }
        contentContainer.setVisibility(expanded ? VISIBLE : GONE);
        chevron.setImageResource(
            expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more
        );
    }
}
