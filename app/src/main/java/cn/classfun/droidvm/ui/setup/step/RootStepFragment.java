package cn.classfun.droidvm.ui.setup.step;

import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
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

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.internal.MainShell;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.setup.base.BaseCheckStepFragment;

public final class RootStepFragment extends BaseCheckStepFragment {
    private static final String TAG = "RootStepFragment";

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_setup_step_root, container, false);
    }

    @Override
    protected void runCheck() {
        showLoading(R.string.setup_root_title, R.string.setup_root_desc);
        runOnPool(this::operationThread);
    }

    private void resetCachedShell() {
        try {
            var field = MainShell.class.getDeclaredField("mainShell");
            field.setAccessible(true);
            var arr = (Shell[]) field.get(null);
            if (arr != null) arr[0] = null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to reset MainShell via reflection", e);
        }
    }

    private void operationThread() {
        threadSleep(CHECK_DELAY);
        resetCachedShell();
        var shell = Shell.getShell();
        if (!shell.isRoot()) try {
            shell.close();
            shell = Shell.getShell();
        } catch (Exception e) {
            Log.w(TAG, "Failed to get root shell", e);
        }
        var isRoot = shell.isRoot();
        var kernel = runList("uname", "-r").getOutString();
        var secontext = runList("id", "-Z").getOutString();
        Log.i(TAG, fmt("Root: %s kernel: %s selinux: %s", isRoot, kernel, secontext));
        putData("isRoot", isRoot);
        triggerEvent("rootCheckDone");
        activity.runOnUiThread(() -> {
            if (!isAdded()) return;
            if (isRoot) {
                showSuccess(R.string.setup_root_title, R.string.setup_root_success);
            } else {
                showError(R.string.setup_root_title, R.string.setup_root_fail);
            }
            showDetail(getString(R.string.setup_root_detail, kernel, secontext));
        });
    }
}
