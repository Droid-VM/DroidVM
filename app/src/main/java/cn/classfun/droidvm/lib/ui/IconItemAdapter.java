package cn.classfun.droidvm.lib.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import java.util.List;

import cn.classfun.droidvm.R;

public final class IconItemAdapter extends ArrayAdapter<String> {
    private final List<String> names;
    private Drawable[] iconsDrawable = null;
    private Drawable iconDrawable = null;
    private int[] iconsId = null;
    private int iconId = 0;

    private IconItemAdapter(
        @NonNull Context context,
        @NonNull List<String> names
    ) {
        super(context, R.layout.item_icon_text, names);
        this.names = names;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext())
                .inflate(R.layout.item_icon_text, parent, false);
        }
        ImageView iv = convertView.findViewById(R.id.iv_icon);
        TextView tv = convertView.findViewById(R.id.tv_label);
        if (iconDrawable != null)
            iv.setImageDrawable(iconDrawable);
        else if (iconId != 0)
            iv.setImageResource(iconId);
        else if (iconsDrawable != null && position < iconsDrawable.length)
            iv.setImageDrawable(iconsDrawable[position]);
        else if (iconsId != null && position < iconsId.length)
            iv.setImageResource(iconsId[position]);
        tv.setText(names.get(position));
        return convertView;
    }

    @NonNull
    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                var results = new FilterResults();
                results.values = names;
                results.count = names.size();
                return results;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                notifyDataSetChanged();
            }
        };
    }

    @NonNull
    @SuppressWarnings("unused")
    public static IconItemAdapter createDrawable(
        @NonNull Context context,
        @NonNull List<String> names,
        @NonNull List<Drawable> icons
    ) {
        var adapter = new IconItemAdapter(context, names);
        adapter.iconsDrawable = icons.toArray(new Drawable[0]);
        return adapter;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static IconItemAdapter createDrawable(
        @NonNull Context context,
        @NonNull List<String> names,
        @NonNull Drawable icon
    ) {
        var adapter = new IconItemAdapter(context, names);
        adapter.iconDrawable = icon;
        return adapter;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static IconItemAdapter createDrawable(
        @NonNull Context context,
        @NonNull String[] names,
        @NonNull Drawable[] icons
    ) {
        var adapter = new IconItemAdapter(context, List.of(names));
        adapter.iconsDrawable = icons;
        return adapter;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static IconItemAdapter createDrawable(
        @NonNull Context context,
        @NonNull String[] names,
        @NonNull Drawable icon
    ) {
        var adapter = new IconItemAdapter(context, List.of(names));
        adapter.iconDrawable = icon;
        return adapter;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static IconItemAdapter create(
        @NonNull Context context,
        @NonNull List<String> names,
        @DrawableRes int iconRes
    ) {
        var adapter = new IconItemAdapter(context, names);
        adapter.iconId = iconRes;
        return adapter;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static IconItemAdapter create(
        @NonNull Context context,
        @NonNull List<String> names,
        @NonNull List<Integer> iconRes
    ) {
        var adapter = new IconItemAdapter(context, names);
        adapter.iconsId = new int[iconRes.size()];
        for (int i = 0; i < iconRes.size(); i++)
            adapter.iconsId[i] = iconRes.get(i);
        return adapter;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static IconItemAdapter create(
        @NonNull Context context,
        @NonNull List<String> names,
        @NonNull int[] iconRes
    ) {
        var adapter = new IconItemAdapter(context, names);
        adapter.iconsId = iconRes;
        return adapter;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static IconItemAdapter create(
        @NonNull Context context,
        @NonNull String[] names,
        @DrawableRes int iconRes
    ) {
        var adapter = new IconItemAdapter(context, List.of(names));
        adapter.iconId = iconRes;
        return adapter;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static IconItemAdapter create(
        @NonNull Context context,
        @NonNull String[] names,
        @NonNull int[] iconRes
    ) {
        var adapter = new IconItemAdapter(context, List.of(names));
        adapter.iconsId = iconRes;
        return adapter;
    }
}
