package cn.classfun.droidvm.daemon.vm.backend;

import static android.net.LocalSocketAddress.Namespace.FILESYSTEM;
import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.lib.natives.NativeProcess.RLIM_INFINITY;
import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.FileUtils.deleteFile;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.store.vm.DisplayBackend.SIMPLEFB;
import static cn.classfun.droidvm.lib.store.vm.DisplayBackend.VIRTIO_GPU;
import static cn.classfun.droidvm.lib.store.vm.GpuApi.VULKAN;

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
import cn.classfun.droidvm.lib.natives.NativeProcess;
import cn.classfun.droidvm.daemon.vm.SerialPipe;
import cn.classfun.droidvm.daemon.vm.VMBackendInstance;
import cn.classfun.droidvm.daemon.vm.VMStartResult;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.DisplayBackend;
import cn.classfun.droidvm.lib.store.vm.GpuApi;
import cn.classfun.droidvm.lib.store.vm.GpuBackend;
import cn.classfun.droidvm.lib.store.vm.SharedDirCache;
import cn.classfun.droidvm.lib.store.vm.SharedDirType;

@SuppressWarnings("FieldCanBeLocal")
public final class CrosvmBackendInstance extends VMBackendInstance {
    private static final String TAG = "CrosvmBackendInstance";
    private static final String RUN_PATH = pathJoin(DATA_DIR, "run");
    private final VMConfig config;
    private SerialPipe uart = null;
    private String controlSocketPath = null;
    private final FDPipeConsoleStream uartStream;
    private final InputConsoleStream stdoutStream;
    private final InputConsoleStream stderrStream;
    private final SimpleConsoleStream stdioStream;

    public CrosvmBackendInstance(@NonNull VMConfig config) {
        this.config = config;
        uartStream = new FDPipeConsoleStream(config, "uart", -1, -1);
        stdoutStream = new InputConsoleStream(config, "stdout", null);
        stderrStream = new InputConsoleStream(config, "stderr", null);
        stdioStream = new SimpleConsoleStream(config, "stdio");
        addStream(stdoutStream);
        addStream(stderrStream);
        addStream(stdioStream);
        addStream(uartStream);
    }

    private void cleanUpMemory() {
        run("echo madvise > /sys/kernel/mm/transparent_hugepage/enabled");
        run("echo madvise > /sys/kernel/mm/transparent_hugepage/defrag");
        run("echo advise > /sys/kernel/mm/transparent_hugepage/shmem_enabled");
        run("echo 0 > /dev/memcg/apps/memory.force_empty");
        run("echo 0 > /dev/memcg/system/memory.force_empty");
        run("echo 3 > /proc/sys/vm/drop_caches");
        run("echo 1 > /proc/sys/vm/compact_memory");
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
        var args = buildCommand();
        Log.i(TAG, fmt("Executing: %s", String.join(" ", args)));
        try {
            var builder = new NativeProcess.Builder(args.toArray(new String[0]));
            String[] preload = {
                pathJoin(DATA_DIR, "lib", "libsimpledump.so"),
            };
            builder.environment("LD_PRELOAD", String.join(":", preload));
            builder.environment("LD_LIBRARY_PATH", pathJoin(DATA_DIR, "usr", "lib"));
            if (uart != null) {
                builder.preserveFd(uart.getOutputRemoteFd());
                builder.preserveFd(uart.getInputRemoteFd());
            }
            builder.maxOpenFiles(65536);
            builder.maxLockedMemory(RLIM_INFINITY);
            cleanUpMemory();
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
            return result;
        }
        return result;
    }

    @NonNull
    private List<String> buildCommand() {
        var item = config.item;
        var args = new ArrayList<String>();
        args.add(getPrebuiltBinaryPath("crosvm"));
        args.add("run");
        args.add("--name");
        args.add(config.getName());
        args.add("--mem");
        args.add(String.valueOf(Math.max(item.optLong("memory_mb", 512), 64)));
        args.add("--cpus");
        args.add(String.valueOf(Math.max(item.optLong("cpu_count", 1), 1)));
        switch (optEnum(item, "protected_vm", ProtectedVM.PROTECTED_WITHOUT_FIRMWARE)) {
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
        var initrd = item.optString("initrd", "");
        if (!initrd.isEmpty()) {
            args.add("--initrd");
            args.add(initrd);
        }
        var cmdline = item.optString("cmdline", "");
        if (!cmdline.isEmpty()) {
            args.add("--params");
            args.add(cmdline);
        }
        if (controlSocketPath != null) {
            args.add("--socket");
            args.add(controlSocketPath);
        }
        buildDiskCommand(args);
        buildNetCommand(args);
        buildSharedDirCommand(args);
        buildGpuCommand(args);
        buildVncCommand(args);
        buildSerialCommand(args);
        var kernel = item.optString("kernel", "");
        if (!kernel.isEmpty())
            args.add(kernel);
        return args;
    }

    private void buildDiskCommand(@NonNull List<String> args) {
        var disks = config.item.opt("disks", null);
        if (disks == null) return;
        for (var iter : disks) {
            var disk = iter.getValue();
            var path = disk.optString("path", "");
            if (path.isEmpty()) continue;
            var readonly = disk.optBoolean("readonly", false);
            var bus = optEnum(disk, "bus", DiskBus.VIRTIO);
            var arg = new StringBuilder(path);
            switch (bus) {
                case SCSI:
                    if (readonly) arg.append(",ro=true");
                    args.add("--scsi-block");
                    args.add(arg.toString());
                    break;
                case PMEM:
                    if (readonly) arg.append(",ro=true");
                    args.add("--pmem");
                    args.add(arg.toString());
                    break;
                case PFLASH:
                    args.add("--pflash");
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
    public void cleanup() {
        if (controlSocketPath != null) {
            deleteFile(controlSocketPath);
            controlSocketPath = null;
        }
    }
}
