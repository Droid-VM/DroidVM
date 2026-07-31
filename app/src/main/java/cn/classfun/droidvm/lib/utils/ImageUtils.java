// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.utils;

import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.RunUtils.runListQuiet;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

public final class ImageUtils {
    private ImageUtils() {
    }

    @NonNull
    public static JSONObject getImageInfo(String path) throws JSONException {
        var result = runListQuiet(
            getPrebuiltBinaryPath("qemu-img"),
            "info", "--output=json", path
        );
        if (!result.isSuccess()) {
            result.printLog("qemu-img");
            throw new RuntimeException(fmt(
                "Failed to get image info with %d",
                result.getCode()
            ));
        }
        return new JSONObject(result.getOutString());
    }

    @NonNull
    public static JSONObject getImageCheck(String path) throws JSONException {
        var result = runListQuiet(
            getPrebuiltBinaryPath("qemu-img"),
            "check", "--output=json", path
        );
        // qemu-img check still writes its JSON report on non-zero exits
        // (leaks/corruptions), so parse whatever it produced rather than
        // gating on isSuccess(); a missing/garbage body throws.
        var out = result.getOutString();
        if (out == null || out.isEmpty())
            throw new JSONException("qemu-img check produced no output");
        return new JSONObject(out);
    }

    /**
     * Whether {@code path} actually stores compressed clusters, per
     * {@code qemu-img check}'s {@code compressed-clusters} count. This is the
     * real signal: the qcow2 header's {@code compression-type} reads "zlib" for
     * every v3 image (even ones with no compressed data), so it cannot tell an
     * uncompressed disk from a compressed one. Unlike lbx, qemu-img reads both
     * zlib and zstd images, so a zstd disk is detected too. Any detection
     * failure (raw images reject {@code check}, etc.) returns {@code false} --
     * an undetectable image is treated as uncompressed.
     */
    public static boolean hasCompressedClusters(String path) {
        try {
            return getImageCheck(path).optLong("compressed-clusters", 0) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The image's effective compression as qemu names it: {@code "none"} unless the image
     * actually stores compressed clusters (see {@link #hasCompressedClusters}); the qcow2
     * header's {@code compression-type} then picks {@code "zlib"} vs {@code "zstd"} (that header
     * field alone can't - it reads "zlib" for every v3 image). Detection failures return
     * {@code "none"}: an undetectable image is treated as uncompressed.
     */
    @NonNull
    public static String detectCompression(String path) {
        try {
            if (!hasCompressedClusters(path)) return "none";
            var info = getImageInfo(path);
            var fmtSpecific = info.optJSONObject("format-specific");
            var data = fmtSpecific == null ? null : fmtSpecific.optJSONObject("data");
            var type = data == null ? "" : data.optString("compression-type", "");
            return "zstd".equals(type) ? "zstd" : "zlib";
        } catch (Exception e) {
            return "none";
        }
    }
}
