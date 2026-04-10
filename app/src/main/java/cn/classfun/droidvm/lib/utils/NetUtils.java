package cn.classfun.droidvm.lib.utils;

import static org.yaml.snakeyaml.util.UriEncoder.encode;
import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.FileUtils.deleteFile;
import static cn.classfun.droidvm.lib.utils.FileUtils.readFile;
import static cn.classfun.droidvm.lib.utils.FileUtils.writeFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.StringUtils.streamToString;

import android.os.Build;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import cn.classfun.droidvm.BuildConfig;

public final class NetUtils {
    private static final String TAG = "NetUtils";
    private static final Random random = new Random();
    private static final int PORT_RANGE_START = 12000;
    private static final int PORT_RANGE_END = 13000;
    public static final String LXC_USER_AGENT = "Incus 6.23 (Android)";
    public static final String DL_USER_AGENT = "Image Downloader (Android, DroidVM)";
    public static final String USER_AGENT = fmt(
        "DroidVM/v%s(r%d/%s) (Android %s; Device %s %s %s %s; SoC %s %s)",
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
        BuildConfig.BUILD_TYPE,
        Build.VERSION.RELEASE,
        Build.MANUFACTURER,
        Build.BRAND,
        Build.MODEL,
        Build.PRODUCT,
        Build.SOC_MANUFACTURER,
        Build.SOC_MODEL
    );
    public static final String BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; ARM64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/145.0.0.0 Safari/537.36";

    private NetUtils() {
    }

    @NonNull
    public static String formatGMTDate(@NonNull Date date) {
        var sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        return sdf.format(date);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static String fetchString(
        @NonNull String url,
        @NonNull String userAgent
    ) throws IOException {
        Log.i(TAG, fmt("Request %s", url));
        var key = Base64.encodeToString(
            url.getBytes(StandardCharsets.UTF_8),
            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
        );
        var cacheDir = new File(pathJoin(DATA_DIR, "cache", "http_cache"));
        if (!cacheDir.exists() && !cacheDir.mkdirs())
            Log.w(TAG, "Failed to create cache directory");
        var cacheFile = new File(cacheDir, key);
        boolean exists = cacheFile.exists() && cacheFile.isFile();
        var conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", userAgent);
            if (exists) {
                var gmt = formatGMTDate(new Date(cacheFile.lastModified()));
                conn.setRequestProperty("If-Modified-Since", gmt);
            }
            int code = conn.getResponseCode();
            if (code == 304 && exists) {
                Log.i(TAG, fmt("Cache hit for %s", url));
                return readFile(cacheFile);
            }
            if (code == 204) return "";
            if (code != 200) throw new HttpException(code);
            try (var in = conn.getInputStream()) {
                var data = streamToString(in);
                var modified = conn.getLastModified();
                if (modified != 0) {
                    var tmpFile = new File(cacheDir, fmt("%s.tmp", key));
                    try {
                        writeFile(tmpFile, data);
                        if (!tmpFile.setLastModified(modified))
                            Log.w(TAG, fmt("Failed to set last modified for %s", url));
                        deleteFile(cacheFile);
                        if (!tmpFile.renameTo(cacheFile))
                            Log.w(TAG, fmt("Failed to rename cache file for %s", url));
                    } catch (Exception e) {
                        Log.w(TAG, fmt("Failed to write cache for %s", url), e);
                        deleteFile(tmpFile);
                    }
                }
                return data;
            }
        } finally {
            conn.disconnect();
        }
    }

