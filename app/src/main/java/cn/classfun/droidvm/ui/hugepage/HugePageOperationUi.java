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
                if (failure.canAcknowledge) dialog.setNeutralButton(R.string.hugepage_operation_review,
                    (d, which) -> reviewTerminalState(activity, refresh));
                dialog.show();
            });
        }
    }

    private static void reviewTerminalState(Activity activity, Runnable refresh) {
        ThreadUtils.runOnPool(() -> run(activity, () -> {
            var client = HugePageOperationClient.getInstance();
            var snapshot = client.readTerminalRecovery();
            var controls = snapshot.optJSONObject("controls");
            var settings = snapshot.optJSONObject("settings");
            String summary = activity.getString(R.string.hugepage_operation_review_state,
                snapshot.optBoolean("loaded"), snapshot.optBoolean("bootEnabled"),
                controls == null ? "?" : controls.optString("pool_want", "?"),
                controls == null ? "?" : controls.optString("pool_want_with_cma", "?"),
                settings == null ? "?" : settings.optString("pool_want", "?"),
                settings == null ? "?" : settings.optString("pool_want_with_cma", "?"));
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
}
