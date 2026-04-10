package cn.classfun.droidvm.lib.utils;

import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.RunUtils.runListQuiet;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.StringUtils.streamToString;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.app.Activity;
import android.content.Context;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import cn.classfun.droidvm.lib.crypt.HashFile;

@SuppressWarnings({"unused", "BooleanMethodIsAlwaysInverted", "UnusedReturnValue"})
public final class FileUtils {
    private static final String TAG = "FileUtils";
    private static final int MAX_NAME_LENGTH = 256;

    private FileUtils() {
    }

    public static boolean checkFileName(@Nullable String name) {
        final char[] invalids = {
            '\0', '\\', '/', ':', '*', '?', '"', '<', '>', '|'
        };
        if (name == null) return false;
        if (name.isEmpty()) return false;
        if (name.length() > MAX_NAME_LENGTH) return false;
        if (name.startsWith("@")) return false;
        if (name.startsWith(".")) return false;
        if (name.startsWith(" ") || name.endsWith(" ")) return false;
        if (name.endsWith(".")) return false;
        for (char c : invalids)
            if (name.indexOf(c) >= 0) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) return false;
        }
        return true;
    }

    public static boolean checkFilePath(@Nullable String path, boolean abs) {
        final char[] invalids = {
            '\0', '\\', ':', '*', '?', '"', '<', '>', '|'
        };
        if (path == null) return false;
        if (path.isEmpty()) return false;
        if (abs && !path.startsWith("/")) return false;
        if (path.endsWith("/")) return false;
        if (path.startsWith("/../")) return false;
        if (path.contains("/../")) return false;
        if (path.contains("/./")) return false;
        if (path.contains("//")) return false;
        if (path.endsWith("/..")) return false;
        if (path.endsWith("/.")) return false;
        if (path.equals("..") || path.equals(".")) return false;
        for (char c : invalids)
            if (path.indexOf(c) >= 0) return false;
        return true;
    }

    @NonNull
    public static JSONObject loadJSONFile(
        @NonNull File parent,
        @NonNull String child
    ) throws IOException, JSONException {
        return loadJSONFile(new File(parent, child));
    }

    @NonNull
    public static JSONObject loadJSONFile(
        @NonNull String parent,
        @NonNull String child
    ) throws IOException, JSONException {
        return loadJSONFile(new File(parent, child));
    }

    @NonNull
    public static JSONObject loadJSONFile(
        @NonNull String path
    ) throws IOException, JSONException {
        return loadJSONFile(new File(path));
    }

    @NonNull
    public static JSONObject loadJSONFile(
        @NonNull File path
    ) throws IOException, JSONException {
        return new JSONObject(readFile(path));
    }

    public static void saveJSONFile(
        @NonNull String path,
        @NonNull JSONObject obj
    ) throws IOException, JSONException {
        saveJSONFile(new File(path), obj);
    }

    public static void saveJSONFile(
        @NonNull String parent,
        @NonNull String child,
        @NonNull JSONObject obj
    ) throws IOException, JSONException {
        saveJSONFile(new File(parent, child), obj);
    }

    public static void saveJSONFile(
        @NonNull File path,
        @NonNull JSONObject obj
    ) throws IOException, JSONException {
        writeFile(path, obj.toString(4));
    }

    @NonNull
    public static String readFile(
        @NonNull File file
    ) throws IOException {
        try (var input = new FileInputStream(file)) {
            return streamToString(input);
        }
    }

    @NonNull
    public static String readFile(
        @NonNull String file
    ) throws IOException {
        try (var input = new FileInputStream(file)) {
            return streamToString(input);
        }
    }

    public static void writeFile(
        @NonNull File file,
        @NonNull String data
    ) throws IOException {
        try (var out = new FileOutputStream(file)) {
            out.write(data.getBytes());
            out.flush();
        }
    }

    public static void writeFile(
        @NonNull String file,
        @NonNull String data
    ) throws IOException {
        try (var out = new FileOutputStream(file)) {
            out.write(data.getBytes());
            out.flush();
        }
    }

    @NonNull
    public static String calcHashForStream(
        @NonNull InputStream stream,
        @NonNull String algo
    ) throws NoSuchAlgorithmException, IOException {
        MessageDigest digest = MessageDigest.getInstance(algo);
        byte[] buf = new byte[65536];
        int len;
        while ((len = stream.read(buf)) > 0)
            digest.update(buf, 0, len);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest())
            sb.append(fmt("%02x", b));
        return sb.toString();
    }

    @NonNull
    public static String calcHashForFile(
        @NonNull String path,
        @NonNull String algo
    ) throws NoSuchAlgorithmException, IOException {
        return calcHashForFile(new File(path), algo);
    }

    @NonNull
    public static String calcHashForFile(
        @NonNull File file,
        @NonNull String algo
    ) throws NoSuchAlgorithmException, IOException {
        try (var stream = new FileInputStream(file)) {
            return calcHashForStream(stream, algo);
        }
    }

    @NonNull
    public static String calcHashForAsset(
        @NonNull Context context,
        @NonNull String assetPath,
        @NonNull String algo
    ) throws NoSuchAlgorithmException, IOException {
        try (var stream = context.getAssets().open(assetPath)) {
            return calcHashForStream(stream, algo);
        }
    }

    @NonNull
    public static String getFileHash(@NonNull Context context, @NonNull String relPath, boolean calc) {
        var file = new File(context.getDataDir(), relPath);
        try {
            final var hash = new HashFile(context.getDataDir(), "hash.json");
            return hash.lookupSHA256(relPath);
        } catch (Exception ignored) {
        }
        if (calc) try {
            var hash = calcHashForFile(file, "SHA-256");
            Log.d(TAG, fmt("Calculated hash for file %s: %s", file.getAbsolutePath(), hash));
            return hash;
        } catch (Exception ignored) {
        }
        throw new RuntimeException(fmt("Failed to get file hash for %s", relPath));
    }

    public static void copyStream(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int len;
        while ((len = in.read(buf)) > 0)
            out.write(buf, 0, len);
        out.flush();
    }

    @NonNull
    public static List<String> createFileCommandList(@NonNull List<String> paths, int maxLen) {
        var items = new ArrayList<String>();
        var sb = new StringBuilder();
        for (var item : paths) {
            sb.append(" ");
            sb.append(escapedString(item));
            if (sb.length() > maxLen) {
                items.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0)
            items.add(sb.toString());
        return items;
    }

    public static void chownForeach(@NonNull List<String> path, int uid, int gid) {
        for (var p : createFileCommandList(path, 4000)) {
            runList("chown", String.valueOf(uid), p);
            runList("chgrp", String.valueOf(gid), p);
        }
    }

    public static @Nullable String findExecute(@NonNull String name, @Nullable String def) {
        if (name.contains("/")) return name;
        var sysPath = System.getenv("PATH");
        if (sysPath == null) return def;
        for (var path : sysPath.split(":")) {
            var f = new File(pathJoin(path, name));
            if (f.isFile() && f.canExecute())
                return f.getAbsolutePath();
        }
        return def;
    }

    public static @NonNull String findExecute(@NonNull String name) {
        var result = findExecute(name, null);
        if (result == null)
            throw new RuntimeException(fmt("Failed to find executable: %s", name));
        return result;
    }

    public static boolean shellCheckExists(@NonNull String path) {
        return runListQuiet("test", "-e", path).isSuccess();
    }

    @NonNull
    public static String shellReadFile(@NonNull String path) {
        var result = runListQuiet("cat", path);
        if (!result.isSuccess()) {
            result.printLog("readFileInRoot");
            throw new RuntimeException(fmt(
                "Failed to read file in root: %s, code: %d",
                path, result.getCode()
            ));
        }
        return result.getOutString();
    }

    public static void shellRemoveTree(@NonNull String path) {
        var result = runList("rm", "-rf", path);
        if (result.isSuccess()) return;
        result.printLog("shellRemoveTree");
        throw new RuntimeException(fmt(
            "Failed to remove tree in root: %s, code: %d",
            path, result.getCode()
        ));
    }

    public static void shellAsyncCheckExists(
        @NonNull Activity activity,
        @NonNull String path,
        @NonNull Consumer<Boolean> callback
    ) {
        runOnPool(() -> {
            var exists = shellCheckExists(path);
            activity.runOnUiThread(() -> callback.accept(exists));
        });
    }

    @NonNull
    public static String externalPath() {
        return Environment.getExternalStorageDirectory().getPath();
    }

    public static boolean deleteFile(@NonNull String path) {
        try {
            return new File(path).delete();
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to delete file: %s", path), e);
            return false;
        }
    }

    public static boolean deleteFile(@NonNull File path) {
        try {
            return path.delete();
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to delete file: %s", path), e);
            return false;
        }
    }
}
