package cn.classfun.droidvm.ui.setup.step;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.threadSleep;
import static cn.classfun.droidvm.ui.setup.SetupActivity.CHECK_DELAY;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.setup.base.BaseCheckStepFragment;

public final class StorageStepFragment extends BaseCheckStepFragment {
    private static final String TAG = "StorageStepFragment";
    private ActivityResultLauncher<Intent> manageStorageLauncher;
    private MaterialButton btnGrant;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        manageStorageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> runCheck()
        );
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_setup_step_storage, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        btnGrant = view.findViewById(R.id.btn_grant);
        btnGrant.setOnClickListener(v -> requestStorageAccess());
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    protected void runCheck() {
        showLoading(R.string.setup_storage_title, R.string.setup_storage_desc);
        btnGrant.setVisibility(GONE);
        runOnPool(this::operationThread);
    }

    private void operationThread() {
        threadSleep(CHECK_DELAY);
        var granted = Environment.isExternalStorageManager();
        Log.i(TAG, fmt("storage access: %s", granted));
        requireActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            if (granted) {
                showSuccess(R.string.setup_storage_title, R.string.setup_storage_success);
            } else {
                showError(R.string.setup_storage_title, R.string.setup_storage_fail);
                btnGrant.setVisibility(VISIBLE);
            }
        });
    }

    @Override
    public boolean isHiddenStep() {
        return Environment.isExternalStorageManager();
    }

    private void requestStorageAccess() {
        var intent = new Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse(fmt("package:%s", requireContext().getPackageName()))
        );
        manageStorageLauncher.launch(intent);
    }
}
