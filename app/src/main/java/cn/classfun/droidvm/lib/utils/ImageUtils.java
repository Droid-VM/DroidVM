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
}
