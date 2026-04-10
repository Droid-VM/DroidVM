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
