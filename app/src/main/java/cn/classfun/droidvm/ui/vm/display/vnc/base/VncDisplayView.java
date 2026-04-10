package cn.classfun.droidvm.ui.vm.display.vnc.base;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

public final class VncDisplayView extends AppCompatImageView {
    public interface TextCommitListener {
        void onCommitText(@NonNull CharSequence text);

        void onDeleteSurrounding(int beforeLength, int afterLength);
    }

    @Nullable
    private TextCommitListener textCommitListener;

    public VncDisplayView(@NonNull Context context) {
        super(context);
    }

    public VncDisplayView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public VncDisplayView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setTextCommitListener(@Nullable TextCommitListener listener) {
        this.textCommitListener = listener;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @NonNull
    @Override
    public InputConnection onCreateInputConnection(@NonNull EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_NULL;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (text != null && textCommitListener != null)
                    textCommitListener.onCommitText(text);
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (textCommitListener != null)
                    textCommitListener.onDeleteSurrounding(beforeLength, afterLength);
                return true;
            }
        };
    }
}

