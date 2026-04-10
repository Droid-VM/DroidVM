package cn.classfun.droidvm.ui.vm.edit.graphics;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static java.lang.Integer.parseInt;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.store.vm.DisplayBackend.SIMPLEFB;
import static cn.classfun.droidvm.lib.store.vm.GpuBackend.GPU_GFXSTREAM;
import static cn.classfun.droidvm.lib.utils.StringUtils.generateRandomPassword;
import static cn.classfun.droidvm.lib.utils.StringUtils.getEditText;

import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.store.vm.DisplayBackend;
import cn.classfun.droidvm.lib.store.vm.GpuApi;
import cn.classfun.droidvm.lib.store.vm.GpuBackend;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.widgets.row.ChooseRowWidget;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;

public final class VMEditGraphicsTab extends VMEditBaseTab {
    private static final int VNC_PASSWORD_LENGTH = 8;
    private View gpuOptions;
    private View displayOptions;
    private View displayDpiOptions;
    private View vncOptions;
    private View vncPasswordOptions;
    private SwitchRowWidget swGpuEnabled;
    private SwitchRowWidget swVncEnabled;
    private SwitchRowWidget swVncPasswordAuth;
    private SwitchRowWidget swDisplayEnabled;
    private ChooseRowWidget chooseGpuBackend;
    private ChooseRowWidget chooseGpuApi;
    private ChooseRowWidget chooseDisplayBackend;
    private TextInputEditText etDisplayWidth;
    private TextInputEditText etDisplayHeight;
    private TextInputEditText etDisplayRefreshRate;
    private TextInputEditText etDisplayDpiH;
    private TextInputEditText etDisplayDpiV;
    private TextInputEditText etVncHost;
    private TextInputEditText etVncPort;
    private TextInputEditText etVncPassword;
    private MaterialButton btnVncPasswordClear;
    private MaterialButton btnVncPasswordGenerate;

    public VMEditGraphicsTab(VMEditActivity parent, View view) {
        super(parent, view);
    }

    @Override
    public void initView() {
        swGpuEnabled = view.findViewById(R.id.sw_gpu_enabled);
        chooseGpuBackend = view.findViewById(R.id.choose_gpu_backend);
        chooseGpuApi = view.findViewById(R.id.choose_gpu_api);
        gpuOptions = view.findViewById(R.id.gpu_options);
        swDisplayEnabled = view.findViewById(R.id.sw_display_enabled);
        chooseDisplayBackend = view.findViewById(R.id.choose_display_backend);
        displayOptions = view.findViewById(R.id.display_options);
        etDisplayWidth = view.findViewById(R.id.et_display_width);
        etDisplayHeight = view.findViewById(R.id.et_display_height);
        etDisplayRefreshRate = view.findViewById(R.id.et_display_refresh_rate);
        etDisplayDpiH = view.findViewById(R.id.et_display_dpi_h);
        etDisplayDpiV = view.findViewById(R.id.et_display_dpi_v);
        displayDpiOptions = view.findViewById(R.id.display_dpi_options);
        swVncEnabled = view.findViewById(R.id.sw_vnc_enabled);
        swVncPasswordAuth = view.findViewById(R.id.sw_vnc_password_auth);
        vncOptions = view.findViewById(R.id.vnc_options);
        vncPasswordOptions = view.findViewById(R.id.vnc_password_options);
        etVncHost = view.findViewById(R.id.et_vnc_host);
        etVncPort = view.findViewById(R.id.et_vnc_port);
        etVncPassword = view.findViewById(R.id.et_vnc_password);
        btnVncPasswordClear = view.findViewById(R.id.btn_vnc_password_clear);
        btnVncPasswordGenerate = view.findViewById(R.id.btn_vnc_password_generate);
    }

    @Override
    public void initValue() {
        swGpuEnabled.setOnCheckedChangeListener(this::updateGpuVisibility);
        swDisplayEnabled.setOnCheckedChangeListener(this::updateDisplayVisibility);
        swVncEnabled.setOnCheckedChangeListener(this::updateVncVisibility);
        chooseGpuBackend.configure(GpuBackend.class, GPU_GFXSTREAM);
        chooseGpuApi.configure(GpuApi.class, GpuApi.OPENGLES);
        chooseDisplayBackend.configure(DisplayBackend.class, SIMPLEFB);
        chooseDisplayBackend.setOnValueChangedListener((o, n) -> {
            if (n == DisplayBackend.VIRTIO_GPU && !swGpuEnabled.isChecked()) {
                chooseDisplayBackend.setSelectedItem(SIMPLEFB);
                return;
            }
            updateDisplayDpiVisibility();
        });
        btnVncPasswordClear.setOnClickListener(v -> etVncPassword.setText(""));
        btnVncPasswordGenerate.setOnClickListener(v ->
            etVncPassword.setText(generateRandomPassword(VNC_PASSWORD_LENGTH)));
        swVncPasswordAuth.setOnCheckedChangeListener((b, checked) ->
            updateVncPasswordVisibility());
        updateGpuVisibility();
        updateDisplayVisibility();
        updateDisplayDpiVisibility();
        updateVncVisibility();
        updateVncPasswordVisibility();
    }

