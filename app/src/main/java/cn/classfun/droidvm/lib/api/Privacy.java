package cn.classfun.droidvm.lib.api;

import static cn.classfun.droidvm.lib.utils.AssetUtils.loadFromAssets;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

import cn.classfun.droidvm.lib.utils.AssetUtils;

public final class Privacy {
    private static final String TAG = "PrivacyUtils";
    private static JSONObject metadata = null;
    private Privacy() {
    }

    private static SharedPreferences getPreferences(@NonNull Context ctx) {
        return ctx.getSharedPreferences("droidvm_prefs", Context.MODE_PRIVATE);
    }

    @NonNull
    private static JSONObject readPrivacyMetadata(@NonNull Context ctx) {
        if (metadata == null) {
            try {
                metadata = AssetUtils.loadJSONFromAssets(ctx, "privacy/metadata.json");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return metadata;
    }

    public static int getCurrentPrivacyVersion(@NonNull Context ctx) {
        try {
            var meta = readPrivacyMetadata(ctx);
            if (!meta.has("version"))
                throw new RuntimeException("Invalid privacy metadata: missing version");
            return meta.getInt("version");
        } catch (JSONException e) {
            throw new RuntimeException("Invalid privacy metadata", e);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isPrivacyAgreed(@NonNull Context ctx) {
        try {
            var pref = getPreferences(ctx);
            var agreed = pref.getBoolean("privacy_agreed", false);
            var version = pref.getInt("privacy_version", 0);
            return agreed && version >= getCurrentPrivacyVersion(ctx);
        } catch (Exception e) {
            Log.w(TAG, "Failed to check privacy agreement", e);
            return false;
        }
    }

    public static boolean isNeedAskPrivacy(@NonNull Context ctx) {
        try {
            var pref = getPreferences(ctx);
            var version = pref.getInt("privacy_version", 0);
            return version < getCurrentPrivacyVersion(ctx);
        } catch (Exception e) {
            Log.w(TAG, "Failed to check privacy agreement", e);
            return true;
        }
    }

    public static void unsetPrivacyAgreement(@NonNull Context ctx) {
        try {
            var pref = getPreferences(ctx);
            var editor = pref.edit();
            editor.remove("privacy_agreed");
            editor.remove("privacy_version");
            editor.apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to unset privacy agreement", e);
        }
    }

    public static void setPolicyAgreed(@NonNull Context ctx, boolean agreed) {
        try {
            var pref = getPreferences(ctx);
            var editor = pref.edit();
            editor.putBoolean("privacy_agreed", agreed);
            editor.putInt("privacy_version", getCurrentPrivacyVersion(ctx));
            editor.apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to set privacy agreement", e);
        }
    }

    @NonNull
    public static String getPolicyContent(@NonNull Context ctx) {
        try {
            var meta = readPrivacyMetadata(ctx);
            var files = meta.getJSONArray("files");
            var locale = Locale.getDefault();
            var langTag = locale.toLanguageTag();
            var lang = locale.getLanguage();
            String fallbackFile = null;
            for (int i = 0; i < files.length(); i++) {
                var entry = files.getJSONObject(i);
                var name = entry.getString("name");
                var languages = entry.getJSONArray("languages");
                for (int j = 0; j < languages.length(); j++) {
                    var l = languages.getString(j);
                    if (l.equals("default")) {
                        if (fallbackFile == null) fallbackFile = name;
                    } else if (l.equalsIgnoreCase(langTag) || l.equalsIgnoreCase(lang))
                        return loadFromAssets(ctx, fmt("privacy/%s", name));
                }
            }
            if (fallbackFile != null)
                return loadFromAssets(ctx, fmt("privacy/%s", fallbackFile));
            throw new RuntimeException("No matching privacy policy file found");
        } catch (JSONException e) {
            throw new RuntimeException("Invalid privacy metadata", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load privacy policy content", e);
        }
    }
}
