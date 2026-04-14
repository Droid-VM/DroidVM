package cn.classfun.droidvm.daemon.vm.backend;

import static android.net.LocalSocketAddress.Namespace.FILESYSTEM;
import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.Constants.PATH_EDK2_FIRMWARE;
import static cn.classfun.droidvm.lib.Constants.PATH_EDK2_QEMU_FIRMWARE;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.daemon.console.InputConsoleStream;
import cn.classfun.droidvm.daemon.console.LocalSocketConsoleStream;
import cn.classfun.droidvm.daemon.console.SimpleConsoleStream;
import cn.classfun.droidvm.daemon.vm.VMBackendInstance;
import cn.classfun.droidvm.daemon.vm.VMStartResult;
import cn.classfun.droidvm.lib.natives.NativeProcess;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.vm.DisplayBackend;
import cn.classfun.droidvm.lib.store.vm.GpuBackend;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.SharedDirCache;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

@SuppressWarnings("FieldCanBeLocal")
public final class QemuBackendInstance extends VMBackendInstance {
    private static final String TAG = "QemuBackendInstance";
    private static final String RUN_PATH = pathJoin(DATA_DIR, "run");
    private final VMConfig config;
    private String qmpSocketPath = null;
    private String uartSocketPath = null;
    private int ioThreadCounter = 0;
    private int driveCounter = 0;
    private final LocalSocketConsoleStream uartStream;
    private final InputConsoleStream stdoutStream;
    private final InputConsoleStream stderrStream;
    private final SimpleConsoleStream stdioStream;

    public QemuBackendInstance(@NonNull VMConfig config) {
        this.config = config;
        uartStream = new LocalSocketConsoleStream(config, "uart", null);
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
        qmpSocketPath = pathJoin(RUN_PATH, fmt("%s-qmp.sock", config.getName()));
        deleteFile(qmpSocketPath);
        uartSocketPath = pathJoin(RUN_PATH, fmt("%s-uart.sock", config.getName()));
        deleteFile(uartSocketPath);
        Log.i(TAG, fmt("QMP socket path: %s", qmpSocketPath));
        var args = buildCommand();
        Log.i(TAG, fmt("Executing: %s", String.join(" ", args)));
        try {
            var builder = new NativeProcess.Builder(args.toArray(new String[0]));
            prepareProcess(builder);
            var process = builder.start();
            result.setProcess(process);
            stdoutStream.setInputStream(process.getInputStream());
            stderrStream.setInputStream(process.getErrorStream());
        } catch (IOException e) {
            Log.e(TAG, "Failed to start qemu process", e);
            return result;
        }
        waitForUartClient();
        return result;
    }

    private void waitForUartClient() {
        int i = 0;
        Log.i(TAG, fmt("UART socket path: %s", uartSocketPath));
        while (true) {
            try {
                var uart = new LocalSocket(LocalSocket.SOCKET_STREAM);
                uart.connect(new LocalSocketAddress(uartSocketPath, FILESYSTEM));
                Log.i(TAG, "UART client connected");
                uartStream.setSocket(uart);
                return;
            } catch (Exception e) {
                if (i >= 50) {
                    Log.e(TAG, "failed to create UART socket after multiple attempts, giving up");
                    throw new RuntimeException(e);
                }
                i++;
            }
            threadSleep(100);
        }
    }

