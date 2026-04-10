package cn.classfun.droidvm.lib.utils;

import static android.os.Build.SUPPORTED_ABIS;
import static cn.classfun.droidvm.lib.Constants.*;
import static cn.classfun.droidvm.lib.utils.FileUtils.*;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import cn.classfun.droidvm.lib.crypt.HashFile;

public final class AssetUtils {
    private static final String TAG = "AssetsUtils";
    private static String prebuiltAsset = null;
    private static String prebuiltHash = null;

    private AssetUtils() {
    }

    @NonNull
    private static String getPrebuiltAsset(@NonNull Context context) {
        if (prebuiltAsset != null) return prebuiltAsset;
        for (var abi : SUPPORTED_ABIS) {
            var name = fmt("prebuilts/prebuilt-%s.7z", abi);
            if (checkAssetsExists(context, name)) {
                prebuiltAsset = name;
                return name;
            }
        }
        throw new RuntimeException("No prebuilt asset found for supported ABIs");
    }

    @NonNull
    private static String getPrebuiltHash(@NonNull Context context) {
        if (prebuiltHash != null) return prebuiltHash;
        for (var abi : SUPPORTED_ABIS) {
            var name = fmt("prebuilts/prebuilt-%s.json", abi);
            if (checkAssetsExists(context, name)) {
                prebuiltHash = name;
                return name;
            }
        }
        throw new RuntimeException("No prebuilt asset found for supported ABIs");
    }

    @Nullable
    public static <T> T loadYAMLFromAssets(
        @NonNull Context context,
        @NonNull String assetName
    ) throws IOException {
        try (var is = context.getAssets().open(assetName)) {
            var yaml = new Yaml();
            return yaml.load(is);
        }
    }

    @NonNull
    public static JSONObject loadJSONFromAssets(
        @NonNull Context context,
        @NonNull String assetName
    ) throws IOException, JSONException {
        return new JSONObject(loadFromAssets(context, assetName));
    }

    @NonNull
    public static String loadFromAssets(
        @NonNull Context context,
        @NonNull String assetName
    ) throws IOException {
        try (var is = context.getAssets().open(assetName)) {
            return new String(is.readAllBytes());
        }
    }

