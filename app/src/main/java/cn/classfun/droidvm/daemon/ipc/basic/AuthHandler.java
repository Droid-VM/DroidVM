package cn.classfun.droidvm.daemon.ipc.basic;

import static cn.classfun.droidvm.lib.daemon.DaemonHelper.getTokenFile;
import static cn.classfun.droidvm.lib.utils.FileUtils.readFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import java.io.File;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class AuthHandler extends RequestHandler {
    private static final String TAG = "AuthHandler";
    private String token = null;

    @NonNull
    @Override
    public String getName() {
        return "auth";
    }

    @Override
    public boolean needAuthorization() {
        return false;
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var j = request.getParams();
        var clientId = request.getClient().getId();
        var token = j.getString("token");
        var tokenPath = new File(getTokenFile());
        if (!tokenPath.exists())
            throw new RequestException("Token file not found");
        if (this.token == null) {
            var tokenLocal = readFile(tokenPath).trim();
            if (tokenLocal.length() != 32)
                throw new RequestException("Invalid token file");
            this.token = tokenLocal;
        }
        if (!token.equals(this.token))
            throw new RequestException("Token mismatch");
        request.getClient().authorized = true;
        Log.i(TAG, fmt("Client %s authenticated successfully", clientId));
    }
}
