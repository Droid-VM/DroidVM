package cn.classfun.droidvm.ui.logs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.daemon.Daemon.LOG_PATH;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.utils.ShareUtils;

public final class LogsActivity extends AppCompatActivity {
    private static final int MAX_LOG_LINES = 10000;
    private static final long FLUSH_INTERVAL_MS = 100;

    private RecyclerView recyclerView;
    private LogAdapter adapter;
    private RandomAccessFile logFile;
    private Thread readerThread;
    private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(8192);
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
        if (new File(LOG_PATH).exists())
            startLogcat();
        else new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs_not_found_title)
            .setMessage(R.string.logs_not_found_message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private boolean onMenuItemClick(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_save) {
            saveLogs();
            return true;
        }
        if (id == R.id.menu_share) {
            shareLogs();
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

    private String logFilename() {
        var sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        return fmt("droidvm_logs_%s.txt", sdf.format(new Date()));
    }

    private void saveLogs() {
        saveLauncher.launch(logFilename());
    }

    private void shareLogs() {
        var sb = new StringBuilder();
        for (var line : adapter.getLines()) {
            sb.append(line).append('\n');
        }
        ShareUtils.shareTextAsFile(
            this,
            logFilename(),
            sb.toString(),
            getString(R.string.logs_share_title),
            msg -> Toast.makeText(
                this,
                fmt(getString(R.string.logs_share_failed), msg),
                Toast.LENGTH_LONG
            ).show()
        );
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
            logFile = new RandomAccessFile(LOG_PATH, "r");
            logFile.seek(0);
        } catch (Exception e) {
            var lines = new ArrayList<String>();
            lines.add(e.toString());
            adapter.appendLines(lines, MAX_LOG_LINES);
            return;
        }
        running = true;
        lineBuf.reset();
        readerThread = new Thread(this::logcatThread, "LogReader");
        readerThread.setDaemon(true);
        readerThread.start();
        uiHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS);
    }

    private void logcatThread() {
        var buf = new byte[8192];
        try {
            while (running) {
                long len = logFile.length();
                long pos = logFile.getFilePointer();
                if (len < pos) {
                    logFile.seek(0);
                    pos = 0;
                }
                if (len > pos) {
                    int rl = (int) Math.min(buf.length, len - pos);
                    int n = logFile.read(buf, 0, rl);
                    if (n > 0) {
                        for (int i = 0; i < n; i++) {
                            byte b = buf[i];
                            if (b == '\n') {
                                var line = lineBuf.toString(UTF_8);
                                lineBuf.reset();
                                synchronized (pendingLines) {
                                    pendingLines.add(line);
                                    updated.set(true);
                                }
                            } else if (b != '\r')
                                lineBuf.write(b);
                        }
                        continue;
                    }
                }
                while (running && suspend.get()) {
                    //noinspection BusyWait
                    Thread.sleep(FLUSH_INTERVAL_MS);
                }
                //noinspection BusyWait
                Thread.sleep(FLUSH_INTERVAL_MS);
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
        if (logFile != null) {
            try {
                logFile.close();
            } catch (Exception ignored) {
            }
            logFile = null;
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
