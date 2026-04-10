package cn.classfun.droidvm.lib.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem.OnMenuItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;
import android.widget.PopupMenu;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

public final class MenuDialogBuilder extends MaterialAlertDialogBuilder {
    private final Context context;
    private Menu menu = null;
    private OnMenuItemClickListener listener = null;
    private Drawable iconDrawable = null;

    public MenuDialogBuilder(@NonNull Context context) {
        super(context);
        this.context = context;
    }

    @NonNull
    @SuppressWarnings("UnusedReturnValue")
    public MenuDialogBuilder setTitle(@StringRes int titleRes) {
        return (MenuDialogBuilder) super.setTitle(titleRes);
    }


    @NonNull
    @SuppressWarnings("UnusedReturnValue")
    public MenuDialogBuilder setTitle(@Nullable CharSequence title) {
        return (MenuDialogBuilder) super.setTitle(title);
    }

    @NonNull
    @SuppressWarnings("UnusedReturnValue")
    public MenuDialogBuilder setIcon(int iconId) {
        this.iconDrawable = AppCompatResources.getDrawable(context, iconId);
        return this;
    }

    @NonNull
    @SuppressWarnings("UnusedReturnValue")
    public MenuDialogBuilder setIcon(Drawable iconDrawable) {
        this.iconDrawable = iconDrawable;
        return this;
    }

    @NonNull
    @SuppressWarnings("UnusedReturnValue")
    public MenuDialogBuilder inflate(@MenuRes int menuRes) {
        var pop = new PopupMenu(context, null);
        pop.getMenuInflater().inflate(menuRes, pop.getMenu());
        return setMenu(pop.getMenu());
    }

    @NonNull
    @SuppressWarnings("UnusedReturnValue")
    public MenuDialogBuilder setMenu(@NonNull Menu menu) {
        this.menu = menu;
        return this;
    }

    @Override
    @SuppressWarnings("UnusedReturnValue")
    public AlertDialog show() {
        if (menu == null) throw new IllegalStateException("No menu items added");
        var adapter = createMenuAdapter(menu);
        setAdapter(adapter, (dialog, which) -> {
            var item = menu.getItem(which);
            if (listener != null)
                listener.onMenuItemClick(item);
        });
        return super.show();
    }

    @NonNull
    private ListAdapter createMenuAdapter(@NonNull Menu menu) {
        var names = new ArrayList<String>();
        var icons = new ArrayList<Drawable>();
        var ids = new ArrayList<Integer>();
        boolean hasIcons = false;
        for (int i = 0; i < menu.size(); i++) {
            var item = menu.getItem(i);
            var t = item.getTitle();
            names.add(t != null ? t.toString() : "");
            ids.add(item.getItemId());
            if (iconDrawable == null) {
                var icon = item.getIcon();
                icons.add(icon);
                if (icon != null) hasIcons = true;
            }
        }
        if (iconDrawable != null)
            return IconItemAdapter.createDrawable(context, names, iconDrawable);
        if (!hasIcons) return new ArrayAdapter<>(
            context, android.R.layout.simple_list_item_1, names
        );
        return IconItemAdapter.createDrawable(context, names, icons);
    }

    @NonNull
    @SuppressWarnings("UnusedReturnValue")
    public MenuDialogBuilder setListener(@Nullable OnMenuItemClickListener listener) {
        this.listener = listener;
        return this;
    }

    @SuppressWarnings("UnusedReturnValue")
    public static AlertDialog showSimple(
        @NonNull Context context,
        @StringRes int titleRes,
        @MenuRes int menuRes,
        @Nullable OnMenuItemClickListener listener
    ) {
        return new MenuDialogBuilder(context)
            .setTitle(titleRes)
            .inflate(menuRes)
            .setListener(listener)
            .show();
    }

    @SuppressWarnings({"UnusedReturnValue", "unused"})
    public static AlertDialog showSimple(
        @NonNull Context context,
        @Nullable CharSequence title,
        @MenuRes int menuRes,
        @Nullable OnMenuItemClickListener listener
    ) {
        return new MenuDialogBuilder(context)
            .setTitle(title)
            .inflate(menuRes)
            .setListener(listener)
            .show();
    }
}
