package cn.classfun.droidvm.lib.ui;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

public interface UIContext {
    boolean isAlive();

    @NonNull
    Context getContext();

    @NonNull
    static UIContext fromFragment(@NonNull Fragment fragment) {
        return new UIContext() {
            @Override
            public boolean isAlive() {
                return fragment.isAdded();
            }

            @NonNull
            @Override
            public Context getContext() {
                return fragment.requireContext();
            }
        };
    }
    @NonNull
    static UIContext fromActivity(@NonNull Activity act) {
        return new UIContext() {
            @Override
            public boolean isAlive() {
                return !act.isFinishing();
            }

            @NonNull
            @Override
            public Context getContext() {
                return act;
            }
        };
    }
}
