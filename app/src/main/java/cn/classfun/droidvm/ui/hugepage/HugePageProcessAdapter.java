package cn.classfun.droidvm.ui.hugepage;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.size.SizeUtils;

@SuppressLint("NotifyDataSetChanged")
@SuppressWarnings("ClassEscapesDefinedScope")
public final class HugePageProcessAdapter
    extends RecyclerView.Adapter<HugePageProcessAdapter.Holder> {

    public interface Listener {
        void onKill(@NonNull HugePageProcess proc);

        void onShowStack(@NonNull HugePageProcess proc);

        void onAcquire();

        void onStopAcquire();
    }

    private static final long PAGE_SIZE = 2L * 1024 * 1024;
    private final List<HugePageProcess> items = new ArrayList<>();
    private final Listener listener;
    private boolean acquiring = false;

    public HugePageProcessAdapter(@NonNull Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    /** Toggle the spinner on the Acquire row while a run is in flight. */
    public void setAcquiring(boolean acquiring) {
        if (this.acquiring == acquiring) return;
        this.acquiring = acquiring;
        notifyDataSetChanged();
    }

    public void submit(@NonNull List<HugePageProcess> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).pid;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        var view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_hugepage_process, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        var ctx = holder.itemView.getContext();
        var p = items.get(position);

        holder.icon.setColorFilter(p.color);

        // Usage line: page count (size). Always shown when known.
        if (p.thpKb >= 0) {
            holder.anon.setVisibility(VISIBLE);
            holder.anon.setText(ctx.getString(
                R.string.hugepage_proc_anon,
                p.thpPages(), SizeUtils.formatSize(p.thpKb * 1024)));
        } else {
            holder.anon.setVisibility(GONE);
        }

        if (p.unknown || p.acquire) {
            // Synthetic rows: "unattributed" (no action) and "waiting for
            // acquire" (deficit, with an Acquire button).
            holder.title.setText(p.comm);
            holder.served.setVisibility(GONE);
            holder.stateView.setVisibility(GONE);
            holder.btnStack.setVisibility(GONE);
            holder.btnStack.setOnClickListener(null);
            holder.itemView.setAlpha(1f);
            if (p.acquire) {
                // While a run is in flight show the spinner in place of the
                // button - same look as before, but tapping it stops the acquire.
                holder.progressAcquire.setVisibility(acquiring ? VISIBLE : GONE);
                holder.progressAcquire.setOnClickListener(
                    acquiring ? v -> listener.onStopAcquire() : null);
                holder.btnKill.setVisibility(acquiring ? GONE : VISIBLE);
                holder.btnKill.setImageResource(R.drawable.ic_download);
                holder.btnKill.setColorFilter(MaterialColors.getColor(
                    holder.itemView, androidx.appcompat.R.attr.colorPrimary));
                holder.btnKill.setContentDescription(
                    ctx.getString(R.string.hugepage_proc_acquire));
                holder.btnKill.setOnClickListener(v -> listener.onAcquire());
            } else {
                holder.progressAcquire.setVisibility(GONE);
                holder.progressAcquire.setOnClickListener(null);
                holder.btnKill.setVisibility(GONE);
                holder.btnKill.setOnClickListener(null);
            }
            return;
        }

        holder.progressAcquire.setVisibility(GONE);
        holder.btnKill.setVisibility(VISIBLE);
        // Restore the kill affordance (views are recycled from acquire rows).
        holder.btnKill.setImageResource(R.drawable.ic_close);
        holder.btnKill.setColorFilter(MaterialColors.getColor(
            holder.itemView, androidx.appcompat.R.attr.colorError));
        holder.btnKill.setContentDescription(
            ctx.getString(R.string.hugepage_proc_kill));
        holder.title.setText(ctx.getString(
            R.string.hugepage_proc_title, p.comm, p.pid));

        // Served pages are only known for module-attributed owners; scan-sourced
        // rows (servedPages < 0) hide it.
        if (p.servedPages >= 0) {
            holder.served.setVisibility(VISIBLE);
            holder.served.setText(ctx.getString(
                R.string.hugepage_proc_served,
                p.servedPages, SizeUtils.formatSize(p.servedPages * PAGE_SIZE)));
        } else {
            holder.served.setVisibility(GONE);
        }

        // Kernel state char only (R/S/D/...), shown left of the kill button.
        // D (uninterruptible sleep) highlighted in the error color.
        holder.stateView.setVisibility(VISIBLE);
        holder.stateView.setText(String.valueOf(p.state));
        holder.stateView.setTextColor(MaterialColors.getColor(holder.itemView,
            p.isUninterruptibleSleep()
                ? androidx.appcompat.R.attr.colorError
                : com.google.android.material.R.attr.colorOnSurfaceVariant));

        boolean dead = !p.alive;
        holder.itemView.setAlpha(dead ? 0.45f : 1f);

        // D-state -> show kernel-stack icon
        if (p.isUninterruptibleSleep()) {
            holder.btnStack.setVisibility(VISIBLE);
            holder.btnStack.setOnClickListener(v -> listener.onShowStack(p));
        } else {
            holder.btnStack.setVisibility(GONE);
            holder.btnStack.setOnClickListener(null);
        }

        holder.btnKill.setEnabled(!dead);
        holder.btnKill.setOnClickListener(dead ? null : v -> listener.onKill(p));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView served;
        final TextView anon;
        final TextView stateView;
        final ImageButton btnStack;
        final ImageButton btnKill;
        final View progressAcquire;

        Holder(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.iv_proc_icon);
            title = v.findViewById(R.id.tv_proc_title);
            served = v.findViewById(R.id.tv_proc_served);
            anon = v.findViewById(R.id.tv_proc_anon);
            stateView = v.findViewById(R.id.tv_proc_state);
            btnStack = v.findViewById(R.id.btn_proc_stack);
            btnKill = v.findViewById(R.id.btn_proc_kill);
            progressAcquire = v.findViewById(R.id.progress_proc_acquire);
        }
    }
}
