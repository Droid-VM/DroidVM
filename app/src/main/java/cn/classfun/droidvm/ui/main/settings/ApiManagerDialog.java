package cn.classfun.droidvm.ui.main.settings;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.api.ApiManager;
import cn.classfun.droidvm.lib.api.Privacy;

public final class ApiManagerDialog {
    private final Context context;
    private final ApiManager apiManager;

    public ApiManagerDialog(@NonNull Context context, @NonNull ApiManager apiManager) {
        this.context = context;
        this.apiManager = apiManager;
    }

    public void show() {
        if (!Privacy.isPrivacyAgreed(context)) {
            Toast.makeText(context,
                R.string.settings_api_manager_not_loaded, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            var apiInfo = ApiManager.getApiInfo();
            var services = apiInfo.getServices();
            if (services.isEmpty()) {
                Toast.makeText(context,
                    R.string.settings_api_manager_not_loaded, Toast.LENGTH_SHORT).show();
                return;
            }
            var list = new ArrayList<>(services.values());
            var recyclerView = new RecyclerView(context);
            recyclerView.setLayoutManager(new LinearLayoutManager(context));
            recyclerView.setAdapter(new ApiServiceAdapter(list, apiManager));
            new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_api_manager_title)
                .setView(recyclerView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        } catch (Exception e) {
            Toast.makeText(context,
                R.string.settings_api_manager_not_loaded, Toast.LENGTH_SHORT).show();
        }
    }
}
