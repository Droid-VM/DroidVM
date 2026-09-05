// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.base;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

public interface JSONSerialize {
    @NonNull
    JSONObject toJson() throws JSONException;
}
