// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.main.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayInputStream;

import cn.classfun.droidvm.R;

/**
 * Shows a module's "why is this needed" page ({@link KernelModuleDescriptions}) in a WebView, so
 * the explanation can use real layout and inline SVG rather than the handful of tags a TextView
 * understands.
 *
 * <p>The page arrives as one self-contained document -- stylesheet inlined, theme and language
 * already resolved -- so the WebView has nothing to fetch. Everything that could fetch is
 * therefore turned off: no JavaScript, no file or content access, network loads blocked, and any
 * request that still gets issued is answered with nothing.
 */
final class KernelModuleWhyDialog {
    /** Tallest the page gets before it scrolls, as a fraction of the screen. */
    private static final float MAX_HEIGHT_FRACTION = 0.62f;

    private KernelModuleWhyDialog() {
    }

    @SuppressLint("SetJavaScriptEnabled") // turning it OFF; lint flags the setter either way
    static void show(@NonNull Context ctx, @NonNull String title, @NonNull String html) {
        var content = LayoutInflater.from(ctx).inflate(R.layout.dialog_module_descr, null);
        WebView web = content.findViewById(R.id.descr_web);

        var s = web.getSettings();
        s.setJavaScriptEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);
        s.setGeolocationEnabled(false);
        // The page is authored for this width; don't let the WebView zoom out to a desktop one.
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);

        // A WebView starts white, which flashes against a dark dialog before the page paints.
        web.setBackgroundColor(MaterialColors.getColor(content,
            com.google.android.material.R.attr.colorSurface));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                return new WebResourceResponse("text/plain", "utf-8",
                    new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // these pages have no links; never navigate away from the document
            }
        });

        var lp = web.getLayoutParams();
        lp.height = (int) (ctx.getResources().getDisplayMetrics().heightPixels
            * MAX_HEIGHT_FRACTION);
        web.setLayoutParams(lp);

        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);

        new MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener(d -> web.destroy())
            .show();
    }
}
