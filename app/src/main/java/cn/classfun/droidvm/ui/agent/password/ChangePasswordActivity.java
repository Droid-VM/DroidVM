package cn.classfun.droidvm.ui.agent.password;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.ui.agent.AgentOperationActivity;
import cn.classfun.droidvm.ui.agent.base.AgentVM;

public final class ChangePasswordActivity extends AppCompatActivity {
    private static final String TAG = "ChangePasswordActivity";
    public static final String EXTRA_DISK_ID = "disk_id";
    private TextInputLayout tilPassword;
    private TextInputEditText etPassword;
    private TextInputLayout tilConfirmPassword;
    private TextInputEditText etConfirmPassword;
    private MaterialToolbar toolbar;
    private CollapsingToolbarLayout collapsingToolbar;
    private FloatingActionButton fabConfirm;
    private TextView tvDiskName;
    private MaterialSwitch swNormalUsers;
    private UUID diskId;

    @NonNull
    public static Intent createIntent(@NonNull Context context, @NonNull UUID diskId) {
        var intent = new Intent(context, ChangePasswordActivity.class);
        intent.putExtra(EXTRA_DISK_ID, diskId.toString());
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_password);
        toolbar = findViewById(R.id.toolbar);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        tvDiskName = findViewById(R.id.tv_disk_name);
        tilPassword = findViewById(R.id.til_password);
        etPassword = findViewById(R.id.et_password);
        tilConfirmPassword = findViewById(R.id.til_confirm_password);
        etConfirmPassword = findViewById(R.id.et_confirm_password);
        swNormalUsers = findViewById(R.id.sw_normal_users);
        fabConfirm = findViewById(R.id.fab_confirm);
        initialize();
    }

    private void initialize() {
        collapsingToolbar.setTitle(getString(R.string.change_password_title));
        toolbar.setNavigationOnClickListener(v -> finish());
        var diskIdStr = getIntent().getStringExtra(EXTRA_DISK_ID);
        if (diskIdStr == null) {
            Log.e(TAG, "Missing disk_id extra");
            finish();
            return;
        }
        diskId = UUID.fromString(diskIdStr);
        var diskStore = new DiskStore();
        diskStore.load(this);
        var diskConfig = diskStore.findById(diskId);
        if (diskConfig == null) {
            Log.e(TAG, fmt("Disk not found: %s", diskId));
            finish();
            return;
        }
        tvDiskName.setText(getString(R.string.change_password_disk_label, diskConfig.getName()));
        fabConfirm.setOnClickListener(v -> onConfirm());
    }

    private void onConfirm() {
        tilPassword.setError(null);
        tilConfirmPassword.setError(null);
        var password = etPassword.getText() != null ? etPassword.getText().toString() : "";
        var confirmPassword = etConfirmPassword.getText() != null ?
            etConfirmPassword.getText().toString() : "";
        if (password.isEmpty()) {
            tilPassword.setError(getString(R.string.change_password_error_empty));
            return;
        }
        if (!password.equals(confirmPassword)) {
            tilConfirmPassword.setError(getString(R.string.change_password_error_mismatch));
            return;
        }
        boolean changeNormalUsers = swNormalUsers.isChecked();
        var diskStore = new DiskStore();
        diskStore.load(this);
        var diskConfig = diskStore.findById(diskId);
        if (diskConfig == null) {
            Log.e(TAG, fmt("Disk not found: %s", diskId));
            finish();
            return;
        }
        var agentVM = new AgentVM();
        var action = new PasswordAction(agentVM);
        action.setPassword(password);
        action.setChangeNormalUsers(changeNormalUsers);
        agentVM.addDisk(diskConfig);
        var intent = AgentOperationActivity.createIntent(this, agentVM);
        startActivity(intent);
        finish();
    }
}
