// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.main.settings;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import cn.classfun.droidvm.R;

/**
 * The "why is this needed" pages behind each Kernel Module card.
 *
 * <p>A page is one self-contained HTML file per module, carrying <b>every</b> language and no
 * colours of its own; this class picks the language and paints in the live Material palette at
 * display time, by injecting CSS ahead of the page's own stylesheet. That is why the shipped
 * files are neither per-language nor per-theme -- see {@code gunyah_host_mod/descr/}.
 *
 * <p>Pages for the shipped {@code .ko} modules come from {@code usr/lib/modules/descr/}, staged
 * next to the modules themselves; GH-Hugepage-Reserve is a separately installed Magisk module
 * with no prebuilt to ride on, so its page ships in the APK assets. Both are read once into
 * memory ({@link #preload()}, off the main thread) -- they are a few KB each, and the card's
 * why-button must know whether a page exists before it can decide to show itself.
 */
final class KernelModuleDescriptions {
    private static final String DESCR_DIR = pathJoin(DATA_DIR, "usr", "lib", "modules", "descr");
    private static final String ASSET_DIR = "descr";
    private static final String HUGEPAGE_PAGE = "gh_hugepage_reserve";
    /** The app's stylesheet replaces this line; see the build's matching check. */
    private static final Pattern CSS_LINK = Pattern.compile("<link[^>]+style\\.css[^>]*>");
    /** Pages tag their per-language blocks with this; used to test a language is present. */
    private static final String LANG_ATTR = "data-lang=\"%s\"";
    private static final String FALLBACK_LANG = "en";

    /** module-name prefix -> raw page HTML. Populated once by {@link #preload()}. */
    private static final Map<String, String> PAGES = new HashMap<>();
    @Nullable
    private static volatile String css;
    private static volatile boolean loadTried;

    private KernelModuleDescriptions() {
    }

    /** Read the pages and stylesheet once. Does file I/O: call off the main thread. */
    static synchronized void preload(@NonNull Context ctx) {
        if (loadTried) return;
        loadTried = true;
        css = readAsset(ctx, ASSET_DIR + "/style.css");
        var hugepage = readAsset(ctx, ASSET_DIR + "/" + HUGEPAGE_PAGE + ".html");
        if (hugepage != null) PAGES.put(HUGEPAGE_PAGE, hugepage);
        var files = new File(DESCR_DIR).listFiles((d, n) -> n.endsWith(".html"));
        if (files == null) return; // prebuilts not extracted yet -- why-buttons just stay hidden
        for (var f : files) {
            var html = readFile(f);
            if (html != null)
                PAGES.put(f.getName().substring(0, f.getName().length() - 5), html);
        }
    }

    /** Is there a page for this module? Cheap; safe on the main thread after preload. */
    static boolean hasModulePage(@NonNull String moduleName) {
        return matchModule(moduleName) != null;
    }

    static boolean hasHugepagePage() {
        return PAGES.containsKey(HUGEPAGE_PAGE);
    }

    /**
     * Display-ready HTML for a module named as the card names it (normalized .ko basename, e.g.
     * {@code udmabuf_gki_6.6}), or null if no page covers it.
     */
    @Nullable
    static String modulePage(@NonNull Context ctx, @NonNull String moduleName) {
        var raw = matchModule(moduleName);
        return raw == null ? null : prepare(ctx, raw);
    }

    @Nullable
    static String hugepagePage(@NonNull Context ctx) {
        var raw = PAGES.get(HUGEPAGE_PAGE);
        return raw == null ? null : prepare(ctx, raw);
    }

    /**
     * Page whose file name is the longest prefix of this module's name -- so a family page
     * ({@code udmabuf.html}) covers every KMI build of it, and a page named for a specific
     * build would still win over the family one.
     */
    @Nullable
    private static String matchModule(@NonNull String moduleName) {
        String best = null;
        int bestLen = -1;
        for (var e : PAGES.entrySet()) {
            var key = e.getKey();
            if (key.length() > bestLen && !HUGEPAGE_PAGE.equals(key) && moduleName.startsWith(key)) {
                best = e.getValue();
                bestLen = key.length();
            }
        }
        return best;
    }

