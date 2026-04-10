package cn.classfun.droidvm.ui.disk.images;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.data.Images;
import cn.classfun.droidvm.ui.disk.images.FlatImage.FlatImages;

public final class ImagePickerDialog {
    private static final long DEBOUNCE_MS = 200;
    private final Context context;
    private final FlatImages allImages = new FlatImages();
    private final FlatImages filteredImages = new FlatImages();
    private final Consumer<FlatImage> onPicked;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger filterGeneration = new AtomicInteger(0);
    private ImageAdapter adapter;
    private TextView tvEmpty;
    private RecyclerView recyclerImages;
    private ProgressBar progressLoading;
    private AlertDialog dialog;
    private String pendingQuery = "";
    private final Runnable debounceRunnable = () -> scheduleFilter(pendingQuery);

    public ImagePickerDialog(@NonNull Context context, @NonNull Consumer<FlatImage> onPicked) {
        this.context = context;
        this.onPicked = onPicked;
    }

    public void show() {
        var view = LayoutInflater.from(context).inflate(R.layout.dialog_image_picker, null);
        var editSearch = (TextInputEditText) view.findViewById(R.id.edit_search);
        tvEmpty = view.findViewById(R.id.tv_empty);
        recyclerImages = view.findViewById(R.id.recycler_images);
        progressLoading = view.findViewById(R.id.progress_loading);
        adapter = new ImageAdapter(filteredImages, this::onItemClicked);
        recyclerImages.setLayoutManager(new LinearLayoutManager(context));
        recyclerImages.setAdapter(adapter);
        recyclerImages.setHasFixedSize(true);
        progressLoading.setVisibility(VISIBLE);
        tvEmpty.setVisibility(GONE);
        recyclerImages.setVisibility(GONE);
        dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.images_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                pendingQuery = s != null ? s.toString().trim() : "";
                mainHandler.removeCallbacks(debounceRunnable);
                if (!allImages.isEmpty())
                    mainHandler.postDelayed(debounceRunnable, DEBOUNCE_MS);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        runOnPool(this::loadAllImagesAsync);
    }

    private void loadAllImagesAsync() {
        var images = Images.load(context);
        if (images != null) {
            allImages.load(images);
            allImages.sort();
        }
        mainHandler.post(() -> {
            progressLoading.setVisibility(GONE);
            scheduleFilter(pendingQuery);
        });
    }

    private void scheduleFilter(@NonNull String query) {
        var q = query.toLowerCase(Locale.ROOT);
        var gen = filterGeneration.incrementAndGet();
        runOnPool(() -> {
            var result = new ArrayList<FlatImage>();
            for (var fi : allImages) {
                if (q.isEmpty() || fi.matchesQuery(q))
                    result.add(fi);
            }
            if (filterGeneration.get() != gen) return;
            mainHandler.post(() -> {
                if (filterGeneration.get() != gen) return;
                filteredImages.clear();
                filteredImages.addAll(result);
                adapter.onChanged();
                tvEmpty.setVisibility(filteredImages.isEmpty() ? VISIBLE : GONE);
                recyclerImages.setVisibility(filteredImages.isEmpty() ? GONE : VISIBLE);
            });
        });
    }

    private void onItemClicked(@NonNull FlatImage fi) {
        showConfirmDialog(fi);
    }

    @SuppressWarnings("StringBufferReplaceableByString")
    private void showConfirmDialog(@NonNull FlatImage fi) {
        var sb = new StringBuilder();
        sb.append(context.getString(R.string.images_confirm_repo, fi.displayRepo)).append("\n");
        sb.append(context.getString(R.string.images_confirm_path, fi.image.getPath())).append("\n");
        sb.append(context.getString(R.string.images_confirm_arch, fi.image.getArch())).append("\n");
        sb.append(context.getString(R.string.images_confirm_size, formatSize(fi.image.getSize()))).append("\n");
        sb.append(context.getString(R.string.images_confirm_date, fi.displayDate));
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.images_confirm_title)
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                if (dialog != null) dialog.dismiss();
                onPicked.accept(fi);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
