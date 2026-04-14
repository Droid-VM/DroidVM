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
    public static final String GITHUB_WIKI_URL = "https://github.com/Droid-VM/DroidVM/wiki";
    public static final String GITHUB_ISSUE_URL = "https://github.com/Droid-VM/DroidVM/issues";
    public static final String[] BINARIES_PREBUILT = {"7za"};
    public static final String[] BINARIES_BUILT = {"droidvm", "daemon"};
    public static final String[] LIBRARIES_BUILT = {"libsimpledump.so", "libunixhelper.so"};

    private Constants() {
    }
}
