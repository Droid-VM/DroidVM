// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import static android.net.LocalSocketAddress.Namespace.FILESYSTEM;
import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.Constants.PATH_EDK2_FIRMWARE;
import static cn.classfun.droidvm.lib.Constants.PATH_EDK2_VARS;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.store.vm.DisplayBackend.SIMPLEFB;
import static cn.classfun.droidvm.lib.store.vm.DisplayBackend.VIRTIO_GPU;
import static cn.classfun.droidvm.lib.store.vm.GpuApi.VULKAN;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.FileUtils.deleteFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.daemon.console.FDPipeConsoleStream;
import cn.classfun.droidvm.daemon.console.InputConsoleStream;
import cn.classfun.droidvm.daemon.console.SimpleConsoleStream;
import cn.classfun.droidvm.daemon.server.ServerContext;
import cn.classfun.droidvm.daemon.vm.BootPlan;
import cn.classfun.droidvm.daemon.vm.SerialPipe;
import cn.classfun.droidvm.daemon.vm.VMBackendInstance;
import cn.classfun.droidvm.daemon.vm.VMStartResult;
import cn.classfun.droidvm.lib.natives.NativeProcess;
import cn.classfun.droidvm.lib.utils.RunUtils;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.vm.CpuPlacementPlan;
import cn.classfun.droidvm.lib.store.vm.DisplayBackend;
import cn.classfun.droidvm.lib.store.vm.GpuApi;
import cn.classfun.droidvm.lib.store.vm.GpuMode;
import cn.classfun.droidvm.lib.store.vm.GpuBackend;
import cn.classfun.droidvm.lib.store.vm.GpuBlitProvider;
import cn.classfun.droidvm.lib.store.vm.LendMthpMode;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.SharedDirCache;
import cn.classfun.droidvm.lib.store.vm.SharedDirType;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;

@SuppressWarnings("FieldCanBeLocal")
public final class CrosvmBackendInstance extends VMBackendInstance {
    private static final String TAG = "CrosvmBackendInstance";
    private static final String RUN_PATH = pathJoin(DATA_DIR, "run");
    private SerialPipe uart = null;
    private String controlSocketPath = null;
    /** Set by prepareGpuCgroup() once the cpuset exists and holds cores; else null. */
    private String gpuCgroupPath = null;
    /** Owns the per-VM native-display input sockets (crosvm-facing + UI-facing); see start(). */
    private final NativeDisplayInputBridge inputBridge = new NativeDisplayInputBridge();
    private final FDPipeConsoleStream uartStream;
    private final InputConsoleStream stdoutStream;
    private final InputConsoleStream stderrStream;
    private final SimpleConsoleStream stdioStream;

    public CrosvmBackendInstance(
        @NonNull ServerContext context,
        @NonNull VMConfig config
    ) {
        super(context, config);
        uartStream = new FDPipeConsoleStream(config, "uart", -1, -1);
        stdoutStream = new InputConsoleStream(config, "stdout", null);
        stderrStream = new InputConsoleStream(config, "stderr", null);
        stdioStream = new SimpleConsoleStream(config, "stdio");
        addStream(stdoutStream);
        addStream(stderrStream);
        addStream(stdioStream);
        addStream(uartStream);
    }

