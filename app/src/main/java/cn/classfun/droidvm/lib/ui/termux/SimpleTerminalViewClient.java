package cn.classfun.droidvm.lib.ui.termux;

import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalViewClient;

public abstract class SimpleTerminalViewClient implements TerminalViewClient {
    @Override
    public float onScale(float scale) {
        return 1.0f;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return true;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession s) {
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        return false;
    }

    @Override
    public boolean readAltKey() {
        return false;
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int cp, boolean ctrl, TerminalSession s) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
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
