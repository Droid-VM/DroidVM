// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.agent.base;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/** Splits an encoded rescue payload into lines safely below the TTY canonical limit. */
public final class AgentPayloadChunks {
    public static final int MAX_CHUNK_LENGTH = 1024;

    private AgentPayloadChunks() {
    }

    @NonNull
    public static List<String> split(@NonNull String payload) {
        var chunks = new ArrayList<String>();
        for (int start = 0; start < payload.length(); start += MAX_CHUNK_LENGTH) {
            int end = Math.min(start + MAX_CHUNK_LENGTH, payload.length());
            chunks.add(payload.substring(start, end));
        }
        return chunks;
    }
}
