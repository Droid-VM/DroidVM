package cn.classfun.droidvm.lib.ui;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;

public class BackAskHelper {
    private final AppCompatActivity activity;
    private final OnBackPressedCallback callback;

    public BackAskHelper(@NonNull AppCompatActivity activity) {
        this.activity = activity;
        this.callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                showConfirmDialog();
            }
        };
        activity.getOnBackPressedDispatcher().addCallback(activity, callback);
        try {
            MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
            toolbar.setNavigationOnClickListener(v ->
                activity.getOnBackPressedDispatcher().onBackPressed());
        } catch (Exception ignored) {
        }
    }

    private void showConfirmDialog() {
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.back_ask_title)
            .setMessage(R.string.back_ask_message)
            .setPositiveButton(R.string.back_ask_discard, (d, w) -> {
                callback.setEnabled(false);
                activity.getOnBackPressedDispatcher().onBackPressed();
            })
            .setNegativeButton(R.string.back_ask_keep_editing, null)
            .show();
    }

    public void setEnabled(boolean enabled) {
        callback.setEnabled(enabled);
    }

    public void finish() {
        callback.setEnabled(false);
        activity.finish();
    }
}
