package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import androidx.annotation.NonNull;

/**
 * Shared naming for the native (crosvm android-display) backend. The daemon (launching crosvm and
 * hosting the native-display binder) and the UI (looking up the display binder / sending input)
 * both derive the per-VM service name and socket paths from here, so they agree and different VMs
 * never collide.
 *
 * The service name doubles as the vmKey: it identifies both the servicemanager entry crosvm
 * registers (--android-display-service) and the VM's input-socket set.
 */
public final class NativeDisplay {
    /** Input channels (MVP: multi-touch + keyboard). */
    public static final int MULTITOUCH = 0;
    public static final int KEYBOARD = 1;
    public static final int CHANNEL_COUNT = 2;

    /**
     * Broadcast the daemon (running as root) sends to hand its INativeDisplayRootService binder to
     * the UI. A live IBinder can't cross the daemon's TCP/JSON-RPC channel, so it travels through
     * system_server as a broadcast extra instead (the same path libsu/Shizuku use), sidestepping the
     * servicemanager-find restriction an untrusted_app hits. The binder + nonce are nested in a
     * Bundle under {@link #EXTRA_BUNDLE} so AMS doesn't strip the binder from the top-level extras.
     */
    public static final String BINDER_BROADCAST_ACTION =
        "cn.classfun.droidvm.action.NATIVE_DISPLAY_BINDER";
    public static final String EXTRA_BUNDLE = "extra.bundle";
    public static final String EXTRA_BINDER = "binder";
    /** Per-attach random token; the UI only accepts a broadcast carrying the nonce it requested. */
    public static final String EXTRA_NONCE = "nonce";

    private static final String[] KINDS = {"multitouch", "keyboard"};
    private static final String RUN_PATH = pathJoin(DATA_DIR, "run");

    private NativeDisplay() {
    }

    /** Per-VM crosvm display service name; also used as the vmKey for input sockets. */
    @NonNull
    public static String serviceName(@NonNull VMConfig config) {
        return serviceNameFromId(config.getId().toString());
    }

    /** Same as {@link #serviceName(VMConfig)} but from a raw VM id (e.g. an Intent extra). */
    @NonNull
    public static String serviceNameFromId(@NonNull String vmId) {
        return "droidvm_disp_" + sanitize(vmId);
    }

    /** The socket path crosvm connects to for [vmKey]'s [channel]. Must match across all callers. */
    @NonNull
    public static String inputSocketPath(@NonNull String vmKey, int channel) {
        return pathJoin(RUN_PATH, fmt("%s_input_%s.sock", sanitize(vmKey), KINDS[channel]));
    }

    /** Keep socket/service names to a filesystem- and binder-safe charset. */
    @NonNull
    public static String sanitize(@NonNull String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append((Character.isLetterOrDigit(c) || c == '_' || c == '-') ? c : '_');
        }
        return sb.toString();
    }
}
