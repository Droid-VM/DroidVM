// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.setup.base;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;

public abstract class BaseCheckStepFragment extends BaseStepFragment {
    protected TextView tvStepTitle;
    protected TextView tvStepDesc;
    protected TextView tvStepDetail;
    protected ProgressBar progressBar;
    private boolean skipConfirmed = false;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvStepTitle = view.findViewById(R.id.tv_step_title);
        tvStepDesc = view.findViewById(R.id.tv_step_desc);
        tvStepDetail = view.findViewById(R.id.tv_step_detail);
        progressBar = view.findViewById(R.id.progress_bar);
        runCheck();
    }

    /**
     * onViewCreated has already run the check by the time the first onResume arrives, and every
     * runCheck() here starts a worker thread. Re-running it immediately means two workers doing
     * the same job: harmless for the read-only steps, but the extract step writes files, and two
     * of those racing produced "extraction failed" followed by "extraction succeeded" -- the
     * loser tripping over the directories the winner was creating.
     *
     * <p>Later resumes still re-check, which is the point of doing it here at all: the user
     * leaves to grant root or a permission and comes back expecting the step to notice.
     */
    private boolean resumedBefore;

    @Override
    public void onResume() {
        super.onResume();
        if (!resumedBefore) {
            resumedBefore = true;
            return;
        }
        runCheck();
    }

    protected abstract void runCheck();

    @SuppressWarnings("SameReturnValue")
    protected boolean isSkippableStep() {
        return true;
    }

    protected void showLoading(int titleRes, int descRes) {
        tvStepTitle.setText(titleRes);
        tvStepDesc.setText(descRes);
        tvStepDetail.setVisibility(GONE);
        progressBar.setVisibility(VISIBLE);
        activity.hideFab();
    }

    protected void showSuccess(int titleRes, int descRes) {
        tvStepTitle.setText(titleRes);
        tvStepDesc.setText(descRes);
        progressBar.setVisibility(INVISIBLE);
        activity.showFab(R.drawable.ic_arrow_forward, activity::onStepCompleted);
    }

    protected void showError(int titleRes, int descRes) {
        tvStepTitle.setText(titleRes);
        tvStepDesc.setText(descRes);
        progressBar.setVisibility(INVISIBLE);
        if (isSkippableStep()) {
            activity.showFab(R.drawable.ic_skip_next, this::requestSkip);
        } else {
            activity.showFab(R.drawable.ic_refresh, this::runCheck);
        }
    }

    private void requestSkip() {
        if (skipConfirmed) {
            activity.onStepCompleted();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
            .setIcon(R.drawable.ic_large_warning)
            .setTitle(R.string.setup_skip_title)
            .setMessage(R.string.setup_skip_message)
            .setPositiveButton(R.string.setup_skip_confirm, (dialog, which) -> {
                skipConfirmed = true;
                activity.onStepCompleted();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    protected void showDetail(CharSequence text) {
        tvStepDetail.setText(text);
        tvStepDetail.setVisibility(VISIBLE);
    }
}

