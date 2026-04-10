package cn.classfun.droidvm.ui.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.BuildConfig;
import cn.classfun.droidvm.R;

public final class UpdateDialog {
    private final Context context;
    private final UpdateInfo info;

    public UpdateDialog(@NonNull Context context, @NonNull UpdateInfo info) {
        this.context = context;
        this.info = info;
    }

    public void show() {
        var sb = new StringBuilder();
        sb.append(context.getString(R.string.update_current_version, BuildConfig.VERSION_NAME));
        sb.append("\n");
        sb.append(context.getString(R.string.update_new_version, info.getVersion()));
        sb.append("\n\n");
        var changelog = info.getChangelog();
        if (!changelog.isEmpty()) {
            sb.append(context.getString(R.string.update_changelog));
            sb.append("\n");
            sb.append(changelog);
        }
        var builder = new MaterialAlertDialogBuilder(context);
        builder.setTitle(R.string.update_title);
        builder.setMessage(sb.toString());
        builder.setNegativeButton(R.string.update_cancel, null);
        if (!info.getPageUrl().isEmpty())
            builder.setNeutralButton(R.string.update_browser, (d, w) -> openBrowser());
        if (!info.getDownloadUrl().isEmpty())
            builder.setPositiveButton(R.string.update_download, (d, w) -> openDownload());
        builder.show();
    }

    private void goUrl(String url) {
        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void openDownload() {
        var url = info.getPageUrl();
        if (url.isEmpty()) url = info.getDownloadUrl();
        if (!url.isEmpty()) goUrl(url);
    }

    private void openBrowser() {
        var url = info.getDownloadUrl();
        if (url.isEmpty()) url = info.getPageUrl();
        if (!url.isEmpty()) goUrl(url);
    }
}
