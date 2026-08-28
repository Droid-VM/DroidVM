// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.graphics;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static java.lang.Integer.parseInt;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.store.vm.GpuBackend.GPU_GFXSTREAM;
import static cn.classfun.droidvm.lib.utils.StringUtils.getEditText;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.TextInputEditText;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.natives.VulkanBlitProbe;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.store.vm.DisplayExporter;
import cn.classfun.droidvm.lib.store.vm.VMScreenConfig;
import cn.classfun.droidvm.lib.store.vm.VpuConfig;
import cn.classfun.droidvm.lib.store.vm.GpuApi;
import cn.classfun.droidvm.lib.store.vm.GpuBackend;
import cn.classfun.droidvm.lib.store.vm.GpuBlitProvider;
import cn.classfun.droidvm.lib.store.vm.GpuMode;
import cn.classfun.droidvm.lib.store.vm.GpuProvider;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class VMEditGraphicsTab extends VMEditBaseTab {
    /** Set while loadConfig() is applying stored values, so enforcement stays silent. */
    private boolean loadingConfig = false;
    /** The config this tab was loaded with; the protection mode is read back out of it. */
    @Nullable
    private VMConfig loadedConfig;
    private View gpuOptions;
    private View rendererSection;
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
    private View dynamicVramHostOptions;
    private View dynamicVramGuestOptions;
    private TextInputEditText etGpuGuestPreallocMb;
    private TextInputEditText etGpuGuestStepMb;
    private TextInputEditText etGpuGuestMaxGrants;
    private TextInputEditText etGpuPoolBlobMaxKb;
    private ChooseRowWidget chooseGpuBackend;
    private ChooseRowWidget chooseGpuMode;
    private ChooseRowWidget chooseGpuProvider;
    private ChooseRowWidget chooseDisplayBlitProvider;
    /** One block per screen, in VMScreenConfig.IDS order. */
    private ScreenBindingRow screenGpu0;
    private ScreenBindingRow screenFb;
    private SwitchRowWidget swGpuCgroup;
    private SwitchRowWidget swVpuEnabled;
    private View vpuOptions;
    private View mediaGuestPoolOptions;
    private TextInputEditText etMediaHostPoolMb;
    private TextInputEditText etMediaGuestPoolMb;
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
        chooseGpuBackend = view.findViewById(R.id.choose_gpu_backend);
        chooseGpuMode = view.findViewById(R.id.choose_gpu_mode);
        chooseGpuProvider = view.findViewById(R.id.choose_gpu_provider);
        gpuOptions = view.findViewById(R.id.gpu_options);
        rendererSection = view.findViewById(R.id.renderer_section);
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
        dynamicVramHostOptions = view.findViewById(R.id.dynamic_vram_host_options);
        dynamicVramGuestOptions = view.findViewById(R.id.dynamic_vram_guest_options);
        etGpuGuestPreallocMb = view.findViewById(R.id.et_gpu_guest_prealloc_mb);
        etGpuGuestStepMb = view.findViewById(R.id.et_gpu_guest_step_mb);
        etGpuGuestMaxGrants = view.findViewById(R.id.et_gpu_guest_max_grants);
        etGpuPoolBlobMaxKb = view.findViewById(R.id.et_gpu_pool_blob_max_kb);
        chooseDisplayBlitProvider = view.findViewById(R.id.choose_display_blit_provider);
        // A new VM comes up with the simplefb screen on and no virtio-gpu device at all until the
        // user asks for one. This is where a new screen's exporter default lives -- the stored
        // config's fallback is a separate question and stays at NONE, see
        // VMScreenConfig.getExporter -- and it is NATIVE rather than the VNC it used to be: the
        // viewer for that one is this app, already installed, so it is the only exporter whose
        // first boot can be looked at without setting something up first. The gpu-0 row's default
        // is inert while its screen is off (save() writes NONE for a screen that is off) and is
        // there for the moment the user turns the device on.
        screenGpu0 = new ScreenBindingRow(VMScreenConfig.ID_GPU0,
            view.findViewById(R.id.screen_gpu0_block),
            view.findViewById(R.id.sw_screen_gpu0_enabled),
            false, DisplayExporter.NATIVE);
        screenFb = new ScreenBindingRow(VMScreenConfig.ID_SIMPLEFB,
            view.findViewById(R.id.screen_fb_block),
            view.findViewById(R.id.sw_screen_fb_enabled),
            true, DisplayExporter.NATIVE);
        swGpuCgroup = view.findViewById(R.id.sw_gpu_cgroup);
        swVpuEnabled = view.findViewById(R.id.sw_vpu_enabled);
        vpuOptions = view.findViewById(R.id.vpu_options);
        mediaGuestPoolOptions = view.findViewById(R.id.media_guest_pool_options);
        etMediaHostPoolMb = view.findViewById(R.id.et_media_host_pool_mb);
        etMediaGuestPoolMb = view.findViewById(R.id.et_media_guest_pool_mb);
        gpuCgroupOptions = view.findViewById(R.id.gpu_cgroup_options);
        etGpuCgroupPath = view.findViewById(R.id.et_gpu_cgroup_path);
        rowGpuCgroupCpus = view.findViewById(R.id.row_gpu_cgroup_cpus);
    }

    @Override
    public void initValue() {
        screenGpu0.init(this::updateDisplayVisibility);
        screenFb.init(this::updateDisplayVisibility);
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
        chooseGpuBackend.configure(GpuBackend.class, GpuBackend.GPU_VIRGLRENDERER);
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
        updateDisplayVisibility();
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
        swVpuEnabled.setOnCheckedChangeListener(() -> updateVpuVisibility());
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
        loadedConfig = config;
        loadingConfig = true;
        try {
            loadConfigLocked(config);
        } finally {
            loadingConfig = false;
        }
    }

    private void loadConfigLocked(@NonNull VMConfig config) {
        var item = config.item;
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
        // Defaults to on: before it had a switch, a vram limit was always handed to crosvm. Over
        // a guest pool "on" just exposes the three growth fields above -- their own defaults
        // (prealloc = pool, step 0) still describe a fully pre-shared pool, so an older config
        // loads with the same behaviour it had. The listener drops it back to off when dynamic
        // sharing is not available to this VM.
        swGpuDynamicVram.setChecked(item.optBoolean("gpu_dynamic_vram", true));
        etGpuPoolBlobMaxKb.setText(String.valueOf(item.optLong("gpu_pool_blob_max_kb", 4096)));
        screenGpu0.load(item);
        screenFb.load(item);
        var gpuBackend = optEnum(item, "gpu_backend", GpuBackend.NONE);
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
        chooseDisplayBlitProvider.setSelectedItem(
            optEnum(item, "display_blit_provider", GpuBlitProvider.TURNIP));
        updateDisplayVisibility();
        updateVramAllocVisibility();
        swGpuCgroup.setChecked(item.optBoolean(CpuPlacementPlan.KEY_GPU_CGROUP, false));
        swVpuEnabled.setChecked(VpuConfig.isEnabled(item));
        etMediaHostPoolMb.setText(String.valueOf(VpuConfig.getHostPoolMb(item)));
        etMediaGuestPoolMb.setText(String.valueOf(VpuConfig.getGuestPoolMb(item)));
        updateVpuVisibility();
        etGpuCgroupPath.setText(item.optString(CpuPlacementPlan.KEY_GPU_CGROUP_PATH,
            CpuPlacementPlan.DEFAULT_GPU_CGROUP_PATH));
        setGpuCgroupCpus(item.optString(CpuPlacementPlan.KEY_GPU_CGROUP_CPUS, ""));
        updateGpuCgroupVisibility();
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
        if (!validateScreenGeometry()) return false;
        if (!checkInputField(etGpuHostPoolMb, false, 0, 65536)) return false;
        if (!checkInputField(etGpuVramQuotaMb, false, 0, 65536)) return false;
        if (!checkInputField(etGpuGuestPoolMb, false, 0, 65536)) return false;
        if (!checkInputField(etGpuPoolBlobMaxKb, false, 0, 1048576)) return false;
        if (swVpuEnabled.isChecked()) {
            if (!checkInputField(etMediaHostPoolMb, false, 0, 65536)) return false;
            // Checked only when it is offered: a hidden field holds whatever the config carried
            // over from another protection mode, and rejecting a value nobody can see or edit
            // would make the VM unsaveable for a reason the screen does not show.
            if (VpuConfig.guestPoolApplies(currentProtectedVm())
                && !checkInputField(etMediaGuestPoolMb, false, 0, 65536)) return false;
        }
        // The growth fields are only read (and only shown) with dynamic vram on over a guest
        // pool; otherwise saveConfig() writes the fully pre-shared values in their place, so a
        // stale entry in a hidden field must not block the save.
        if (isGuestPoolDynamic()) {
            if (!checkInputField(etGpuGuestPreallocMb, false, 0, 65536)) return false;
            if (!checkInputField(etGpuGuestStepMb, false, 0, 65536)) return false;
            if (!checkInputField(etGpuGuestMaxGrants, false, 0, 65536)) return false;
            if (!validateGuestPoolOptions()) return false;
        }
        if (!validateScreens()) return false;
        // Dynamic vram shares memory in at runtime on both paths -- host-visible memory past
        // the pre-alloc pool with host-alloc, the guest pool's growth grants (one memparcel per
        // step) with guest-alloc -- so either way it needs the dynamic-sharing mechanism
        // underneath. The switch refuses to turn on without it; this catches sharing being
        // turned off in the basic tab afterwards.
        if (screenGpu0.isScreenEnabled() && usesVramSettings()
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

    /**
     * Each screen's own geometry, checked on each screen's own rows.
     *
     * <p>Size and rate are picked from menus, so the row itself holds their bounds -- including the
     * one that differs by device: a virtio-gpu mode's refresh rate is what the guest is told to
     * drive, while simplefb's poll rate is how often the host looks at a block of memory (linear
     * host cost, no guest involvement), so it is bounded by what the bridge accepts rather than by
     * what a panel could show. The DPI is still typed, and is still checked here.</p>
     */
    private boolean validateScreenGeometry() {
        for (var row : new ScreenBindingRow[]{screenGpu0, screenFb}) {
            if (!row.validateGeometry()) return false;
            if (row.isGpuScreen()) {
                if (!checkInputField(row.dpiHField(), false, 100, 800)) return false;
                if (!checkInputField(row.dpiVField(), false, 100, 800)) return false;
            }
        }
        return true;
    }

    private boolean validateGuestPoolOptions() {
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

    /** Whether the VRAM block applies at all: gfxstream (either alloc path), drm2kgsl or venus. */
    private boolean usesVramSettings() {
        boolean gfxstream = chooseGpuBackend.getSelectedItem() == GPU_GFXSTREAM;
        return gfxstream || usesGuestPool();
    }

    /**
     * Whether the guest pool grows at runtime: guest-alloc with the dynamic-vram switch on. Off
     * means the whole pool is SHARE'd at boot (prealloc = pool, step 0, no grants), which is
     * what saveConfig() writes and what crosvm's own defaults produce for the three keys.
     */
    private boolean isGuestPoolDynamic() {
        return usesGuestPool() && swGpuDynamicVram.isChecked();
    }

    @Override
    public void onTabShown() {
        // The protection mode is chosen on the basic tab, and it decides whether the guest-alloc
        // pool is offered at all. Nothing tells this tab when that changes, so ask on the way in.
        updateVramAllocVisibility();
        // Same reason: the media guest pool is only offered to a VM whose memory the host cannot
        // read, which is also decided over there.
        updateVpuVisibility();
    }

    /**
     * Whether the host can reach this VM's RAM without being handed it.
     *
     * The guest-alloc pool is a region the host is given access to so that buffers the guest
     * allocates are reachable at all -- in an ordinary protected VM they otherwise are not. An
     * unprotected VM never had that problem, and a pseudo-unprotected one has its whole RAM
     * shared back to the host before the payload runs, so in both the pool is a slice of memory
     * carved out of the guest to solve something that is not happening. virtio-gpu with no pool
     * to find falls back to allocating from system RAM, which is the stock behaviour and works
     * here for exactly the same reason.
     *
     * Live from the basic tab so a just-changed selection counts, then the stored config, then
     * the backend default -- the same order {@code VMEditBootTab.isProtectedVm} reads it in.
     */
    private boolean hostVisibleRam() {
        var pvm = currentProtectedVm();
        return pvm == ProtectedVM.PROTECTED_NORMAL || pvm == ProtectedVM.PSEUDO_UNPROTECTED;
    }

    /**
     * The protection mode this VM will start with: live from the basic tab so a just-changed
     * selection counts, then the stored config, then the backend default.
     */
    @NonNull
    private ProtectedVM currentProtectedVm() {
        ProtectedVM pvm = null;
        try {
            var basic = (VMEditBasicTab) parent.getTab(VMEditTab.TAB_BASIC);
            pvm = basic.getCurrentProtectedVm();
        } catch (Exception ignored) {
        }
        if (pvm == null && loadedConfig != null)
            pvm = optEnum(loadedConfig.item, "protected_vm", ProtectedVM.PROTECTED_WITHOUT_FIRMWARE);
        return pvm == null ? ProtectedVM.PROTECTED_WITHOUT_FIRMWARE : pvm;
    }

    /**
     * The pool fields only exist while the device does, and the guest one only while the host
     * cannot read guest memory. Hiding it is not cosmetic: with no field there is no
     * {@code media_guest} node, and with no node in /reserved-memory the guest driver stays on
     * its stock path of allocating from system RAM.
     */
    private void updateVpuVisibility() {
        boolean enabled = swVpuEnabled.isChecked();
        vpuOptions.setVisibility(enabled ? VISIBLE : GONE);
        mediaGuestPoolOptions.setVisibility(
            enabled && VpuConfig.guestPoolApplies(currentProtectedVm()) ? VISIBLE : GONE);
    }

    /**
     * Whether runtime memory sharing is usable by this VM. Gunyah needs the host module loaded
     * and the accept transport attached, which the basic tab's switch turns on; KVM and gzvm
     * expose it to crosvm directly.
     */
    private boolean isDynamicMemorySharingAvailable() {
        var hypervisor = parent.get("hypervisor", VMHypervisor.DEFAULT);
        hypervisor = VMHypervisor.resolveConfigured(
            parent.get("backend", VMBackend.DEFAULT), hypervisor);
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
        // One binding per screen, written straight through: the pair of VM-level booleans that
        // used to stand in for it could not say which screen either one meant, and could say
        // "both" -- which is the combination crosvm now refuses to start. The geometry rides
        // along inside each block for the same reason -- one width could not be two -- and so
        // does the virtio-gpu device's own existence, which no longer has a switch of its own.
        screenGpu0.save(item);
        screenFb.save(item);
        // The renderer settings describe a device that is only there when the screen is: with the
        // block off they are left exactly as they were, so switching the device off and back on
        // finds the renderer it had rather than a default.
        if (screenGpu0.isScreenEnabled()) {
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
        // Which host Vulkan composites frames into the native display's Surface. Written outside
        // the virtio-gpu block since the simplefb screen's GPU-copy path: it is the native sink's
        // driver choice, and a simplefb-only VM loads it too (the daemon's env predicate is
        // any-native-binding for the same reason).
        item.set("display_blit_provider", chooseDisplayBlitProvider.getSelectedItem());
        item.set(CpuPlacementPlan.KEY_GPU_CGROUP, swGpuCgroup.isChecked());
        VpuConfig.setEnabled(item, swVpuEnabled.isChecked());
        VpuConfig.setHostPoolMb(item, parseInt(getEditText(etMediaHostPoolMb)));
        // Stored even while hidden, so flipping the protection mode back does not lose it. What
        // decides whether a media_guest pool is created is VpuConfig.guestPoolMbFor, not whether
        // the field was on screen.
        VpuConfig.setGuestPoolMb(item, parseInt(getEditText(etMediaGuestPoolMb)));
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
            // virglrenderer has all three capsets: OPENGL (virgl2), NATIVE (drm2kgsl), VULKAN
            // (venus). Only two of them are wired here -- the GL path is listed and refused rather
            // than hidden, so the menu says what virglrenderer is for and where this build stands
            // in it, and a VM stored with OPENGL still shows what it is set to.
            chooseGpuMode.setVisibility(VISIBLE);
            chooseGpuMode.setItems(GpuMode.NATIVE, GpuMode.OPENGL, GpuMode.VULKAN);
            chooseGpuMode.setDisabledItems(
                parent.getString(R.string.create_vm_option_not_implemented), GpuMode.OPENGL);
            // setItems already lands on the first of them (Native Context), which is what a new
            // VM comes up with; a stored mode is restored by load() afterwards. This only catches
            // a selection left over from a set that no longer applies.
            Object cur = chooseGpuMode.getSelectedItem();
            if (cur != GpuMode.OPENGL && cur != GpuMode.NATIVE && cur != GpuMode.VULKAN)
                chooseGpuMode.setSelectedItem(GpuMode.NATIVE);
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

    /**
     * The two screen blocks' visibility pass, run from every switch and picker under them.
     *
     * <p>One pass rather than one per row: the renderer section belongs to the virtio-gpu switch
     * above it and the blit row to that screen's exporter below it, so each row asking only about
     * itself is how a stale combination stays on screen.</p>
     */
    private void updateDisplayVisibility() {
        boolean gpuDevice = screenGpu0.isScreenEnabled();
        screenGpu0.updateVisibility();
        screenFb.updateVisibility();
        // Device off = the whole block folds to its enable switch: renderer, display and export
        // sections all hide (the screen row hides its own two; the renderer section is ours).
        rendererSection.setVisibility(gpuDevice ? VISIBLE : GONE);
        // The GPU blit is no longer the native sink's step alone: the VNC sink dlopens the same
        // driver for a headless blit of its own, so the row follows any binding that could climb
        // to the GPU rung -- which for VNC means one whose ceiling has not been dropped to the CPU
        // copy. With none of those, the row would name a driver nothing loads. It stays the same
        // question the daemon's env predicate asks, asked of the live rows.
        boolean anyBlitBinding = screenGpu0.isGpuBlitBinding() || screenFb.isGpuBlitBinding();
        chooseDisplayBlitProvider.setVisibility(anyBlitBinding ? VISIBLE : GONE);
    }

    /**
     * Enables or disables every control under [root].
     *
     * <p>Recursive because setEnabled on a ViewGroup does not reach its children: a block left
     * looking greyed but still tappable is worse than one that was never greyed, since it takes
     * input for a device that does not exist.</p>
     */
    private static void setRowsEnabled(@NonNull View root, boolean enabled) {
        root.setEnabled(enabled);
        if (!(root instanceof ViewGroup)) return;
        var group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++)
            setRowsEnabled(group.getChildAt(i), enabled);
    }

    /**
     * The rules crosvm would otherwise refuse the command line for, checked here so the refusal
     * is a message in the editor rather than a VM that will not start.
     *
     * <p>"One exporter per screen" needs no check: the schema keys the binding by screen, so it
     * cannot be said twice. Two listeners on one port can be, and crosvm compares the effective
     * ports -- so ports left unnamed are fine (the daemon hands out distinct ones), but two that
     * name the same one are not. One listener per VNC binding again, now that the H.264 stream is
     * served on the RFB port instead of beside it.</p>
     */
    private boolean validateScreens() {
        var crosvm = parent.get("backend", VMBackend.DEFAULT) == VMBackend.CROSVM;
        for (var row : new ScreenBindingRow[]{screenGpu0, screenFb}) {
            if (!row.isScreenEnabled()) continue;
            if (row.getExporter() == DisplayExporter.NATIVE && !crosvm)
                return showValidateFailed(R.string.create_vm_error_native_display_only_crosvm);
            if (row.getExporter() != DisplayExporter.VNC) continue;
            if (!checkInputField(row.portField(), true, 1024, 65535)) return false;
        }
        return validateNoPortCollision();
    }

    /**
     * The named ports, checked pairwise. Reported on the field that repeats one already claimed, so
     * the error lands on the number the user would change rather than on the first one they typed.
     */
    private boolean validateNoPortCollision() {
        var fields = new ArrayList<TextInputEditText>();
        var ports = new ArrayList<Integer>();
        for (var row : new ScreenBindingRow[]{screenGpu0, screenFb}) {
            if (!row.isScreenEnabled() || row.getExporter() != DisplayExporter.VNC) continue;
            fields.add(row.portField());
            ports.add(row.typedVncPort());
        }
        for (var i = 0; i < ports.size(); i++) {
            if (ports.get(i) <= 0) continue;
            for (var j = 0; j < i; j++)
                if (ports.get(j).equals(ports.get(i))) {
                    fields.get(i).setError(
                        parent.getString(R.string.create_vm_error_vnc_port_conflict));
                    return false;
                }
        }
        return true;
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

    // VRAM allocation split: with guest-alloc (udmabuf) the guest owns a pre-sized pool
    // (gpu_guest_pool_mb); otherwise host-visible memory is shared in at runtime, metered
    // against gpu_vram_quota_mb.
    // The host pool stays visible in both modes.
    //
    // The dynamic-vram switch is offered on both paths and gates a different set of fields on
    // each: host-alloc grows host-visible memory past the pre-alloc pool (quota + fusion gate);
    // guest-alloc grows the guest pool past its boot preallocation (prealloc / step / max
    // grants). Both are runtime SHARE, so both need dynamic memory sharing underneath.
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
        boolean guestAlloc = (udmabuf || drm2kgsl || venus) && !hostVisibleRam();
        tilGpuGuestPoolMb.setVisibility(guestAlloc ? VISIBLE : GONE);
        boolean hostAlloc = gfxstream && !udmabuf;
        // One of the two is always true whenever the VRAM block itself is shown, so the switch
        // sits under the pool sizes on every path.
        swGpuDynamicVram.setVisibility(hostAlloc || guestAlloc ? VISIBLE : GONE);
        dynamicVramOptions.setVisibility(
            (hostAlloc || guestAlloc) && swGpuDynamicVram.isChecked() ? VISIBLE : GONE);
        dynamicVramHostOptions.setVisibility(hostAlloc ? VISIBLE : GONE);
        dynamicVramGuestOptions.setVisibility(guestAlloc ? VISIBLE : GONE);
    }
}
