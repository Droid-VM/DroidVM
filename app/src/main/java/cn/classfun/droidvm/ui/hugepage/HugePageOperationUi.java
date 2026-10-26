// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.hugepage;

import android.app.Activity;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.HugePageOperationClient;
import cn.classfun.droidvm.lib.utils.ThreadUtils;

/** Shared boundary: an uncertain native result aborts the entire UI action. */
final class HugePageOperationUi {
    private HugePageOperationUi() {}

    static void run(Activity activity, Runnable action, Runnable refresh) {
        try { action.run(); }
        catch (HugePageOperationClient.Blocked failure) {
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                refresh.run();
                var dialog = new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.hugepage_operation_blocked_title)
                    .setMessage(activity.getString(failure.uncertain
                        ? R.string.hugepage_operation_unknown : R.string.hugepage_operation_unavailable)
                        + "\n\n" + failure.getMessage())
                    .setNegativeButton(android.R.string.cancel, null);
                if (failure.uncertain) dialog.setPositiveButton(R.string.hugepage_operation_query, (d, which) ->
                    ThreadUtils.runOnPool(() -> run(activity, () -> {
                        var receipt = HugePageOperationClient.getInstance().queryPending();
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            refresh.run();
                            Toast.makeText(activity, receipt == null || receipt.succeeded
                                ? activity.getString(R.string.hugepage_operation_resolved)
                                : receipt.error, Toast.LENGTH_LONG).show();
                        });
                    }, refresh)));
                else dialog.setPositiveButton(android.R.string.ok, null);
                // The third slot is the way out, and it always stays open: with a
                // validated terminal receipt the review flow can settle the request,
                // otherwise the only remaining move is the explicit escape hatch.
                // Without this, a daemon that lost the task record deadlocks the page.
                if (failure.canAcknowledge) dialog.setNeutralButton(R.string.hugepage_operation_review,
                    (d, which) -> reviewTerminalState(activity, refresh));
                else if (failure.uncertain) dialog.setNeutralButton(R.string.hugepage_operation_force_clear,
                    (d, which) -> confirmForceClear(activity, refresh));
                dialog.show();
            });
        }
    }

    private static void reviewTerminalState(Activity activity, Runnable refresh) {
        ThreadUtils.runOnPool(() -> run(activity, () -> {
            var client = HugePageOperationClient.getInstance();
            var snapshot = client.readTerminalRecovery();
            String summary = summary(activity, snapshot);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.hugepage_operation_review)
                    .setMessage(activity.getString(R.string.hugepage_operation_acknowledge_message) + "\n\n" + summary)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.hugepage_operation_acknowledge, (d, which) ->
                        ThreadUtils.runOnPool(() -> run(activity, () -> {
                            client.acknowledgeTerminalRecovery(snapshot.optString("fingerprint"));
                            activity.runOnUiThread(() -> {
                                if (activity.isFinishing() || activity.isDestroyed()) return;
                                refresh.run();
                                Toast.makeText(activity, R.string.hugepage_operation_resolved, Toast.LENGTH_LONG).show();
                            });
                        }, refresh)))
                    .show();
            });
        }, refresh));
    }

    /**
     * Escape hatch. Shows the module's real state, then drops only the local pending
     * marker: no retry, no undo, no native write. Reachable from both the blocked
     * dialog and the toolbar, so a pending request whose daemon record is gone can
     * never lock the screen again.
     */
    static void confirmForceClear(Activity activity, Runnable refresh) {
        ThreadUtils.runOnPool(() -> {
            var client = HugePageOperationClient.getInstance();
            String id = client.pendingId();
            String observed;
            try {
                observed = summary(activity, client.snapshot());
            } catch (Exception e) {
                observed = String.valueOf(e.getMessage());
            }
            final String observedSummary = observed;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.hugepage_operation_force_clear)
                    .setMessage(activity.getString(R.string.hugepage_operation_force_clear_message)
                        + (id == null ? "" : "\n\n" + id) + "\n\n" + observedSummary)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.hugepage_operation_force_clear, (d, which) -> {
                        try {
                            client.forceClearPending();
                        } catch (HugePageOperationClient.Blocked failure) {
                            Toast.makeText(activity, activity.getString(R.string.hugepage_operation_failed,
                                failure.getMessage()), Toast.LENGTH_LONG).show();
                            return;
                        }
                        refresh.run();
                        Toast.makeText(activity, R.string.hugepage_operation_force_cleared,
                            Toast.LENGTH_LONG).show();
                    })
                    .show();
            });
        });
    }

    private static String summary(Activity activity, org.json.JSONObject snapshot) {
        var controls = snapshot.optJSONObject("controls");
        var settings = snapshot.optJSONObject("settings");
        return activity.getString(R.string.hugepage_operation_review_state,
            snapshot.optBoolean("loaded"), snapshot.optBoolean("bootEnabled"),
            controls == null ? "?" : controls.optString("pool_want", "?"),
            controls == null ? "?" : controls.optString("pool_want_with_cma", "?"),
            settings == null ? "?" : settings.optString("pool_want", "?"),
            settings == null ? "?" : settings.optString("pool_want_with_cma", "?"));
    }
}
