package cn.classfun.droidvm.lib.ui.termux;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

import androidx.annotation.NonNull;

import com.termux.view.TerminalView;

import java.io.File;

/**
 * Picks the typeface for the embedded terminals. Mirrors Termux's font logic:
 * a user-supplied {@code $HOME/.termux/font.ttf} wins, otherwise the bundled
 * Maple Mono NL NF is used, falling back to the system monospace if either fails
 * to parse. The console shells run with {@code HOME=getFilesDir()}, so the
 * user-override path matches Termux exactly.
 */
public final class TerminalFonts {
    private static final String TAG = "TerminalFonts";

    /** Bundled default: Maple Mono NL NF (NL = no ligatures); see app/src/main/assets/fonts. */
    private static final String ASSET_FONT = "fonts/MapleMonoNL-NF-Regular.ttf";

    /** Termux-compatible drop-in override, relative to the shell's HOME. */
    private static final String USER_FONT = ".termux/font.ttf";

    /** The bundled font is immutable, so parse it once and reuse. */
    private static Typeface bundled;

    private TerminalFonts() {
    }

    /** Loads {@link #load(Context)} and applies it to {@code tv}. */
    public static void apply(@NonNull TerminalView tv) {
        tv.setTypeface(load(tv.getContext()));
    }

    /**
     * Resolves the terminal typeface. The user override is re-checked on every
     * call (a cheap stat) so dropping in a {@code font.ttf} takes effect the
     * next time a terminal is opened, without restarting the app.
     */
    @NonNull
    public static Typeface load(@NonNull Context ctx) {
        var userFont = new File(ctx.getFilesDir(), USER_FONT);
        if (userFont.isFile()) {
            try {
                return Typeface.createFromFile(userFont);
            } catch (Exception e) {
                Log.w(TAG, fmt("Ignoring unreadable user font %s", userFont), e);
            }
        }
        if (bundled == null) {
            try {
                bundled = Typeface.createFromAsset(ctx.getAssets(), ASSET_FONT);
            } catch (Exception e) {
                Log.w(TAG, "Failed to load bundled terminal font, using monospace", e);
                bundled = Typeface.MONOSPACE;
            }
        }
        return bundled;
    }
}
