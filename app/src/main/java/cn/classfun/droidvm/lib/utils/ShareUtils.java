package cn.classfun.droidvm.lib.utils;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Helpers for exporting in-app text to the system share sheet (ACTION_SEND). */
public final class ShareUtils {
    private static final String TAG = "ShareUtils";
    private static final String SHARE_DIR = "shared_logs";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ShareUtils() {
    }

    /**
     * Write {@code content} to a temp file under the cache dir and launch the system
     * share sheet with it as a {@code text/plain} attachment. File write runs off the
     * main thread; the chooser is launched back on the main thread. {@code onError} is
     * invoked on the main thread with a message when sharing fails.
     */
    public static void shareTextAsFile(
        Context context,
        String filename,
        String content,
        String chooserTitle,
        OnError onError
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
                var authority = fmt("%s.fileprovider", appContext.getPackageName());
                var uri = FileProvider.getUriForFile(appContext, authority, file);
                MAIN.post(() -> launchChooser(context, uri, chooserTitle, onError));
            } catch (Exception e) {
                Log.e(TAG, "Failed to prepare shared file", e);
                MAIN.post(() -> {
                    if (onError != null) onError.onError(e.getMessage());
                });
            }
        });
    }

    private static void launchChooser(Context context, Uri uri, String title, OnError onError) {
        try {
            var send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
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

    /** Drop previously exported files so the cache dir does not grow unbounded. */
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
