// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.graphics;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static java.lang.Integer.parseInt;
import static cn.classfun.droidvm.lib.ui.SimpleTextWatcher.simpleAfterTextWatcher;
import static cn.classfun.droidvm.lib.utils.StringUtils.generateRandomPassword;
import static cn.classfun.droidvm.lib.utils.StringUtils.getEditText;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import cn.classfun.droidvm.R;
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

    /** Screen id, as stored and as handed to crosvm's {@code screen=}. */
    final String screenId;
    /** True for {@code gpu-0}: it has a mode, so it has a refresh rate and a DPI. */
    private final boolean gpuScreen;
    private final boolean defaultEnabled;
    private final DisplayExporter defaultExporter;

    private final SwitchRowWidget swEnabled;
    private final View options;
    private final TextInputEditText etWidth;
    private final TextInputEditText etHeight;
    private final TextRowWidget rowWidthCpuFallback;
    private final View tilRefreshRate;
    private final TextInputEditText etRefreshRate;
    private final View tilPollHz;
    private final TextInputEditText etPollHz;
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
        etWidth = block.findViewById(R.id.et_screen_width);
        etHeight = block.findViewById(R.id.et_screen_height);
        rowWidthCpuFallback = block.findViewById(R.id.row_screen_width_cpu_fallback);
        tilRefreshRate = block.findViewById(R.id.til_screen_refresh_rate);
        etRefreshRate = block.findViewById(R.id.et_screen_refresh_rate);
        tilPollHz = block.findViewById(R.id.til_screen_poll_hz);
        etPollHz = block.findViewById(R.id.et_screen_poll_hz);
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
        // Which of the rate fields this screen has is a property of the device, not of anything
        // the user can change, so it is settled once here rather than in the visibility pass.
        tilRefreshRate.setVisibility(gpuScreen ? VISIBLE : GONE);
        tilPollHz.setVisibility(gpuScreen ? GONE : VISIBLE);
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
        // The width decides whether the GPU copy can take this screen's frames at all, so it is
        // one of the fields another row depends on -- the only geometry field that is. Through the
        // tab's whole pass like every other change, rather than poking the one row it moves.
        etWidth.addTextChangedListener(simpleAfterTextWatcher(s -> onChanged.run()));
        chooseExporter.setOnValueChangedListener(() -> {
            // The ladder belongs to the edge, so changing who is on the far end of it changes
            // which rungs exist -- not just which are reachable.
            applyTransportOptions(getExporter(), null);
            onChanged.run();
        });
        swPasswordAuth.setOnCheckedChangeListener(onChanged);
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
     * @param want the value to restore if this edge has it -- the stored one on load, or null to
     *             keep whatever is selected. Anything the edge does not have falls back to the
     *             default <em>on screen only</em>: the stored config is not rewritten, so a screen
     *             whose exporter was flipped to look and flipped back keeps its answer.
     */
    private void applyTransportOptions(@NonNull DisplayExporter exporter,
                                       @Nullable DisplayTransportCap want) {
        // No exporter, no edge, no ladder. The row is hidden in that state; leave the menu as it
        // was rather than emptying it, since the picker refuses an empty item list outright.
        var options = DisplayTransportCap.optionsFor(screenId, exporter);
        if (options.length == 0) return;
        var keep = want != null ? want : currentTransport();
        chooseTransport.setItems(options);
        chooseTransport.setDisabledItems(
            chooseTransport.getContext().getString(
                R.string.create_vm_screen_transport_not_implemented),
            DisplayTransportCap.unimplementedFor(screenId, exporter));
        var pick = keep != null && DisplayTransportCap.isOfferedFor(screenId, exporter, keep)
            ? keep : DisplayTransportCap.defaultFor(screenId, exporter);
        chooseTransport.setSelectedItem(pick);
    }

    /** The transport currently selected, or null before the menu has ever been built. */
    @Nullable
    private DisplayTransportCap currentTransport() {
        try {
            return chooseTransport.getSelectedItem();
        } catch (IllegalStateException ignored) {
            return null;
        }
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
                screenId, exporter, transport, typedWidth()) ? VISIBLE : GONE);
    }

    /**
     * The width typed into this row, or 0 for a field that is empty or not a number -- a value no
     * alignment rule can complain about, since a half-typed number is not yet a width to warn
     * about and the geometry validator is what has something to say about it.
     */
    private long typedWidth() {
        try {
            var text = getEditText(etWidth);
            return text.isEmpty() ? 0 : parseInt(text);
        } catch (Exception ignored) {
            return 0;
        }
    }

    void load(@NonNull DataItem config) {
        var screen = VMScreenConfig.find(config, screenId);
        if (screen == null) {
            swEnabled.setChecked(false);
            chooseExporter.setSelectedItem(DisplayExporter.NONE);
            return;
        }
        swEnabled.setChecked(screen.isEnabled());
        chooseExporter.setSelectedItem(screen.getExporter());
        applyTransportOptions(screen.getExporter(), screen.getTransportCap());
        // Absent in the stored config means on, so an existing VM keeps the devices it had
        // without its file being rewritten to say so.
        swInputEnabled.setChecked(screen.isInputEnabled());
        etWidth.setText(String.valueOf(screen.getWidth()));
        etHeight.setText(String.valueOf(screen.getHeight()));
        if (gpuScreen) {
            etRefreshRate.setText(String.valueOf(screen.getRefreshRate()));
            etDpiH.setText(String.valueOf(screen.getDpiH()));
            etDpiV.setText(String.valueOf(screen.getDpiV()));
        } else {
            etPollHz.setText(String.valueOf(screen.getPollHz()));
        }
        etHost.setText(screen.getVncHost());
        var port = screen.getVncPort();
        etPort.setText(port > 0 ? String.valueOf(port) : "");
        swPasswordAuth.setChecked(screen.isVncPasswordAuth());
        etPassword.setText(screen.getVncPassword());
    }

    void save(@NonNull DataItem config) {
        var screen = VMScreenConfig.of(config, screenId);
        var enabled = swEnabled.isChecked();
        var exporter = enabled ? getExporter() : DisplayExporter.NONE;
        screen.setEnabled(enabled);
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
        screen.setWidth(parseInt(getEditText(etWidth)));
        screen.setHeight(parseInt(getEditText(etHeight)));
        if (gpuScreen) {
            screen.setRefreshRate(parseInt(getEditText(etRefreshRate)));
            screen.setDpiH(parseInt(getEditText(etDpiH)));
            screen.setDpiV(parseInt(getEditText(etDpiV)));
        } else {
            screen.setPollHz(parseInt(getEditText(etPollHz)));
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
        try {
            var portStr = getEditText(etPort);
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
    TextInputEditText widthField() {
        return etWidth;
    }

    @NonNull
    TextInputEditText heightField() {
        return etHeight;
    }

    /** The rate field this screen actually has: the mode's refresh rate, or the poll rate. */
    @NonNull
    TextInputEditText rateField() {
        return gpuScreen ? etRefreshRate : etPollHz;
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
