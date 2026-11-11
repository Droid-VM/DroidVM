// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.base;

import static android.content.DialogInterface.BUTTON_NEUTRAL;
import static android.graphics.drawable.GradientDrawable.OVAL;
import static android.view.KeyEvent.*;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.yaml.snakeyaml.util.UriEncoder.encode;
import static cn.classfun.droidvm.lib.utils.NetUtils.resolveAddress;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.ui.vm.display.base.X11Keymap.*;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;


import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.perf.GamePerfHint;
import cn.classfun.droidvm.lib.ui.CopyableField;
import cn.classfun.droidvm.lib.ui.ImeInsetsExempt;
import cn.classfun.droidvm.ui.vm.display.base.DisplayExtraKeysPanel;
import cn.classfun.droidvm.ui.vm.display.vnc.input.VncExtraKeysPanel;

public abstract class BaseVncActivity extends AppCompatActivity implements ImeInsetsExempt {
    protected final String TAG = getClass().getSimpleName();
    public static final String EXTRA_VM_NAME = "vm_name";
    public static final String EXTRA_VM_ID = "vm_id";
    /**
     * Which screen's VNC server to connect to. A VM can run one per screen on different ports,
     * so the daemon is asked for that screen's settings rather than for "the VM's VNC".
     */
    public static final String EXTRA_SCREEN = "screen";
    /**
     * Whether that screen was configured with its own absolute input devices. Used only to say
     * why an input mode is doing nothing; where the events go is the daemon's answer, not this
     * one. Modes that ride RFB (the tablet pointer, the keyboard) are unaffected either way.
     */
    public static final String EXTRA_INPUT_ENABLED = "input_enabled";
    protected static final int DEFAULT_PORT = VncSession.DEFAULT_PORT;
    protected final Handler mainHandler = new Handler(Looper.getMainLooper());
    /**
     * The connection to the screen, shared with the external display's window while the picture
     * is there (VncSession.acquire): this console keeps its input either way.
     */
    @Nullable
    protected VncSession session;
    private final VncSession.Listener sessionListener = new SessionListener();
    protected VncClient vncClient;
    protected MaterialToolbar toolbar;
    protected View statusIndicator;
    protected TextView tvStatus;
    protected TextView tvConnectingMessage;
    protected LinearLayout overlayConnecting;
    protected VncDisplayView ivDisplay;
    protected DisplayExtraKeysPanel extraKeysPanel;
    protected VncExtraKeysPanel vncExtraKeys;
    protected String vmName = "";
    protected String vmId = "";
    /** Screen whose VNC server this view shows; empty means "the VM's first bound one". */
    protected String screenId = "";
    /** Whether that screen has absolute input devices at all; see {@link #EXTRA_INPUT_ENABLED}. */
    protected boolean screenInputEnabled = true;
    protected String vncHost = "127.0.0.1";
    // Phone LAN address the daemon resolved for an IPv4-wildcard bind (offload
    // proxy IPs already excluded); empty when not applicable. Preferred over
    // local interface enumeration for the external/remote connect address.
    protected String vncRemoteHost = "";
    protected int vncPort = DEFAULT_PORT;
    protected String vncPassword = null;
    /**
     * The decoder view this console has, when it has one; null leaves it RFB-only. The session
     * draws into it while the picture is here.
     */
    protected TextureView h264View;
    /** What {@link #setStatus} last put in the status line, and the note appended to it. */
    private String statusText = "";
    private String statusNote = "";
    protected int fbWidth, fbHeight;
    protected Bitmap displayBitmap;
    protected final Object bitmapLock = new Object();
    protected VncStatus status = VncStatus.CONNECTING;
    protected boolean capsLockOn = false;
    protected boolean numLockOn = false;
    protected LedStateListener ledStateListener;

    public interface LedStateListener {
        void onLedStateChanged(boolean caps, boolean num);
    }

    @SuppressWarnings("unused")
    protected abstract int getContentLayoutId();

    @SuppressWarnings("unused")
    protected abstract String getActivityTitle();

    @SuppressWarnings("unused")
    protected abstract void onBindExtraViews();

