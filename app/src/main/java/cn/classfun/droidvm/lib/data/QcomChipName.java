package cn.classfun.droidvm.lib.data;

import static cn.classfun.droidvm.lib.utils.RunUtils.runListQuiet;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.Map;

import cn.classfun.droidvm.R;

public final class QcomChipName {
    private static final String TAG = "QcomChipName";
    private final Context ctx;
    private Map<String, String> chips = null;

    public QcomChipName(Context ctx) {
        this.ctx = ctx;
        loadChipName();
    }

    private void loadChipName() {
        this.chips = new HashMap<>();
        try (XmlResourceParser parser = ctx.getResources().getXml(R.xml.qcom)) {
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
