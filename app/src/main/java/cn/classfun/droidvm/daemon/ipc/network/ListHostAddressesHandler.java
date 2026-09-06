// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.ipc.network;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.network.backend.HostAddressScan;
import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestHandler;

/**
 * The phone's own addresses, for a UI offering somewhere to listen.
 *
 * <p>Asked of the daemon rather than enumerated in the app, because the two addresses that must
 * not appear -- a pseudo-bridged guest's IP parked on the uplink, and the host-route-only shape
 * pbridge parks it in -- are netlink details no unprivileged interface enumeration can see. See
 * {@link HostAddressScan#list()} for the whole policy.</p>
 */
@AutoService(RequestHandler.class)
public final class ListHostAddressesHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "network_list_host_addresses";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        request.res().put("data", HostAddressScan.list());
    }
}
