package cn.classfun.droidvm.daemon.network.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.daemon.network.NetworkInstance;

@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "unused", "UnusedReturnValue"})
public abstract class FirewallHelper {

    public abstract boolean initialize();

    public abstract boolean shutdown();

    public abstract void initNetwork(NetworkInstance inst);

    public abstract void deinitNetwork(NetworkInstance inst);

    public abstract boolean applyForward(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        @Nullable String hostIp, int hostPort, int guestPort);

    public abstract boolean removeForward(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        @Nullable String hostIp, int hostPort, int guestPort);
}
