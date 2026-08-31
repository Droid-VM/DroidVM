// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.graphics;

import static android.content.DialogInterface.BUTTON_POSITIVE;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static java.lang.Integer.parseInt;
import static cn.classfun.droidvm.lib.utils.StringUtils.generateRandomPassword;
import static cn.classfun.droidvm.lib.utils.StringUtils.getEditText;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.AutoCompleteTextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.IconItemAdapter;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.DisplayExporter;
import cn.classfun.droidvm.lib.store.vm.DisplayTransportCap;
import cn.classfun.droidvm.lib.store.vm.VMScreenConfig;
import cn.classfun.droidvm.ui.widgets.row.ChooseRowWidget;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextRowWidget;

/**
 * One screen's rows in the graphics tab: the switch that says the VM has the device, how big that
 * screen is and how often it produces a picture, and who exports it over what.
 *
 * <p>The two screens have the same shape, so the wiring is written once here and the layout
 * carries one block of ids per screen. The differences are both in "display settings" and both
 * follow from what the device is: a virtio-gpu mode has a refresh rate and a DPI the guest is told
 * about, and a framebuffer nobody announces frames for has a poll rate instead. Neither has the
 * other's, so exactly one of the two is shown.</p>
 *
 * <p>The enable switch is passed in rather than found inside the block, because the renderer
 * section sits between it and everything else on the virtio-gpu side, and an {@code <include>} has
 * nowhere to put a section in the middle of itself.</p>
 */
final class ScreenBindingRow {
    private static final int VNC_PASSWORD_LENGTH = 8;
    /** The size bounds the editor saves within; the menu and its dialog both honour them. */
    private static final int MIN_EDGE = ScreenResolutionOptions.MIN_EDGE;
    private static final int MAX_EDGE = 8192;
    /** A mode's rate is bounded by what a panel could show; the poll rate by what crosvm takes. */
    private static final int MAX_REFRESH_RATE = 400;
    /** The rates offered before "custom". Both screens take all three. */
    private static final int[] RATE_OPTIONS = {30, 60, 120};

    /** Screen id, as stored and as handed to crosvm's {@code screen=}. */
    final String screenId;
    /** True for {@code gpu-0}: it has a mode, so it has a refresh rate and a DPI. */
    private final boolean gpuScreen;
    private final boolean defaultEnabled;
    private final DisplayExporter defaultExporter;

    private final SwitchRowWidget swEnabled;
    private final View options;
    private final TextInputLayout tilResolution;
    private final AutoCompleteTextView ddResolution;
    private final TextInputLayout tilRate;
    private final AutoCompleteTextView ddRate;
    private final TextRowWidget rowWidthCpuFallback;
    private final View dpiOptions;
    private final TextInputEditText etDpiH;
    private final TextInputEditText etDpiV;
    private final ChooseRowWidget chooseExporter;
    private final ChooseRowWidget chooseTransport;
    private final SwitchRowWidget swInputEnabled;
    private final View vncOptions;
    private final TextInputEditText etHost;
    private final TextInputEditText etPort;
    private final SwitchRowWidget swPasswordAuth;
    private final View passwordOptions;
    private final TextInputEditText etPassword;

    /**
     * The geometry as picked, rather than as typed.
     *
     * <p>It lives here because the two fields that show it are menus now: what a menu carries is a
     * label, and the label is written from these numbers rather than parsed back out of. What
     * reaches the config is unchanged -- the same three numbers under the same keys -- so a config
     * written before this row was a menu still loads into it.</p>
     */
    private int width = (int) VMScreenConfig.DEFAULT_WIDTH;
    private int height = (int) VMScreenConfig.DEFAULT_HEIGHT;
    private int rate;
    /** The sizes this device offers, settled once in {@link #init}. */
    @NonNull
    private List<ScreenResolutionOptions.Option> sizes = List.of();