    @Override
    public void loadConfig(@NonNull VMConfig config) {
        var item = config.item;
        swGpuEnabled.setChecked(item.optBoolean("gpu_enabled", false));
        swDisplayEnabled.setChecked(item.optBoolean("display_enabled", false));
        etDisplayWidth.setText(String.valueOf(item.optLong("display_width", 1280)));
        etDisplayHeight.setText(String.valueOf(item.optLong("display_height", 720)));
        etDisplayRefreshRate.setText(String.valueOf(item.optLong("display_refresh_rate", 60)));
        etDisplayDpiH.setText(String.valueOf(item.optLong("display_dpi_h", 160)));
        etDisplayDpiV.setText(String.valueOf(item.optLong("display_dpi_v", 160)));
        swVncEnabled.setChecked(item.optBoolean("vnc_enabled", false));
        swVncPasswordAuth.setChecked(item.optBoolean("vnc_password_auth", false));
        etVncHost.setText(item.optString("vnc_host", ""));
        var vncPort = item.optLong("vnc_port", -1);
        etVncPort.setText(vncPort > 0 ? String.valueOf(vncPort) : "");
        etVncPassword.setText(item.optString("vnc_password", ""));
        var gpuApi = optEnum(item, "gpu_api", GpuApi.NONE);
        var gpuBackend = optEnum(item, "gpu_backend", GpuBackend.NONE);
        var displayBackend = optEnum(item, "display_backend", DisplayBackend.NONE);
        if (gpuApi != GpuApi.NONE)
            chooseGpuApi.setSelectedItem(gpuApi);
        if (gpuBackend != GpuBackend.NONE)
            chooseGpuBackend.setSelectedItem(gpuBackend);
        if (displayBackend != DisplayBackend.NONE)
            chooseDisplayBackend.setSelectedItem(displayBackend);
        updateGpuVisibility();
        updateDisplayVisibility();
        updateDisplayDpiVisibility();
        updateVncVisibility();
        updateVncPasswordVisibility();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean checkInputField(
        @NonNull TextInputEditText field,
        boolean allowEmpty, int min, int max
    ) {
        field.setError(null);
        try {
            var text = getEditText(field);
            if (text.isEmpty() && allowEmpty) return true;
            var ret = parseInt(text);
            if (ret < min || ret > max)
                throw new IllegalArgumentException();
            return true;
        } catch (Exception ignored) {
            field.setError(parent.getString(R.string.create_vm_error_invalid_number));
            return false;
        }
    }

    @Override
    public boolean validateInput(@NonNull VMStore store) {
        if (!checkInputField(etDisplayWidth, false, 320, 8192)) return false;
        if (!checkInputField(etDisplayHeight, false, 320, 8192)) return false;
        if (!checkInputField(etDisplayRefreshRate, false, 1, 400)) return false;
        if (!checkInputField(etDisplayDpiH, false, 100, 800)) return false;
        if (!checkInputField(etDisplayDpiV, false, 100, 800)) return false;
        if (!checkInputField(etVncPort, true, 1024, 65535)) return false;
        return true;
    }

    @Override
    public void saveConfig(@NonNull VMConfig config) {
        var item = config.item;
        var gpuEnabled = swGpuEnabled.isChecked();
        var displayEnabled = swDisplayEnabled.isChecked();
        var vncEnabled = swVncEnabled.isChecked();
        item.set("gpu_enabled", gpuEnabled);
        item.set("display_enabled", displayEnabled);
        item.set("vnc_enabled", vncEnabled);
        if (gpuEnabled) {
            GpuBackend gb = chooseGpuBackend.getSelectedItem();
            GpuApi ga = chooseGpuApi.getSelectedItem();
            item.set("gpu_backend", gb);
            item.set("gpu_api", ga);
        }
        if (displayEnabled) {
            DisplayBackend displayBackend = chooseDisplayBackend.getSelectedItem();
            item.set("display_backend", displayBackend);
            item.set("display_width", parseInt(getEditText(etDisplayWidth)));
            item.set("display_height", parseInt(getEditText(etDisplayHeight)));
            item.set("display_refresh_rate", parseInt(getEditText(etDisplayRefreshRate)));
            if (displayBackend != DisplayBackend.NONE && displayBackend != SIMPLEFB) {
                item.set("display_dpi_h", parseInt(getEditText(etDisplayDpiH)));
                item.set("display_dpi_v", parseInt(getEditText(etDisplayDpiV)));
            }
        }
        if (vncEnabled) {
            var passwordAuth = swVncPasswordAuth.isChecked();
            item.set("vnc_password_auth", passwordAuth);
            if (passwordAuth)
                item.set("vnc_password", getEditText(etVncPassword));
            item.set("vnc_host", getEditText(etVncHost));
            var vncPortStr = getEditText(etVncPort);
            item.set("vnc_port", vncPortStr.isEmpty() ? -1 : parseInt(vncPortStr));
        }
    }

    private void updateGpuVisibility() {
        gpuOptions.setVisibility(swGpuEnabled.isChecked() ? VISIBLE : GONE);
        if (!swGpuEnabled.isChecked()) {
            if (chooseDisplayBackend.getSelectedItem() == DisplayBackend.VIRTIO_GPU)
                chooseDisplayBackend.setSelectedItem(SIMPLEFB);
        }
    }

    private void updateDisplayVisibility() {
        displayOptions.setVisibility(swDisplayEnabled.isChecked() ? VISIBLE : GONE);
    }

    private void updateDisplayDpiVisibility() {
        var backend = chooseDisplayBackend.getSelectedItem();
        displayDpiOptions.setVisibility(backend != SIMPLEFB ? VISIBLE : GONE);
    }

    private void updateVncVisibility() {
        vncOptions.setVisibility(swVncEnabled.isChecked() ? VISIBLE : GONE);
    }

    private void updateVncPasswordVisibility() {
        vncPasswordOptions.setVisibility(swVncPasswordAuth.isChecked() ? VISIBLE : GONE);
    }
}
