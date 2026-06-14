package cn.classfun.droidvm.lib.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.TextInputLayout;

import cn.classfun.droidvm.R;

/**
 * Helpers for the read-only boot-info fields (kernel / initrd / cmdline /
 * source). They show values the user may want to copy but not type into, so
 * each becomes selectable (long-press → Copy) and its enclosing
 * {@link TextInputLayout} gets a copy end-icon.
 */
public final class CopyableField {
    private CopyableField() {
    }

    /**
     * Read-only but selectable (long-press Copy works), plus a copy end-icon.
     * {@code setTextIsSelectable} alone does <em>not</em> stop an EditText from
     * being typed into — only dropping the key listener does — so we do both
     * and suppress the soft keyboard / caret.
     */
    public static void setupReadOnly(@NonNull EditText et, @NonNull CharSequence label) {
        makeReadOnly(et);
        addCopyIcon(et, label);
    }

    /** Make an EditText non-editable yet still selectable and copyable. */
    public static void makeReadOnly(@NonNull EditText et) {
        et.setKeyListener(null);           // no text input -> not editable
        et.setTextIsSelectable(true);      // long-press select + Copy
        et.setCursorVisible(false);
        et.setShowSoftInputOnFocus(false); // tapping never raises the keyboard
    }

    /**
     * Just the copy end-icon — for a field that must stay editable to capture
     * input (e.g. one that redirects edits elsewhere) yet still offers copy.
     */
    public static void addCopyIcon(@NonNull EditText et, @NonNull CharSequence label) {
        var til = enclosingLayout(et);
        if (til == null) return;
        til.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        til.setEndIconDrawable(R.drawable.ic_content_copy);
        til.setEndIconContentDescription(et.getContext().getString(R.string.field_copy));
        til.setEndIconOnClickListener(v -> {
            var t = et.getText();
            copy(et.getContext(), t == null ? "" : t.toString(), label);
        });
    }

    /** Puts {@code text} on the clipboard and confirms with a short toast. */
    public static void copy(
        @NonNull Context ctx, @NonNull CharSequence text, @NonNull CharSequence label) {
        var cm = ctx.getSystemService(ClipboardManager.class);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(ctx, R.string.field_copied, Toast.LENGTH_SHORT).show();
    }

    @Nullable
    private static TextInputLayout enclosingLayout(@NonNull View v) {
        ViewParent p = v.getParent();
        while (p != null && !(p instanceof TextInputLayout)) p = p.getParent();
        return (TextInputLayout) p;
    }
}
