package cn.classfun.droidvm.ui;

import static cn.classfun.droidvm.lib.utils.AssetUtils.extractBinaries;
import static cn.classfun.droidvm.lib.utils.AssetUtils.extractLibraries;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.utils.AssetUtils;
import cn.classfun.droidvm.lib.api.Privacy;
import cn.classfun.droidvm.ui.main.MainActivity;
import cn.classfun.droidvm.ui.setup.SetupActivity;
import cn.classfun.droidvm.ui.setup.base.BaseStepFragment;
import cn.classfun.droidvm.ui.setup.step.ExtractStepFragment;
import cn.classfun.droidvm.ui.setup.step.PrivacyStepFragment;

@SuppressLint("CustomSplashScreen")
public final class SplashActivity extends AppCompatActivity {
    private static final String TAG = "SplashActivity";
    public static final String PREFS_NAME = "setup_prefs";
    public static final String KEY_SETUP_COMPLETE = "setup_complete";
    private static final long SPLASH_DELAY_MS = 500;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private void processExtract() {
        extractBinaries(this);
        extractLibraries(this);
    }

    private void extractThread() {
        try {
            processExtract();
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract binaries", e);
        }
        mainHandler.postDelayed(this::navigateNext, SPLASH_DELAY_MS);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        runOnPool(this::extractThread);
    }

    private void navigateNext() {
        var prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        var setupComplete = prefs.getBoolean(KEY_SETUP_COMPLETE, false);
        if (!setupComplete) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        var steps = new ArrayList<Class<? extends BaseStepFragment>>();
        if (Privacy.isNeedAskPrivacy(this)) {
            Log.i(TAG, "Privacy policy needs to be agreed, launching privacy step");
            steps.add(PrivacyStepFragment.class);
        }
        if (AssetUtils.needsExtractPrebuilt(this)) {
            Log.i(TAG, "Prebuilt needs re-extraction, launching extract step");
            steps.add(ExtractStepFragment.class);
        }
        Intent intent;
        if (steps.isEmpty()) {
            intent = new Intent(this, MainActivity.class);
        } else {
            intent = SetupActivity.createSingleStepIntent(this, steps);
        }
        startActivity(intent);
        finish();
    }
}
