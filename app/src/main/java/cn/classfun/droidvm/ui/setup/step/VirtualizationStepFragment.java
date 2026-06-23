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
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.ui.setup.SetupActivity;
import cn.classfun.droidvm.ui.setup.base.BaseCheckStepFragment;

public final class VirtualizationStepFragment extends BaseCheckStepFragment {
    private static final String TAG = "VirtualizationStepFragment";

    public VirtualizationStepFragment(SetupActivity activity) {
        this.activity = activity;
    }

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
        boolean success = false;
        var ctx = requireContext();
        var displayString = new StringBuilder();
        for (var hyp : VMHypervisor.values()) {
            if (hyp.getDevicePath() == null) continue;
            var supported = hyp.isSupported();
            displayString.append(fmt(
                "%s: %s\n",
                hyp.getDisplayString(ctx),
                supported ? "✅" : "❌"
            ));
            Log.i(TAG, fmt(
                "%s: %s",
                hyp.name().toLowerCase(),
                supported
            ));
            if (supported) success = true;
        }
        var finalSuccess = success;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            if (finalSuccess) {
                showSuccess(R.string.setup_virt_title, R.string.setup_virt_success);
                showDetail(displayString.toString());
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
