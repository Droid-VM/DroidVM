package cn.classfun.droidvm.lib.diag.handler;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.diag.LogHelperHandler;

public final class UnsupportedSandboxHandler extends LogHelperHandler {
    @Override
    public boolean match(@NonNull UUID vmId, @NonNull String stream, @NonNull String text) {
        return
            stream.equals("stderr") &&
            text.contains("\"/var/empty\" is not a directory, cannot create jail") &&
            isGunyah();
    }

    @Override
    public void show(@NonNull Context ctx, @NonNull UUID vmId, @NonNull String vmName) {
        showDialog(ctx,
            R.string.nullptr,
            R.string.log_helper_unsupported_sandbox_title,
            R.string.log_helper_unsupported_sandbox_message
        );
    }
}
