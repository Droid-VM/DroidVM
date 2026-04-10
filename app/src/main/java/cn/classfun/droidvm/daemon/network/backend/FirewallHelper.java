package cn.classfun.droidvm.daemon.network.backend;

import cn.classfun.droidvm.daemon.network.NetworkInstance;

@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "unused", "UnusedReturnValue"})
public abstract class FirewallHelper {

    public abstract boolean initialize();

    public abstract boolean shutdown();

    public abstract void initNetwork(NetworkInstance inst);

    public abstract void deinitNetwork(NetworkInstance inst);
}
