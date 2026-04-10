package cn.classfun.droidvm.lib.ui;

import android.text.Editable;
import android.text.TextWatcher;

import androidx.annotation.NonNull;

import java.util.function.Consumer;

public abstract class SimpleTextWatcher implements TextWatcher {
    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
    }

    @NonNull
    public static TextWatcher simpleAfterTextWatcher(@NonNull Consumer<Editable> consumer) {
        return new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                consumer.accept(s);
            }
        };
    }
}