// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.tree;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.radiobutton.MaterialRadioButton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;

/**
 * Compact rendering of an overlay-relation tree, used inside dialogs (switch-branch, disk
 * picker) and info views. Feed it {@link DiskTree} roots; rows indent by depth, parents get a
 * working collapse chevron (in-memory state - the main list's persisted collapse is separate),
 * locked disks show the padlock, broken links a warning line. Optional single-selection with a
 * radio column and an optional per-node menu button.
 */
public final class DiskTreeView extends RecyclerView {
    public interface Listener {
        /** Row tapped in selectable mode (already reflected in the UI). */
        default void onNodeSelected(@NonNull DiskTree.Node node) {
        }

        /** Node menu button tapped. */
        default void onNodeMenu(@NonNull View anchor, @NonNull DiskTree.Node node) {
        }
    }

    private static final int INDENT_DP = 16;
    private static final int MAX_INDENT_STEPS = 4;

    private final Adapter adapter = new Adapter();
    private List<DiskTree.Node> roots = new ArrayList<>();
    private final List<DiskTree.Node> flat = new ArrayList<>();
    private final Set<UUID> collapsed = new HashSet<>();
    private boolean selectable = false;
    private boolean showNodeMenu = false;
    @Nullable
    private UUID currentId;
    @Nullable
    private UUID selectedId;
    @Nullable
    private Listener listener;

    public DiskTreeView(@NonNull Context context) {
        super(context);
        init();
    }

    public DiskTreeView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DiskTreeView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        setLayoutManager(new LinearLayoutManager(getContext()));
        setAdapter(adapter);
    }

    public void configure(
        boolean selectable,
        boolean showNodeMenu,
        @Nullable UUID currentId,
        @Nullable Listener listener
    ) {
        this.selectable = selectable;
        this.showNodeMenu = showNodeMenu;
        this.currentId = currentId;
        this.listener = listener;
        if (selectable) this.selectedId = currentId;
    }

    /** Replace the tree contents (fully expanded) and repaint. */
    public void setRoots(@NonNull List<DiskTree.Node> roots) {
        this.roots = roots;
        collapsed.clear();
        rebuild();
    }

    @Nullable
    public UUID getSelectedId() {
        return selectedId;
    }

    @Nullable
    public DiskTree.Node getSelectedNode() {
        if (selectedId == null) return null;
        for (var n : flat)
            if (n.id().equals(selectedId)) return n;
        return null;
    }

    @SuppressWarnings("NotifyDataSetChanged")
    private void rebuild() {
        flat.clear();
        flat.addAll(DiskTree.flatten(roots, collapsed));
        adapter.notifyDataSetChanged();
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            var v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_disk_tree_row, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            h.bind(flat.get(position));
        }

        @Override
        public int getItemCount() {
            return flat.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        private final View root;
        private final ImageButton chevron;
        private final ImageView icon;
        private final TextView name;
        private final TextView sub;
        private final ImageView lock;
        private final MaterialRadioButton radio;
        private final ImageButton menu;

        Holder(@NonNull View v) {
            super(v);
            root = v.findViewById(R.id.tree_row_root);
            chevron = v.findViewById(R.id.tree_chevron);
            icon = v.findViewById(R.id.tree_icon);
            name = v.findViewById(R.id.tree_name);
            sub = v.findViewById(R.id.tree_sub);
            lock = v.findViewById(R.id.tree_lock);
            radio = v.findViewById(R.id.tree_radio);
            menu = v.findViewById(R.id.tree_menu);
        }

        void bind(@NonNull DiskTree.Node node) {
            var ctx = root.getContext();
            float density = ctx.getResources().getDisplayMetrics().density;
            int steps = Math.min(node.depth, MAX_INDENT_STEPS);
            root.setPaddingRelative(
                Math.round(steps * INDENT_DP * density),
                root.getPaddingTop(), root.getPaddingEnd(), root.getPaddingBottom());

            name.setText(node.config.getName());
            icon.setImageResource(node.config.getFormat() == DiskFormat.ISO
                ? R.drawable.ic_cdrom : R.drawable.ic_nav_disk);

            boolean isCollapsed = collapsed.contains(node.id());
            chevron.setVisibility(node.hasChildren() ? VISIBLE : View.INVISIBLE);
            chevron.setRotation(isCollapsed ? -90 : 0);
            chevron.setOnClickListener(v -> {
                if (!collapsed.remove(node.id())) collapsed.add(node.id());
                rebuild();
            });

            lock.setVisibility(node.hasChildren() ? VISIBLE : GONE);

            var subText = new StringBuilder();
            if (node.brokenParent)
                subText.append(ctx.getString(R.string.disk_tree_broken_parent));
            if (isCollapsed && node.hasChildren()) {
                if (subText.length() > 0) subText.append("  ");
                subText.append(fmt("+%d", node.countDescendants()));
            }
            if (currentId != null && currentId.equals(node.id())) {
                if (subText.length() > 0) subText.append("  ");
                subText.append(ctx.getString(R.string.disk_tree_current_attached));
            }
            sub.setVisibility(subText.length() > 0 ? VISIBLE : GONE);
            sub.setText(subText);

            radio.setVisibility(selectable ? VISIBLE : GONE);
            radio.setChecked(selectable && node.id().equals(selectedId));

            menu.setVisibility(showNodeMenu ? VISIBLE : GONE);
            menu.setOnClickListener(v -> {
                if (listener != null) listener.onNodeMenu(v, node);
            });

            root.setOnClickListener(v -> {
                if (!selectable) return;
                selectedId = node.id();
                adapter.notifyDataSetChanged();
                if (listener != null) listener.onNodeSelected(node);
            });
        }
    }
}
