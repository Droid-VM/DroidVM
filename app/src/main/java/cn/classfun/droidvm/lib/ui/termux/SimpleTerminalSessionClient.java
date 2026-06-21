package cn.classfun.droidvm.lib.ui.termux;

import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import cn.classfun.droidvm.lib.ui.CopyableField;

public abstract class SimpleTerminalSessionClient implements TerminalSessionClient {
    /** Clipboard label for text copied out of a terminal. */
    private static final String CLIP_LABEL = "Terminal";

    /**
     * Used to reach the system clipboard for copy/paste; never null. Stored as
     * given (typically the owning Activity) and only touched at copy/paste time,
     * so this is safe even when constructed as an Activity field initializer
     * (before the base context is attached). The client lives no longer than its
     * owner, so retaining an Activity reference here leaks nothing.
     */
    protected final Context context;

    protected SimpleTerminalSessionClient(@NonNull Context context) {
        this.context = context;
    }

    @Override
    public void onTextChanged(@NonNull TerminalSession s) {
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession s) {
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession s) {
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession s, String t) {
        if (t == null || t.isEmpty()) return;
        CopyableField.copy(context, t, CLIP_LABEL);
    }

    @Override
    public void onPasteTextFromClipboard(@NonNull TerminalSession s) {
        var cm = context.getSystemService(ClipboardManager.class);
        if (cm == null || !cm.hasPrimaryClip()) return;
        var clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        var text = clip.getItemAt(0).coerceToText(context);
        if (text == null || text.length() == 0) return;
        var emulator = s.getEmulator();
        if (emulator != null) emulator.paste(text.toString());
    }

    @Override
    public void onBell(@NonNull TerminalSession s) {
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession s) {
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
    }

    @Override
    public void logError(String tag, String msg) {
        Log.e(tag, msg);
    }

    @Override
    public void logWarn(String tag, String msg) {
        Log.w(tag, msg);
    }

    @Override
    public void logInfo(String tag, String msg) {
        Log.i(tag, msg);
    }

    @Override
    public void logDebug(String tag, String msg) {
        Log.d(tag, msg);
    }

    @Override
    public void logVerbose(String tag, String msg) {
        Log.v(tag, msg);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String msg, Exception e) {
        Log.e(tag, msg, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(tag, "error", e);
    }
}
