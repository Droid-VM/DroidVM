package cn.classfun.droidvm.ui.setup.step;

import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
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
import cn.classfun.droidvm.ui.setup.base.BaseCheckStepFragment;

public final class VirtualizationStepFragment extends BaseCheckStepFragment {
    private static final String TAG = "VirtualizationStepFragment";

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_setup_step_virt, container, false);
    }

    @Override
    protected void runCheck() {
        showLoading(R.string.setup_virt_title, R.string.setup_virt_desc);
        runOnPool(this::operationThread);
    }

    private void operationThread() {
        threadSleep(CHECK_DELAY);
        var kvmExists = shellCheckExists("/dev/kvm");
        var gunyahExists = shellCheckExists("/dev/gunyah");
        boolean success = kvmExists || gunyahExists;
        Log.i(TAG, fmt(
            "kvm exists: %s gunyah exists: %s",
            String.valueOf(kvmExists),
            String.valueOf(gunyahExists)
        ));
        requireActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            if (success) {
                showSuccess(R.string.setup_virt_title, R.string.setup_virt_success);
                showDetail(getString(
                    R.string.setup_virt_detail,
                    String.valueOf(kvmExists),
                    String.valueOf(gunyahExists)
                ));
            } else {
                showError(R.string.setup_virt_title, R.string.setup_virt_fail);
            }
        });
    }

    @Override
    public boolean isHiddenStep() {
        return !optBoolean("isRoot", false);
    }
}
