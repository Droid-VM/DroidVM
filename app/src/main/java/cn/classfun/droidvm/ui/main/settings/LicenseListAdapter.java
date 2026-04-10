package cn.classfun.droidvm.ui.main.settings;


import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.data.License;

final class LicenseListAdapter extends ArrayAdapter<License> {
    private final List<License> items;

    LicenseListAdapter(@NonNull Context context, @NonNull List<License> items) {
        super(context, 0, items);
        this.items = items;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View v, @NonNull ViewGroup parent) {
        if (v == null) v = LayoutInflater.from(getContext())
            .inflate(R.layout.item_license, parent, false);
        var item = items.get(position);
        TextView tv_license_name = v.findViewById(R.id.tv_license_name);
        TextView tv_license_description = v.findViewById(R.id.tv_license_description);
        TextView tv_license_type = v.findViewById(R.id.tv_license_type);
        tv_license_name.setText(item.getName());
        tv_license_description.setText(item.getDescription());
        tv_license_type.setText(item.getLicense());
        return v;
    }
}