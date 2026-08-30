// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.hugepage;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.LinkedHashMap;
import java.util.Map;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.SimpleTextWatcher;
import cn.classfun.droidvm.ui.widgets.row.TextRowWidget;

/**
 * The advanced knobs: the settings.prop keys {@code load.sh} feeds the module at
 * insmod, which the main screen has no business guessing for the user.
 *
 * <p>Everything here is deliberately <b>raw</b>. Each row is labelled with the
 * parameter's own name, shows the settings.prop value, and carries the module's
 * current readback underneath. Nothing is renamed into friendlier language: the
 * only people who should be on this screen are reading the module's own
 * documentation, and a second vocabulary would just stand between them and it.
 *
 * <p>Showing both numbers is the point. They disagree while a saved value waits
 * for the next load, and also when the insmod ladder degraded past the rung that
 * carries that parameter - a single number would hide both cases.
 *
 * <p>All reads and writes go through {@link HugePageModel}; this screen never
 * touches sysfs itself.
 */
public final class HugePageAdvancedActivity extends AppCompatActivity {
    /**
     * The one key with no edit affordance. Lowering the reserve is how a phone is
     * made unstable, so its row stays a label and the editor sits behind a
     * deliberate gesture - anyone who reaches it went looking for it.
     */
    private static final String KEY_SYSTEM_RESERVE = "system_reserve_mb";
    private static final int RESERVE_TAPS = 10;
    /** Taps further apart than this start the count over ("consecutive"). */
    private static final long RESERVE_TAP_WINDOW_MS = 1500;

