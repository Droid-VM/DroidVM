package cn.classfun.droidvm.ui.main;

import static android.content.Intent.ACTION_VIEW;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.Constants.GITHUB_ISSUE_URL;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.Map;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonHelper;
import cn.classfun.droidvm.lib.ui.SwipeableTabActivity;
import cn.classfun.droidvm.lib.ui.UIContext;
import cn.classfun.droidvm.lib.utils.AssetUtils;
import cn.classfun.droidvm.ui.SplashActivity;
import cn.classfun.droidvm.ui.logs.LogsActivity;
import cn.classfun.droidvm.ui.main.base.MainBaseFragment;
import cn.classfun.droidvm.ui.main.base.MainFragmentEnum;

public final class MainActivity extends SwipeableTabActivity {
    private static final String TAG = "MainActivity";
    private static final String KEY_ACTIVE = "active_tag";
    private static final String KEY_HIDE_TEST_WARNING = "hide_test_warning";
    private FloatingActionButton fab;
    private CollapsingToolbarLayout collapsingToolbar;
    private AppBarLayout appBarLayout;
    private DaemonHelper daemon;
    private FragmentManager fm;
    private BottomNavigationView bottomNav;
    private MaterialToolbar toolbar;
    private boolean isSwipeSwitching = false;
    private MainFragmentEnum activeFragment;
    private Map<MainFragmentEnum, MainBaseFragment> fragments = null;

