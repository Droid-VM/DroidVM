// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.input;

import static cn.classfun.droidvm.lib.utils.FileUtils.readFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code keyboard_grab.json}: whether the physical keyboard may be taken from Android for a guest,
 * and the chord that hands it back.
 *
 * <p>The chord ships unbound on purpose. While a keyboard is grabbed Android sees none of it, so
 * an escape has to be a combination the guest will never want either -- and which one that is
 * depends on what the guest is, which is the user's to say once there is a page to say it on. The
 * mechanism is here and armed; an empty chord simply never matches, and the way out until then is
 * the same one that already exists: leave the display (touch), or switch the console to the system
 * keyboard, both of which release the grab.</p>
 */
public final class KeyboardGrabConfig {
    private static final String TAG = "KeyboardGrabConfig";
    public static final String NAME = "keyboard_grab.json";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ESCAPE_CHORD = "escape_chord";

    /**
     * Whether a display console may ask for the grab at all. The switch a user who wants Android's
     * shortcuts back for good reaches for; the console asks on every state change either way.
     */
    public final boolean enabled;

    /**
     * Linux {@code KEY_*} codes that must be held together to hand the keyboard back, in no
     * particular order. Empty means nothing is bound (see the class note).
     */
    @NonNull
    public final List<Integer> escapeChord;

    public KeyboardGrabConfig(boolean enabled, @NonNull List<Integer> escapeChord) {
        this.enabled = enabled;
        this.escapeChord = Collections.unmodifiableList(new ArrayList<>(escapeChord));
    }

    @NonNull
    public static KeyboardGrabConfig defaults() {
        return new KeyboardGrabConfig(true, Collections.emptyList());
    }

    @NonNull
    public static KeyboardGrabConfig fromJson(@NonNull JSONObject obj) {
        var chord = new ArrayList<Integer>();
        var arr = obj.optJSONArray(KEY_ESCAPE_CHORD);
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                int code = arr.optInt(i, -1);
                // A code outside the evdev key range could never be pressed, and a chord holding
                // one could never match: dropped rather than kept as a chord that cannot fire.
                if (code > 0 && code <= 0x2ff && !chord.contains(code)) chord.add(code);
            }
        }
        return new KeyboardGrabConfig(obj.optBoolean(KEY_ENABLED, true), chord);
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        var obj = new JSONObject();
        obj.put(KEY_ENABLED, enabled);
        var arr = new JSONArray();
        for (var code : escapeChord) arr.put(code);
        obj.put(KEY_ESCAPE_CHORD, arr);
        return obj;
    }

    /** The saved settings, or the defaults when there is no file or it cannot be read. */
    @NonNull
    public static KeyboardGrabConfig load(@NonNull File file) {
        if (!file.isFile()) return defaults();
        try {
            return fromJson(new JSONObject(readFile(file)));
        } catch (Exception e) {
            Log.w(TAG, fmt("Cannot read %s; using the defaults", file), e);
            return defaults();
        }
    }

    /**
     * Writes next to the target and renames, so a reader never sees half a file, and leaves it to
     * the owner of the directory -- the daemon runs as root and the UI does not, and a root-owned
     * file in the app's directory is one the UI could neither read nor replace.
     */
    @SuppressWarnings("OctalInteger")
    public void save(@NonNull File file) throws IOException {
        var parent = file.getParentFile();
        if (parent == null || !parent.isDirectory())
            throw new IOException(fmt("no directory for %s", file));
        var staging = new File(parent, fmt("%s.tmp", file.getName()));
        try {
            var text = toJson().toString(2);
            try (var out = new FileOutputStream(staging)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            var owner = Os.stat(parent.getPath());
            Os.chmod(staging.getPath(), 0600);
            Os.chown(staging.getPath(), owner.st_uid, owner.st_gid);
            if (!staging.renameTo(file))
                throw new IOException(fmt("cannot replace %s", file));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            staging.delete();
        }
    }
}
