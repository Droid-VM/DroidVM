package cn.classfun.droidvm.lib.diag.handler;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.diag.LogHelperHandler;

public final class BadSM8650HostKernelHandler extends LogHelperHandler {
    @Override
    public boolean match(@NonNull UUID vmId, @NonNull String stream, @NonNull String text) {
        return
            stream.equals("stderr") &&
            text.contains("exiting with error 1: the architecture failed to build the vm") &&
            text.contains("failed to initialize virtual machine No such device (os error 19)") &&
            isGunyah();
    }

    @Override
    public void show(@NonNull Context ctx, @NonNull UUID vmId, @NonNull String vmName) {
        showDialog(ctx,
            R.string.log_helper_bad_sm8650_kernel_url,
            R.string.log_helper_bad_sm8650_kernel_title,
            R.string.log_helper_bad_sm8650_kernel_message
        );
    }
}
