package cn.classfun.droidvm.ui.setup.step;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.threadSleep;
import static cn.classfun.droidvm.ui.setup.SetupActivity.CHECK_DELAY;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.data.QcomChipName;
import cn.classfun.droidvm.lib.data.QcomGunyahSupports;
import cn.classfun.droidvm.ui.setup.base.BaseCheckStepFragment;

public final class SocStepFragment extends BaseCheckStepFragment {
    private static final String TAG = "SocStepFragment";

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_setup_step_soc, container, false);
    }

    @Override
    protected void runCheck() {
        showLoading(R.string.setup_soc_title, R.string.setup_soc_desc);
        runOnPool(this::operationThread);
    }

    private void operationThread() {
        threadSleep(CHECK_DELAY);
        var socModel = QcomChipName.getCurrentSoC();
        Log.i(TAG, fmt("SoC model: %s", socModel));
        var gunyah = new QcomGunyahSupports(activity);
        var chipName = new QcomChipName(activity);
        var supported = gunyah.isGunyahSupported(socModel);
        var friendlyName = chipName.lookupChipName(socModel);
        activity.runOnUiThread(() -> {
            if (!isAdded()) return;
            if (supported) {
                showSuccess(R.string.setup_soc_title, R.string.setup_soc_success);
                tvStepDesc.setText(getString(R.string.setup_soc_success, socModel));
            } else {
                showError(R.string.setup_soc_title, R.string.setup_soc_fail);
                tvStepDesc.setText(getString(R.string.setup_soc_fail, socModel));
            }
            showDetail(getString(R.string.setup_soc_detail, friendlyName));
        });
    }
}
