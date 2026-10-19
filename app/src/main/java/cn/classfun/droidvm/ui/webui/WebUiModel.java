// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.webui;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
import cn.classfun.droidvm.lib.daemon.WebUiClient;
import cn.classfun.droidvm.lib.utils.ThreadUtils;

/** Keeps in-flight writes across recreation. No Activity or saved credential state. */
public final class WebUiModel extends ViewModel {
    // Also serialize operations from separate instances of the settings activity.
    private static final WebUiClient CLIENT = new WebUiClient(DaemonConnection.getInstance()::request);
    public static final class State {
        public final WebUiClient.Status status;
        public final boolean busy, reloadRequired;
        public final String problem, token;
        State(WebUiClient.Status status, boolean busy, boolean reload, String problem, String token) {
            this.status = status; this.busy = busy; this.reloadRequired = reload; this.problem = problem; this.token = token;
        }
    }
    private final MutableLiveData<State> state = new MutableLiveData<>(new State(null, false, false, "", ""));
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cleared;
    private boolean foreground;
    private long tokenDeadline;
    public LiveData<State> state() { return state; }
    private interface Work { WebUiClient.Status run() throws Exception; }
    public void foreground(boolean value) { foreground = value; if (!value) clearToken(); }
    public void clearToken() {
        State current = state.getValue();
        state.setValue(new State(current.status, current.busy, current.reloadRequired, current.problem, ""));
        tokenDeadline = 0;
    }
    private void run(Work work, boolean pairing) {
        State current = state.getValue();
        if (current.busy) return;
        state.setValue(new State(current.status, true, current.reloadRequired, "", ""));
        ThreadUtils.runOnPool(() -> {
            WebUiClient.Status result = null;
            String token = "", problem = "";
            boolean reload = false;
            try {
                if (pairing) token = CLIENT.pair(); else result = work.run();
            } catch (WebUiClient.Unsupported e) { problem = "unsupported"; }
            catch (VmConfigClient.RemoteFailure e) { problem = e.code; }
            catch (Exception e) { problem = "unavailable"; }
            // Do not emit exceptions or response bodies: a pairing reply contains a secret.
            reload = CLIENT.needsReload();
            final WebUiClient.Status loaded = result;
            final String grant = token, failure = problem;
            final boolean needsReload = reload;
            main.post(() -> {
                if (cleared) return;
                String visibleToken = foreground ? grant : "";
                state.setValue(new State(loaded != null ? loaded : current.status, false, needsReload, failure, visibleToken));
                if (!visibleToken.isEmpty()) {
                    tokenDeadline = SystemClock.elapsedRealtime() + 300_000;
                    main.postDelayed(() -> { if (tokenDeadline != 0 && SystemClock.elapsedRealtime() >= tokenDeadline) clearToken(); }, 300_000);
                }
            });
        });
    }
    public void refresh(boolean reload) { run(() -> CLIENT.read(reload), false); }
    public void configure(WebUiClient.Settings expected, WebUiClient.Settings settings) {
        run(() -> CLIENT.configure(expected, settings), false);
    }
    public void pair() { run(null, true); }
    public void revoke(String id) { run(() -> CLIENT.revoke(id), false); }
    @Override protected void onCleared() { cleared = true; main.removeCallbacksAndMessages(null); }
}
