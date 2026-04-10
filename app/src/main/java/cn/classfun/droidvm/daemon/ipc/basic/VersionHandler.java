package cn.classfun.droidvm.daemon.ipc.basic;

import static cn.classfun.droidvm.daemon.Daemon.daemonHash;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.BuildConfig;
import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class VersionHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "version";
    }

    @Override
    public boolean needAuthorization() {
        return false;
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var res = request.res();
        res.put("client_id", request.getClient().getId().toString());
        res.put("version", BuildConfig.VERSION_NAME);
        res.put("version_code", BuildConfig.VERSION_CODE);
        res.put("pkg_name", BuildConfig.APPLICATION_ID);
        res.put("build_type", BuildConfig.BUILD_TYPE);
        res.put("daemon_hash", daemonHash);
    }
}
