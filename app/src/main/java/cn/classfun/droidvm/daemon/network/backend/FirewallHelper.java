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

    /**
     * Installs the host-IP-independent half of a port forward: the FORWARD
     * ACCEPT for the guest target and, when hairpinSubnet is set, the
     * NAT-loopback MASQUERADE so a guest in that subnet reaching this forward
     * via the host has its source masqueraded (reply returns via the gateway).
     * The actual DNAT is installed per host IP via {@link #applyDnat}.
     */
    public abstract boolean applyForwardBase(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        int guestStart, int guestEnd, @Nullable String hairpinSubnet);

    public abstract boolean removeForwardBase(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        int guestStart, int guestEnd, @Nullable String hairpinSubnet);

    /**
     * Installs one DNAT rule scoped to a specific host IP ({@code -d hostIp}),
     * so only traffic actually destined to the phone is redirected — transit
     * traffic (other networks, tethered clients) is left alone. Called once
     * per current host IP; rules are added/removed incrementally as the host
     * IP set changes.
     */
    public abstract boolean applyDnat(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        @NonNull String hostIp, int hostStart, int hostEnd, int guestStart, int guestEnd);

    public abstract boolean removeDnat(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        @NonNull String hostIp, int hostStart, int hostEnd, int guestStart, int guestEnd);
}
