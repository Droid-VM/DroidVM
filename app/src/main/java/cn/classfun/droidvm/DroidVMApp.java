package cn.classfun.droidvm;

import android.app.Application;
import android.util.Log;

import com.google.android.material.color.DynamicColors;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.VMEventHandler;
import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.ImeInsetsApplier;
import cn.classfun.droidvm.lib.utils.ThreadUtils;
import cn.classfun.droidvm.ui.main.settings.KernelModuleManager;

public final class DroidVMApp extends Application {
    private static final String TAG = "DroidVMApp";
    private VMEventHandler vmEventHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        registerActivityLifecycleCallbacks(new ImeInsetsApplier());
        vmEventHandler = new VMEventHandler(this);
        registerActivityLifecycleCallbacks(vmEventHandler);
        DaemonConnection.getInstance().addListener(vmEventHandler);
        ThreadUtils.runOnPool(() -> {
            initializeStore(new VMStore());
            initializeStore(new DiskStore());
            initializeStore(new NetworkStore());
            try {
                KernelModuleManager.applyAutostart(this);
            } catch (Exception e) {
                Log.w(TAG, "kernel module autostart failed", e);
            }
        });
    }

    private void initializeStore(DataStore<?> store) {
        try {
            if (!store.getStoreFile(this).exists())
                store.save(this);
        } catch (Exception e) {
            Log.w(TAG, String.format(
                "Failed to initialize store: %s",
                store.getClass().getSimpleName()
            ), e);
        }
    }

    public VMEventHandler getVMEventHandler() {
        return vmEventHandler;
    }

    @Override
    public void onTerminate() {
        DaemonConnection.getInstance().removeListener(vmEventHandler);
        unregisterActivityLifecycleCallbacks(vmEventHandler);
        DaemonConnection.getInstance().shutdown();
        super.onTerminate();
    }
}
