package cn.classfun.droidvm.ui.main.home;

import static android.content.Intent.ACTION_VIEW;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.Constants.GITHUB_ISSUE_URL;
import static cn.classfun.droidvm.lib.Constants.GITHUB_WIKI_URL;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.DaemonHelper;
import cn.classfun.droidvm.lib.data.QcomChipName;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.lib.ui.UIContext;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.ui.main.base.MainBaseFragment;
import cn.classfun.droidvm.ui.main.settings.MainSettingsFragment;
import cn.classfun.droidvm.ui.update.UpdateDialog;
import cn.classfun.droidvm.ui.update.UpdateInfo;
import cn.classfun.droidvm.ui.update.VersionCheck;

public final class MainHomeFragment extends MainBaseFragment
    implements DaemonConnection.EventListener {
    private static final long REFRESH_INTERVAL_MS = 3000;
    private TextView tvDaemonStatus;
    private TextView tvDaemonPid;
    private ImageView ivDaemonCheck;
    private MaterialCardView cardDaemon;
    private TextView tvVMSummary;
    private TextView tvDiskSummary;
    private TextView tvNetworkSummary;
    private TextView tvKernelVersion;
    private TextView tvSocModel;
    private MaterialCardView cardUpdate;
    private TextView tvUpdateMessage;
    private UpdateInfo pendingUpdateInfo;
    private VMStore vmStore;
    private DiskStore diskStore;
    private NetworkStore networkStore;
    private final SummaryData data = new SummaryData();
    private final Runnable refreshRunnable = this::scheduleRefresh;
    private DaemonHelper daemon;

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_main_home;
    }

    @Override
    public int getTitleResId() {
        return R.string.app_name;
    }

    @Override
    protected @MenuRes int getCustomMenuResId() {
        return R.menu.menu_main_home;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvDaemonStatus = view.findViewById(R.id.tv_daemon_status);
        tvDaemonPid = view.findViewById(R.id.tv_daemon_pid);
        ivDaemonCheck = view.findViewById(R.id.iv_daemon_check);
        cardDaemon = view.findViewById(R.id.card_daemon);
        tvVMSummary = view.findViewById(R.id.tv_vm_summary);
        tvDiskSummary = view.findViewById(R.id.tv_disk_summary);
        tvNetworkSummary = view.findViewById(R.id.tv_network_summary);
        tvKernelVersion = view.findViewById(R.id.tv_kernel_version);
        tvSocModel = view.findViewById(R.id.tv_soc_model);
        tvUpdateMessage = view.findViewById(R.id.tv_update_message);
        cardUpdate = view.findViewById(R.id.card_update);
        vmStore = new VMStore();
        diskStore = new DiskStore();
        networkStore = new NetworkStore();
        var ui = UIContext.fromFragment(this);
        daemon = new DaemonHelper(ui);
        daemon.setOnRefreshDaemonStatus(this::onDaemonStatusChanged);
        ivDaemonCheck.setImageResource(R.drawable.ic_daemon_checking);
        cardDaemon.setCardBackgroundColor(ContextCompat.getColor(
            requireContext(), R.color.daemon_card_background_checking
        ));
        cardDaemon.setOnClickListener(this::showDaemonMenu);
        view.findViewById(R.id.card_vm).setOnClickListener(v -> navigateToTab(R.id.nav_vm));
        view.findViewById(R.id.card_disk).setOnClickListener(v -> navigateToTab(R.id.nav_disk));
        view.findViewById(R.id.card_network).setOnClickListener(v -> navigateToTab(R.id.nav_network));
        view.findViewById(R.id.card_wizard).setOnClickListener(v -> openWizard());
        view.findViewById(R.id.card_import).setOnClickListener(v -> openImport());
        view.findViewById(R.id.card_help).setOnClickListener(v -> openLink(GITHUB_WIKI_URL));
        view.findViewById(R.id.card_feedback).setOnClickListener(v -> openLink(GITHUB_ISSUE_URL));
        cardUpdate.setOnClickListener(v -> showUpdateDialog());
        checkForUpdate();
    }

    private void checkForUpdate() {
        var ctx = requireContext();
        if (!MainSettingsFragment.isAutoCheckUpdateEnabled(ctx)) return;
        new VersionCheck().check(ctx, new VersionCheck.Callback() {
            @Override
            public void onUpdateAvailable(@NonNull UpdateInfo info) {
                if (!isAdded()) return;
                pendingUpdateInfo = info;
                tvUpdateMessage.setText(getString(
                    R.string.home_card_update_available, info.getVersion()));
                cardUpdate.setVisibility(View.VISIBLE);
            }

            @Override
            public void onNoUpdate() {
                if (!isAdded()) return;
                cardUpdate.setVisibility(View.GONE);
            }

            @Override
            public void onError(@NonNull Exception e) {
                if (!isAdded()) return;
                cardUpdate.setVisibility(View.GONE);
            }
        });
    }

    private void showUpdateDialog() {
        if (pendingUpdateInfo == null) return;
        new UpdateDialog(requireContext(), pendingUpdateInfo).show();
    }

    @Override
    public void onResume() {
        super.onResume();
        DaemonConnection.getInstance().addListener(this);
        mainHandler.post(refreshRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        DaemonConnection.getInstance().removeListener(this);
        mainHandler.removeCallbacks(refreshRunnable);
    }

    private void scheduleRefresh() {
        mainHandler.removeCallbacks(refreshRunnable);
        runOnPool(() -> {
            loadSummaryData();
            mainHandler.post(() -> {
                updateSummaryView();
                mainHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
            });
        });
    }

    public void loadSummaryData() {
        try {
            data.daemonPid = DaemonHelper.readPid();
            data.daemonRunning = DaemonHelper.isDaemonRunning();
        } catch (Exception e) {
            data.daemonPid = -1;
            data.daemonRunning = false;
        }
        Context ctx;
        try {
            ctx = requireContext();
        } catch (Exception e) {
            return;
        }
        vmStore.load(ctx);
        diskStore.load(ctx);
        networkStore.load(ctx);
        data.vmTotal = vmStore.size();
        data.diskTotal = diskStore.size();
        data.networkTotal = networkStore.size();
        long totalVirtual = 0;
        for (int i = 0; i < diskStore.size(); i++) {
            try {
                var info = ImageUtils.getImageInfo(diskStore.get(i).getFullPath());
                long vs = info.optLong("virtual-size", -1);
                if (vs > 0) totalVirtual += vs;
            } catch (Exception ignored) {
            }
        }
        data.diskVirtual = totalVirtual;
        var conn = DaemonConnection.getInstance();
        var latch = new CountDownLatch(2);
        conn.buildRequest("vm_list")
            .onResponse(resp -> {
                data.vmRunning = countRunning(resp);
                latch.countDown();
            })
            .onError(e -> {
                data.vmRunning = 0;
                latch.countDown();
            })
            .invoke();
        conn.buildRequest("network_list")
            .onResponse(resp -> {
                data.networkActive = countRunning(resp);
                latch.countDown();
            })
            .onError(e -> {
                data.networkActive = 0;
                latch.countDown();
            })
            .invoke();

        try {
            //noinspection ResultOfMethodCallIgnored
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        var kv = System.getProperty("os.version");
        data.kernelVersion = kv != null ? kv : "N/A";
        try {
            var socModel = QcomChipName.getCurrentSoC();
            var chipName = new QcomChipName(ctx);
            data.socModel = chipName.lookupChipName(socModel);
        } catch (Exception e) {
            data.socModel = "N/A";
        }
    }

    public void updateSummaryView() {
        if (!isAdded()) return;
        var ctx = requireContext();
        tvDaemonStatus.setText(data.daemonRunning ?
            R.string.home_card_daemon_running :
            R.string.home_card_daemon_stopped);
        ivDaemonCheck.setImageResource(data.daemonRunning ?
            R.drawable.ic_daemon_running :
            R.drawable.ic_daemon_stopped);
        cardDaemon.setCardBackgroundColor(ContextCompat.getColor(ctx, data.daemonRunning ?
            R.color.daemon_card_background_running :
            R.color.daemon_card_background_stopped));
        if (data.daemonRunning && data.daemonPid > 0) {
            tvDaemonPid.setText(getString(R.string.home_card_daemon_pid, data.daemonPid));
            tvDaemonPid.setVisibility(View.VISIBLE);
        } else {
            tvDaemonPid.setVisibility(View.GONE);
        }
        tvVMSummary.setText(data.vmTotal == 0 && data.vmRunning == 0 ?
            getString(R.string.home_card_vm_empty) :
            getString(R.string.home_card_vm_summary, data.vmRunning, data.vmTotal));
        if (data.diskTotal == 0) {
            tvDiskSummary.setText(getString(R.string.home_card_disk_empty));
        } else {
            tvDiskSummary.setText(getString(
                R.string.home_card_disk_summary,
                data.diskTotal,
                formatSize(data.diskVirtual)
            ));
        }
        if (data.networkTotal == 0 && data.networkActive == 0) {
            tvNetworkSummary.setText(getString(R.string.home_card_network_empty));
        } else {
            tvNetworkSummary.setText(getString(
                R.string.home_card_network_summary,
                data.networkActive, data.networkTotal
            ));
        }
        tvKernelVersion.setText(data.kernelVersion);
        tvSocModel.setText(data.socModel);
    }

    private void onDaemonStatusChanged(boolean running) {
        data.daemonRunning = running;
        updateSummaryView();
    }

    private int countRunning(@NonNull JSONObject resp) {
        var arr = resp.optJSONArray("data");
        if (arr == null) return 0;
        int count = 0;
        for (int i = 0; i < arr.length(); i++) {
            var obj = arr.optJSONObject(i);
            if (obj != null && obj.optString("state").equalsIgnoreCase("running"))
                count++;
        }
        return count;
    }

    private void showDaemonMenu(View anchor) {
        var popup = new MaterialMenu(requireContext(), anchor);
        popup.inflate(R.menu.menu_daemon_operation);
        popup.setItemVisible(R.id.menu_daemon_start, !data.daemonRunning);
        popup.setItemVisible(R.id.menu_daemon_stop, data.daemonRunning);
        popup.setItemVisible(R.id.menu_daemon_restart, data.daemonRunning);
        popup.setOnMenuItemClickListener(this::onDaemonMenuClicked);
        popup.show();
    }

    private boolean onDaemonMenuClicked(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_daemon_start) {
            daemon.asyncStartDaemon();
            return true;
        } else if (id == R.id.menu_daemon_stop) {
            daemon.asyncStopDaemon();
            return true;
        } else if (id == R.id.menu_daemon_restart) {
            daemon.asyncRestartDaemon();
            return true;
        }
        return false;
    }

    private void navigateToTab(int navId) {
        var act = getActivity();
        if (act == null) return;
        var bottomNav = act.findViewById(R.id.bottom_nav);
        if (bottomNav instanceof BottomNavigationView) {
            ((BottomNavigationView) bottomNav).setSelectedItemId(navId);
        }
    }

    private void openWizard() {
        Toast.makeText(requireContext(), R.string.unimplement, LENGTH_SHORT).show();
    }

    private void openImport() {
        Toast.makeText(requireContext(), R.string.unimplement, LENGTH_SHORT).show();
    }

    private void openLink(String url) {
        startActivity(new Intent(ACTION_VIEW, Uri.parse(url)));
    }

    @Override
    public void refreshView() {
        mainHandler.removeCallbacks(refreshRunnable);
        mainHandler.post(refreshRunnable);
    }

    @Override
    public void onDaemonEvent(JSONObject data) {
        refreshView();
    }

    @Override
    public void onDaemonConnected() {
        refreshView();
    }

    @Override
    public void onDaemonDisconnected() {
        refreshView();
    }

    private static class SummaryData {
        boolean daemonRunning = false;
        int daemonPid = -1;
        int vmTotal = 0;
        int vmRunning = 0;
        int diskTotal = 0;
        long diskVirtual = 0;
        int networkTotal = 0;
        int networkActive = 0;
        String kernelVersion = "N/A";
        String socModel = "N/A";
    }
}
