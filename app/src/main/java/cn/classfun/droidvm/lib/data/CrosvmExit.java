package cn.classfun.droidvm.lib.data;

/**
 * crosvm process exit codes (CommandStatus). RESET drives the automatic relaunch
 * in VMInstance; the rest are surfaced to the user as distinct failure reasons.
 */
public enum CrosvmExit {
    RESET(32),        // VmReset: guest requested reboot/reset
    CRASH(33),        // VmCrash
    GUEST_PANIC(34),  // kernel panic in guest
    WATCHDOG(36);     // vcpu stall / watchdog reset

    private final int code;

    CrosvmExit(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /** Maps a raw crosvm exit code to its constant, or null when unrecognized. */
    public static CrosvmExit fromCode(int code) {
        for (CrosvmExit exit : values()) {
            if (exit.code == code) {
                return exit;
            }
        }
        return null;
    }
}
