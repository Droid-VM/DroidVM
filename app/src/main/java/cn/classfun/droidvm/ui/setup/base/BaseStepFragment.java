package cn.classfun.droidvm.ui.setup.base;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import java.util.function.Consumer;

import cn.classfun.droidvm.ui.setup.SetupActivity;

public abstract class BaseStepFragment extends Fragment {
    public SetupActivity activity;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
    }

    public boolean isHiddenStep() {
        return false;
    }

    protected boolean optBoolean(String key, boolean defaultValue) {
        if (activity == null)
            return defaultValue;
        if (!activity.sharedData.containsKey(key))
            return defaultValue;
        var val = activity.sharedData.get(key);
        if (val instanceof Boolean)
            return (Boolean) val;
        return defaultValue;
    }

    protected void putData(String key, Object value) {
        activity.sharedData.put(key, value);
    }

    protected void triggerEvent(String key) {
        Log.i("BaseStepFragment", fmt("Trigger event: %s", key));
        for (var entry : activity.sharedEvent.values())
            entry.accept(key);
    }

    protected void addEventListener(String key, Consumer<String> listener) {
        activity.sharedEvent.put(key, listener);
    }

    protected void removeEventListener(String key) {
        activity.sharedEvent.remove(key);
    }
}
