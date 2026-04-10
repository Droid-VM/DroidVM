package cn.classfun.droidvm.ui.update;

import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.BuildConfig;
import cn.classfun.droidvm.lib.api.ApiManager;

public final class VersionCheck {
    private static final String TAG = "VersionCheck";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        @SuppressWarnings("unused")
        void onUpdateAvailable(@NonNull UpdateInfo info);

        @SuppressWarnings("unused")
        default void onNoUpdate() {
        }

        @SuppressWarnings("unused")
        default void onError(@NonNull Exception e) {
        }
    }

    public void check(@NonNull Context ctx, @NonNull Callback callback) {
        runOnPool(() -> {
            try {
                var api = ApiManager.create(ctx);
                var obj = api.fetchApiWithVersion("update_check");
                var info = new UpdateInfo(obj);
                if (info.getVersionCode() > BuildConfig.VERSION_CODE) {
                    mainHandler.post(() -> callback.onUpdateAvailable(info));
                } else {
                    mainHandler.post(callback::onNoUpdate);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to check for updates", e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }
}
