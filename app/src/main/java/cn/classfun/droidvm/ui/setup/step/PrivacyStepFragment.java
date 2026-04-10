package cn.classfun.droidvm.ui.setup.step;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.api.Privacy;
import cn.classfun.droidvm.ui.setup.base.BaseStepFragment;

public final class PrivacyStepFragment extends BaseStepFragment {

    @Override
    public boolean isHiddenStep() {
        return !Privacy.isNeedAskPrivacy(activity);
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_setup_step_privacy, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity.hideFab();
        TextView tvContent = view.findViewById(R.id.tv_policy_content);
        MaterialButton btnDecline = view.findViewById(R.id.btn_decline);
        MaterialButton btnAccept = view.findViewById(R.id.btn_accept);
        try {
            var content = Privacy.getPolicyContent(activity);
            tvContent.setText(content);
        } catch (Exception e) {
            tvContent.setText(e.getMessage());
        }
        btnDecline.setOnClickListener(v -> showDeclineConfirm());
        btnAccept.setOnClickListener(v -> {
            Privacy.setPolicyAgreed(activity, true);
            activity.onStepCompleted();
        });
    }

    private void showDeclineConfirm() {
        new MaterialAlertDialogBuilder(activity)
            .setIcon(R.drawable.ic_large_warning)
            .setTitle(R.string.privacy_decline_title)
            .setMessage(R.string.privacy_decline_message)
            .setPositiveButton(R.string.privacy_decline_confirm, (d, w) -> {
                Privacy.setPolicyAgreed(activity, false);
                activity.onStepCompleted();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
