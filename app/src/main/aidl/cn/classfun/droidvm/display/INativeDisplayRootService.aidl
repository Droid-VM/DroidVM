package cn.classfun.droidvm.display;

/**
 * AIDL interface for the libsu RootService (uid=0) that brokers native display for the UI:
 * it looks up the per-VM display binder that crosvm registers via
 * --android-display-service <serviceName> (an untrusted_app cannot do this lookup itself).
 *
 * Input is NOT handled here: crosvm's --input sockets are owned by the daemon (the only process
 * that binds them before crosvm starts), and the UI forwards evdev to the daemon over its IPC.
 */
interface INativeDisplayRootService {
    /**
     * Calls ServiceManager.waitForService(serviceName) as root and returns the
     * ICrosvmAndroidDisplayService binder, or null if not found. serviceName is the per-VM key.
     */
    IBinder waitForDisplayBinder(String serviceName);
}
