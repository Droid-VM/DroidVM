// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.pkg.imports;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.utils.FileUtils.externalPath;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.pkg.NetworkImportPlan;
import cn.classfun.droidvm.lib.pkg.PackageConstants;
import cn.classfun.droidvm.lib.pkg.PackageInput;
import cn.classfun.droidvm.lib.pkg.PackageManifest;
import cn.classfun.droidvm.lib.pkg.Phase;
import cn.classfun.droidvm.lib.pkg.VolumeSet;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.markdown.MarkdownRender;
import cn.classfun.droidvm.ui.vm.info.VMInfoActivity;
import cn.classfun.droidvm.ui.widgets.container.CollapsibleContainer;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class VMPkgImportActivity extends AppCompatActivity
    implements DaemonConnection.EventListener {
    private static final String TAG = "VMPkgImport";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String defaultPath = pathJoin(externalPath(), "DroidVM");
    private CollapsingToolbarLayout collapsingToolbar;
    private MaterialToolbar toolbar;
    private MaterialButton btnPick;
    private ExtendedFloatingActionButton btnImport;
    private LinearProgressIndicator pbRun;
    private TextView tvError;
    private TextView tvVmName;
    private TextView tvVmDetail;
    private TextView tvPackageMeta;
    private TextView tvDiskSummary;
    private TextInputRowWidget inputTarget;
    private CollapsibleContainer ccNetworks;
    private CollapsibleContainer ccNotes;
    private ComposeView notesPreview;
    private RecyclerView containerDisks;
    private LinearLayout containerNetworks;
    private TextView tvStatus;
    private TextView tvFile;
    private TextView tvProgressDetail;
    private View groupSummary;
    private Uri pickedUri;
    // Real filesystem path of the metadata master, resolved at pick time when a
    // sub-volume (.NNN) is chosen so preview/import target the master directly.
    private String masterRealPath;
    private PackageManifest preview;
    private VMPkgImportDiskAdapter diskAdapter;
    private final List<VMPkgImportNetworkBinder> networkBinders = new ArrayList<>();
    /** Loaded when a package is previewed; what the cards check themselves against. */
    private NetworkStore networkStore;
    private String pendingTaskId = null;
    private int importVolumeTotal = 0;
    private boolean importing = false;
    private final ActivityResultLauncher<String[]> openDocLauncher =
        registerForActivityResult(new OpenDocument(), this::onDocPicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vmpkg_import);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        toolbar = findViewById(R.id.toolbar);
        btnPick = findViewById(R.id.btn_pick);
        btnImport = findViewById(R.id.btn_import);
        pbRun = findViewById(R.id.pb_run);
        tvError = findViewById(R.id.tv_error);
        tvVmName = findViewById(R.id.tv_vm_name);
        tvVmDetail = findViewById(R.id.tv_vm_detail);
        tvPackageMeta = findViewById(R.id.tv_package_meta);
        tvDiskSummary = findViewById(R.id.tv_disk_summary);
        inputTarget = findViewById(R.id.input_target);
        ccNetworks = findViewById(R.id.cc_networks);
        ccNotes = findViewById(R.id.cc_notes);
        notesPreview = findViewById(R.id.notes_preview);
        containerDisks = findViewById(R.id.container_disks);
        containerNetworks = findViewById(R.id.container_networks);
        tvStatus = findViewById(R.id.tv_status);
        tvFile = findViewById(R.id.tv_file);
        tvProgressDetail = findViewById(R.id.tv_progress_detail);
        groupSummary = findViewById(R.id.group_summary);
        initialize();
    }

    private void initialize() {
        collapsingToolbar.setTitle(getString(R.string.vmpkg_import_title));
        toolbar.setNavigationOnClickListener(v -> {
            if (!importing) finish();
        });
        btnImport.setOnClickListener(v -> doImport());
        diskAdapter = new VMPkgImportDiskAdapter();
        containerDisks.setLayoutManager(new LinearLayoutManager(this));
        containerDisks.setAdapter(diskAdapter);
        inputTarget.setText(defaultPath);
        var filter = new String[]{PackageConstants.MIME, "*/*"};
        btnPick.setOnClickListener(v -> {
            if (!importing) openDocLauncher.launch(filter);
        });
        var intentUri = resolveIntentUri(getIntent());
        if (intentUri != null) {
            onDocPicked(intentUri);
        } else {
            mainHandler.postDelayed(() -> openDocLauncher.launch(filter), 50);
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        var uri = resolveIntentUri(intent);
        if (uri != null) onDocPicked(uri);
    }

    @Nullable
    private Uri resolveIntentUri(@Nullable Intent intent) {
        if (intent == null) return null;
        var data = intent.getData();
        if (data != null) return data;
        var stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        if (stream != null) return stream;
        var clip = intent.getClipData();
        if (clip == null || clip.getItemCount() <= 0) return null;
        for (int i = 0; i < clip.getItemCount(); i++) {
            var uri = clip.getItemAt(i).getUri();
            if (uri != null) return uri;
        }
        return null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        DaemonConnection.getInstance().addListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        DaemonConnection.getInstance().removeListener(this);
    }

    private void onDocPicked(@Nullable Uri uri) {
        if (uri == null || importing) return;
        pickedUri = uri;
        masterRealPath = null;
        importVolumeTotal = 0;
        tvError.setVisibility(GONE);
        groupSummary.setVisibility(GONE);
        btnImport.setEnabled(false);
        tvStatus.setText(R.string.vmpkg_import_reading);
        runOnPool(() -> {
            try {
                preview = readPreviewManifest(uri);
                mainHandler.post(this::showPreview);
            } catch (Exception e) {
                mainHandler.post(() -> showError(getString(R.string.vmpkg_import_invalid, e.getMessage())));
            }
        });
    }

    @NonNull
    private PackageManifest readPreviewManifest(@NonNull Uri uri) throws Exception {
        // A picked sub-volume (.NNN) has no header/manifest; redirect to its
        // metadata master in the same folder (needs MANAGE_EXTERNAL_STORAGE).
        var real = resolveUriPath(this, uri);
        if (real != null && !real.isEmpty() && VolumeSet.isSubVolume(real)) {
            masterRealPath = VolumeSet.masterOf(real);
            try (var is = new FileInputStream(masterRealPath)) {
                return PackageInput.peekManifest(is);
            }
        }
        // Master or single file: SAF stream is enough. Remember the resolved
        // master path (may be null) so import can reuse it.
        masterRealPath = real == null || real.isEmpty() ? null : VolumeSet.masterOf(real);
        try (var is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new RuntimeException("Cannot open file");
            return PackageInput.peekManifest(is);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void showPreview() {
        if (preview == null) return;
        tvVmName.setText(preview.vm.getName());
        tvVmDetail.setText(getString(
            R.string.vm_item_info,
            preview.vm.item.optLong("cpu_count", 0),
            preview.vm.item.optLong("memory_mb", 0)
        ));
        var df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        var appVer = preview.appVersion.isEmpty() ? "DroidVM" : preview.appVersion;
        var dateStr = preview.createdAt > 0 ? df.format(new Date(preview.createdAt)) : "-";
        var meta = getString(
            R.string.vmpkg_import_meta,
            appVer, dateStr,
            preview.manifestVersion
        );
        tvPackageMeta.setText(meta.trim());
        long totalSize = 0;
        int diskCount = 0;
        diskAdapter.disks.clear();
        for (var d : preview.disks) {
            totalSize += d.size;
            // Backing images take up room like anything else, but they are not disks the VM
            // gets: counting them as disks would say this package holds more than it does.
            if (d.attached) diskCount++;
            diskAdapter.disks.add(d);
        }
        diskAdapter.notifyDataSetChanged();
        showNotes();
        buildNetworkCards();
        ccNetworks.setVisibility(networkBinders.isEmpty() ? GONE : VISIBLE);
        if (inputTarget.getText().trim().isEmpty())
            inputTarget.setText(defaultPath);
        tvDiskSummary.setText(getString(
            R.string.vmpkg_import_disk_summary,
            diskCount,
            formatSize(totalSize)
        ));
        tvStatus.setText("");
        btnPick.setVisibility(GONE);
        groupSummary.setVisibility(VISIBLE);
        btnImport.setVisibility(VISIBLE);
        btnImport.setEnabled(true);
    }

    /**
     * The notes the packaged VM carries, rendered before anything is imported: they are where an
     * author says what the VM is and what to do with it, which is exactly what someone looking at
     * a package they were handed wants to read first.
     */
    private void showNotes() {
        var notes = preview == null ? "" : preview.vm.getNotes();
        if (notes.trim().isEmpty()) {
            ccNotes.setVisibility(GONE);
            return;
        }
        ccNotes.setVisibility(VISIBLE);
        MarkdownRender.bind(notesPreview, notes);
    }

    /**
     * One card per network the package carries, each deciding for itself. A package holds one
     * network per distinct one its VM's adapters were on, and they need not be alike -- an L2
     * bridge and a routed gVisor network can travel together -- so what can be done with one of
     * them says nothing about the next.
     */
    private void buildNetworkCards() {
        networkBinders.clear();
        containerNetworks.removeAllViews();
        if (preview == null) return;
        networkStore = new NetworkStore();
        networkStore.load(this);
        var plan = new NetworkImportPlan(networkStore);
        var inflater = LayoutInflater.from(this);
        for (var packaged : preview.networks) {
            var view = inflater.inflate(
                R.layout.item_vmpkg_import_network, containerNetworks, false);
            var binder = new VMPkgImportNetworkBinder(view, packaged, this::refreshCreatePreviews);
            binder.bind(plan);
            networkBinders.add(binder);
            containerNetworks.addView(view);
        }
        refreshCreatePreviews();
    }

    /**
     * Settles the name and bridge name each network being created would end up with, in card
     * order and against one plan, so that two packaged networks that want the same name are not
     * both shown taking it. Redone whenever a card changes its mind: a name freed by a card that
     * switched to joining is a name the next one can have.
     */
    private void refreshCreatePreviews() {
        if (networkStore == null) return;
        var plan = new NetworkImportPlan(networkStore);
        for (var binder : networkBinders)
            binder.setPrepared(binder.mode() == NetworkImportMode.CREATE
                ? plan.prepareCreate(binder.packaged) : null);
    }

    /** What the cards decided, as the import request carries it. */
    @NonNull
    private JSONArray buildNetworkPlan() {
        var arr = new JSONArray();
        for (var binder : networkBinders) {
            try {
                arr.put(binder.toEntry().toJson());
            } catch (JSONException e) {
                Log.w(TAG, "Failed to encode a network import decision", e);
            }
        }
        return arr;
    }

    private void doImport() {
        if (pickedUri == null || preview == null || importing) return;
        // Prefer the master path resolved at pick time; the daemon maps any
        // .NNN sub-volume to its master anyway.
        var srcPath = masterRealPath;
        if (srcPath == null || srcPath.isEmpty()) srcPath = resolveUriPath(this, pickedUri);
        if (srcPath == null || srcPath.isEmpty()) {
            showError(getString(
                R.string.vmpkg_import_failed,
                getString(R.string.vmpkg_export_failed_path_resolve)
            ));
            return;
        }
        try {
            ensureTargetSpace(importRequiredBytes(), targetFolder());
        } catch (IOException e) {
            showError(getString(R.string.vmpkg_import_failed, e.getMessage()));
            return;
        }
        setImporting(true);
        tvError.setVisibility(GONE);
        tvStatus.setText(R.string.vmpkg_import_running);
        tvFile.setText("");
        tvProgressDetail.setText("");
        var conn = DaemonConnection.getInstance();
        conn.buildRequest("vm_import")
            .put("src_path", srcPath)
            .put("target_dir", targetFolder().getPath())
            .put("network_plan", buildNetworkPlan())
            .onResponse(resp -> {
                var tid = resp.optString("task_id", "");
                if (!tid.isEmpty()) pendingTaskId = tid;
                else onImportFailure(getString(R.string.vmpkg_export_failed_no_task));
            })
            .onUnsuccessful(resp -> onImportFailure(resp.optString("message", "request failed")))
            .onError(e -> onImportFailure(e.getMessage()))
            .invoke();
    }

    @Override
    public void onDaemonEvent(JSONObject msg) {
        if (msg == null || !msg.optString("type", "").equals("event")) return;
        var data = msg.optJSONObject("data");
        if (data == null || !data.optString("event", "").equals("vm_import_status")) return;
        var tid = data.optString("task_id", "");
        if (pendingTaskId == null || !pendingTaskId.equals(tid)) return;
        var phase = optEnum(data, "phase", Phase.SCAN);
        var done = data.optInt("done", 0);
        var total = data.optInt("total", 0);
        importVolumeTotal = data.optInt("volume_total", importVolumeTotal);
        var file = data.optString("file", "");
        var bytesDone = data.optLong("bytes_done", -1);
        var bytesTotal = data.optLong("bytes_total", -1);
        var message = data.optString("message", "");
        var vmId = data.optString("vm_id", "");
        var disks = data.optJSONArray("disks");
        var networks = data.optJSONArray("networks");
        mainHandler.post(() -> onProgress(
            phase, done, total, file,
            bytesDone, bytesTotal,
            message, vmId, disks, networks
        ));
    }

    @Override
    public void onDaemonConnected() {
    }

    @Override
    public void onDaemonDisconnected() {
    }

    private void onProgress(
        @NonNull Phase phase,
        int done,
        int total,
        @NonNull String file,
        long bytesDone,
        long bytesTotal,
        @NonNull String message,
        @NonNull String vmId,
        @Nullable JSONArray disks,
        @Nullable JSONArray networks
    ) {
        switch (phase) {
            case PACK:
                if (importVolumeTotal > 0) tvStatus.setText(getString(
                    R.string.vmpkg_import_running_volume, importVolumeTotal
                ));
                else tvStatus.setText(R.string.vmpkg_import_running);
                applyProgress(done, total, bytesDone, bytesTotal);
                applyProgressDetail(file, bytesDone, bytesTotal);
                break;
            case DONE:
                if (vmId.isEmpty()) {
                    onImportFailure("missing vm_id");
                    return;
                }
                finishImport(
                    vmId,
                    disks == null ? new JSONArray() : disks,
                    networks == null ? new JSONArray() : networks
                );
                break;
            case ERROR:
                onImportFailure(message);
                break;
            default:
                break;
        }
    }

    private void finishImport(
        @NonNull String vmId,
        @NonNull JSONArray disks,
        @NonNull JSONArray networks
    ) {
        DaemonConnection.getInstance().buildRequest("vm_get")
            .put("vm_id", vmId)
            .onResponse(resp -> persistImport(resp.optJSONObject("data"), disks, networks))
            .onUnsuccessful(resp -> onImportFailure(resp.optString("message", "request failed")))
            .onError(e -> onImportFailure(e.getMessage()))
            .invoke();
    }

    private void persistImport(
        @Nullable JSONObject vmJson,
        @NonNull JSONArray disks,
        @NonNull JSONArray networks
    ) {
        try {
            if (vmJson == null) throw new IllegalArgumentException("missing VM config");
            var vm = new VMConfig(vmJson);
            var vmStore = new VMStore();
            vmStore.load(this);
            if (vmStore.findById(vm.getId()) == null) vmStore.add(vm);
            else vmStore.update(vm);
            var diskStore = new DiskStore();
            diskStore.load(this);
            var pathByArchive = new HashMap<String, String>();
            for (int i = 0; i < disks.length(); i++) {
                var diskJson = disks.optJSONObject(i);
                if (diskJson == null) continue;
                var path = diskJson.optString("path");
                var file = new File(path);
                var folder = file.getParent();
                var name = file.getName();
                if (path.isEmpty()) {
                    name = diskJson.optString("name", "");
                    folder = diskJson.optString("folder", "");
                    path = pathJoin(folder, name);
                }
                var archive = diskJson.optString("archive_path", "");
                if (!archive.isEmpty() && !path.isEmpty()) pathByArchive.put(archive, path);
                if (!path.isEmpty() && diskStore.findByPath(path) != null) continue;
                var disk = new DiskConfig();
                disk.setName(name);
                disk.item.set("folder", folder == null ? "" : folder);
                diskStore.add(disk);
            }
            linkImportedChains(diskStore, disks, pathByArchive);
            var networkStore = new NetworkStore();
            networkStore.load(this);
            for (int i = 0; i < networks.length(); i++) {
                var netJson = networks.optJSONObject(i);
                if (netJson == null) continue;
                var net = new NetworkConfig(netJson);
                if (networkStore.findById(net.getId()) == null) networkStore.add(net);
            }
            vmStore.save(this);
            diskStore.save(this);
            networkStore.save(this);
            mainHandler.post(() -> {
                setImporting(false);
                pendingTaskId = null;
                tvStatus.setText(getString(R.string.vmpkg_import_success, vm.getName()));
                Toast.makeText(this, R.string.vmpkg_import_done, LENGTH_SHORT).show();
                setResult(Activity.RESULT_OK);
                var intent = new Intent(this, VMInfoActivity.class);
                intent.putExtra("target_id", vm.getId().toString());
                startActivity(intent);
                finish();
            });
        } catch (Exception e) {
            onImportFailure(e.getMessage());
        }
    }

    /**
     * Record the overlay-to-base links the package described, so imported disks show as one
     * tree in branch management right away rather than waiting for the header walk to
     * rediscover them the next time something opens them.
     */
    private void linkImportedChains(
        @NonNull DiskStore store,
        @NonNull JSONArray disks,
        @NonNull HashMap<String, String> pathByArchive
    ) {
        for (int i = 0; i < disks.length(); i++) {
            var diskJson = disks.optJSONObject(i);
            if (diskJson == null) continue;
            var backing = diskJson.optString("backing_archive", "");
            if (backing.isEmpty()) continue;
            var childPath = diskJson.optString("path", "");
            var parentPath = pathByArchive.get(backing);
            if (childPath.isEmpty() || parentPath == null) continue;
            var child = store.findByPath(childPath);
            var parent = store.findByPath(parentPath);
            if (child == null || parent == null) continue;
            child.setParentId(parent.getId());
        }
    }

    private void onImportFailure(@Nullable String msg) {
        var message = msg == null ? "Unknown error" : msg;
        mainHandler.post(() -> {
            setImporting(false);
            pendingTaskId = null;
            tvStatus.setText(getString(R.string.vmpkg_import_failed, message));
            showError(getString(R.string.vmpkg_import_failed, message));
        });
    }

    private void showError(@NonNull String message) {
        tvError.setText(message);
        tvError.setVisibility(VISIBLE);
        Toast.makeText(this, message, LENGTH_LONG).show();
    }

    private void setImporting(boolean importing) {
        this.importing = importing;
        btnPick.setEnabled(!importing);
        btnImport.setEnabled(!importing && preview != null);
        for (var binder : networkBinders) binder.setEnabled(!importing);
        pbRun.setVisibility(importing ? VISIBLE : GONE);
        pbRun.setIndeterminate(importing);
    }

    private void applyProgress(
        int done,
        int total,
        long bytesDone,
        long bytesTotal
    ) {
        if (total <= 0) return;
        pbRun.setIndeterminate(false);
        if (bytesTotal > 0 && bytesDone >= 0) {
            int unit = 1000;
            long clampedBytes = Math.min(bytesDone, bytesTotal);
            int scaled = (int) ((clampedBytes * unit) / bytesTotal);
            pbRun.setMax(total * unit);
            pbRun.setProgress(Math.min(done * unit + scaled, total * unit));
        } else {
            pbRun.setMax(total);
            pbRun.setProgress(done);
        }
    }

    private void applyProgressDetail(
        @NonNull String file,
        long bytesDone,
        long bytesTotal
    ) {
        tvFile.setText(file);
        if (bytesTotal <= 0 || bytesDone < 0) {
            tvProgressDetail.setText("");
            return;
        }
        var done = Math.min(bytesDone, bytesTotal);
        tvProgressDetail.setText(getString(
            R.string.vmpkg_export_progress_detail,
            formatSize(done),
            formatSize(bytesTotal),
            done * 100 / bytesTotal
        ));
    }

    @NonNull
    private File targetFolder() {
        var path = inputTarget.getText().trim();
        if (path.isEmpty()) path = defaultPath;
        return new File(path);
    }

    private long importRequiredBytes() {
        long size = 0;
        if (preview != null) {
            for (var disk : preview.disks) size += disk.size;
            for (var boot : preview.boots) size += boot.size;
        }
        return size;
    }

    private void ensureTargetSpace(long required, @NonNull File target) throws IOException {
        if (required <= 0) return;
        var parent = target.getParentFile();
        var spacePath = target.exists() || parent == null ? target : parent;
        var usable = spacePath.getUsableSpace();
        if (usable >= required) return;
        var storage = getSystemService(StorageManager.class);
        if (storage != null) {
            var uuid = storage.getUuidForPath(spacePath);
            var allocatable = storage.getAllocatableBytes(uuid);
            if (allocatable >= required) {
                storage.allocateBytes(uuid, required);
                if (spacePath.getUsableSpace() >= required) return;
            }
            usable = Math.max(usable, allocatable);
        }
        throw new IOException(getString(
            R.string.vmpkg_import_no_space,
            formatSize(required),
            formatSize(usable)
        ));
    }
}
