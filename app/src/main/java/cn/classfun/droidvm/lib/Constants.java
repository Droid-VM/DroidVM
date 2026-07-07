package cn.classfun.droidvm.lib;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.annotation.SuppressLint;

import cn.classfun.droidvm.BuildConfig;

@SuppressLint("SdCardPath")
public final class Constants {
    public static final String APEX_VIRT_PATH = "/apex/com.android.virt";
    public static final String DATA_DIR = fmt("/data/data/%s", BuildConfig.APPLICATION_ID);
    public static final String PATH_MICRODROID_KERNEL = pathJoin(APEX_VIRT_PATH, "/etc/fs/microdroid_kernel");
    public static final String PATH_MICRODROID_INITRD = pathJoin(APEX_VIRT_PATH, "/etc/microdroid_initrd_normal.img");
    public static final String PATH_MICRODROID_INITRD_DEBUG = pathJoin(APEX_VIRT_PATH, "/etc/microdroid_initrd_debuggable.img");
    public static final String PATH_BUILTIN_KERNEL = pathJoin(DATA_DIR, "/usr/share/droidvm/vmlinuz");
    public static final String PATH_BUILTIN_INITRD = pathJoin(DATA_DIR, "/usr/share/droidvm/initramfs.img");
    public static final String PATH_EDK2_FIRMWARE = pathJoin(DATA_DIR, "/usr/share/droidvm/edk2-gunyah.fd");
    public static final String PATH_EDK2_QEMU_FIRMWARE = pathJoin(DATA_DIR, "/usr/share/droidvm/edk2-qemu.fd");
    public static final String GITHUB_ISSUE_URL = "https://github.com/Droid-VM/DroidVM/issues";
    // droidvm + daemon are CMake-built and shipped as APK assets. The
    // third-party daemons (gvswitch/pbridge/bridgedhcp/netbox) and lbx now
    // arrive via the prebuilt tar.xz, extracted to DATA/bin/<name>.
    public static final String[] BINARIES_BUILT = {"droidvm", "daemon"};
    /**
     * IFA_RT_PRIORITY magic that pbridge tags its ND/ARP-offload proxy
     * addresses with (its --offload-workaround-magic). Host-IP discovery
     * excludes addresses carrying this metric: they are guest addresses
     * pbridge parks on the uplink, not the phone's own addresses.
     */
    public static final long PBRIDGE_OFFLOAD_MAGIC = 4243672773L;

    private Constants() {
    }
}
