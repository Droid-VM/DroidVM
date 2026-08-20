// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
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
import cn.classfun.droidvm.lib.natives.VulkanBlitProbe;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.store.vm.DisplayBackend;
import cn.classfun.droidvm.lib.store.vm.DisplayOutput;
import cn.classfun.droidvm.lib.store.vm.GpuApi;
import cn.classfun.droidvm.lib.store.vm.GpuBackend;
import cn.classfun.droidvm.lib.store.vm.GpuBlitProvider;
import cn.classfun.droidvm.lib.store.vm.GpuMode;
import cn.classfun.droidvm.lib.store.vm.GpuProvider;
import cn.classfun.droidvm.lib.store.vm.CpuPlacementPlan;
import cn.classfun.droidvm.lib.utils.CpuUtils;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditTab;
import cn.classfun.droidvm.ui.vm.edit.basic.VMEditBasicTab;
import cn.classfun.droidvm.ui.widgets.row.ChooseRowWidget;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextRowWidget;
import cn.classfun.droidvm.ui.widgets.tools.CpuCorePickerDialog;

import java.util.List;
import java.util.Map;

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
    private View tilGpuDrm2KgslPoolMb;
    private View tilGpuHostPoolMb;
    private View tilGpuVenusPoolMb;
    private TextInputEditText etGpuDrm2KgslPoolMb;
    private TextInputEditText etGpuVenusPoolMb;
    private View dynamicVramOptions;
    private View tilGpuGuestPoolMb;
    private SwitchRowWidget swGpuUdmabuf;
    private SwitchRowWidget swGpuRtPrio;
    private SwitchRowWidget swGpuDynamicVram;
    private TextInputEditText etGpuHostPoolMb;
    private TextInputEditText etGpuVramQuotaMb;
    private TextInputEditText etGpuGuestPoolMb;
    private View tilGpuGuestPreallocMb;
    private View tilGpuGuestStepMb;
    private View tilGpuGuestMaxGrants;
    private TextInputEditText etGpuGuestPreallocMb;
    private TextInputEditText etGpuGuestStepMb;
    private TextInputEditText etGpuGuestMaxGrants;
    private TextInputEditText etGpuPoolBlobMaxKb;
    private SwitchRowWidget swGpuEnabled;
    private SwitchRowWidget swVncPasswordAuth;
    private SwitchRowWidget swDisplayEnabled;
    private ChooseRowWidget chooseGpuBackend;
    private ChooseRowWidget chooseGpuMode;
    private ChooseRowWidget chooseGpuProvider;
    private ChooseRowWidget chooseDisplayBackend;
    private ChooseRowWidget chooseDisplayOutput;
    private ChooseRowWidget chooseDisplayBlitProvider;
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
    private SwitchRowWidget swGpuCgroup;
    private View gpuCgroupOptions;
    private TextInputEditText etGpuCgroupPath;
    private TextRowWidget rowGpuCgroupCpus;

    /** Host cores in the GPU worker cpuset, as a CPUSET string. */
    private String gpuCgroupCpus = "";
    /** Host cores as reported by sysfs; read once, drives the cpuset picker. */
    private List<CpuUtils.CpuCore> hostCores = List.of();

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
        swGpuRtPrio = view.findViewById(R.id.sw_gpu_rt_prio);
        vramSettings = view.findViewById(R.id.vram_settings);
        swGpuDynamicVram = view.findViewById(R.id.sw_gpu_dynamic_vram);
        dynamicVramOptions = view.findViewById(R.id.dynamic_vram_options);
        tilGpuGuestPoolMb = view.findViewById(R.id.til_gpu_guest_pool_mb);
        tilGpuDrm2KgslPoolMb = view.findViewById(R.id.til_gpu_drm2kgsl_pool_mb);
        tilGpuHostPoolMb = view.findViewById(R.id.til_gpu_host_pool_mb);
        tilGpuVenusPoolMb = view.findViewById(R.id.til_gpu_venus_pool_mb);
        etGpuDrm2KgslPoolMb = view.findViewById(R.id.et_gpu_drm2kgsl_pool_mb);
        etGpuHostPoolMb = view.findViewById(R.id.et_gpu_host_pool_mb);
        etGpuVenusPoolMb = view.findViewById(R.id.et_gpu_venus_pool_mb);
        etGpuVramQuotaMb = view.findViewById(R.id.et_gpu_vram_quota_mb);
        etGpuGuestPoolMb = view.findViewById(R.id.et_gpu_guest_pool_mb);
        tilGpuGuestPreallocMb = view.findViewById(R.id.til_gpu_guest_prealloc_mb);
        tilGpuGuestStepMb = view.findViewById(R.id.til_gpu_guest_step_mb);
        tilGpuGuestMaxGrants = view.findViewById(R.id.til_gpu_guest_max_grants);
        etGpuGuestPreallocMb = view.findViewById(R.id.et_gpu_guest_prealloc_mb);
        etGpuGuestStepMb = view.findViewById(R.id.et_gpu_guest_step_mb);
        etGpuGuestMaxGrants = view.findViewById(R.id.et_gpu_guest_max_grants);
        etGpuPoolBlobMaxKb = view.findViewById(R.id.et_gpu_pool_blob_max_kb);
        swDisplayEnabled = view.findViewById(R.id.sw_display_enabled);
        chooseDisplayBackend = view.findViewById(R.id.choose_display_backend);
        chooseDisplayOutput = view.findViewById(R.id.choose_display_output);
        chooseDisplayBlitProvider = view.findViewById(R.id.choose_display_blit_provider);
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
        swGpuCgroup = view.findViewById(R.id.sw_gpu_cgroup);
        gpuCgroupOptions = view.findViewById(R.id.gpu_cgroup_options);
        etGpuCgroupPath = view.findViewById(R.id.et_gpu_cgroup_path);
        rowGpuCgroupCpus = view.findViewById(R.id.row_gpu_cgroup_cpus);
    }

    @Override
    public void initValue() {
        swGpuEnabled.setOnCheckedChangeListener(this::updateGpuVisibility);
        swDisplayEnabled.setOnCheckedChangeListener(this::updateDisplayVisibility);
        // PanVK (Mali) is listed but not wired yet: toast + revert to the previous choice.
        // Acceleration decides which host drivers make sense and which memory knobs exist,
        // so it drives both of the rows under it.
        chooseGpuMode.setOnValueChangedListener((o, n) -> {
            // Vulkan on virglrenderer is venus (guest Vulkan proxied to the host over virtio-gpu);
            // its provider row is the host ICD, same as gfxstream's, so this is just a normal mode
            // change like NATIVE/OPENGL.
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
        // virgl/2d picks a guest GL/Vulkan translation API or the drm2kgsl native context.
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
        // GPU-blit provider for the native display's scanout composite -- a peer set of Vulkan
        // drivers plus an Off (CPU copy) escape. TURNIP/SYSTEM/OFF are wired (SYSTEM points the
        // bridge at the SoC's vendor Vulkan and degrades to the CPU copy if it lacks the extensions);
        // only PANVK is not built yet, so it toasts + reverts like the render provider's PanVK.
        chooseDisplayBlitProvider.configure(GpuBlitProvider.class, GpuBlitProvider.TURNIP);
        chooseDisplayBlitProvider.setOnValueChangedListener((o, n) -> {
            if (n == GpuBlitProvider.PANVK) {
                Toast.makeText(parent, R.string.create_vm_gpu_api_not_implemented,
                    Toast.LENGTH_SHORT).show();
                chooseDisplayBlitProvider.setSelectedItem(
                    o instanceof GpuBlitProvider ? (GpuBlitProvider) o : GpuBlitProvider.TURNIP);
            } else if (n == GpuBlitProvider.SYSTEM) {
                // The system driver is a peer, gated by the same rule as the others: it is usable
                // only if it exposes the blit's extensions. Probe the real driver and, if it is
                // short, say which -- the choice is still allowed (crosvm then degrades to a CPU
                // copy), the user just gets told instead of quietly getting the fallback.
                warnIfSystemBlitIncapable();
            }
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
        initGpuCgroup();
    }

    private void initGpuCgroup() {
        hostCores = CpuUtils.getCores();
        etGpuCgroupPath.setText(CpuPlacementPlan.DEFAULT_GPU_CGROUP_PATH);
        swGpuCgroup.setOnCheckedChangeListener(() -> {
            // Default to the top (usually prime) core, the one worth keeping vCPUs off.
            if (swGpuCgroup.isChecked() && gpuCgroupCpus.trim().isEmpty()
                && !hostCores.isEmpty())
                setGpuCgroupCpus(String.valueOf(hostCores.get(hostCores.size() - 1).index));
            updateGpuCgroupVisibility();
        });
        rowGpuCgroupCpus.setOnClickListener(v -> showGpuCpusPicker());
        // RT is only offered on top of a cpuset with cores in it. The switch is inside the
        // cpuset block, so it is already hidden when the block is off; this catches the one
        // reachable gap -- the block on, but every core cleared in the picker.
        //
        // Not while loading: loadConfig() restores this switch before the cpuset rows below it,
        // so the guard would see an empty core list for every VM and clear a legitimately stored
        // RT setting. The updateGpuCgroupVisibility() at the end of the load enforces the same
        // rule once both halves are in place.
        swGpuRtPrio.setOnCheckedChangeListener(() -> {
            if (loadingConfig) return;
            if (swGpuRtPrio.isChecked() && !hasGpuCgroupCpus()) {
                swGpuRtPrio.setChecked(false);
                showHint(parent.getString(
                    R.string.create_vm_error_gpu_rt_prio_needs_cpuset));
            }
        });
        // Unconditionally, so the row always carries a value label ("none selected"
        // when empty) rather than a bare title with a blank right-hand side.
        setGpuCgroupCpus(gpuCgroupCpus);
        updateGpuCgroupVisibility();
    }

    private void updateGpuCgroupVisibility() {
        boolean enabled = swGpuCgroup.isChecked();
        gpuCgroupOptions.setVisibility(enabled ? VISIBLE : GONE);
        // RT lives inside the block, so turning the cpuset off hides it. Clear it rather than
        // leaving a checked-but-invisible switch: without the cpuset, SCHED_FIFO 97 GPU threads
        // are free to take every host core above everything else on the phone. Same for a
        // stored config that has RT on with the block on but no cores named -- the cpuset would
        // never be set up (the daemon skips an empty cpus list), so the confinement is absent.
        if (!enabled || !hasGpuCgroupCpus()) swGpuRtPrio.setChecked(false);
    }

    /** Whether the cpuset actually names cores; RT is refused without them. */
    private boolean hasGpuCgroupCpus() {
        return !gpuCgroupCpus.trim().isEmpty();
    }

    private void showGpuCpusPicker() {
        CpuCorePickerDialog.show(
            parent, R.string.create_vm_gpu_cgroup_cpus, gpuCgroupCpus,
            picked -> {
                setGpuCgroupCpus(picked);
                // Clearing the last core takes RT down with it, for the same reason the switch
                // refuses to come up without cores.
                if (!hasGpuCgroupCpus() && swGpuRtPrio.isChecked()) {
                    swGpuRtPrio.setChecked(false);
                    showHint(parent.getString(
                        R.string.create_vm_error_gpu_rt_prio_needs_cpuset));
                }
                warnOnGpuCgroupOverlap();
            });
    }

    private void setGpuCgroupCpus(@NonNull String cpuSet) {
        gpuCgroupCpus = cpuSet;
        var compact = CpuUtils.compactRanges(cpuSet);
        rowGpuCgroupCpus.setValue(compact.isEmpty()
            ? parent.getString(R.string.create_vm_gpu_cgroup_cpus_none)
            : parent.getString(R.string.create_vm_gpu_cgroup_cpus_summary_fmt, compact));
    }

    /**
     * Sharing a core between vCPUs and the GPU worker is allowed -- on a host with
     * few big cores it is often the only workable split -- but it defeats the point
     * of the cpuset, so say so instead of silently accepting it. The affinity lives
     * in the basic tab, so it is read from there rather than held here.
     */
    private void warnOnGpuCgroupOverlap() {
        if (!swGpuCgroup.isChecked()) return;
        Map<Integer, List<Integer>> affinity = Map.of();
        try {
            var basic = (VMEditBasicTab) parent.getTab(VMEditTab.TAB_BASIC);
            affinity = basic.getCurrentAffinity();
        } catch (Exception ignored) {
        }
        if (affinity.isEmpty()) return;
        var shared = CpuPlacementPlan.findHostOverlaps(affinity, gpuCgroupCpus);
        if (shared.isEmpty()) return;
        var sb = new StringBuilder();
        for (var core : shared) {
            if (sb.length() > 0) sb.append(',');
            sb.append(core);
        }
        showHint(parent.getString(
            R.string.create_vm_cpu_affinity_host_overlap, sb.toString()));
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
        swGpuUdmabuf.setChecked(item.optBoolean("gpu_udmabuf", true));
        // SCHED_FIFO on the GPU worker; gpu_rt_prio holds the level as a string ("" by default),
        // and "" means "do not set RT at all". Off (the default) -> normal scheduling, which avoids
        // the gfxstream render-thread priority inversion that starves the present path. The cpuset
        // rows are restored at the end of this method; updateGpuCgroupVisibility() there clears
        // this again if the config carries RT without a cpuset to confine it.
        swGpuRtPrio.setChecked(!item.optString("gpu_rt_prio", "").isEmpty());
        // The two host pools hold very different things, so they are sized very differently.
        //
        // The DRM route's host pool holds only the per-context msm shmem rings: 16 KiB each,
        // 192 KiB measured across a desktop plus Minecraft, on top of the 2 MiB the RM base guard
        // takes at the head of the pool. 8 MiB is room for ~380 contexts.
        etGpuDrm2KgslPoolMb.setText(String.valueOf(item.optLong("gpu_drm2kgsl_pool_mb", 8)));
        // gfxstream's holds its ASG rings, one per guest context, at 1036 KiB each -- a desktop
        // plus Minecraft was 16 of them, 18.2 MiB including the guard. 64 MiB is room for ~61
        // contexts, and this is mlocked physical memory, so the spare is not free.
        //
        // It is a floor rather than a cap: with guest-alloc off, host-visible VkDeviceMemory
        // comes from here too and whatever does not fit falls back to runtime SHARE, which is
        // what the dynamic-VRAM switch below governs. So a pool that is too small costs speed,
        // not correctness -- 64 MiB leaves ~46 MiB of that fast path once the rings are in.
        etGpuHostPoolMb.setText(String.valueOf(item.optLong("gpu_host_pool_mb", 64)));
        // venus's holds its per-instance transport shmems (ring 128K + CS pool >=8M + reply pool
        // >=1M); vkr sub-allocates them here and the guest maps pool-relative with no runtime SHARE.
        // Bigger avoids a memfd fallback under KDE's several Vulkan instances; but on a small-
        // reservoir device (e.g. the 8gen3's 5.5 GB cap) it competes with guest RAM + the guest
        // pool, so it is exposed here to tune per device rather than hard-wired to the daemon default.
        etGpuVenusPoolMb.setText(String.valueOf(item.optLong("gpu_venus_pool_mb", 256)));
        // A quota, not an allocation: crosvm meters host-visible memory against this rather
        // than reserving any. Only read when guest-alloc is off.
        etGpuVramQuotaMb.setText(String.valueOf(item.optLong("gpu_vram_quota_mb", 2048)));
        long guestPool = item.optLong("gpu_guest_pool_mb", 1024);
        etGpuGuestPoolMb.setText(String.valueOf(guestPool));
        etGpuGuestPreallocMb.setText(String.valueOf(
            item.optLong("gpu_guest_prealloc_mb", guestPool)));
        etGpuGuestStepMb.setText(String.valueOf(item.optLong("gpu_guest_step_mb", 0)));
        etGpuGuestMaxGrants.setText(String.valueOf(item.optLong("gpu_guest_max_grants", 0)));
        // Defaults to on: before it had a switch, a vram limit was always handed to crosvm.
        swGpuDynamicVram.setChecked(item.optBoolean("gpu_dynamic_vram", true));
        etGpuPoolBlobMaxKb.setText(String.valueOf(item.optLong("gpu_pool_blob_max_kb", 4096)));
        swDisplayEnabled.setChecked(item.optBoolean("display_enabled", false));
        etDisplayWidth.setText(String.valueOf(item.optLong("display_width", 1280)));
        etDisplayHeight.setText(String.valueOf(item.optLong("display_height", 720)));
        etDisplayRefreshRate.setText(String.valueOf(item.optLong("display_refresh_rate", 120)));
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
        // gfxstream only offers VULKAN; virglrenderer offers OPENGL/NATIVE/VULKAN(venus). Restore
        // any mode the current backend actually lists, or the picker keeps the default and a saved
        // venus VM silently reloads as something else.
        boolean modeOk = gfx ? (gpuMode == GpuMode.VULKAN)
            : (gpuMode == GpuMode.OPENGL || gpuMode == GpuMode.NATIVE || gpuMode == GpuMode.VULKAN);
        if (gpuMode != GpuMode.NONE && modeOk)
            chooseGpuMode.setSelectedItem(gpuMode);
        updateGpuProviderOptions();
        // The VK_* providers belong to VULKAN mode on either renderer (gfxstream, or venus on
        // virglrenderer); everything else is virglrenderer-only. Restore only what the row lists.
        // (Read the mode picker only under virglrenderer: 2D leaves it unset.)
        boolean vkMode = gfx
            || (chooseGpuBackend.getSelectedItem() == GpuBackend.GPU_VIRGLRENDERER
                && chooseGpuMode.getSelectedItem() == GpuMode.VULKAN);
        if (gpuProvider != GpuProvider.NONE && vkMode == isVulkanProvider(gpuProvider))
            chooseGpuProvider.setSelectedItem(gpuProvider);
        if (displayBackend != DisplayBackend.NONE)
            chooseDisplayBackend.setSelectedItem(displayBackend);
        // The output side is UI-only: derive the single choice from the two persisted booleans.
        // Native wins if both are set -- that is what crosvm does anyway (it keeps the first
        // display backend that opens and the Android display service is inserted first).
        chooseDisplayOutput.setSelectedItem(readDisplayOutput(config));
        chooseDisplayBlitProvider.setSelectedItem(
            optEnum(item, "display_blit_provider", GpuBlitProvider.TURNIP));
        updateGpuVisibility();
        updateDisplayVisibility();
        updateDisplayOutputVisibility();
        updateDisplayDpiVisibility();
        updateVncVisibility();
        updateVncPasswordVisibility();
        updateVramAllocVisibility();
        swGpuCgroup.setChecked(item.optBoolean(CpuPlacementPlan.KEY_GPU_CGROUP, false));
        etGpuCgroupPath.setText(item.optString(CpuPlacementPlan.KEY_GPU_CGROUP_PATH,
            CpuPlacementPlan.DEFAULT_GPU_CGROUP_PATH));
        setGpuCgroupCpus(item.optString(CpuPlacementPlan.KEY_GPU_CGROUP_CPUS, ""));
        updateGpuCgroupVisibility();
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
        if (!checkInputField(etGpuVramQuotaMb, false, 0, 65536)) return false;
        if (!checkInputField(etGpuGuestPoolMb, false, 0, 65536)) return false;
        if (!checkInputField(etGpuGuestPreallocMb, false, 0, 65536)) return false;
        if (!checkInputField(etGpuGuestStepMb, false, 0, 65536)) return false;
        if (!checkInputField(etGpuGuestMaxGrants, false, 0, 65536)) return false;
        if (!checkInputField(etGpuPoolBlobMaxKb, false, 0, 1048576)) return false;
        if (!validateGuestPoolOptions()) return false;
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
        if (swGpuCgroup.isChecked()) {
            var path = getEditText(etGpuCgroupPath).trim();
            if (path.isEmpty() || !path.startsWith("/"))
                return showValidateFailed(R.string.create_vm_error_gpu_cgroup_path);
            if (gpuCgroupCpus.trim().isEmpty())
                return showValidateFailed(R.string.create_vm_error_gpu_cgroup_cpus_empty);
        }
        return true;
    }

    private boolean validateGuestPoolOptions() {
        if (!usesGuestPool()) return true;
        int guestPool = parseInt(getEditText(etGpuGuestPoolMb));
        int prealloc = parseInt(getEditText(etGpuGuestPreallocMb));
        int step = parseInt(getEditText(etGpuGuestStepMb));
        if (prealloc > guestPool) {
            etGpuGuestPreallocMb.setError(
                parent.getString(R.string.create_vm_error_gpu_guest_prealloc_exceeds_pool));
            return false;
        }
        if (step > 0 && prealloc >= guestPool) {
            etGpuGuestPreallocMb.setError(
                parent.getString(R.string.create_vm_error_gpu_guest_dynamic_requires_room));
            return false;
        }
        if (step > 0 && (step < 2 || (step & (step - 1)) != 0)) {
            etGpuGuestStepMb.setError(
                parent.getString(R.string.create_vm_error_gpu_guest_step_invalid));
            return false;
        }
        return true;
    }

    private boolean usesGuestPool() {
        boolean gfxstream = chooseGpuBackend.getSelectedItem() == GPU_GFXSTREAM;
        // Read the mode only under virglrenderer: 2D clears the mode picker and reading it would
        // throw "Items not set". drm2kgsl only exists as virglrenderer's NATIVE mode anyway.
        boolean drm2kgsl = chooseGpuBackend.getSelectedItem() == GpuBackend.GPU_VIRGLRENDERER
            && chooseGpuMode.getSelectedItem() == GpuMode.NATIVE;
        // Venus (virglrenderer + VULKAN) allocates its BOs from the same guest pool.
        boolean venus = chooseGpuBackend.getSelectedItem() == GpuBackend.GPU_VIRGLRENDERER
            && chooseGpuMode.getSelectedItem() == GpuMode.VULKAN;
        return drm2kgsl || venus || (gfxstream && swGpuUdmabuf.isChecked());
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
            // 2D clears the mode/provider pickers, so reading them would throw "Items not set".
            // Persist NONE for both in that case.
            boolean accel = gb == GPU_GFXSTREAM || gb == GpuBackend.GPU_VIRGLRENDERER;
            GpuMode gm = accel ? chooseGpuMode.getSelectedItem() : GpuMode.NONE;
            GpuProvider gp = accel ? chooseGpuProvider.getSelectedItem() : GpuProvider.NONE;
            item.set("gpu_backend", gb);
            item.set("gpu_mode", gm);
            item.set("gpu_provider", gp);
            // Keep gpu_api written too: the daemon and an older build still read it, and it is
            // exactly recoverable from the pair.
            item.set("gpu_api", toLegacyApi(gm, gp));
            item.set("gpu_udmabuf", swGpuUdmabuf.isChecked());
            // "97" only with a cpuset holding cores under it -- the editor cannot produce any
            // other combination, and writing "" for the rest keeps a config that was edited with
            // the cpuset turned off from carrying unconfined RT forward.
            boolean rtPrio = swGpuRtPrio.isChecked()
                && swGpuCgroup.isChecked() && hasGpuCgroupCpus();
            item.set("gpu_rt_prio", rtPrio ? "97" : "");
            item.set("gpu_drm2kgsl_pool_mb", parseInt(getEditText(etGpuDrm2KgslPoolMb)));
            item.set("gpu_host_pool_mb", parseInt(getEditText(etGpuHostPoolMb)));
            item.set("gpu_venus_pool_mb", parseInt(getEditText(etGpuVenusPoolMb)));
            item.set("gpu_vram_quota_mb", parseInt(getEditText(etGpuVramQuotaMb)));
            int guestPool = parseInt(getEditText(etGpuGuestPoolMb));
            item.set("gpu_guest_pool_mb", guestPool);
            // With dynamic vram off over a guest pool the growth knobs collapse to "fully
            // pre-shared": preallocate the whole pool, no step, no grants. That is also what an
            // older daemon assumes when the keys are absent, so the stored config stays honest
            // even though the fields keep their last typed values while hidden.
            if (isGuestPoolDynamic()) {
                item.set("gpu_guest_prealloc_mb", parseInt(getEditText(etGpuGuestPreallocMb)));
                item.set("gpu_guest_step_mb", parseInt(getEditText(etGpuGuestStepMb)));
                item.set("gpu_guest_max_grants", parseInt(getEditText(etGpuGuestMaxGrants)));
            } else {
                item.set("gpu_guest_prealloc_mb", guestPool);
                item.set("gpu_guest_step_mb", 0);
                item.set("gpu_guest_max_grants", 0);
            }
            item.set("gpu_dynamic_vram", swGpuDynamicVram.isChecked());
            item.set("gpu_pool_blob_max_kb", parseInt(getEditText(etGpuPoolBlobMaxKb)));
        }
        if (displayEnabled) {
            DisplayBackend displayBackend = chooseDisplayBackend.getSelectedItem();
            item.set("display_backend", displayBackend);
            item.set("display_blit_provider", chooseDisplayBlitProvider.getSelectedItem());
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
        item.set(CpuPlacementPlan.KEY_GPU_CGROUP, swGpuCgroup.isChecked());
        item.set(CpuPlacementPlan.KEY_GPU_CGROUP_PATH, getEditText(etGpuCgroupPath).trim());
        item.set(CpuPlacementPlan.KEY_GPU_CGROUP_CPUS, gpuCgroupCpus.trim());
    }

    // What the selected renderer can actually proxy.
    //
    // gfxstream is Vulkan-only here (its GLES and composer capsets are not used, and the guest
    // reaches GL through zink on top of Vulkan). virglrenderer offers OpenGL (capset virgl2),
    // Native (capset drm) and Vulkan (capset venus -- "Vulkan on virglrenderer" is venus by
    // definition, so the mode row is where venus lives; the provider row below picks its ICD).
    private void updateGpuModeOptions() {
        var backend = chooseGpuBackend.getSelectedItem();
        if (backend == GPU_GFXSTREAM) {
            // gfxstream proxies Vulkan and nothing else here.
            chooseGpuMode.setVisibility(VISIBLE);
            chooseGpuMode.setItems(GpuMode.VULKAN);
            chooseGpuMode.setSelectedItem(GpuMode.VULKAN);
        } else if (backend == GpuBackend.GPU_VIRGLRENDERER) {
            // virglrenderer serves all three: OPENGL (host GL), NATIVE (drm2kgsl), VULKAN (venus).
            chooseGpuMode.setVisibility(VISIBLE);
            chooseGpuMode.setItems(GpuMode.NATIVE, GpuMode.OPENGL, GpuMode.VULKAN);
            Object cur = chooseGpuMode.getSelectedItem();
            if (cur != GpuMode.OPENGL && cur != GpuMode.NATIVE && cur != GpuMode.VULKAN)
                chooseGpuMode.setSelectedItem(GpuMode.OPENGL);
        } else {
            // 2D has no acceleration to choose. setItems() rejects an empty list, so hide the row
            // rather than clearing it; its stale items are never read for 2D (the reads below are
            // all gated on the backend being gfxstream or virglrenderer).
            chooseGpuMode.setVisibility(GONE);
        }
    }

    // Which host driver serves the proxied calls. Native has none -- the DRM backend is
    // compiled into virglrenderer for the device it runs on (KGSL on Adreno).
    private void updateGpuProviderOptions() {
        var backend = chooseGpuBackend.getSelectedItem();
        boolean gfxstream = backend == GPU_GFXSTREAM;
        boolean twoD = backend != GPU_GFXSTREAM && backend != GpuBackend.GPU_VIRGLRENDERER;
        if (twoD) {
            // 2D has no host driver. setItems() rejects an empty list, so just hide the row (its
            // stale items are never read for 2D). Bail before the mode read below, which would be
            // reading the hidden mode picker.
            chooseGpuProvider.setVisibility(GONE);
            return;
        }
        GpuMode mode = chooseGpuMode.getSelectedItem();
        if (gfxstream || mode == GpuMode.VULKAN) {
            // Host Vulkan ICD. gfxstream is always in this mode; on virglrenderer it is venus.
            // Both dlopen whatever ANDROID_EMU_VK_LOADER_PATH names (or the system loader when it
            // is unset), so the choice is identical: bundled turnip, PanVK, or the SoC's stock HAL.
            chooseGpuProvider.setItems(
                GpuProvider.VK_TURNIP, GpuProvider.VK_PANVK, GpuProvider.VK_SYSTEM);
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
            chooseGpuProvider.setItems(GpuProvider.DRM2KGSL);
            chooseGpuProvider.setSelectedItem(GpuProvider.DRM2KGSL);
        }
        chooseGpuProvider.setVisibility(
            gfxstream || mode == GpuMode.OPENGL || mode == GpuMode.NATIVE
                || mode == GpuMode.VULKAN ? VISIBLE : GONE);
    }

    // The single value the daemon still reads. Every (mode, provider) pair the UI can produce
    // maps onto one of the old names, so nothing is lost by keeping both written.
    @NonNull
    private static GpuApi toLegacyApi(GpuMode mode, GpuProvider provider) {
        if (mode == GpuMode.NATIVE) return GpuApi.DRM2KGSL;
        if (provider == null) return GpuApi.NONE;
        switch (provider) {
            case EGL:       return GpuApi.EGL;
            case GLES:      return GpuApi.OPENGLES;

            // VULKAN mode on either renderer: gfxstream and venus both read the host ICD choice
            // from these three (CrosvmBackendInstance.applyGfxstreamEnv).
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
        // The GPU-blit provider and the DPI rows share a trigger: both are meaningful only for the
        // accelerated scanout, i.e. virtio-gpu presented on the native display. VNC and simplefb
        // composite through crosvm's CPU copy, which ignores both.
        boolean accelScanout =
            backend == DisplayBackend.VIRTIO_GPU && output == DisplayOutput.NATIVE;
        displayDpiOptions.setVisibility(accelScanout ? VISIBLE : GONE);
        chooseDisplayBlitProvider.setVisibility(accelScanout ? VISIBLE : GONE);
    }

    // Probing Vulkan touches a driver, so do it off the UI thread and report once via a hint.
    // SYSTEM stays selected regardless of the outcome -- a miss just means crosvm will fall back
    // to a CPU copy, which we tell the user about instead of letting it happen silently. A null
    // result (no Vulkan / no device to ask) is treated as "unknown" and says nothing.
    private void warnIfSystemBlitIncapable() {
        new Thread(() -> {
            String[] missing = VulkanBlitProbe.missingBlitExtensions();
            if (missing == null || missing.length == 0) return;
            view.post(() -> {
                if (chooseDisplayBlitProvider.getSelectedItem() == GpuBlitProvider.SYSTEM)
                    showHint(parent.getString(R.string.display_blit_system_missing_ext,
                        String.join("\n  ", missing)));
            });
        }, "vkprobe-blit").start();
    }

    private void updateVncVisibility() {
        DisplayOutput output = chooseDisplayOutput.getSelectedItem();
        vncOptions.setVisibility(output == DisplayOutput.VNC ? VISIBLE : GONE);
    }

    private void updateVncPasswordVisibility() {
        vncPasswordOptions.setVisibility(swVncPasswordAuth.isChecked() ? VISIBLE : GONE);
    }

    // VRAM allocation split: with guest-alloc (udmabuf) the guest owns a pre-sized pool
    // (gpu_guest_pool_mb); otherwise host-visible memory is shared in at runtime, metered
    // against gpu_vram_quota_mb.
    // The host pool stays visible in both modes.
    private void updateVramAllocVisibility() {
        boolean gfxstream = chooseGpuBackend.getSelectedItem() == GPU_GFXSTREAM;
        // The DRM native context has two: a guest pool every BO is allocated from, and a small
        // host pool left holding only the per-context msm shmem rings. None of gfxstream's other
        // plumbing applies -- vram-limit and the fusion size gate have gfxstream-only consumers.
        // Read the mode only under virglrenderer: 2D clears the mode picker and reading it would
        // throw "Items not set". drm2kgsl only exists as virglrenderer's NATIVE mode anyway.
        boolean drm2kgsl = chooseGpuBackend.getSelectedItem() == GpuBackend.GPU_VIRGLRENDERER
            && chooseGpuMode.getSelectedItem() == GpuMode.NATIVE;
        // Venus (virglrenderer + VULKAN) also allocates every BO from the guest pool; its host
        // pool (venus-host-mb) holds the venus transport shmems and is tunable via its own field.
        boolean venus = chooseGpuBackend.getSelectedItem() == GpuBackend.GPU_VIRGLRENDERER
            && chooseGpuMode.getSelectedItem() == GpuMode.VULKAN;
        boolean udmabuf = gfxstream && swGpuUdmabuf.isChecked();
        vramSettings.setVisibility(gfxstream || drm2kgsl || venus ? VISIBLE : GONE);
        // The three host pools each hold only a renderer's command-stream/transport buffers, so the
        // UI labels them all "command buffer pool size"; exactly one is shown for the active mode.
        tilGpuDrm2KgslPoolMb.setVisibility(drm2kgsl ? VISIBLE : GONE);
        swGpuUdmabuf.setVisibility(gfxstream ? VISIBLE : GONE);
        tilGpuHostPoolMb.setVisibility(gfxstream ? VISIBLE : GONE);
        tilGpuVenusPoolMb.setVisibility(venus ? VISIBLE : GONE);
        // The guest-allocated pool is the same region and the same flag for both renderers -- the
        // guest driver keeps one allocator -- so it is offered wherever guest-alloc is in use:
        // gfxstream with udmabuf on, and the DRM native context always (every BO comes from it).
        boolean guestAlloc = udmabuf || drm2kgsl || venus;
        tilGpuGuestPoolMb.setVisibility(guestAlloc ? VISIBLE : GONE);
        tilGpuGuestPreallocMb.setVisibility(guestAlloc ? VISIBLE : GONE);
        tilGpuGuestStepMb.setVisibility(guestAlloc ? VISIBLE : GONE);
        tilGpuGuestMaxGrants.setVisibility(guestAlloc ? VISIBLE : GONE);
        boolean hostAlloc = gfxstream && !udmabuf;
        swGpuDynamicVram.setVisibility(hostAlloc ? VISIBLE : GONE);
        dynamicVramOptions.setVisibility(
            hostAlloc && swGpuDynamicVram.isChecked() ? VISIBLE : GONE);
    }
}
