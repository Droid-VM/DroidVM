// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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
     * off the main thread. Daemon errors read as "none in use": these checks guard disk
     * operations, and with the daemon down no VM can be running anyway.
     */
    @NonNull
    public static List<String> inUseAmong(@NonNull Collection<String> candidates) {
        var inUse = new HashSet<String>();
        var latch = new CountDownLatch(1);
        DaemonConnection.getInstance().buildRequest("vm_list")
            .onResponse(resp -> {
                var arr = resp.optJSONArray("data");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        var obj = arr.optJSONObject(i);
                        if (obj != null
                            && !obj.optString("state").equalsIgnoreCase("stopped"))
                            inUse.add(obj.optString("name", ""));
                    }
                }
                latch.countDown();
            })
            .onUnsuccessful(resp -> latch.countDown())
            .onError(e -> latch.countDown())
            .invoke();
        try {
            //noinspection ResultOfMethodCallIgnored
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var out = new ArrayList<String>();
        for (var name : candidates)
            if (inUse.contains(name)) out.add(name);
        return out;
    }
}