    private final HugePageModel model = new HugePageModel();
    /** Rows keyed by parameter name, in {@link HugePageModel#ADVANCED_KEYS} order. */
    private final Map<String, TextRowWidget> rows = new LinkedHashMap<>();
    /** Last read of the knobs, so opening an editor needs no shell I/O. */
    private Map<String, HugePageModel.Knob> knobs = Map.of();
    /** This device's default reserve (MB), or -1 when the module can't say. */
    private int reserveDefaultMb = -1;
    private int reserveTapCount;
    private long reserveLastTapMs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hugepage_advanced);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.hugepage_advanced);
        toolbar.setNavigationOnClickListener(v -> finish());

        rows.put(KEY_SYSTEM_RESERVE, findViewById(R.id.row_adv_system_reserve));
        rows.put("cma_reservoir_floor_mb", findViewById(R.id.row_adv_cma_floor));
        rows.put("boot_acquire", findViewById(R.id.row_adv_boot_acquire));
        rows.put("boot_acquire_runs", findViewById(R.id.row_adv_boot_acquire_runs));
        rows.put("boot_acquire_wait", findViewById(R.id.row_adv_boot_acquire_wait));
        for (var e : rows.entrySet()) {
            var key = e.getKey();
            e.getValue().setText(key);          // the raw name, never a translation
            if (KEY_SYSTEM_RESERVE.equals(key))
                e.getValue().setOnClickListener(v -> onReserveTap());
            else
                e.getValue().setOnClickListener(v -> showEditor(key));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    /**
     * Ten taps in a row open the {@code system_reserve_mb} editor, and nothing
     * before the tenth acknowledges them. The silence is the design: a stray
     * double-tap must not offer to shrink the reserve that keeps the phone alive.
     */
    private void onReserveTap() {
        var now = SystemClock.uptimeMillis();
        reserveTapCount = now - reserveLastTapMs > RESERVE_TAP_WINDOW_MS
            ? 1 : reserveTapCount + 1;
        reserveLastTapMs = now;
        if (reserveTapCount < RESERVE_TAPS) return;
        reserveTapCount = 0;
        showEditor(KEY_SYSTEM_RESERVE);
    }

    /** Re-read every knob: value = settings.prop, subtitle = what the module runs. */
    private void load() {
        runOnPool(() -> {
            var read = model.advancedKnobs();
            var def = model.systemReserveDefaultMb();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                knobs = read;
                reserveDefaultMb = def;
                for (var e : rows.entrySet()) {
                    var knob = read.get(e.getKey());
                    e.getValue().setValue(knob == null || knob.saved == null
                        ? getString(R.string.hugepage_adv_unset) : knob.saved);
                    e.getValue().setSubtitle(fmt("sysfs = %s",
                        knob == null || knob.live == null
                            ? getString(R.string.hugepage_adv_unavailable) : knob.live));
                }
            });
        });
    }

    /**
     * Edit one key: save a value, or clear it back to the module default. The
     * header repeats the module's own readback (plus, for the reserve, this
     * device's default) so the number being changed sits next to the number in
     * force. Values come from the last {@link #load()} - opening an editor must
     * not do shell I/O on the UI thread.
     */
    private void showEditor(@NonNull String key) {
        var view = getLayoutInflater().inflate(R.layout.dialog_hugepage_advanced, null);
        TextView liveView = view.findViewById(R.id.tv_adv_live);
        TextInputLayout til = view.findViewById(R.id.til_adv_value);
        EditText input = view.findViewById(R.id.et_adv_value);
        TextView warning = view.findViewById(R.id.tv_adv_warning);

        var knob = knobs.get(key);
        var unavailable = getString(R.string.hugepage_adv_unavailable);
        var header = new StringBuilder(fmt("sysfs = %s",
            knob == null || knob.live == null ? unavailable : knob.live));
        if (KEY_SYSTEM_RESERVE.equals(key))
            header.append(fmt("\nsystem_reserve_mb_default = %s", reserveDefaultMb < 0
                ? unavailable : Integer.toString(reserveDefaultMb)));
        liveView.setText(header);
        til.setHint(key);
        if (knob != null && knob.saved != null) {
            input.setText(knob.saved);
            input.setSelection(input.getText().length());
        }

        var dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(key)
            .setView(view)
            .setPositiveButton(R.string.hugepage_save_pool_size,
                (d, w) -> save(key, input.getText().toString().trim()))
            .setNeutralButton(R.string.hugepage_adv_clear, (d, w) -> save(key, null))
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> {
            var ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Runnable validate = () -> {
                var raw = input.getText().toString().trim();
                var value = parse(key, raw);
                ok.setEnabled(value != null);
                til.setError(value == null && !raw.isEmpty()
                    ? getString(R.string.hugepage_adv_invalid) : null);
                // Only the reserve has a "too low". The module hands back THIS
                // device's default, so the threshold is right even where RAM/2
                // already caps the built-in one and lowering to it is a no-op.
                var low = value != null && KEY_SYSTEM_RESERVE.equals(key)
                    && reserveDefaultMb > 0 && value < reserveDefaultMb;
                if (low)
                    warning.setText(getString(
                        R.string.hugepage_adv_warn_below_default, reserveDefaultMb));
                warning.setVisibility(low ? VISIBLE : GONE);
            };
            input.addTextChangedListener(new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(Editable e) {
                    validate.run();
                }
            });
            validate.run();
        });
        dialog.show();
    }

    /** Parse and range-check one knob; null = don't let it be saved. */
    @Nullable
    private static Long parse(@NonNull String key, @NonNull String raw) {
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        long min, max;
        switch (key) {
            case KEY_SYSTEM_RESERVE:
                min = 64;                 // the module's own floor
                max = 1024L * 1024;
                break;
            case "boot_acquire":
                min = 0;
                max = 3;                  // the acquire knob's modes
                break;
            case "boot_acquire_runs":
                min = 1;
                max = 99;
                break;
            case "boot_acquire_wait":
                min = 0;
                max = 3600;
                break;
            default:                      // cma_reservoir_floor_mb
                min = 0;
                max = 1024L * 1024;
        }
        return value < min || value > max ? null : value;
    }

    /** Persist one key ({@code null} clears it) and re-read what stuck. */
    private void save(@NonNull String key, @Nullable String value) {
        runOnPool(() -> {
            var res = model.saveAdvanced(key, value);
            runOnUiThread(() -> {
                Toast.makeText(this, res.ok()
                    ? R.string.hugepage_adv_saved
                    : R.string.hugepage_adv_save_failed, LENGTH_SHORT).show();
                load();
            });
        });
    }
}
