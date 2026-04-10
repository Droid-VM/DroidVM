package cn.classfun.droidvm.ui.vm.edit;

import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.SwipeableTabActivity;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditTab;

public final class VMEditActivity extends SwipeableTabActivity {
    private static final String TAG = "VMEditActivity";
    public static final String EXTRA_VM_ID = "vm_id";
    private FloatingActionButton fab;
    private CollapsingToolbarLayout collapsingToolbar;
    private MaterialToolbar toolbar;
    private TabLayout tabLayout;
    private NestedScrollView scrollView;
    private VMEditTab currentTab = VMEditTab.DEFAULT;
    private List<VMEditBaseTab> tabs;
    public ActivityResultLauncher<String[]> filePickerLauncher;
    public ActivityResultLauncher<Uri> folderPickerLauncher;
    public Consumer<Uri> currentPicker = null;
    public boolean editMode = false;
    public UUID editVMId = null;

    private void pickerResult(Uri uri) {
        if (currentPicker != null && uri != null)
            currentPicker.accept(uri);
        currentPicker = null;
    }

    private void folderPickerResult(Uri uri) {
        if (currentPicker != null && uri != null)
            currentPicker.accept(uri);
        currentPicker = null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vm_edit);
        tabs = createTabInstances();
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        toolbar = findViewById(R.id.toolbar);
        tabLayout = findViewById(R.id.tab_layout);
        scrollView = findViewById(R.id.scroll_view);
        fab = findViewById(R.id.fab_create);
        initSwipeHelper();
        tabs.forEach(VMEditBaseTab::initView);
        initialize();
    }

    @Override
    public int getTabCount() {
        return VMEditTab.values().length;
    }

    @Override
    public int getCurrentTabIndex() {
        return currentTab.ordinal();
    }

    @NonNull
    @Override
    public View getTabView(int index) {
        return requireNonNull(findViewById(VMEditTab.values()[index].getLayoutId()));
    }

    @Override
    public void onTabSwitchedByDrag(int newIndex) {
        currentTab = VMEditTab.fromIndex(newIndex);
        tabLayout.selectTab(tabLayout.getTabAt(newIndex));
        scrollView.scrollTo(0, 0);
    }

    private void setupTabs() {
        for (var tab : VMEditTab.values())
            tabLayout.addTab(tabLayout.newTab().setText(tab.getTitleId()));
        swipeHelper.setTabImmediate(currentTab.ordinal());
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int newPos = tab.getPosition();
                if (swipeHelper.isSettling()) return;
                int direction = Integer.compare(newPos, currentTab.ordinal());
                currentTab = VMEditTab.fromIndex(newPos);
                swipeHelper.animateToTab(newPos, direction);
                scrollView.scrollTo(0, 0);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void switchToTab(@NonNull VMEditTab tabIndex) {
        var tab = tabLayout.getTabAt(tabIndex.ordinal());
        if (tab != null) tabLayout.selectTab(tab);
    }

    private void initialize() {
        var intent = getIntent();
        var doc = new ActivityResultContracts.OpenDocument();
        filePickerLauncher = registerForActivityResult(doc, this::pickerResult);
        var tree = new ActivityResultContracts.OpenDocumentTree();
        folderPickerLauncher = registerForActivityResult(tree, this::folderPickerResult);
        var editVMId = intent.getStringExtra(EXTRA_VM_ID);
        if (editVMId != null)
            this.editVMId = UUID.fromString(editVMId);
        editMode = editVMId != null;
        toolbar.setNavigationOnClickListener(v -> finish());
        if (editMode) collapsingToolbar.setTitle(getString(R.string.edit_vm_title));
        fab.setOnClickListener(v -> doSave());
        setupTabs();
        tabs.forEach(VMEditBaseTab::initValue);
        if (editMode) loadExistingConfig();
    }

    private void loadExistingConfig() {
        var store = new VMStore();
        store.load(this);
        var config = store.findById(editVMId);
        if (config == null) {
            finish();
            return;
        }
        tabs.forEach(tab -> tab.loadConfig(config));
    }

    @NonNull
    public List<VMEditBaseTab> createTabInstances() {
        var list = new ArrayList<VMEditBaseTab>();
        for (var tab : VMEditTab.values())
            list.add(tab.createTabInstance(this));
        return list;
    }

    private void doSave() {
        var store = new VMStore();
        store.load(this);
        boolean valid = true;
        VMEditTab firstErrorTab = null;
        for (var tab : tabs) {
            boolean success;
            try {
                success = tab.validateInput(store);
            } catch (Exception e) {
                Log.w(TAG, fmt(
                    "Failed to validate input from tab %s",
                    tab.getClass().getSimpleName()
                ), e);
                success = false;
            }
            if (!success) {
                if (firstErrorTab == null)
                    firstErrorTab = VMEditTab.fromTabClass(tab.getClass());
                valid = false;
            }
        }
        if (!valid) {
            switchToTab(firstErrorTab);
            return;
        }
        VMConfig config;
        if (editMode) {
            config = store.findById(editVMId);
            if (config == null) {
                finish();
                return;
            }
        } else {
            config = new VMConfig();
        }
        for (var tab : tabs) {
            try {
                tab.saveConfig(config);
            } catch (Exception e) {
                Log.w(TAG, fmt(
                    "Failed to save config from tab %s",
                    tab.getClass().getSimpleName()
                ), e);
                switchToTab(VMEditTab.fromTabClass(tab.getClass()));
                Toast.makeText(this, R.string.create_vm_error_save, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (editMode) {
            store.update(config);
        } else {
            store.add(config);
        }
        store.save(this);
        setResult(RESULT_OK);
        finish();
    }
}
