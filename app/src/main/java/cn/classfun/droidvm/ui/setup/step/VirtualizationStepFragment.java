package cn.classfun.droidvm.ui.setup.step;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.threadSleep;
import static cn.classfun.droidvm.ui.setup.SetupActivity.CHECK_DELAY;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Objects;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.ui.setup.SetupActivity;
import cn.classfun.droidvm.ui.setup.base.BaseCheckStepFragment;

public final class VirtualizationStepFragment extends BaseCheckStepFragment {
    private static final String TAG = "VirtualizationStepFragment";

    public VirtualizationStepFragment(SetupActivity activity) {
        this.activity = activity;
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_setup_step_virt, container, false);
    }

    @Override
    protected void runCheck() {
        showLoading(R.string.setup_virt_title, R.string.setup_virt_desc);
        runOnPool(this::operationThread);
    }

    private void operationThread() {
        threadSleep(CHECK_DELAY);
        boolean success = false;
        var ctx = requireContext();
        // Per-hypervisor line with an inline check/cross icon (not an emoji) for
        // its supported state; a Spannable carries the ImageSpans into the detail
        // TextView. Built off the UI thread -- ImageSpan only holds a Drawable, it
        // touches no view -- then handed to showDetail() on the UI thread.
        var detail = new SpannableStringBuilder();
        for (var hyp : VMHypervisor.values()) {
            if (hyp.getDevicePath() == null) continue;
            var supported = hyp.isSupported();
            if (detail.length() > 0) detail.append('\n');
            detail.append(hyp.getDisplayString(ctx)).append(": ");
            appendStatusIcon(detail, ctx, supported);
            Log.i(TAG, fmt(
                "%s: %s",
                hyp.name().toLowerCase(),
                supported
            ));
            if (supported) success = true;
        }
        var finalSuccess = success;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            if (finalSuccess) {
                showSuccess(R.string.setup_virt_title, R.string.setup_virt_success);
            } else {
                showError(R.string.setup_virt_title, R.string.setup_virt_fail);
            }
            showDetail(detail);
        });
    }

    /** Appends a tinted check (supported) or cross (unsupported) icon inline. */
    private static void appendStatusIcon(
        @NonNull SpannableStringBuilder sb, @NonNull Context ctx, boolean supported
    ) {
        @DrawableRes int icon = supported ? R.drawable.ic_check : R.drawable.ic_close;
        @ColorRes int tint = supported ? R.color.status_supported : R.color.status_unsupported;
        Drawable d = Objects.requireNonNull(ContextCompat.getDrawable(ctx, icon)).mutate();
        int size = Math.round(ctx.getResources().getDisplayMetrics().density * 16);
        d.setBounds(0, 0, size, size);
        d.setTint(ContextCompat.getColor(ctx, tint));
        int start = sb.length();
        sb.append(' '); // placeholder glyph the span draws over
        sb.setSpan(new ImageSpan(d, ImageSpan.ALIGN_CENTER),
            start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    @Override
    public boolean isHiddenStep() {
        return !optBoolean("isRoot", false);
    }
}
