package cn.classfun.droidvm.lib.api;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

import cn.classfun.droidvm.lib.utils.JsonUtils;

public final class ApiInfo {
    private final String baseUrl;
    private final Map<String, ApiServiceInfo> services;

    public ApiInfo(@NonNull JSONObject json) throws JSONException {
        this.baseUrl = json.getString("base_url");
        this.services = JsonUtils.objectToMap(json, "services", ApiServiceInfo::create);
    }

    @NonNull
    public String getBaseUrl() {
        return baseUrl;
    }

    @NonNull
    public Map<String, ApiServiceInfo> getServices() {
        return services;
    }

    @Nullable
    public ApiServiceInfo getService(@NonNull String name) {
        return services.get(name);
    }

    @NonNull
    public ApiServiceInfo requireService(@NonNull String name) {
        var svc = getService(name);
        if (svc == null) throw new IllegalArgumentException(fmt("Service not found: %s", name));
        return svc;
    }

    @NonNull
    public String getServiceUrl(@NonNull String name) {
        return getFullUrl(requireService(name));
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean hasService(@NonNull String name) {
        return services.containsKey(name);
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getServiceName(@NonNull String name) {
        return requireService(name).getName();
    }

    @NonNull
    public String getFullUrl(@NonNull String relPath) {
        return pathJoin(getBaseUrl(), relPath);
    }

    @NonNull
    public String getFullUrl(@NonNull ApiServiceInfo service) {
        return getFullUrl(service.getPath());
    }
}