    /**
     * Swap the page's stylesheet link for: the app's copy of that stylesheet, then the live
     * theme as CSS variables and the rule that reveals one language. Everything the page needs
     * is inside the document, so the WebView loads no subresources at all.
     *
     * <p>Order matters. The app's block comes <b>after</b> the stylesheet because the stylesheet
     * carries a standalone fallback palette (so a page can be proofread in a browser) -- injected
     * first, that fallback would win and the page would ignore the app's real theme.
     */
    @NonNull
    private static String prepare(@NonNull Context ctx, @NonNull String rawHtml) {
        boolean dark = isDark(ctx);
        int ink = dark ? 0xFFE2E2E6 : 0xFF1A1C1E;
        int paper = dark ? 0xFF1A1C1E : 0xFFFDFCFF;
        var injected = "<style>" + (css == null ? "" : css) + "</style>\n"
            + "<style>\n"
            + ":root{\n"
            + colorVar(ctx, "--surface", com.google.android.material.R.attr.colorSurface, paper)
            + colorVar(ctx, "--on-surface", com.google.android.material.R.attr.colorOnSurface, ink)
            + colorVar(ctx, "--on-surface-variant",
                       com.google.android.material.R.attr.colorOnSurfaceVariant, ink)
            + colorVar(ctx, "--surface-variant",
                       com.google.android.material.R.attr.colorSurfaceVariant, paper)
            + colorVar(ctx, "--primary", androidx.appcompat.R.attr.colorPrimary, ink)
            + colorVar(ctx, "--error", androidx.appcompat.R.attr.colorError, ink)
            + colorVar(ctx, "--outline", com.google.android.material.R.attr.colorOutline, ink)
            + fmt("color-scheme:%s;\n}\n", dark ? "dark" : "light")
            + ".i18n{display:none}\n"
            + fmt(".i18n[data-lang=\"%s\"]{display:block}\n", pageLang(ctx, rawHtml))
            // The stylesheet rules a divider between language blocks, for the all-languages
            // browser view. Here only one is shown, and an adjacent-sibling selector still
            // matches across the hidden ones -- leaving a stray rule above the page. Same
            // selector, not a plainer one: two classes outrank one whatever the order.
            + ".i18n + .i18n{margin-top:0;padding-top:0;border-top:none}\n"
            + "</style>";
        var html = CSS_LINK.matcher(rawHtml).replaceFirst(java.util.regex.Matcher
            .quoteReplacement(injected));
        // Structural dark-mode branching for anything colours alone cannot express.
        return html.replaceFirst("<html", fmt("<html data-theme=\"%s\"", dark ? "dark" : "light"));
    }

    /** {@code --name:#rrggbb;} from a theme attribute, so pages match the app exactly. */
    @NonNull
    private static String colorVar(@NonNull Context ctx, @NonNull String name, int attr,
                                   int fallback) {
        int c = MaterialColors.getColor(ctx, attr, fallback);
        return fmt("%s:#%06X;\n", name, c & 0xFFFFFF);
    }

    private static boolean isDark(@NonNull Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * The app language if this page has it, else English. The fallback is for an older prebuilt
     * whose pages predate a language the app has since gained -- the build refuses to ship a
     * page that is missing one.
     */
    @NonNull
    private static String pageLang(@NonNull Context ctx, @NonNull String rawHtml) {
        var want = appLang(ctx);
        return rawHtml.contains(fmt(LANG_ATTR, want)) ? want : FALLBACK_LANG;
    }

    /** Device language as the pages tag it: zh-TW / zh-CN / en. */
    @NonNull
    private static String appLang(@NonNull Context ctx) {
        var loc = ctx.getResources().getConfiguration().getLocales().get(0);
        if (!"zh".equals(loc.getLanguage())) return FALLBACK_LANG;
        if ("Hant".equals(loc.getScript())) return "zh-TW";
        if ("Hans".equals(loc.getScript())) return "zh-CN";
        var c = loc.getCountry();
        return "TW".equals(c) || "HK".equals(c) || "MO".equals(c) ? "zh-TW" : "zh-CN";
    }

    @Nullable
    private static String readAsset(@NonNull Context ctx, @NonNull String path) {
        try (var in = ctx.getAssets().open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String readFile(@NonNull File f) {
        try (var in = new FileInputStream(f)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
