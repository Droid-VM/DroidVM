package cn.classfun.droidvm.ui.main.base;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import java.util.HashMap;
import java.util.Map;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.main.disk.MainDiskFragment;
import cn.classfun.droidvm.ui.main.home.MainHomeFragment;
import cn.classfun.droidvm.ui.main.network.MainNetworkFragment;
import cn.classfun.droidvm.ui.main.settings.MainSettingsFragment;
import cn.classfun.droidvm.ui.main.vm.MainVMFragment;

public enum MainFragmentEnum {
    FRAGMENT_HOME(MainHomeFragment.class, R.id.nav_home),
    FRAGMENT_VM(MainVMFragment.class, R.id.nav_vm),
    FRAGMENT_DISK(MainDiskFragment.class, R.id.nav_disk),
    FRAGMENT_NETWORK(MainNetworkFragment.class, R.id.nav_network),
    FRAGMENT_SETTINGS(MainSettingsFragment.class, R.id.nav_settings);

    public final static MainFragmentEnum DEFAULT = FRAGMENT_HOME;

    private final Class<? extends MainBaseFragment> fragmentClass;
    private final @IdRes int navId;

    MainFragmentEnum(Class<? extends MainBaseFragment> fragmentClass, @IdRes int navId) {
        this.fragmentClass = fragmentClass;
        this.navId = navId;
    }

    public Class<? extends MainBaseFragment> getFragmentClass() {
        return fragmentClass;
    }

    @IdRes
    public int getNavId() {
        return navId;
    }

    @NonNull
    public MainBaseFragment createFragment() {
        try {
            var cons = getFragmentClass().getConstructor();
            var frag = cons.newInstance();
            frag.fragmentId = this;
            return frag;
        } catch (Exception e) {
            throw new RuntimeException(fmt(
                "Failed to create fragment instance: %s",
                fragmentClass.getName()
            ), e);
        }
    }

    @NonNull
    public static Map<MainFragmentEnum, MainBaseFragment> createFragments() {
        var map = new HashMap<MainFragmentEnum, MainBaseFragment>();
        for (var e : values())
            map.put(e, e.createFragment());
        return map;
    }

    @NonNull
    public static Map<MainFragmentEnum, MainBaseFragment> loadFragments(FragmentManager fm) {
        var map = new HashMap<MainFragmentEnum, MainBaseFragment>();
        for (var e : values()) {
            var f = fm.findFragmentByTag(e.name());
            if (!(f instanceof MainBaseFragment))
                throw new RuntimeException(fmt("Fragment with tag '%s' is not found", e.name()));
            map.put(e, (MainBaseFragment) f);
        }
        return map;
    }

    @NonNull
    public static MainFragmentEnum fromIndex(int index) {
        var values = values();
        if (index < 0 || index >= values.length) throw new IndexOutOfBoundsException(fmt(
            "Index %d is out of bounds for MainFragmentEnum", index
        ));
        return values[index];
    }

    @Nullable
    public static MainFragmentEnum fromTag(@NonNull String tag) {
        try {
            return valueOf(tag);
        } catch (Exception ignored) {
        }
        return null;
    }

    @Nullable
    public static MainFragmentEnum fromNavId(@IdRes int navId) {
        for (var e : values())
            if (e.getNavId() == navId)
                return e;
        return null;
    }
}
