package cn.classfun.droidvm.lib.utils;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class ShareUtils {
    private static final String TAG = "ShareUtils";
    private static final String SHARE_DIR = "shared";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ShareUtils() {
    }

    public static void shareTextAsFile(
        @NonNull Context context,
        @NonNull String filename,
        @NonNull String content,
        @NonNull String chooserTitle,
        @Nullable OnError onError
    ) {
        var appContext = context.getApplicationContext();
        runOnPool(() -> {
            try {
                var dir = new File(appContext.getCacheDir(), SHARE_DIR);
                if (!dir.exists() && !dir.mkdirs())
                    throw new Exception("Failed to create share dir");
                pruneOldFiles(dir);
                var file = new File(dir, filename);
                try (var os = new FileOutputStream(file)) {
                    os.write(content.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                shareFile(context, file, null, chooserTitle, onError);
            } catch (Exception e) {
                Log.e(TAG, "Failed to prepare shared file", e);
                MAIN.post(() -> {
                    if (onError != null) onError.onError(e.getMessage());
                });
            }
        });
    }

    public static void shareFile(
        @NonNull Context context,
        @NonNull File file,
        @Nullable String mime,
        @NonNull String chooserTitle,
        @Nullable OnError onError
    ) {
        try {
            var appContext = context.getApplicationContext();
            var authority = fmt("%s.fileprovider", appContext.getPackageName());
            var uri = FileProvider.getUriForFile(appContext, authority, file);
            MAIN.post(() -> launchChooser(context, uri, chooserTitle, mime, onError));
        } catch (Exception e) {
            Log.e(TAG, "Failed to create share uri", e);
            MAIN.post(() -> {
                if (onError != null) onError.onError(e.getMessage());
            });
        }
    }

    public static void launchChooser(
        @NonNull Context context,
        @NonNull Uri uri,
        @NonNull String title,
        @Nullable String mime,
        @Nullable OnError onError
    ) {
        if (mime == null) {
            var ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            if (ext != null) mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime == null) mime = "application/octet-stream";
        }
        try {
            var send = new Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            var chooser = Intent.createChooser(send, title);
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(chooser);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch share chooser", e);
            if (onError != null) onError.onError(e.getMessage());
        }
    }

    private static void pruneOldFiles(File dir) {
        var files = dir.listFiles();
        if (files == null) return;
        for (var f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public interface OnError {
        void onError(String message);
    }
}
