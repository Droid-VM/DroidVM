package cn.classfun.droidvm.lib.api;

import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import cn.classfun.droidvm.BuildConfig;
import cn.classfun.droidvm.lib.utils.NetUtils;

public final class ApiManager {
    private final Context context;
    private final SharedPreferences prefs;
    private static volatile ApiInfo apiInfo = null;

    public ApiManager(@NonNull Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("droidvm_prefs", Context.MODE_PRIVATE);
    }

    public void load() {
        if (!Privacy.isPrivacyAgreed(context))
            throw new RuntimeException("Privacy policy not agreed or outdated");
        if (apiInfo != null) return;
        synchronized (ApiManager.class) {
            if (apiInfo != null) return;
            try {
                apiInfo = new ApiInfo(NetUtils.fetchJSON(
                    "https://api.classfun.cn/droidvm/",
                    NetUtils.USER_AGENT
                ));
            } catch (Exception e) {
                throw new RuntimeException("Failed to load API information", e);
            }
        }
    }

    @NonNull
    public static ApiManager create(@NonNull Context context) {
        var manager = new ApiManager(context);
        manager.load();
        return manager;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static ApiInfo getApiInfo() {
        return requireNonNull(apiInfo);
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getApiUrl(@NonNull String service) {
        if (!isServiceEnabled(service))
            throw new RuntimeException(fmt("Service %s is not enabled", service));
        return getApiRawUrl(service);
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getApiRawUrl(@NonNull String service) {
        return getApiInfo().getServiceUrl(service);
    }

    @NonNull
    @SuppressWarnings("unused")
    public JSONObject fetchApi(@NonNull String service) throws Exception {
        return NetUtils.fetchJSON(getApiUrl(service), NetUtils.USER_AGENT);
    }

    @NonNull
    @SuppressWarnings("unused")
    public JSONObject fetchApi(
        @NonNull String service,
        @NonNull Map<String, String> params
    ) throws Exception {
        var sb = new StringBuilder();
        sb.append(getApiUrl(service));
        if (!params.isEmpty()) {
            sb.append("?");
            sb.append(NetUtils.buildUrlQuery(params));
        }
        return NetUtils.fetchJSON(sb.toString(), NetUtils.USER_AGENT);
    }

    @NonNull
    @SuppressWarnings("unused")
    public JSONObject fetchApiWithVersion(@NonNull String service) throws Exception {
        return fetchApi(service, buildVersionParams());
    }

    @NonNull
    public static Map<String, String> buildVersionParams() {
        var params = new HashMap<String, String>();
        params.put("version", String.valueOf(BuildConfig.VERSION_CODE));
        return params;
    }

    public boolean isServiceEnabled(@NonNull String service) {
        if (!Privacy.isPrivacyAgreed(context)) return false;
        if (!getApiInfo().hasService(service)) return false;
        return this.prefs.getBoolean(getServiceKey(service), true);
    }

    public void setServiceEnabled(@NonNull String service, boolean enabled) {
        if (!getApiInfo().hasService(service))
            throw new IllegalArgumentException(fmt("Service %s does not exist", service));
        this.prefs.edit().putBoolean(getServiceKey(service), enabled).apply();
    }

    @NonNull
    private String getServiceKey(@NonNull String service) {
        return fmt("api_service_%s_enabled", service);
    }
}
