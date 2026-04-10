package cn.classfun.droidvm.ui.disk.info;

import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.ui.SwipeableTabActivity;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.ui.disk.info.base.DiskInfoBaseTab;
import cn.classfun.droidvm.ui.disk.info.base.DiskInfoTab;

public final class DiskInfoActivity extends SwipeableTabActivity {
    private static final String TAG = "DiskInfoActivity";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private UUID diskId;
    private DiskStore store;
    private CollapsingToolbarLayout collapsingToolbar;
    private MaterialToolbar toolbar;
    private TabLayout tabLayout;
    private FrameLayout tabContainer;
    private int currentTabIndex = 0;
    private List<DiskInfoBaseTab> tabs;
    public FloatingActionButton fab;
    public DiskConfig config;
    public JSONObject imageInfo;
    public JSONArray backingChain;
    public JSONArray snapshots;
    public String rawText;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_disk_info);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        tabLayout = findViewById(R.id.tab_layout);
        tabContainer = findViewById(R.id.tab_container);
        toolbar = findViewById(R.id.toolbar);
        fab = findViewById(R.id.fab_create_snapshot);
        initialize();
    }

    private void initialize() {
        var id = getIntent().getStringExtra("target_id");
        if (id == null) {
            finish();
            return;
        }
        diskId = UUID.fromString(id);
        toolbar.setNavigationOnClickListener(v -> finish());
        initSwipeHelper();
        store = new DiskStore();
        store.load(this);
        config = store.findById(diskId);
        setupTabs();
    }

    private void setupTabs() {
        var tabValues = DiskInfoTab.values();
        tabContainer.removeAllViews();
        tabLayout.clearOnTabSelectedListeners();
        tabLayout.removeAllTabs();
        tabs = new ArrayList<>(tabValues.length);
        for (var tab : tabValues) {
            var tabInstance = tab.createTabInstance(this, tabContainer);
            if (!tabInstance.isShow()) {
                tabContainer.removeView(tabInstance.getScrollView());
                continue;
            }
            tabLayout.addTab(tabLayout.newTab().setText(tab.getTitleId()));
            tabs.add(tabInstance);
            tabInstance.onCreateView();
        }
        currentTabIndex = 0;
        swipeHelper.setTabImmediate(0);
        tabs.get(0).onTabSelected();
        tabLayout.setVisibility(tabs.size() > 1 ? View.VISIBLE : View.GONE);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int newPos = tab.getPosition();
                if (swipeHelper.isSettling()) return;
                int oldPos = currentTabIndex;
                int direction = Integer.compare(newPos, oldPos);
                currentTabIndex = newPos;
                if (oldPos >= 0 && oldPos < tabs.size())
                    tabs.get(oldPos).onTabDeselected();
                tabs.get(newPos).onTabSelected();
                swipeHelper.animateToTab(newPos, direction);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
    }

    @Override
    public int getTabCount() {
        return tabs != null ? tabs.size() : 0;
    }

    @Override
    public int getCurrentTabIndex() {
        return currentTabIndex;
    }

    @NonNull
    @Override
    public View getTabView(int index) {
        return tabs.get(index).getScrollView();
    }

    @Override
    public void onTabSwitchedByDrag(int newIndex) {
        int oldIndex = currentTabIndex;
        currentTabIndex = newIndex;
        if (oldIndex >= 0 && oldIndex < tabs.size())
            tabs.get(oldIndex).onTabDeselected();
        tabs.get(newIndex).onTabSelected();
        tabLayout.selectTab(tabLayout.getTabAt(newIndex));
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadConfig();
    }

    public void reloadData() {
        loadImageDetails();
    }

    private void reloadConfig() {
        runOnPool(() -> {
            store.load(this);
            config = store.findById(diskId);
            runOnUiThread(() -> {
                if (config == null) {
                    finish();
                    return;
                }
                collapsingToolbar.setTitle(config.getName());
                tabs.forEach(DiskInfoBaseTab::onConfigLoaded);
                loadImageDetails();
            });
        });
    }

    private void loadImageDetails() {
        var fullPath = config.getFullPath();
        runOnPool(() -> {
            JSONObject jsonInfo = null;
            JSONArray chainArray = null;
            String rawOutput = null;
            try {
                jsonInfo = ImageUtils.getImageInfo(fullPath);
            } catch (Exception e) {
                Log.w(TAG, "Failed to get JSON image info", e);
            }
            try {
                var result = runList(
                    getPrebuiltBinaryPath("qemu-img"),
                    "info", "--backing-chain", "--output=json", fullPath
                );
                if (result.isSuccess()) {
                    var output = result.getOutString().trim();
                    if (output.startsWith("[")) {
                        chainArray = new JSONArray(output);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to get backing chain info", e);
            }
            try {
                var result = runList(
                    getPrebuiltBinaryPath("qemu-img"),
                    "info", fullPath
                );
                if (result.isSuccess()) {
                    rawOutput = String.join("\n", result.getOutString()).trim();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to get raw image info", e);
            }
            final JSONObject info = jsonInfo;
            final JSONArray chain = chainArray;
            final String raw = rawOutput;
            mainHandler.post(() -> {
                if (isFinishing()) return;
                imageInfo = info;
                backingChain = chain;
                snapshots = info != null ? info.optJSONArray("snapshots") : null;
                rawText = raw;
                tabs.forEach(DiskInfoBaseTab::onDataLoaded);
            });
        });
    }
}
