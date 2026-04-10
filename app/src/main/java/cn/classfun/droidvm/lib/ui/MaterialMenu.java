package cn.classfun.droidvm.lib.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListPopupWindow;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;

public final class MaterialMenu {
    private final Context context;
    private final View anchor;
    private final Menu menu;
    private final MenuInflater menuInflater;
    private final ListPopupWindow popup;
    private MenuItem.OnMenuItemClickListener listener;
    private int gravity = Gravity.NO_GRAVITY;

    public MaterialMenu(@NonNull Context context, @NonNull View anchor) {
        this.context = context;
        this.anchor = anchor;
        var helper = new PopupMenu(context, anchor);
        this.menu = helper.getMenu();
        this.menuInflater = helper.getMenuInflater();
        this.popup = new ListPopupWindow(context);
        popup.setAnchorView(anchor);
        popup.setModal(true);
    }

    @NonNull
    public Menu getMenu() {
        return menu;
    }

    public void setItemVisible(@IdRes int id, boolean visible) {
        var item = menu.findItem(id);
        if (item != null) item.setVisible(visible);
    }

    public void inflate(@MenuRes int menuRes) {
        menuInflater.inflate(menuRes, menu);
    }

    public void setOnMenuItemClickListener(
        @Nullable MenuItem.OnMenuItemClickListener listener
    ) {
        this.listener = listener;
    }

    @SuppressWarnings("unused")
    public void setGravity(int gravity) {
        this.gravity = gravity;
    }

    public void show() {
        preparePopup();
        popup.setDropDownGravity(gravity);
        showPopup();
    }

    public void showAtTouch(float x, float y) {
        int contentWidth = preparePopup();
        popup.setDropDownGravity(Gravity.START);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        if (x != 0) {
            int offset = (int) x - loc[0] - contentWidth / 2;
            popup.setHorizontalOffset(offset);
        }
        if (y != 0) {
            int offset = (int) y - loc[1];
            popup.setVerticalOffset(offset);
        }
        showPopup();
    }

    private int preparePopup() {
        var items = collectItems();
        var hasIcons = items.stream().anyMatch(i -> i.icon != null);
        var adapter = new MenuAdapter(context, items, hasIcons);
        popup.setAdapter(adapter);
        var size = measureContent(adapter);
        popup.setContentWidth(size.x);
        popup.setHeight(size.y);
        popup.setBackgroundDrawable(createMaterial3Background());
        popup.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            if (listener != null && position < items.size())
                listener.onMenuItemClick(items.get(position).menuItem);
        });
        return size.x;
    }

    private void showPopup() {
        popup.show();
        applyListViewStyle();
    }

    @NonNull
    private List<MenuEntry> collectItems() {
        var result = new ArrayList<MenuEntry>();
        for (int i = 0; i < menu.size(); i++) {
            var item = menu.getItem(i);
            if (!item.isVisible()) continue;
            var title = item.getTitle();
            result.add(new MenuEntry(
                item,
                title != null ? title.toString() : "",
                item.getIcon()
            ));
        }
        return result;
    }

    @NonNull
    private Point measureContent(@NonNull BaseAdapter adapter) {
        int maxWidth = 0, totalHeight = 0;
        int widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        var parent = anchor instanceof ViewGroup ?
            (ViewGroup) anchor : (ViewGroup) anchor.getParent();
        for (int i = 0; i < adapter.getCount(); i++) {
            var view = adapter.getView(i, null, parent);
            view.measure(widthSpec, heightSpec);
            maxWidth = Math.max(maxWidth, view.getMeasuredWidth());
            totalHeight += view.getMeasuredHeight();
        }
        float density = context.getResources().getDisplayMetrics().density;
        int minWidth = (int) (196 * density);
        int maxAllowed = (int) (280 * density);
        int hPad = (int) (16 * density);
        int vPad = (int) (8 * density) * 2;
        int width = Math.min(Math.max(maxWidth + hPad, minWidth), maxAllowed);
        int height = totalHeight + vPad;
        return new Point(width, height);
    }

    @NonNull
    private Drawable createMaterial3Background() {
        float density = context.getResources().getDisplayMetrics().density;
        float cornerSize = 16 * density;
        var shape = new ShapeAppearanceModel.Builder()
            .setAllCornerSizes(cornerSize)
            .build();
        var bg = new MaterialShapeDrawable(shape);
        int colorAttr = com.google.android.material.R.attr.colorSurfaceContainer;
        try (var ta = context.obtainStyledAttributes(new int[]{colorAttr})) {
            int surfaceColor = ta.getColor(0, 0xFFFFFFFF);
            bg.setFillColor(ColorStateList.valueOf(surfaceColor));
        }
        bg.setElevation(8 * density);
        bg.setShadowCompatibilityMode(
            MaterialShapeDrawable.SHADOW_COMPAT_MODE_ALWAYS
        );
        return bg;
    }

    private void applyListViewStyle() {
        var listView = popup.getListView();
        if (listView == null) return;
        float density = context.getResources().getDisplayMetrics().density;
        int vertPad = (int) (8 * density);
        listView.setPadding(0, vertPad, 0, vertPad);
        listView.setClipToPadding(false);
        listView.setDivider(null);
        listView.setDividerHeight(0);
    }

    private static final class MenuEntry {
        final MenuItem menuItem;
        final String title;
        final Drawable icon;

        MenuEntry(MenuItem menuItem, String title, Drawable icon) {
            this.menuItem = menuItem;
            this.title = title;
            this.icon = icon;
        }
    }

    private static final class MenuAdapter extends BaseAdapter {
        private final Context context;
        private final List<MenuEntry> items;
        private final boolean hasIcons;

        MenuAdapter(
            @NonNull Context context,
            @NonNull List<MenuEntry> items,
            boolean hasIcons
        ) {
            this.context = context;
            this.items = items;
            this.hasIcons = hasIcons;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public MenuEntry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean isEnabled(int position) {
            return items.get(position).menuItem.isEnabled();
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                var inf = LayoutInflater.from(context);
                convertView = inf.inflate(R.layout.item_material_menu, parent, false);
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            var entry = items.get(position);
            holder.title.setText(entry.title);
            if (!hasIcons) {
                holder.icon.setVisibility(View.GONE);
            } else if (entry.icon != null) {
                holder.icon.setImageDrawable(entry.icon);
                holder.icon.setVisibility(View.VISIBLE);
            } else {
                holder.icon.setImageDrawable(null);
                holder.icon.setVisibility(View.INVISIBLE);
            }
            convertView.setEnabled(entry.menuItem.isEnabled());
            convertView.setAlpha(entry.menuItem.isEnabled() ? 1f : 0.38f);
            return convertView;
        }

        private static final class ViewHolder {
            final ImageView icon;
            final TextView title;

            ViewHolder(@NonNull View view) {
                icon = view.findViewById(R.id.menu_icon);
                title = view.findViewById(R.id.menu_title);
            }
        }
    }
}
