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
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.store.vm.DisplayBackend;
import cn.classfun.droidvm.lib.store.vm.DisplayOutput;
import cn.classfun.droidvm.lib.store.vm.GpuApi;
import cn.classfun.droidvm.lib.store.vm.GpuBackend;
import cn.classfun.droidvm.lib.store.vm.GpuMode;
import cn.classfun.droidvm.lib.store.vm.GpuProvider;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.widgets.row.ChooseRowWidget;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;

public final class VMEditGraphicsTab extends VMEditBaseTab {
    private static final int VNC_PASSWORD_LENGTH = 8;
    /** Set while loadConfig() is applying stored values, so enforcement stays silent. */
    private boolean loadingConfig = false;
    private View gpuOptions;
    private View displayOptions;
    private View displayDpiOptions;
    private View vncOptions;
    private View vncPasswordOptions;
    private View vramSettings;
    private View tilGpuKgslPoolMb;
    private View tilGpuHostPoolMb;
    private TextInputEditText etGpuKgslPoolMb;
    private View dynamicVramOptions;
    private View tilGpuGuestPoolMb;
    private SwitchRowWidget swGpuUdmabuf;
    private SwitchRowWidget swGpuDynamicVram;
    private TextInputEditText etGpuHostPoolMb;
    private TextInputEditText etGpuArenaMb;
    private TextInputEditText etGpuGuestPoolMb;
    private TextInputEditText etGpuPoolBlobMaxKb;
    private SwitchRowWidget swGpuEnabled;
    private SwitchRowWidget swVncPasswordAuth;
    private SwitchRowWidget swDisplayEnabled;
    private ChooseRowWidget chooseGpuBackend;
    private ChooseRowWidget chooseGpuMode;
    private ChooseRowWidget chooseGpuProvider;
    private ChooseRowWidget chooseDisplayBackend;
    private ChooseRowWidget chooseDisplayOutput;
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
        chooseGpuMode = view.findViewById(R.id.choose_gpu_mode);
        chooseGpuProvider = view.findViewById(R.id.choose_gpu_provider);
        gpuOptions = view.findViewById(R.id.gpu_options);
        swGpuUdmabuf = view.findViewById(R.id.sw_gpu_udmabuf);
        vramSettings = view.findViewById(R.id.vram_settings);
        swGpuDynamicVram = view.findViewById(R.id.sw_gpu_dynamic_vram);
        dynamicVramOptions = view.findViewById(R.id.dynamic_vram_options);
        tilGpuGuestPoolMb = view.findViewById(R.id.til_gpu_guest_pool_mb);
        tilGpuKgslPoolMb = view.findViewById(R.id.til_gpu_kgsl_pool_mb);
        tilGpuHostPoolMb = view.findViewById(R.id.til_gpu_host_pool_mb);
        etGpuKgslPoolMb = view.findViewById(R.id.et_gpu_kgsl_pool_mb);
        etGpuHostPoolMb = view.findViewById(R.id.et_gpu_host_pool_mb);
        etGpuArenaMb = view.findViewById(R.id.et_gpu_arena_mb);
        etGpuGuestPoolMb = view.findViewById(R.id.et_gpu_guest_pool_mb);
        etGpuPoolBlobMaxKb = view.findViewById(R.id.et_gpu_pool_blob_max_kb);
        swDisplayEnabled = view.findViewById(R.id.sw_display_enabled);
        chooseDisplayBackend = view.findViewById(R.id.choose_display_backend);
        chooseDisplayOutput = view.findViewById(R.id.choose_display_output);
        displayOptions = view.findViewById(R.id.display_options);
        etDisplayWidth = view.findViewById(R.id.et_display_width);
        etDisplayHeight = view.findViewById(R.id.et_display_height);
        etDisplayRefreshRate = view.findViewById(R.id.et_display_refresh_rate);
        etDisplayDpiH = view.findViewById(R.id.et_display_dpi_h);
        etDisplayDpiV = view.findViewById(R.id.et_display_dpi_v);
        displayDpiOptions = view.findViewById(R.id.display_dpi_options);
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
        // PanVK (Mali) is listed but not wired yet: toast + revert to the previous choice.
        // Acceleration decides which host drivers make sense and which memory knobs exist,
        // so it drives both of the rows under it.
        chooseGpuMode.setOnValueChangedListener((o, n) -> {
            updateGpuProviderOptions();
            updateVramAllocVisibility();
        });
        // PanVK (Mali) is listed but not wired yet: toast + revert to the previous choice.
        chooseGpuProvider.setOnValueChangedListener((o, n) -> {
            if (n == GpuProvider.VK_PANVK) {
                Toast.makeText(parent, R.string.create_vm_gpu_api_not_implemented,
                    Toast.LENGTH_SHORT).show();
                chooseGpuProvider.setSelectedItem(
                    o instanceof GpuProvider ? (GpuProvider) o : GpuProvider.VK_TURNIP);
            }
        });
        // The GPU API set depends on the backend: gfxstream picks a host Vulkan driver,
        // virgl/2d picks a guest GL/Vulkan translation API or the KGSL native context.
        chooseGpuBackend.setOnValueChangedListener((o, n) -> {
            updateGpuModeOptions();
            updateGpuProviderOptions();
            updateVramAllocVisibility();
        });
        chooseGpuBackend.configure(GpuBackend.class, GPU_GFXSTREAM);
        chooseDisplayBackend.configure(DisplayBackend.class, SIMPLEFB);
        chooseDisplayBackend.setOnValueChangedListener((o, n) -> {
            if (n == DisplayBackend.VIRTIO_GPU && !swGpuEnabled.isChecked()) {
                chooseDisplayBackend.setSelectedItem(SIMPLEFB);
                return;
            }
            updateDisplayOutputVisibility();
            updateDisplayDpiVisibility();
        });
        // VNC is the default for a new VM: it needs nothing from the host app to be viewable,
        // which is what the standalone VNC switch used to default to (android:checked="true").
        chooseDisplayOutput.configure(DisplayOutput.class, DisplayOutput.VNC);
        chooseDisplayOutput.setOnValueChangedListener((o, n) -> {
            updateDisplayDpiVisibility();
            updateVncVisibility();
        });
        btnVncPasswordClear.setOnClickListener(v -> etVncPassword.setText(""));
        btnVncPasswordGenerate.setOnClickListener(v ->
            etVncPassword.setText(generateRandomPassword(VNC_PASSWORD_LENGTH)));
        swVncPasswordAuth.setOnCheckedChangeListener((b, checked) ->
            updateVncPasswordVisibility());
        swGpuUdmabuf.setOnCheckedChangeListener(this::updateVramAllocVisibility);
        swGpuDynamicVram.setOnCheckedChangeListener(() -> {
            // Refuse to turn on without the mechanism underneath: put the switch back and say
            // why, instead of letting the VM look configured and fail at save time.
            if (swGpuDynamicVram.isChecked() && !isDynamicMemorySharingAvailable()) {
                swGpuDynamicVram.setChecked(false);
                if (!loadingConfig) showHint(dynamicVramNeedsSharingMessage());
                return;
            }
            updateVramAllocVisibility();
        });
        // The backend chooser's configure() above does not fire its listener, so seed the two
        // rows under it before the first paint; loadConfig() then restores over the top.
        updateGpuModeOptions();
        updateGpuProviderOptions();
        updateVramAllocVisibility();
        updateGpuVisibility();
        updateDisplayVisibility();
        updateDisplayOutputVisibility();
        updateDisplayDpiVisibility();
        updateVncVisibility();
        updateVncPasswordVisibility();
        updateVramAllocVisibility();
    }

    @Override
    public void loadConfig(@NonNull VMConfig config) {
        loadingConfig = true;
        try {
            loadConfigLocked(config);
        } finally {
            loadingConfig = false;
        }
    }

    private void loadConfigLocked(@NonNull VMConfig config) {
        var item = config.item;
        swGpuEnabled.setChecked(item.optBoolean("gpu_enabled", false));
        swGpuUdmabuf.setChecked(item.optBoolean("gpu_udmabuf", false));
        etGpuKgslPoolMb.setText(String.valueOf(item.optLong("gpu_kgsl_pool_mb", 1024)));
        etGpuHostPoolMb.setText(String.valueOf(item.optLong("gpu_host_pool_mb", 256)));
        etGpuArenaMb.setText(String.valueOf(item.optLong("gpu_arena_mb", 2048)));
        etGpuGuestPoolMb.setText(String.valueOf(item.optLong("gpu_guest_pool_mb", 1024)));
        // Defaults to on: before it had a switch, a vram limit was always handed to crosvm.
        swGpuDynamicVram.setChecked(item.optBoolean("gpu_dynamic_vram", true));
        etGpuPoolBlobMaxKb.setText(String.valueOf(item.optLong("gpu_pool_blob_max_kb", 4096)));
        swDisplayEnabled.setChecked(item.optBoolean("display_enabled", false));
        etDisplayWidth.setText(String.valueOf(item.optLong("display_width", 1280)));
        etDisplayHeight.setText(String.valueOf(item.optLong("display_height", 720)));
        etDisplayRefreshRate.setText(String.valueOf(item.optLong("display_refresh_rate", 60)));
        etDisplayDpiH.setText(String.valueOf(item.optLong("display_dpi_h", 160)));
        etDisplayDpiV.setText(String.valueOf(item.optLong("display_dpi_v", 160)));
        swVncPasswordAuth.setChecked(item.optBoolean("vnc_password_auth", false));
        etVncHost.setText(item.optString("vnc_host", ""));
        var vncPort = item.optLong("vnc_port", -1);
        etVncPort.setText(vncPort > 0 ? String.valueOf(vncPort) : "");
        etVncPassword.setText(item.optString("vnc_password", ""));
        var gpuBackend = optEnum(item, "gpu_backend", GpuBackend.NONE);
        var displayBackend = optEnum(item, "display_backend", DisplayBackend.NONE);
        // Configs written before acceleration and host driver were separate rows carry a single
        // gpu_api whose meaning depended on the renderer. Derive both from it when the new keys
        // are absent; gpu_api is left in place, so an older build still reads the VM.
        var legacyApi = optEnum(item, "gpu_api", GpuApi.NONE);
        var gpuMode = optEnum(item, "gpu_mode", GpuMode.NONE);
        var gpuProvider = optEnum(item, "gpu_provider", GpuProvider.NONE);
        if (gpuMode == GpuMode.NONE) gpuMode = GpuMode.fromLegacyApi(legacyApi);
        if (gpuProvider == GpuProvider.NONE) gpuProvider = GpuProvider.fromLegacyApi(legacyApi);
        // Backend first: its listener rebuilds the option sets below it (and their defaults).
        if (gpuBackend != GpuBackend.NONE)
            chooseGpuBackend.setSelectedItem(gpuBackend);
        // Restore each saved choice only if the backend still offers it; otherwise keep the
        // default updateGpuModeOptions()/updateGpuProviderOptions() just picked.
        boolean gfx = chooseGpuBackend.getSelectedItem() == GPU_GFXSTREAM;
        if (gpuMode != GpuMode.NONE && (gfx == (gpuMode == GpuMode.VULKAN)))
            chooseGpuMode.setSelectedItem(gpuMode);
        updateGpuProviderOptions();
        if (gpuProvider != GpuProvider.NONE && gfx == isVulkanProvider(gpuProvider))
            chooseGpuProvider.setSelectedItem(gpuProvider);
        if (displayBackend != DisplayBackend.NONE)
            chooseDisplayBackend.setSelectedItem(displayBackend);
        // The output side is UI-only: derive the single choice from the two persisted booleans.
        // Native wins if both are set -- that is what crosvm does anyway (it keeps the first
        // display backend that opens and the Android display service is inserted first).
        chooseDisplayOutput.setSelectedItem(readDisplayOutput(config));
        updateGpuVisibility();
        updateDisplayVisibility();
        updateDisplayOutputVisibility();
        updateDisplayDpiVisibility();
        updateVncVisibility();
        updateVncPasswordVisibility();
        updateVramAllocVisibility();
    }

    @NonNull
    private static DisplayOutput readDisplayOutput(@NonNull VMConfig config) {
        var item = config.item;
        if (item.optBoolean("native_display_enabled", false)) return DisplayOutput.NATIVE;
        if (item.optBoolean("vnc_enabled", false)) return DisplayOutput.VNC;
        return DisplayOutput.NONE;
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
        if (!checkInputField(etGpuHostPoolMb, false, 0, 65536)) return false;
        if (!checkInputField(etGpuArenaMb, false, 0, 65536)) return false;
        if (!checkInputField(etGpuGuestPoolMb, false, 0, 65536)) return false;
        if (!checkInputField(etGpuPoolBlobMaxKb, false, 0, 1048576)) return false;
        DisplayOutput displayOutput = chooseDisplayOutput.getSelectedItem();
        if (displayOutput == DisplayOutput.VNC
            && !checkInputField(etVncPort, true, 1024, 65535)) return false;
        if (parent.get("backend", VMBackend.DEFAULT) != VMBackend.CROSVM
            && displayOutput == DisplayOutput.NATIVE)
            return showValidateFailed(R.string.create_vm_error_native_display_only_crosvm);
        // Dynamic vram grows host-visible memory by sharing it at runtime, so it needs the
        // dynamic-sharing mechanism underneath. (Guest-alloc does not: it hands the host
        // dma-bufs out of the guest pool, which is shared once at boot.)
        if (swGpuEnabled.isChecked() && !swGpuUdmabuf.isChecked()
            && swGpuDynamicVram.isChecked() && !isDynamicMemorySharingAvailable())
            return showValidateFailed(dynamicVramNeedsSharingMessage());
        return true;
    }

    /**
     * Whether runtime memory sharing is usable by this VM. Gunyah needs the host module loaded
     * and the accept transport attached, which the basic tab's switch turns on; KVM and gzvm
     * expose it to crosvm directly.
     */
    private boolean isDynamicMemorySharingAvailable() {
        var hypervisor = parent.get("hypervisor", VMHypervisor.DEFAULT);
        if (hypervisor == VMHypervisor.AUTO)
            hypervisor = VMHypervisor.findPreferredHypervisor(
                parent.get("backend", VMBackend.DEFAULT));
        return hypervisor != VMHypervisor.GUNYAH
            || parent.get(VMEditActivity.SHARED_GUNYAH_DYNAMIC_SHARE, false);
    }

    private CharSequence dynamicVramNeedsSharingMessage() {
        return parent.getString(
            R.string.create_vm_error_needs_gunyah_dynamic_share,
            parent.getString(R.string.create_vm_gpu_dynamic_vram));
    }

    @Override
    public void saveConfig(@NonNull VMConfig config) {
        var item = config.item;
        var gpuEnabled = swGpuEnabled.isChecked();
        var displayEnabled = swDisplayEnabled.isChecked();
        // One output, two booleans: crosvm keeps the first display backend that opens (with the
        // Android display service inserted first), so at most one of them may be set.
        DisplayOutput displayOutput = chooseDisplayOutput.getSelectedItem();
        var vncEnabled = displayOutput == DisplayOutput.VNC;
        item.set("gpu_enabled", gpuEnabled);
        item.set("display_enabled", displayEnabled);
        item.set("vnc_enabled", vncEnabled);
        if (gpuEnabled) {
            GpuBackend gb = chooseGpuBackend.getSelectedItem();
            GpuMode gm = chooseGpuMode.getSelectedItem();
            GpuProvider gp = chooseGpuProvider.getSelectedItem();
            item.set("gpu_backend", gb);
            item.set("gpu_mode", gm);
            item.set("gpu_provider", gp);
            // Keep gpu_api written too: the daemon and an older build still read it, and it is
            // exactly recoverable from the pair.
            item.set("gpu_api", toLegacyApi(gm, gp));
            item.set("gpu_udmabuf", swGpuUdmabuf.isChecked());
            item.set("gpu_kgsl_pool_mb", parseInt(getEditText(etGpuKgslPoolMb)));
            item.set("gpu_host_pool_mb", parseInt(getEditText(etGpuHostPoolMb)));
            item.set("gpu_arena_mb", parseInt(getEditText(etGpuArenaMb)));
            item.set("gpu_guest_pool_mb", parseInt(getEditText(etGpuGuestPoolMb)));
            item.set("gpu_dynamic_vram", swGpuDynamicVram.isChecked());
            item.set("gpu_pool_blob_max_kb", parseInt(getEditText(etGpuPoolBlobMaxKb)));
        }
        if (displayEnabled) {
            DisplayBackend displayBackend = chooseDisplayBackend.getSelectedItem();
            item.set("display_backend", displayBackend);
            item.set("native_display_enabled",
                displayOutput == DisplayOutput.NATIVE && isNativeDisplayAllowed());
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

    // What the selected renderer can actually proxy.
    //
    // gfxstream is Vulkan-only here (its GLES and composer capsets are not used, and the guest
    // reaches GL through zink on top of Vulkan). virglrenderer offers OpenGL (capset virgl2)
    // and Native (capset drm). Its third mode, Vulkan via venus, is deliberately absent: this
    // build's libvirglrenderer.so has no venus symbols at all, so offering it could only
    // produce a VM that fails to start.
    private void updateGpuModeOptions() {
        boolean gfxstream = chooseGpuBackend.getSelectedItem() == GPU_GFXSTREAM;
        if (gfxstream) {
            chooseGpuMode.setItems(GpuMode.VULKAN);
            chooseGpuMode.setSelectedItem(GpuMode.VULKAN);
        } else {
            chooseGpuMode.setItems(GpuMode.OPENGL, GpuMode.NATIVE);
            Object cur = chooseGpuMode.getSelectedItem();
            if (cur != GpuMode.OPENGL && cur != GpuMode.NATIVE)
                chooseGpuMode.setSelectedItem(GpuMode.OPENGL);
        }
    }

    // Which host driver serves the proxied calls. Native has none -- the DRM backend is
    // compiled into virglrenderer for the device it runs on (KGSL on Adreno).
    private void updateGpuProviderOptions() {
        boolean gfxstream = chooseGpuBackend.getSelectedItem() == GPU_GFXSTREAM;
        GpuMode mode = chooseGpuMode.getSelectedItem();
        if (gfxstream) {
            chooseGpuProvider.setItems(
                GpuProvider.VK_SYSTEM, GpuProvider.VK_TURNIP, GpuProvider.VK_PANVK);
            if (!isVulkanProvider(chooseGpuProvider.getSelectedItem()))
                chooseGpuProvider.setSelectedItem(GpuProvider.VK_TURNIP);
        } else if (mode == GpuMode.OPENGL) {
            chooseGpuProvider.setItems(GpuProvider.EGL, GpuProvider.GLES);
            if (isVulkanProvider(chooseGpuProvider.getSelectedItem())
                || chooseGpuProvider.getSelectedItem() == GpuProvider.NONE)
                chooseGpuProvider.setSelectedItem(GpuProvider.GLES);
        } else if (mode == GpuMode.NATIVE) {
            // One backend today, but the row stays: it is what makes the config say WHICH DRM
            // driver the host answers with, and it is the key the launcher branches on.
            chooseGpuProvider.setItems(GpuProvider.DRM_KGSL);
            chooseGpuProvider.setSelectedItem(GpuProvider.DRM_KGSL);
        }
        chooseGpuProvider.setVisibility(
            gfxstream || mode == GpuMode.OPENGL || mode == GpuMode.NATIVE ? VISIBLE : GONE);
    }

    // The single value the daemon still reads. Every (mode, provider) pair the UI can produce
    // maps onto one of the old names, so nothing is lost by keeping both written.
    @NonNull
    private static GpuApi toLegacyApi(GpuMode mode, GpuProvider provider) {
        if (mode == GpuMode.NATIVE) return GpuApi.KGSL;
        if (provider == null) return GpuApi.NONE;
        switch (provider) {
            case EGL:       return GpuApi.EGL;
            case GLES:      return GpuApi.OPENGLES;

            case VK_SYSTEM: return GpuApi.VULKAN_SYSTEM;
            case VK_TURNIP: return GpuApi.VULKAN_TURNIP;
            case VK_PANVK:  return GpuApi.VULKAN_PANVK;
            default:        return mode == GpuMode.VULKAN ? GpuApi.VULKAN : GpuApi.NONE;
        }
    }

    private static boolean isVulkanProvider(Object p) {
        return p == GpuProvider.VK_SYSTEM || p == GpuProvider.VK_TURNIP
            || p == GpuProvider.VK_PANVK;
    }

    // Both display producers can present into the app's Surface: virtio-gpu hands over its
    // scanout (needs the GPU enabled), while simplefb has crosvm's bridge poll the guest's
    // linear framebuffer -- no GPU involved. Single source of the rule for the visibility
    // update and for saveConfig; keep in sync with CrosvmBackendInstance.isNativeDisplayEnabled.
    private boolean isNativeDisplayAllowed() {
        DisplayBackend backend = chooseDisplayBackend.getSelectedItem();
        return (backend == DisplayBackend.VIRTIO_GPU && swGpuEnabled.isChecked())
            || backend == SIMPLEFB;
    }

    private void updateGpuVisibility() {
        gpuOptions.setVisibility(swGpuEnabled.isChecked() ? VISIBLE : GONE);
        if (!swGpuEnabled.isChecked()) {
            if (chooseDisplayBackend.getSelectedItem() == DisplayBackend.VIRTIO_GPU)
                chooseDisplayBackend.setSelectedItem(SIMPLEFB);
        }
        updateDisplayOutputVisibility();
        updateDisplayDpiVisibility();
    }

    private void updateDisplayVisibility() {
        displayOptions.setVisibility(swDisplayEnabled.isChecked() ? VISIBLE : GONE);
        updateDisplayOutputVisibility();
    }

    private void updateDisplayOutputVisibility() {
        chooseDisplayOutput.setVisibility(swDisplayEnabled.isChecked() ? VISIBLE : GONE);
        // Never leave an output the current producer cannot drive selected.
        DisplayOutput output = chooseDisplayOutput.getSelectedItem();
        if (output == DisplayOutput.NATIVE && !isNativeDisplayAllowed())
            chooseDisplayOutput.setSelectedItem(DisplayOutput.NONE);
    }

    // DPI only reaches the guest through virtio-gpu's scanout on the native display: simplefb
    // has no mode information to carry it and VNC clients pick their own.
    private void updateDisplayDpiVisibility() {
        DisplayBackend backend = chooseDisplayBackend.getSelectedItem();
        DisplayOutput output = chooseDisplayOutput.getSelectedItem();
        displayDpiOptions.setVisibility(
            backend == DisplayBackend.VIRTIO_GPU && output == DisplayOutput.NATIVE
                ? VISIBLE : GONE);
    }

    private void updateVncVisibility() {
        DisplayOutput output = chooseDisplayOutput.getSelectedItem();
        vncOptions.setVisibility(output == DisplayOutput.VNC ? VISIBLE : GONE);
    }

    private void updateVncPasswordVisibility() {
        vncPasswordOptions.setVisibility(swVncPasswordAuth.isChecked() ? VISIBLE : GONE);
    }

    // VRAM allocation split: with guest-alloc (udmabuf) the guest owns a pre-sized pool
    // (gpu_guest_pool_mb); otherwise the host grows a dynamic arena up to gpu_arena_mb.
    // The host pool stays visible in both modes.
    private void updateVramAllocVisibility() {
        boolean gfxstream = chooseGpuBackend.getSelectedItem() == GPU_GFXSTREAM;
        // The KGSL native context has exactly one memory knob: the boot-blessed pool its BOs are
        // sub-allocated from. None of gfxstream's plumbing applies -- vram-limit and the fusion
        // size gate are gfxstream-only consumers, and a guest-alloc pool cannot exist at all,
        // because the msm/vdrm wire only ever asks for host-allocated blobs.
        boolean kgsl = !gfxstream && chooseGpuMode.getSelectedItem() == GpuMode.NATIVE;
        boolean udmabuf = gfxstream && swGpuUdmabuf.isChecked();
        vramSettings.setVisibility(gfxstream || kgsl ? VISIBLE : GONE);
        tilGpuKgslPoolMb.setVisibility(kgsl ? VISIBLE : GONE);
        swGpuUdmabuf.setVisibility(gfxstream ? VISIBLE : GONE);
        tilGpuHostPoolMb.setVisibility(gfxstream ? VISIBLE : GONE);
        // The guest pool belongs to guest-alloc; dynamic vram is the host-alloc alternative
        // (crosvm ignores a vram limit in guest-alloc mode), so the two never show together.
        tilGpuGuestPoolMb.setVisibility(udmabuf ? VISIBLE : GONE);
        boolean hostAlloc = gfxstream && !udmabuf;
        swGpuDynamicVram.setVisibility(hostAlloc ? VISIBLE : GONE);
        dynamicVramOptions.setVisibility(
            hostAlloc && swGpuDynamicVram.isChecked() ? VISIBLE : GONE);
    }
}