    private final AppBarLayout.Behavior appBarBehavior = new AppBarLayout.Behavior() {
        @Override
        public boolean onStartNestedScroll(
            @NonNull CoordinatorLayout parent,
            @NonNull AppBarLayout child,
            @NonNull View directTargetChild,
            @NonNull View target,
            int axes, int type
        ) {
            super.onStartNestedScroll(
                parent, child,
                directTargetChild,
                target, axes, type
            );
            return (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toolbar = findViewById(R.id.toolbar);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        appBarLayout = findViewById(R.id.app_bar);
        fab = findViewById(R.id.fab_add);
        bottomNav = findViewById(R.id.bottom_nav);
        initSwipeHelper();
        initialize(savedInstanceState);
    }

    @NonNull
    private MainBaseFragment getFragment(@NonNull MainFragmentEnum id) {
        return requireNonNull(requireNonNull(fragments).get(id));
    }

    @Override
    public int getTabCount() {
        return fragments.size();
    }

    @Override
    public int getCurrentTabIndex() {
        return activeFragment.ordinal();
    }

    @NonNull
    @Override
    public View getTabView(int index) {
        return getFragment(MainFragmentEnum.fromIndex(index)).requireView();
    }

    @Override
    public void onTabSwitchedByDrag(int newIndex) {
        if (newIndex != activeFragment.ordinal()) {
            requireNonNull(fragments);
            var newFragId = MainFragmentEnum.fromIndex(newIndex);
            var oldFrag = getFragment(activeFragment);
            var newFrag = getFragment(newFragId);
            fm.beginTransaction().hide(oldFrag).show(newFrag).commitNow();
            activeFragment = newFragId;
        }
        var curFragment = getFragment(activeFragment);
        collapsingToolbar.setTitle(getString(curFragment.getTitleResId()));
        isSwipeSwitching = true;
        bottomNav.setSelectedItemId(activeFragment.getNavId());
        isSwipeSwitching = false;
        appBarLayout.setExpanded(true, true);
    }

    private void initialize(Bundle savedInstanceState) {
        var ui = UIContext.fromActivity(this);
        this.daemon = new DaemonHelper(ui);
        setSupportActionBar(toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_logs);
        toolbar.setNavigationContentDescription(R.string.logs_title);
        toolbar.setNavigationOnClickListener(v ->
                startActivity(new Intent(this, LogsActivity.class))
        );
        var appBarParams = (CoordinatorLayout.LayoutParams) appBarLayout.getLayoutParams();
        appBarParams.setBehavior(appBarBehavior);
        fm = getSupportFragmentManager();
        if (savedInstanceState == null) {
            fragments = MainFragmentEnum.createFragments();
            var trans = fm.beginTransaction();
            for (var id : fragments.keySet()) {
                var fragment = requireNonNull(fragments.get(id));
                trans.add(R.id.main_container, fragment, id.name());
                if (fragment.isDefaultHide())
                    trans.hide(fragment);
                else
                    activeFragment = id;
            }
            trans.commit();
        } else {
            fragments = MainFragmentEnum.loadFragments(fm);
            var defaultId = MainFragmentEnum.DEFAULT;
            var activeTag = savedInstanceState.getString(KEY_ACTIVE, defaultId.name());
            var activeId = MainFragmentEnum.fromTag(activeTag);
            activeFragment = activeId != null ? activeId : defaultId;
        }
        var curFragment = getFragment(activeFragment);
        bottomNav.setSelectedItemId(activeFragment.getNavId());
        collapsingToolbar.setTitle(getString(curFragment.getTitleResId()));
        updateFab(curFragment.isShowFab());
        bottomNav.setOnItemSelectedListener(this::bottomNavOnItemSelected);
        findViewById(R.id.main_container).post(() ->
            swipeHelper.setTabImmediate(activeFragment.ordinal())
        );
        if (showTestBuildWarning())
            launchDaemon();
    }

    private boolean bottomNavOnItemSelected(@NonNull MenuItem item) {
        var id = MainFragmentEnum.fromNavId(item.getItemId());
        if (id == null) return false;
        var target = getFragment(id);
        collapsingToolbar.setTitle(getString(target.getTitleResId()));
        appBarLayout.setExpanded(true, true);
        updateFab(target.isShowFab());
        if (isSwipeSwitching) return true;
        if (id != activeFragment) {
            var oldFrag = getFragment(activeFragment);
            int direction = Integer.compare(id.ordinal(), activeFragment.ordinal());
            activeFragment = id;
            swipeHelper.animateToTab(id.ordinal(), direction, () ->
                fm.beginTransaction()
                    .hide(oldFrag)
                    .show(target)
                    .commitNow()
            );
        }
        return true;
    }

    private void launchDaemon() {
        runOnPool(() -> {
            if (!DaemonHelper.isDaemonRunning()) {
                Log.i(TAG, "Starting droidvmd...");
                daemon.startDaemon();
            } else {
                Log.d(TAG, "droidvmd already running");
            }
        });
    }

    private boolean showTestBuildWarning() {
        if (!AssetUtils.isAssetFileExists(this, "testBuildConfig.json")) return true;
        var prefs = getSharedPreferences(SplashActivity.PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_HIDE_TEST_WARNING, false)) return true;
        var layout = new LinearLayout(this);
        var checkBox = new CheckBox(this);
        checkBox.setText(R.string.test_build_warning_hide);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);
        layout.addView(checkBox);
        Runnable onAccept = () -> {
            if (checkBox.isChecked())
                prefs.edit().putBoolean(KEY_HIDE_TEST_WARNING, true).apply();
            launchDaemon();
        };
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.test_build_warning_title)
            .setMessage(R.string.test_build_warning_message)
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok, (d, w) -> onAccept.run())
            .setNegativeButton(android.R.string.cancel, (d, w) -> finishAffinity())
            .setNeutralButton(R.string.settings_feedback_title, (d, w) -> {
                onAccept.run();
                var intent = new Intent(ACTION_VIEW, Uri.parse(GITHUB_ISSUE_URL));
                startActivity(intent);
            })
            .show();
        return false;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_ACTIVE, activeFragment.name());
    }

    private void updateFab(boolean showFab) {
        if (showFab) {
            fab.setOnClickListener(v -> getFragment(activeFragment).onFabClick(v));
            if (fab.getVisibility() != VISIBLE) {
                fab.setVisibility(VISIBLE);
                fab.setScaleX(0f);
                fab.setScaleY(0f);
                fab.setAlpha(0f);
                fab.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(250)
                    .setInterpolator(new OvershootInterpolator())
                    .start();
            }
        } else {
            if (fab.getVisibility() == VISIBLE) {
                fab.animate()
                    .scaleX(0f).scaleY(0f).alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> fab.setVisibility(GONE))
                    .start();
            }
        }
    }
}
