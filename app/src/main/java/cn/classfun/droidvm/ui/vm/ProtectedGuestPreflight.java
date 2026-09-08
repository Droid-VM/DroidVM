// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.vm.BootConfig;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMXhciConfig;
import cn.classfun.droidvm.ui.usb.UsbRuleLayer;
import cn.classfun.droidvm.ui.vm.boot.BootEntries;

/**
 * Will this guest work with its memory lent to the hypervisor?
 *
 * <p>A protected VM's RAM is out of the host's reach while it runs (see
 * {@link ProtectedVM#lendsGuestMemory}), and two things the guest may be counting on need the
 * host to reach it. Which one bites depends on which OS actually comes up, so that is what is
 * asked first -- the image, not the boot protocol:
 *
 * <ul>
 *   <li><b>Linux</b> -- virtio has to bounce through a restricted DMA pool, so a kernel built
 *       without {@code CONFIG_DMA_RESTRICTED_POOL} comes up with no disk and no network. lbx
 *       reads that out of the image's own {@code config-*}, per entry.</li>
 *   <li><b>Windows</b> -- its xHCI passthrough has nowhere to map transfer buffers, so an
 *       attached device does not work. (Linux guests are fine here: the same bounce pool that
 *       the kernel needs anyway covers USB too.) Only worth saying when this VM actually has a
 *       controller and a rule pointing at it -- otherwise nothing would have been passed
 *       through in the first place.</li>
 * </ul>
 *
 * <p>Both have the same answer, which is why they are one check:
 * {@link ProtectedVM#PSEUDO_UNPROTECTED} shares the guest's RAM instead of lending it, and both
 * limits go away.</p>
 *
 * <p><b>Only what is certain is raised.</b> An image whose bootloader lbx cannot read, an OS
 * this build has no rule for (FreeBSD, something custom), a kernel with no {@code config-*} to
 * read -- each of those is a question we cannot answer, and a warning nobody can act on is
 * worse than none. Every one of them ends the check quietly.</p>
 *
 * <p>The cheap questions come first -- the memory mode, then the boot config -- and the image is
 * read only past them; the daemon's rule list is fetched only once the image says Windows.
 * Asynchronous throughout: the callback lands on whichever thread answered (the caller's, or
 * the daemon's), so post it where you need it.</p>
 */
public final class ProtectedGuestPreflight {
    /** Why lending this guest's memory will not work. */
    public enum Reason {
        /** Linux, whose kernel has no restricted DMA pool for virtio to bounce through. */
        DMA_POOL,
        /** Windows, with USB passthrough configured. */
        WINDOWS_USB,
    }

    public interface Callback {
        /** {@code reason} is null when nothing is in the way. */
        void onResult(@Nullable Reason reason);
    }

    private ProtectedGuestPreflight() {
    }

    /**
     * @param menuWillAsk the boot menu is about to run, and offers the same choice per entry --
     *                    then the Linux check is left to it, which knows the entry the user
     *                    picks rather than only the one the config resolves to
     */
    public static void check(
        @NonNull VMConfig config,
        boolean menuWillAsk,
        @NonNull Callback callback
    ) {
        if (!ProtectedVM.lendsGuestMemory(config.item)) {
            callback.onResult(null);
            return;
        }
        var boot = BootConfig.of(config);
        var uefi = boot.getProtocol() == BootConfig.Protocol.UEFI;
        // A manual kernel is one the user pointed at themselves; there is no image to read a
        // .config out of, so there is nothing to say about it.
        if (!uefi && (boot.getLinuxSource() != BootConfig.LinuxSource.IMAGE || menuWillAsk)) {
            callback.onResult(null);
            return;
        }
        var image = BootConfig.imagePath(config, boot.getImageDisk());
        if (image == null) {
            callback.onResult(null);
            return;
        }
        BootEntries.scan(image, (result, error) -> {
            if (result == null) {
                // A scan that failed says nothing either way, and a start that needs the image
                // read has its own error for that.
                callback.onResult(null);
                return;
            }
            // Which guest actually comes up: under UEFI the image boots itself, so it is the
            // bootloader's own choice; a direct kernel boot takes that over and picks a Linux
            // entry itself.
            var entry = uefi ? result.bootloaderDefault() : result.resolve(boot.getImageEntry());
            if (entry == null) {
                callback.onResult(null);
                return;
            }
            if (entry.isWindows()) {
                checkWindowsUsb(config, callback);
                return;
            }
            // An OS this build does not recognise -- FreeBSD, something custom, an image whose
            // bootloader lbx could not read -- is one we know nothing about. Say nothing.
            if (!entry.isLinux()) {
                callback.onResult(null);
                return;
            }
            // Unknown (null) is not a warning either: lbx only says "false" when it read the
            // kernel's own config and the option was not set.
            callback.onResult(entry.lacksRestrictedDmaPool() ? Reason.DMA_POOL : null);
        });
    }

    /**
     * Is anything actually passed through to this VM? Asked only once the image says Windows,
     * so a VM with no controller and no rules costs nothing to rule out.
     */
    private static void checkWindowsUsb(@NonNull VMConfig config, @NonNull Callback callback) {
        if (!VMXhciConfig.isEnabled(config.item)) {
            callback.onResult(null);
            return;
        }
        DaemonConnection.getInstance().buildRequest("usb_rules_get")
            .onResponse(resp -> {
                var rules = resp.optJSONObject("rules");
                var passed = rules != null && rules.optBoolean("enabled", true)
                    && anyRuleTargets(rules, config);
                callback.onResult(passed ? Reason.WINDOWS_USB : null);
            })
            // No rules to read means nothing is being passed through: the daemon that would
            // act on them is the one that did not answer.
            .onUnsuccessful(resp -> callback.onResult(null))
            .onError(e -> callback.onResult(null))
            .invoke();
    }

    /**
     * Does any rule hand a device to this VM? A rule naming a controller the VM no longer has
     * is not one of them -- it can never attach, which is exactly why the editor leaves such a
     * rule visibly dangling instead of re-pointing it.
     */
    private static boolean anyRuleTargets(@NonNull JSONObject rules, @NonNull VMConfig config) {
        for (var layer : UsbRuleLayer.values()) {
            var arr = rules.optJSONArray(layer.key);
            for (int i = 0; arr != null && i < arr.length(); i++) {
                var rule = arr.optJSONObject(i);
                if (rule == null) continue;
                if (pointsAtVm(config,
                    text(rule, "vm"), text(rule, "target"), text(rule, "controller")))
                    return true;
            }
        }
        return false;
    }

    /**
     * Does one rule hand a device to this VM? Takes the rule's fields rather than the rule, so
     * the decision is reachable without org.json -- which the unit tests' android.jar stubs out.
     *
     * @param target     what the rule does with what it matches; a rule carrying none is one
     *                   written before there was a target to write, and its VM is the whole of
     *                   what it said -- how the daemon reads it too
     * @param controller the controller inside the VM, or null for its first
     */
    static boolean pointsAtVm(
        @NonNull VMConfig config,
        @Nullable String vm,
        @Nullable String target,
        @Nullable String controller
    ) {
        var id = config.getId();
        if (id == null || vm == null || !id.toString().equals(vm)) return false;
        if (target != null && !"vm".equals(target)) return false;
        return VMXhciConfig.findController(config.item, controller) != null;
    }

    /** A rule's string field: absent, JSON null and empty all read as null. */
    @Nullable
    private static String text(@NonNull JSONObject rule, @NonNull String key) {
        if (rule.isNull(key)) return null;
        var value = rule.optString(key, "");
        return value.isEmpty() ? null : value;
    }
}
