package cn.classfun.droidvm.lib.utils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Base64.getDecoder;
import static java.util.Base64.getEncoder;
import static cn.classfun.droidvm.lib.utils.FileUtils.copyStream;
import static cn.classfun.droidvm.lib.utils.FileUtils.externalPath;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Formatter;
import java.util.Locale;

public final class StringUtils {
    private StringUtils() {
    }

    @NonNull
    public static String formatDuration(long seconds) {
        if (seconds < 0) return "?";
        if (seconds < 60)
            return fmt("0:%02d", seconds);
        if (seconds < 3600)
            return fmt("%d:%02d", seconds / 60, seconds % 60);
        return fmt("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    @NonNull
    public static String getEditText(@NonNull EditText editText) {
        return editText.getText() != null ? editText.getText().toString().trim() : "";
    }

    @NonNull
    public static String stripExtension(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    @Nullable
    private static String getRealPathFromURI(@NonNull Context context, Uri uri) {
        String[] projection = {"_data"};
        var res = context.getContentResolver();
        try (var cursor = res.query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndexOrThrow("_data");
                return cursor.getString(index);
            }
        }
        return null;
    }

    public static String resolveUriPath(Context ctx, Uri uri) {
        var extStorage = externalPath();
        try {
            var real = getRealPathFromURI(ctx, uri);
            if (real != null && shellCheckExists(real)) return real;
        } catch (Exception ignored) {
        }
        try {
            var docId = DocumentsContract.getDocumentId(uri);
            if (docId != null && docId.startsWith("primary:"))
                return pathJoin(extStorage, docId.substring("primary:".length()));
        } catch (Exception ignored) {
        }
        try {
            var treeId = DocumentsContract.getTreeDocumentId(uri);
            if (treeId != null && treeId.startsWith("primary:"))
                return pathJoin(extStorage, treeId.substring("primary:".length()));
        } catch (Exception ignored) {
        }
        var path = uri.getPath();
        if (path != null && path.startsWith("/document/primary:"))
            return pathJoin(extStorage, path.substring("/document/primary:".length()));
        if (path != null && path.startsWith("/external_files/"))
            return pathJoin(extStorage, path.substring("/external_files/".length()));
        if (path != null && path.startsWith("/tree/primary:")) {
            var sub = path.substring("/tree/primary:".length());
            int slash = sub.indexOf("/document/");
            if (slash >= 0) sub = sub.substring(0, slash);
            return pathJoin(extStorage, sub);
        }
        return path;
    }

    @NonNull
    public static String basename(@NonNull String path) {
        while (path.endsWith("/"))
            path = path.substring(0, path.length() - 1);
        int sep = path.lastIndexOf('/');
        return (sep >= 0) ? path.substring(sep + 1) : path;
    }

    @NonNull
    public static String dirname(@NonNull String path) {
        while (path.endsWith("/"))
            path = path.substring(0, path.length() - 1);
        int sep = path.lastIndexOf('/');
        return (sep >= 0) ? path.substring(0, sep) : "";
    }

    @NonNull
    public static String extension(@NonNull String path) {
        int dot = path.lastIndexOf('.');
        return (dot >= 0) ? path.substring(dot + 1) : "";
    }

    @NonNull
    public static String extensionLower(@NonNull String path) {
        return extension(path).toLowerCase(Locale.ROOT);
    }

    @NonNull
    public static String pathJoin(@NonNull String base, @NonNull String child) {
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (child.startsWith("/")) child = child.substring(1);
        return base + "/" + child;
    }

    @NonNull
    public static String pathJoin(@NonNull String base, @NonNull String... children) {
        for (String c : children)
            base = pathJoin(base, c);
        return base;
    }

    @NonNull
    public static String pathJoin(@NonNull File base, @NonNull String children) {
        return pathJoin(base.getAbsolutePath(), children);
    }

    @NonNull
    public static String pathJoin(@NonNull File base, @NonNull String... children) {
        return pathJoin(base.getAbsolutePath(), children);
    }

    @NonNull
    public static String fmt(String fmt, Object... args) {
        return new Formatter(Locale.ROOT).format(fmt, args).toString();
    }

    @NonNull
    public static String streamToString(@NonNull InputStream input) throws IOException {
        var result = new ByteArrayOutputStream();
        copyStream(input, result);
        return result.toString(UTF_8);
    }

    @NonNull
    public static String generateRandomPassword(int length) {
        final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        var random = new SecureRandom();
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++)
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        return sb.toString();
    }

    @NonNull
    @SuppressWarnings("unused")
    public static String base64Encode(@NonNull byte[] data) {
        return getEncoder().encodeToString(data);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static String base64Encode(@NonNull String data) {
        return base64Encode(data.getBytes(UTF_8));
    }

    @NonNull
    @SuppressWarnings("unused")
    public static byte[] base64DecodeToBytes(@NonNull String data) {
        return getDecoder().decode(data);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static String base64DecodeToString(@NonNull String data) {
        return new String(base64DecodeToBytes(data), UTF_8);
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    @NonNull
    public static String urlEncodeBytesAll(@NonNull byte[] data, int off, int len) {
        var chars = new char[len * 3];
        for (int i = 0, j = 0; i < len; i++) {
            int b = data[off + i] & 0xFF;
            chars[j++] = '%';
            chars[j++] = HEX[b >>> 4];
            chars[j++] = HEX[b & 0x0F];
        }
        return new String(chars);
    }

    @NonNull
    public static String urlEncodeBytesAll(@NonNull byte[] data) {
        return urlEncodeBytesAll(data, 0, data.length);
    }
}
