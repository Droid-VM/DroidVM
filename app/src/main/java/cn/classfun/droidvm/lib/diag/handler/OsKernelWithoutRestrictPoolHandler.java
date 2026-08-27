// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.diag.handler;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.diag.LogHelperHandler;
import cn.classfun.droidvm.ui.vm.console.VMConsoleActivity;

/**
 * A device could not reach memory the guest lent to the host in a protected VM.
 *
 * <p>Two different faults print this same line, which is why the dialog names both: the guest
 * kernel may have no restricted DMA pool to put the device's buffers in, or the guest driver for
 * that particular device may not be one of the ported ones and so never allocated from the pool it
 * does have. The device names are the only thing in the log that separates "the whole VM has no
 * pool" from "this one driver is wrong", so they are listed rather than summarised away.</p>
 */
public final class OsKernelWithoutRestrictPoolHandler extends LogHelperHandler {
    /**
     * What the log page is prefiltered with -- the marker without the address, since the address
     * differs per line. {@link #MARKER} is the same text plus the start of the address, which is
     * what makes a line one of these rather than a mention of the words.
     */
    private static final String FILTER = "host access to lent memory region at";
    private static final String MARKER = fmt("%s 0x", FILTER);
    /**
     * The failing device, which crosvm puts before " activate failed" on the same line:
     * {@code ... virtio_pci_device] pcivu-sound activate failed: failed to get host address: host
     * access to lent memory region at 0x105600000 (purpose=GuestMemoryRegion) in protected VM}
     */
    private static final Pattern DEVICE = Pattern.compile("(\\S+) activate failed");
    private static final String BULLET = "\u2022 ";  // U+2022, escaped to keep this file ASCII
    private static final String STREAM = "stderr";

    /**
     * Distinct device names per VM, in the order the log named them. Keyed by vmId because one
     * instance serves every VM, and emptied by {@link #onLogContextReset}.
     */
    private final Map<UUID, Set<String>> devices = new ConcurrentHashMap<>();

    @Override
    public void observe(@NonNull UUID vmId, @NonNull String stream, @NonNull String text) {
        if (!stream.equals(STREAM) || !text.contains(MARKER)) return;
        var found = devices.computeIfAbsent(
            vmId, k -> Collections.synchronizedSet(new LinkedHashSet<>()));
        for (var line : text.split("\n")) {
            if (!line.contains(MARKER)) continue;
            var m = DEVICE.matcher(line);
            // A matching line that does not name a device goes in as it came: dropping it would
            // report fewer devices than the log shows, which is the one thing this list is for.
            found.add(m.find() ? m.group(1) : line.trim());
        }
    }

    @Override
    public boolean match(@NonNull UUID vmId, @NonNull String stream, @NonNull String text) {
        // observe() has already read this same text; a non-empty list is exactly "a line with the
        // marker was seen", so the buffer is not scanned a second time.
        return stream.equals(STREAM) && !namesOf(vmId).isEmpty();
    }

    @Override
    public void onLogContextReset(@NonNull UUID vmId) {
        devices.remove(vmId);
    }

    @Override
    public void show(@NonNull Context ctx, @NonNull UUID vmId, @NonNull String vmName) {
        var sb = new android.text.SpannableStringBuilder();
        sb.append(ctx.getString(R.string.log_helper_no_restrict_pool_devices, vmName));
        for (var device : namesOf(vmId)) sb.append('\n').append(BULLET).append(device);
        sb.append("\n\n");
        // The body carries its links as anchors with human labels, so it is HTML in the resource
        // and spans here; a raw URL would be linkified downstream, but an <a> tag would not.
        sb.append(android.text.Html.fromHtml(
            ctx.getString(R.string.log_helper_no_restrict_pool_message),
            android.text.Html.FROM_HTML_MODE_LEGACY));
        showDialog(ctx,
            R.string.log_helper_no_restrict_pool_url,
            R.string.log_helper_no_restrict_pool_title,
            sb,
            R.string.log_helper_open_log,
            (d, w) -> openLog(ctx, vmId, vmName)
        );
    }

    /** A snapshot: observe() runs on the daemon's event thread and show() on the main one. */
    @NonNull
    private List<String> namesOf(@NonNull UUID vmId) {
        var found = devices.get(vmId);
        if (found == null) return List.of();
        synchronized (found) {
            return new ArrayList<>(found);
        }
    }

    /**
     * The log page, on stderr, prefiltered to the lines the list above was read from -- the point
     * of opening it here is to see those lines, not to land in the whole boot log.
     */
    private static void openLog(@NonNull Context ctx, @NonNull UUID vmId, @NonNull String vmName) {
        var intent = new Intent(ctx, VMConsoleActivity.class);
        intent.putExtra(VMConsoleActivity.EXTRA_VM_ID, vmId.toString());
        intent.putExtra(VMConsoleActivity.EXTRA_VM_NAME, vmName);
        intent.putExtra(VMConsoleActivity.EXTRA_STREAM, STREAM);
        intent.putExtra(VMConsoleActivity.EXTRA_LOGS, true);
        intent.putExtra(VMConsoleActivity.EXTRA_FILTER, FILTER);
        ctx.startActivity(intent);
    }
}
