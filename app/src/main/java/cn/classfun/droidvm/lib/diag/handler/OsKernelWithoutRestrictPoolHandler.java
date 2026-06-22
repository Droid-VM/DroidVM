package cn.classfun.droidvm.lib.diag.handler;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.diag.LogHelperHandler;

public final class OsKernelWithoutRestrictPoolHandler extends LogHelperHandler {
    @Override
    public boolean match(@NonNull UUID vmId, @NonNull String stream, @NonNull String text) {
        return
            stream.equals("stderr") &&
            text.contains("host access to lent memory region at 0x");
    }

    @Override
    public void show(@NonNull Context ctx, @NonNull UUID vmId, @NonNull String vmName) {
        showDialog(ctx,
            R.string.log_helper_no_restrict_pool_url,
            R.string.log_helper_no_restrict_pool_title,
            R.string.log_helper_no_restrict_pool_message,
            vmName
        );
    }
}
