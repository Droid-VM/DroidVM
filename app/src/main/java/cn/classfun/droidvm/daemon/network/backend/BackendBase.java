package cn.classfun.droidvm.daemon.network.backend;

import androidx.annotation.NonNull;

import org.json.JSONArray;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.vm.VMInstanceStore;
import cn.classfun.droidvm.lib.network.IPNetwork;

@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "unused", "UnusedReturnValue"})
public abstract class BackendBase {
    public abstract boolean createBridge(@NonNull String name);

    public abstract boolean deleteBridge(@NonNull String name);

    public abstract boolean createTap(@NonNull String name);

    public abstract boolean deleteTap(@NonNull String name);

    public abstract boolean setLinkState(@NonNull String name, boolean up);

    public abstract boolean addInterface(@NonNull String bridge, @NonNull String iface);

    public abstract boolean removeInterface(@NonNull String iface);

    public abstract boolean setMacAddress(@NonNull String dev, @NonNull String mac);

    public abstract boolean setStp(@NonNull String bridge, boolean enabled);

    public abstract boolean isInterfaceExists(@NonNull String name);

    public abstract boolean addAddress(@NonNull String dev, @NonNull IPNetwork<?, ?, ?> cidr);

    public abstract boolean removeAddress(@NonNull String dev, @NonNull IPNetwork<?, ?, ?> cidr);

    public abstract JSONArray listAddresses(@NonNull String dev);

    public abstract JSONArray listBridges();

    public abstract JSONArray listBridgeInterfaces(@NonNull String bridge);

    public abstract JSONArray listAvailableInterfaces();

    public abstract JSONArray listInterfaces(VMInstanceStore vms, String bridge);

    public abstract JSONArray listNeighbors(String bridge);

    public abstract JSONArray listDhcpLeases(String bridge);

    public abstract void populateRuleRoute(@NonNull NetworkInstance inst);
}
