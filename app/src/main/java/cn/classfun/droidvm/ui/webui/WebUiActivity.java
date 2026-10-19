// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.webui;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.ArrayList;
import java.util.List;
import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.WebUiClient;
import cn.classfun.droidvm.ui.widgets.row.TextRowWidget;

/** App-only management. Browser credentials never enter saved state or settings. */
public final class WebUiActivity extends AppCompatActivity {
    private WebUiModel model;
    private WebUiModel.State current;
    private TextView status;
    private MaterialSwitch enabled, lanAutoLogin;
    private MaterialButton refresh, pair;
    private TextRowWidget scope, port, origins;
    private LinearLayout addresses, sessions;
    private View progress, lanAutoLoginHelp;
    private boolean rendering;
    private AlertDialog tokenDialog;
    private String displayedToken = "";

    @Override protected void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved); setContentView(R.layout.activity_webui);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());
        model = new ViewModelProvider(this).get(WebUiModel.class);
        status = findViewById(R.id.webui_status); enabled = findViewById(R.id.webui_enabled);
        lanAutoLogin = findViewById(R.id.webui_lan_auto_login);
        lanAutoLoginHelp = findViewById(R.id.webui_lan_auto_login_help);
        refresh = findViewById(R.id.webui_refresh); pair = findViewById(R.id.webui_pair);
        scope = findViewById(R.id.webui_scope); port = findViewById(R.id.webui_port); origins = findViewById(R.id.webui_origins);
        addresses = findViewById(R.id.webui_addresses); sessions = findViewById(R.id.webui_sessions); progress = findViewById(R.id.webui_progress);
        refresh.setOnClickListener(v -> model.refresh(current != null && current.reloadRequired));
        enabled.setOnCheckedChangeListener((view, checked) -> {
            if (rendering || !editable()) return;
            WebUiClient.Settings old = current.status.settings;
            model.configure(old, old.withConnection(checked, old.port, old.scope, old.publicOrigins));
        });
        lanAutoLogin.setOnCheckedChangeListener((view, checked) -> {
            if (rendering || !editable() || !current.status.settings.lanAutoLoginSupported) return;
            WebUiClient.Settings old = current.status.settings;
            model.configure(old, old.withLanAutoLogin(checked));
        });
        scope.setOnClickListener(v -> chooseScope());
        port.setOnClickListener(v -> edit(false)); origins.setOnClickListener(v -> edit(true));
        pair.setOnClickListener(v -> model.pair());
        model.state().observe(this, this::render);
    }
    @Override protected void onResume() {
        super.onResume(); model.foreground(true); model.refresh(false);
    }
    @Override protected void onStop() {
        model.foreground(false); dismissToken(); super.onStop();
    }
    private boolean editable() {
        return current != null && current.status != null && !current.busy && !current.reloadRequired && current.problem.isEmpty();
    }
    private void render(WebUiModel.State next) {
        current = next; rendering = true;
        boolean edit = editable();
        enabled.setEnabled(edit); scope.setEnabled(edit); port.setEnabled(edit); origins.setEnabled(edit);
        boolean lanSupported = next.status != null && next.status.settings.lanAutoLoginSupported;
        lanAutoLogin.setVisibility(lanSupported ? View.VISIBLE : View.GONE);
        lanAutoLoginHelp.setVisibility(lanSupported ? View.VISIBLE : View.GONE);
        lanAutoLogin.setEnabled(edit && lanSupported);
        refresh.setEnabled(!next.busy);
        refresh.setText(next.reloadRequired ? R.string.webui_reload : R.string.webui_refresh);
        progress.setVisibility(next.busy ? View.VISIBLE : View.GONE);
        pair.setEnabled(edit && next.status.running);
        if (next.status != null) {
            WebUiClient.Settings settings = next.status.settings;
            enabled.setChecked(settings.enabled);
            lanAutoLogin.setChecked(settings.lanAutoLogin);
            scope.setValue(getString("lan".equals(settings.scope) ? R.string.webui_lan : R.string.webui_all));
            port.setValue(Integer.toString(settings.port));
            origins.setValue(Integer.toString(settings.publicOrigins.size()));
        }
        status.setText(message(next));
        addresses.removeAllViews(); sessions.removeAllViews();
        if (next.status != null && next.problem.isEmpty()) {
            for (String address : next.status.addresses) {
                MaterialButton button = button(address); button.setEnabled(!next.busy);
                button.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setTitle(R.string.webui_addresses)
                    .setMessage(address).setPositiveButton(R.string.webui_open, (dialog, which) -> {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address))); }
                        catch (android.content.ActivityNotFoundException e) { Toast.makeText(this, R.string.webui_no_browser, Toast.LENGTH_SHORT).show(); }
                    }).setNeutralButton(R.string.webui_copy, (dialog, which) -> copy(address, false))
                    .setNegativeButton(android.R.string.cancel, null).show());
                addresses.addView(button);
            }
            for (int i = 0; i < next.status.sessions.size(); i++) {
                String id = next.status.sessions.get(i);
                MaterialButton button = button(getString(R.string.webui_session, i + 1)); button.setEnabled(edit);
                button.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setTitle(R.string.webui_revoke)
                    .setMessage(getString(R.string.webui_session_id, id)).setPositiveButton(R.string.webui_revoke, (dialog, which) -> model.revoke(id))
                    .setNegativeButton(android.R.string.cancel, null).show());
                sessions.addView(button);
            }
            if (next.status.sessions.isEmpty()) {
                TextView empty = new TextView(this); empty.setText(R.string.webui_no_sessions); sessions.addView(empty);
            }
        }
        rendering = false;
        if (next.token.isEmpty()) dismissToken();
        else if (!next.token.equals(displayedToken)) showToken(next.token);
    }
    private int message(WebUiModel.State state) {
        if ("unsupported".equals(state.problem)) return R.string.webui_unsupported;
        if ("CONFLICT".equals(state.problem)) return R.string.webui_conflict;
        if (state.reloadRequired) return R.string.webui_unknown;
        if ("RESOURCE_EXHAUSTED".equals(state.problem)) return R.string.webui_limit;
        if (!state.problem.isEmpty()) return R.string.webui_unavailable;
        if (state.status == null) return R.string.webui_loading;
        switch (state.status.error) {
            case "discovery_failed": return R.string.webui_discovery_failed;
            case "listen_failed": return R.string.webui_listen_failed;
            case "configuration_write_failed": return R.string.webui_unknown;
        }
        switch (state.status.state) {
            case "disabled": return R.string.webui_disabled;
            case "local_only": return R.string.webui_local_only;
            case "running": return R.string.webui_running;
            default: return R.string.webui_unavailable;
        }
    }
    private MaterialButton button(String text) {
        MaterialButton button = new MaterialButton(this); button.setText(text); button.setAllCaps(false);
        button.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        button.setLayoutParams(new LinearLayout.LayoutParams(-1, -2)); return button;
    }
    private void chooseScope() {
        if (!editable()) return;
        WebUiClient.Settings settings = current.status.settings;
        int[] chosen = {"all".equals(settings.scope) ? 1 : 0};
        new MaterialAlertDialogBuilder(this).setTitle(R.string.webui_scope)
            .setSingleChoiceItems(new String[]{getString(R.string.webui_lan), getString(R.string.webui_all)}, chosen[0], (dialog, which) -> chosen[0] = which)
            .setPositiveButton(R.string.webui_save, (dialog, which) -> {
                if (editable()) model.configure(settings, settings.withConnection(settings.enabled, settings.port, chosen[0] == 0 ? "lan" : "all", settings.publicOrigins));
            }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void edit(boolean external) {
        if (!editable()) return;
        WebUiClient.Settings settings = current.status.settings;
        EditText input = new EditText(this); input.setTextSize(16); input.setSaveEnabled(false);
        input.setInputType(external ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(!external);
        input.setText(external ? String.join("\n", settings.publicOrigins) : Integer.toString(settings.port));
        input.setHint(external ? "http://vm.example:8080" : "8080");
        LinearLayout container = new LinearLayout(this);
        int padding = (int) (24 * getResources().getDisplayMetrics().density); container.setPadding(padding, 0, padding, 0);
        container.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(external ? R.string.webui_origins : R.string.webui_port)
            .setView(container).setPositiveButton(R.string.webui_save, null).setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!editable()) { dialog.dismiss(); return; }
            try {
                List<String> entries = new ArrayList<>();
                if (external) for (String line : input.getText().toString().split("\\R")) if (!line.trim().isEmpty()) entries.add(line.trim());
                int number = external ? settings.port : Integer.parseInt(input.getText().toString().trim());
                model.configure(settings, settings.withConnection(settings.enabled, number, settings.scope, external ? entries : settings.publicOrigins));
                dialog.dismiss();
            } catch (IllegalArgumentException error) { input.setError(getString(external ? R.string.webui_invalid_origins : R.string.webui_invalid_port)); }
        }));
        dialog.show();
    }
    private void showToken(String token) {
        dismissToken(); displayedToken = token;
        TextView text = new TextView(this); text.setText(token); text.setTextIsSelectable(true); text.setSaveEnabled(false); text.setTextSize(16);
        int padding = (int) (24 * getResources().getDisplayMetrics().density); text.setPadding(padding, padding, padding, padding);
        tokenDialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.webui_pair).setView(text)
            .setPositiveButton(R.string.webui_copy, (dialog, which) -> copy(token, true)).setNegativeButton(android.R.string.ok, null).create();
        tokenDialog.setOnDismissListener(dialog -> { displayedToken = ""; tokenDialog = null; model.clearToken(); });
        tokenDialog.show();
    }
    private void dismissToken() {
        if (tokenDialog != null) { tokenDialog.setOnDismissListener(null); tokenDialog.dismiss(); tokenDialog = null; }
        displayedToken = "";
    }
    private void copy(String value, boolean secret) {
        ClipData clip = ClipData.newPlainText(getString(secret ? R.string.webui_pair : R.string.webui_addresses), value);
        if (secret) { PersistableBundle extras = new PersistableBundle(); extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true); clip.getDescription().setExtras(extras); }
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        Toast.makeText(this, R.string.webui_copied, Toast.LENGTH_SHORT).show();
    }
}