    /**
     * @param block  the root of this screen's {@code partial_vm_screen_binding} include, and also
     *               the view whose visibility follows the switch. Every lookup below is scoped to
     *               it, which is what keeps the two includes' identical ids apart -- an
     *               activity-wide findViewById would always find the first.
     * @param switch_ this screen's enable switch, which lives in the parent block.
     */
    ScreenBindingRow(@NonNull String screenId, @NonNull View block,
                     @NonNull SwitchRowWidget switch_,
                     boolean defaultEnabled, @NonNull DisplayExporter defaultExporter) {
        this.screenId = screenId;
        this.gpuScreen = VMScreenConfig.ID_GPU0.equals(screenId);
        this.defaultEnabled = defaultEnabled;
        this.defaultExporter = defaultExporter;
        swEnabled = switch_;
        options = block;
        tilResolution = block.findViewById(R.id.til_screen_resolution);
        ddResolution = block.findViewById(R.id.dd_screen_resolution);
        tilRate = block.findViewById(R.id.til_screen_rate);
        ddRate = block.findViewById(R.id.dd_screen_rate);
        rowWidthCpuFallback = block.findViewById(R.id.row_screen_width_cpu_fallback);
        dpiOptions = block.findViewById(R.id.screen_dpi_options);
        etDpiH = block.findViewById(R.id.et_screen_dpi_h);
        etDpiV = block.findViewById(R.id.et_screen_dpi_v);
        chooseExporter = block.findViewById(R.id.choose_screen_exporter);
        chooseTransport = block.findViewById(R.id.choose_screen_transport);
        swInputEnabled = block.findViewById(R.id.sw_screen_input_enabled);
        vncOptions = block.findViewById(R.id.screen_vnc_options);
        etHost = block.findViewById(R.id.et_screen_vnc_host);
        etPort = block.findViewById(R.id.et_screen_vnc_port);
        swPasswordAuth = block.findViewById(R.id.sw_screen_vnc_password_auth);
        passwordOptions = block.findViewById(R.id.screen_vnc_password_options);
        etPassword = block.findViewById(R.id.et_screen_vnc_password);
        MaterialButton btnClear = block.findViewById(R.id.btn_screen_vnc_password_clear);
        MaterialButton btnGenerate = block.findViewById(R.id.btn_screen_vnc_password_generate);
        btnClear.setOnClickListener(v -> etPassword.setText(""));
        btnGenerate.setOnClickListener(v ->
            etPassword.setText(generateRandomPassword(VNC_PASSWORD_LENGTH)));
        // Which rate this screen has -- and whether it has a DPI at all -- is a property of the
        // device, not of anything the user can change, so both are settled once here rather than
        // in the visibility pass. One rate row either way; only its hint and its bounds differ.
        rate = (int) (gpuScreen
            ? VMScreenConfig.DEFAULT_REFRESH_RATE : VMScreenConfig.NEW_VM_DEFAULT_POLL_HZ);
        tilRate.setHint(block.getContext().getString(rateHint()));
        dpiOptions.setVisibility(gpuScreen ? VISIBLE : GONE);
    }

    /**
     * Wires the listeners. {@code onChanged} runs after every change that another row can depend
     * on -- the tab re-runs its whole visibility pass there rather than each row guessing what
     * else moved.
     */
    void init(@NonNull Runnable onChanged) {
        // Off is a real choice: a screen the VM has but nobody is watching is a state crosvm
        // accepts, not a half-configured one. The default is what a brand-new VM comes up with,
        // since only edit mode ever calls load().
        chooseExporter.configure(DisplayExporter.class, defaultExporter);
        applyTransportOptions(defaultExporter, null);
        swEnabled.setChecked(defaultEnabled);
        // The absolute devices are what a screen has unless the user says otherwise, so a new VM
        // and a config written before the key both come up with them on.
        swInputEnabled.setChecked(true);
        swEnabled.setOnCheckedChangeListener(onChanged);
        // The width decides whether the GPU copy can take this screen's frames at all, so picking
        // a size is one of the changes another row depends on -- the only geometry one that is.
        // Through the tab's whole pass like every other change, rather than poking the one row it
        // moves.
        bindSizeMenu(onChanged);
        bindRateMenu(onChanged);
        chooseExporter.setOnValueChangedListener(() -> {
            // The ladder belongs to the edge, so changing who is on the far end of it changes
            // which rungs exist -- not just which are reachable.
            applyTransportOptions(getExporter(), null);
            onChanged.run();
        });
        swPasswordAuth.setOnCheckedChangeListener(onChanged);
    }

