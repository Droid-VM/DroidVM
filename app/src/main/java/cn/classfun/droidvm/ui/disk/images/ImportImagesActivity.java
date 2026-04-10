package cn.classfun.droidvm.ui.disk.images;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;
import static cn.classfun.droidvm.lib.utils.FileUtils.externalPath;
import static cn.classfun.droidvm.lib.utils.NetUtils.DL_USER_AGENT;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity.startOptimize;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.io.File;
import java.util.ArrayList;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.data.Repos;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.ui.IconItemAdapter;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;
import cn.classfun.droidvm.ui.widgets.row.DropdownRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;
import cn.classfun.droidvm.ui.widgets.tools.DownloadWidget;
import cn.classfun.droidvm.ui.widgets.tools.DownloadWidget.OnDownloadListener;

public final class ImportImagesActivity extends AppCompatActivity implements OnDownloadListener {
    private Repos repos;
    private CollapsingToolbarLayout collapsingToolbar;
    private MaterialToolbar toolbar;
    private MaterialCardView cardPlaceholder;
    private MaterialCardView cardSelected;
    private View sectionMirror;
    private DropdownRowWidget dropdownMirror;
    private View sectionOutput;
    private TextInputRowWidget inputFilename;
    private TextInputRowWidget inputFolder;
    private MaterialCardView cardInfo;
    private TextView tvInfoSize;
    private TextView tvInfoUrl;
    private ExtendedFloatingActionButton fabDownload;
    private DownloadWidget downloadWidget;
    private NestedScrollView scrollView;
    private String[] mirrorKeys;
    private String[] mirrorLabels;
    private boolean isDownloading = false;
    private String downloadName = null;
    private String downloadFolder = null;
    private FlatImage selectedImage;
    private ActivityResultLauncher<Uri> folderPickerLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_images);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        toolbar = findViewById(R.id.toolbar);
        cardPlaceholder = findViewById(R.id.card_placeholder);
        cardSelected = findViewById(R.id.card_selected);
        sectionMirror = findViewById(R.id.section_mirror);
        dropdownMirror = findViewById(R.id.dropdown_mirror);
        sectionOutput = findViewById(R.id.section_output);
        inputFilename = findViewById(R.id.input_filename);
        inputFolder = findViewById(R.id.input_folder);
        cardInfo = findViewById(R.id.card_info);
        tvInfoSize = findViewById(R.id.tv_info_size);
        tvInfoUrl = findViewById(R.id.tv_info_url);
        fabDownload = findViewById(R.id.fab_download);
        downloadWidget = findViewById(R.id.download_widget);
        scrollView = findViewById(R.id.scroll_view);
        initialize();
    }

    private void initialize() {
        repos = Repos.load(this);
        collapsingToolbar.setTitle(getString(R.string.images_title));
        toolbar.setNavigationOnClickListener(v -> confirmExit());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });
        var tree = new ActivityResultContracts.OpenDocumentTree();
        folderPickerLauncher = registerForActivityResult(tree, this::onFolderPickerResult);
        inputFolder.setText(pathJoin(externalPath(), "DroidVM"));
        inputFolder.setIconButtonOnClickListener(this::onChooseFolder);
        fabDownload.setOnClickListener(v -> doDownload());
        cardPlaceholder.setOnClickListener(v -> showImagePicker());
        cardSelected.setOnClickListener(v -> showImagePicker());
        hideSelection();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (downloadWidget != null)
            downloadWidget.stopAndCleanup();
    }

    private void onChooseFolder() {
        folderPickerLauncher.launch(null);
    }

    private void onFolderPickerResult(Uri uri) {
        if (uri == null) return;
        var path = resolveUriPath(this, uri);
        if (path != null) inputFolder.setText(path);
    }

    private void confirmExit() {
        if (isDownloading && downloadWidget != null && downloadWidget.isRunning()) {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.download_exit_title)
                .setMessage(R.string.download_exit_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                .show();
        } else {
            finish();
        }
    }

    private void showImagePicker() {
        if (isDownloading) return;
        new ImagePickerDialog(this, this::onImageSelected).show();
    }

    private void onImageSelected(@NonNull FlatImage fi) {
        selectedImage = fi;
        var vh = new ImageViewHolder(cardSelected);
        vh.render(fi, false);
        cardPlaceholder.setVisibility(GONE);
        cardSelected.setVisibility(VISIBLE);
        showMirrorSection(fi);
        showOutputSection(fi);
    }

    private void showMirrorSection(@NonNull FlatImage fi) {
        var keys = new ArrayList<String>();
        var labels = new ArrayList<String>();
        keys.add("official");
        labels.add(getString(R.string.images_mirror_official));
        if (repos != null) {
            for (var mirror : repos.getMirrorsFor(fi.repo.getUrl())) {
                keys.add(mirror.getId());
                labels.add(mirror.getName());
            }
        }
        mirrorKeys = keys.toArray(new String[0]);
        mirrorLabels = labels.toArray(new String[0]);
        var mirrorAdapter = IconItemAdapter.create(this, mirrorLabels, R.drawable.ic_nav_network);
        dropdownMirror.setAdapter(mirrorAdapter);
        dropdownMirror.setText(mirrorLabels[0]);
        dropdownMirror.setOnItemClickListener((p, v, pos, id) -> updateInfoCard());
        sectionMirror.setVisibility(VISIBLE);
    }

    private void showOutputSection(@NonNull FlatImage fi) {
        var filename = fi.displayFilename;
        inputFilename.setText(filename);
        sectionOutput.setVisibility(VISIBLE);
        fabDownload.setVisibility(VISIBLE);
        updateInfoCard();
    }

    private void hideSelection() {
        selectedImage = null;
        cardPlaceholder.setVisibility(VISIBLE);
        cardSelected.setVisibility(GONE);
        sectionMirror.setVisibility(GONE);
        sectionOutput.setVisibility(GONE);
        fabDownload.setVisibility(GONE);
        cardInfo.setVisibility(GONE);
        inputFilename.setText("");
    }

    private void updateInfoCard() {
        if (selectedImage == null) return;
        var url = resolveDownloadUrl();
        tvInfoSize.setText(getString(R.string.images_info_size,
            formatSize(selectedImage.image.getSize())));
        tvInfoUrl.setText(getString(R.string.images_info_url, url));
        cardInfo.setVisibility(VISIBLE);
    }

    @NonNull
    private String resolveDownloadUrl() {
        if (selectedImage == null) return "";
        var mirrorKey = getSelectedMirrorKey();
        if (mirrorKey.equals("official"))
            return selectedImage.image.getUrl();
        if (repos == null)
            return selectedImage.image.getUrl();
        var mirror = repos.getMirror().get(mirrorKey);
        return selectedImage.image.getUrl(mirror);
    }

    @NonNull
    private String getSelectedMirrorKey() {
        if (mirrorKeys == null) return "official";
        var label = dropdownMirror.getText();
        for (int i = 0; i < mirrorLabels.length; i++)
            if (mirrorLabels[i].equals(label))
                return mirrorKeys[i];
        return "official";
    }

    private void doDownload() {
        if (isDownloading) return;
        if (selectedImage == null) {
            Toast.makeText(this, R.string.images_error_no_image, LENGTH_SHORT).show();
            return;
        }
        var name = inputFilename.getText();
        if (name.isEmpty()) {
            inputFilename.setError(getString(R.string.images_error_name_empty));
            return;
        }
        inputFilename.setError(null);
        var folder = inputFolder.getText();
        if (folder.isEmpty()) {
            inputFolder.setError(getString(R.string.images_error_folder_empty));
            return;
        }
        inputFolder.setError(null);
        var destPath = pathJoin(folder, name);
        if (new File(destPath).exists()) {
            inputFilename.setError(getString(R.string.images_error_file_exists));
            return;
        }
        downloadName = name;
        downloadFolder = folder;
        var url = resolveDownloadUrl();
        startDownload(url, destPath);
    }

    private void startDownload(String url, String destPath) {
        isDownloading = true;
        setInputsEnabled(false);
        fabDownload.setVisibility(GONE);
        downloadWidget.setVisibility(VISIBLE);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        downloadWidget.setOnDownloadListener(this);
        downloadWidget.setUserAgent(DL_USER_AGENT);
        downloadWidget.start(url, destPath);
    }

    @Override
    public void onFinished(DownloadWidget widget, File file) {
        var config = new DiskConfig();
        config.setName(downloadName);
        config.item.set("folder", downloadFolder);
        Runnable done = () -> {
            var resultData = new Intent();
            resultData.putExtra("result_disk_path", config.getFullPath());
            setResult(RESULT_OK, resultData);
            if (config.getFormat() == DiskFormat.QCOW2)
                startOptimize(this, config.getId());
            finish();
        };
        runOnPool(() -> {
            var store = new DiskStore();
            store.load(this);
            store.add(config);
            store.save(this);
            runOnUiThread(done);
        });
        Toast.makeText(this,
            getString(R.string.images_success, downloadName),
            LENGTH_SHORT
        ).show();
    }

    @Override
    public void onFailed(DownloadWidget widget, String error) {
        isDownloading = false;
        setInputsEnabled(true);
        fabDownload.setVisibility(VISIBLE);
    }

    @Override
    public void onCancelled(DownloadWidget widget) {
        isDownloading = false;
        setInputsEnabled(true);
        fabDownload.setVisibility(VISIBLE);
    }

    private void setInputsEnabled(boolean enabled) {
        cardPlaceholder.setEnabled(enabled);
        cardSelected.setEnabled(enabled);
        dropdownMirror.setEnabled(enabled);
        inputFilename.setEnabled(enabled);
        inputFolder.setEnabled(enabled);
    }
}
