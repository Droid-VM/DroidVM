package cn.classfun.droidvm.ui.network.info;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;

/**
 * Read-only viewer for a network helper tool's captured stdout/stderr
 * (pbridge / gvswitch / bridgedhcp). Polls the daemon while resumed and
 * keeps the view pinned to the bottom unless the user scrolled up.
 */
public final class NetworkToolLogActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed = false;
    private String networkId, tool;
    private TextView tvLog;
    private NestedScrollView scroll;
    private final Runnable poll = () -> {
        fetch();
        schedule();
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_tool_log);
        networkId = getIntent().getStringExtra("network_id");
        tool = getIntent().getStringExtra("tool");
        if (networkId == null || tool == null) {
            finish();
            return;
        }
        var title = getIntent().getStringExtra("title");
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(title != null ? title : tool);
        toolbar.setNavigationOnClickListener(v -> finish());
        tvLog = findViewById(R.id.tv_log);
        scroll = findViewById(R.id.scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        fetch();
        schedule();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(poll);
    }

    private void schedule() {
        if (resumed) handler.postDelayed(poll, 2000);
    }

    private void fetch() {
        DaemonConnection.getInstance().buildRequest("network_tool_log")
            .put("network_id", networkId)
            .put("tool", tool)
            .onResponse(resp -> {
                var lines = resp.optJSONArray("lines");
                var sb = new StringBuilder();
                if (lines != null)
                    for (int i = 0; i < lines.length(); i++)
                        sb.append(lines.optString(i, "")).append('\n');
                var text = sb.length() == 0
                    ? getString(R.string.network_info_tool_log_empty) : sb.toString();
                handler.post(() -> {
                    if (isFinishing()) return;
                    boolean atBottom = !scroll.canScrollVertically(1);
                    tvLog.setText(text);
                    if (atBottom) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                });
            })
            .onUnsuccessful(r -> {})
            .onError(e -> {})
            .invoke();
    }
}
