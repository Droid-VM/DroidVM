package cn.classfun.droidvm.lib.ui;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public final class SimpleAdapterDataObserver extends RecyclerView.AdapterDataObserver {
    @NonNull
    private final Runnable run;

    public SimpleAdapterDataObserver(@NonNull Runnable run) {
        this.run = run;
    }

    @Override
    public void onChanged() {
        run.run();
    }

    @Override
    public void onItemRangeInserted(int start, int count) {
        run.run();
    }

    @Override
    public void onItemRangeRemoved(int start, int count) {
        run.run();
    }
}
