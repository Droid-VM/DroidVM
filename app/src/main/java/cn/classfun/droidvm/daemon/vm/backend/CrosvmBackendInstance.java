package cn.classfun.droidvm.daemon.vm.backend;

import static android.net.LocalSocketAddress.Namespace.FILESYSTEM;
import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.Constants.PATH_EDK2_FIRMWARE;
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
import android.system.Os;
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
import cn.classfun.droidvm.daemon.vm.SerialPipe;
import cn.classfun.droidvm.daemon.vm.VMBackendInstance;
import cn.classfun.droidvm.daemon.vm.VMStartResult;
import cn.classfun.droidvm.lib.natives.NativeProcess;
import cn.classfun.droidvm.lib.natives.UnixHelper;
import cn.classfun.droidvm.lib.network.FDSocket;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.vm.DisplayBackend;
import cn.classfun.droidvm.lib.store.vm.GpuApi;
import cn.classfun.droidvm.lib.store.vm.GpuBackend;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.SharedDirCache;
import cn.classfun.droidvm.lib.store.vm.SharedDirType;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

@SuppressWarnings("FieldCanBeLocal")
public final class CrosvmBackendInstance extends VMBackendInstance {
    private static final String TAG = "CrosvmBackendInstance";
    private static final String RUN_PATH = pathJoin(DATA_DIR, "run");
    private final VMConfig config;
    private SerialPipe uart = null;
    private String controlSocketPath = null;
    /** Server fds for the per-VM native-display input sockets, kept while crosvm is running. */
    private int[] inputServerFds = null;
    /** Paths of the input sockets we listened on, so cleanup() can unlink them. */
    private String[] inputSocketPaths = null;
    /**
     * The crosvm-side connection accepted on each input channel. crosvm connects to OUR socket at
     * startup (we are the only listener), so writing UI-forwarded evdev here is what actually
     * reaches the guest. Indexed by NativeDisplay channel constants.
     */
    private FDSocket[] inputPeers = null;
    /** Per-channel write lock; also guards swapping {@link #inputPeers} on reconnect. */
    private Object[] inputWriteLocks = null;
    /**
     * UI-facing input sockets: the daemon listens here and the UI connects directly (bypassing the
     * JSON-RPC round-trip on the touch hot path). Bytes received from the UI peer are forwarded to
     * the matching {@link #inputPeers} entry (crosvm). Indexed by NativeDisplay channel constants.
     */
    private int[] uiInputServerFds = null;
    private String[] uiInputSocketPaths = null;
    private FDSocket[] uiInputPeers = null;
    private Object[] uiInputWriteLocks = null;
    private Thread[] uiInputForwardThreads = null;
    private volatile boolean uiInputClosed = false;
    private volatile boolean inputClosed = false;
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
        // the VM is up — so the daemon is the only process that can both bind the socket before
        // crosvm starts and stay alive to feed it. We pre-bind + accept here; the UI forwards evdev
        // to us via the vm_input IPC command (see InputHandler). Server fds released on cleanup().
        if (isNativeDisplayEnabled()) {
            if (!ensureInputSocketsListening()) {
                Log.e(TAG, "Native display input sockets unavailable; crosvm will likely fail");
            }
        }
        var args = buildCommand();
        Log.i(TAG, fmt("Executing: %s", String.join(" ", args)));
        try {
            var builder = new NativeProcess.Builder(args.toArray(new String[0]));
            prepareProcess(builder);
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
            releaseInputSockets();
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
        if (item.optBoolean("prepare_lend_mthp", true))
            args.add("--prepare-lend-mthp");
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
        if (item.optBoolean("use_uefi", true)) {
            args.add(PATH_EDK2_FIRMWARE);
        } else {
            var kernel = item.optString("kernel", "");
            if (!kernel.isEmpty())
                args.add(kernel);
        }
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
        // Native display: crosvm registers an ICrosvmAndroidDisplayService binder under a per-VM
        // name and renders the gfxstream/virtio-gpu output straight into the Android Surface the UI
        // hands it. Requires the GPU (virtio-gpu) path above. Touch/keyboard come back over the
        // per-VM unix sockets the root service listens on; their paths must match NativeDisplay.
        if (useGpu && useDisplay && backend == VIRTIO_GPU
            && item.optBoolean("native_display_enabled", false)) {
            buildNativeDisplayCommand(args);
        }
    }