    @NonNull
    @Override
    public VMStartResult start() {
        var result = new VMStartResult();
        if (!new File(RUN_PATH).mkdirs())
            Log.w(TAG, fmt("Failed to create run directory: %s", RUN_PATH));
        try {
            uart = new SerialPipe(uartStream, "uart");
            if (!uart.isReady()) {
                Log.w(TAG, "UART pipe not ready, discarding");
                uart.close();
                uart = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to create UART pipe", e);
            uart = null;
        }
        controlSocketPath = pathJoin(RUN_PATH, fmt("%s.sock", config.getName()));
        deleteFile(controlSocketPath);
        Log.i(TAG, fmt("Control socket path: %s", controlSocketPath));
        // Native display: crosvm's --input <kind>[path=...] connects to a unix socket whose inode
        // must already exist (crosvm is the *client*), and the display-page entry only appears after
        // the VM is up - so the daemon is the only process that can both bind the socket before
        // crosvm starts and stay alive to feed it. We pre-bind + accept here; the UI forwards evdev
        // to us via the vm_input IPC command (see InputHandler). Server fds released on cleanup().
        // Single source of truth: isInputBridgeNeeded() gates both this pre-bind and the --input
        // args in buildCommand(), so the sockets and devices never diverge.
        if (isInputBridgeNeeded()) {
            if (!inputBridge.startListening(NativeDisplay.serviceName(config))) {
                Log.e(TAG, "Display input sockets unavailable; crosvm will likely fail");
            }
        }
        // Must happen before buildCommand(): crosvm opens <cgroup>/tasks and never
        // creates the directory, and buildCommand() only passes the flag if this worked.
        prepareGpuCgroup();
        var args = buildCommand();
        Log.i(TAG, fmt("Executing: %s", String.join(" ", args)));
        try {
            var builder = new NativeProcess.Builder(args.toArray(new String[0]));
            prepareProcess(builder);
            applyGfxstreamEnv(builder);
            applyDisplayBlitEnv(builder);
            applyGpuRtPrioEnv(builder);
            if (uart != null) {
                builder.preserveFd(uart.getOutputRemoteFd());
                builder.preserveFd(uart.getInputRemoteFd());
            }
            var process = builder.start();
            if (uart != null)
                uart.closeRemoteFd();
            result.setProcess(process);
            stdoutStream.setInputStream(process.getInputStream());
            stderrStream.setInputStream(process.getErrorStream());
        } catch (IOException e) {
            Log.e(TAG, "Failed to start crosvm process", e);
            if (uart != null) {
                uart.close();
                uart = null;
            }
            controlSocketPath = null;
            inputBridge.release();
            return result;
        }
        return result;
    }

    /**
     * Appends the guest-owned VRAM pool settings. Older configs only have the total pool size;
     * their defaults keep the whole pool preallocated and leave dynamic grants disabled.
     */
    private static void appendGuestPoolOptions(
        @NonNull StringBuilder preAlloc,
        @NonNull DataItem item,
        long guestPool
    ) {
        if (guestPool <= 0) return;
        if (preAlloc.length() > 0) preAlloc.append(',');
        preAlloc.append(fmt("gpu-guest-mb=%d", guestPool));
        preAlloc.append(fmt(",gpu-guest-prealloc-mb=%d",
            item.optLong("gpu_guest_prealloc_mb", guestPool)));
        preAlloc.append(fmt(",gpu-guest-step-mb=%d",
            item.optLong("gpu_guest_step_mb", 0)));
        preAlloc.append(fmt(",gpu-guest-max-grants=%d",
            item.optLong("gpu_guest_max_grants", 0)));
    }

    @NonNull
    private List<String> buildCommand() {
        var item = config.item;
        var args = new ArrayList<String>();
        prepareStraceArguments(args);
        args.add(getPrebuiltBinaryPath("crosvm"));
        // Top-level flag (must precede `run`): makes crosvm surface CommandStatus
        // exit codes (see CrosvmExit) so runVM() can tell reset/crash/panic apart.
        args.add("--extended-status");
        args.add("run");
        args.add("--name");
        args.add(config.getName());
        args.add("--mem");
        args.add(String.valueOf(Math.max(item.optLong("memory_mb", 512), 64)));
        args.add("--cpus");
        args.add(String.valueOf(Math.max(item.optLong("cpu_count", 1), 1)));
        buildCpuPlacementCommand(args);
        var hyp = item.optString("hypervisor", "auto");
        var hypervisor = VMHypervisor.valueOf(hyp.toUpperCase());
        if (hypervisor == VMHypervisor.AUTO)
            hypervisor = VMHypervisor.findPreferredHypervisor(VMBackend.CROSVM);
        if (hypervisor == null) throw new RuntimeException("No supported hypervisor found for CROSVM backend");
        args.add("--hypervisor");
        var defProtectedMode = ProtectedVM.PROTECTED_NORMAL;
        switch (hypervisor) {
            case KVM:
                args.add("kvm");
                break;
            case GUNYAH: {
                boolean gfxstreamGpu = item.optBoolean("gpu_enabled", false)
                    && optEnum(item, "gpu_backend", GpuBackend.NONE) == GpuBackend.GPU_GFXSTREAM;
                boolean drm2kgslGpu = item.optBoolean("gpu_enabled", false)
                    && optEnum(item, "gpu_backend", GpuBackend.NONE) == GpuBackend.GPU_VIRGLRENDERER
                    && effectiveGpuMode(item) == GpuMode.NATIVE;
                boolean venusGpu = item.optBoolean("gpu_enabled", false)
                    && optEnum(item, "gpu_backend", GpuBackend.NONE) == GpuBackend.GPU_VIRGLRENDERER
                    && effectiveGpuMode(item) == GpuMode.VULKAN;
                // How host-visible blobs reach the guest is no longer a hypervisor sub-option:
                // it follows from --runtime-share / --pre-alloc / --gpu vm-accept below. The old
                // `gunyah[blob_mode=guest-accept]` field is gone from crosvm, and passing it made
                // every gfxstream-on-Gunyah VM fail to start with "unknown field `blob_mode`".
                args.add("gunyah");
                // Dynamic memory sharing: the guest returns folios at runtime instead of pinning
                // them up front; hugepage-threshold-kb selects which allocations take the
                // hugepage share path.
                if (item.optBoolean("gunyah_dynamic_share", false)) {
                    args.add("--runtime-share");
                    args.add(fmt("hugepage-threshold-kb=%d",
                        item.optLong("gunyah_hugepage_threshold_kb", 1024)));
                }
                // Pre-allocate the gfxstream host-visible pools (host arena + optional guest-alloc
                // pool). Only meaningful for gfxstream on Gunyah.
                if (gfxstreamGpu) {
                    boolean udmabuf = item.optBoolean("gpu_udmabuf", true);
                    // The editor refuses this combination; a config built straight through the
                    // daemon API can still reach here. Dynamic vram grows host-visible memory by
                    // sharing it at runtime, which on Gunyah needs the module and the accept
                    // transport that dynamic sharing brings up. Boot anyway, but say so.
                    if (!udmabuf && item.optBoolean("gpu_dynamic_vram", true)
                        && !item.optBoolean("gunyah_dynamic_share", false))
                        Log.w(TAG, "dynamic vram without gunyah_dynamic_share: host-visible "
                            + "allocations past the pre-alloc pool have nowhere to go");
                    long hostPool = item.optLong("gpu_host_pool_mb", 0);
                    long guestPool = item.optLong("gpu_guest_pool_mb", 0);
                    if (hostPool > 0 || udmabuf) {
                        var preAlloc = new StringBuilder(fmt("gfx-host-mb=%d", hostPool));
                        if (udmabuf)
                            appendGuestPoolOptions(preAlloc, item, guestPool);
                        args.add("--pre-alloc");
                        args.add(preAlloc.toString());
                    }
                }
                // DRM native context. Two pools, and they hold different things:
                //   drm-host-mb  the host arena, now only the per-context msm shmem rings, so
                //                single-digit MB rather than the gigabyte the BOs used to need.
                //   gpu-guest-mb the guest's drm_buddy pool, where every BO comes from. Same
                //                region and flag as the gfxstream guest pool -- the guest driver
                //                keeps one allocator and cannot tell the renderers apart.
                // The guest pool needs udmabuf=true as well; that is what gates
                // VIRTIO_GPU_F_CREATE_GUEST_HANDLE, and without it guest mesa silently keeps a
                // host-allocating path this host no longer implements.
                if (drm2kgslGpu) {
                    long drmHostPool = item.optLong("gpu_drm2kgsl_pool_mb", 0);
                    long guestPool = item.optLong("gpu_guest_pool_mb", 0);
                    var preAlloc = new StringBuilder();
                    if (drmHostPool > 0)
                        preAlloc.append(fmt("drm-host-mb=%d", drmHostPool));
                    appendGuestPoolOptions(preAlloc, item, guestPool);
                    if (preAlloc.length() > 0) {
                        args.add("--pre-alloc");
                        args.add(preAlloc.toString());
                    }
                }
                // Venus host pool: the venus command-stream transport shmems (per-instance ring +
                // CS/reply chunks) live here (venus-host-mb -> VenusPool). vkr sub-allocates every
                // blob_id==0 shmem from this pre-shared region and the guest maps pool_base+offset
                // with no runtime SHARE. venus's real VkDeviceMemory is separately guest-alloc and
                // comes from the shared guest pool (gpu-guest-mb) -- the same region/flag drm2kgsl
                // and gfxstream guest-alloc use; the guest driver keeps one allocator and cannot
                // tell the renderers apart. Default sized for the KDE+vkmark transport peak (cs
                // pool alone is >=8M/instance): too small forces a per-blob memfd fallback ->
                // runtime SHARE, which SoC-resets the fragile sm8650 (8gen3) RM.
                if (venusGpu) {
                    long venusHostPool = item.optLong("gpu_venus_pool_mb", 256);
                    long guestPool = item.optLong("gpu_guest_pool_mb", 0);
                    var preAlloc = new StringBuilder();
                    if (venusHostPool > 0)
                        preAlloc.append(fmt("venus-host-mb=%d", venusHostPool));
                    appendGuestPoolOptions(preAlloc, item, guestPool);
                    if (preAlloc.length() > 0) {
                        args.add("--pre-alloc");
                        args.add(preAlloc.toString());
                    }
                }
                defProtectedMode = ProtectedVM.PROTECTED_WITHOUT_FIRMWARE;
                break;
            }
            case GENIEZONE:
                args.add("geniezone");
                break;
            default:throw new IllegalArgumentException(fmt("Unsupported hypervisor: %s", hypervisor));
        }
        switch (optEnum(item, "protected_vm", defProtectedMode)) {
            case PROTECTED_PROTECTED:
                args.add("--protected-vm");
                break;
            case PROTECTED_WITHOUT_FIRMWARE:
                args.add("--protected-vm-without-firmware");
                break;
            default:
                break;
        }
        if (!item.optBoolean("balloon", false))
            args.add("--no-balloon");
        if (!item.optBoolean("pmu", false))
            args.add("--no-pmu");
        if (!item.optBoolean("rng", false))
            args.add("--no-rng");
        if (!item.optBoolean("smt", false))
            args.add("--no-smt");
        if (!item.optBoolean("usb", false))
            args.add("--no-usb");
        if (!item.optBoolean("sandbox", false))
            args.add("--disable-sandbox");
        if (item.optBoolean("hugepages", true))
            args.add("--hugepages");
        switch (LendMthpMode.fromItem(item)) {
            case SINGLE:
                args.add("--prepare-lend-mthp-mode");
                args.add("single");
                break;
            case CHUNKED:
                args.add("--prepare-lend-mthp-mode");
                args.add("chunked");
                break;
            default:
                break;
        }
        var swiotlbMb = item.optLong("swiotlb_mb", 0);
        if (swiotlbMb > 0) {
            args.add("--swiotlb");
            args.add(String.valueOf(swiotlbMb));
        }
        var boot = BootPlan.of(config);
        if (!boot.initrd.isEmpty()) {
            args.add("--initrd");
            args.add(boot.initrd);
        }
        if (!boot.cmdline.isEmpty()) {
            args.add("--params");
            args.add(boot.cmdline);
        }
        if (controlSocketPath != null) {
            args.add("--socket");
            args.add(controlSocketPath);
        }
        // Real host CPU name for the guest: crosvm forwards it via FDT /chosen and EDK2 publishes
        // it as SMBIOS Type 4 processor version, so UEFI guests (Windows) show e.g.
        // "Qualcomm Snapdragon 8 Elite" instead of the firmware default "Gunyah vCPU".
        var socName = HostSocName.get();
        if (socName != null) {
            args.add("--smbios");
            args.add("processor-version=" + socName);
        }
        buildDiskCommand(args);
        buildNetCommand(args);
        buildSharedDirCommand(args);
        buildGpuCommand(args);
        buildVncCommand(args);
        // The evdev --input devices ride along whenever any app display path is active: the native
        // display routes everything through them; the VNC display routes its MOUSE (relative) and
        // TOUCH (multi-touch) modes here while the tablet pointer + keyboard stay on RFB.
        if (isInputBridgeNeeded()) {
            buildInputDevicesCommand(args);
        }
        buildSerialCommand(args);
        item.opt("extra_options", DataItem.newArray())
            .forEach(arg -> args.add(arg.getValue().asString()));
        if (boot.uefi) {
            // crosvm has no custom-firmware support; always builtin EDK2
            args.add(PATH_EDK2_FIRMWARE);
            if (boot.varsEnabled) {
                var vars = boot.vars.isEmpty() ? PATH_EDK2_VARS : boot.vars;
                args.add("--pflash");
                args.add(fmt("path=%s,block_size=%d", vars, pflashBlockSize(vars)));
            }
        } else if (!boot.kernel.isEmpty()) {
            args.add(boot.kernel);
        }
        return args;
    }

    private static long pflashBlockSize(@NonNull String path) {
        long size = new File(path).length();
        for (long blockSize : new long[]{262144, 65536, 4096})
            if (size > 0 && size % blockSize == 0) return blockSize;
        return 262144;
    }

    /**
     * Creates and configures the gpuworker cpuset cgroup before crosvm starts.
     * crosvm opens {@code <path>/tasks} without creating the directory; the cpuset
     * requires non-empty {@code cpus}/{@code mems} before threads can join it.
     *
     * <p>Soft-fail: any error is logged and {@link #gpuCgroupPath} stays null so
     * {@link #buildCpuPlacementCommand} simply omits the flag. Better to run
     * without GPU thread isolation than to refuse to start the VM.
     */
    private void prepareGpuCgroup() {
        gpuCgroupPath = null;
        var item = config.item;
        if (!item.optBoolean(CpuPlacementPlan.KEY_GPU_CGROUP, false)) return;
        var path = item.optString(CpuPlacementPlan.KEY_GPU_CGROUP_PATH,
            CpuPlacementPlan.DEFAULT_GPU_CGROUP_PATH).trim();
        var cpus = item.optString(CpuPlacementPlan.KEY_GPU_CGROUP_CPUS, "").trim();
        if (path.isEmpty() || !path.startsWith("/")) {
            Log.w(TAG, fmt("gpu-cgroup-path is not an absolute path: '%s'; skipping", path));
            return;
        }
        if (cpus.isEmpty()) {
            Log.w(TAG, "gpu_cgroup_cpus is empty; cannot set up cpuset, skipping");
            return;
        }
        // Parent dir for inheriting cpuset.mems (single NUMA node = "0" on all
        // Android devices, but copy the parent rather than hard-coding it).
        var parent = new java.io.File(path).getParent();
        if (parent == null) parent = "/dev/cpuset";
        var ep = RunUtils.escapedString(path);
        var ec = RunUtils.escapedString(cpus);
        var eq = RunUtils.escapedString(parent);
        // Shell.cmd feeds the whole string to the persistent root shell; newlines work.
        var script =
            "p=" + ep + "\n" +
            "c=" + ec + "\n" +
            "q=" + eq + "\n" +
            "mkdir -p \"$p\" || exit 1\n" +
            // mems first: some kernels validate cpus against a non-empty mems
            "for n in mems cpuset.mems; do\n" +
            "  if [ -e \"$p/$n\" ] && [ ! -s \"$p/$n\" ]; then\n" +
            "    v=$(cat \"$q/$n\" 2>/dev/null); [ -n \"$v\" ] || v=0\n" +
            "    echo \"$v\" > \"$p/$n\"\n" +
            "  fi\n" +
            "done\n" +
            // cpuset v1 (noprefix) uses 'cpus'; v2 uses 'cpuset.cpus' -- try both
            "for n in cpus cpuset.cpus; do\n" +
            "  if [ -e \"$p/$n\" ]; then echo \"$c\" > \"$p/$n\"; fi\n" +
            "done\n" +
            // Last line output verifies the write; also becomes the script exit code
            "cat \"$p/cpus\" 2>/dev/null || cat \"$p/cpuset.cpus\" 2>/dev/null";
        var result = RunUtils.run(script);
        if (!result.isSuccess() || result.getOutString().trim().isEmpty()) {
            Log.e(TAG, fmt("Failed to set up gpuworker cpuset at %s (cpus=%s): %s",
                path, cpus, result.getErrString()));
            return;
        }
        Log.i(TAG, fmt("gpuworker cpuset ready: %s (cpus=%s)", path, result.getOutString().trim()));
        gpuCgroupPath = path;
    }

    /**
     * Appends CPU placement flags: per-vCPU host affinity, guest capacity, guest
     * clusters, and (when the cpuset was successfully prepared) the GPU cgroup.
     */
    private void buildCpuPlacementCommand(@NonNull List<String> args) {
        CpuPlacementPlan.of(config.item).appendArgs(args);
        if (gpuCgroupPath != null) {
            args.add("--gpu-cgroup-path");
            args.add(gpuCgroupPath);
        }
    }

    private void buildDiskCommand(@NonNull List<String> args) {
        var disks = config.item.opt("disks", null);
        if (disks == null) return;
        for (var iter : disks) {
            var disk = iter.getValue();
            var path = disk.optString("path", "");
            if (path.isEmpty()) continue;
            path = patchOptimizedPath(path);
            var readonly = disk.optBoolean("readonly", false);
            var bus = optEnum(disk, "bus", DiskBus.VIRTIO);
            var arg = new StringBuilder(path);
            switch (bus) {
                case SCSI:
                    arg.append(",lock=false");
                    if (readonly) arg.append(",ro=true");
                    args.add("--scsi-block");
                    args.add(arg.toString());
                    break;
                case PMEM:
                    if (readonly) arg.append(",ro=true");
                    args.add("--pmem");
                    args.add(arg.toString());
                    break;
                case CDROM:
                    args.add("--scsi-block");
                    arg.append(",ro=true,type=cdrom,lock=false");
                    args.add(arg.toString());
                    break;
                case VIRTIO:
                    arg.append(",lock=false");
                    if (readonly) arg.append(",ro=true");
                    args.add("--block");
                    args.add(arg.toString());
                    break;
            }
        }
    }

    private void buildNetCommand(@NonNull List<String> args) {
        var nets = config.item.opt("networks", null);
        if (nets == null) return;
        for (var iter : nets) {
            var net = iter.getValue();
            var tapName = net.optString("tap_name", "");
            if (tapName.isEmpty()) continue;
            var netArg = new StringBuilder();
            netArg.append("tap-name=");
            netArg.append(tapName);
            var mac = net.optString("mac_address", "");
            if (!mac.isEmpty()) {
                netArg.append(",mac=");
                netArg.append(mac);
            }
            args.add("--net");
            args.add(netArg.toString());
        }
    }

    private void buildSharedDirCommand(@NonNull List<String> args) {
        var dirs = config.item.opt("shared_dirs", null);
        if (dirs == null) return;
        for (var iter : dirs) {
            var dir = iter.getValue();
            var path = dir.optString("path", "");
            var tag = dir.optString("tag", "");
            if (path.isEmpty() || tag.isEmpty()) continue;
            var type = optEnum(dir, "type", SharedDirType.FS);
            var cache = optEnum(dir, "cache", SharedDirCache.AUTO);
            args.add("--shared-dir");
            args.add(fmt(
                "%s:%s:type=%s:cache=%s:timeout=%d:writeback=%s:dax=%s:posix_acl=%s",
                path, tag,
                type.name().toLowerCase(),
                cache.name().toLowerCase(),
                dir.optLong("timeout", 5),
                dir.optBoolean("writeback", false),
                dir.optBoolean("dax", false),
                dir.optBoolean("posix_acl", true)
            ));
        }
    }

    // Resolve the effective hypervisor (mirrors buildCommand's --hypervisor logic) to gate
    // Gunyah-only GPU behavior such as gunyah-pvm.
    private boolean isGunyahHypervisor() {
        var hyp = config.item.optString("hypervisor", "auto");
        var hypervisor = VMHypervisor.valueOf(hyp.toUpperCase());
        if (hypervisor == VMHypervisor.AUTO)
            hypervisor = VMHypervisor.findPreferredHypervisor(VMBackend.CROSVM);
        return hypervisor == VMHypervisor.GUNYAH;
    }

    /**
     * What the guest hands to the host: the {@code gpu_mode} row of the editor's three-level
     * GPU section (renderer / mode / provider).
     *
     * <p>Configs written before that split carry only {@code gpu_api}, whose meaning depended on
     * the renderer, so fall back to the same migration the editor shows. Reading {@code gpu_api}
     * directly is what this replaces: a VM configured through the new rows stores
     * {@code gpu_mode=native} and no longer sets {@code gpu_api=drm2kgsl}, so the drm2kgsl branch below
     * would silently not fire and the VM would come up without context-types=virgl2:drm.
     */
    @NonNull
    private static GpuMode effectiveGpuMode(@NonNull DataItem item) {
        var mode = optEnum(item, "gpu_mode", GpuMode.NONE);
        if (mode != GpuMode.NONE) return mode;
        return GpuMode.fromLegacyApi(optEnum(item, "gpu_api", GpuApi.NONE));
    }

    private void buildGpuCommand(@NonNull List<String> args) {
        var item = config.item;
        var useGpu = item.optBoolean("gpu_enabled", false);
        var useDisplay = item.optBoolean("display_enabled", false);
        var backend = optEnum(item, "display_backend", DisplayBackend.NONE);
        var api = optEnum(item, "gpu_api", GpuApi.NONE);
        if (!useGpu && !useDisplay) return;
        if (useGpu) {
            var gpuBackend = optEnum(item, "gpu_backend", GpuBackend.NONE);
            var isGfxstream = gpuBackend == GpuBackend.GPU_GFXSTREAM;
            var gpuArg = new StringBuilder();
            gpuArg.append(gpuBackend.getName());
            // gfxstream host-visible Vulkan: the guest turnip (VK) + zink (GL-on-VK)
            // stack needs the gfxstream-vulkan context type.
            if (isGfxstream) {
                gpuArg.append(",context-types=gfxstream-vulkan");
            }
            if (useDisplay && backend == VIRTIO_GPU) {
                gpuArg.append(fmt(",displays=[[mode=windowed[%d,%d]",
                    item.optLong("display_width", 1280),
                    item.optLong("display_height", 720)));
                gpuArg.append(fmt(",refresh-rate=%d",
                    item.optLong("display_refresh_rate", 120)));
                gpuArg.append(fmt(",dpi=[%d,%d]]]",
                    item.optLong("display_dpi_h", 160),
                    item.optLong("display_dpi_v", 160)));
            }

            if (isGfxstream) {
                // gfxstream serves both VK (turnip) and GL (zink) clients, so both
                // capsets are on. pci-bar-size is the host-visible BAR window and
                // doubles as the GPU memory ceiling (the guest has no
                // device-local-only memory type); default 4 GiB.
                gpuArg.append(",vulkan=true,gles=true");
                gpuArg.append(fmt(",pci-bar-size=%d",
                    item.optLong("gpu_pci_bar_size", 0x100000000L)));
                // Dynamic vram: a defined vram-limit is what enables runtime-shared host-visible
                // memory (and, with a pre-alloc pool, fusion routing); leaving it undefined keeps
                // every allocation inside the pool. crosvm ignores it in guest-alloc mode, where
                // the guest pool is the cap, so only send it for host-alloc.
                boolean udmabufGpu = item.optBoolean("gpu_udmabuf", true);
                boolean dynamicVram = !udmabufGpu && item.optBoolean("gpu_dynamic_vram", true);
                if (dynamicVram) {
                    // crosvm calls it vram-limit, and it meters three separate things: the
                    // runtime-share folio budget, the VK_EXT_memory_budget figure handed to
                    // the guest driver, and -- by being defined and non-zero at all --
                    // whether fusion routing is on. All three are ignored under guest-alloc.
                    gpuArg.append(
                        fmt(",vram-limit=%d", item.optLong("gpu_vram_quota_mb", 2048)));
                    // Fusion size gate: host-visible allocations up to this try the pre-alloc
                    // pool first, larger ones go straight to the runtime-SHARE path.
                    gpuArg.append(fmt(",pool-blob-max-kb=%d",
                        item.optLong("gpu_pool_blob_max_kb", 4096)));
                }
                // gunyah-pvm pins the RingBlob backing so the permanent Gunyah SHARE mapping
                // stays stable. Only meaningful under the Gunyah hypervisor; other SoCs skip it.
                if (isGunyahHypervisor()) {
                    gpuArg.append(",gunyah-pvm=true");
                }
                // Guest-allocated blobs: the guest owns the host-visible pool and hands
                // dma-bufs to gfxstream via udmabuf, instead of the host growing the arena.
                if (udmabufGpu) {
                    gpuArg.append(",udmabuf=true");
                }
            } else if (effectiveGpuMode(item) == GpuMode.NATIVE) {
                // DRM native context: the guest runs its own turnip over vdrm and virglrenderer
                // translates the msm protocol to KGSL ioctls, so nothing is remoted at the
                // GL/VK level and the host exposes no Vulkan capset.
                //
                // virgl2 MUST stay in the list. With "drm" alone the virgl renderer is never
                // initialised (NO_VIRGL) and the very first CREATE_2D comes back
                // ComponentError(22) -- a black screen with nothing else to go on.
                gpuArg.append(",context-types=virgl2:drm");
                // No external-blob: create_gpu_device overwrites it with
                // `is_sandboxed || fixed_blob_mapping` regardless of what the CLI said, so
                // passing it would only suggest it does something.
                gpuArg.append(",vulkan=false,egl=true,gles=true");
                // udmabuf builds the dma-buf for a guest-allocated blob, and -- less obviously --
                // it is what gates VIRTIO_GPU_F_CREATE_GUEST_HANDLE. Without it the feature is
                // never offered, the guest reports has_create_guest_handle=0, and guest mesa
                // falls back to a host-allocating path this host no longer implements. The VM
                // boots and the desktop comes up; the failure waits for the first large buffer.
                gpuArg.append(",udmabuf=true");
                gpuArg.append(fmt(",pci-bar-size=%d",
                    item.optLong("gpu_pci_bar_size", 0x100000000L)));
            } else if (effectiveGpuMode(item) == GpuMode.VULKAN) {
                // Venus: virglrenderer's Vulkan proxy (capset venus, guest-allocated blobs).
                // virgl2 MUST stay in the list -- with "venus" alone the virgl renderer is never
                // initialised (NO_VIRGL) and the first CREATE_2D comes back ErrInvalidResourceId,
                // a black screen. vulkan=true maps to use_venus on the virgl path; the venus capset
                // also forces use_venus and use_guest_vram host-side, so this is belt-and-suspenders.
                gpuArg.append(",context-types=virgl2:venus");
                gpuArg.append(",vulkan=true,egl=true,gles=true");
                // udmabuf gates VIRTIO_GPU_F_CREATE_GUEST_HANDLE, the guest-alloc blob path venus
                // uses (guest owns the pool, hands the host dma-bufs) -- same contract as drm2kgsl.
                // No external-blob: create_gpu_device forces it from is_sandboxed||fixed_blob_mapping
                // (false under --disable-sandbox), so passing the CLI key would be inert.
                gpuArg.append(",udmabuf=true");
                gpuArg.append(fmt(",pci-bar-size=%d",
                    item.optLong("gpu_pci_bar_size", 0x100000000L)));
            } else {
                gpuArg.append(fmt(",vulkan=%s", String.valueOf(api == VULKAN)));
                switch (api) {
                    case EGL:
                        gpuArg.append(",egl=true");
                        break;
                    case OPENGLES:
                        gpuArg.append(",gles=true");
                        break;
                    case ANGLE:
                        // crosvm has no `angle` --gpu key and rejects unknown ones outright, so
                        // emitting it would stop the VM from starting. Treat a config that
                        // still carries ANGLE as GLES, which is what the editor migrates it to.
                        gpuArg.append(",gles=true");
                        break;
                }
            }
            args.add("--gpu");
            args.add(gpuArg.toString());
        }
        if (useDisplay && backend == SIMPLEFB) {
            args.add("--simplefb");
            args.add(fmt(
                "width=%d,height=%d",
                item.optLong("display_width", 1280),
                item.optLong("display_height", 720)
            ));
        }
        // Native display: crosvm registers an ICrosvmAndroidDisplayService binder under a per-VM
        // name and renders the gfxstream/virtio-gpu output straight into the Android Surface the UI
        // hands it. Requires the GPU (virtio-gpu) path above. Touch/keyboard come back over the
        // per-VM unix sockets the root service listens on; their paths must match NativeDisplay.
        // Single source of truth for the enable check; isNativeDisplayEnabled() also gates the
        // socket pre-bind in start(), so the two must never diverge.
        if (isNativeDisplayEnabled()) {
            buildNativeDisplayCommand(args);
        }
    }

    private void buildNativeDisplayCommand(@NonNull List<String> args) {
        args.add("--android-display-service");
        args.add(NativeDisplay.serviceName(config));
    }

    // One virtio-input device per NativeDisplay channel; the daemon pre-binds the matching sockets
    // (see start()) and the UI ships evdev records to them via vm_input / the direct sink.
    private void buildInputDevicesCommand(@NonNull List<String> args) {
        var item = config.item;
        var serviceName = NativeDisplay.serviceName(config);
        // multi-touch + absolute-mouse advertise a fixed normalized ABS range (crosvm
        // NORMALIZED_ABS_MAX) because width/height are OMITTED here; the UI scales view coords to
        // that range (EvdevEncoder.NORMALIZED_ABS_MAX / TouchScaleCalculator), so the mapping is
        // resolution-independent and survives guest auto-resize -- no display size needed.
        args.add("--input");
        args.add(fmt(
            "multi-touch[path=%s]",
            NativeDisplay.inputSocketPath(serviceName, NativeDisplay.MULTITOUCH)
        ));
        args.add("--input");
        args.add(fmt(
            "keyboard[path=%s]",
            NativeDisplay.inputSocketPath(serviceName, NativeDisplay.KEYBOARD)
        ));
        // Relative-pointer mouse (REL_X/Y + buttons + wheel) for InputMode.MOUSE; the guest renders
        // the cursor, which is what relative-motion consumers (FPS games) need.
        args.add("--input");
        args.add(fmt(
            "mouse[path=%s]",
            NativeDisplay.inputSocketPath(serviceName, NativeDisplay.MOUSE)
        ));
        // Tablet = crosvm's absolute-pointing mouse (qemu usb-tablet): ABS position + buttons +
        // wheel, so it gives the guest pointer hover, right-click and scroll -- which single-touch
        // (a BTN_TOUCH touchscreen) can't. The UI maps a host mouse/stylus onto it in TABLET mode.
        args.add("--input");
        args.add(fmt(
            "absolute-mouse[path=%s]",
            NativeDisplay.inputSocketPath(serviceName, NativeDisplay.TABLET)
        ));
    }

    // crosvm can promote the virtio-gpu worker to SCHED_FIFO (CROSVM_GPU_RT_PRIO). On gfxstream its
    // per-context render threads inherit that policy and, spin-waiting on the guest command ring,
    // can starve the normal-priority vCPU that feeds them -- a priority inversion that caps the
    // native-display present rate. So RT is opt-in and off by default: the graphics tab's "GPU worker
    // real-time scheduling" switch (in the GPU Worker Cpuset section) stores gpu_rt_prio ("97" on /
    // "" off); pass it through only when set so crosvm leaves scheduling normal otherwise.
    private void applyGpuRtPrioEnv(@NonNull NativeProcess.Builder builder) {
        var item = config.item;
        if (!item.optBoolean("gpu_enabled", false)) return;
        // gpu_rt_prio is the SCHED_FIFO level as a string, "" (unset) by default. Empty means leave
        // CROSVM_GPU_RT_PRIO unset so crosvm applies no real-time scheduling (RT is opt-in).
        String prio = item.optString("gpu_rt_prio", "");
        if (!prio.isEmpty()) builder.environment("CROSVM_GPU_RT_PRIO", prio);
    }

    /**
     * The host-Vulkan renderers (gfxstream, and venus on virglrenderer) need their host ICD
     * selected, gfxstream its host-visible folio/blob env, and both a raised udmabuf import cap.
     * No-op for OpenGL, Native and 2D.
     */
    private void applyGfxstreamEnv(@NonNull NativeProcess.Builder builder) {
        var item = config.item;
        if (!item.optBoolean("gpu_enabled", false)) return;
        var backend = optEnum(item, "gpu_backend", GpuBackend.NONE);
        boolean gfxstream = backend == GpuBackend.GPU_GFXSTREAM;
        // Venus is Vulkan-on-virglrenderer: it drives the host GPU through the same host ICD
        // and the same guest-alloc udmabuf blobs as gfxstream, so it needs this env too.
        boolean venus = backend == GpuBackend.GPU_VIRGLRENDERER
            && effectiveGpuMode(item) == GpuMode.VULKAN;
        if (!gfxstream && !venus) return;
        // gfxstream-only: advertise a device-local memory type to the guest. The folio budget
        // (vram-limit) and Gunyah RingBlob pin (gunyah-pvm) are on the --gpu line.
        if (gfxstream)
            builder.environment("GFXSTREAM_DEVICE_LOCAL_MEMORY_TYPE", "1");
        // Host Vulkan driver, for gfxstream and venus alike (both dlopen ANDROID_EMU_VK_LOADER_PATH
        // ahead of the system loader: gfxstream's VulkanDispatch, venus's vkr_library). It follows
        // gpu_api, which the editor derives from the provider row: VULKAN_SYSTEM / VULKAN_PANVK use
        // the SoC's stock HAL (leave the env unset so the system loader picks the vendor ICD),
        // anything else -- VULKAN_TURNIP, or plain VULKAN from a pre-provider config -- the
        // bundled turnip. Either way the env falls back to the system HAL if the turnip file is
        // missing.
        var api = optEnum(item, "gpu_api", GpuApi.NONE);
        boolean systemDriver = api == GpuApi.VULKAN_SYSTEM || api == GpuApi.VULKAN_PANVK;
        if (!systemDriver) {
            var turnip = pathJoin(DATA_DIR, "usr", "lib", "libvulkan_freedreno.so");
            if (new File(turnip).exists()) {
                builder.environment("ANDROID_EMU_VK_LOADER_PATH", turnip);
            }
        }
        // udmabuf's default 64MB/handle cap chokes large blob imports; raise it so a
        // whole host-visible allocation can be wrapped as one dma-buf. The glob covers
        // both the in-tree driver (/sys/module/udmabuf) and the app-shipped fallback
        // module for kernels without CONFIG_UDMABUF (/sys/module/udmabuf_gki_6.1 etc.).
        RunUtils.run("for p in /sys/module/udmabuf*/parameters/size_limit_mb; do " +
                "echo %d > \"$p\"; done 2>/dev/null || true",
            item.optLong("gpu_udmabuf_limit_mb", 4096));
    }

    /**
     * Host Vulkan provider for the native display's GPU blit ({@link GpuBlitProvider}) -- the
     * scanout-dmabuf-to-SurfaceControl path. Only the accelerated scanout uses it: native display
     * on the virtio-gpu backend (simplefb/VNC present through crosvm's CPU copy and never reach
     * here). This is a separate axis from the render host driver ({@link #applyGfxstreamEnv}); the
     * two can name the same turnip .so or differ.
     *
     * <p>TURNIP points the crosvm bridge at the bundled turnip. OFF -- and, until they are wired,
     * PANVK/SYSTEM -- forces crosvm's CPU copy so a stale or hand-edited value degrades cleanly
     * instead of half-loading a wrong driver. (SYSTEM will instead leave the library unset and let
     * the bridge load the SoC driver once the capability probe that gates it exists.)
     */
    private void applyDisplayBlitEnv(@NonNull NativeProcess.Builder builder) {
        var item = config.item;
        if (!isNativeDisplayEnabled()) return;
        if (optEnum(item, "display_backend", DisplayBackend.NONE) != DisplayBackend.VIRTIO_GPU)
            return;
        var provider = optEnum(item, "display_blit_provider", GpuBlitProvider.TURNIP);
        switch (provider) {
            case TURNIP: {
                var turnip = pathJoin(DATA_DIR, "usr", "lib", "libvulkan_freedreno.so");
                if (new File(turnip).exists())
                    builder.environment("CROSVM_DISPLAY_VULKAN_LIBRARY", turnip);
                break;
            }
            case SYSTEM: {
                // The SoC's stock Vulkan performs the blit. The bridge dlopens whatever it is
                // pointed at as a hwvulkan HMI, and the vendor driver under /vendor/lib64/hw is one,
                // so aim it there instead of turnip. The bridge's own extension probe drops to the
                // CPU copy when the stock driver lacks raw-dmabuf import (as Qualcomm's does) or
                // cannot be loaded -- so SYSTEM attempts the system Vulkan and degrades, it never
                // forces the CPU path.
                var sysVk = resolveSystemVulkanHal();
                if (sysVk != null)
                    builder.environment("CROSVM_DISPLAY_VULKAN_LIBRARY", sysVk);
                break;
            }
            case OFF:
            case PANVK:
            default:
                // OFF is explicit; PANVK is not built yet (and is bounced in the editor). Force the
                // CPU copy rather than letting the bridge load the wrong driver.
                builder.environment("GPU_DISPLAY_COPY_MODE", "cpu");
                break;
        }
    }

    /**
     * The SoC's stock Vulkan hwvulkan HAL under {@code /vendor/lib64/hw} -- a real hwvulkan HMI the
     * display bridge can dlopen -- or null if only a software rasteriser is present. Used by the
     * SYSTEM {@link GpuBlitProvider}.
     */
    private static String resolveSystemVulkanHal() {
        var files = new File("/vendor/lib64/hw").listFiles(
            (d, name) -> name.startsWith("vulkan.") && name.endsWith(".so"));
        if (files == null) return null;
        for (var vf : files) {
            var n = vf.getName();
            // Skip the software fallbacks (lvp/swiftshader/pastel); we want the GPU driver.
            if (n.contains("lvp") || n.contains("swiftshader") || n.contains("pastel")) continue;
            return vf.getAbsolutePath();
        }
        return null;
    }

    private boolean isNativeDisplayEnabled() {
        var item = config.item;
        if (!item.optBoolean("display_enabled", false)) return false;
        var backend = optEnum(item, "display_backend", DisplayBackend.NONE);
        switch (backend) {
            case VIRTIO_GPU:
                // The scanout comes from the GPU device, so it has to be enabled.
                if (!item.optBoolean("gpu_enabled", false)) return false;
                break;
            case SIMPLEFB:
                // No GPU involved: crosvm's simplefb bridge polls the guest's linear framebuffer
                // and presents it through the same display service.
                break;
            default:
                return false;
        }
        return item.optBoolean("native_display_enabled", false);
    }

    /**
     * The evdev input bridge (and matching --input devices) is needed by both app display paths:
     * native uses it for every input; the VNC display uses it for MOUSE/TOUCH modes (tablet
     * pointer + keyboard ride the RFB channel instead).
     */
    private boolean isInputBridgeNeeded() {
        return isNativeDisplayEnabled() || config.item.optBoolean("vnc_enabled", false);
    }

    private void buildVncCommand(@NonNull List<String> args) {
        var item = config.item;
        if (!item.optBoolean("vnc_enabled", false)) return;
        var vncArg = new StringBuilder();
        var host = item.optString("vnc_host", "");
        if (!host.isEmpty()) {
            vncArg.append("host=");
            vncArg.append(host);
            vncArg.append(",");
        }
        vncArg.append("port=");
        vncArg.append(Math.max(item.optLong("vnc_port", -1), 1));
        var password = item.optString("vnc_password", "");
        if (!password.isEmpty()) {
            vncArg.append(",password=");
            vncArg.append(password);
        }
        // VNC server pointer is FIXED to tablet (absolute, 1:1 cursor + hover/right-click/wheel):
        // every third-party VNC client gets absolute-tablet semantics. The app's own VNC display
        // routes MOUSE/TOUCH modes around RFB via the crosvm --input devices instead, so nothing
        // needs a different server-side mode.
        vncArg.append(",input=tablet");
        args.add("--vnc-server");
        args.add(vncArg.toString());
    }

    private void buildSerialCommand(@NonNull List<String> args) {
        if (uart == null) return;
        var serial = fmt(
            "type=file,hardware=serial,num=1,earlycon,console,path=/proc/self/fd/%d,input=/proc/self/fd/%d",
            uart.getOutputRemoteFd(), uart.getInputRemoteFd()
        );
        args.add("--serial");
        args.add(serial);
    }

    @Nullable
    private static String mapControlCommand(@NonNull String command) {
        switch (command) {
            case "stop":
                return "Exit";
            case "powerbtn":
                return "Powerbtn";
            case "sleepbtn":
                return "Sleepbtn";
            case "resume":
                return "ResumeVcpus";
            case "suspend":
                return "SuspendVcpus";
            default:
                return null;
        }
    }

    @Override
    public synchronized int runControlCommand(@NonNull String command) {
        if (controlSocketPath == null) {
            Log.w(TAG, fmt("Cannot run crosvm %s: no control socket", command));
            return -1;
        }
        var vmRequest = mapControlCommand(command);
        if (vmRequest == null) {
            Log.w(TAG, fmt("Unknown control command: %s", command));
            return -1;
        }
        try (var socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET)) {
            socket.connect(new LocalSocketAddress(
                controlSocketPath, FILESYSTEM
            ));
            socket.setSoTimeout(5000);
            var request = fmt("\"%s\"", vmRequest).getBytes(UTF_8);
            Log.i(TAG, fmt(
                "Sending control: %s -> %s (%d bytes)",
                command, vmRequest, request.length
            ));
            socket.getOutputStream().write(request);
            var buf = new byte[4096];
            int n = socket.getInputStream().read(buf);
            if (n <= 0) {
                Log.w(TAG, fmt("No response for crosvm %s", command));
                return -1;
            }
            var response = new String(buf, 0, n, UTF_8);
            Log.i(TAG, fmt("Control response: %s", response));
            if (response.equals("\"Ok\"")) return 0;
            Log.w(TAG, fmt("crosvm %s returned: %s", command, response));
            return -1;
        } catch (IOException e) {
            Log.e(TAG, fmt("Control command %s failed", command), e);
            return -1;
        }
    }

    @Override
    public boolean hasControlSocket() {
        return controlSocketPath != null;
    }

    @Override
    public boolean writeNativeInput(int channel, @NonNull byte[] data) {
        return inputBridge.writeNativeInput(channel, data);
    }

    @Override
    public void cleanup() {
        if (controlSocketPath != null) {
            deleteFile(controlSocketPath);
            controlSocketPath = null;
        }
        inputBridge.release();
    }
}
