package cn.classfun.droidvm.ui.disk.create;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.FileUtils.checkFileName;
import static cn.classfun.droidvm.lib.utils.FileUtils.checkFilePath;
import static cn.classfun.droidvm.lib.utils.FileUtils.externalPath;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;
import static cn.classfun.droidvm.lib.utils.StringUtils.stripExtension;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.lib.store.disk.DiskConfig.supportsCompress;
import static cn.classfun.droidvm.lib.store.disk.DiskConfig.supportsPreallocate;
import static cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity.createIntent;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.SimpleTextWatcher;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.size.SizeUnit;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.ui.widgets.row.ChooseRowWidget;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class DiskCreateActivity extends AppCompatActivity {
    private static final String TAG = "DiskCreateActivity";
    public static final String EXTRA_BACKING_ID = "backing_id";
    private boolean updatingName = false;
    private boolean nameManuallyEdited = false;
    private UUID backingId = null;
    private TextInputRowWidget inputName, inputFolder;
    private TextInputRowWidget inputSize;
    private TextInputRowWidget inputBacking;
    private Chip chipPreview;
    private ChooseRowWidget chooseFormat;
    private ChooseRowWidget chooseCompress;
    private SwitchRowWidget switchPreallocate;
    private FloatingActionButton fabCreate;
    private MaterialToolbar toolbar;
    private CollapsingToolbarLayout collapsingToolbar;
    private ActivityResultLauncher<Uri> folderPickerLauncher;
    private ActivityResultLauncher<String[]> filePickerLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_disk_create);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        toolbar = findViewById(R.id.toolbar);
        inputName = findViewById(R.id.input_name);
        inputFolder = findViewById(R.id.input_folder);
        inputSize = findViewById(R.id.input_size);
        chipPreview = findViewById(R.id.chip_preview);
        chooseFormat = findViewById(R.id.choose_format);
        chooseCompress = findViewById(R.id.choose_compress);
        switchPreallocate = findViewById(R.id.sw_preallocate);
        inputBacking = findViewById(R.id.input_backing);
        fabCreate = findViewById(R.id.fab_create);
        initialize();
    }

    private void initialize() {
        var tree = new ActivityResultContracts.OpenDocumentTree();
        folderPickerLauncher = registerForActivityResult(tree, this::onFolderPickerResult);
        var doc = new ActivityResultContracts.OpenDocument();
        filePickerLauncher = registerForActivityResult(doc, this::onFilePickerResult);
        collapsingToolbar.setTitle(getString(R.string.disk_create_title));
        toolbar.setNavigationOnClickListener(v -> finish());
        var defaultFolder = pathJoin(externalPath(), "DroidVM");
        inputFolder.setText(defaultFolder);
        inputSize.setValue(1, SizeUnit.GB);
        chooseFormat.configure(DiskFormat.class, DiskFormat.QCOW2);
        chooseFormat.setOnValueChangedListener(this::onFormatChanged);
        chooseCompress.configure(DiskCompress.class, DiskCompress.DEFLATE);
        inputBacking.setIconButtonOnClickListener(this::onChooseBacking);
        inputName.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!updatingName) nameManuallyEdited = true;
                updatePreview();
            }
        });
        inputFolder.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                updatePreview();
            }
        });
        inputFolder.setIconButtonOnClickListener(this::onChooseFolder);
        var intent = getIntent();
        var backingId = intent.getStringExtra(EXTRA_BACKING_ID);
        if (backingId != null) initBackingMode(backingId);
        updateOptionsVisibility(chooseFormat.getSelectedItem());
        updatePreview();
        fabCreate.setOnClickListener(v -> doCreate());
        runOnPool(() -> {
            int i = 0;
            var name = "disk.qcow2";
            while (shellCheckExists(pathJoin(defaultFolder, name)))
                name = fmt("disk%d.qcow2", ++i);
            var finalName = name;
            runOnUiThread(() -> {
                updatingName = true;
                inputName.setText(finalName);
                updatingName = false;
            });
        });
    }

    private void setupFormats() {
        var formats = new ArrayList<DiskFormat>();
        for (var fmt : DiskFormat.values())
            if (DiskConfig.supportsCreate(fmt))
                formats.add(fmt);
        chooseFormat.setItems(formats.toArray(new DiskFormat[0]));
        chooseFormat.setSelectedItem(DiskFormat.QCOW2);
        chooseFormat.setEnabled(false);
    }

    private void initBackingMode(@NonNull String backingId) {
        try {
            var store = new DiskStore();
            store.load(this);
            var backing = store.findById(backingId);
            if (backing == null) throw new RuntimeException(fmt(
                "Backing disk not found: %s", backingId
            ));
            this.backingId = backing.getId();
            var path = backing.getFullPath();
            var info = ImageUtils.getImageInfo(path);
            inputBacking.setTextAndMoveCursor(path);
            inputBacking.setEnabled(false);
            setupFormats();
            if (info.has("virtual-size"))
                inputSize.setValue(info.getLong("virtual-size"));
        } catch (Exception e) {
            Log.w(TAG, "Failed to load backing disk config", e);
            finish();
        }
    }

    private void onChooseFolder() {
        folderPickerLauncher.launch(null);
    }

    private void onFolderPickerResult(Uri uri) {
        if (uri == null) return;
        var path = resolveUriPath(this, uri);
        if (path != null) inputFolder.setText(path);
    }

    private void onChooseBacking() {
        filePickerLauncher.launch(new String[]{"*/*"});
    }

    private void onFilePickerResult(Uri uri) {
        if (uri == null) return;
        var path = resolveUriPath(this, uri);
        if (path != null) inputBacking.setText(path);
    }

    private void onFormatChanged() {
        DiskFormat newFmt = chooseFormat.getSelectedItem();
        var newExt = newFmt.getExt();
        if (!nameManuallyEdited) {
            var currentName = inputName.getText();
            var baseName = stripExtension(currentName);
            updatingName = true;
            var fn = fmt("%s.%s", baseName, newExt);
            inputName.setText(fn);
            var e = inputName.getText();
            inputName.setSelection(e.length());
            updatingName = false;
        }
        updateOptionsVisibility(newFmt);
        updatePreview();
    }

    private void updateOptionsVisibility(DiskFormat fmt) {
        switchPreallocate.setVisibility(supportsPreallocate(fmt) ? VISIBLE : GONE);
        chooseCompress.setVisibility(supportsCompress(fmt) ? VISIBLE : GONE);
        inputBacking.setVisibility(fmt == DiskFormat.QCOW2 ? VISIBLE : GONE);
    }

    private void updatePreview() {
        var name = inputName.getText();
        var folder = inputFolder.getText();
        chipPreview.setText(getString(R.string.disk_create_preview, pathJoin(folder, name)));
    }

    private void doCreate() {
        boolean valid = true;
        var name = inputName.getText();
        inputName.setError(null);
        inputFolder.setError(null);
        inputBacking.setError(null);
        inputSize.setError(null);
        if (name.isEmpty()) {
            inputName.setError(R.string.disk_create_error_name_empty);
            valid = false;
        } else if (!checkFileName(name)) {
            inputName.setError(R.string.disk_create_error_name_invalid);
            valid = false;
        }
        var folder = inputFolder.getText();
        if (folder.isEmpty()) {
            inputFolder.setError(R.string.disk_create_error_folder_empty);
            valid = false;
        } else if (!checkFilePath(folder, true)) {
            inputFolder.setError(R.string.disk_create_error_folder_invalid);
            valid = false;
        }
        var fullPath = pathJoin(folder, name);
        long sizeBytes = 0;
        try {
            sizeBytes = inputSize.getValue();
        } catch (Exception ignored) {
        }
        if (sizeBytes <= 0) {
            inputSize.setError(R.string.disk_create_error_size_zero);
            valid = false;
        }
        var backing = inputBacking.getText();
        if (backingId != null) {
            if (backing.isEmpty()) {
                inputBacking.setError(R.string.disk_create_error_name_invalid);
                valid = false;
            }
            if (chooseFormat.getSelectedItem() != DiskFormat.QCOW2)
                valid = false;
        }
        if (!backing.isEmpty()) {
            if (backing.equals(fullPath)) {
                inputBacking.setError(R.string.disk_create_error_backing_same);
                valid = false;
            } else if (!checkFilePath(backing, true)) {
                inputBacking.setError(R.string.disk_create_error_backing_invalid);
                valid = false;
            }
        }
        if (!valid) return;
        var config = new DiskConfig();
        config.setName(name);
        config.item.set("folder", folder);
        if (shellCheckExists(config.getFullPath())) {
            inputName.setError(R.string.disk_create_error_exists);
            return;
        }
        runOnPool(() -> {
            var store = new DiskStore();
            store.load(this);
            store.add(config);
            store.save(this);
        });
        try {
            DiskFormat format = chooseFormat.getSelectedItem();
            DiskCompress compress = chooseCompress.getSelectedItem();
            var preallocate = switchPreallocate.isChecked() && supportsPreallocate(format);
            var obj = new JSONObject();
            obj.put("action", "create");
            obj.put("format", format.name().toLowerCase());
            obj.put("size", String.valueOf(sizeBytes));
            obj.put("preallocate", preallocate);
            if (supportsCompress(format))
                obj.put("compress", compress.name().toLowerCase());
            if (backingId != null) {
                obj.put("backing_id", backingId.toString());
            } else if (!backing.isEmpty() && format == DiskFormat.QCOW2)
                obj.put("backing_path", backing);
            var intent = createIntent(this, config.getId(), obj);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start create activity", e);
        }
        var resultData = new Intent();
        resultData.putExtra("result_disk_path", config.getFullPath());
        setResult(RESULT_OK, resultData);
        finish();
    }
}
