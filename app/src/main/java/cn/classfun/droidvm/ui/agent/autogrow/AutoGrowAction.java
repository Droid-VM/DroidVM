// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.agent.autogrow;

import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.ui.agent.base.AgentActionSpec;
import cn.classfun.droidvm.ui.agent.base.AgentVM;
import cn.classfun.droidvm.ui.agent.base.BaseAction;

/** Grows the physical last data partition and its supported filesystem into trailing space. */
public final class AutoGrowAction extends BaseAction {
    public static final String TYPE = "autogrow";

    /** Appends an autogrow step targeting the first AgentVM disk (/dev/vda). */
    public AutoGrowAction(@NonNull AgentVM vm) {
        this(vm, vm.addAction(TYPE));
    }

    public AutoGrowAction(@NonNull AgentVM vm, @NonNull AgentActionSpec spec) {
        super(vm, spec);
        if (!spec.hasParam("device")) spec.setParam("device", "/dev/vda");
    }

    public void setDevice(@NonNull String device) {
        spec.setParam("device", device);
    }

    @NonNull
    @Override
    protected String buildActionScript() {
        var device = spec.getParam("device", "/dev/vda");
        var script = String.join("\n",
            "DISK=%s",
            "DISK_NAME=${DISK##*/}",
            "SYS_DISK=/sys/class/block/$DISK_NAME",
            "[ -b \"$DISK\" ] && [ -d \"$SYS_DISK\" ] || fail AUTOGROW_DISK_NOT_FOUND",
            // DOS extended partitions are containers, not data partitions. Ignore the container
            // when selecting the physical last partition, but extend it before a logical child.
            "EXTENDED_DEVICE=$(sfdisk -d \"$DISK\" 2>/dev/null | grep -Ei 'type=(0x)?(5|f|85)(,|$)' | head -n 1 | cut -d' ' -f1)",
            "LAST_PART=",
            "LAST_SYS=",
            "LAST_END=0",
            "for SYS_PART in /sys/class/block/\"$DISK_NAME\"*; do",
            "    [ -f \"$SYS_PART/partition\" ] || continue",
            "    PART_NAME=${SYS_PART##*/}",
            "    PART_DEVICE=/dev/$PART_NAME",
            "    [ \"$PART_DEVICE\" = \"$EXTENDED_DEVICE\" ] && continue",
            "    PART_START=$(cat \"$SYS_PART/start\") || fail AUTOGROW_PROBE_FAILED",
            "    PART_SIZE=$(cat \"$SYS_PART/size\") || fail AUTOGROW_PROBE_FAILED",
            "    PART_END=$((PART_START + PART_SIZE))",
            "    if [ \"$PART_END\" -gt \"$LAST_END\" ]; then",
            "        LAST_END=$PART_END",
            "        LAST_PART=$PART_DEVICE",
            "        LAST_SYS=$SYS_PART",
            "    fi",
            "done",
            "if [ -z \"$LAST_PART\" ]; then",
            "    skip_action NO_PARTITION",
            "    return 0",
            "fi",
            "PART_NUM=$(cat \"$LAST_SYS/partition\") || fail AUTOGROW_PROBE_FAILED",
            "BLKID_LINE=$(blkid \"$LAST_PART\" 2>/dev/null || true)",
            "FS_TYPE=$(printf '%%s\\n' \"$BLKID_LINE\" | sed -n 's/.* TYPE=\"\\([^\"]*\\)\".*/\\1/p')",
            "case \"$FS_TYPE\" in",
            "    ext2|ext3|ext4|btrfs|f2fs) ;;",
            "    '') skip_action UNKNOWN_FILESYSTEM; return 0 ;;",
            "    *) skip_action \"UNSUPPORTED_FILESYSTEM:$FS_TYPE\"; return 0 ;;",
            "esac",
            "DISK_SECTORS=$(cat \"$SYS_DISK/size\") || fail AUTOGROW_PROBE_FAILED",
            "FREE_SECTORS=$((DISK_SECTORS - LAST_END))",
            // Ignore alignment and GPT-backup-header sized gaps. sysfs sizes are 512-byte sectors.
            "if [ \"$FREE_SECTORS\" -lt 2048 ]; then",
            "    skip_action NO_TRAILING_SPACE",
            "    return 0",
            "fi",
            "if awk -v dev=\"$LAST_PART\" '$1 == dev { found=1 } END { exit !found }' /proc/mounts; then",
            "    fail AUTOGROW_PARTITION_IN_USE",
            "fi",
            "OLD_PART_SIZE=$(cat \"$LAST_SYS/size\") || fail AUTOGROW_PROBE_FAILED",
            "marker \"AUTOGROW:PARTITION:$LAST_PART:$FS_TYPE:$FREE_SECTORS\"",
            "if [ -n \"$EXTENDED_DEVICE\" ] && [ \"$PART_NUM\" -ge 5 ]; then",
            "    EXTENDED_NAME=${EXTENDED_DEVICE##*/}",
            "    EXTENDED_NUM=$(cat \"/sys/class/block/$EXTENDED_NAME/partition\") || fail AUTOGROW_PROBE_FAILED",
            "    parted -s -f \"$DISK\" resizepart \"$EXTENDED_NUM\" 100%% || fail PARTITION_GROW_FAILED",
            "fi",
            "parted -s -f \"$DISK\" resizepart \"$PART_NUM\" 100%% || fail PARTITION_GROW_FAILED",
            "partprobe \"$DISK\" || fail PARTITION_REREAD_FAILED",
            "NEW_PART_SIZE=$OLD_PART_SIZE",
            "for wait_count in $(seq 1 20); do",
            "    NEW_PART_SIZE=$(cat \"$LAST_SYS/size\" 2>/dev/null || echo 0)",
            "    [ \"$NEW_PART_SIZE\" -gt \"$OLD_PART_SIZE\" ] && break",
            "    sleep 0.1",
            "done",
            "[ \"$NEW_PART_SIZE\" -gt \"$OLD_PART_SIZE\" ] || fail PARTITION_REREAD_FAILED",
            "case \"$FS_TYPE\" in",
            "    ext2|ext3|ext4)",
            "        e2fsck -pf \"$LAST_PART\"",
            "        FSCK_RC=$?",
            "        [ \"$FSCK_RC\" -le 1 ] || fail FILESYSTEM_CHECK_FAILED",
            "        resize2fs \"$LAST_PART\" || fail FILESYSTEM_GROW_FAILED",
            "        ;;",
            "    btrfs)",
            "        modprobe btrfs >/dev/null 2>&1 || true",
            "        mount -t btrfs -o rw \"$LAST_PART\" /mnt-autogrow || fail FILESYSTEM_MOUNT_FAILED",
            "        btrfs filesystem resize max /mnt-autogrow || fail FILESYSTEM_GROW_FAILED",
            "        sync",
            "        umount /mnt-autogrow || fail FILESYSTEM_UNMOUNT_FAILED",
            "        ;;",
            "    f2fs)",
            "        modprobe f2fs >/dev/null 2>&1 || true",
            "        fsck.f2fs -f \"$LAST_PART\" || fail FILESYSTEM_CHECK_FAILED",
            "        resize.f2fs \"$LAST_PART\" || fail FILESYSTEM_GROW_FAILED",
            "        ;;",
            "esac",
            "sync",
            "marker \"AUTOGROW:GROWN:$LAST_PART:$FS_TYPE:$OLD_PART_SIZE:$NEW_PART_SIZE\"",
            ""
        );
        return fmt(script, escapedString(device));
    }
}
