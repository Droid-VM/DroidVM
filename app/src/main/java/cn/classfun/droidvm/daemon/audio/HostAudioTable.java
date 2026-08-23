// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.audio;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import cn.classfun.droidvm.lib.data.HostAudioDevices;

/**
 * Publishes the host's audio endpoints where crosvm's audio backend can read them.
 *
 * <p>AAudio names a device with an integer the platform hands out per connection: unplug a headset
 * and plug it back in and the number is different, though it is plainly the same headset. A VM
 * pinned to an endpoint therefore cannot hold on to the number -- it has to hold the name and look
 * the number up again, every time it opens a stream.</p>
 *
 * <p>Only Java can enumerate the devices: AAudio has no API for it and {@link AudioManager} is not
 * reachable from the backend, which is a native process running unprivileged. So the list is
 * written out here and re-read there. The matching happens on the crosvm side rather than the id
 * being pushed to it, so a device coming back needs no round trip -- the file changes, and the
 * name the backend is holding is still the same name.</p>
 */
public final class HostAudioTable {
    private static final String TAG = "HostAudioTable";

    /** Where the table lives. One per daemon, not per VM: it describes the host, not a guest. */
    public static final String PATH = pathJoin(DATA_DIR, "run", "audio_devices");

    private static @Nullable HostAudioTable instance;

    private final Context context;
    private final AudioDeviceCallback callback;

    private HostAudioTable(@NonNull Context context) {
        this.context = context;
        this.callback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                write();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                write();
            }
        };
    }

    /**
     * Starts publishing, and keeps publishing until the daemon exits. Safe to call more than
     * once; only the first call does anything.
     */
    public static synchronized void start(@Nullable Context context) {
        try {
            startOrThrow(context);
        } catch (Throwable t) {
            // Never take the daemon down over this. Without the table a VM pinned to an endpoint
            // falls back to the platform's routing, which is a worse configuration than the user
            // asked for; a daemon that will not start is no configuration at all.
            Log.w(TAG, "failed to start publishing host audio endpoints", t);
        }
    }

    private static void startOrThrow(@Nullable Context context) {
        if (instance != null || context == null) return;
        var am = context.getSystemService(AudioManager.class);
        if (am == null) {
            Log.w(TAG, "AudioManager unavailable; host audio endpoints will not be published");
            return;
        }
        var table = new HostAudioTable(context);
        // Its own thread, rather than Looper.getMainLooper(): the daemon is not an app process
        // and has no main looper, so asking for one returns null and constructing a Handler on
        // it throws. Writing the table is a few hundred bytes and happens only when something is
        // plugged or unplugged, so a thread of its own costs nothing.
        var thread = new HandlerThread("HostAudioTable");
        thread.start();
        am.registerAudioDeviceCallback(table.callback, new Handler(thread.getLooper()));
        table.write();
        instance = table;
        Log.i(TAG, fmt("publishing host audio endpoints to %s", PATH));
    }

    /**
     * Writes the current endpoints.
     *
     * <p>Lines are {@code <id>\t<in|out>\t<TYPE|address>}. Written to a neighbouring file and
     * renamed, so a reader never sees half a table -- half a table resolves an endpoint to
     * nothing, and the backend would fall back to the platform's routing for no reason.</p>
     */
    /**
     * Rewrites the table, and never lets a failure escape.
     *
     * <p>This runs on the device-callback thread, where an uncaught exception takes the whole
     * daemon down with it -- which is how a failure to describe the audio devices came to stop
     * VMs from starting at all.</p>
     */
    private void write() {
        try {
            writeOrThrow();
        } catch (Throwable t) {
            Log.w(TAG, "failed to publish host audio endpoints", t);
        }
    }

    private void writeOrThrow() {
        var text = new StringBuilder();
        int lines = appendAll(text, false) + appendAll(text, true);

        var target = new File(PATH);
        var staging = new File(PATH + ".new");
        try {
            var parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                Log.w(TAG, fmt("cannot create %s", parent));
                return;
            }
            try (var out = new FileOutputStream(staging)) {
                out.write(text.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            // The backend runs as this app's uid, so the default mode already lets it read.
            if (!staging.renameTo(target)) {
                Log.w(TAG, "failed to replace the host audio table");
                //noinspection ResultOfMethodCallIgnored
                staging.delete();
                return;
            }
            Log.i(TAG, fmt("host audio endpoints published: %d", lines));
        } catch (IOException e) {
            Log.w(TAG, "failed to write the host audio table", e);
        }
    }

    private int appendAll(@NonNull StringBuilder text, boolean input) {
        // The platform's own routing, as an ordinary row against AAUDIO_DEVICE_UNSPECIFIED.
        // Listing it means "follow the platform" resolves through the same lookup as everything
        // else, instead of being an absence that every reader has to recognise separately.
        text.append(fmt("%d\t%s\t%s\n", HostAudioDevices.DEVICE_UNSPECIFIED,
            input ? "in" : "out", HostAudioDevices.SYSTEM_DEFAULT_KEY));
        // Deliberately not HostAudioDevices.list: that builds a label for the picker, and a label
        // needs the app's string resources, which the daemon's context does not have.
        var keys = new java.util.ArrayList<String>();
        var ids = HostAudioDevices.idsAndKeys(context, input, keys);
        for (int i = 0; i < ids.size() && i < keys.size(); i++) {
            text.append(fmt("%d\t%s\t%s\n", ids.get(i)[0], input ? "in" : "out", keys.get(i)));
        }
        return keys.size();
    }
}
