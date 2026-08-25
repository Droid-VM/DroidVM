// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.graphics;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static java.lang.Integer.parseInt;
import static cn.classfun.droidvm.lib.utils.StringUtils.generateRandomPassword;
import static cn.classfun.droidvm.lib.utils.StringUtils.getEditText;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.DisplayExporter;
import cn.classfun.droidvm.lib.store.vm.VMScreenConfig;
import cn.classfun.droidvm.ui.widgets.row.ChooseRowWidget;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;

/**
 * One screen's rows in the graphics tab: the switch that says the VM has the device, the exporter
 * bound to it, and that exporter's settings.
 *
 * <p>The two screens have the same shape, so the wiring is written once here and the layout
 * carries one block of ids per screen. What differs between them -- the GPU screen's DPI and blit
 * provider, which only mean anything for the accelerated scanout -- stays in the tab, because it
 * belongs to that screen's definition rather than to its binding.</p>
 */
final class ScreenBindingRow {
    private static final int VNC_PASSWORD_LENGTH = 8;

    /** Screen id, as stored and as handed to crosvm's {@code screen=}. */
    final String screenId;
    private final boolean defaultEnabled;
    private final DisplayExporter defaultExporter;

    private final SwitchRowWidget swEnabled;
    private final View options;
    private final ChooseRowWidget chooseExporter;
    private final View vncOptions;
    private final TextInputEditText etHost;
    private final TextInputEditText etPort;
    private final SwitchRowWidget swPasswordAuth;
    private final View passwordOptions;
    private final TextInputEditText etPassword;

    /**
     * @param block the root of this screen's {@code partial_vm_screen_binding} include. Every
     *              lookup below is scoped to it, which is what keeps the two includes' identical
     *              ids apart -- an activity-wide findViewById would always find the first.
     */
    ScreenBindingRow(@NonNull String screenId, @NonNull View block, @StringRes int titleRes,
                     boolean defaultEnabled, @NonNull DisplayExporter defaultExporter) {
        this.screenId = screenId;
        this.defaultEnabled = defaultEnabled;
        this.defaultExporter = defaultExporter;
        swEnabled = block.findViewById(R.id.sw_screen_enabled);
        swEnabled.setText(titleRes);
        options = block.findViewById(R.id.screen_options);
        chooseExporter = block.findViewById(R.id.choose_screen_exporter);
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
        swEnabled.setChecked(defaultEnabled);
        swEnabled.setOnCheckedChangeListener(onChanged);
        chooseExporter.setOnValueChangedListener(onChanged);
        swPasswordAuth.setOnCheckedChangeListener(onChanged);
    }

    boolean isScreenEnabled() {
        return swEnabled.isChecked();
    }

    void setScreenEnabled(boolean enabled) {
        swEnabled.setChecked(enabled);
    }

    @NonNull
    DisplayExporter getExporter() {
        return chooseExporter.getSelectedItem();
    }

    void setExporter(@NonNull DisplayExporter exporter) {
        chooseExporter.setSelectedItem(exporter);
    }

    /** Whether the whole block is offered at all -- the GPU screen needs the GPU enabled. */
    void setAvailable(boolean available) {
        swEnabled.setVisibility(available ? VISIBLE : GONE);
        if (!available) swEnabled.setChecked(false);
    }

    void updateVisibility() {
        var enabled = swEnabled.isChecked();
        options.setVisibility(enabled ? VISIBLE : GONE);
        vncOptions.setVisibility(
            enabled && getExporter() == DisplayExporter.VNC ? VISIBLE : GONE);
        passwordOptions.setVisibility(swPasswordAuth.isChecked() ? VISIBLE : GONE);
    }

    void load(@NonNull DataItem config) {
        var screen = VMScreenConfig.find(config, screenId);
        if (screen == null) {
            swEnabled.setChecked(false);
            setExporter(DisplayExporter.NONE);
            return;
        }
        swEnabled.setChecked(screen.isEnabled());
        setExporter(screen.getExporter());
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
}
