package cn.classfun.droidvm.ui.logs;

import static cn.classfun.droidvm.lib.utils.FileUtils.findExecute;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonHelper;

public final class LogsActivity extends AppCompatActivity {
    private static final int MAX_LOG_LINES = 10000;
    private static final long FLUSH_INTERVAL_MS = 100;

    private RecyclerView recyclerView;
    private LogAdapter adapter;
    private Process logcatProcess;
    private Thread readerThread;
    private volatile boolean autoScroll = true;
    private final List<String> pendingLines = new ArrayList<>();
    private final AtomicBoolean updated = new AtomicBoolean(false);
    private final AtomicBoolean suspend = new AtomicBoolean(false);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable flushRunnable = this::flushBuffer;
    private volatile boolean running = false;
    private final ActivityResultLauncher<String> saveLauncher =
        registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"),
            this::onSaveResult
        );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.logs_title);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        recyclerView = findViewById(R.id.recycler_logs);
        var layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setItemAnimator(null);
        adapter = new LogAdapter();
        recyclerView.setAdapter(adapter);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!rv.canScrollVertically(1)) {
                    autoScroll = true;
                } else if (dy < 0) {
                    autoScroll = false;
                }
            }
        });
        startLogcat();
    }

    private boolean onMenuItemClick(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_save) {
            saveLogs();
            return true;
        }
        if (id == R.id.menu_scroll_bottom) {
            scrollToBottom();
            return true;
        }
        if (id == R.id.menu_clear) {
            clearAndRestart();
            return true;
        }
        return false;
    }

    private void saveLogs() {
        var sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        var filename = fmt("droidvm_logs_%s.txt", sdf.format(new Date()));
        saveLauncher.launch(filename);
    }

    private void onSaveResult(Uri uri) {
        if (uri == null) return;
        try (
            var os = getContentResolver().openOutputStream(uri);
            var writer = new BufferedWriter(new OutputStreamWriter(os))
        ) {
            for (var line : adapter.getLines()) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
            Toast.makeText(this, R.string.logs_save_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            var msg = fmt(getString(R.string.logs_save_failed), e.getMessage());
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }
    }

    private void startLogcat() {
        autoScroll = true;
        adapter.clear();
        synchronized (pendingLines) {
            pendingLines.clear();
        }
        updated.set(false);
        try {
            var pid = DaemonHelper.readPid();
            if (pid < 0) throw new RuntimeException("Daemon is not running");
            var cmd = fmt("logcat --pid=%d", pid);
            var shell = findExecute("su", "/system/bin/su");
            var builder = new ProcessBuilder(shell, "-c", cmd);
            builder.redirectErrorStream(true);
            logcatProcess = builder.start();
        } catch (Exception e) {
            var lines = new ArrayList<String>();
            lines.add(e.toString());
            adapter.appendLines(lines, MAX_LOG_LINES);
            return;
        }
        running = true;
        readerThread = new Thread(this::logcatThread, "LogcatReader");
        readerThread.setDaemon(true);
        readerThread.start();
        uiHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS);
    }

    private void logcatThread() {
        try (
            var input = new InputStreamReader(logcatProcess.getInputStream());
            var reader = new BufferedReader(input, 16384)
        ) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                synchronized (pendingLines) {
                    pendingLines.add(line);
                    updated.set(true);
                }
                while (running && suspend.get()) {
                    //noinspection BusyWait
                    Thread.sleep(FLUSH_INTERVAL_MS);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void flushBuffer() {
        if (!running || suspend.get()) {
            if (running) uiHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS);
            return;
        }
        if (updated.compareAndSet(true, false)) {
            List<String> batch;
            synchronized (pendingLines) {
                batch = new ArrayList<>(pendingLines);
                pendingLines.clear();
            }
            if (!batch.isEmpty()) {
                adapter.appendLines(batch, MAX_LOG_LINES);
                if (autoScroll)
                    recyclerView.scrollToPosition(adapter.getItemCount() - 1);
            }
        }
        uiHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS);
    }

    private void stopLogcat() {
        running = false;
        uiHandler.removeCallbacks(flushRunnable);
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
    }

    private void scrollToBottom() {
        autoScroll = true;
        if (adapter.getItemCount() > 0) {
            recyclerView.scrollToPosition(adapter.getItemCount() - 1);
        }
    }

    private void clearAndRestart() {
        stopLogcat();
        startLogcat();
    }

    @Override
    protected void onPause() {
        super.onPause();
        suspend.set(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        suspend.set(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLogcat();
    }
}
