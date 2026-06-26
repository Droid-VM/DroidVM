package cn.classfun.droidvm.lib.diag;

import static android.content.Intent.ACTION_VIEW;

import android.content.Context;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.Duration;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.utils.FileUtils;

public abstract class LogHelperHandler {
    private static boolean hasGunyahInfo = false, isGunyah = false;
    public boolean match(@NonNull UUID vmId, @NonNull String stream, @NonNull String text) {
        return false;
    }

    public boolean isOnce() {
        return true;
    }

    public Duration getShowDelay() {
        return Duration.ofSeconds(2);
    }

    public abstract void show(@NonNull Context ctx, @NonNull UUID vmId, @NonNull String vmName);

    protected static boolean isGunyah() {
        if (!hasGunyahInfo) {
            isGunyah = FileUtils.shellCheckExists("/dev/gunyah");
            hasGunyahInfo = true;
        }
        return isGunyah;
    }

    protected static void showDialog(
        @NonNull Context ctx,
        @StringRes int urlId,
        @StringRes int titleId,
        @StringRes int messageId,
        Object... args
    ) {
        var mab = new MaterialAlertDialogBuilder(ctx);
        mab.setTitle(titleId);
        mab.setMessage(ctx.getString(messageId, args));
        mab.setPositiveButton(android.R.string.ok, null);
        var url = ctx.getString(urlId);
        OnClickListener cb = (d, w) -> ctx.startActivity(new Intent(ACTION_VIEW, Uri.parse(url)));
        if (!url.isEmpty()) mab.setNeutralButton(R.string.log_helper_open_url, cb);
        mab.show();
    }
}
