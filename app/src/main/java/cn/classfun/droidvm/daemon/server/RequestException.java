// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.server;

public final class RequestException extends RuntimeException {
    public RequestException(String message) {
        super(message);
    }
}
