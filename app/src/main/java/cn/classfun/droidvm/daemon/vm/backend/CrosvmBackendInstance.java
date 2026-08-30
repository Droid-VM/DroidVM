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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.daemon.console.FDPipeConsoleStream;
import cn.classfun.droidvm.daemon.console.InputConsoleStream;
import cn.classfun.droidvm.daemon.console.SimpleConsoleStream;
import cn.classfun.droidvm.daemon.server.ServerContext;
import cn.classfun.droidvm.daemon.vm.BootPlan;
import cn.classfun.droidvm.daemon.vm.SerialPipe;
import cn.classfun.droidvm.daemon.vm.UsbAcmPool;
import cn.classfun.droidvm.daemon.vm.VMBackendInstance;
import cn.classfun.droidvm.daemon.vm.VMStartResult;
import cn.classfun.droidvm.lib.natives.NativeProcess;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.vm.DisplayBackend;
import cn.classfun.droidvm.lib.store.vm.CpuPlacementPlan;
import cn.classfun.droidvm.lib.store.vm.GpuApi;
import cn.classfun.droidvm.lib.store.vm.GpuBackend;
import cn.classfun.droidvm.lib.store.vm.LendMthpMode;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;
import cn.classfun.droidvm.lib.store.vm.SerialBackend;
import cn.classfun.droidvm.lib.store.vm.SerialHardware;
import cn.classfun.droidvm.lib.store.vm.VMSerialConfig;
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
            if (!inputBridge.startListening(NativeDisplay.serviceName(config))) {
        // Single source of truth: isInputBridgeNeeded() gates both this pre-bind and the --input
        if (isInputBridgeNeeded()) {
                    Log.e(TAG, "Display input sockets unavailable; crosvm will likely fail");
                }
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
        hypervisor = VMHypervisor.resolveConfigured(VMBackend.CROSVM, hypervisor);
        if (hypervisor == null) throw new RuntimeException("No supported hypervisor found for CROSVM backend");
        args.add("--hypervisor");
        var defProtectedMode = ProtectedVM.PROTECTED_NORMAL;
        switch (hypervisor) {
            case KVM:
                args.add("kvm");
                break;
            case GUNYAH:
                args.add("gunyah");
                defProtectedMode = ProtectedVM.PROTECTED_WITHOUT_FIRMWARE;
                break;
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
            args.add(fmt("processor-version=%s", socName));
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
        // The switch and the device both: the threads this cpuset exists to hold are the
        // virtio-gpu device's workers, so with no device there is nobody to put in it.
        if (!CpuPlacementPlan.wantsGpuCgroup(item)) return;
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

        hypervisor = VMHypervisor.resolveConfigured(VMBackend.CROSVM, hypervisor);
    private void buildGpuCommand(@NonNull List<String> args) {
        var item = config.item;
        var useGpu = item.optBoolean("gpu_enabled", false);
        var useDisplay = item.optBoolean("display_enabled", false);
        var backend = optEnum(item, "display_backend", DisplayBackend.NONE);
        var api = optEnum(item, "gpu_api", GpuApi.NONE);
        if (!useGpu && !useDisplay) return;
        if (useGpu) {
            var gpuBackend = optEnum(item, "gpu_backend", GpuBackend.NONE);
            var gpuArg = new StringBuilder();
            gpuArg.append(gpuBackend.getName());
            if (useDisplay && backend == VIRTIO_GPU) {
                gpuArg.append(fmt(",displays=[[mode=windowed[%d,%d]",
                    item.optLong("display_width", 1280),
                    item.optLong("display_height", 720)));
                gpuArg.append(fmt(",refresh-rate=%d",
                    item.optLong("display_refresh_rate", 60)));
                gpuArg.append(fmt(",dpi=[%d,%d]]]",
                    item.optLong("display_dpi_h", 160),
                    item.optLong("display_dpi_v", 160)));
            }

            gpuArg.append(fmt(",vulkan=%s", String.valueOf(api == VULKAN)));
            switch (api) {
                case EGL:
                    gpuArg.append(",egl=true");
                    break;
                case OPENGLES:
                    gpuArg.append(",gles=true");
                    break;
                case ANGLE:
                    gpuArg.append(",angle=true");
                    break;
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
                    args.add("--android-display-service");
        }
    }

    private void buildNativeDisplayCommand(@NonNull List<String> args) {
        var item = config.item;
        var serviceName = NativeDisplay.serviceName(config);
        var width = item.optLong("display_width", 1280);
        var height = item.optLong("display_height", 720);
        // multi-touch ABS range must equal the guest resolution so view coords scale straight onto
        // ABS_X/ABS_Y (see EvdevEncoder / TouchScaleCalculator).
        args.add("--input");
        args.add(fmt(
            "multi-touch[path=%s,width=%d,height=%d]",
            NativeDisplay.inputSocketPath(serviceName, NativeDisplay.MULTITOUCH), width, height
        ));
    private void buildInputDevicesCommand(@NonNull List<String> args) {
        // Relative-pointer mouse (REL_X/Y + buttons + wheel) for InputMode.MOUSE; the guest renders
        // the cursor, which is what relative-motion consumers (FPS games) need.
        args.add("--input");
        args.add(fmt(
            "keyboard[path=%s]",
            NativeDisplay.inputSocketPath(serviceName, NativeDisplay.KEYBOARD)
            "mouse[path=%s]",
        ));
            ));
            args.add("--input");
            args.add(fmt(
            ));
    }

    /** True iff the per-VM crosvm command will reference native-display input sockets. */
    private boolean isNativeDisplayEnabled() {
        var item = config.item;
        if (!item.optBoolean("gpu_enabled", false)) return false;
        if (!item.optBoolean("display_enabled", false)) return false;
        var backend = optEnum(item, "display_backend", DisplayBackend.NONE);
        if (backend != DisplayBackend.VIRTIO_GPU) return false;
        return item.optBoolean("native_display_enabled", false);
    }

    private void buildVncCommand(@NonNull List<String> args) {
        var item = config.item;
        if (!item.optBoolean("vnc_enabled", false)) return;
    /**
     * The evdev input bridge (and matching --input devices) is needed by both app display paths:
     * native uses it for every input; the VNC display uses it for MOUSE/TOUCH modes (tablet
     */
    private boolean isInputBridgeNeeded() {
    }

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
        args.add("--vnc-server");
        args.add(vncArg.toString());
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
        closeSerialPorts();
    }
}
