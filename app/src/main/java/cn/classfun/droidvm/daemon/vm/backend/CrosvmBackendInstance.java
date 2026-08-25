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
import static cn.classfun.droidvm.lib.store.vm.GpuApi.VULKAN;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.FileUtils.deleteFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.threadSleep;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.BuildConfig;
import cn.classfun.droidvm.daemon.console.FDPipeConsoleStream;
import cn.classfun.droidvm.daemon.console.InputConsoleStream;
import cn.classfun.droidvm.daemon.console.SimpleConsoleStream;
import cn.classfun.droidvm.daemon.display.DaemonSystemContext;
import cn.classfun.droidvm.daemon.server.ServerContext;
import cn.classfun.droidvm.daemon.vm.BootPlan;
import cn.classfun.droidvm.daemon.vm.SerialPipe;
import cn.classfun.droidvm.daemon.vm.UsbAcmPool;
import cn.classfun.droidvm.daemon.vm.VMBackendInstance;
import cn.classfun.droidvm.daemon.vm.VMStartResult;
import cn.classfun.droidvm.daemon.audio.HostAudioTable;
import cn.classfun.droidvm.lib.data.HostAudioDevices;
import cn.classfun.droidvm.lib.natives.NativeProcess;
import cn.classfun.droidvm.lib.utils.RunUtils;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.vm.CpuPlacementPlan;
import cn.classfun.droidvm.lib.store.vm.DisplayExporter;
import cn.classfun.droidvm.lib.store.vm.DisplayTransportCap;
import cn.classfun.droidvm.lib.store.vm.GpuApi;
import cn.classfun.droidvm.lib.store.vm.GpuMode;
import cn.classfun.droidvm.lib.store.vm.GpuBackend;
import cn.classfun.droidvm.lib.store.vm.GpuBlitProvider;
import cn.classfun.droidvm.lib.store.vm.LendMthpMode;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.SerialBackend;
import cn.classfun.droidvm.lib.store.vm.SerialHardware;
import cn.classfun.droidvm.lib.store.vm.VMSerialConfig;
import cn.classfun.droidvm.lib.store.vm.SoundMode;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.SharedDirCache;
import cn.classfun.droidvm.lib.store.vm.SharedDirType;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
import cn.classfun.droidvm.lib.store.vm.VMScreenConfig;

@SuppressWarnings("FieldCanBeLocal")
public final class CrosvmBackendInstance extends VMBackendInstance {
    private static final String TAG = "CrosvmBackendInstance";
    private static final String RUN_PATH = pathJoin(DATA_DIR, "run");
    private String controlSocketPath = null;
    /** Set by prepareGpuCgroup() once the cpuset exists and holds cores; else null. */
    private String gpuCgroupPath = null;
    /** Owns the per-VM native-display input sockets (crosvm-facing + UI-facing); see start(). */
    private final NativeDisplayInputBridge inputBridge = new NativeDisplayInputBridge();
    private final InputConsoleStream stdoutStream;
    private final InputConsoleStream stderrStream;
    private final SimpleConsoleStream stdioStream;

    /**
     * One configured serial port resolved for a run: its config plus whatever host resource
     * backs it this time. The daemon owns every resource here; the UI only ever sees the
     * config and the console stream. The pty backend holds nothing -- that is crosvm's own
     * serial type.
     */
    private static final class ResolvedSerial {
        final VMSerialConfig port;
        SerialPipe pipe;   // APP_CONSOLE
        String acmDevPath; // USB_ACM: pool member's ttyGSn that crosvm opens

        ResolvedSerial(@NonNull VMSerialConfig port) {
            this.port = port;
        }
    }

    private final List<ResolvedSerial> resolvedSerials = new ArrayList<>();
    /** Console streams for APP_CONSOLE ports, keyed by stream name; registered once. */
    private final Map<String, FDPipeConsoleStream> serialStreams = new LinkedHashMap<>();

    public CrosvmBackendInstance(
        @NonNull ServerContext context,
        @NonNull VMConfig config
    ) {
        super(context, config);
        stdoutStream = new InputConsoleStream(config, "stdout", null);
        stderrStream = new InputConsoleStream(config, "stderr", null);
        stdioStream = new SimpleConsoleStream(config, "stdio");
        addStream(stdoutStream);
        addStream(stderrStream);
        addStream(stdioStream);
        // One text console per app-console serial port. Registered here, like the old fixed
        // "uart" stream, so the stream list is stable across VM restarts.
        VMSerialConfig.ensureDefaults(config.item);
        for (var port : VMSerialConfig.listOf(config.item)) {
            if (port.getBackend() != SerialBackend.APP_CONSOLE) continue;
            var name = port.getStreamName();
            if (serialStreams.containsKey(name)) continue;
            var stream = new FDPipeConsoleStream(config, name, -1, -1);
            serialStreams.put(name, stream);
            addStream(stream);
        }
    }

