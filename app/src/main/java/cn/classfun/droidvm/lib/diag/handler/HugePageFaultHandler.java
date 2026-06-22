package cn.classfun.droidvm.lib.diag.handler;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.diag.LogHelperHandler;

public final class HugePageFaultHandler extends LogHelperHandler {
    @Override
    public boolean match(@NonNull UUID vmId, @NonNull String stream, @NonNull String text) {
        return
            stream.equals("stderr") &&
            text.contains("page fault at 0x") &&
            text.contains("vcpu hit unknown error: Out of memory");
    }

    @Override
    public void show(@NonNull Context ctx, @NonNull UUID vmId, @NonNull String vmName) {
        showDialog(ctx,
            R.string.log_helper_huge_page_fault_url,
            R.string.log_helper_huge_page_fault_title,
            R.string.log_helper_huge_page_fault_message,
            vmName
        );
    }
}