    /**
     * The size menu: every size this device has a reason to offer, smallest first, then "custom".
     *
     * <p>Picking is the whole of the input path now, which is what turns the geometry check into a
     * check on a stored value rather than on something half-typed: every entry above the last is
     * in bounds by construction, and the last one validates before it hands anything back.</p>
     */
    private void bindSizeMenu(@NonNull Runnable onChanged) {
        var ctx = ddResolution.getContext();
        var panel = panelSize(ctx);
        sizes = ScreenResolutionOptions.build(panel[0], panel[1]);
        var labels = new ArrayList<String>(sizes.size() + 1);
        for (var size : sizes) labels.add(sizeLabel(ctx, size.width, size.height));
        labels.add(ctx.getString(R.string.create_vm_display_custom));
        ddResolution.setAdapter(IconItemAdapter.create(ctx, labels, R.drawable.ic_monitor));
        ddResolution.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos < sizes.size()) {
                var size = sizes.get(pos);
                width = size.width;
                height = size.height;
                tilResolution.setError(null);
                onChanged.run();
            } else {
                askCustomSize(ctx, onChanged);
            }
            // The menu wrote the entry's own label into the field on its way out. Put the value
            // back: after "custom" it would otherwise sit there reading "Custom..." while the
            // dialog is still open, and after a pick the label is this row's to format.
            applySizeText();
        });
        applySizeText();
    }

    /** The rate menu. Same shape as the size menu, and the same reason for it. */
    private void bindRateMenu(@NonNull Runnable onChanged) {
        var ctx = ddRate.getContext();
        var labels = new ArrayList<String>(RATE_OPTIONS.length + 1);
        for (var hz : RATE_OPTIONS) labels.add(rateLabel(ctx, hz));
        labels.add(ctx.getString(R.string.create_vm_display_custom));
        ddRate.setAdapter(IconItemAdapter.create(ctx, labels, R.drawable.ic_speedometer));
        ddRate.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos < RATE_OPTIONS.length) {
                rate = RATE_OPTIONS[pos];
                tilRate.setError(null);
                onChanged.run();
            } else {
                askCustomRate(ctx, onChanged);
            }
            applyRateText();
        });
        applyRateText();
    }

    /** How a size reads, whether the menu offers it, a dialog produced it or a config carried it. */
    @NonNull
    private static String sizeLabel(@NonNull Context ctx, int w, int h) {
        return ctx.getString(R.string.create_vm_display_size_fmt, w, h);
    }

    @NonNull
    private static String rateLabel(@NonNull Context ctx, int hz) {
        return ctx.getString(R.string.create_vm_display_rate_fmt, hz);
    }

    private void applySizeText() {
        ddResolution.setText(sizeLabel(ddResolution.getContext(), width, height), false);
    }

    private void applyRateText() {
        ddRate.setText(rateLabel(ddRate.getContext(), rate), false);
    }

    /** The size menu's last entry: two numbers, checked against the bounds the editor saves in. */
    private void askCustomSize(@NonNull Context ctx, @NonNull Runnable onChanged) {
        var view = LayoutInflater.from(ctx).inflate(R.layout.dialog_screen_resolution, null);
        TextInputEditText etW = view.findViewById(R.id.et_custom_width);
        TextInputEditText etH = view.findViewById(R.id.et_custom_height);
        etW.setText(String.valueOf(width));
        etH.setText(String.valueOf(height));
        var dialog = new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.create_vm_display_custom_size_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .show();
        // Wired after show() so a refused value can stay on screen with its error: the listener
        // setPositiveButton takes dismisses the dialog whatever it decides.
        dialog.getButton(BUTTON_POSITIVE).setOnClickListener(v -> {
            var w = bounded(etW, MIN_EDGE, MAX_EDGE);
            var h = bounded(etH, MIN_EDGE, MAX_EDGE);
            if (w == 0 || h == 0) return;
            width = w;
            height = h;
            tilResolution.setError(null);
            applySizeText();
            onChanged.run();
            dialog.dismiss();
        });
    }

    /** The rate menu's last entry, bounded by whichever rate this screen has. */
    private void askCustomRate(@NonNull Context ctx, @NonNull Runnable onChanged) {
        var view = LayoutInflater.from(ctx).inflate(R.layout.dialog_screen_rate, null);
        TextInputLayout til = view.findViewById(R.id.til_custom_rate);
        TextInputEditText etRate = view.findViewById(R.id.et_custom_rate);
        til.setHint(ctx.getString(rateHint()));
        etRate.setText(String.valueOf(rate));
        var dialog = new MaterialAlertDialogBuilder(ctx)
            .setTitle(rateHint())
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .show();
        dialog.getButton(BUTTON_POSITIVE).setOnClickListener(v -> {
            var hz = bounded(etRate, rateMin(), rateMax());
            if (hz == 0) return;
            rate = hz;
            tilRate.setError(null);
            applyRateText();
            onChanged.run();
            dialog.dismiss();
        });
    }

    /**
     * The number typed into a dialog field, or 0 with the reason shown on the field -- the same
     * reading, bounds and message the tab applies to every other number on this screen, since the
     * value is bound for the same config.
     */
    private static int bounded(@NonNull TextInputEditText field, int min, int max) {
        field.setError(null);
        try {
            var value = parseInt(getEditText(field));
            if (value < min || value > max) throw new IllegalArgumentException();
            return value;
        } catch (Exception ignored) {
            field.setError(field.getContext().getString(R.string.create_vm_error_invalid_number));
            return 0;
        }
    }

    @StringRes
    private int rateHint() {
        return gpuScreen
            ? R.string.create_vm_display_refresh_rate : R.string.create_vm_display_poll_hz;
    }

    private int rateMin() {
        return gpuScreen ? 1 : (int) VMScreenConfig.MIN_POLL_HZ;
    }

    private int rateMax() {
        return gpuScreen ? MAX_REFRESH_RATE : (int) VMScreenConfig.MAX_POLL_HZ;
    }

    /** The panel this app is running on, in whatever rotation it is held, or 0x0 if it cannot be
     * asked -- which offers the fixed sizes alone rather than inventing a device. */
    @NonNull
    private static int[] panelSize(@NonNull Context ctx) {
        var wm = ctx.getSystemService(WindowManager.class);
        if (wm == null) return new int[]{0, 0};
        var bounds = wm.getMaximumWindowMetrics().getBounds();
        return new int[]{bounds.width(), bounds.height()};
    }

    /**
     * Rebuilds the transport menu for [exporter] and settles on a value.
     *
     * <p>Three things decide the menu, and all of them are the edge's rather than this row's: the
     * rungs this (screen, exporter) pair has at all, which of them this build can honour, and what
     * the highest honourable one is. Rungs that exist but are not built yet are listed and refused
     * with a note, so the ladder reads whole; rungs that cannot exist on this pair are absent,
     * because offering a choice nobody will ever be able to make is worse than not naming it.</p>
     *
     * @param want the stored ceiling to restore on load, if this edge still offers it; or null on
     *             a fresh row or an exporter switch, which re-evaluates to the edge's default
     *             instead of carrying the old pick across. Switching exporter is switching which
     *             ladder this is, and each ladder's fastest rung is a different one, so the pick
     *             follows the new edge rather than lingering on a value that was only best for the
     *             old one. A restore that the edge no longer offers also falls back to the default.
     */
    private void applyTransportOptions(@NonNull DisplayExporter exporter,
                                       @Nullable DisplayTransportCap want) {
        // No exporter, no edge, no ladder. The row is hidden in that state; leave the menu as it
        // was rather than emptying it, since the picker refuses an empty item list outright.
        var options = DisplayTransportCap.optionsFor(screenId, exporter);
        if (options.length == 0) return;
        chooseTransport.setItems(options);
        transportMenuBuilt = true;
        chooseTransport.setDisabledItems(
            chooseTransport.getContext().getString(
                R.string.create_vm_option_not_implemented),
            DisplayTransportCap.unimplementedFor(screenId, exporter));
        // want != null is a load: restore the stored ceiling if this edge still offers it. want ==
        // null is a fresh row or an exporter switch, and then the ceiling is re-evaluated to the
        // new edge's default rather than carried over -- the fastest rung differs per exporter
        // (GPU_HW on VNC, plain GPU on native), so carrying the old pick left a screen switched to
        // VNC sitting at plain GPU copy when the hardware-encode rung is what it should default to.
        var pick = want != null && DisplayTransportCap.isOfferedFor(screenId, exporter, want)
            ? want : DisplayTransportCap.defaultFor(screenId, exporter);
        chooseTransport.setSelectedItem(pick);
    }

    /** Set by the first applyTransportOptions; the picker cannot be read before it. */
    private boolean transportMenuBuilt = false;

    /** The transport currently selected, or null before the menu has ever been built. */
    @Nullable
    private DisplayTransportCap currentTransport() {
        // Asking the widget before its first setItems is not an IllegalStateException, it is an
        // NPE from deep inside (ChooseRowWidget.getSelectedItem -> getPicker() on nothing) --
        // which is exactly how the editor crashed on first open while every unit test, none of
        // which inflates the real widget, stayed green. Track the state ourselves instead of
        // classifying the widget's failure modes.
        if (!transportMenuBuilt) return null;
        return chooseTransport.getSelectedItem();
    }

    boolean isScreenEnabled() {
        return swEnabled.isChecked();
    }

    /**
     * Whether this row currently describes a binding the host might blit for -- the same question
     * the daemon asks the stored config before it names a blit driver, asked of the live widgets
     * so the row that names that driver appears while the user is still choosing.
     */
    boolean isGpuBlitBinding() {
        var transport = currentTransport();
        return swEnabled.isChecked() && transport != null
            && VMScreenConfig.isGpuBlitBinding(getExporter(), transport);
    }

    @NonNull
    DisplayExporter getExporter() {
        return chooseExporter.getSelectedItem();
    }

    void updateVisibility() {
        var enabled = swEnabled.isChecked();
        var exporter = getExporter();
        options.setVisibility(enabled ? VISIBLE : GONE);
        // No exporter, no edge for a transport to run along, so the row would be a ceiling on
        // nothing. Its value is kept while hidden, like the VNC block's.
        chooseTransport.setVisibility(
            enabled && exporter != DisplayExporter.NONE ? VISIBLE : GONE);
        vncOptions.setVisibility(
            enabled && exporter == DisplayExporter.VNC ? VISIBLE : GONE);
        passwordOptions.setVisibility(swPasswordAuth.isChecked() ? VISIBLE : GONE);
        // The one thing the transport ceiling cannot promise: a width whose stride the blit's
        // dma-buf import will not take settles a rung lower, silently, and the only clue is a line
        // in the console. Say so beside the field that causes it. Nothing is refused and nothing is
        // rounded -- the ceiling is honoured either way, it just lands on the CPU copy.
        var transport = currentTransport();
        rowWidthCpuFallback.setVisibility(
            enabled && transport != null && DisplayTransportCap.cpuFallbackFromWidth(
                screenId, exporter, transport, width) ? VISIBLE : GONE);
    }

    /**
     * Whether the geometry this row holds is one the VM can be saved with, said on the row that
     * holds it.
     *
     * <p>Nothing the two menus produce can fail this: every size and rate they offer is in bounds,
     * and their dialogs check before they return. What it catches is a config that arrived out of
     * bounds -- hand-edited, or written where the limits were different -- and it reports on a row
     * that is on screen, because a save refused for a reason the screen does not show is a VM the
     * user cannot fix.</p>
     */
    boolean validateGeometry() {
        tilResolution.setError(null);
        tilRate.setError(null);
        var ctx = tilResolution.getContext();
        if (width < MIN_EDGE || width > MAX_EDGE || height < MIN_EDGE || height > MAX_EDGE) {
            tilResolution.setError(ctx.getString(R.string.create_vm_error_invalid_number));
            return false;
        }
        if (rate < rateMin() || rate > rateMax()) {
            tilRate.setError(ctx.getString(R.string.create_vm_error_invalid_number));
            return false;
        }
        return true;
    }

    void load(@NonNull DataItem config) {
        var screen = VMScreenConfig.find(config, screenId);
        if (screen == null) {
            // No entry at all: the screen has never been configured, so the row answers with
            // what a new one gets rather than with the sentinel for "watched by nobody".
            swEnabled.setChecked(false);
            chooseExporter.setSelectedItem(defaultExporter);
            applyTransportOptions(defaultExporter, null);
            return;
        }
        swEnabled.setChecked(screen.isEnabled());
        chooseExporter.setSelectedItem(screen.getExporter());
        applyTransportOptions(screen.getExporter(), screen.getTransportCap());
        // Absent in the stored config means on, so an existing VM keeps the devices it had
        // without its file being rewritten to say so.
        swInputEnabled.setChecked(screen.isInputEnabled());
        width = (int) screen.getWidth();
        height = (int) screen.getHeight();
        applySizeText();
        if (gpuScreen) {
            rate = (int) screen.getRefreshRate();
            etDpiH.setText(String.valueOf(screen.getDpiH()));
            etDpiV.setText(String.valueOf(screen.getDpiV()));
        } else {
            rate = (int) screen.getPollHz();
        }
        applyRateText();
        etHost.setText(screen.getVncHost());
        var port = screen.getVncPort();
        etPort.setText(port > 0 ? String.valueOf(port) : "");
        swPasswordAuth.setChecked(screen.isVncPasswordAuth());
        etPassword.setText(screen.getVncPassword());
    }

    void save(@NonNull DataItem config) {
        var screen = VMScreenConfig.of(config, screenId);
        // The switch stores the switch, and nothing else. It used to also write NONE over the
        // exporter of a screen it was turning off, which read back as a choice the next time the
        // editor opened: turning the device on again showed it bound to nobody, with the pick the
        // user had made gone. Every reader already asks whether a screen is on before asking what
        // it is bound to -- directly (isInputBridgeNeeded, buildScreenExportersCommand) or through
        // a filter that does (boundOf, hasAbsoluteInput) -- so "off" needs no second spelling in
        // the fields underneath, the same way the geometry below has never needed one.
        var exporter = getExporter();
        screen.setEnabled(swEnabled.isChecked());
        screen.setExporter(exporter);
        // Written whatever the exporter is, so a rung picked under one exporter is still there
        // after a detour through another -- and so a rung that is refused today is remembered for
        // the build that can honour it.
        var transport = currentTransport();
        if (transport != null) screen.setTransportCap(transport);
        screen.setInputEnabled(swInputEnabled.isChecked());
        // The geometry is written whether or not the screen is on, for the same reason the VNC
        // block below keeps its values: a size typed once and then switched off should still be
        // there when the switch comes back. Only the rows this screen actually has are written --
        // a poll rate on the GPU screen would be a number nothing reads.
        screen.setWidth(width);
        screen.setHeight(height);
        if (gpuScreen) {
            screen.setRefreshRate(rate);
            screen.setDpiH(parseInt(getEditText(etDpiH)));
            screen.setDpiV(parseInt(getEditText(etDpiV)));
        } else {
            screen.setPollHz(rate);
        }
        // The VNC block keeps its values even while hidden, so a screen switched to native and
        // back finds its port and password where it left them. Only the auth switch is written
        // from the live state, since it is what decides whether the password is used at all.
        if (exporter == DisplayExporter.VNC) {
            screen.setVncHost(getEditText(etHost));
            var portStr = getEditText(etPort);
            screen.setVncPort(portStr.isEmpty() ? -1 : parseInt(portStr));
            var auth = swPasswordAuth.isChecked();
            screen.setVncPasswordAuth(auth);
            if (auth) screen.setVncPassword(getEditText(etPassword));
        }
    }

    /** The port typed into this row, or -1 for "let the daemon pick one". */
    int typedVncPort() {
        return typedPort(etPort);
    }

    private static int typedPort(@NonNull TextInputEditText field) {
        try {
            var portStr = getEditText(field);
            return portStr.isEmpty() ? -1 : parseInt(portStr);
        } catch (Exception ignored) {
            return -1;
        }
    }

    @NonNull
    TextInputEditText portField() {
        return etPort;
    }

    @NonNull
    TextInputEditText dpiHField() {
        return etDpiH;
    }

    @NonNull
    TextInputEditText dpiVField() {
        return etDpiV;
    }

    boolean isGpuScreen() {
        return gpuScreen;
    }
}