    @NonNull
    @Override
    public VMStartResult start() {
        var result = new VMStartResult();
        if (!new File(RUN_PATH).mkdirs())
            Log.w(TAG, fmt("Failed to create run directory: %s", RUN_PATH));
        try {
            resolveSerialPorts();
        } catch (IOException e) {
            // A refused serial slot fails the whole start; the reason lands on the stdio
            // console where every other boot failure already goes.
            Log.e(TAG, "Serial setup refused", e);
            stdioStream.appendBuffer(fmt("serial setup failed: %s\n", e.getMessage()));
            closeSerialPorts();
            return result;
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
        // args in buildCommand(), and absoluteInputScreens() decides which screens get the two
        // per-screen devices in both places, so the sockets and devices never diverge.
        if (isInputBridgeNeeded()) {
            try {
                if (!inputBridge.startListening(config.getId().toString(),
                    absoluteInputScreens())) {
                    Log.e(TAG, "Display input sockets unavailable; crosvm will likely fail");
                }
            } catch (IllegalArgumentException e) {
                // A socket path too long for sun_path. crosvm would refuse the command line and
                // our own bind() would truncate it in silence, so there is no half-working start
                // to attempt: fail here, with the path and its length on the console the user is
                // already looking at, the way a refused serial slot does.
                Log.e(TAG, "Display input socket path refused", e);
                stdioStream.appendBuffer(fmt("display input setup failed: %s\n", e.getMessage()));
                inputBridge.release();
                closeSerialPorts();
                controlSocketPath = null;
                return result;
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
            for (var rs : resolvedSerials) {
                if (rs.pipe != null) {
                    builder.preserveFd(rs.pipe.getOutputRemoteFd());
                    builder.preserveFd(rs.pipe.getInputRemoteFd());
                }
            }
            var process = builder.start();
            for (var rs : resolvedSerials)
                if (rs.pipe != null) rs.pipe.closeRemoteFd();
            result.setProcess(process);
            stdoutStream.setInputStream(process.getInputStream());
            stderrStream.setInputStream(process.getErrorStream());
        } catch (IOException e) {
            Log.e(TAG, "Failed to start crosvm process", e);
            closeSerialPorts();
            controlSocketPath = null;
            inputBridge.release();
            return result;
        }
        return result;
    }

    /**
     * Turns the config's serial list into live host resources for this run: a daemon pipe pair
     * per app-console port. A port whose pipes cannot be opened degrades to a sink in
     * buildSerialCommand rather than failing the start.
     */
    private void resolveSerialPorts() throws IOException {
        closeSerialPorts();
        for (var port : VMSerialConfig.listOf(config.item)) {
            var rs = new ResolvedSerial(port);
            var backend = port.getBackend();
            if (backend == SerialBackend.APP_CONSOLE) {
                var stream = serialStreams.get(port.getStreamName());
                if (stream != null) {
                    try {
                        var pipe = new SerialPipe(stream, port.getStreamName());
                        if (pipe.isReady()) {
                            rs.pipe = pipe;
                        } else {
                            Log.w(TAG, fmt("Serial pipe %s not ready, discarding",
                                port.getStreamName()));
                            pipe.close();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, fmt("Failed to create serial pipe %s",
                            port.getStreamName()), e);
                    }
                }
            } else if (backend == SerialBackend.USB_ACM) {
                // Attaches the configured slot of the daemon-wide ACM pool; only the pool's
                // first-time build rebinds USB. A busy or out-of-range slot aborts the start
                // (SlotUnavailableException propagates): a VM silently landing on another
                // host COM port -- or stealing one -- is worse than not booting. Only a
                // broken gadget degrades to a sink.
                try {
                    rs.acmDevPath = UsbAcmPool.acquire(port.getUsbSlot(),
                        acmOwnerToken(port), context.appConfig);
                } catch (UsbAcmPool.SlotUnavailableException e) {
                    throw e;
                } catch (IOException e) {
                    Log.w(TAG, fmt("USB ACM for %s unavailable; port degrades to sink",
                        port.getStreamName()), e);
                }
            }
            resolvedSerials.add(rs);
        }
    }

    @NonNull
    private String acmOwnerToken(@NonNull VMSerialConfig port) {
        return fmt("%s/%s", config.getId(), port.getStreamName());
    }

    private void closeSerialPorts() {
        for (var rs : resolvedSerials) {
            if (rs.pipe != null) rs.pipe.close();
            if (rs.acmDevPath != null) UsbAcmPool.release(acmOwnerToken(rs.port));
        }
        resolvedSerials.clear();
    }

    /**
     * Appends the guest-owned VRAM pool settings. Older configs only have the total pool size;
     * their defaults keep the whole pool preallocated and leave dynamic grants disabled. The
     * editor writes exactly those values (prealloc = pool, step 0, no grants) whenever its
     * dynamic-vram switch is off over a guest pool, so a non-zero step here means the user
     * asked for runtime growth. Only called under Gunyah.
     */
    private static void appendGuestPoolOptions(
        @NonNull StringBuilder preAlloc,
        @NonNull DataItem item,
        long guestPool
    ) {
        if (guestPool <= 0) return;
        long step = item.optLong("gpu_guest_step_mb", 0);
        // Same situation as the host-alloc dynamic-vram warning in buildCommand(): the editor
        // refuses this combination, a config built straight through the daemon API can still
        // reach here. Each growth grant is a runtime SHARE (one memparcel), which on Gunyah needs
        // the module and accept transport that dynamic sharing brings up. Boot anyway, but say so.
        if (step > 0 && !item.optBoolean("gunyah_dynamic_share", false))
            Log.w(TAG, "guest pool step without gunyah_dynamic_share: the pool cannot grow "
                + "past its preallocation");
        if (preAlloc.length() > 0) preAlloc.append(',');
        preAlloc.append(fmt("gpu-guest-mb=%d", guestPool));
        preAlloc.append(fmt(",gpu-guest-prealloc-mb=%d",
            item.optLong("gpu_guest_prealloc_mb", guestPool)));
        preAlloc.append(fmt(",gpu-guest-step-mb=%d", step));
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
                boolean hasGpu = VMScreenConfig.hasGpuDevice(item);
                boolean gfxstreamGpu = hasGpu
                    && optEnum(item, "gpu_backend", GpuBackend.NONE) == GpuBackend.GPU_GFXSTREAM;
                boolean drm2kgslGpu = hasGpu
                    && optEnum(item, "gpu_backend", GpuBackend.NONE) == GpuBackend.GPU_VIRGLRENDERER
                    && effectiveGpuMode(item) == GpuMode.NATIVE;
                boolean venusGpu = hasGpu
                    && optEnum(item, "gpu_backend", GpuBackend.NONE) == GpuBackend.GPU_VIRGLRENDERER
                    && effectiveGpuMode(item) == GpuMode.VULKAN;
                // How host-visible blobs reach the guest is no longer a hypervisor sub-option:
                // it follows from --runtime-share / --pre-alloc / --gpu vm-accept below. The old
                // `gunyah[blob_mode=guest-accept]` field is gone from crosvm, and passing it made
                // every gfxstream-on-Gunyah VM fail to start with "unknown field `blob_mode`".
                args.add("gunyah");
                // The guest-alloc pool buys the host access to buffers the guest allocated, which
                // in an ordinary protected VM it does not otherwise have. When the host can
                // already reach the guest's RAM -- an unprotected VM, or a pseudo-unprotected one
                // whose window is shared back before the payload runs -- the pool is memory taken
                // from the guest to solve a problem that is not happening, and virtio-gpu with no
                // pool node to find allocates from system RAM instead, which the host can read
                // for the same reason. The editor hides the field in those modes; a config from
                // the daemon API, or one saved before switching mode, still arrives with a size
                // in it, so it is zeroed here rather than trusted.
                var pvm = optEnum(item, "protected_vm", ProtectedVM.PROTECTED_WITHOUT_FIRMWARE);
                boolean hostVisibleRam = pvm == ProtectedVM.PROTECTED_NORMAL
                    || pvm == ProtectedVM.PSEUDO_UNPROTECTED;
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
                    long guestPool = hostVisibleRam ? 0 : item.optLong("gpu_guest_pool_mb", 0);
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
                    long guestPool = hostVisibleRam ? 0 : item.optLong("gpu_guest_pool_mb", 0);
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
                    long guestPool = hostVisibleRam ? 0 : item.optLong("gpu_guest_pool_mb", 0);
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
        var protectedVm = optEnum(item, "protected_vm", defProtectedMode);
        switch (protectedVm) {
            case PROTECTED_PROTECTED:
                args.add("--protected-vm");
                break;
            case PROTECTED_WITHOUT_FIRMWARE:
                args.add("--protected-vm-without-firmware");
                break;
            case PSEUDO_UNPROTECTED:
                args.add("--protected-vm-pseudo-unprotected");
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
        // A pseudo-unprotected VM has nothing to bounce through -- its RAM is shared to it, so the
        // host can already reach every buffer the guest hands a device. A pool here would do only
        // harm: it puts a restricted-dma-pool node in the tree of a guest that was never built to
        // honour one, which is the exact thing this mode exists to avoid. Ignore the stored value
        // rather than asking everyone who switches to this mode to zero it by hand.
        if (protectedVm == ProtectedVM.PSEUDO_UNPROTECTED)
            swiotlbMb = 0;
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
            args.add(fmt("processor-version=%s", socName));
        }
        buildDiskCommand(args);
        buildNetCommand(args);
        buildSharedDirCommand(args);
        buildGpuCommand(args);
        buildScreenExportersCommand(args);
        // The evdev --input devices ride along whenever any app display path is active: the native
        // display routes everything through them; the VNC display routes its MOUSE (relative) and
        // TOUCH (multi-touch) modes here while the tablet pointer + keyboard stay on RFB.
        if (isInputBridgeNeeded()) {
            buildInputDevicesCommand(args);
        }
        buildPeripheralCommand(args);
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
        // The three paths go in as shell variables, so the body below stays a plain
        // literal instead of interleaving quoting with concatenation.
        var script = fmt(
            "p=%s\n" +
            "c=%s\n" +
            "q=%s\n" +
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
            "cat \"$p/cpus\" 2>/dev/null || cat \"$p/cpuset.cpus\" 2>/dev/null",
            ep, ec, eq);
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
            // Only virtio-fs is wired up. The editor forces it, but a hand-edited vms.json can
            // still say p9 -- and that is not a degraded mode, it is a VM that will not start:
            // crosvm's 9p config accepts `ascii_casefold` and nothing else, so every key below
            // makes the whole `--shared-dir` argument fail to parse.
            if (optEnum(dir, "type", SharedDirType.FS) != SharedDirType.FS)
                Log.w(TAG, fmt("Shared dir '%s': 9P is not implemented, using virtio-fs", tag));
            // `dax` is deliberately absent: the fs device gates DAX on cfg!(target_arch =
            // "x86_64"), so on this platform the key would only describe something that cannot
            // happen.
            var cache = optEnum(dir, "cache", SharedDirCache.AUTO);
            var arg = new StringBuilder(fmt(
                "%s:%s:type=fs:cache=%s:timeout=%d:writeback=%s:posix_acl=%s",
                path, tag,
                cache.name().toLowerCase(),
                dir.optLong("timeout", 5),
                dir.optBoolean("writeback", false),
                dir.optBoolean("posix_acl", true)
            ));
            // Root access off -- the default -- means the file server serves as the app rather
            // than as the VMM. crosvm forks and pivot_roots this device either way; these keys
            // only decide who it is once it gets there. Left as root it reaches every file root
            // can, which under /storage/emulated/0 is every other app's data as well.
            if (!dir.optBoolean("root_access", false)) {
                int uid = getAppUid();
                var gids = uid > 0 ? AppGroups.resolve(uid) : null;
                if (gids == null) {
                    // Quietly serving as root instead would make the switch mean its opposite.
                    // That is the one outcome worth losing a shared directory over.
                    Log.e(TAG, fmt(
                        "Shared dir '%s': cannot resolve the app identity (uid=%d); skipped. "
                            + "Open DroidVM once, or turn on root access for this directory.",
                        tag, uid
                    ));
                    continue;
                }
                // An Android app's primary group is its uid. Say so rather than leaving gid
                // unset, which would leave the process in root's group with the app's uid.
                arg.append(fmt(":uid=%d:gid=%d", uid, uid));
                if (gids.length > 0)
                    arg.append(fmt(":supp_gids=%s", AppGroups.join(gids)));
            }
            args.add("--shared-dir");
            args.add(arg.toString());
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
     * would silently not fire and the VM would come up without context-types=drm.
     */
    @NonNull
    private static GpuMode effectiveGpuMode(@NonNull DataItem item) {
        var mode = optEnum(item, "gpu_mode", GpuMode.NONE);
        if (mode != GpuMode.NONE) return mode;
        return GpuMode.fromLegacyApi(optEnum(item, "gpu_api", GpuApi.NONE));
    }

    /**
     * The two display devices: {@code --gpu} for the virtio-gpu screen, {@code --simplefb} for the
     * simplefb screen, each emitted exactly when its own switch is on.
     *
     * <p>One predicate per device, which is the whole point of the split. The arbitration-era
     * version emitted the GPU device's {@code displays=} for either screen, because back then the
     * simplefb bridge had no display of its own and handed its frames to this device -- so a VM
     * with only the simplefb screen on still got a virtio-gpu scanout, a Linux guest saw a
     * virtio-gpu output, drew its desktop onto it, and nobody exported it. That bridge is gone
     * (crosvm's simplefb screen opens its own sink), so the geometry belongs to the screen it
     * describes and to nothing else.</p>
     *
     * <p>{@code --gpu} and its {@code displays=} are one thing, never two: a virtio-gpu device
     * with no scanout was tried and no guest desktop ever came up on it, so it is not a
     * configuration this emits.</p>
     */
    private void buildGpuCommand(@NonNull List<String> args) {
        var item = config.item;
        var gpuScreen = isScreenEnabled(VMScreenConfig.ID_GPU0);
        var fbScreen = isScreenEnabled(VMScreenConfig.ID_SIMPLEFB);
        var api = optEnum(item, "gpu_api", GpuApi.NONE);
        if (!gpuScreen && !fbScreen) return;
        if (gpuScreen) {
            var gpu0 = VMScreenConfig.of(item, VMScreenConfig.ID_GPU0);
            var gpuBackend = optEnum(item, "gpu_backend", GpuBackend.NONE);
            var isGfxstream = gpuBackend == GpuBackend.GPU_GFXSTREAM;
            var gpuArg = new StringBuilder();
            gpuArg.append(gpuBackend.getName());
            // gfxstream host-visible Vulkan: the guest turnip (VK) + zink (GL-on-VK)
            // stack needs the gfxstream-vulkan context type.
            if (isGfxstream) {
                gpuArg.append(",context-types=gfxstream-vulkan");
            }
            // This screen's own geometry, unconditionally: the device and its scanout are emitted
            // together or not at all. What the guest is told here is what it gets -- crosvm turns
            // it into the EDID and the display-info a Linux guest picks its mode from -- and it no
            // longer has anything to do with the simplefb screen, which now carries its own size
            // to its own device below.
            gpuArg.append(fmt(",displays=[[mode=windowed[%d,%d]",
                gpu0.getWidth(), gpu0.getHeight()));
            gpuArg.append(fmt(",refresh-rate=%d", gpu0.getRefreshRate()));
            gpuArg.append(fmt(",dpi=[%d,%d]]]", gpu0.getDpiH(), gpu0.getDpiV()));

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
                // Only the DRM capset is advertised. rutabaga now keeps vrend (classic 2D
                // resources: fbcon, dumb buffers, llvmpipe scanout) initialised for every
                // virglrenderer configuration, so "drm" alone no longer fails the first
                // CREATE_2D. Not advertising VIRGL2 matters for a stock guest: with it, stock
                // Mesa picks host-GL virgl and our CPU-copy scanout cannot read those frames
                // back (black until the guest additions + mesa-guest-drm2kgsl are installed);
                // without it stock Mesa falls back to llvmpipe, which displays fine.
                gpuArg.append(",context-types=drm");
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
                // Only the venus capset is advertised (see the drm branch above: rutabaga keeps
                // vrend initialised for the classic 2D path regardless, and not advertising
                // VIRGL2 keeps a stock guest on llvmpipe instead of an unreadable host-GL
                // virgl). vulkan=true maps to use_venus on the virgl path; the venus capset also
                // forces use_venus and use_guest_vram host-side, so this is belt-and-suspenders.
                gpuArg.append(",context-types=venus");
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
        // The simplefb device is its own screen, so it rides on its own switch and carries its own
        // size -- which is no longer required to equal the GPU screen's, and usually should not be.
        //
        // poll-hz is this screen's whole answer to "how often is there a picture": nothing in the
        // device announces a frame, the guest maps the region write-combining and no write traps,
        // so the host's sampling rate is the frame rate. Sent explicitly rather than left to
        // crosvm's default, because it is a value the user can see in the editor and a default
        // that only one of the two sides knows is a value nobody can check.
        if (fbScreen) {
            var fb = VMScreenConfig.of(item, VMScreenConfig.ID_SIMPLEFB);
            args.add("--simplefb");
            args.add(fmt(
                "width=%d,height=%d,poll-hz=%d",
                fb.getWidth(), fb.getHeight(), fb.getPollHz()
            ));
        }
    }

    /**
     * One exporter per screen: {@code --android-display-service} or {@code --vnc-server}, each
     * naming the screen it is bound to.
     *
     * <p>Native display means crosvm registers an ICrosvmAndroidDisplayService binder under that
     * screen's name and renders its output straight into the Android Surface the UI hands it.
     * Touch/keyboard come back over the VM's input sockets, whose paths must match NativeDisplay.
     *
     * <p>Every binding names its screen explicitly. crosvm still accepts an exporter with no
     * {@code screen=} and resolves it to whichever screen a pre-screens command line would have
     * landed on, but writing it out means the app and the VMM agree in the config file rather
     * than in two copies of the same defaulting rule. crosvm rejects an exporter naming a screen
     * whose device is not configured, which is why every binding here is gated on
     * {@link #isScreenEnabled} -- the same predicate that decides whether {@code --gpu} and
     * {@code --simplefb} are emitted at all.</p>
     */
    private void buildScreenExportersCommand(@NonNull List<String> args) {
        for (var screen : VMScreenConfig.listOf(config.item)) {
            if (!isScreenEnabled(screen.id)) continue;
            switch (screen.getExporter()) {
                case NATIVE:
                    args.add("--android-display-service");
                    args.add(fmt("name=%s,screen=%s%s",
                        NativeDisplay.serviceName(config, screen.id), screen.id,
                        transportCapArg(screen)));
                    break;
                case VNC:
                    args.add("--vnc-server");
                    args.add(buildVncArg(screen) + transportCapArg(screen));
                    break;
                default:
                    // A screen nobody is watching. Legal, and not the same thing as no screen.
                    break;
            }
        }
    }

    /**
     * The ceiling on this binding's transport, as a key-value fragment for its exporter flag --
     * or nothing at all, which is the usual answer.
     *
     * <p>Only the bottom rung is emitted today, because it is the only one that says something
     * the negotiation would not already work out: capping at a CPU copy asks the host to skip a
     * blit it could have done. Every rung above it is at or above what any sink can currently
     * reach, so naming it would restrict nothing -- and a flag that restricts nothing is a flag
     * whose absence and presence mean the same thing, which is worse than not sending it.</p>
     *
     * <p>The stored choice is kept whichever way this goes, so a rung landing later turns an
     * already-answered preference into a real cap without asking the user again. crosvm's
     * {@code transport-cap} enum is meant to grow the same way -- new tokens added beside
     * {@code cpu}, nothing re-spelt -- so this stays a lookup rather than a translation.</p>
     */
    @NonNull
    private static String transportCapArg(@NonNull VMScreenConfig screen) {
        var cap = screen.getTransportCap();
        return cap == DisplayTransportCap.CPU
            ? fmt(",transport-cap=%s", cap.getToken()) : "";
    }

    // The virtio-input devices the UI drives; the daemon pre-binds the matching sockets (see
    // start()) and the UI ships evdev records to them via vm_input / the direct sink.
    //
    // Two of them are the VM's and two are each screen's. The keyboard and the relative pointer
    // have no output binding at all -- the guest compositor routes them by focus, and a relative
    // pointer walks from one output to the next -- so there is one of each for the VM. An
    // absolute coordinate is only meaningful against one output's geometry, so multi-touch and
    // the absolute pointer exist once per screen that has input enabled, and each carries a
    // name= built from the screen id: evdev has no "I belong to output N" field, so the guest is
    // told which touchscreen is which output by hand, keyed on that name, in every guest OS.
    // That is why the name is derived and never allocated -- it has to come back identical after
    // a reboot or the user's mapping quietly stops matching.
    private void buildInputDevicesCommand(@NonNull List<String> args) {
        var vmId = config.getId().toString();
        // No screen for these two, and the empty string says exactly that: their socket names do
        // not take one, so there is no screen a console could name that would move them.
        args.add("--input");
        args.add(fmt(
            "keyboard[path=%s]",
            NativeDisplay.inputSocketPath(vmId, "", NativeDisplay.KEYBOARD)
        ));
        // Relative-pointer mouse (REL_X/Y + buttons + wheel) for InputMode.MOUSE; the guest renders
        // the cursor, which is what relative-motion consumers (FPS games) need.
        args.add("--input");
        args.add(fmt(
            "mouse[path=%s]",
            NativeDisplay.inputSocketPath(vmId, "", NativeDisplay.MOUSE)
        ));
        // multi-touch + absolute-mouse advertise a fixed normalized ABS range (crosvm
        // NORMALIZED_ABS_MAX) because width/height are OMITTED here; the UI scales view coords to
        // that range (EvdevEncoder.NORMALIZED_ABS_MAX / TouchScaleCalculator), so the mapping is
        // resolution-independent and survives guest auto-resize -- no display size needed.
        //
        // crosvm's own RFB device set is still VM-global: --vnc-server turns on
        // display_window_mouse and creates its own touchscreen, tablet, mouse and keyboard
        // (create_display_window_input_devices). Those four now carry fixed names of their own --
        // "DroidVM VNC Touch" and siblings -- rather than copying the first --input multi-touch's
        // name, which used to hand the guest a second device under the first screen's identity.
        // So a VNC-bound VM still shows more devices than screens, but no two of them claim to be
        // the same one, and the names below stay this screen's alone.
        for (var screenId : absoluteInputScreens()) {
            args.add("--input");
            args.add(fmt(
                "multi-touch[path=%s,name=%s]",
                NativeDisplay.inputSocketPath(vmId, screenId, NativeDisplay.MULTITOUCH),
                NativeDisplay.touchDeviceName(screenId)
            ));
            // Tablet = crosvm's absolute-pointing mouse (qemu usb-tablet): ABS position + buttons
            // + wheel, so it gives the guest pointer hover, right-click and scroll -- which
            // single-touch (a BTN_TOUCH touchscreen) can't. The UI maps a host mouse/stylus onto
            // it in TABLET mode.
            //
            // It carries a name= for the same reason the touchscreen does. It could not before:
            // crosvm's AbsoluteMouse option had no name field and its enum is deny_unknown_fields,
            // so `absolute-mouse[...,name=X]` was not a device with an odd name but a command line
            // crosvm refuses, and this screen's tablet fell back to the generated "Crosvm Virtio
            // Absolute Mouse <idx>" -- an idx that counts emission order here and so moves when
            // another screen's input is switched off. Both devices of the pair are pinnable now.
            args.add("--input");
            args.add(fmt(
                "absolute-mouse[path=%s,name=%s]",
                NativeDisplay.inputSocketPath(vmId, screenId, NativeDisplay.TABLET),
                NativeDisplay.tabletDeviceName(screenId)
            ));
        }
    }

    /**
     * The screens that get their own multi-touch + absolute pointer, in schema order.
     *
     * <p>One list, read by both the socket pre-bind and the {@code --input} args, because a screen
     * in one and not the other is either a device crosvm cannot connect to (the VM does not
     * start) or a socket nothing ever opens.</p>
     */
    @NonNull
    private List<String> absoluteInputScreens() {
        var out = new ArrayList<String>();
        for (var screen : VMScreenConfig.absoluteInputOf(config.item))
            out.add(screen.id);
        return out;
    }

    // crosvm can promote the virtio-gpu worker to SCHED_FIFO (CROSVM_GPU_RT_PRIO). On gfxstream its
    // per-context render threads inherit that policy and, spin-waiting on the guest command ring,
    // can starve the normal-priority vCPU that feeds them -- a priority inversion that caps the
    // native-display present rate. So RT is opt-in and off by default: the graphics tab's
    // "real-time scheduling" switch (inside the GPU Worker Cpuset section) stores gpu_rt_prio
    // ("97" on / "" off); pass it through only when set so crosvm leaves scheduling normal
    // otherwise.
    //
    // Requires the cpuset: RT confined to the picked cores trades vCPU latency for render
    // throughput on those cores, which is the point of the switch. RT with no cpuset is a
    // different thing entirely -- FIFO 97 threads eligible for every host core, above everything
    // else Android is running -- so it is refused rather than silently applied. Called after
    // prepareGpuCgroup(), whose gpuCgroupPath is non-null only once the cpuset exists, holds
    // cores and is about to be handed to crosvm.
    private void applyGpuRtPrioEnv(@NonNull NativeProcess.Builder builder) {
        var item = config.item;
        if (!VMScreenConfig.hasGpuDevice(item)) return;
        // gpu_rt_prio is the SCHED_FIFO level as a string, "" (unset) by default. Empty means leave
        // CROSVM_GPU_RT_PRIO unset so crosvm applies no real-time scheduling (RT is opt-in).
        String prio = item.optString("gpu_rt_prio", "");
        if (prio.isEmpty()) return;
        // The editor cannot save this combination; a config built straight through the daemon
        // API can still carry it, as can one whose cpuset setup soft-failed (no root, bad path).
        if (gpuCgroupPath == null) {
            Log.w(TAG, "gpu_rt_prio set without a GPU worker cpuset; skipping real-time "
                + "scheduling rather than leaving FIFO GPU threads free on every core");
            return;
        }
        builder.environment("CROSVM_GPU_RT_PRIO", prio);
    }

    /**
     * The host-Vulkan renderers (gfxstream, and venus on virglrenderer) need their host ICD
     * selected, gfxstream its host-visible folio/blob env, and both a raised udmabuf import cap.
     * No-op for OpenGL, Native and 2D.
     */
    private void applyGfxstreamEnv(@NonNull NativeProcess.Builder builder) {
        var item = config.item;
        if (!VMScreenConfig.hasGpuDevice(item)) return;
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
     * dmabuf-to-SurfaceControl path. It belongs to the native display rather than to any one
     * screen: the bridge does the same blit for the virtio-gpu scanout and for the simplefb
     * framebuffer, so both need this pointed somewhere before either can use the GPU. (VNC has no
     * GPU half at all yet and presents through crosvm's CPU copy.) This is a separate axis from
     * the render host driver ({@link #applyGfxstreamEnv}); the two can name the same turnip .so or
     * differ.
     *
     * <p>TURNIP points the crosvm bridge at the bundled turnip. OFF -- and, until they are wired,
     * PANVK/SYSTEM -- forces crosvm's CPU copy so a stale or hand-edited value degrades cleanly
     * instead of half-loading a wrong driver. (SYSTEM will instead leave the library unset and let
     * the bridge load the SoC driver once the capability probe that gates it exists.)
     */
    private void applyDisplayBlitEnv(@NonNull NativeProcess.Builder builder) {
        var item = config.item;
        // Any screen this VM actually has, bound to the native display -- not the GPU screen's
        // binding in particular. The env var is process-wide, so it is set from whether that path
        // exists at all, and it exists for the simplefb bridge just as much: it dlopens the same
        // driver to import the framebuffer as a dma-buf and blit it. Gating on the GPU screen was
        // the arbitration-era rule, from when simplefb had no display of its own to export.
        if (!VMScreenConfig.hasNativeExporter(item)) return;
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

    /** Whether this VM has [screenId]'s display device -- the screen's own switch, and nothing else. */
    private boolean isScreenEnabled(@NonNull String screenId) {
        var screen = VMScreenConfig.find(config.item, screenId);
        return screen != null && screen.isEnabled();
    }

    /**
     * The evdev input bridge (and matching --input devices) is needed by both app display paths:
     * native uses it for every input; the VNC display uses it for MOUSE/TOUCH modes (tablet
     * pointer + keyboard ride the RFB channel instead). So: any screen with any exporter on it.
     *
     * <p>Single source of truth: this gates both the socket pre-bind in start() and the --input
     * args in buildCommand(), so the sockets and the devices never diverge. It is the gate on the
     * VM-wide keyboard and relative pointer; which screens additionally get their own absolute
     * pair is {@link #absoluteInputScreens}, and a VM with every screen's input switched off
     * still gets these two -- they are not a screen's to switch off.</p>
     */
    private boolean isInputBridgeNeeded() {
        for (var screen : VMScreenConfig.listOf(config.item)) {
            if (!isScreenEnabled(screen.id)) continue;
            if (screen.getExporter() != DisplayExporter.NONE) return true;
        }
        return false;
    }

    @NonNull
    private static String buildVncArg(@NonNull VMScreenConfig screen) {
        var vncArg = new StringBuilder();
        var host = screen.getVncHost();
        if (!host.isEmpty()) {
            vncArg.append("host=");
            vncArg.append(host);
            vncArg.append(",");
        }
        vncArg.append("port=");
        vncArg.append(Math.max(screen.getVncPort(), 1));
        var password = screen.getVncPassword();
        if (!password.isEmpty()) {
            vncArg.append(",password=");
            vncArg.append(password);
        }
        // VNC server pointer is FIXED to tablet (absolute, 1:1 cursor + hover/right-click/wheel):
        // every third-party VNC client gets absolute-tablet semantics. The app's own VNC display
        // routes MOUSE/TOUCH modes around RFB via the crosvm --input devices instead, so nothing
        // needs a different server-side mode.
        vncArg.append(",input=tablet");
        vncArg.append(",screen=");
        vncArg.append(screen.id);
        return vncArg.toString();
    }

    /**
     * Attaches the VM's peripherals. One peripheral is one guest device.
     *
     * <p>A VIRTIO_SOUND peripheral is `--virtio-snd` with a `uid`: the audio has to leave
     * the root process to be heard at all, because Android silences AAudio playback from uid 0
     * outright and hands back zeroed buffers for capture. Measured on device with the same probe
     * under different uids -- root muted both ways, shell, system and the app's own uid all fine.
     * crosvm does the moving itself, re-execing its own `device snd` backend under that uid and
     * reaching it over a socketpair. The daemon deliberately does not spawn that process: it did
     * once, and every part of doing so -- `su`, a rendezvous socket to wait for, a pid to kill on
     * teardown -- was a way to get it wrong.</p>
     *
     * <p>INTEL_HDA is accepted by the model and skipped here: crosvm emulates no HDA controller,
     * and starting a VM that claims hardware nothing can serve is worse than starting without
     * it. The UI says the same thing on the row.</p>
     */
    private void buildPeripheralCommand(@NonNull List<String> args) {
        var peripherals = VMPeripheralConfig.listOf(config.item);
        int appUid = getAppUid();
        for (var peripheral : peripherals) {
            var type = peripheral.getType();
            if (type != PeripheralType.VIRTIO_SOUND) {
                Log.w(TAG, fmt("peripheral %s skipped: no host backend", type));
                continue;
            }
            if (appUid <= 0) {
                Log.e(TAG, "cannot resolve app uid; sound device skipped");
                continue;
            }
            if (peripheral.getEndpoints().isEmpty()) {
                // A card with no endpoints is a device the guest would enumerate and find
                // nothing behind, which is worse than not offering it.
                Log.w(TAG, "virtio-snd card has no endpoints; skipped");
                continue;
            }
            args.add("--virtio-snd");
            args.add(buildSoundConfig(peripheral, appUid));
        }
    }

    /**
     * The `--virtio-snd` configuration for one peripheral.
     *
     * <p>The `uid` is what makes this audible at all. Android decides whether a stream can be
     * heard from the uid that opened it and silences uid 0 in both directions, and crosvm runs as
     * root -- so crosvm re-execs itself under this uid and serves the device over a socketpair.
     * Using the app's own uid rather than any other non-root one is what makes Android attribute
     * the audio, and the microphone indicator, to DroidVM instead of to an anonymous process.</p>
     */
    @NonNull
    private String buildSoundConfig(@NonNull VMPeripheralConfig peripheral, int appUid) {
        var endpoints = peripheral.getEndpoints();
        var outputs = new ArrayList<VMPeripheralConfig.Endpoint>();
        var inputs = new ArrayList<VMPeripheralConfig.Endpoint>();
        for (var endpoint : endpoints) {
            (endpoint.getMode().isInput() ? inputs : outputs).add(endpoint);
        }

        // A diagnostic escape hatch: with this marker present the card writes the periods it
        // receives to stream-N.out instead of playing them, which is the only way to see what
        // actually crossed the virtqueue rather than what everyone reports having sent. Gated on
        // a file rather than a build so it can be turned off without shipping anything.
        var dump = new java.io.File("/data/local/tmp/viosnd_dump");
        var cfg = new StringBuilder(dump.exists() ? "backend=file" : "backend=aaudio");
        if (dump.exists()) {
            cfg.append(fmt(",playback_path=%s,playback_size=%d",
                "/data/data/cn.classfun.droidvm/cache", 4 * 1024 * 1024));
        }
        // capture= is the card's own flag for whether it has any input at all.
        cfg.append(fmt(",capture=%b", !inputs.isEmpty()));
        cfg.append(fmt(",num_output_devices=%d", outputs.size()));
        cfg.append(fmt(",num_input_devices=%d", inputs.size()));
        // The endpoints go over by name, not by number. A number is only what the platform calls
        // an endpoint today: reconnect a headset and it has a different one, while the name is
        // unchanged -- so crosvm looks each one up in the table for itself, every time it opens a
        // stream, and finds a returning device without being told.
        cfg.append(fmt(",device_table=%s", HostAudioTable.PATH));
        appendEndpoints(cfg, "output_device_config", outputs);
        appendEndpoints(cfg, "input_device_config", inputs);
        // Shared by every endpoint on the card, because they describe the device's queues rather
        // than any one endpoint: one underrun policy, one buffer depth. Separate cards keep their
        // own.
        cfg.append(fmt(",underrun=%s", peripheral.getUnderrun().name().toLowerCase()));
        // The latency knob. crosvm publishes it in the device's vendor config block; a driver
        // that does not read the block keeps its own default, which is why this can be a hint.
        cfg.append(fmt(",guest_outstanding_packets=%d", peripheral.getBuffer().getPackets()));
        // Same field name as --shared-dir and --pmem-ext2 use for the process a device runs as.
        // No supplementary groups: the VMM's are root's, and audio needs none -- recording was
        // measured working with an empty group list.
        cfg.append(fmt(",uid=%d", appUid));
        Log.i(TAG, fmt("sound device: %s", cfg));
        return cfg.toString();
    }

    /**
     * Appends one direction's endpoints as a list of per-device settings.
     *
     * <p>Their order here is their `hda_fn_nid` on the other side, which is what ties a stream to
     * the endpoint it belongs to -- so it has to match the order the counts were taken in.</p>
     */
    private void appendEndpoints(
        @NonNull StringBuilder cfg, @NonNull String field,
        @NonNull List<VMPeripheralConfig.Endpoint> endpoints
    ) {
        if (endpoints.isEmpty()) return;
        cfg.append(fmt(",%s=[", field));
        for (int i = 0; i < endpoints.size(); i++) {
            var endpoint = endpoints.get(i);
            // An unset field is what older configs stored for "follow the platform"; it names the
            // same endpoint now, so everything downstream sees a device rather than an absence.
            var hostKey = endpoint.getHostDevice();
            if (hostKey.isEmpty()) hostKey = HostAudioDevices.SYSTEM_DEFAULT_KEY;
            // Quoted, because a key is TYPE|address and the bar would otherwise read as a
            // separator to the option parser.
            cfg.append(fmt("%s[host_device=\"%s\"]", i == 0 ? "" : ",", hostKey));
            // Only to say in the log which endpoint that name means right now.
            resolveHostDevice(hostKey, endpoint.getMode().isInput(), endpoint.getMode());
        }
        cfg.append("]");
    }

    /** The app's uid, which the daemon can reach through its system context. */
    private int getAppUid() {
        try {
            var sys = DaemonSystemContext.get();
            if (sys != null) {
                return sys.getPackageManager()
                    .getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid;
            }
        } catch (Throwable t) {
            Log.w(TAG, "package manager lookup failed; falling back to the data dir owner", t);
        }
        // Fallback: the data directory belongs to the app uid by construction.
        try {
            return android.system.Os.stat(DATA_DIR).st_uid;
        } catch (Throwable t) {
            Log.w(TAG, "stat of the data dir failed", t);
            return -1;
        }
    }

    /**
     * Live AAudio device id for a stored host endpoint, or 0 (AAUDIO_UNSPECIFIED) when it asked
     * to follow the system or names something that is not connected right now. The ids are
     * per-boot, which is why the config stores a descriptor and this happens at start.
     */
    private int resolveHostDevice(
        @NonNull String key, boolean input, @NonNull SoundMode mode
    ) {
        if (key.isEmpty()) return HostAudioDevices.DEVICE_UNSPECIFIED;
        var sys = DaemonSystemContext.get();
        if (sys == null) {
            Log.w(TAG, fmt("no system context; %s falls back to default audio routing", key));
            return HostAudioDevices.DEVICE_UNSPECIFIED;
        }
        int id = HostAudioDevices.resolve(sys, input, key);
        Log.i(TAG, fmt("%s -> host device %s (id=%d)", mode, key, id));
        return id;
    }


    /** One port's crosvm argument pieces, resolved from its backend. */
    private static final class SerialArg {
        String type = "sink";
        String path;
        String input;
        boolean interactive;
    }

    @NonNull
    private SerialArg serialArgOf(@NonNull ResolvedSerial rs) {
        var arg = new SerialArg();
        var port = rs.port;
        switch (port.getBackend()) {
            case APP_CONSOLE:
                if (rs.pipe != null) {
                    arg.type = "file";
                    arg.path = fmt("/proc/self/fd/%d", rs.pipe.getOutputRemoteFd());
                    arg.input = fmt("/proc/self/fd/%d", rs.pipe.getInputRemoteFd());
                    arg.interactive = true;
                }
                break;
            case PTY:
                // crosvm's own pty type: it holds the master; the optional path becomes
                // a symlink to the slave for external consumers.
                arg.type = "pty";
                if (!port.getPath().isEmpty()) arg.path = port.getPath();
                arg.interactive = true;
                break;
            case FILE:
                arg.type = "file";
                arg.path = port.getPath();
                break;
            case UNIX:
                arg.type = "unix";
                arg.path = port.getPath();
                break;
            case UNIX_STREAM:
                // crosvm is the connecting side: the consumer must already listen there.
                arg.type = "unix-stream";
                arg.path = port.getPath();
                arg.interactive = true;
                break;
            case STDOUT:
                arg.type = "stdout";
                break;
            case SYSLOG:
                arg.type = "syslog";
                break;
            case USB_ACM:
                // A pool member was attached in resolveSerialPorts; crosvm opens the
                // ttyGSn raw and non-blocking (drops output when the external host is
                // not draining, so the guest console never stalls).
                if (rs.acmDevPath != null) {
                    arg.type = "dev";
                    arg.path = rs.acmDevPath;
                    arg.interactive = true;
                }
                break;
            case SINK:
            default:
                break;
        }
        return arg;
    }

    /**
     * One --serial per configured port. Exactly one port carries console+earlycon -- that is
     * what crosvm's FDT stdout-path (and so EDK2's SPCR, and so Windows EMS/SAC) points at.
     * The port the user marked as console wins, whatever its backend: a sink console is a
     * deliberate "discard the guest console". Only configs from before the explicit flag
     * fall back to the historical rule, the first port that can carry a conversation.
     */
    private void buildSerialCommand(@NonNull List<String> args) {
        var serialArgs = new ArrayList<SerialArg>(resolvedSerials.size());
        for (var rs : resolvedSerials)
            serialArgs.add(serialArgOf(rs));
        var consoleIdx = -1;
        for (int i = 0; i < resolvedSerials.size(); i++)
            if (resolvedSerials.get(i).port.isConsole()) {
                consoleIdx = i;
                break;
            }
        if (consoleIdx < 0)
            for (int i = 0; i < serialArgs.size(); i++)
                if (serialArgs.get(i).interactive) {
                    consoleIdx = i;
                    break;
                }
        for (int i = 0; i < resolvedSerials.size(); i++) {
            var port = resolvedSerials.get(i).port;
            var arg = serialArgs.get(i);
            var serial = new StringBuilder(fmt(
                "type=%s,hardware=%s,num=%d",
                arg.type, port.getHardware().getCrosvmName(), port.getNum()
            ));
            if (arg.path != null) serial.append(fmt(",path=%s", arg.path));
            if (arg.input != null) serial.append(fmt(",input=%s", arg.input));
            if (i == consoleIdx) {
                serial.append(",console");
                // earlycon is a UART notion; a virtio-console has no early MMIO registers.
                if (port.getHardware() != SerialHardware.VIRTIO_CONSOLE)
                    serial.append(",earlycon");
            }
            args.add("--serial");
            args.add(serial.toString());
        }
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
    public boolean writeNativeInput(@NonNull String screenId, int channel, @NonNull byte[] data) {
        return inputBridge.writeNativeInput(screenId, channel, data);
    }

    @Override
    public void cleanup() {
        if (controlSocketPath != null) {
            deleteFile(controlSocketPath);
            controlSocketPath = null;
        }
        inputBridge.release();
        closeSerialPorts();
    }
}
