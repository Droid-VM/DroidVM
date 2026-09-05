// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.notes;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.LinearLayout.HORIZONTAL;
import static android.widget.LinearLayout.VERTICAL;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.BackAskHelper;
import cn.classfun.droidvm.ui.markdown.MarkdownRender;

/**
 * The Markdown notes editor: a screen of its own because notes are prose, and prose in a row-sized
 * text box is unusable the moment it is longer than a sentence.
 *
 * <p>Three modes -- source, rendered, and both -- and in "both" the split always runs along the
 * screen's short axis: upright that means one pane above the other, on its side one beside the
 * other, so each pane keeps the longest lines it can. That is decided from the container's own
 * measurements rather than the display's, so it stays right in split-screen and freeform windows.
 */
public final class VMNotesActivity extends AppCompatActivity {
    /** In: the Markdown to edit. Out (RESULT_OK): what the user left. */
    public static final String EXTRA_NOTES = "notes";
    /** In, optional: shown under the title, so it is clear whose notes these are. */
    public static final String EXTRA_SUBTITLE = "subtitle";
    /** Re-rendering on every keystroke is wasted work; this is the pause that ends a burst. */
    private static final long PREVIEW_DELAY_MS = 250;

    private enum Mode {
        CODE,
        PREVIEW,
        SPLIT,
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable renderPreview = this::renderPreview;
    private MaterialToolbar toolbar;
    private MaterialButtonToggleGroup groupMode;
    private LinearLayout splitContainer;
    private MaterialCardView paneCode;
    private NestedScrollView panePreview;
    private EditText etMarkdown;
    private ComposeView preview;
    private TextView tvPreviewEmpty;
    private FloatingActionButton fabSave;
    private BackAskHelper backAsk;
    private Mode mode = Mode.SPLIT;
    private String original = "";
    /** What the preview currently shows, so a re-layout does not recompose for nothing. */
    private String rendered = null;

    @NonNull
    public static Intent createIntent(
        @NonNull Context ctx, @NonNull String notes, @Nullable String subtitle
    ) {
        var intent = new Intent(ctx, VMNotesActivity.class);
        intent.putExtra(EXTRA_NOTES, notes);
        if (subtitle != null) intent.putExtra(EXTRA_SUBTITLE, subtitle);
        return intent;
    }

    /** The edited Markdown carried by a RESULT_OK intent, or null when the edit was abandoned. */
    @Nullable
    public static String resultOf(int resultCode, @Nullable Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return null;
        var notes = data.getStringExtra(EXTRA_NOTES);
        return notes == null ? "" : notes;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vm_notes);
        toolbar = findViewById(R.id.toolbar);
        groupMode = findViewById(R.id.group_mode);
        splitContainer = findViewById(R.id.split_container);
        paneCode = findViewById(R.id.pane_code);
        panePreview = findViewById(R.id.pane_preview);
        etMarkdown = findViewById(R.id.et_markdown);
        preview = findViewById(R.id.preview);
        tvPreviewEmpty = findViewById(R.id.tv_preview_empty);
        fabSave = findViewById(R.id.fab_save);
        initialize(savedInstanceState);
    }

