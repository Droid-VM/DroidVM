// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.webrtc;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;

/**
 * Hands out the link to crosvm's WebRTC viewer page for one screen.
 *
 * <p>WebRTC is for viewers on other devices; this phone already has the native display, which
 * skips the encode/decode round trip. So the app shares the link rather than embedding a
 * browser.</p>
 */
public final class WebrtcShareDialog {
    private static final String TAG = "WebrtcShareDialog";

    private WebrtcShareDialog() {
    }

    public static void show(@NonNull Activity activity, @NonNull String vmId, @NonNull String screenId) {
        var main = new Handler(Looper.getMainLooper());
        DaemonConnection.getInstance().buildRequest("vm_webrtc_info")
            .put("vm_id", vmId)
            .put("screen", screenId)
            .onResponse(resp -> {
                // A wildcard bind is reached at any of the phone's own addresses; the phone can be
                // on several networks at once, so each gets a link and the user picks one.
                var hosts = new ArrayList<String>();
                var remote = resp.optJSONArray("remote_hosts");
                if (remote != null)
                    for (int i = 0; i < remote.length(); i++) {
                        var host = remote.optString(i, "");
                        if (!host.isEmpty() && !hosts.contains(host)) hosts.add(host);
                    }
                if (hosts.isEmpty()) {
                    var host = resp.optString("remote_host", "");
                    hosts.add(host.isEmpty() ? resp.optString("host", "127.0.0.1") : host);
                }
                var urls = new ArrayList<String>();
                for (var host : hosts) {
                    var url = viewerUrl(host, resp.optInt("port", -1), resp.optString("token", ""));
                    if (url != null) urls.add(url);
                }
                main.post(() -> {
                    if (urls.isEmpty()) fail(activity, activity.getString(R.string.webrtc_share_failed));
                    else showLinks(activity, urls);
                });
            })
            .onUnsuccessful(resp -> {
                var msg = resp.optString("message", activity.getString(R.string.webrtc_share_failed));
                main.post(() -> fail(activity, msg));
            })
            .onError(e -> {
                Log.e(TAG, "Failed to query WebRTC info", e);
                main.post(() -> fail(activity, activity.getString(R.string.webrtc_share_failed)));
            })
            .invoke();
    }

    /** {@code http://host:port/?token=}, or null for an endpoint the daemon did not assign. */
    @Nullable
    static String viewerUrl(@NonNull String host, int port, @NonNull String token) {
        if (port < 1 || port > 65535 || host.isEmpty()) return null;
        var authority = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        var builder = new Uri.Builder().scheme("http").encodedAuthority(authority).path("/");
        if (!token.isEmpty()) builder.appendQueryParameter("token", token);
        return builder.build().toString();
    }

    /** One row per address; tapping a row copies its link. */
    private static void showLinks(@NonNull Activity activity, @NonNull List<String> urls) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.webrtc_share_title)
            .setItems(urls.toArray(new String[0]), (d, which) -> copy(activity, urls.get(which)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void copy(@NonNull Activity activity, @NonNull String url) {
        var clipboard = activity.getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("WebRTC", url));
        Toast.makeText(activity, R.string.webrtc_share_copied, LENGTH_SHORT).show();
    }

    private static void fail(@NonNull Activity activity, @NonNull String message) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Toast.makeText(activity, message, LENGTH_LONG).show();
    }
}