    @SuppressWarnings("unused")
    protected abstract void onSetupActivity();

    @SuppressWarnings("unused")
    protected abstract void onFramebufferReady(int width, int height);

    @SuppressWarnings("unused")
    protected abstract void onBitmapUpdated(@NonNull Bitmap bitmap);

    @SuppressWarnings("unused")
    protected void onStatusChanged(String text, VncStatus status) {
    }

    @SuppressWarnings("unused")
    protected void onDestroyExtra() {
    }

    @SuppressWarnings("unused")
    protected void onVncClientCreated() {
    }

    protected void onClearDisplay() {
        ivDisplay.setImageBitmap(null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(getContentLayoutId());
        var intent = getIntent();
        vmName = intent.getStringExtra(EXTRA_VM_NAME);
        if (vmName == null) vmName = "";
        vmId = intent.getStringExtra(EXTRA_VM_ID);
        if (vmId == null) vmId = "";
        screenId = intent.getStringExtra(EXTRA_SCREEN);
        if (screenId == null) screenId = "";
        screenInputEnabled = intent.getBooleanExtra(EXTRA_INPUT_ENABLED, true);
        bindViews();
        setupToolbar();
        // Before onSetupActivity() so subclasses can wire views (e.g. the physical keyboard)
        // to the adapter during their setup.
        vncExtraKeys = new VncExtraKeysPanel(extraKeysPanel);
        onSetupActivity();
        fetchVncInfoAndConnect();
    }

    /** Joins the screen's session -- the external display's window may have it already. */
    protected void fetchVncInfoAndConnect() {
        if (session != null) return;
        if (vmId.isEmpty()) {
            Log.e(TAG, "No VM ID provided");
            setStatus(getString(R.string.vnc_display_connect_failed), VncStatus.ERROR);
            showConnectingOverlay(getString(R.string.vnc_display_connect_failed));
            return;
        }
        session = VncSession.acquire(this, vmId, screenId, sessionListener);
        if (h264View != null && wantsH264View()) session.setH264View(h264View);
    }

    /**
     * Whether this console's decoder view should have the stream: true unless the picture is
     * somewhere else (the external display's window has the view then).
     */
    protected boolean wantsH264View() {
        return true;
    }

    /** What the session says, as this console says it. */
    private final class SessionListener implements VncSession.Listener {
        @Override
        public void onStatus(@NonNull String text, @NonNull VncStatus status) {
            setStatus(text, status);
            if (status == VncStatus.CONNECTED) hideConnectingOverlay();
            else showConnectingOverlay(text);
        }

        @Override
        public void onStatusNote(@Nullable String note) {
            setStatusNote(note);
        }

        @Override
        public void onClientCreated(@NonNull VncClient client) {
            vncClient = client;
            vncExtraKeys.setVncClient(client);
            var s = session;
            if (s != null) {
                vncHost = s.host();
                vncRemoteHost = s.remoteHost();
                vncPort = s.port();
                vncPassword = s.password();
            }
            onVncClientCreated();
        }

        @Override
        public void onScreenResolved(@NonNull String resolved) {
            // Input has to agree with the screen the daemon answered with: the absolute devices
            // are per screen.
            screenId = resolved;
        }

        @Override
        public void onFramebufferReady(int width, int height) {
            fbWidth = width;
            fbHeight = height;
            BaseVncActivity.this.onFramebufferReady(width, height);
        }

        @Override
        public void onBitmapUpdated(@NonNull Bitmap bitmap) {
            displayBitmap = bitmap;
            BaseVncActivity.this.onBitmapUpdated(bitmap);
        }

        @Override
        public void onClearDisplay() {
            fbWidth = 0;
            fbHeight = 0;
            displayBitmap = null;
            BaseVncActivity.this.onClearDisplay();
        }

        @Override
        public void onH264StreamChanged(boolean live, int width, int height) {
            BaseVncActivity.this.onH264StreamChanged(live, width, height);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A VM display is on screen and rendering: tell the platform this is sustained heavy
        // gameplay so its power policy raises clocks (see GamePerfHint).
        GamePerfHint.enterGameplay(this);
        // Nothing to restart here any more. Going to the background takes the decoder's surface
        // with the window, and coming back gives it a new one -- which the pipeline hears about
        // from the view itself. The stream never stopped: it rides the RFB connection, which stays
        // up the whole time, so the next rect after the surface returns is the one that draws.
    }

    @Override
    protected void onPause() {
        super.onPause();
        GamePerfHint.exitGameplay(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        onDestroyExtra();
        var s = session;
        session = null;
        if (s != null) {
            s.releaseH264View(h264View);
            s.release(sessionListener);
        }
        vncClient = null;
        onClearDisplay();
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar);
        statusIndicator = findViewById(R.id.status_indicator);
        tvStatus = findViewById(R.id.tv_status);
        tvConnectingMessage = findViewById(R.id.tv_connecting_message);
        overlayConnecting = findViewById(R.id.overlay_connecting);
        ivDisplay = findViewById(R.id.iv_display);
        // Null for a layout that has no decoder view of its own, which is both how a console opts
        // out of the H.264 path entirely and how the presentation defers the question until it
        // knows which display it is putting the picture on.
        h264View = findViewById(R.id.texture_h264);
        extraKeysPanel = findViewById(R.id.extra_keys_panel);
        onBindExtraViews();
    }

    private void setupToolbar() {
        toolbar.setTitle(getActivityTitle());
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    /** Binds this console's decoder view to the session, or takes it back (null). */
    protected void setH264View(@Nullable TextureView view) {
        var s = session;
        if (view == null) {
            if (s != null) s.releaseH264View(h264View);
            return;
        }
        h264View = view;
        if (s != null) s.setH264View(view);
    }

    /** Whether the H.264 decoder is what is painting the picture. */
    protected boolean isH264Live() {
        var s = session;
        return s != null && s.isH264Live();
    }

    /**
     * Hook for subclasses that have to move something when the decoder view appears or goes away.
     * The default console has nothing to do here -- the two views share one geometry, written by
     * the same viewport controller. [width] and [height] are the stream's, and zero when it is not
     * live, for the console whose decoder view has to be letterboxed by hand.
     */
    @SuppressWarnings("unused")
    protected void onH264StreamChanged(boolean live, int width, int height) {
    }

    /**
     * Appends a note to the status line, or clears it. Kept beside the status text rather than
     * replacing it: which transport is carrying the picture is a second fact about the same
     * connection, and losing "connected 1280x720" to say it would be a worse trade.
     */
    protected void setStatusNote(@Nullable String note) {
        var next = note == null ? "" : note;
        // A note that has not changed is not written again. The H.264 policy is read once a second
        // and re-states its verdict every time, which without this would be a setText per second
        // for the whole life of a console that has settled on the pixel path.
        if (statusNote.equals(next)) return;
        statusNote = next;
        applyStatusText();
    }

    private void applyStatusText() {
        tvStatus.setText(statusNote.isEmpty()
            ? statusText : fmt("%s  \u00b7  %s", statusText, statusNote));
    }

    protected void setStatus(String text, VncStatus newStatus) {
        int color;
        if (newStatus == this.status) return;
        switch (newStatus) {
            case CONNECTED:
                color = getColor(R.color.vnc_status_connected);
                break;
            case ERROR:
                color = getColor(R.color.vnc_status_error);
                break;
            case CONNECTING:
                color = getColor(R.color.vnc_status_connecting);
                break;
            default:
                return;
        }
        statusText = text;
        applyStatusText();
        this.status = newStatus;
        var indicator = new GradientDrawable();
        indicator.setShape(OVAL);
        indicator.setColor(color);
        statusIndicator.setBackground(indicator);
        onStatusChanged(text, newStatus);
    }

    protected void showConnectingOverlay(String message) {
        overlayConnecting.setVisibility(VISIBLE);
        tvConnectingMessage.setText(message);
    }

    protected void hideConnectingOverlay() {
        overlayConnecting.setVisibility(GONE);
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        int keyCode = event.getKeyCode();
        // A hardware-mouse right-click the framework (or OEM ROM) failed to see consumed gets
        // synthesized as a mouse-sourced BACK key. Inside the VM display that must never navigate
        // back - the right-click itself is delivered to the guest by the pointer handlers.
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK
            && (event.getSource() & android.view.InputDevice.SOURCE_MOUSE) != 0)
            return true;
        if (keyCode == KEYCODE_VOLUME_UP || keyCode == KEYCODE_VOLUME_DOWN)
            return super.dispatchKeyEvent(event);
        int keysym = androidKeyToXKeysym(keyCode);
        if (keysym != 0 && vncClient != null && vncClient.isConnected()) {
            boolean modifier = isModifierKey(keyCode);
            int action = event.getAction();
            int metaState = event.getMetaState();
            int baseKeysym = shiftedSymbolToBase(keysym);
            if (baseKeysym != 0) {
                keysym = baseKeysym;
                metaState = (metaState | META_SHIFT_ON) & ~META_SHIFT_MASK | META_SHIFT_ON;
            }
            if (action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KEYCODE_CAPS_LOCK) {
                    capsLockOn = !capsLockOn;
                    notifyLedState();
                } else if (keyCode == KEYCODE_NUM_LOCK) {
                    numLockOn = !numLockOn;
                    notifyLedState();
                }
                if (!modifier && vncExtraKeys.hasNonStickyModifiers())
                    vncExtraKeys.applyModifiers(true);
                if (!modifier) sendMetaState(metaState, true);
                vncClient.sendKey(keysym, true);
            } else if (action == KeyEvent.ACTION_UP) {
                vncClient.sendKey(keysym, false);
                if (!modifier) sendMetaState(metaState, false);
                if (!modifier && vncExtraKeys.hasNonStickyModifiers())
                    vncExtraKeys.applyModifiers(false);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static int shiftedSymbolToBase(int keysym) {
        switch (keysym) {
            case XK_exclam:
                return XK_1;            // '!' -> Shift+1
            case XK_at:
                return XK_2;            // '@' -> Shift+2
            case XK_numbersign:
                return XK_3;            // '#' -> Shift+3
            case XK_dollar:
                return XK_4;            // '$' -> Shift+4
            case XK_percent:
                return XK_5;            // '%' -> Shift+5
            case XK_asciicircum:
                return XK_6;            // '^' -> Shift+6
            case XK_ampersand:
                return XK_7;            // '&' -> Shift+7
            case XK_asterisk:
                return XK_8;            // '*' -> Shift+8
            case XK_parenleft:
                return XK_9;            // '(' -> Shift+9
            case XK_parenright:
                return XK_0;            // ')' -> Shift+0
            case XK_underscore:
                return XK_minus;        // '_' -> Shift+-
            case XK_plus:
                return XK_equal;        // '+' -> Shift+=
            case XK_asciitilde:
                return XK_grave;        // '~' -> Shift+`
            case XK_braceleft:
                return XK_bracketleft;  // '{' -> Shift+[
            case XK_braceright:
                return XK_bracketright; // '}' -> Shift+]
            case XK_bar:
                return XK_backslash;    // '|' -> Shift+\
            case XK_colon:
                return XK_semicolon;    // ':' -> Shift+;
            case XK_quotedbl:
                return XK_apostrophe;   // '"' -> Shift+'
            case XK_less:
                return XK_comma;        // '<' -> Shift+,
            case XK_greater:
                return XK_period;       // '>' -> Shift+.
            case XK_question:
                return XK_slash;        // '?' -> Shift+/
            default:
                return 0;
        }
    }

    private void sendMetaState(int metaState, boolean down) {
        if ((metaState & META_SHIFT_ON) != 0)
            vncClient.sendKey(androidKeyToXKeysym(KEYCODE_SHIFT_LEFT), down);
        if ((metaState & META_CTRL_ON) != 0)
            vncClient.sendKey(androidKeyToXKeysym(KEYCODE_CTRL_LEFT), down);
        if ((metaState & META_ALT_ON) != 0)
            vncClient.sendKey(androidKeyToXKeysym(KEYCODE_ALT_LEFT), down);
        if ((metaState & META_META_ON) != 0)
            vncClient.sendKey(androidKeyToXKeysym(KEYCODE_META_LEFT), down);
    }

    private void notifyLedState() {
        if (ledStateListener != null)
            ledStateListener.onLedStateChanged(capsLockOn, numLockOn);
    }

    protected static boolean isModifierKey(int keyCode) {
        switch (keyCode) {
            case KEYCODE_SHIFT_LEFT:
            case KEYCODE_SHIFT_RIGHT:
            case KEYCODE_CTRL_LEFT:
            case KEYCODE_CTRL_RIGHT:
            case KEYCODE_ALT_LEFT:
            case KEYCODE_ALT_RIGHT:
            case KEYCODE_META_LEFT:
            case KEYCODE_META_RIGHT:
            case KEYCODE_CAPS_LOCK:
                return true;
            default:
                return false;
        }
    }

    protected void toggleSoftKeyboard() {
        var imm = getSystemService(InputMethodManager.class);
        if (imm == null || ivDisplay == null) return;
        // Post so the fab-menu popup has finished tearing down: called inline right after the item
        // click, the popup still owns the focus transition and showSoftInput lands before ivDisplay
        // is the served view and does nothing. (The letterbox onClick path already has focus, but
        // routing both through the same retry keeps them consistent.)
        mainHandler.post(() -> tryShowKeyboard(imm, 15));
    }

    // showSoftInput() can return true for a view the IMM isn't serving yet and show nothing, so the
    // success test is imm.isActive(view), retried on a short delay until the input connection is
    // live. The last few rounds force the IME (some ROMs ignore the implicit request).
    private void tryShowKeyboard(@NonNull InputMethodManager imm, int attemptsLeft) {
        if (attemptsLeft <= 0 || isFinishing() || ivDisplay == null) return;
        ivDisplay.requestFocusFromTouch();
        ivDisplay.requestFocus();
        int flag = attemptsLeft <= 3
            ? InputMethodManager.SHOW_FORCED : InputMethodManager.SHOW_IMPLICIT;
        imm.showSoftInput(ivDisplay, flag);
        if (ivDisplay.isFocused() && imm.isActive(ivDisplay)) return;
        mainHandler.postDelayed(() -> tryShowKeyboard(imm, attemptsLeft - 1), 60);
    }

    protected VncDisplayView.TextCommitListener createTextCommitListener() {
        return new VncDisplayView.TextCommitListener() {
            @Override
            public void onCommitText(@NonNull CharSequence text) {
                if (vncClient == null || !vncClient.isConnected()) return;
                vncExtraKeys.applyModifiers(true);
                for (int i = 0; i < text.length(); i++) {
                    int ch = text.charAt(i);
                    vncClient.sendKey(ch, true);
                    vncClient.sendKey(ch, false);
                }
                vncExtraKeys.applyModifiers(false);
            }

            @Override
            public void onDeleteSurrounding(int beforeLength, int afterLength) {
                if (vncClient == null || !vncClient.isConnected()) return;
                vncExtraKeys.applyModifiers(true);
                int keysym = androidKeyToXKeysym(KEYCODE_DEL);
                for (int i = 0; i < beforeLength; i++) {
                    vncClient.sendKey(keysym, true);
                    vncClient.sendKey(keysym, false);
                }
                int fwdKeysym = androidKeyToXKeysym(KEYCODE_FORWARD_DEL);
                for (int i = 0; i < afterLength; i++) {
                    vncClient.sendKey(fwdKeysym, true);
                    vncClient.sendKey(fwdKeysym, false);
                }
                vncExtraKeys.applyModifiers(false);
            }
        };
    }

    @SuppressLint("SourceLockedOrientationActivity")
    protected void rotateScreen() {
        int current = getRequestedOrientation();
        switch (current) {
            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
                break;
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
                break;
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                break;
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
        }
    }

    /**
     * Resolves the host for a VNC URI. For the external/remote address, prefers
     * the daemon-resolved phone LAN address ({@link #vncRemoteHost}) when the VM
     * binds the IPv4 wildcard, since it excludes pbridge offload-proxy IPs that
     * local interface enumeration cannot tell apart. Falls back to
     * {@link cn.classfun.droidvm.lib.utils.NetUtils#resolveAddress} otherwise.
     */
    @NonNull
    private String resolveVncHost(boolean local) {
        if (!local && "0.0.0.0".equals(vncHost) && !vncRemoteHost.isEmpty())
            return vncRemoteHost;
        return resolveAddress(vncHost, local);
    }

    @NonNull
    protected String generateVncUri(boolean local) {
        var sb = new StringBuilder();
        var host = resolveVncHost(local);
        var port = vncPort;
        sb.append(fmt("vnc://%s:%d/", host, port));
        var ps = new StringBuilder();
        if (vncPassword != null && !vncPassword.isEmpty())
            ps.append("VncPassword=").append(encode(vncPassword));
        if (ps.length() > 0)
            sb.append("?").append(ps);
        return sb.toString();
    }

    /**
     * The one connection dialog: what to point a viewer at, and the handoff to one.
     *
     * <p>It shows the {@code vnc://} URI for this device and, when the server is bound wider than
     * loopback, the one another machine on the network would use -- the pair the separate "view
     * URL" dialog used to show, which is why there is no longer a separate dialog: a bare
     * {@code host:port} said nothing the URI does not already say. Connect hands the network URI
     * to whatever app claims {@code vnc://}; the password rides in it as a query parameter, and
     * the copy button is there for the viewers that ignore it.</p>
     */
    protected void openWithExternalApp() {
        var localUrl = generateVncUri(true);
        var remoteUrl = generateVncUri(false);
        boolean sameUrl = localUrl.equals(remoteUrl);
        boolean hasPassword = vncPassword != null && !vncPassword.isEmpty();
        var view = getLayoutInflater().inflate(R.layout.dialog_vnc_external, null);
        EditText etLocal = view.findViewById(R.id.et_local);
        EditText etRemote = view.findViewById(R.id.et_remote);
        TextView etPassword = view.findViewById(R.id.et_password);
        TextInputLayout tilRemote = view.findViewById(R.id.til_remote);
        TextInputLayout tilPassword = view.findViewById(R.id.til_password);
        etLocal.setText(localUrl);
        CopyableField.setupReadOnly(etLocal, getString(R.string.vnc_external_hint_local));
        if (sameUrl) {
            tilRemote.setVisibility(GONE);
        } else {
            etRemote.setText(remoteUrl);
            CopyableField.setupReadOnly(etRemote, getString(R.string.vnc_external_hint_remote));
        }
        if (hasPassword) {
            etPassword.setText(vncPassword);
        } else {
            tilPassword.setVisibility(GONE);
        }
        DialogInterface.OnClickListener onConnect = (d, w) -> {
            var intent = new Intent(Intent.ACTION_VIEW, Uri.parse(remoteUrl));
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.vnc_menu_no_vnc_app, Toast.LENGTH_SHORT).show();
            }
        };
        var builder = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vnc_external_title)
            .setView(view)
            .setNegativeButton(R.string.vnc_external_cancel, null)
            .setPositiveButton(R.string.vnc_external_connect, onConnect);
        if (hasPassword)
            builder.setNeutralButton(R.string.vnc_external_copy_password, null);
        var dialog = builder.show();
        if (hasPassword) dialog.getButton(BUTTON_NEUTRAL).setOnClickListener(v ->
            CopyableField.copy(this, vncPassword, getString(R.string.vnc_external_hint_password)));
    }

    protected void reconnect() {
        var s = session;
        if (s != null) s.reconnect();
    }

    protected boolean onMenuItemClicked(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_rotate) {
            rotateScreen();
            return true;
        } else if (id == R.id.menu_reconnect) {
            reconnect();
            return true;
        } else if (id == R.id.menu_external) {
            openWithExternalApp();
            return true;
        }
        return false;
    }
}
