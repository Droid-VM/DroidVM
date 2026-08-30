// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.agent.base;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import cn.classfun.droidvm.lib.store.base.JSONSerialize;
import cn.classfun.droidvm.lib.utils.JsonUtils;

/** One ordered, serializable operation executed by an {@link AgentVM}. */
public final class AgentActionSpec implements JSONSerialize {
    private final String type;
    private final Map<String, String> params = new HashMap<>();

    public AgentActionSpec(@NonNull String type) {
        var normalized = type.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9_-]*"))
            throw new IllegalArgumentException(fmt("Invalid agent action type: %s", type));
        this.type = normalized;
    }

    public AgentActionSpec(@NonNull JSONObject jo) throws JSONException {
        this(jo.getString("type"));
        if (jo.has("params")) params.putAll(JsonUtils.objectToStringMap(jo, "params"));
    }

    @NonNull
    public String getType() {
        return type;
    }

    public void setParam(@NonNull String key, @NonNull String value) {
        params.put(key, value);
    }

    public boolean hasParam(@NonNull String key) {
        return params.containsKey(key);
    }

    @Nullable
    public String getParam(@NonNull String key, @Nullable String def) {
        var value = params.getOrDefault(key, def);
        return value == null || value.isEmpty() ? def : value;
    }

    public void clearParam(@NonNull String key) {
        params.remove(key);
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var out = new JSONObject();
        out.put("type", type);
        var paramsObject = new JSONObject();
        for (var entry : params.entrySet())
            paramsObject.put(entry.getKey(), entry.getValue());
        out.put("params", paramsObject);
        return out;
    }
}