    @NonNull
    private List<String> buildCommand() {
        var item = config.item;
        var args = new ArrayList<String>();
        args.add(getPrebuiltBinaryPath("qemu-system-aarch64"));
        args.add("-name");
        args.add(config.getName());
        args.add("-L");
        args.add(pathJoin(DATA_DIR, "usr", "share", "qemu"));
        var protectedVm = optEnum(item, "protected_vm", ProtectedVM.PROTECTED_WITHOUT_FIRMWARE);
        switch (protectedVm) {
            case PROTECTED_PROTECTED:
            case PROTECTED_WITHOUT_FIRMWARE:
                args.add("-machine");
                args.add("virt,confidential-guest-support=prot0");
                args.add("-accel");
                args.add("gunyah");
                break;
            default:
                args.add("-machine");
                args.add("virt");
                args.add("-accel");
                args.add("gunyah");
                break;
        }
        args.add("-cpu");
        args.add(item.optBoolean("pmu", false) ? "host" : "host,pmu=off");
        long cpuCount = Math.max(item.optLong("cpu_count", 1), 1);
        boolean smt = item.optBoolean("smt", true);
        long threads = smt ? 2 : 1;
        long cores = Math.max(cpuCount / threads, 1);
        args.add("-smp");
        args.add(fmt("%d,sockets=1,cores=%d,threads=%d", cores * threads, cores, threads));
        long memMb = Math.max(item.optLong("memory_mb", 512), 64);
        args.add("-m");
        args.add(fmt("%dM", memMb));
        switch (protectedVm) {
            case PROTECTED_PROTECTED:
            case PROTECTED_WITHOUT_FIRMWARE:
                args.add("-object");
                args.add("arm-confidential-guest,id=prot0,swiotlb-size=64M");
                break;
        }
        if (item.optBoolean("use_uefi", true)) {
            args.add("-bios");
            args.add(PATH_EDK2_QEMU_FIRMWARE);
        } else {
            var bios = item.optString("bios", "");
            if (!bios.isEmpty()) {
                args.add("-bios");
                args.add(bios);
            }
        }
        var kernel = item.optString("kernel", "");
        if (!kernel.isEmpty() && !kernel.equals(PATH_EDK2_FIRMWARE)) {
            args.add("-kernel");
            args.add(kernel);
        }
        var initrd = item.optString("initrd", "");
        if (!initrd.isEmpty()) {
            args.add("-initrd");
            args.add(initrd);
        }
        var cmdline = item.optString("cmdline", "");
        if (!cmdline.isEmpty()) {
            args.add("-append");
            args.add(cmdline);
        }
        if (item.optBoolean("hugepages", true))
            args.add("-mem-prealloc");
        if (item.optBoolean("rng", true)) {
            args.add("-object");
            args.add("rng-random,filename=/dev/urandom,id=rng0");
            args.add("-device");
            args.add("virtio-rng-pci,rng=rng0,disable-legacy=on,disable-modern=off");
        }
        if (item.optBoolean("balloon", false)) {
            args.add("-device");
            args.add("virtio-balloon-pci,disable-legacy=on,disable-modern=off");
        }
        buildInputCommand(args);
        buildUsbCommand(args);
        buildDiskCommand(args);
        buildNetCommand(args);
        buildSharedDirCommand(args);
        buildAudioCommand(args);
        buildGpuCommand(args);
        buildVncCommand(args);
        args.add("-chardev");
        args.add(fmt("socket,id=uart0,path=%s,server=on,wait=on", uartSocketPath));
        args.add("-serial");
        args.add("chardev:uart0");
        args.add("-qmp");
        args.add(fmt("unix:%s,server,nowait", qmpSocketPath));
        args.add("-nodefaults");
        return args;
    }

