// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.agent.password;

import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.ui.agent.base.AgentActionSpec;
import cn.classfun.droidvm.ui.agent.base.AgentVM;
import cn.classfun.droidvm.ui.agent.base.BaseAction;

public final class PasswordAction extends BaseAction {
    public static final String TYPE = "chpasswd";

    /** Appends a chpasswd step to the VM's ordered action queue. */
    public PasswordAction(@NonNull AgentVM vm) {
        this(vm, vm.addAction(TYPE));
    }

    public PasswordAction(@NonNull AgentVM vm, @NonNull AgentActionSpec spec) {
        super(vm, spec);
        if (!spec.hasParam("password")) spec.setParam("password", "");
        if (!spec.hasParam("normal_users")) spec.setParam("normal_users", "false");
    }

    public void setPassword(@NonNull String password) {
        spec.setParam("password", password);
    }

    public void setChangeNormalUsers(boolean change) {
        spec.setParam("normal_users", String.valueOf(change));
    }

    @NonNull
    @Override
    protected String buildActionScript() {
        var password = spec.getParam("password", "");
        var changeNormalUsers = spec.getParam("normal_users", "false");
        var script = String.join("\n",
            "PASSWORD=%s",
            "CHANGE_NORMAL_USERS=%s",
            "FILESYSTEMS=$(blkid)",
            "echo \"$FILESYSTEMS\" | grep -q 'TYPE=\"btrfs\"' && modprobe btrfs >/dev/null 2>&1 || true",
            "echo \"$FILESYSTEMS\" | grep -q 'TYPE=\"xfs\"' && modprobe xfs >/dev/null 2>&1 || true",
            "echo \"$FILESYSTEMS\" | grep -q 'TYPE=\"f2fs\"' && modprobe f2fs >/dev/null 2>&1 || true",
            "TARGET_DEVICE=\"\"",
            "for dev in $(echo \"$FILESYSTEMS\" | grep -E 'TYPE=\"(ext2|ext3|ext4|btrfs|xfs|f2fs)\"' | cut -d: -f1); do",
            "    marker \"PROBE:$dev\"",
            "    if mount -o rw \"$dev\" /mnt >/dev/null 2>&1; then",
            "        if [ -f /mnt/etc/passwd ]; then",
            "            TARGET_DEVICE=\"$dev\"",
            "            break",
            "        fi",
            "        umount /mnt >/dev/null 2>&1 || true",
            "    fi",
            "done",
            "[ -n \"$TARGET_DEVICE\" ] || fail ROOT_NOT_FOUND",
            "printf '%%s\\n' \"$TARGET_DEVICE\" > /run/droidvm-root-device",
            "marker \"ROOT:$TARGET_DEVICE\"",
            "change_password() {",
            "    marker \"PASSWD:$1\"",
            "    printf '%%s:%%s\\n' \"$1\" \"$PASSWORD\" |",
            "        busybox chpasswd --crypt-method SHA256 --root /mnt || fail PASSWD_FAILED",
            "}",
            "change_password root",
            "if [ \"$CHANGE_NORMAL_USERS\" = true ]; then",
            "    for user in $(awk -F: '$3 >= 1000 && $3 < 2000 {print $1}' /mnt/etc/passwd); do",
            "        change_password \"$user\"",
            "    done",
            "fi",
            "PASSWORD=",
            "sync",
            "umount /mnt >/dev/null 2>&1 || fail UNMOUNT_FAILED",
            ""
        );
        return fmt(script, escapedString(password), escapedString(changeNormalUsers));
    }

    @Override
    public void clearSecrets() {
        spec.clearParam("password");
        vm.clearActionVar("PASSWORD"); // Also scrub a deserialized legacy action.
    }
}
