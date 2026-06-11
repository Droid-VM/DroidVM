package cn.classfun.droidvm.daemon.network.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.lib.network.IPv6Network;

@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "unused", "UnusedReturnValue"})
public abstract class FirewallHelper {

    public abstract boolean initialize();

    public abstract boolean shutdown();

    public abstract void initNetwork(NetworkInstance inst);

    public abstract void deinitNetwork(NetworkInstance inst);

    /** Allow traffic for a delegated prefix acquired while the network runs. */
    public abstract void addLiveV6Subnet(
        @NonNull NetworkInstance inst, @NonNull String dev, @NonNull IPv6Network net);

    public abstract void removeLiveV6Subnet(
        @NonNull NetworkInstance inst, @NonNull String dev, @NonNull IPv6Network net);

    public abstract boolean applyForward(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        @Nullable String hostIp, int hostPort, int guestPort);

    public abstract boolean removeForward(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        @Nullable String hostIp, int hostPort, int guestPort);

    public abstract boolean applyForwardRange(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        @Nullable String hostIp, int hostStart, int hostEnd, int guestStart, int guestEnd);

    public abstract boolean removeForwardRange(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        @Nullable String hostIp, int hostStart, int hostEnd, int guestStart, int guestEnd);
}
