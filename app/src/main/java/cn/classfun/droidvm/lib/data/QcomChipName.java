// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.data;

import static cn.classfun.droidvm.lib.utils.RunUtils.runListQuiet;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import cn.classfun.droidvm.R;

public final class QcomChipName {
    private static final String TAG = "QcomChipName";
    private Map<String, String> chips = null;

    public QcomChipName(Context ctx) {
        this(ctx.getResources());
    }

    /**
     * The table from a bare {@link Resources}, for processes that have no usable Context of their
     * own -- see {@link #apkResources()}.
     */
    public QcomChipName(@NonNull Resources res) {
        loadChipName(res);
    }

    private void loadChipName(Resources res) {
        this.chips = new HashMap<>();
        try (XmlResourceParser parser = res.getXml(R.xml.qcom)) {
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.getName().equals("chip")) {
                    var id = parser.getAttributeValue(null, "id");
                    var name = parser.nextText();
                    if (id != null && name != null)
                        chips.put(id.trim(), name.trim());
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load qcom.xml", e);
        }
    }

    /**
     * Resources for the app's own APK, built without going through a Context.
     *
     * The daemon is a bare {@code app_process} that never ran {@code ActivityThread.attach()}, so
     * {@code ActivityThread.currentActivityThread()} is null there and
     * {@code Context.createPackageContext()} dies inside ResourcesManager with an NPE. An
     * AssetManager built straight from the APK -- the one on the daemon's CLASSPATH, which is how
     * DaemonHelper launches it -- reaches the same {@code res/xml/qcom.xml} with nothing but the
     * default configuration, which is all a plain lookup table needs.
     *
     * @return the resources, or null when the APK cannot be located or opened.
     */
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    @SuppressWarnings("deprecation")
    @Nullable
    public static Resources apkResources() {
        var classPath = System.getenv("CLASSPATH");
        if (classPath == null || classPath.trim().isEmpty()) {
            Log.w(TAG, "no CLASSPATH; cannot reach the APK resources");
            return null;
        }
        for (var entry : classPath.split(":")) {
            var apk = entry.trim();
            if (apk.isEmpty() || !new File(apk).isFile()) continue;
            try {
                var assets = AssetManager.class.getDeclaredConstructor().newInstance();
                var addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
                var cookie = (Integer) addAssetPath.invoke(assets, apk);
                if (cookie == null || cookie == 0) {
                    Log.w(TAG, fmt("not a resource archive: %s", apk));
                    continue;
                }
                var res = new Resources(assets, new DisplayMetrics(), new Configuration());
                // Fails here rather than at lookup time if this archive has no chip table.
                res.getResourceName(R.xml.qcom);
                Log.i(TAG, fmt("chip table from %s", apk));
                return res;
            } catch (Throwable t) {
                Log.w(TAG, fmt("failed to open resources from %s", apk), t);
            }
        }
        return null;
    }

    @SuppressWarnings("ReplaceAllNonRegex")
    public String lookupChipName(String soc) {
        if (chips == null)
            throw new IllegalStateException("Chips not loaded");
        soc = soc.replaceAll("-", "");
        soc = soc.replaceAll("_", "");
        soc = soc.replaceAll(" ", "");
        if (chips.containsKey(soc))
            return chips.get(soc);
        return soc;
    }

    @NonNull
    public static String getCurrentSoC() {
        String ret;
        ret = runListQuiet("getprop", "ro.vendor.qti.soc_model").getOutString().trim();
        if (!ret.isEmpty()) return ret;
        ret = runListQuiet("getprop", "ro.soc.model").getOutString().trim();
        if (!ret.isEmpty()) return ret;
        return Build.SOC_MODEL;
    }
}
