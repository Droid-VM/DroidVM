package cn.classfun.droidvm.ui.setup.step;

import static android.content.Context.MODE_PRIVATE;
import static cn.classfun.droidvm.ui.SplashActivity.KEY_SETUP_COMPLETE;
import static cn.classfun.droidvm.ui.SplashActivity.PREFS_NAME;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.setup.SetupActivity;
import cn.classfun.droidvm.ui.setup.base.BaseStepFragment;

public final class DoneStepFragment extends BaseStepFragment {
    public DoneStepFragment(SetupActivity activity) {
        this.activity = activity;
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_setup_step_done, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity.showFab(R.drawable.ic_check, () -> {
            var prefs = activity.getSharedPreferences(
                PREFS_NAME, MODE_PRIVATE
            );
            prefs.edit().putBoolean(KEY_SETUP_COMPLETE, true).apply();
            activity.onStepCompleted();
        });
    }
}
