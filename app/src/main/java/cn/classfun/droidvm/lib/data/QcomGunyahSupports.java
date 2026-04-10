package cn.classfun.droidvm.lib.data;

import static cn.classfun.droidvm.lib.utils.AssetUtils.loadYAMLFromAssets;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class QcomGunyahSupports {
    private static final String TAG = "QcomGunyahSupports";
    private static final String GUNYAH_ASSET = "data/gunyah.yaml";
    private final Map<String, Map<String, Boolean>> socCapabilities = new HashMap<>();
    private final Context ctx;

    public QcomGunyahSupports(Context ctx) {
        this.ctx = ctx;
        loadGunyahSupports();
    }

    @SuppressWarnings("unchecked")
    private void loadGunyahSupports() {
        try {
            Map<String, Object> root = loadYAMLFromAssets(ctx, GUNYAH_ASSET);
            if (root == null) return;
            var entries = (List<Map<String, Object>>) root.get("gunyah");
            if (entries == null) return;
            for (var entry : entries) {
                var socs = (List<String>) entry.get("socs");
                var caps = (Map<String, Object>) entry.get("capabilities");
                if (socs == null || caps == null) continue;
                var parsed = new HashMap<String, Boolean>();
                for (var cap : caps.entrySet()) {
                    var val = cap.getValue();
                    boolean enabled;
                    if (val instanceof Boolean) {
                        enabled = (Boolean) val;
                    } else {
                        var s = String.valueOf(val).trim().toLowerCase();
                        enabled = s.equals("yes") || s.equals("true");
                    }
                    parsed.put(cap.getKey(), enabled);
                }
                for (var soc : socs)
                    socCapabilities.put(soc.trim().toUpperCase(), parsed);
            }
        } catch (Exception e) {
            Log.e(TAG, fmt("Failed to load %s", GUNYAH_ASSET), e);
        }
    }

    public boolean isCapacitySupported(String soc, String capacity) {
        if (soc == null || capacity == null) return false;
        var caps = socCapabilities.get(soc.trim().toUpperCase());
        if (caps == null) return false;
        var val = caps.get(capacity);
        return val != null && val;
    }

    public boolean isGunyahSupported(String soc) {
        return isCapacitySupported(soc, "gunyah");
    }
}