    @NonNull
    @SuppressWarnings("unused")
    public static String fetchString(
        @NonNull String url
    ) throws IOException {
        return fetchString(url, USER_AGENT);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static JSONObject fetchJSON(
        @NonNull String url,
        @NonNull String userAgent
    ) throws IOException, JSONException {
        return new JSONObject(fetchString(url, userAgent));
    }

    @NonNull
    @SuppressWarnings("unused")
    public static JSONObject fetchJSON(
        @NonNull String url
    ) throws IOException, JSONException {
        return fetchJSON(url, USER_AGENT);
    }

    public static class HttpException extends IOException {
        private final int code;

        public HttpException(int code) {
            super(fmt("HTTP error %d", code));
            this.code = code;
        }

        @SuppressWarnings("unused")
        public HttpException(int code, @NonNull String message) {
            super(fmt("HTTP error %d: %s", code, message));
            this.code = code;
        }

        @SuppressWarnings("unused")
        public int getCode() {
            return code;
        }
    }

    @NonNull
    public static String extractFileName(@Nullable String disposition, String urlStr) {
        if (disposition != null && !disposition.isEmpty()) {
            var cd = Pattern.compile("filename\\*?=[\"']?(?:UTF-8'')?([^\"';\\s]+)");
            var m = cd.matcher(disposition);
            if (m.find()) {
                var name = m.group(1);
                if (name != null && !name.isEmpty())
                    return sanitizeFileName(name);
            }
        }
        try {
            var path = new URL(urlStr).getPath();
            if (path != null && !path.isEmpty())
                return sanitizeFileName(basename(path));
        } catch (Exception ignored) {
        }
        return "download.bin";
    }

    @NonNull
    public static String sanitizeFileName(String name) {
        try {
            name = URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        name = name.replace("\"", "").replace("'", "");
        name = name.replaceAll("[\\\\/:*?<>|]", "_");
        return name.trim();
    }

    @NonNull
    public static String generateRandomMac() {
        var bytes = new byte[6];
        random.nextBytes(bytes);
        bytes[0] = 0x02;
        return fmt(
            "%02x:%02x:%02x:%02x:%02x:%02x",
            bytes[0], bytes[1], bytes[2],
            bytes[3], bytes[4], bytes[5]
        );
    }

    @NonNull
    public static String stripScopeId(@NonNull String addr) {
        int i = addr.indexOf('%');
        return i > 0 ? addr.substring(0, i) : addr;
    }

    @NonNull
    public static String getLocalAddressV4() {
        try (var socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress("1.1.1.1", 53));
            var addr = socket.getLocalAddress();
            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                var host = addr.getHostAddress();
                if (host != null) return host;
            }
        } catch (Exception ignored) {
        }
        try {
            var ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                var ni = ifaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                var addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        var host = addr.getHostAddress();
                        if (host != null) return host;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    @NonNull
    public static String getLocalAddressV6() {
        try (var socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress("2606:4700:4700::1111", 53));
            var addr = socket.getLocalAddress();
            if (addr instanceof Inet6Address && !addr.isLoopbackAddress()) {
                var raw = addr.getHostAddress();
                if (raw != null) return stripScopeId(raw);
            }
        } catch (Exception ignored) {
        }
        try {
            var ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                var ni = ifaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                var addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();
                    if (addr instanceof Inet6Address
                        && !addr.isLoopbackAddress()
                        && !addr.isLinkLocalAddress()) {
                        var raw = addr.getHostAddress();
                        if (raw != null) return stripScopeId(raw);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "::1";
    }

    @NonNull
    public static String resolveAddress(@Nullable String host, boolean local) {
        if (host == null)
            host = "127.0.0.1";
        if (host.equals("::"))
            host = local ? "::1" : getLocalAddressV6();
        if (host.equals("0.0.0.0"))
            host = local ? "127.0.0.1" : getLocalAddressV4();
        host = encode(host);
        if (host.contains(":"))
            host = fmt("[%s]", host);
        return host;
    }

    public static boolean isPortAvailable(int port) {
        try (var ss = new ServerSocket()) {
            ss.setReuseAddress(false);
            ss.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @NonNull
    public static String buildUrlQuery(@NonNull Map<String, String> params) {
        var sb = new StringBuilder();
        var first = new AtomicBoolean(true);
        params.forEach((k, v) -> {
            if (first.get()) first.set(false);
            else sb.append("&");
            sb.append(k);
            if (v != null && ! v.isEmpty())
                sb.append("=").append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    public static int generateRandomAvailablePort() {
        var ports = new ArrayList<Integer>();
        for (int p = PORT_RANGE_START; p <= PORT_RANGE_END; p++)
            ports.add(p);
        Collections.shuffle(ports, new SecureRandom());
        for (int port : ports) {
            if (isPortAvailable(port)) {
                Log.i(TAG, fmt("Selected available port: %d", port));
                return port;
            }
        }
        Log.e(TAG, fmt("No available port found in range %d-%d", PORT_RANGE_START, PORT_RANGE_END));
        return -1;
    }
}
