package cn.classfun.droidvm.daemon.server;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

public final class RequestHandlerStore {
    private static final String TAG = "RequestHandlerStore";
    private final Map<String, RequestHandler> handlers = new HashMap<>();

    public RequestHandlerStore() {
        loadAll();
    }

    private void loadAll() {
        for (var handler : ServiceLoader.load(RequestHandler.class))
            register(handler);
        Log.d(TAG, fmt("Loaded %d request handlers", handlers.size()));
    }

    public void register(@NonNull RequestHandler handler) {
        handlers.put(handler.getName(), handler);
    }

    @Nullable
    public RequestHandler find(@NonNull String name) {
        return handlers.get(name);
    }
}
