// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;

/** One-shot daemon queries about VM run state, for pre-operation checks. */
public final class VmRunningQuery {
    private VmRunningQuery() {
    }

    /**
     * Names among {@code candidates} whose VM is anything but stopped - starting, running,
     * suspended, stopping or rebooting all hold the disk files open. Blocking (up to 5s) - call
     * off the main thread. Failure is unknown, never evidence that a VM stopped.
     */
    @NonNull
    public static List<String> inUseAmong(@NonNull Collection<String> candidates) {
        var response = new AtomicReference<JSONObject>();
        var failure = new AtomicReference<Exception>();
        var latch = new CountDownLatch(1);
        DaemonConnection.getInstance().buildRequest("vm_list")
            .onResponse(resp -> { response.set(resp); latch.countDown(); })
            .onUnsuccessful(resp -> {
                failure.set(new IllegalStateException(resp.optString("message", "VM state query failed")));
                latch.countDown();
            })
            .onError(error -> { failure.set(error); latch.countDown(); })
            .invoke();
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("VM state query timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("VM state query interrupted", e);
        }
        if (failure.get() != null) throw new IllegalStateException("Cannot verify VM run state", failure.get());
        var reply = response.get();
        var rows = reply == null ? null : reply.optJSONArray("data");
        if (rows == null) throw new IllegalStateException("Missing VM run state list");
        var states = new HashMap<String, String>();
        for (int i = 0; i < rows.length(); i++) {
            var row = rows.optJSONObject(i);
            if (row == null || !(row.opt("name") instanceof String) || !(row.opt("state") instanceof String))
                throw new IllegalStateException("Invalid VM run state entry");
            var name = row.optString("name");
            if (name.isEmpty() || states.put(name, row.optString("state")) != null)
                throw new IllegalStateException("Ambiguous VM run state entry");
        }
        return inUseFromStates(candidates, states);
    }

    static List<String> inUseFromStates(Collection<String> candidates, Map<String, String> states) {
        var out = new ArrayList<String>();
        for (var name : candidates)
            if (!"stopped".equalsIgnoreCase(states.get(name))) out.add(name);
        return out;
    }
}
