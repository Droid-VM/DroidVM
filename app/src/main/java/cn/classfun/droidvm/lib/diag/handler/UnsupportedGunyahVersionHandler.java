package cn.classfun.droidvm.lib.diag.handler;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.diag.LogHelperHandler;

public final class UnsupportedGunyahVersionHandler extends LogHelperHandler {
    @Override
    public boolean match(@NonNull UUID vmId, @NonNull String stream, @NonNull String text) {
        return
            stream.equals("stderr") &&
            text.contains("exiting with error 1: failed to create vm") &&
            text.contains("Invalid argument (os error 22)") &&
            isGunyah();
    }

    @Override
    public void show(@NonNull Context ctx, @NonNull UUID vmId, @NonNull String vmName) {
        showDialog(ctx,
            R.string.nullptr,
            R.string.log_helper_unsupported_gunyah_title,
            R.string.log_helper_unsupported_gunyah_message
        );
    }
}
