package cn.classfun.droidvm.ui.main.settings;

import static android.content.Intent.ACTION_VIEW;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static androidx.appcompat.R.attr.colorPrimary;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.data.License;

public final class SoftwareLicenseDialog {
    private final Context context;

    public SoftwareLicenseDialog(@NonNull Context context) {
        this.context = context;
    }

    public void show() {
        var items = License.load(context);
        if (items == null || items.isEmpty()) return;
        var adapter = new LicenseListAdapter(context, items);
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_license_title)
            .setAdapter(adapter, (dialog, which) -> showDetail(items.get(which)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showDetail(@NonNull License item) {
        var inflater = LayoutInflater.from(context);
        var view = inflater.inflate(R.layout.dialog_license_detail, null);
        bindSection(
            view.findViewById(R.id.label_description),
            view.findViewById(R.id.tv_description),
            item.getDescription()
        );
        bindSection(
            view.findViewById(R.id.label_license),
            view.findViewById(R.id.tv_license),
            item.getLicense()
        );
        var urls = item.getUrls();
        if (urls != null && !urls.isEmpty()) {
            view.findViewById(R.id.label_urls).setVisibility(VISIBLE);
            LinearLayout llUrls = view.findViewById(R.id.ll_urls);
            llUrls.setVisibility(VISIBLE);
            for (var url : urls) {
                var tvUrl = new TextView(context);
                var lp = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
                lp.topMargin = (int) (4 * context.getResources().getDisplayMetrics().density);
                tvUrl.setLayoutParams(lp);
                tvUrl.setTextSize(14);
                tvUrl.setLinkTextColor(getColorAttr(context, colorPrimary));
                SpannableString spannable = new SpannableString(url);
                spannable.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        context.startActivity(new Intent(ACTION_VIEW, Uri.parse(url)));
                    }
                }, 0, url.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tvUrl.setText(spannable);
                tvUrl.setMovementMethod(LinkMovementMethod.getInstance());
                llUrls.addView(tvUrl);
            }
        }
        new MaterialAlertDialogBuilder(context)
            .setTitle(item.getName())
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private static void bindSection(
        @NonNull TextView label, @NonNull TextView value, @Nullable String text
    ) {
        if (text == null || text.isEmpty()) return;
        label.setVisibility(VISIBLE);
        value.setVisibility(VISIBLE);
        value.setText(text);
    }

    private static int getColorAttr(@NonNull Context context, int attr) {
        var tv = new TypedValue();
        context.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }
}
