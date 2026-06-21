package cn.classfun.droidvm.lib.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Reinstates the legacy {@code adjustResize} behaviour under edge-to-edge.
 *
 * <p>From {@code targetSdk} 35 the app always draws edge-to-edge, and the
 * framework no longer shrinks the window for the soft keyboard; instead it
 * reports the keyboard height as an {@link WindowInsetsCompat.Type#ime() ime}
 * inset that the app must consume. {@code android:fitsSystemWindows} only
 * applies the system bar insets, so without this the keyboard covers whatever
 * the user is typing into.
 *
 * <p>This applier pads the bottom of every activity's content view by the IME
 * inset, lifting the content (and any scroll container within it) above the
 * keyboard for every activity uniformly. Activities that handle the keyboard
 * themselves opt out via {@link ImeInsetsExempt}.
 */
public final class ImeInsetsApplier implements Application.ActivityLifecycleCallbacks {

    private static void apply(Activity activity) {
        if (activity instanceof ImeInsetsExempt) return;
        final View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        final int basePadding = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            final Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(
                v.getPaddingLeft(),
                v.getPaddingTop(),
                v.getPaddingRight(),
                basePadding + ime.bottom
            );
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    @Override
    public void onActivityPostCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        apply(activity);
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }
}
