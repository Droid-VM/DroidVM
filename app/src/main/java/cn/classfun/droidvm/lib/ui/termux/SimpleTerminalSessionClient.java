package cn.classfun.droidvm.lib.ui.termux;

import android.util.Log;

import androidx.annotation.NonNull;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

public abstract class SimpleTerminalSessionClient implements TerminalSessionClient {
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
    }

    @Override
    public void onPasteTextFromClipboard(@NonNull TerminalSession s) {
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
