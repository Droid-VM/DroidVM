// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.network;

import static org.junit.Assert.*;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;

public final class NetworkActionsTest {
    private static VMConfig vm(String name, String... networks) {
        var vm = new VMConfig();
        vm.setId(UUID.randomUUID());
        vm.setName(name);
        var rows = DataItem.newArray();
        for (var network : networks) {
            var row = DataItem.newObject();
            row.set("network_id", network);
            rows.append(row);
        }
        vm.item.set("networks", rows);
        return vm;
    }

    @Test public void preflightIncludesEveryPersistedUserWithoutRuntimeListFiltering() {
        var store = new VMStore();
        store.add(vm("not-in-runtime", "target"));
        store.add(vm("other", "other-network"));
        store.add(vm("no-nics"));
        assertEquals(List.of("not-in-runtime"), NetworkActions.namesUsingNetwork(store, "target"));
    }

    @Test public void repeatedNicIsCheckedOnceAndPlanningDoesNotRemoveReferences() {
        var store = new VMStore();
        var vm = vm("two-nics", "target", "target");
        store.add(vm);
        assertEquals(List.of("two-nics"), NetworkActions.namesUsingNetwork(store, "target"));
        assertEquals(2, vm.item.get("networks").size());
        assertEquals("target", vm.item.get("networks").get(0).optString("network_id", ""));
    }
}
