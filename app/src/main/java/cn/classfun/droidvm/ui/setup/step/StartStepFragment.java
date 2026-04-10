package cn.classfun.droidvm.ui.setup.step;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.setup.base.BaseStepFragment;

public final class StartStepFragment extends BaseStepFragment {
    private TextView[] faqTitles;
    private TextView[] faqBodies;
    private ViewGroup rootLayout;

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_setup_step_start, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rootLayout = (ViewGroup) view;
        faqTitles = new TextView[]{
            view.findViewById(R.id.faq1_title),
            view.findViewById(R.id.faq2_title),
            view.findViewById(R.id.faq3_title),
        };
        faqBodies = new TextView[]{
            view.findViewById(R.id.faq1_body),
            view.findViewById(R.id.faq2_body),
            view.findViewById(R.id.faq3_body),
        };
        for (int i = 0; i < faqTitles.length; i++) {
            final int index = i;
            faqTitles[i].setOnClickListener(v -> toggleFaq(index));
        }
        activity.showFab(R.drawable.ic_arrow_forward, activity::onStepCompleted);
    }

    private void toggleFaq(int index) {
        var expanding = faqBodies[index].getVisibility() != VISIBLE;
        var transition = new AutoTransition();
        transition.setDuration(100);
        TransitionManager.beginDelayedTransition(rootLayout, transition);
        for (int i = 0; i < faqTitles.length; i++) {
            if (i == index && expanding) {
                faqBodies[i].setVisibility(VISIBLE);
                faqTitles[i].setCompoundDrawablesRelativeWithIntrinsicBounds(
                    0, 0, R.drawable.ic_expand_less, 0
                );
            } else {
                faqBodies[i].setVisibility(GONE);
                faqTitles[i].setCompoundDrawablesRelativeWithIntrinsicBounds(
                    0, 0, R.drawable.ic_expand_more, 0
                );
            }
        }
    }
}