    private void buildNativeDisplayCommand(@NonNull List<String> args) {
        var item = config.item;
        var serviceName = NativeDisplay.serviceName(config);
        var width = item.optLong("display_width", 1280);
        var height = item.optLong("display_height", 720);
        args.add("--android-display-service");
        args.add(serviceName);
        // multi-touch ABS range must equal the guest resolution so view coords scale straight onto
        // ABS_X/ABS_Y (see EvdevEncoder / TouchScaleCalculator).
        args.add("--input");
        args.add(fmt(
            "multi-touch[path=%s,width=%d,height=%d]",
            NativeDisplay.inputSocketPath(serviceName, NativeDisplay.MULTITOUCH), width, height
        ));
        args.add("--input");
        args.add(fmt(
            "keyboard[path=%s]",
            NativeDisplay.inputSocketPath(serviceName, NativeDisplay.KEYBOARD)
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

    /**
     * Pre-creates the per-VM native-display input sockets as listening unix sockets. crosvm
     * connects to these paths at startup, so a listener must exist before {@link #start()} execs
     * the crosvm process. nativeUnixListen unlinks any stale inode and re-binds, so a leftover
     * socket file from a crashed run is replaced rather than blocking us. If the UI's RootService
     * already bound the path, bind fails with EADDRINUSE and that channel keeps the UI's listener;
     * we just skip it. Returns true iff every channel ended up with a live listener (ours or the
     * UI's). Server fds we open are tracked for release in {@link #releaseInputSockets()}.
     */
    private boolean ensureInputSocketsListening() {
        if (!UnixHelper.isLoaded()) {
            Log.w(TAG, "UnixHelper not loaded; cannot pre-bind native-display input sockets");
            return false;
        }
        var serviceName = NativeDisplay.serviceName(config);
        var paths = new String[NativeDisplay.CHANNEL_COUNT];
        var fds = new int[NativeDisplay.CHANNEL_COUNT];
        inputClosed = false;
        inputPeers = new FDSocket[NativeDisplay.CHANNEL_COUNT];
        inputWriteLocks = new Object[NativeDisplay.CHANNEL_COUNT];
        boolean allListening = true;
        for (int ch = 0; ch < NativeDisplay.CHANNEL_COUNT; ch++) {
            inputWriteLocks[ch] = new Object();
            var path = NativeDisplay.inputSocketPath(serviceName, ch);
            paths[ch] = path;
            var fd = UnixHelper.nativeUnixListen(path);
            if (fd < 0) {
                Log.w(TAG, fmt("Failed to pre-listen on input socket: %s", path));
                allListening = false;
                fds[ch] = -1;
            } else {
                Log.i(TAG, fmt("Pre-listening on input socket: %s (fd=%d)", path, fd));
                fds[ch] = fd;
                // Accept crosvm's connection in the background. crosvm is the client and connects
                // at its own startup, so a peer may not arrive until after start() execs it.
                startInputAcceptThread(ch, fd);
            }
        }
        inputSocketPaths = paths;
        inputServerFds = fds;
        // Best-effort: open the UI-facing sockets too. Failure here is non-fatal — the UI will
        // fall back to the vm_input IPC path — so we never let it mask the crosvm sockets above.
        try {
            ensureUiInputSocketsListening(serviceName);
        } catch (Exception e) {
            Log.w(TAG, "UI input sockets unavailable; UI will fall back to vm_input IPC", e);
        }
        return allListening;
    }

    /**
     * Opens one UI-facing unix socket per channel ({@link NativeDisplay#uiInputSocketPath}) and
     * spawns a forwarder thread that accepts the UI's connection, reads evdev bytes from it and
     * writes them to the matching {@link #inputPeers} (crosvm). chmods the inode world-readable so
     * the app-uid UI can connect to a root-owned listener. Released in {@link #releaseInputSockets()}.
     */
    private void ensureUiInputSocketsListening(@NonNull String vmKey) {
        if (!UnixHelper.isLoaded()) {
            Log.w(TAG, "UnixHelper not loaded; cannot open UI input sockets");
            return;
        }
        uiInputClosed = false;
        uiInputPeers = new FDSocket[NativeDisplay.CHANNEL_COUNT];
        uiInputWriteLocks = new Object[NativeDisplay.CHANNEL_COUNT];
        uiInputForwardThreads = new Thread[NativeDisplay.CHANNEL_COUNT];
        var paths = new String[NativeDisplay.CHANNEL_COUNT];
        var fds = new int[NativeDisplay.CHANNEL_COUNT];
        for (int ch = 0; ch < NativeDisplay.CHANNEL_COUNT; ch++) {
            uiInputWriteLocks[ch] = new Object();
            var path = NativeDisplay.uiInputSocketPath(vmKey, ch);
            paths[ch] = path;
            var fd = UnixHelper.nativeUnixListen(path);
            if (fd < 0) {
                Log.w(TAG, fmt("Failed to listen on UI input socket: %s", path));
                fds[ch] = -1;
                continue;
            }
            // The UI runs as the app uid (not root); the inode created by our root listener is
            // 0755 root:root by default, so the UI can't connect. Open it up — the data is just
            // evdev input events, no secrets, and the path is private to our package dir.
            // The UI runs as the app uid (not root). The inode created by our root listener is owned by
            // root; SELinux and DAC together block the app from connecting. Hand the inode to the
            // app uid (chown via stat(DATA_DIR).st_uid — the app's home dir owner is the app) and
            // restrict to owner-only rw. chmod 0660 is enough because the app is now the owner.
            int appUid = -1;
            try {
                appUid = cn.classfun.droidvm.daemon.server.Server.getDroidVMUid();
            } catch (Exception e) {
                Log.w(TAG, "getDroidVMUid failed: " + e.getMessage());
            }
            try {
                if (appUid >= 0) {
                    Os.chown(path, appUid, appUid);
                    Os.chmod(path, 0660);
                } else {
                    Os.chmod(path, 0666);
                }
            } catch (Exception e) {
                Log.w(TAG, fmt("chown/chmod(%s) failed: %s", path, e.getMessage()));
            }
            fds[ch] = fd;
            Log.i(TAG, fmt("Listening on UI input socket: %s (fd=%d)", path, fd));
            startUiInputForwardThread(ch, fd);
        }
        uiInputSocketPaths = paths;
        uiInputServerFds = fds;
    }

    /**
     * Accepts the UI's connection on one UI input channel, then pumps evdev bytes from it into
     * the matching crosvm peer. Runs until {@link #uiInputClosed} flips or the server fd is closed
     * by {@link #releaseInputSockets()}.
     */
    private void startUiInputForwardThread(int channel, int serverFd) {
        var t = new Thread(() -> {
            while (!uiInputClosed) {
                int clientFd = UnixHelper.nativeUnixAccept(serverFd);
                if (clientFd < 0) {
                    if (uiInputClosed) break;
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                var peer = new FDSocket(clientFd);
                synchronized (uiInputWriteLocks[channel]) {
                    var old = uiInputPeers[channel];
                    uiInputPeers[channel] = peer;
                    if (old != null) {
                        try { old.close(); } catch (Exception ignored) {}
                    }
                }
                forwardUiInput(channel, peer);
                synchronized (uiInputWriteLocks[channel]) {
                    if (uiInputPeers[channel] == peer) uiInputPeers[channel] = null;
                }
                peer.close();
            }
        }, fmt("UiInputForward-%d", channel));
        t.setDaemon(true);
        t.start();
        uiInputForwardThreads[channel] = t;
    }

    /**
     * Reads from the UI peer on [channel] and writes to the crosvm peer on the same channel, one
     * buffer at a time. crosvm reads fixed 8-byte evdev records, so we forward raw bytes without
     * re-encoding. Returns when the UI disconnects (EOF) or the VM is shutting down.
     */
    private void forwardUiInput(int channel, @NonNull FDSocket uiPeer) {
        Log.i(TAG, fmt("UI input connected: channel %d", channel));
        var in = uiPeer.getInputStream();
        var buf = new byte[4096];
        try {
            while (!uiInputClosed && !inputClosed) {
                int n = in.read(buf);
                if (n <= 0) break;
                forwardToCrosvm(channel, buf, n);
            }
        } catch (IOException e) {
            if (!uiInputClosed && !inputClosed)
                Log.w(TAG, fmt("UI input channel %d read failed: %s", channel, e.getMessage()));
        }
        Log.i(TAG, fmt("UI input disconnected: channel %d", channel));
    }

    /** Writes [len] bytes to the crosvm peer for [channel]; no-op if crosvm isn't connected yet. */
    private void forwardToCrosvm(int channel, @NonNull byte[] data, int len) {
        if (inputWriteLocks == null || inputPeers == null) return;
        synchronized (inputWriteLocks[channel]) {
            var peer = inputPeers[channel];
            if (peer == null || !peer.isOpen()) return; // crosvm not connected yet; drop silently
            try {
                var os = peer.getOutputStream();
                os.write(data, 0, len);
                os.flush();
            } catch (IOException e) {
                Log.w(TAG, fmt("forward channel %d to crosvm failed: %s", channel, e.getMessage()));
                inputPeers[channel] = null;
                try { peer.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Accepts crosvm's connection on one input channel and keeps the live peer in
     * {@link #inputPeers}. Loops so a crosvm restart (new connection on the same socket) replaces
     * the dead peer; ends when {@link #releaseInputSockets()} closes the server fd.
     */
    private void startInputAcceptThread(int channel, int serverFd) {
        var t = new Thread(() -> {
            while (!inputClosed) {
                int peerFd = UnixHelper.nativeUnixAccept(serverFd);
                if (peerFd < 0) {
                    if (inputClosed) break;
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                var peer = new FDSocket(peerFd);
                synchronized (inputWriteLocks[channel]) {
                    var old = inputPeers[channel];
                    inputPeers[channel] = peer;
                    if (old != null) old.close();
                }
                Log.i(TAG, fmt("crosvm input connected: channel %d", channel));
            }
        }, fmt("CrosvmInputAccept-%d", channel));
        t.setDaemon(true);
        t.start();
    }

    /**
     * Writes pre-encoded evdev bytes (8-byte records) to the crosvm connection for [channel].
     * Called from the daemon IPC thread on behalf of the UI. Returns false if no crosvm peer is
     * connected yet or the write fails.
     */
    public boolean writeNativeInput(int channel, @NonNull byte[] data) {
        if (channel < 0 || channel >= NativeDisplay.CHANNEL_COUNT
            || inputPeers == null || inputWriteLocks == null || data.length == 0) return false;
        synchronized (inputWriteLocks[channel]) {
            var peer = inputPeers[channel];
            if (peer == null || !peer.isOpen()) return false;
            try {
                var os = peer.getOutputStream();
                os.write(data);
                os.flush();
                return true;
            } catch (IOException e) {
                Log.w(TAG, fmt("input write channel %d failed: %s", channel, e.getMessage()));
                inputPeers[channel] = null;
                peer.close();
                return false;
            }
        }
    }

    /**
     * Closes the input server fds we opened and unlinks only the inodes we own. Channels that
     * fell through to the UI's listener (fd == -1) are left untouched so we don't yank a socket
     * the UI still holds.
     */
    private void releaseInputSockets() {
        inputClosed = true; // stop accept loops; closing the server fd below unblocks nativeUnixAccept
        if (inputPeers != null) {
            for (int ch = 0; ch < inputPeers.length; ch++) {
                if (inputWriteLocks != null && inputWriteLocks[ch] != null) {
                    synchronized (inputWriteLocks[ch]) {
                        if (inputPeers[ch] != null) {
                            inputPeers[ch].close();
                            inputPeers[ch] = null;
                        }
                    }
                }
            }
            inputPeers = null;
        }
        inputWriteLocks = null;
        if (inputServerFds == null) {
            inputSocketPaths = null;
            return;
        }
        for (int ch = 0; ch < inputServerFds.length; ch++) {
            int fd = inputServerFds[ch];
            if (fd < 0) continue;
            UnixHelper.nativeCloseFd(fd);
            if (inputSocketPaths != null && inputSocketPaths[ch] != null)
                deleteFile(inputSocketPaths[ch]);
        }
        inputServerFds = null;
        inputSocketPaths = null;
        releaseUiInputSockets();
    }

    /** Releases the UI-facing input sockets and unlinks their inodes. */
    private void releaseUiInputSockets() {
        uiInputClosed = true;
        if (uiInputPeers != null) {
            for (int ch = 0; ch < uiInputPeers.length; ch++) {
                if (uiInputWriteLocks != null && uiInputWriteLocks[ch] != null) {
                    synchronized (uiInputWriteLocks[ch]) {
                        if (uiInputPeers[ch] != null) {
                            uiInputPeers[ch].close();
                            uiInputPeers[ch] = null;
                        }
                    }
                }
            }
            uiInputPeers = null;
        }
        uiInputWriteLocks = null;
        if (uiInputServerFds == null) {
            uiInputSocketPaths = null;
            return;
        }
        for (int ch = 0; ch < uiInputServerFds.length; ch++) {
            int fd = uiInputServerFds[ch];
            if (fd < 0) continue;
            UnixHelper.nativeCloseFd(fd);
            if (uiInputSocketPaths != null && uiInputSocketPaths[ch] != null)
                deleteFile(uiInputSocketPaths[ch]);
        }
        uiInputServerFds = null;
        uiInputSocketPaths = null;
        uiInputForwardThreads = null;
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
        releaseInputSockets();
    }
}
