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

        /** @param mode acquire algorithm: 2 = migrate + system reclaim,
         *              3 = migrate + per-block evict to zram. */
        void onAcquire(int mode);

        /** Short-press: confirm via the info dialog (unless the user opted out), then run. */
        void onAcquireConfirm(int mode);

        /** Long-press: show the what-does-this-mode-do dialog (with a Run choice). */
        void onAcquireInfo(int mode);

        void onStopAcquire();
    }

    private static final long PAGE_SIZE = 2L * 1024 * 1024;
    private final List<HugePageProcess> items = new ArrayList<>();
    private final Listener listener;
    private boolean acquiring = false;
    private int acquireMode = -1;   // running v (1/2/3); -1 = unknown (old module)

    public HugePageProcessAdapter(@NonNull Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    /**
     * Reflect the acquire state on the row: idle = three buttons, running with a
     * known mode = that slot spins + the other two greyed out, running with an
     * unknown mode (-1, old module) = all three spin.
     */
    public void setAcquireState(boolean acquiring, int mode) {
        if (!acquiring) mode = -1;
        if (this.acquiring == acquiring && this.acquireMode == mode) return;
        this.acquiring = acquiring;
        this.acquireMode = mode;
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
            // Synthetic rows: "available"/"CMA"/"unattributed" (no action) and
            // "waiting for acquire" (deficit, with an Acquire button). The
            // served line doubles as their free-text detail (breakdowns).
            holder.title.setText(p.comm);
            if (p.detail != null) {
                holder.served.setVisibility(VISIBLE);
                holder.served.setText(p.detail);
            } else {
                holder.served.setVisibility(GONE);
            }
            holder.stateView.setVisibility(GONE);
            holder.btnStack.setVisibility(GONE);
            holder.btnStack.setOnClickListener(null);
            holder.itemView.setAlpha(1f);
            holder.btnKill.setVisibility(GONE);
            holder.btnKill.setOnClickListener(null);
            // Usually the deficit row; the CMA row when that one is hidden.
            if (p.acquireSlots) {
                bindAcquireSlot(holder.btnAcquireV1, holder.progressAcquireV1, 1);
                bindAcquireSlot(holder.btnAcquireV2, holder.progressAcquireV2, 2);
                bindAcquireSlot(holder.btnAcquireV3, holder.progressAcquireV3, 3);
            } else {
                hideAcquireSlots(holder);
            }
            return;
        }

        hideAcquireSlots(holder);
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

    /** One acquire slot: spins iff running AND (mode unknown or this mode); when a
     *  run is in flight the non-running slots are visible-but-disabled buttons. */
    private void bindAcquireSlot(View btn, View spinner, int mode) {
        boolean spin = acquiring && (acquireMode < 0 || acquireMode == mode);
        btn.setVisibility(spin ? GONE : VISIBLE);
        // Left enabled so long-press always opens the info dialog; short-press is
        // gated on !acquiring and the icon greys via alpha while a run is in flight.
        btn.setAlpha(acquiring ? 0.38f : 1f);
        btn.setOnClickListener(v -> { if (!acquiring) listener.onAcquireConfirm(mode); });
        btn.setOnLongClickListener(v -> { listener.onAcquireInfo(mode); return true; });
        spinner.setVisibility(spin ? VISIBLE : GONE);
        spinner.setOnClickListener(spin ? v -> listener.onStopAcquire() : null);
    }

    /** Hide all six acquire views and reset the buttons' enabled state (recycled). */
    private static void hideAcquireSlots(Holder h) {
        View[] all = {h.btnAcquireV1, h.btnAcquireV2, h.btnAcquireV3,
            h.progressAcquireV1, h.progressAcquireV2, h.progressAcquireV3};
        for (View v : all) {
            v.setVisibility(GONE);
            v.setOnClickListener(null);
        }
        for (View b : new View[]{h.btnAcquireV1, h.btnAcquireV2, h.btnAcquireV3}) {
            b.setOnLongClickListener(null);
            b.setAlpha(1f);
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView served;
        final TextView anon;
        final TextView stateView;
        final ImageButton btnStack;
        final ImageButton btnKill;
        final View btnAcquireV1;
        final View btnAcquireV2;
        final View btnAcquireV3;
        final View progressAcquireV1;
        final View progressAcquireV2;
        final View progressAcquireV3;

        Holder(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.iv_proc_icon);
            title = v.findViewById(R.id.tv_proc_title);
            served = v.findViewById(R.id.tv_proc_served);
            anon = v.findViewById(R.id.tv_proc_anon);
            stateView = v.findViewById(R.id.tv_proc_state);
            btnStack = v.findViewById(R.id.btn_proc_stack);
            btnKill = v.findViewById(R.id.btn_proc_kill);
            btnAcquireV1 = v.findViewById(R.id.btn_acquire_v1);
            btnAcquireV2 = v.findViewById(R.id.btn_acquire_v2);
            btnAcquireV3 = v.findViewById(R.id.btn_acquire_v3);
            progressAcquireV1 = v.findViewById(R.id.progress_acquire_v1);
            progressAcquireV2 = v.findViewById(R.id.progress_acquire_v2);
            progressAcquireV3 = v.findViewById(R.id.progress_acquire_v3);
        }
    }
}
