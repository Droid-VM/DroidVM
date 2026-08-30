// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.server;

import androidx.annotation.NonNull;

public abstract class RequestHandler {
    public boolean needAuthorization() {
        return true;
    }

    @NonNull
    public abstract String getName();

    public abstract void handle(@NonNull ClientRequest request) throws Exception;
}
