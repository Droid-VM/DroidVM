package cn.classfun.droidvm.ui.widgets.tools;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.CopyableField;
import cn.classfun.droidvm.lib.ui.IconItemAdapter;
import cn.classfun.droidvm.ui.vm.boot.BootEntries;
import cn.classfun.droidvm.ui.widgets.row.DropdownRowWidget;

/**
 * A drop-in "kernel analysis" card for the import screens. The user picks an
 * image (a URL); pressing Analyze runs a single cloud analysis
 * ({@code lbx entries <url> --json}, via the daemon's {@code vm_bootscan})
 * and the card shows, per boot entry, the kernel / initrd / cmdline and the
 * protected-VM DMA warning — the same read-only view the VM edit screen
 * gives a local disk image.
 *
 * <p>One analysis is one lbx call that returns every entry with every field,
 * so there's nothing to cache. The host wires a {@link UrlProvider} (where to
 * read the current URL) and calls {@link #reset()} whenever that URL changes.
 */
public final class KernelAnalysisWidget extends FrameLayout {

    /** Supplies the http(s) URL to analyse, or {@code null} if none yet. */
    public interface UrlProvider {
        @Nullable
        String getUrl();
    }

    /** lbx per-connection timeout: a stalled chunk download aborts this fast. */
    private static final int CHUNK_TIMEOUT_SECS = 8;

    private final Handler main = new Handler(Looper.getMainLooper());

    private MaterialButton btnAnalyze;
    private CircularProgressIndicator progress;
    private TextView tvStatus;
    private TextView tvDmaWarn;
    private View groupResults;
    private DropdownRowWidget ddEntry;
    private TextInputEditText etKernel;
    private TextInputEditText etInitrd;
    private TextInputEditText etCmdline;

    @Nullable
    private UrlProvider urlProvider;
    @Nullable
    private BootEntries scanned;
    private boolean scanning = false;

    public KernelAnalysisWidget(@NonNull Context context) {
        super(context);
        init();
    }

    public KernelAnalysisWidget(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.view_kernel_analysis, this, true);
        btnAnalyze = findViewById(R.id.btn_analyze);
        progress = findViewById(R.id.progress_analysis);
        tvStatus = findViewById(R.id.tv_analysis_status);
        tvDmaWarn = findViewById(R.id.tv_analysis_dma_warn);
        groupResults = findViewById(R.id.group_analysis_results);
        ddEntry = findViewById(R.id.dd_analysis_entry);
        etKernel = findViewById(R.id.et_analysis_kernel);
        etInitrd = findViewById(R.id.et_analysis_initrd);
        etCmdline = findViewById(R.id.et_analysis_cmdline);
        // Cloud-analysis fields are read-only: selectable (long-press Copy) with
        // a copy end-icon, never editable.
        var ctx = getContext();
        CopyableField.setupReadOnly(etKernel, ctx.getString(R.string.edit_vm_boot_detect_kernel));
        CopyableField.setupReadOnly(etInitrd, ctx.getString(R.string.edit_vm_boot_detect_initrd));
        CopyableField.setupReadOnly(etCmdline, ctx.getString(R.string.edit_vm_boot_detect_cmdline));
        btnAnalyze.setOnClickListener(v -> analyze());
    }

    public void setUrlProvider(@Nullable UrlProvider provider) {
        this.urlProvider = provider;
    }

    /** Clear any shown result; call when the source URL/selection changes. */
    public void reset() {
        if (scanning) return;
        scanned = null;
        groupResults.setVisibility(GONE);
        tvDmaWarn.setVisibility(GONE);
        setStatus(getContext().getString(R.string.kernel_analysis_hint), false);
    }

    private void analyze() {
        if (scanning) return;
        var url = urlProvider != null ? urlProvider.getUrl() : null;
        url = url != null ? url.trim() : "";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            setStatus(getContext().getString(R.string.kernel_analysis_need_url), true);
            return;
        }
        scanning = true;
        progress.setVisibility(VISIBLE);
        btnAnalyze.setEnabled(false);
        groupResults.setVisibility(GONE);
        tvDmaWarn.setVisibility(GONE);
        setStatus(getContext().getString(R.string.edit_vm_boot_detect_scanning), false);
        BootEntries.analyzeUrl(getContext(), url, CHUNK_TIMEOUT_SECS,
            (loaded, total) -> main.post(() -> onProgress(loaded, total)),
            (result, error) -> main.post(() -> onScan(result, error)));
    }

    private void onProgress(int loaded, int total) {
        if (!scanning) return;
        setStatus(total > 0
            ? getContext().getString(R.string.kernel_analysis_progress, loaded, total)
            : getContext().getString(R.string.kernel_analysis_progress_n, loaded), false);
    }

    private void onScan(@Nullable BootEntries result, @Nullable String error) {
        scanning = false;
        progress.setVisibility(GONE);
        btnAnalyze.setEnabled(true);
        if (result == null || result.entries.isEmpty()) {
            scanned = null;
            groupResults.setVisibility(GONE);
            tvDmaWarn.setVisibility(GONE);
            setStatus(getContext().getString(
                R.string.edit_vm_boot_detect_failed,
                error != null ? error : "no entries"), true);
            return;
        }
        scanned = result;
        var labels = new ArrayList<String>();
        for (var e : result.entries) labels.add(e.displayLabel(getContext()));
        ddEntry.setAdapter(IconItemAdapter.create(getContext(), labels, R.drawable.ic_linux));
        ddEntry.setOnItemClickListener((p, v, pos, id) -> showEntry(pos));
        int def = defaultIndex(result);
        ddEntry.setText(labels.get(def));
        groupResults.setVisibility(VISIBLE);
        setStatus(getContext().getString(
            R.string.kernel_analysis_found, result.entries.size()), false);
        showEntry(def);
    }

    private static int defaultIndex(@NonNull BootEntries entries) {
        for (int i = 0; i < entries.entries.size(); i++)
            if (entries.entries.get(i).isDefault) return i;
        return 0;
    }

    private void showEntry(int pos) {
        if (scanned == null || pos < 0 || pos >= scanned.entries.size()) return;
        var e = scanned.entries.get(pos);
        etKernel.setText(e.kernel);
        etInitrd.setText(String.join(", ", e.initrd));
        // vdafix is applied on a real boot, so preview the bootable cmdline.
        // No root= highlighting here: with no vdafix toggle to contrast
        // against, the rewrite markup would have nothing to mean.
        etCmdline.setText(e.effectiveCmdline(true));
        // Imported images become protected VMs, so warn unconditionally when
        // the kernel can't do restricted-pool DMA (same text as the editor).
        if (e.lacksRestrictedDmaPool()) {
            tvDmaWarn.setText(R.string.edit_vm_boot_protected_dma_warn);
            tvDmaWarn.setVisibility(VISIBLE);
        } else {
            tvDmaWarn.setVisibility(GONE);
        }
    }

    private void setStatus(@NonNull String message, boolean error) {
        tvStatus.setText(message);
        tvStatus.setTextColor(MaterialColors.getColor(this, error
            ? androidx.appcompat.R.attr.colorError
            : com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvStatus.setVisibility(VISIBLE);
    }
}
