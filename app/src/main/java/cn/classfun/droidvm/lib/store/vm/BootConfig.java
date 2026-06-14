package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.enums.Enums;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * Wrapper over a VM config's "boot" object — the single source of truth
 * for how the guest is brought up:
 *
 * <pre>
 * boot: {
 *   protocol: "uefi" | "linux",
 *   uefi:  { firmware },                       // empty = builtin EDK2
 *   linux: {
 *     source: "manual" | "image",
 *     kernel, initrd, cmdline,                 // manual-source fields
 *     image: {
 *       disk,                                  // index into "disks"
 *       entry: { id, title, version } | null,  // null = bootloader default
 *       cmdline,                               // override, empty = from image
 *       vdafix,                                // default true
 *       wait                                   // boot menu seconds, 0 = off
 *     }
 *   }
 * }
 * </pre>
 *
 * Both branches are kept in the store at once; {@code protocol} (and
 * {@code source} below it) only select which one takes effect, so toggling
 * in the UI never loses the other branch's values. Legacy flat keys
 * ({@code use_uefi}/{@code kernel}/{@code initrd}/{@code cmdline}/
 * {@code bios}) are folded into this object once, on config load — see
 * {@link VMConfig#migrateBoot}.
 */
public final class BootConfig {
    public enum Protocol implements StringEnum {
        UEFI(R.string.edit_vm_boot_protocol_uefi),
        LINUX(R.string.edit_vm_boot_protocol_linux);

        private final @StringRes int stringId;

        Protocol(@StringRes int stringId) {
            this.stringId = stringId;
        }

        @Override
        @StringRes
        public int getStringId() {
            return stringId;
        }
    }

    public enum LinuxSource implements StringEnum {
        IMAGE(R.string.edit_vm_kernel_source_image),
        MANUAL(R.string.edit_vm_kernel_source_manual);

        private final @StringRes int stringId;

        LinuxSource(@StringRes int stringId) {
            this.stringId = stringId;
        }

        @Override
        @StringRes
        public int getStringId() {
            return stringId;
        }
    }

    public static final long DEFAULT_BOOT_WAIT = 1;

    /** Manual-source cmdline seed: first virtio disk, second partition. */
    public static final String DEFAULT_MANUAL_CMDLINE = "root=/dev/vda2";

    /**
     * Reserved {@code vm_start} boot_entry / boot-menu selection key for
     * "boot this disk with DroidVM's built-in kernel" — not a disk image
     * entry, so it is matched as a sentinel (see {@code BootPlan.resolve})
     * and can never collide with an lbx entry id (which never start '@').
     */
    public static final String BUILTIN_ENTRY_KEY = "@droidvm:builtin-kernel";

    public final DataItem item;

    private BootConfig(@NonNull DataItem item) {
        this.item = item;
    }

    /** The "boot" object of {@code config}, created empty when missing. */
    @NonNull
    public static BootConfig of(@NonNull VMConfig config) {
        return new BootConfig(child(config.item, "boot"));
    }

    /** Child object at {@code key}, created (and stored) when missing. */
    @NonNull
    private static DataItem child(@NonNull DataItem parent, @NonNull String key) {
        var c = parent.opt(key, null);
        if (c == null || !c.is(DataItem.Type.OBJECT)) {
            // set() stores a copy; re-read so callers mutate the stored item
            parent.set(key, DataItem.newObject());
            c = parent.get(key);
        }
        return c;
    }

    @NonNull
    public Protocol getProtocol() {
        return Enums.optEnum(item, "protocol", Protocol.UEFI);
    }

    public void setProtocol(@NonNull Protocol protocol) {
        item.set("protocol", protocol);
    }

    // --- uefi branch ---

    @NonNull
    private DataItem uefi() {
        return child(item, "uefi");
    }

    /** Custom firmware path; empty = builtin EDK2 (QEMU backend only). */
    @NonNull
    public String getUefiFirmware() {
        return uefi().optString("firmware", "");
    }

    public void setUefiFirmware(@NonNull String firmware) {
        uefi().set("firmware", firmware);
    }

    // --- linux branch ---

    @NonNull
    private DataItem linux() {
        return child(item, "linux");
    }

    @NonNull
    public LinuxSource getLinuxSource() {
        return Enums.optEnum(linux(), "source", LinuxSource.MANUAL);
    }

    public void setLinuxSource(@NonNull LinuxSource source) {
        linux().set("source", source);
    }

    @NonNull
    public String getKernel() {
        return linux().optString("kernel", "");
    }

    public void setKernel(@NonNull String kernel) {
        linux().set("kernel", kernel);
    }

    @NonNull
    public String getInitrd() {
        return linux().optString("initrd", "");
    }

    public void setInitrd(@NonNull String initrd) {
        linux().set("initrd", initrd);
    }

    /** Manual-source cmdline (image mode uses {@link #getImageCmdline}). */
    @NonNull
    public String getCmdline() {
        return linux().optString("cmdline", "");
    }

    public void setCmdline(@NonNull String cmdline) {
        linux().set("cmdline", cmdline);
    }

    // --- linux.image branch ---

    @NonNull
    private DataItem image() {
        return child(linux(), "image");
    }

    /** Index into the VM's "disks" array of the image to scan. */
    public int getImageDisk() {
        return (int) image().optLong("disk", 0);
    }

    public void setImageDisk(int disk) {
        image().set("disk", (long) disk);
    }

    /** Pinned boot entry; null = follow the bootloader default. */
    @Nullable
    public ImageEntry getImageEntry() {
        var e = image().opt("entry", null);
        if (e == null || !e.is(DataItem.Type.OBJECT)) return null;
        return new ImageEntry(
            e.optString("id", null),
            e.optString("title", null),
            e.optString("version", null)
        );
    }

    public void setImageEntry(@Nullable ImageEntry entry) {
        if (entry == null) {
            image().remove("entry");
            return;
        }
        var e = DataItem.newObject();
        e.set("id", entry.id);
        e.set("title", entry.title);
        e.set("version", entry.version);
        image().set("entry", e);
    }

    /** Image mode cmdline override; empty = use the image's cmdline. */
    @NonNull
    public String getImageCmdline() {
        return image().optString("cmdline", "");
    }

    public void setImageCmdline(@NonNull String cmdline) {
        image().set("cmdline", cmdline);
    }

    /** Rewrite root=/resume= device names to PARTUUID= (lbx vdafix). */
    public boolean isVdafix() {
        return image().optBoolean("vdafix", true);
    }

    public void setVdafix(boolean vdafix) {
        image().set("vdafix", vdafix);
    }

    /** Boot menu countdown in seconds on manual GUI start; 0 = no menu. */
    public long getBootWait() {
        return image().optLong("wait", DEFAULT_BOOT_WAIT);
    }

    public void setBootWait(long seconds) {
        image().set("wait", Math.max(seconds, 0));
    }

    /**
     * Identity of a pinned boot entry, matched against a fresh scan in
     * order: exact id, exact title, then bootloader default ({@code lbx}
     * ids embed the kernel version for BLS/extlinux, so updates in the
     * guest are expected to miss here and fall back).
     */
    public static final class ImageEntry {
        @Nullable
        public final String id;
        @Nullable
        public final String title;
        @Nullable
        public final String version;

        public ImageEntry(
            @Nullable String id,
            @Nullable String title,
            @Nullable String version
        ) {
            this.id = id;
            this.title = title;
            this.version = version;
        }
    }
}
