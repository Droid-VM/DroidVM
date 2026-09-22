// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.utils.FileUtils.readFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code usb_rules.json} in the app's files directory. The daemon is the only writer: it reads
 * the file once at start-up and rewrites it on every {@code usb_rules_set}, which is the whole
 * of how the two editing pages change it. Neither of them opens the file.
 */
public final class UsbRulesFile {
    private static final String TAG = "UsbRulesFile";
    public static final String NAME = "usb_rules.json";

    private UsbRulesFile() {
    }

    /**
     * The saved rules, or an empty set when there is no file or it cannot be read. A file the
     * daemon cannot parse is left where it is: the UI may still make sense of it, and an empty
     * set is what the daemon acts on either way.
     */
    @NonNull
    public static UsbRules load(@NonNull File file) {
        if (!file.isFile()) return UsbRules.empty();
        try {
            // No VM check: a VM deleted while the daemon was down is not a reason to drop the
            // user's rule for it, and a rule for a VM that does not exist never matches.
            var rules = UsbRules.fromJson(new JSONObject(readFile(file)), null);
            Log.i(TAG, fmt("Loaded USB rules from %s", file));
            return rules;
        } catch (Exception e) {
            Log.w(TAG, fmt("Cannot read %s; starting with no USB rules", file), e);
            return UsbRules.empty();
        }
    }

    /**
     * Writes [rules] next to the target and renames, so a reader never sees half a file, and
     * gives the result to the owner of the directory: the daemon runs as root, the UI does not,
     * and a root-owned file in the app's directory is one the UI can neither read nor replace.
     */
    @SuppressWarnings("OctalInteger")
    public static void save(@NonNull File file, @NonNull UsbRules rules) throws IOException {
        var parent = file.getParentFile();
        if (parent == null || !parent.isDirectory())
            throw new IOException(fmt("no directory for %s", file));
        var staging = new File(parent, fmt("%s.tmp", file.getName()));
        try {
            var text = rules.toJson().toString(2);
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
