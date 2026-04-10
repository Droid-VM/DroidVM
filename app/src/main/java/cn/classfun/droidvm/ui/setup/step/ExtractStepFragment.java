package cn.classfun.droidvm.ui.setup.step;

import static cn.classfun.droidvm.lib.utils.AssetUtils.extractPrebuilt;
import static cn.classfun.droidvm.lib.utils.AssetUtils.needsExtractPrebuilt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.threadSleep;
import static cn.classfun.droidvm.ui.setup.SetupActivity.CHECK_DELAY;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.setup.base.BaseCheckStepFragment;

public final class ExtractStepFragment extends BaseCheckStepFragment {
    private static final String TAG = "ExtractStepFragment";
    private boolean extractNeeded = true;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        addEventListener("rootCheckDone", this::onEvent);
    }

    @Override
    public void onDestroy() {
        removeEventListener("rootCheckDone");
        super.onDestroy();
    }

    private void onEvent(@NonNull String type) {
        if (type.equals("rootCheckDone") && optBoolean("isRoot", false))
            runOnPool(() -> extractNeeded = needsExtractPrebuilt(activity));
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_setup_step_extract, container, false);
    }

    @Override
    protected void runCheck() {
        if (!needsExtractPrebuilt(activity)) {
            Log.d(TAG, "Prebuilt already up to date, skipping");
            showSuccess(R.string.setup_extract_title, R.string.setup_extract_uptodate);
            return;
        }
        showLoading(R.string.setup_extract_title, R.string.setup_extract_desc);
        runOnPool(this::operationThread);
    }

    private void operationThread() {
        threadSleep(CHECK_DELAY);
        boolean success = false;
        String errorMsg = null;
        try {
            extractPrebuilt(activity);
            success = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract prebuilt", e);
            errorMsg = e.getMessage();
        }
        final boolean ok = success;
        final String errDetail = errorMsg;
        activity.runOnUiThread(() -> onOperationDone(ok, errDetail));
    }

    private void onOperationDone(boolean ok, String errDetail) {
        if (!isAdded()) return;
        if (ok) {
            showSuccess(R.string.setup_extract_title, R.string.setup_extract_success);
        } else {
            showError(R.string.setup_extract_title, R.string.setup_extract_fail);
            if (errDetail != null) showDetail(errDetail);
        }
    }

    @Override
    public boolean isHiddenStep() {
        return extractNeeded && !optBoolean("isRoot", false);
    }
}

