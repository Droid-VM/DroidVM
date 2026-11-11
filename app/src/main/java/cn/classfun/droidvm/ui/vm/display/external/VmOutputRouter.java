// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.external;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import cn.classfun.droidvm.lib.store.vm.NativeDisplay;

/**
 * Where each VM screen's picture is shown: on the phone, in its console, or on an external display.
 * One owner a screen at a time, since crosvm has one scanout slot a screen.
 *
 * <p>The console asks here and listens; the external display's window is {@link
 * ExternalOutputService}'s. A choice of display is remembered by the display's name, so the
 * picture goes back there whenever the console opens and that display is connected -- display ids
 * do not survive a reconnect -- until the user brings it back to the phone.</p>
 */
@MainThread
public final class VmOutputRouter {
    private static final String TAG = "VmOutputRouter";
    private static final String PREFS = "external_output";
    private static final VmOutputRouter INSTANCE = new VmOutputRouter();

    /** A screen whose picture moved: to the display [displayId], or back to the phone (INVALID_DISPLAY). */
    public interface Listener {
        void onOutputChanged(@NonNull String screenKey, int displayId);
    }

    /** The screen a picture belongs to, as the console knows it. */
    public static final class Screen {
        public final String vmId;
        public final String screenId;
        public final String vmName;
        public final int width;
        public final int height;
        /** A VNC-exported screen (its picture comes over VNC) rather than the native display. */
        public final boolean vnc;

        public Screen(@NonNull String vmId, @NonNull String screenId, @NonNull String vmName,
                      int width, int height) {
            this(vmId, screenId, vmName, width, height, false);
        }

        public Screen(@NonNull String vmId, @NonNull String screenId, @NonNull String vmName,
                      int width, int height, boolean vnc) {
            this.vmId = vmId;
            this.screenId = screenId;
            this.vmName = vmName;
            this.width = width;
            this.height = height;
            this.vnc = vnc;
        }

        @NonNull
        public String key() {
            return keyOf(vmId, screenId);
        }
    }

    private final Map<String, Integer> outputs = new HashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private VmOutputRouter() {
    }

    @NonNull
    public static VmOutputRouter get() {
        return INSTANCE;
    }

    @NonNull
    public static String keyOf(@NonNull String vmId, @NonNull String screenId) {
        return NativeDisplay.serviceNameFromId(vmId, screenId);
    }

    public void addListener(@NonNull Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    /** The display [screenKey]'s picture is on, or INVALID_DISPLAY for the phone. */
    public int displayOf(@NonNull String screenKey) {
        var id = outputs.get(screenKey);
        return id == null ? Display.INVALID_DISPLAY : id;
    }

    /** Shows [screen]'s picture on [display] and remembers that display for it. */
    public void project(@NonNull Context context, @NonNull Screen screen, @NonNull Display display) {
        checkMain();
        String key = screen.key();
        outputs.put(key, display.getDisplayId());
        prefs(context).edit().putString(key, display.getName()).apply();
        Log.i(TAG, fmt("%s -> display %d (%s)", key, display.getDisplayId(), display.getName()));
        notifyListeners(key, display.getDisplayId());
        context.startForegroundService(new Intent(context, ExternalOutputService.class)
            .putExtra(ExternalOutputService.EXTRA_VM_ID, screen.vmId)
            .putExtra(ExternalOutputService.EXTRA_SCREEN, screen.screenId)
            .putExtra(ExternalOutputService.EXTRA_VM_NAME, screen.vmName)
            .putExtra(ExternalOutputService.EXTRA_WIDTH, screen.width)
            .putExtra(ExternalOutputService.EXTRA_HEIGHT, screen.height)
            .putExtra(ExternalOutputService.EXTRA_VNC, screen.vnc)
            .putExtra(ExternalOutputService.EXTRA_DISPLAY_ID, display.getDisplayId()));
    }

    /**
     * Brings [screenKey]'s picture back to the phone: the console shows it again, and the external
     * window goes. [forget] also drops the remembered display (the user's choice); a display that
     * went away keeps it, so the picture returns there when it does.
     */
    public void bringBack(@NonNull Context context, @NonNull String screenKey, boolean forget) {
        checkMain();
        if (forget) prefs(context).edit().remove(screenKey).apply();
        if (outputs.remove(screenKey) == null) return;
        Log.i(TAG, fmt("%s -> the phone%s", screenKey, forget ? "" : " (its display went away)"));
        notifyListeners(screenKey, Display.INVALID_DISPLAY);
        context.startService(new Intent(context, ExternalOutputService.class)
            .setAction(ExternalOutputService.ACTION_REMOVE)
            .putExtra(ExternalOutputService.EXTRA_KEY, screenKey));
    }

    /**
     * Called by the service when a picture's window is gone without the router having asked (its
     * display disconnected, the window refused): the picture is the phone's again, the choice kept.
     */
    void onWindowGone(@NonNull String screenKey) {
        if (outputs.remove(screenKey) == null) return;
        Log.i(TAG, fmt("%s -> the phone (its window is gone)", screenKey));
        notifyListeners(screenKey, Display.INVALID_DISPLAY);
    }

    /**
     * The connected display [screen] was last shown on, if it is connected and the picture is not
     * already elsewhere: where the console sends it as it opens. Never the phone's own display.
     */
    @Nullable
    public Display rememberedDisplay(@NonNull Context context, @NonNull String screenKey) {
        if (outputs.containsKey(screenKey)) return null;
        String name = prefs(context).getString(screenKey, null);
        if (name == null) return null;
        for (var display : externalDisplays(context))
            if (name.equals(display.getName())) return display;
        return null;
    }

    /** The displays a picture can go to: every connected one but the phone's own. */
    @NonNull
    public static Display[] externalDisplays(@NonNull Context context) {
        var manager = context.getSystemService(DisplayManager.class);
        if (manager == null) return new Display[0];
        return java.util.Arrays.stream(manager.getDisplays())
            .filter(d -> d.getDisplayId() != Display.DEFAULT_DISPLAY)
            .toArray(Display[]::new);
    }

    private void notifyListeners(@NonNull String key, int displayId) {
        for (var listener : listeners) listener.onOutputChanged(key, displayId);
    }

    @NonNull
    private static android.content.SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void checkMain() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("VmOutputRouter is main-thread only");
    }
}