    private void buildDiskCommand(@NonNull List<String> args) {
        var disks = config.item.opt("disks", null);
        if (disks == null) return;
        int bootIndex = 1;
        boolean scsiAdded = false;
        int pmemCounter = 0;
        for (var iter : disks) {
            var disk = iter.getValue();
            var path = disk.optString("path", "");
            if (path.isEmpty()) continue;
            var readonly = disk.optBoolean("readonly", false);
            var bus = optEnum(disk, "bus", DiskBus.VIRTIO);
            switch (bus) {
                case PFLASH:
                    args.add("-drive");
                    args.add(fmt("file=%s,if=pflash,format=raw", path));
                    break;
                case CDROM: {
                    if (!scsiAdded) {
                        args.add("-device");
                        args.add("virtio-scsi-pci,id=scsi0,disable-legacy=on,disable-modern=off");
                        scsiAdded = true;
                    }
                    var cdId = fmt("cd%d", driveCounter++);
                    args.add("-drive");
                    args.add(fmt("file=%s,if=none,id=%s,media=cdrom,readonly=on", path, cdId));
                    args.add("-device");
                    args.add(fmt("scsi-cd,drive=%s,bus=scsi0.0", cdId));
                    break;
                }
                case SCSI: {
                    if (!scsiAdded) {
                        args.add("-device");
                        args.add("virtio-scsi-pci,id=scsi0,disable-legacy=on,disable-modern=off");
                        scsiAdded = true;
                    }
                    var drId = fmt("scsi%d", driveCounter++);
                    var driveArg = new StringBuilder();
                    driveArg.append(fmt("file=%s,if=none,id=%s", path, drId));
                    if (readonly) driveArg.append(",readonly=on");
                    args.add("-drive");
                    args.add(driveArg.toString());
                    args.add("-device");
                    args.add(fmt("scsi-hd,drive=%s,bus=scsi0.0,bootindex=%d",
                        drId, bootIndex++));
                    break;
                }
                case PMEM: {
                    var pmId = fmt("pmem%d", pmemCounter++);
                    long fileSize = new File(path).length();
                    if (fileSize <= 0) fileSize = 64L * 1024 * 1024;
                    args.add("-object");
                    args.add(fmt("memory-backend-file,id=%s,mem-path=%s,share=on,size=%d",
                        pmId, path, fileSize));
                    args.add("-device");
                    args.add(fmt("virtio-pmem-pci,memdev=%s,disable-legacy=on,disable-modern=off",
                        pmId));
                    break;
                }
                case VIRTIO: {
                    var ioId = fmt("io%d", ioThreadCounter++);
                    var drId = fmt("dr%d", driveCounter++);
                    args.add("-object");
                    args.add(fmt("iothread,id=%s", ioId));
                    var driveArg = new StringBuilder();
                    driveArg.append(fmt("file=%s,if=none,id=%s", path, drId));
                    driveArg.append(",cache=unsafe,aio=threads,discard=unmap");
                    if (readonly) driveArg.append(",readonly=on");
                    args.add("-drive");
                    args.add(driveArg.toString());
                    args.add("-device");
                    args.add(fmt(
                        "virtio-blk-pci,drive=%s,iothread=%s,disable-legacy=on,disable-modern=off,bootindex=%d",
                        drId, ioId, bootIndex++));
                    break;
                }
            }
        }
    }

    private void buildInputCommand(@NonNull List<String> args) {
        if (!config.item.optBoolean("display_enabled", false)) return;
        args.add("-device");
        args.add("virtio-multitouch-pci,disable-legacy=on,disable-modern=off");
        args.add("-device");
        args.add("virtio-keyboard-pci,disable-legacy=on,disable-modern=off");
    }

    private void buildUsbCommand(@NonNull List<String> args) {
        if (!config.item.optBoolean("usb", true)) return;
        args.add("-device");
        args.add("qemu-xhci,id=usb-bus,p2=15,p3=15");
        if (config.item.optBoolean("display_enabled", false)) {
            args.add("-device");
            args.add("usb-tablet,bus=usb-bus.0");
            args.add("-device");
            args.add("usb-kbd,bus=usb-bus.0");
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
            var fsArg = new StringBuilder();
            fsArg.append(fmt("local,path=%s,mount_tag=%s,security_model=mapped-xattr", path, tag));
            var cache = optEnum(dir, "cache", SharedDirCache.AUTO);
            switch (cache) {
                case NEVER:
                    fsArg.append(",writeout=immediate");
                    break;
                case ALWAYS:
                    fsArg.append(",writeout=immediate,fmode=0644,dmode=0755");
                    break;
            }
            if (dir.optBoolean("readonly", false))
                fsArg.append(",readonly=on");
            args.add("-virtfs");
            args.add(fsArg.toString());
        }
    }

