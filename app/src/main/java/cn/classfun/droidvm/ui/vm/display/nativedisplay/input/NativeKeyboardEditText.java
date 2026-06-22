package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

/**
 * Invisible EditText whose only job is to host the IME for the native display. A SurfaceView is an
 * unreliable soft-keyboard target on some ROMs (showSoftInput is silently ignored), so keyboard
 * focus and input are delegated to this real editor instead.
 *
 * It uses {@code TYPE_NULL} so IMEs send raw key events (routed to the Activity's dispatchKeyEvent)
 * while keyboards that commit text report it through the listener. No text accumulates in the field.
 */
public final class NativeKeyboardEditText extends AppCompatEditText {
    public interface TextInputListener {
        void onCommitText(@NonNull CharSequence text);

        void onDeleteSurrounding(int beforeLength, int afterLength);
    }

    @Nullable
    private TextInputListener textInputListener;

    public NativeKeyboardEditText(@NonNull Context context) {
        super(context);
    }

    public NativeKeyboardEditText(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NativeKeyboardEditText(@NonNull Context context, @Nullable AttributeSet attrs,
                                  int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setTextInputListener(@Nullable TextInputListener listener) {
        this.textInputListener = listener;
    }

    @NonNull
    @Override
    public InputConnection onCreateInputConnection(@NonNull EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_NULL;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (text != null && textInputListener != null)
                    textInputListener.onCommitText(text);
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (textInputListener != null)
                    textInputListener.onDeleteSurrounding(beforeLength, afterLength);
                return true;
            }
        };
    }
}
