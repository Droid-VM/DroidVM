// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.os.FileObserver;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The host's USB devices, and the inotify watch that keeps the list current.
 *
 * <p>sysfs has no usable inotify semantics, so the watch is on {@code /dev/bus/usb} instead: the
 * kernel creates and removes a node there for every device, and the bus directories themselves
 * come and go with USB role switches, which is why the root is watched as well as each bus.</p>
 */
public final class UsbHostInventory {
    private static final String TAG = "UsbHostInventory";
    /** A device directory: {@code 1-1}, {@code 1-1.4.2}. Excludes usbN root hubs and interfaces. */
    private static final Pattern DEVICE_NAME = Pattern.compile("^\\d+-[\\d.]+$");
    /** A bus directory under devRoot: {@code 001}. */
    private static final Pattern BUS_NAME = Pattern.compile("^\\d+$");
    /** One plug event is several inotify events; rescan once they have stopped arriving. */
    private static final long QUIET_PERIOD_MS = 300;
    private static final int ROOT_MASK = FileObserver.CREATE | FileObserver.DELETE
        | FileObserver.MOVED_TO | FileObserver.MOVED_FROM;
    private static final int BUS_MASK = FileObserver.CREATE | FileObserver.DELETE;
    /**
     * IN_Q_OVERFLOW and IN_UNMOUNT, which FileObserver has no constants for: the kernel sends both
     * unasked, and both mean events were lost, so they stand for "whatever you missed".
     */
    private static final int LOST_EVENTS = 0x4000 | 0x2000;

    public interface Listener {
        void onChanged(@NonNull List<UsbHostDevice> all, @NonNull List<UsbHostDevice> added,
                       @NonNull List<UsbHostDevice> removed);
    }

    private final String sysfsRoot;
    private final String devRoot;
    /** Guards the snapshot and serialises scan-diff-notify against itself. */
    private final Object rescanLock = new Object();
    /** Guards the observers and the debounce timer; held only for the moment it takes to arm. */
    private final Object watchLock = new Object();
    /** Strong references: an unreferenced FileObserver is collected and stops watching silently. */
    private final Map<String, FileObserver> busObservers = new HashMap<>();
    private volatile List<UsbHostDevice> snapshot = Collections.emptyList();
    private volatile Listener listener = null;
    private FileObserver rootObserver = null;
    private ScheduledExecutorService scheduler = null;
    private ScheduledFuture<?> pending = null;

    public UsbHostInventory(@NonNull String sysfsRoot, @NonNull String devRoot) {
        this.sysfsRoot = sysfsRoot;
        this.devRoot = devRoot;
    }

    /** Every non-hub device sysfs currently describes, sorted by its sysfs name. */
    @NonNull
    public List<UsbHostDevice> scan() {
        var entries = new File(sysfsRoot).listFiles();
        var devices = new ArrayList<UsbHostDevice>();
        if (entries == null) {
            Log.w(TAG, fmt("Cannot list %s", sysfsRoot));
            return devices;
        }
        for (var entry : entries) {
            var name = entry.getName();
            if (!DEVICE_NAME.matcher(name).matches()) continue;
            UsbHostDevice device;
            try {
                device = UsbHostDevice.fromSysfs(entry, devRoot);
            } catch (Exception e) {
                // A device disconnected mid-scan leaves a directory whose files are already gone.
                Log.w(TAG, fmt("Skipping USB device %s: %s", name, e.getMessage()));
                continue;
            }
            // A hub is the tree, not a device to lend out.
            if (device.isHub()) continue;
            devices.add(device);
        }
        devices.sort(Comparator.comparing((UsbHostDevice device) -> device.sysfs));
        return devices;
    }

