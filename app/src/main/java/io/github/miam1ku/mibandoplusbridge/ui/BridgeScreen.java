// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

/** Shared Material layout for ordinary user screens. */
public final class BridgeScreen {
    public final LinearLayout body;
    private final AppCompatActivity activity;

    private BridgeScreen(AppCompatActivity activity, LinearLayout body) {
        this.activity = activity;
        this.body = body;
    }

    public static BridgeScreen attach(AppCompatActivity activity, String title, boolean back) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurface, 0));
        MaterialToolbar toolbar = new MaterialToolbar(activity);
        toolbar.setTitle(title);
        toolbar.setTitleCentered(false);
        if (back) {
            toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
            toolbar.setNavigationOnClickListener(view -> activity.finish());
        }
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int gutter = dp(activity, 20);
        body.setPadding(gutter, dp(activity, 8), gutter, gutter);
        scroll.addView(body, new LinearLayout.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        activity.setContentView(root);
        return new BridgeScreen(activity, body);
    }

    public TextView heading(String value) {
        return heading(body, value);
    }

    public TextView heading(LinearLayout parent, String value) {
        TextView text = label(parent, value, 20, true);
        ((LinearLayout.LayoutParams) text.getLayoutParams()).topMargin = dp(activity, 8);
        return text;
    }

    public TextView overline(LinearLayout parent, String value) {
        TextView text = label(parent, value, 12, true);
        text.setTextColor(MaterialColors.getColor(activity,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0));
        return text;
    }

    public TextView title(LinearLayout parent, String value) {
        return label(parent, value, 22, true);
    }

    public TextView bodyText(String value) {
        return bodyText(body, value);
    }

    public TextView bodyText(LinearLayout parent, String value) {
        return label(parent, value, 16, false);
    }

    public TextView caption(String value) {
        return caption(body, value);
    }

    public TextView caption(LinearLayout parent, String value) {
        TextView text = label(parent, value, 14, false);
        text.setTextColor(MaterialColors.getColor(activity,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0));
        return text;
    }

    public LinearLayout card() {
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardElevation(0);
        card.setRadius(dp(activity, 16));
        card.setStrokeWidth(dp(activity, 1));
        card.setStrokeColor(MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOutline, 0));
        LinearLayout inner = new LinearLayout(activity);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        inner.setPadding(pad, pad, pad, pad);
        card.addView(inner, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, 16);
        body.addView(card, params);
        return inner;
    }
    public MaterialButton filled(String title, Runnable action) {
        return material(body, title, com.google.android.material.R.attr.materialButtonStyle, action);
    }

    public MaterialButton outlined(String title, Runnable action) {
        return material(body, title, com.google.android.material.R.attr.materialButtonOutlinedStyle, action);
    }

    public MaterialButton textButton(String title, Runnable action) {
        return material(body, title, com.google.android.material.R.attr.borderlessButtonStyle, action);
    }

    public MaterialButton navRow(String title, Runnable action) {
        return navRow(body, title, action);
    }

    public MaterialButton navRow(LinearLayout parent, String title, Runnable action) {
        MaterialButton button = new MaterialButton(activity, null,
                com.google.android.material.R.attr.borderlessButtonStyle);
        button.setText(title);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(activity, 48));
        button.setInsetTop(dp(activity, 4));
        button.setInsetBottom(dp(activity, 4));
        button.setCornerRadius(dp(activity, 12));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = 0;
        parent.addView(button, params);
        return button;
    }

    public void setLastChildMargin(LinearLayout parent, int space) {
        int count = parent.getChildCount();
        if (count == 0) return;
        View last = parent.getChildAt(count - 1);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) last.getLayoutParams();
        params.bottomMargin = dp(activity, space);
        last.setLayoutParams(params);
    }

    public MaterialButton filled(LinearLayout parent, String title, Runnable action) {
        return material(parent, title, com.google.android.material.R.attr.materialButtonStyle, action);
    }

    public MaterialButton outlined(LinearLayout parent, String title, Runnable action) {
        return material(parent, title, com.google.android.material.R.attr.materialButtonOutlinedStyle, action);
    }

    private MaterialButton material(LinearLayout parent, String title, int styleAttr, Runnable action) {
        MaterialButton button = new MaterialButton(activity, null, styleAttr);
        button.setText(title);
        button.setAllCaps(false);
        button.setMinHeight(dp(activity, 48));
        button.setInsetTop(dp(activity, 4));
        button.setInsetBottom(dp(activity, 4));
        button.setCornerRadius(dp(activity, 12));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, 8);
        parent.addView(button, params);
        return button;
    }

    private TextView label(LinearLayout parent, String value, int sp, boolean heading) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(sp);
        text.setLineSpacing(0, heading ? 1.25f : 1.45f);
        text.setTextColor(MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, 0));
        if (heading) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, heading ? 8 : 12);
        parent.addView(text, params);
        return text;
    }

    public static int dp(android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
