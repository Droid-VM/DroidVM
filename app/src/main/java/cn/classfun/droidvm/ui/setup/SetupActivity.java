// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.setup;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.animation.OvershootInterpolator;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.main.MainActivity;
import cn.classfun.droidvm.ui.setup.base.BaseStepFragment;
import cn.classfun.droidvm.ui.setup.step.DoneStepFragment;
import cn.classfun.droidvm.ui.setup.step.ExtractStepFragment;
import cn.classfun.droidvm.ui.setup.step.KernelModuleStepFragment;
import cn.classfun.droidvm.ui.setup.step.PrivacyStepFragment;
import cn.classfun.droidvm.ui.setup.step.RootStepFragment;
import cn.classfun.droidvm.ui.setup.step.SocStepFragment;
import cn.classfun.droidvm.ui.setup.step.StartStepFragment;
import cn.classfun.droidvm.ui.setup.step.StorageStepFragment;
import cn.classfun.droidvm.ui.setup.step.VirtualizationStepFragment;

public final class SetupActivity extends AppCompatActivity {
    public static final String EXTRA_TARGET_STEP = "extra_target_step";
    private BaseStepFragment[] ALL_STEPS;
    private List<BaseStepFragment> steps = null;
    private int currentStep = 0;
    private FloatingActionButton fab;
    public final static int CHECK_DELAY = 300; // ms
    public final Map<String, Object> sharedData = new HashMap<>();
    public final Map<String, Consumer<String>> sharedEvent = new HashMap<>();

    @NonNull
    public static Intent createSingleStepIntent(
        @NonNull Context context,
        @NonNull List<Class<? extends BaseStepFragment>> stepClass
    ) {
        var steps = new StringBuilder();
        for (var step : stepClass) {
            if (steps.length() > 0) steps.append(",");
            steps.append(step.getName());
        }
        var intent = new Intent(context, SetupActivity.class);
        intent.putExtra(EXTRA_TARGET_STEP, steps.toString());
        return intent;
    }

    @NonNull
    public static Intent createSingleStepIntent(
        @NonNull Context context,
        @NonNull Class<? extends BaseStepFragment> stepClass
    ) {
        return createSingleStepIntent(context, List.of(stepClass));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        fab = findViewById(R.id.fab_action);
        ALL_STEPS = new BaseStepFragment[]{
            new StartStepFragment(this),
            new RootStepFragment(this),
            new SocStepFragment(this),
            new VirtualizationStepFragment(this),
            new StorageStepFragment(this),
            new PrivacyStepFragment(this),
            new ExtractStepFragment(this),
            // After extract: the module list reads the .ko files extract just put in place.
            new KernelModuleStepFragment(this),
            new DoneStepFragment(this),
        };
        var targetStep = getIntent().getStringExtra(EXTRA_TARGET_STEP);
        if (targetStep != null) {
            var lst = new ArrayList<BaseStepFragment>();
            for (var s : targetStep.split(",")) {
                var target = findStepByClassName(s);
                if (target != null) lst.add(target);
            }
            if (!lst.isEmpty())
                steps = lst;
        }
        if (steps == null) steps = List.of(ALL_STEPS);
        if (savedInstanceState == null)
            showStep(false, true);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentStep > 0) {
                    onStepBack();
                } else {
                    finish();
                }
            }
        });
    }

    @Nullable
    private BaseStepFragment findStepByClassName(@NonNull String className) {
        for (var f : ALL_STEPS)
            if (f.getClass().getName().equals(className))
                return f;
        return null;
    }

    /**
     * Advance to the next step that wants to be shown, or leave the wizard when none does.
     *
     * <p>The bounds check has to come before the {@code get}, and "everything after this is
     * hidden" has to end the wizard rather than fall off the end: a step can hide itself for
     * reasons that hold on a whole class of devices (the kernel-module page does, on a phone
     * none of the modules were written for), so a run of hidden steps at the tail is a normal
     * outcome, not an impossible one.
     */
    public void onStepCompleted() {
        int next = currentStep + 1;
        while (next < steps.size() && steps.get(next).isHiddenStep()) next++;
        if (next >= steps.size()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        currentStep = next;
        hideFab();
        showStep(true, true);
    }

    /** Back to the previous shown step; a hidden one is skipped here too, not landed on. */
    public void onStepBack() {
        int prev = currentStep - 1;
        while (prev >= 0 && steps.get(prev).isHiddenStep()) prev--;
        if (prev < 0) return;
        currentStep = prev;
        hideFab();
        showStep(true, false);
    }

    public void showFab(int iconRes, Runnable action) {
        fab.animate().cancel();
        fab.setImageResource(iconRes);
        fab.setOnClickListener(v -> action.run());
        fab.setVisibility(VISIBLE);
        fab.setScaleX(0f);
        fab.setScaleY(0f);
        fab.setAlpha(0f);
        fab.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(250)
            .setInterpolator(new OvershootInterpolator())
            .start();
    }

    public void hideFab() {
        fab.animate().cancel();
        if (fab.getVisibility() == VISIBLE) {
            fab.animate()
                .scaleX(0f)
                .scaleY(0f)
                .alpha(0f)
                .setDuration(200)
                .withEndAction(() -> fab.setVisibility(GONE))
                .start();
        }
    }

    private void showStep(boolean animate, boolean forward) {
        var tx = getSupportFragmentManager().beginTransaction();
        if (animate) {
            if (forward) {
                tx.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left);
            } else {
                tx.setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right);
            }
        }
        tx.replace(R.id.setup_container, steps.get(currentStep));
        tx.commit();
    }
}