    /** Takes the first snapshot -- without calling [listener] -- and starts watching. */
    public void start(@NonNull Listener listener) {
        this.listener = listener;
        synchronized (rescanLock) {
            snapshot = scan();
        }
        synchronized (watchLock) {
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    var t = new Thread(r, "usb-inventory");
                    t.setDaemon(true);
                    return t;
                });
            }
            if (rootObserver == null) {
                // FileObserver(File, int) needs API 29; minSdk is 33, so the File form is fine.
                rootObserver = new DirObserver(new File(devRoot), ROOT_MASK, true);
                rootObserver.startWatching();
            }
            refreshBusObservers();
        }
        Log.i(TAG, fmt("Watching %s (%d device(s) present)", devRoot, snapshot.size()));
    }

    public void stop() {
        synchronized (watchLock) {
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
            for (var observer : busObservers.values())
                observer.stopWatching();
            busObservers.clear();
            if (rootObserver != null) {
                rootObserver.stopWatching();
                rootObserver = null;
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
    }

    /** The last scan; never null, empty before {@link #start}. */
    @NonNull
    public List<UsbHostDevice> snapshot() {
        return snapshot;
    }

    /** Scans now, diffs against the snapshot and calls the listener if anything moved. */
    @NonNull
    public List<UsbHostDevice> rescanNow() {
        List<UsbHostDevice> all;
        List<UsbHostDevice> added = new ArrayList<>();
        List<UsbHostDevice> removed = new ArrayList<>();
        synchronized (rescanLock) {
            var previous = snapshot;
            all = scan();
            // Keyed by address and device number, not by address alone: an unplug and replug
            // that both land inside one quiet period put a new device at the same sysfs name,
            // and by name the two scans would agree that nothing happened. The kernel hands
            // every enumeration a fresh devnum, so that is what tells the two instances apart.
            var before = new HashSet<String>();
            for (var device : previous) before.add(instanceKey(device));
            var after = new HashSet<String>();
            for (var device : all) after.add(instanceKey(device));
            for (var device : all)
                if (!before.contains(instanceKey(device))) added.add(device);
            for (var device : previous)
                if (!after.contains(instanceKey(device))) removed.add(device);
            snapshot = all;
        }
        if (added.isEmpty() && removed.isEmpty()) return all;
        var target = listener;
        if (target == null) return all;
        try {
            target.onChanged(all, added, removed);
        } catch (Exception e) {
            Log.w(TAG, "USB inventory listener failed", e);
        }
        return all;
    }

    @NonNull
    private static String instanceKey(@NonNull UsbHostDevice device) {
        return fmt("%s#%d", device.sysfs, device.devnum);
    }

    private void scheduleRescan() {
        synchronized (watchLock) {
            var exec = scheduler;
            if (exec == null) return;
            if (pending != null) pending.cancel(false);
            try {
                pending = exec.schedule(() -> {
                    try {
                        rescanNow();
                    } catch (Exception e) {
                        Log.w(TAG, "USB inventory rescan failed", e);
                    }
                }, QUIET_PERIOD_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // stop() won the race; there is nothing left to keep current.
                pending = null;
            }
        }
    }

    /** Adds an observer for every bus directory that appeared and drops the ones that went. */
    private void refreshBusObservers() {
        synchronized (watchLock) {
            if (rootObserver == null) return;
            var present = new HashSet<String>();
            var entries = new File(devRoot).listFiles();
            if (entries != null) {
                for (var entry : entries) {
                    var name = entry.getName();
                    if (!entry.isDirectory() || !BUS_NAME.matcher(name).matches()) continue;
                    present.add(name);
                    if (busObservers.containsKey(name)) continue;
                    var observer = new DirObserver(entry, BUS_MASK, false);
                    busObservers.put(name, observer);
                    observer.startWatching();
                }
            }
            var it = busObservers.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (present.contains(e.getKey())) continue;
                e.getValue().stopWatching();
                it.remove();
            }
        }
    }

    private final class DirObserver extends FileObserver {
        private final int mask;
        private final boolean isRoot;

        DirObserver(@NonNull File dir, int mask, boolean isRoot) {
            super(dir, mask);
            this.mask = mask;
            this.isRoot = isRoot;
        }

        @Override
        public void onEvent(int event, @Nullable String path) {
            // FileObserver hands the raw inotify bits through, so events nobody asked for land
            // here too. IN_IGNORED only says this one watch is over and carries no news; a
            // dropped queue or an unmounted devfs is precisely when a rescan is owed, and the
            // bus observers have to be rebuilt first because their watches may be gone with it.
            if ((event & (mask | LOST_EVENTS)) == 0) return;
            if (isRoot || (event & LOST_EVENTS) != 0) refreshBusObservers();
            scheduleRescan();
        }
    }
}