    private void buildAudioCommand(@NonNull List<String> args) {
        var displayEnabled = config.item.optBoolean("display_enabled", false);
        if (!config.item.optBoolean("audio_enabled", displayEnabled)) return;
        args.add("-audiodev");
        args.add("aaudio,id=snd0");
        args.add("-device");
        args.add("virtio-sound-pci,audiodev=snd0,disable-legacy=on,disable-modern=off");
    }

    private void buildNetCommand(@NonNull List<String> args) {
        var nets = config.item.opt("networks", null);
        if (nets == null) {
            args.add("-device");
            args.add("virtio-net-pci,disable-legacy=on,disable-modern=off");
            return;
        }
        boolean hasNet = false;
        for (var iter : nets) {
            var net = iter.getValue();
            var tapName = net.optString("tap_name", "");
            if (tapName.isEmpty()) continue;
            hasNet = true;
            var netArg = new StringBuilder();
            netArg.append(fmt("tap,ifname=%s,script=no,downscript=no", tapName));
            var mac = net.optString("mac_address", "");
            if (!mac.isEmpty()) {
                args.add("-device");
                args.add(fmt("virtio-net-pci,netdev=net_%s,mac=%s,disable-legacy=on,disable-modern=off",
                    tapName, mac));
            } else {
                args.add("-device");
                args.add(fmt("virtio-net-pci,netdev=net_%s,disable-legacy=on,disable-modern=off",
                    tapName));
            }
            args.add("-netdev");
            args.add(fmt("%s,id=net_%s", netArg, tapName));
        }
        if (!hasNet) {
            args.add("-device");
            args.add("virtio-net-pci,disable-legacy=on,disable-modern=off");
        }
    }

    private void buildGpuCommand(@NonNull List<String> args) {
        var item = config.item;
        var useGpu = item.optBoolean("gpu_enabled", false);
        var useDisplay = item.optBoolean("display_enabled", false);
        if (!useGpu && !useDisplay) return;
        var backend = optEnum(item, "display_backend", DisplayBackend.NONE);
        if (useGpu) {
            var gpuBackend = optEnum(item, "gpu_backend", GpuBackend.NONE);
            var gpuArg = new StringBuilder();
            boolean use3d = false;
            switch (gpuBackend) {
                case GPU_VIRGLRENDERER:
                    gpuArg.append("virtio-gpu-gl-pci");
                    use3d = true;
                    break;
                case GPU_GFXSTREAM:
                    gpuArg.append("virtio-gpu-rutabaga-pci");
                    use3d = true;
                    break;
                default:
                    gpuArg.append("virtio-gpu-pci");
                    break;
            }
            gpuArg.append(",disable-legacy=on,disable-modern=off");
            if (useDisplay && backend == DisplayBackend.VIRTIO_GPU) {
                long width = item.optLong("display_width", 1280);
                long height = item.optLong("display_height", 720);
                gpuArg.append(fmt(",xres=%d,yres=%d", width, height));
                gpuArg.append(",edid=on");
            }
            if (use3d) {
                gpuArg.append(",blob=on");
                args.add("-display");
                args.add("egl-headless");
            }
            args.add("-device");
            args.add(gpuArg.toString());
        } else if (backend == DisplayBackend.VIRTIO_GPU) {
            long width = item.optLong("display_width", 1280);
            long height = item.optLong("display_height", 720);
            args.add("-device");
            args.add(fmt("virtio-gpu-pci,disable-legacy=on,disable-modern=off,xres=%d,yres=%d,edid=on",
                width, height));
        }
        if (useDisplay && backend == DisplayBackend.SIMPLEFB) {
            args.add("-device");
            args.add("ramfb");
        }
    }

