// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.data;

import static cn.classfun.droidvm.lib.utils.RunUtils.runListQuiet;

import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * Who made this device's SoC, as a stable token, plus its model string.
 *
 * <p>Used to decide which host kernel modules even apply here: a Gunyah module is meaningless on a
 * MediaTek phone, and a future MediaTek module would be meaningless on a Snapdragon one. The token
 * is the vocabulary the module match rules are written against, so it must stay stable ({@code
 * qualcomm}, {@code mediatek}, {@code google}, {@code samsung}, {@code unisoc}, {@code hisilicon},
 * or {@code unknown}) even as the detection below grows more fallbacks.
 *
 * <p>Detection starts with the framework's own answer, which needs no shell and no root, and only
 * then falls back to properties and {@code /proc/cpuinfo} -- vendors do leave {@code
 * ro.soc.manufacturer} unset. Results are cached: an SoC does not change under a running process.
 */
public final class SocIdentity {
    private static final String TAG = "SocIdentity";

    public static final String QUALCOMM = "qualcomm";
    public static final String MEDIATEK = "mediatek";
    public static final String GOOGLE = "google";
    public static final String SAMSUNG = "samsung";
    public static final String UNISOC = "unisoc";
    public static final String HISILICON = "hisilicon";
    public static final String UNKNOWN = "unknown";

    private static String vendor;
    private static String model;

    private SocIdentity() {
    }

    /** Vendor token for this device, never null. May run a shell: call off the main thread. */
    @NonNull
    public static synchronized String vendor() {
        if (vendor == null) {
            vendor = detectVendor();
            Log.i(TAG, "SoC vendor: " + vendor + " (model " + model() + ")");
        }
        return vendor;
    }

    /** Raw SoC model (e.g. "SM8650", "MT6989", "gs201"), or "" when nothing reports one. */
    @NonNull
    public static synchronized String model() {
        if (model == null) {
            var m = QcomChipName.getCurrentSoC();  // falls back to Build.SOC_MODEL
            model = m == null ? "" : m.trim();
        }
        return model;
    }

    @NonNull
    private static String detectVendor() {
        var fromBuild = fromName(Build.SOC_MANUFACTURER);
        if (!UNKNOWN.equals(fromBuild)) return fromBuild;

        var fromProp = fromName(prop("ro.soc.manufacturer"));
        if (!UNKNOWN.equals(fromProp)) return fromProp;

        // QTI-only property: its mere presence identifies the vendor.
        if (!prop("ro.vendor.qti.soc_model").isEmpty()) return QUALCOMM;

        var hw = prop("ro.hardware").toLowerCase(Locale.ROOT);
        if (hw.equals("qcom") || hw.startsWith("qcom")) return QUALCOMM;
        if (hw.startsWith("mt")) return MEDIATEK;

        var fromCpuinfo = fromName(hardwareLine());
        if (!UNKNOWN.equals(fromCpuinfo)) return fromCpuinfo;

        // Last resort: the model string's own family prefix.
        var m = model().toUpperCase(Locale.ROOT);
        if (m.matches("^(SM|SDM|QCS|QCM|MSM|APQ)\\d.*")) return QUALCOMM;
        if (m.startsWith("MT")) return MEDIATEK;
        if (m.startsWith("GS") || m.startsWith("ZUMA")) return GOOGLE;
        if (m.startsWith("EXYNOS") || m.startsWith("S5E")) return SAMSUNG;
        return UNKNOWN;
    }

    /** Map whatever a vendor calls itself onto our token. */
    @NonNull
    private static String fromName(String raw) {
        if (raw == null) return UNKNOWN;
        var s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("unknown")) return UNKNOWN;
        if (s.contains("qualcomm") || s.equals("qti") || s.contains("qti ")) return QUALCOMM;
        if (s.contains("mediatek") || s.contains("mtk")) return MEDIATEK;
        if (s.contains("google")) return GOOGLE;
        if (s.contains("samsung") || s.contains("exynos")) return SAMSUNG;
        if (s.contains("unisoc") || s.contains("spreadtrum")) return UNISOC;
        if (s.contains("hisilicon") || s.contains("kirin") || s.contains("huawei"))
            return HISILICON;
        return UNKNOWN;
    }

    @NonNull
    private static String prop(@NonNull String key) {
        try {
            return runListQuiet("getprop", key).getOutString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** The {@code Hardware :} line of /proc/cpuinfo, which often names the vendor outright. */
    @NonNull
    private static String hardwareLine() {
        try {
            var r = runListQuiet("grep", "-m1", "^Hardware", "/proc/cpuinfo");
            return r.getOutString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}
