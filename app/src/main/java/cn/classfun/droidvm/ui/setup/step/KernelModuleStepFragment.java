// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.setup.step;

import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.main.settings.KernelModuleListController;
import cn.classfun.droidvm.ui.main.settings.KernelModuleMatch;
import cn.classfun.droidvm.ui.setup.SetupActivity;
import cn.classfun.droidvm.ui.setup.base.BaseStepFragment;

/**
 * Setup step reminding the user the host kernel modules must be loaded: shows the same list the
 * settings' Kernel Module dialog shows ({@link KernelModuleListController}), right after the
 * extract step put the .ko files in place. Loading here is optional (each card explains itself),
 * so the continue FAB is always available.
 *
 * <p>Skipped entirely on a device none of the modules were written for -- every module shipped so
 * far is Qualcomm/Gunyah work, and on a MediaTek or Tensor phone this page would be an empty list
 * telling the user to load it. Settings still opens the same list there; it just shows nothing.
 */
public final class KernelModuleStepFragment extends BaseStepFragment {
    /**
     * Whether anything applies here. Answered off the main thread when the root check lands (the
     * same signal the extract step waits for), because {@link #isHiddenStep()} is asked during a
     * step transition and cannot go and find out then. Defaults to showing the page: an answer
     * that never arrived is not evidence that the page is useless.
     */
    private volatile boolean applicable = true;

    public KernelModuleStepFragment(SetupActivity activity) {
        this.activity = activity;
        // Registered here, not in onAttach: a step is attached only once it is shown, and the
        // question "should this step be shown at all" is settled several steps earlier. Its own
        // slot key, because the event map is keyed by slot and another step already listens for
        // this event -- reusing the key would silently unregister that one.
        addEventListener("rootCheckDone.kmod", this::onEvent);
    }

    @Override
    public void onDestroy() {
        removeEventListener("rootCheckDone.kmod");
        super.onDestroy();
    }

    private void onEvent(@NonNull String type) {
        if (!type.equals("rootCheckDone")) return;
        var ctx = activity.getApplicationContext();
        runOnPool(() -> applicable = KernelModuleMatch.anyApplicable(ctx));
    }

    @Override
    public boolean isHiddenStep() {
        return !applicable;
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_setup_step_kmod, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView desc = view.findViewById(R.id.kmod_desc);
        desc.setText(Html.fromHtml(getString(R.string.setup_kmod_desc),
            Html.FROM_HTML_MODE_COMPACT));
        desc.setMovementMethod(LinkMovementMethod.getInstance());
        new KernelModuleListController(requireContext(), view).refresh();
        activity.showFab(R.drawable.ic_arrow_forward, activity::onStepCompleted);
    }
}
