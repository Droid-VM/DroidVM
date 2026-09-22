package cn.classfun.droidvm.display;

import cn.classfun.droidvm.display.IPhysicalKeyEcho;

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
     *
     * [screenId] is the screen the console sending the bytes is showing. The absolute channels
     * (multi-touch, tablet) have one device per screen, because their coordinates only mean
     * anything under one output's geometry, so the screen is what picks the device; the keyboard
     * and the relative pointer are VM-wide and ignore it. A screen whose absolute input is
     * switched off has no such device, and this returns false rather than sending the events to
     * some other screen.
     */
    boolean writeInput(String vmId, String screenId, int channel, in byte[] data);

    /**
     * Grabs the host's physical keyboards for [vmId]'s [screenId] console, or hands them back.
     * A grabbed keyboard is the only way the keys Android keeps for itself -- Home, the task
     * switcher, Alt+Tab, the Meta shortcuts -- can reach a guest, and while it is held that
     * keyboard types nowhere else, the IME included. So a console asks for it only while it is in
     * front with a typing surface that wants no IME, and gives it back on the way out.
     *
     * [token] is the caller's own binder: the daemon releases the grab if it dies, so a console
     * that crashed cannot leave the user with a keyboard that types into nothing. Returns how
     * many keyboards are held (0 when the feature is off, the VM is not running, or none is
     * attached -- the console stays armed for one that appears later).
     */
    int setKeyboardGrab(String vmId, String screenId, boolean grab, IBinder token);

    /**
     * Registers (or clears, with null) the console's copy of the grabbed keyboard's keys and the
     * guest's keyboard lamps, for [vmId]'s [screenId] console. Only the console that holds the
     * grab is heard, and only while it has something to show -- the laptop-keyboard mode lights
     * the pressed key on the drawn keyboard, and nothing else needs the traffic.
     */
    void setKeyEcho(String vmId, String screenId, IPhysicalKeyEcho echo);
}