    private void buildVncCommand(@NonNull List<String> args) {
        var item = config.item;
        if (!item.optBoolean("vnc_enabled", false)) return;
        var host = item.optString("vnc_host", "0.0.0.0");
        if (host.isEmpty()) host = "0.0.0.0";
        long port = Math.max(item.optLong("vnc_port", 5900), 1);
        long displayNum = port - 5900;
        if (displayNum < 0) displayNum = 0;
        var vncArg = new StringBuilder();
        vncArg.append(fmt("%s:%d", host, displayNum));
        var password = item.optString("vnc_password", "");
        if (!password.isEmpty()) {
            args.add("-object");
            args.add(fmt("secret,id=vnc-password,data=%s", password));
            vncArg.append(",password-secret=vnc-password");
        }
        args.add("-vnc");
        args.add(vncArg.toString());
    }

    @Nullable
    private JSONObject sendQMP(@NonNull JSONObject command) {
        if (qmpSocketPath == null) return null;
        try (var socket = new LocalSocket(LocalSocket.SOCKET_STREAM)) {
            socket.connect(new LocalSocketAddress(qmpSocketPath, FILESYSTEM));
            socket.setSoTimeout(5000);
            var in = socket.getInputStream();
            var out = socket.getOutputStream();
            var buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) return null;
            Log.d(TAG, fmt("QMP greeting: %s", new String(buf, 0, n, UTF_8)));
            var caps = new JSONObject();
            caps.put("execute", "qmp_capabilities");
            out.write(caps.toString().getBytes(UTF_8));
            out.write('\n');
            n = in.read(buf);
            if (n > 0)
                Log.d(TAG, fmt("QMP caps response: %s", new String(buf, 0, n, UTF_8)));
            out.write(command.toString().getBytes(UTF_8));
            out.write('\n');
            n = in.read(buf);
            if (n <= 0) return null;
            var response = new String(buf, 0, n, UTF_8);
            Log.d(TAG, fmt("QMP response: %s", response));
            return new JSONObject(response);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "QMP command failed", e);
            return null;
        }
    }

    @Override
    public synchronized int runControlCommand(@NonNull String command) {
        if (qmpSocketPath == null) {
            Log.w(TAG, fmt("Cannot run qemu %s: no QMP socket", command));
            return -1;
        }
        try {
            var qmpCommand = mapControlCommand(command);
            if (qmpCommand == null) {
                Log.w(TAG, fmt("Unknown control command: %s", command));
                return -1;
            }
            var response = sendQMP(qmpCommand);
            if (response == null) return -1;
            if (response.has("return")) return 0;
            if (response.has("error")) {
                Log.w(TAG, fmt("QMP error for %s: %s", command, response.toString()));
                return -1;
            }
            return 0;
        } catch (JSONException e) {
            Log.e(TAG, fmt("Failed to build QMP command for %s", command), e);
            return -1;
        }
    }

    @Nullable
    private static JSONObject mapControlCommand(@NonNull String command) throws JSONException {
        var cmd = new JSONObject();
        switch (command) {
            case "stop":
                cmd.put("execute", "quit");
                return cmd;
            case "powerbtn":
                cmd.put("execute", "system_powerdown");
                return cmd;
            case "sleepbtn": {
                cmd.put("execute", "human-monitor-command");
                var cmdArgs = new JSONObject();
                cmdArgs.put("command-line", "system_sleepbutton");
                cmd.put("arguments", cmdArgs);
                return cmd;
            }
            case "resume":
                cmd.put("execute", "cont");
                return cmd;
            case "suspend":
                cmd.put("execute", "stop");
                return cmd;
            default:
                return null;
        }
    }

    @Override
    public boolean hasControlSocket() {
        return qmpSocketPath != null;
    }

    @Override
    public void cleanup() {
        uartStream.close();
        if (qmpSocketPath != null) {
            deleteFile(qmpSocketPath);
            qmpSocketPath = null;
        }
        if (uartSocketPath != null) {
            deleteFile(uartSocketPath);
            uartSocketPath = null;
        }
    }
}