    private void initialize(@Nullable Bundle savedInstanceState) {
        var intent = getIntent();
        original = intent == null ? "" : orEmpty(intent.getStringExtra(EXTRA_NOTES));
        var subtitle = intent == null ? null : intent.getStringExtra(EXTRA_SUBTITLE);
        if (subtitle != null && !subtitle.isEmpty()) toolbar.setSubtitle(subtitle);
        // BackAskHelper takes the toolbar's back arrow as well; it is armed only while there is
        // something to discard, so leaving an untouched screen costs no dialog.
        backAsk = new BackAskHelper(this);
        backAsk.setEnabled(false);
        etMarkdown.setText(savedInstanceState == null
            ? original : orEmpty(savedInstanceState.getString(EXTRA_NOTES)));
        etMarkdown.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                backAsk.setEnabled(isModified());
                schedulePreview();
            }
        });
        groupMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            mode = modeOf(checkedId);
            applyMode();
        });
        // The container's own size decides the split, so it has to be asked after every layout:
        // rotation, split-screen resize, a keyboard that takes half the screen.
        splitContainer.addOnLayoutChangeListener(
            (v, l, t, r, b, ol, ot, or, ob) -> applySplitLayout());
        fabSave.setOnClickListener(v -> save());
        groupMode.check(buttonOf(mode));
        applyMode();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(EXTRA_NOTES, text());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(renderPreview);
    }

    private void save() {
        var data = new Intent();
        data.putExtra(EXTRA_NOTES, text());
        setResult(Activity.RESULT_OK, data);
        finish();
    }

    private boolean isModified() {
        return !text().equals(original);
    }

    @NonNull
    private String text() {
        var editable = etMarkdown.getText();
        return editable == null ? "" : editable.toString();
    }

    private void applyMode() {
        boolean code = mode != Mode.PREVIEW;
        boolean rendering = mode != Mode.CODE;
        paneCode.setVisibility(code ? VISIBLE : GONE);
        panePreview.setVisibility(rendering ? VISIBLE : GONE);
        applySplitLayout();
        if (rendering) renderPreview();
    }

    /**
     * Splits along the short axis and sizes the panes in pixels.
     *
     * <p>The pixels are the point. A weighted LinearLayout measures its weighted children once
     * with an unbounded spec along the weight axis to find their natural size, and Compose throws
     * outright when a horizontally scrollable element -- which is what this renderer makes of a
     * table or a code block -- is measured with an infinite maximum width. Upright that never
     * happened, because a vertical LinearLayout only leaves the height unbounded; turning the
     * phone made the split horizontal and took the screen down on the spot. Exact sizes have no
     * such pass.
     */
    private void applySplitLayout() {
        int width = splitContainer.getWidth();
        int height = splitContainer.getHeight();
        if (width <= 0 || height <= 0) return;
        boolean horizontal = width > height;
        if (splitContainer.getOrientation() != (horizontal ? HORIZONTAL : VERTICAL))
            splitContainer.setOrientation(horizontal ? HORIZONTAL : VERTICAL);
        int available = horizontal
            ? width - splitContainer.getPaddingLeft() - splitContainer.getPaddingRight()
            : height - splitContainer.getPaddingTop() - splitContainer.getPaddingBottom();
        // Both panes share the box in split mode; alone, a pane gets all of it.
        int share = mode == Mode.SPLIT ? available / 2 : available;
        sizePane(paneCode, horizontal, share);
        sizePane(panePreview, horizontal, share);
    }

    /** Gives one pane an exact size along the split axis, and all of the other one. */
    private static void sizePane(@NonNull View pane, boolean horizontal, int share) {
        var params = (LinearLayout.LayoutParams) pane.getLayoutParams();
        if (params == null) return;
        int margins = horizontal
            ? params.leftMargin + params.rightMargin
            : params.topMargin + params.bottomMargin;
        int size = Math.max(0, share - margins);
        int width = horizontal ? size : LinearLayout.LayoutParams.MATCH_PARENT;
        int height = horizontal ? LinearLayout.LayoutParams.MATCH_PARENT : size;
        if (params.width == width && params.height == height && params.weight == 0f) return;
        params.width = width;
        params.height = height;
        params.weight = 0f;
        pane.setLayoutParams(params);
    }

    private void schedulePreview() {
        if (mode == Mode.CODE) return;
        mainHandler.removeCallbacks(renderPreview);
        mainHandler.postDelayed(renderPreview, PREVIEW_DELAY_MS);
    }

    private void renderPreview() {
        var markdown = text();
        tvPreviewEmpty.setVisibility(markdown.trim().isEmpty() ? VISIBLE : GONE);
        if (markdown.equals(rendered)) return;
        rendered = markdown;
        MarkdownRender.bind(preview, markdown);
    }

    @NonNull
    private Mode modeOf(int buttonId) {
        if (buttonId == R.id.btn_mode_code) return Mode.CODE;
        if (buttonId == R.id.btn_mode_preview) return Mode.PREVIEW;
        return Mode.SPLIT;
    }

    private int buttonOf(@NonNull Mode mode) {
        switch (mode) {
            case CODE:
                return R.id.btn_mode_code;
            case PREVIEW:
                return R.id.btn_mode_preview;
            default:
                return R.id.btn_mode_split;
        }
    }

    @NonNull
    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
