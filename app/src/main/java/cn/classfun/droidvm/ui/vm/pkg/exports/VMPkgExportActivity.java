package cn.classfun.droidvm.ui.vm.pkg.exports;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static cn.classfun.droidvm.lib.pkg.PackageConstants.EXTENSION;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.archive.Compression;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.pkg.DiskRef;
import cn.classfun.droidvm.lib.pkg.Phase;
import cn.classfun.droidvm.lib.pkg.PackageConstants;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.utils.ShareUtils;
import cn.classfun.droidvm.ui.widgets.container.CollapsibleContainer;
import cn.classfun.droidvm.ui.widgets.row.ChooseRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class VMPkgExportActivity extends AppCompatActivity
    implements DaemonConnection.EventListener {
    public static final String EXTRA_VM_ID = "vm_id";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private CollapsingToolbarLayout collapsingToolbar;
    private CollapsibleContainer ccDisks;
    private CollapsibleContainer ccOptions;
    private MaterialToolbar toolbar;
    private RecyclerView containerDisks;
    private TextView tvVMName;
    private TextView tvVMDetail;
    private TextView tvNoDisks;
    private TextView tvDiskSummary;
    private ChooseRowWidget chooseCompression;
    private TextView tvCompressionDesc;
    private TextInputRowWidget inputVolumeSize;
    private LinearProgressIndicator pbRun;
    private TextView tvStatus;
    private TextView tvFileName;
    private TextView tvProgressDetail;
    private ExtendedFloatingActionButton fabCreate;
    private ExtendedFloatingActionButton fabShare;
    private VMConfig config;
    private Compression compression = PackageConstants.DEFAULT_COMPRESSION;
    private long volumeSize = 0;
    private VMPkgExportDiskAdapter adapter;
    private String pendingTaskId = null;
    private String path = null;
    private int exportVolumeIndex = 0;
    private boolean exporting = false;
    private Phase phase = Phase.IDLE;
    private final ActivityResultLauncher<String> createDocLauncher =
        registerForActivityResult(new CreateDocument(PackageConstants.MIME), this::onDocPicked);

    @NonNull
    public static Intent createIntent(@NonNull Context ctx, @NonNull UUID vmId) {
        var intent = new Intent(ctx, VMPkgExportActivity.class);
        intent.putExtra(EXTRA_VM_ID, vmId.toString());
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vmpkg_export);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        ccDisks = findViewById(R.id.cc_disks);
        ccOptions = findViewById(R.id.cc_options);
        toolbar = findViewById(R.id.toolbar);
        tvVMName = findViewById(R.id.tv_vm_name);
        tvVMDetail = findViewById(R.id.tv_vm_detail);
        containerDisks = findViewById(R.id.container_disks);
        tvNoDisks = findViewById(R.id.tv_no_disks);
        tvDiskSummary = findViewById(R.id.tv_disk_summary);
        chooseCompression = findViewById(R.id.choose_compression);
        tvCompressionDesc = findViewById(R.id.tv_compression_desc);
        inputVolumeSize = findViewById(R.id.input_volume_size);
        pbRun = findViewById(R.id.pb_run);
        tvStatus = findViewById(R.id.tv_status);
        tvFileName = findViewById(R.id.tv_filename);
        tvProgressDetail = findViewById(R.id.tv_progress_detail);
        fabCreate = findViewById(R.id.fab_create);
        fabShare = findViewById(R.id.fab_share);
        initialize();
    }

    private void initialize() {
        var store = new VMStore();
        store.load(this);
        var id = getIntent().getStringExtra(EXTRA_VM_ID);
        if (id == null) {
            finish();
            return;
        }
        var vmId = UUID.fromString(id);
        config = store.findById(vmId);
        if (config == null) {
            finish();
            return;
        }
        collapsingToolbar.setTitle(getString(R.string.vmpkg_export_title));
        tvVMName.setText(config.getName());
        tvVMDetail.setText(getString(
            R.string.vm_item_info,
            config.item.optLong("cpu_count", 0),
            config.item.optLong("memory_mb", 0)
        ));
        adapter = new VMPkgExportDiskAdapter();
        adapter.setOnSelectionChanged(this::updateDiskSummary);
        containerDisks.setLayoutManager(new LinearLayoutManager(this));
        containerDisks.setAdapter(adapter);
        bindDisks();
        bindCompression();
        // Volume split defaults off: leave the input empty so export produces a
        // single self-contained file unless the user enters a size.
        inputVolumeSize.setText("");
        var handler = new VMPkgExportOnBackPressedCallback();
        toolbar.setNavigationOnClickListener(v -> handler.handleOnBackPressed());
        getOnBackPressedDispatcher().addCallback(this, handler);
        fabCreate.setOnClickListener(v -> startPicker());
        fabShare.setOnClickListener(v -> startShare());
    }

    private class VMPkgExportOnBackPressedCallback extends OnBackPressedCallback {
        public VMPkgExportOnBackPressedCallback() {
            super(true);
        }

        @Override
        public void handleOnBackPressed() {
            if (!exporting) finish();
        }
    }

    private void startShare() {
        if (path == null) return;
        var f = new File(path);
        if (!f.exists()) return;
        ShareUtils.shareFile(
            this, f, PackageConstants.MIME,
            getString(R.string.vmpkg_export_share_title),
            msg -> showToast(R.string.vmpkg_export_failed, msg)
        );
    }

    private void bindCompression() {
        chooseCompression.configure(
            Compression.class,
            PackageConstants.DEFAULT_COMPRESSION
        );
        chooseCompression.setOnValueChangedListener(
            this::updateCompressionDesc
        );
        updateCompressionDesc();
    }

    private void updateCompressionDesc() {
        compression = chooseCompression.getSelectedItem();
        tvCompressionDesc.setText(compression.desc);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void bindDisks() {
        adapter.disks.clear();
        adapter.selected.clear();
        var arr = config.item.opt("disks", DataItem.newArray());
        for (int i = 0; i < arr.size(); i++) {
            var e = arr.get(i);
            if (!e.is(DataItem.Type.OBJECT)) continue;
            var d = new DiskRef(i, e);
            if (d.path == null || d.path.isEmpty()) continue;
            adapter.disks.add(d);
            if (!d.isCDROM()) adapter.selected.add(i);
        }
        adapter.notifyDataSetChanged();
        tvNoDisks.setVisibility(adapter.disks.isEmpty() ? VISIBLE : GONE);
        updateDiskSummary();
    }

    private void updateDiskSummary() {
        int selectedCount = 0;
        long totalSize = 0;
        for (var disk : adapter.disks) {
            if (!adapter.selected.contains(disk.index)) continue;
            selectedCount++;
            if (disk.path != null && !disk.path.isEmpty())
                totalSize += new File(disk.path).length();
        }
        tvDiskSummary.setText(getString(
            R.string.vmpkg_export_disk_summary,
            selectedCount,
            adapter.disks.size(),
            formatSize(totalSize)
        ));
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

    private void startPicker() {
        if (config == null || exporting) return;
        if (!validateSelectedDisks()) return;
        var inst = DaemonConnection.getInstance();
        DaemonConnection.OnError onError = e ->
            onExportFailure(e.getMessage());
        DaemonConnection.OnUnsuccessful onUnsuccessful = resp ->
            onExportFailure(resp.optString("message", "request failed"));
        DaemonConnection.OnResponse onStatus = resp -> mainHandler.post(() -> {
            if (resp.optString("state").equals("stopped")) {
                launchCreateDocument();
            } else showToast(
                R.string.vmpkg_export_failed,
                getString(R.string.vmpkg_export_vm_must_stop)
            );
        });
        DaemonConnection.OnResponse onExists = resp -> {
            if (!resp.optBoolean("exists", false)) {
                mainHandler.post(this::launchCreateDocument);
                return;
            }
            inst.buildRequest("vm_status")
                .put("vm_id", config.getId())
                .onResponse(onStatus)
                .onUnsuccessful(onUnsuccessful)
                .onError(onError)
                .invoke();
        };
        inst.buildRequest("vm_exists")
            .put("vm_id", config.getId())
            .onResponse(onExists)
            .onUnsuccessful(onUnsuccessful)
            .onError(onError)
            .invoke();
    }

    private void launchCreateDocument() {
        var base = config.getName();
        var sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char ch = base.charAt(i);
            if ("\\/:*?\"<>|".indexOf(ch) >= 0 || ch < 0x20) ch = '_';
            sb.append(ch);
        }
        if (sb.length() == 0) sb.append("vm");
        var name = sb.toString();
        var ext = fmt(".%s", EXTENSION);
        if (!name.endsWith(ext)) name += ext;
        createDocLauncher.launch(name);
    }

    private boolean validateSelectedDisks() {
        for (var disk : adapter.disks) {
            if (!adapter.selected.contains(disk.index)) continue;
            if (disk.path == null || disk.path.isEmpty() || !new File(disk.path).isFile()) {
                showToast(R.string.vmpkg_export_failed, getString(
                    R.string.vmpkg_export_disk_missing,
                    disk.path == null ? "" : disk.path
                ));
                return false;
            }
        }
        return true;
    }

    private void onDocPicked(@Nullable Uri uri) {
        if (uri == null || exporting) return;
        // Clear any stale volume index from a previous export so a following
        // single-file export does not inherit its "volume N" status.
        exportVolumeIndex = 0;
        // Read the (empty = single file) volume size on the UI thread before
        // handing off to the worker pool.
        var vsText = inputVolumeSize.getText().trim();
        if (vsText.isEmpty()) {
            volumeSize = 0;
        } else if (!inputVolumeSize.isInputValid()) {
            showToast(R.string.vmpkg_export_failed, getString(
                R.string.vmpkg_export_volume_size_invalid,
                PackageConstants.MIN_VOLUME_SIZE / (1024 * 1024)
            ));
            return;
        } else {
            volumeSize = inputVolumeSize.getValue();
        }
        runOnPool(() -> startExport(uri));
    }

    private void startExport(@NonNull Uri uri) {
        var destPath = resolveUriPath(this, uri);
        if (destPath == null || destPath.isEmpty()) {
            showToast(R.string.vmpkg_export_failed_path_resolve);
            return;
        }
        this.path = destPath;
        var diskIndices = new JSONArray();
        for (int i = 0; i < adapter.disks.size(); i++) {
            var idx = adapter.disks.get(i).index;
            if (adapter.selected.contains(idx)) diskIndices.put(idx);
        }
        var inst = DaemonConnection.getInstance();
        mainHandler.post(() -> {
            setExporting(true);
            fabCreate.setVisibility(GONE);
            fabShare.setVisibility(GONE);
            tvStatus.setText(R.string.vmpkg_export_running);
            tvProgressDetail.setText("");
            tvFileName.setText("");
            ccDisks.setVisibility(GONE);
            ccOptions.setVisibility(GONE);
        });
        phase = Phase.IDLE;
        DaemonConnection.OnError err = e -> onExportFailure(e.getMessage());
        DaemonConnection.OnUnsuccessful f = resp ->
            onExportFailure(resp.optString("message", "request failed"));
        DaemonConnection.OnResponse onExport = resp -> {
            var tid = resp.optString("task_id");
            if (!tid.isEmpty()) pendingTaskId = tid;
            else onExportFailure(getString(R.string.vmpkg_export_failed_no_task));
        };
        DaemonConnection.OnResponse onCreateModify = resp ->
            inst.buildRequest("vm_export")
            .put("vm_id", config.getId())
            .put("disks", diskIndices)
            .put("dest_path", destPath)
            .put("compression", compression)
            .put("volume_size", volumeSize)
            .onResponse(onExport)
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
        DaemonConnection.OnResponse onExists = resp ->
            inst.buildRequest(resp.optBoolean("exists", false) ? "vm_modify" : "vm_create")
            .put("config", config)
            .onResponse(onCreateModify)
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
        inst.buildRequest("vm_exists")
            .put("vm_id", config.getId())
            .onResponse(onExists)
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
    }

    @Override
    public void onDaemonEvent(JSONObject msg) {
        switch (phase) {
            case DONE:
            case ERROR:
                return;
        }
        if (msg == null) return;
        if (!msg.optString("type").equals("event")) return;
        var data = msg.optJSONObject("data");
        if (data == null) return;
        if (!data.optString("event").equals("vm_export_status")) return;
        var tid = data.optString("task_id");
        if (pendingTaskId == null || !pendingTaskId.equals(tid)) return;
        var phase = optEnum(data, "phase", Phase.SCAN);
        var done = data.optInt("done");
        var total = data.optInt("total");
        var bytesDone = data.optLong("bytes_done");
        var bytesTotal = data.optLong("bytes_total");
        var file = data.optString("file");
        var message = data.optString("message");
        exportVolumeIndex = data.optInt("volume_index", exportVolumeIndex);
        mainHandler.post(() -> onProgress(
            phase, done, total, bytesDone, bytesTotal, file, message
        ));
    }

    @Override
    public void onDaemonConnected() {
    }

    @Override
    public void onDaemonDisconnected() {
        switch (phase) {
            case IDLE:
            case DONE:
            case ERROR:
                return;
        }
        onExportFailure(getString(
            R.string.vmpkg_export_failed,
            "daemon dead"
        ));
        pendingTaskId = null;
    }

    private void onProgress(
        @NonNull Phase phase,
        int done,
        int total,
        long bytesDone,
        long bytesTotal,
        @NonNull String file,
        @NonNull String message
    ) {
        this.phase = phase;
        switch (phase) {
            case SCAN:
                tvStatus.setText(R.string.vmpkg_export_scan);
                applyProgress(done, total, bytesDone, bytesTotal);
                break;
            case PACK:
                if (exportVolumeIndex > 0) tvStatus.setText(getString(
                    R.string.vmpkg_export_pack_volume, exportVolumeIndex
                ));
                else tvStatus.setText(R.string.vmpkg_export_pack);
                applyProgress(done, total, bytesDone, bytesTotal);
                applyProgressDetail(file, bytesDone, bytesTotal);
                break;
            case DONE:
                pbRun.setVisibility(GONE);
                tvProgressDetail.setText("");
                tvFileName.setText("");
                fabShare.setVisibility(VISIBLE);
                tvStatus.setText(getString(
                    R.string.vmpkg_export_success_path, path
                ));
                showToast(R.string.vmpkg_export_success);
                pendingTaskId = null;
                setExporting(false);
                setResult(Activity.RESULT_OK);
                break;
            case ERROR:
                onExportFailure(message);
                pendingTaskId = null;
                break;
            default:
                break;
        }
    }

    private void onExportFailure(@Nullable String msg) {
        if (msg == null) msg = getString(R.string.vmpkg_export_failed_no_task);
        var finalMsg = msg;
        mainHandler.post(() -> {
            pbRun.setVisibility(GONE);
            setExporting(false);
            tvProgressDetail.setText("");
            tvFileName.setText("");
            fabCreate.setVisibility(VISIBLE);
            tvStatus.setText(getString(R.string.vmpkg_export_failed, finalMsg));
            ccDisks.setVisibility(VISIBLE);
            ccOptions.setVisibility(VISIBLE);
            showToast(R.string.vmpkg_export_failed, finalMsg);
        });
    }

    private void setExporting(boolean exporting) {
        this.exporting = exporting;
        pbRun.setVisibility(exporting ? VISIBLE : GONE);
        pbRun.setIndeterminate(exporting);
        chooseCompression.setEnabled(!exporting);
        inputVolumeSize.setEnabled(!exporting);
        containerDisks.setEnabled(!exporting);
        adapter.setEnabled(!exporting);
    }

    private void applyProgressDetail(
        @NonNull String file,
        long bytesDone,
        long bytesTotal
    ) {
        if (file.isEmpty() || bytesTotal <= 0 || bytesDone < 0) {
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
        tvFileName.setText(file);
    }

    private void showToast(@StringRes int id, Object... args) {
        mainHandler.post(() -> Toast.makeText(
            this, getString(id, args), LENGTH_LONG
        ).show());
    }

    private void applyProgress(int done, int total, long bytesDone, long bytesTotal) {
        if (total <= 0) return;
        pbRun.setIndeterminate(false);
        if (bytesTotal > 0 && bytesDone >= 0) {
            int unit = 1000;
            long clampedBytes = Math.min(bytesDone, bytesTotal);
            int scaled = (int) ((clampedBytes * unit) / bytesTotal);
            pbRun.setMax(total * unit);
            pbRun.setProgress(Math.min(
                done * unit + scaled,
                total * unit
            ));
        } else {
            pbRun.setMax(total);
            pbRun.setProgress(done);
        }
    }
}
