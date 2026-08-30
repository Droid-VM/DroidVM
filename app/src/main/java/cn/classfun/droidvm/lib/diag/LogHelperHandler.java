// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.diag;

import static android.content.Intent.ACTION_VIEW;

import android.content.Context;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.net.Uri;
import android.text.util.Linkify;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.Duration;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.utils.FileUtils;

public abstract class LogHelperHandler {
    private static boolean hasGunyahInfo = false, isGunyah = false;
    public boolean match(@NonNull UUID vmId, @NonNull String stream, @NonNull String text) {
        return false;
    }

    /**
     * Every line, before {@link #match} and whether or not this handler has already fired.
     *
     * <p>For a handler that has to report what the log said rather than only that it said it:
     * {@link #isOnce} takes the handler out of the match loop the moment it first matches, so
     * match() cannot be where anything is gathered -- the lines that arrive during the show delay,
     * and every line after it, would never be looked at.</p>
     */
    public void observe(@NonNull UUID vmId, @NonNull String stream, @NonNull String text) {
    }

    /**
     * Called when [vmId]'s log context is dropped, which is when that VM exits.
     *
     * <p>Handler instances are process-wide singletons shared by every VM, so anything a handler
     * accumulates is keyed by vmId and has to be forgotten here: the context takes the
     * already-fired mark with it, so the next boot of the same VM re-arms this handler and must
     * not inherit the last boot's findings.</p>
     */
    public void onLogContextReset(@NonNull UUID vmId) {
    }

    public boolean isOnce() {
        return true;
    }

    public Duration getShowDelay() {
        return Duration.ofSeconds(2);
    }

    public abstract void show(@NonNull Context ctx, @NonNull UUID vmId, @NonNull String vmName);

    protected static boolean isGunyah() {
        if (!hasGunyahInfo) {
            isGunyah = FileUtils.shellCheckExists("/dev/gunyah");
            hasGunyahInfo = true;
        }
        return isGunyah;
    }

    protected static void showDialog(
        @NonNull Context ctx,
        @StringRes int urlId,
        @StringRes int titleId,
        @StringRes int messageId,
        Object... args
    ) {
        showDialog(ctx, urlId, titleId, ctx.getString(messageId, args), 0, null);
    }

    /**
     * The same dialog with the message already built, and one extra action beside OK and the wiki
     * link. A message that lists what the VM actually did is not one format string, which is why
     * this takes the text rather than a resource id.
     *
     * <p>MaterialAlertDialog's three slots are all spoken for here: positive is OK, neutral is the
     * wiki URL as everywhere else, so [actionId] takes negative. Pass 0 for no extra action.</p>
     *
     * <p>URLs left in the text are made tappable here because nothing else does it: the body is
     * {@code @android:id/message} styled {@code materialAlertDialogBodyTextStyle}, and material
     * 1.14.0 sets {@code autoLink} nowhere in the whole AAR, while AppCompat 1.7.0's
     * AlertController only calls {@code setText} on it -- neither {@code setMovementMethod} nor
     * {@code Linkify} appears in its bytecode. {@code Linkify.addLinks(TextView, int)} installs
     * the movement method itself once it has a span to install it for.</p>
     */
    protected static void showDialog(
        @NonNull Context ctx,
        @StringRes int urlId,
        @StringRes int titleId,
        @NonNull CharSequence message,
        @StringRes int actionId,
        @Nullable OnClickListener action
    ) {
        var mab = new MaterialAlertDialogBuilder(ctx);
        mab.setTitle(titleId);
        mab.setMessage(message);
        mab.setPositiveButton(android.R.string.ok, null);
        if (actionId != 0) mab.setNegativeButton(actionId, action);
        var url = ctx.getString(urlId);
        OnClickListener cb = (d, w) -> ctx.startActivity(new Intent(ACTION_VIEW, Uri.parse(url)));
        if (!url.isEmpty()) mab.setNeutralButton(R.string.log_helper_open_url, cb);
        var dialog = mab.show();
        TextView body = dialog.findViewById(android.R.id.message);
        if (body == null) return;
        // A message built from HTML anchors already carries its URLSpans, and Linkify would strip
        // them while hunting for raw URLs -- so anchors get the movement method only, and plain
        // text keeps the old raw-URL pass.
        boolean hasAnchors = message instanceof android.text.Spanned
            && ((android.text.Spanned) message)
                .getSpans(0, message.length(), android.text.style.URLSpan.class).length > 0;
        if (hasAnchors) {
            body.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        } else {
            Linkify.addLinks(body, Linkify.WEB_URLS);
        }
    }
}