    public static boolean checkAssetsExists(
        @NonNull Context context,
        @NonNull String assetName
    ) {
        try {
            context.getAssets().open(assetName).close();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @NonNull
    private static String getAssetHash(
        @NonNull Context context,
        @NonNull String file,
        boolean hashFile
    ) {
        if (hashFile) try {
            var hash = new HashFile(loadJSONFromAssets(context, "hash.json"));
            return hash.lookupSHA256(file);
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to load assets hash.json for %s", file), e);
        }
        try {
            String hash = calcHashForAsset(context, file, "SHA-256");
            Log.i(TAG, fmt("Calculated hash for asset %s: %s", file, hash));
            return hash;
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to calculate hash for asset: %s", file), e);
        }
        throw new RuntimeException(fmt("Failed to get asset hash for %s", file));
    }

    private static boolean needsExtract(
        @NonNull Context context,
        @NonNull String from,
        @NonNull String to,
        boolean hashFile
    ) {
        var file = new File(context.getDataDir(), to);
        if (!file.exists()) return true;
        try {
            var expectedHash = getAssetHash(context, from, hashFile);
            var currentHash = getFileHash(context, to, false);
            return !currentHash.equalsIgnoreCase(expectedHash);
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean needsExtract(
        @NonNull Context context,
        @NonNull String name,
        boolean hashFile,
        @NonNull String type
    ) {
        return needsExtract(
            context,
            pathJoin(type, SUPPORTED_ABIS[0], name),
            pathJoin(type, name),
            hashFile
        );
    }

    public static void extractAsset(
        @NonNull Context context,
        String type,
        String[] targets,
        boolean hashFile
    ) {
        final var tgtDir = new File(context.getDataDir(), type);
        if (!tgtDir.exists() && tgtDir.mkdirs())
            Log.d(TAG, fmt("Created %s directory for %s", type, tgtDir));
        final var localHashFile = new File(context.getDataDir(), "hash.json");
        final var localHash = HashFile.loadOrCreate(localHashFile);
        for (var name : targets) {
            if (!needsExtract(context, name, hashFile, type)) {
                Log.d(TAG, fmt("%s %s is up to date, skipping extraction", type, name));
                continue;
            }
            final var tgtAsset = pathJoin(type, SUPPORTED_ABIS[0], name);
            final var tgtFile = new File(context.getDataDir(), pathJoin(type, name));
            Log.i(TAG, fmt("Extracting %s %s from asset %s", name, type, tgtAsset));
            if (tgtFile.exists() && tgtFile.delete())
                Log.d(TAG, fmt("Deleted old %s file: %s", type, tgtFile));
            Log.d(TAG, context.getApplicationInfo().nativeLibraryDir);
            try (var in = context.getAssets().open(tgtAsset);
                var out = new FileOutputStream(tgtFile)) {
                copyStream(in, out);
            } catch (IOException e) {
                Log.e(TAG, fmt("Failed to extract: %s", tgtAsset), e);
                continue;
            }
            if (!tgtFile.setExecutable(true, false))
                Log.w(TAG, fmt("Failed to set executable permission on %s", tgtFile));
            var hash = getAssetHash(context, tgtAsset, hashFile);
            localHash.put(pathJoin(type, name), hash);
        }
        try {
            localHash.save(localHashFile);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save local hash.json", e);
        }
    }

    public static void extractBinaries(@NonNull Context context) {
        extractAsset(context, "bin", BINARIES_BUILT, false);
        extractAsset(context, "bin", BINARIES_PREBUILT, true);
    }

    public static void extractLibraries(@NonNull Context context) {
        extractAsset(context, "lib", LIBRARIES_BUILT, false);
    }

    public static boolean needsExtractPrebuilt(@NonNull Context context) {
        var localHashFile = new File(context.getDataDir(), "hash.json");
        if (!localHashFile.exists()) return true;
        try {
            var asset = getPrebuiltAsset(context);
            var hash = getPrebuiltHash(context);
            var localHash = new HashFile(localHashFile);
            var storedHash = localHash.lookupSHA256(asset);
            var assetHash = getAssetHash(context, asset, true);
            if (!storedHash.equalsIgnoreCase(assetHash)) return true;
            var prebuiltHash = new HashFile(loadJSONFromAssets(context, hash));
            for (var item : prebuiltHash.values()) {
                var localFile = new File(context.getDataDir(), item.file);
                if (!localFile.exists()) return true;
                var localHashValue = getFileHash(context, item.file, false);
                if (!localHashValue.equalsIgnoreCase(item.sha256)) {
                    Log.i(TAG, fmt("File %s hash mismatch, needs extraction", item.file));
                    return true;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Prebuilt hash check failed, extraction needed", e);
            return true;
        }
        return false;
    }

    public static void cleanupPrebuilt(@NonNull Context context) {
        var localHashFile = new File(context.getDataDir(), "hash.json");
        if (!localHashFile.exists()) return;
        HashFile localHash = null;
        try {
            localHash = new HashFile(localHashFile);
        } catch (Exception e) {
            Log.d(TAG, "Prebuilt cleanup failed", e);
        }
        if (localHash == null) return;
        var cleanups = new ArrayList<String>();
        for (var item : localHash.values())
            try {
                if (item.source == null || !item.source.equals("prebuilt")) continue;
                var localFile = new File(context.getDataDir(), item.file);
                if (localFile.exists() && localFile.delete())
                    Log.d(TAG, fmt("Deleted prebuilt file: %s", localFile));
                else
                    Log.d(TAG, fmt("Skip delete prebuilt file: %s", localFile));
                cleanups.add(item.file);
            } catch (Exception e) {
                Log.d(TAG, fmt("Failed to delete prebuilt file: %s", item.file), e);
            }
        for (var file : cleanups)
            localHash.remove(file);
        try {
            localHash.save(localHashFile);
        } catch (Exception e) {
            Log.d(TAG, "Failed to save local hash.json after cleanup", e);
        }
    }

    @SuppressLint("DefaultLocale")
    public static void extractPrebuilt(
        @NonNull Context context
    ) throws IOException, JSONException {
        if (!needsExtractPrebuilt(context)) {
            Log.d(TAG, "Prebuilt archive is up to date, skipping extraction");
            return;
        }
        var asset = getPrebuiltAsset(context);
        var hash = getPrebuiltHash(context);
        cleanupPrebuilt(context);
        var prebuiltHash = new HashFile(loadJSONFromAssets(context, hash));
        var p7za = getAssetBinaryPath("7za");
        if (!new File(p7za).exists())
            throw new IOException(fmt("7za binary not found: %s", p7za));
        var tmpFile = new File(context.getCacheDir(), basename(asset));
        Log.i(TAG, fmt("Copying %s to %s", asset, tmpFile.getAbsolutePath()));
        try (var in = context.getAssets().open(asset);
            var out = new FileOutputStream(tmpFile)) {
            copyStream(in, out);
        }
        var dataDir = context.getDataDir().getAbsolutePath();
        Log.i(TAG, fmt("Extracting %s to %s", asset, dataDir));
        var result = RunUtils.runList(
            p7za, "x", "-y",
            fmt("-o%s", dataDir),
            tmpFile.getAbsolutePath()
        );
        result.printLog("7za");
        if (!tmpFile.delete())
            Log.w(TAG, fmt("Failed to delete temp file: %s", tmpFile));
        if (!result.isSuccess())
            throw new IOException(fmt("7za extraction failed (exit %d)", result.getCode()));
        Log.i(TAG, "Prebuilt extraction completed successfully");
        var info = context.getApplicationInfo();
        var usrDir = pathJoin(context.getDataDir(), "usr");
        RunUtils.runList("chown", "-R", String.valueOf(info.uid), usrDir);
        RunUtils.runList("chgrp", "-R", String.valueOf(info.uid), usrDir);
        var usrBinDir = pathJoin(context.getDataDir(), "usr", "bin");
        if (new File(usrBinDir).isDirectory())
            RunUtils.runList("chmod", "-R", "0755", usrBinDir);
        var localHashFile = new File(context.getDataDir(), "hash.json");
        var localHash = HashFile.loadOrCreate(localHashFile);
        try {
            for (var item : prebuiltHash.values())
                localHash.put(item.file, "prebuilt", item.sha256);
            var assetHash = getAssetHash(context, asset, true);
            localHash.put(asset, assetHash);
            localHash.save(localHashFile);
        } catch (Exception e) {
            Log.w(TAG, "Failed to update local hash after prebuilt extraction", e);
        }
    }

    public static boolean isAssetFileExists(@NonNull Context context, @NonNull String assetName) {
        try {
            context.getAssets().open(assetName).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @NonNull
    public static String getAssetBinaryPath(@NonNull String name) {
        return pathJoin(DATA_DIR, "bin", name);
    }

    @NonNull
    public static String getPrebuiltBinaryPath(@NonNull String name) {
        return pathJoin(DATA_DIR, "usr", "bin", name);
    }
}
