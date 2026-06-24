package cn.classfun.droidvm.display;

/**
 * AIDL interface for the native-display broker. It is implemented and hosted by the daemon (which
 * already runs as root) and handed to the UI via a broadcast (see
 * NativeDisplay.BINDER_BROADCAST_ACTION), because a live IBinder cannot cross the daemon's
 * TCP/JSON-RPC channel. No separate root process is involved: an untrusted_app can neither look up
 * crosvm's display service nor connectto the daemon's su-domain input sockets itself, so it calls
 * these methods on the daemon over binder instead.
 */
interface INativeDisplayRootService {
    /**
     * Calls ServiceManager.waitForService(serviceName) inside the daemon (root) and returns the
     * ICrosvmAndroidDisplayService binder, or null if not found. serviceName is the per-VM key.
     */
    IBinder waitForDisplayBinder(String serviceName);

    /**
     * Forwards pre-encoded evdev [data] (8-byte records) for [channel] straight to the crosvm input
     * socket the daemon owns, looking up the running VM by [vmId]. Runs on a daemon binder thread, so
     * the bytes reach crosvm with no extra socket hop. Bytes (not an fd) cross the binder,
     * sidestepping both the SELinux 'connectto' denial (app->su socket) and the 'fd use' denial (app
     * receiving an su-owned fd). Returns true if written, false on any failure so the caller can fall
     * back to the vm_input IPC path.
     */
    boolean writeInput(String vmId, int channel, in byte[] data);
}
