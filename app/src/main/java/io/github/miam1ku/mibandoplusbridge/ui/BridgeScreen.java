// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Shared Material 3 layout for every bridge screen.
 * Pages read top to bottom: status, next step, features, help, management, advanced.
 */
public final class BridgeScreen {
    /** Colour role of a chip, notice card, or status line. */
    public enum Tone { POSITIVE, ACTIVE, NEUTRAL, ATTENTION }

    /** Position of a setup step relative to the current one. */
    public enum StepState { DONE, CURRENT, LATER }

    public final LinearLayout body;
    private final AppCompatActivity activity;

    private BridgeScreen(AppCompatActivity activity, LinearLayout body) {
        this.activity = activity;
        this.body = body;
    }

    public static BridgeScreen attach(AppCompatActivity activity, String title, boolean back) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(activity, R.attr.colorSurface));
        MaterialToolbar toolbar = new MaterialToolbar(activity);
        toolbar.setTitle(title);
        toolbar.setTitleCentered(false);
        if (back) {
            toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
            toolbar.setNavigationIconTint(color(activity, R.attr.colorOnSurface));
            toolbar.setNavigationOnClickListener(view -> activity.finish());
        }
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int gutter = dp(activity, 16);
        body.setPadding(gutter, dp(activity, 4), gutter, dp(activity, 24));
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

    // ---- text ----

    public TextView heading(String value) {
        return heading(body, value);
    }

    /** Section label above a group of cards. */
    public TextView heading(LinearLayout parent, String value) {
        TextView text = label(parent, value, 14, true);
        text.setTextColor(color(activity, R.attr.colorPrimary));
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) text.getLayoutParams();
        params.topMargin = dp(activity, 12);
        params.leftMargin = dp(activity, 4);
        return text;
    }

    public TextView overline(LinearLayout parent, String value) {
        TextView text = label(parent, value, 12, true);
        text.setTextColor(color(activity, R.attr.colorOnSurfaceVariant));
        text.setLetterSpacing(0.04f);
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
        muted(text);
        return text;
    }

    public void muted(TextView text) {
        text.setTextColor(color(activity, R.attr.colorOnSurfaceVariant));
    }

    /** Colours a status line with the tone's foreground role. */
    public void setTone(TextView text, Tone tone) {
        text.setTextColor(foreground(tone));
    }

    // ---- containers ----

    public LinearLayout card() {
        return card(body);
    }

    public LinearLayout card(LinearLayout parent) {
        return cardWith(parent, color(activity, R.attr.colorSurfaceContainerLow),
                color(activity, R.attr.colorOutlineVariant));
    }

    /** Card with a small title. Returns the inner layout. */
    public LinearLayout section(String title) {
        LinearLayout inner = card();
        overline(inner, title);
        return inner;
    }

    /** Tinted card for something the user can act on now. */
    public LinearLayout noticeCard(Tone tone) {
        LinearLayout inner = cardWith(body, background(tone), background(tone));
        inner.setTag(tone);
        return inner;
    }

    /** Collapsible block. The returned layout starts hidden. */
    public LinearLayout expander(String title) {
        return expander(body, title, null);
    }

    public LinearLayout expander(LinearLayout parent, String title, Runnable onOpen) {
        MaterialButton toggle = new MaterialButton(activity, null, R.attr.borderlessButtonStyle);
        toggle.setAllCaps(false);
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setMinHeight(dp(activity, 48));
        toggle.setText("▸  " + title);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(View.GONE);
        toggle.setOnClickListener(view -> {
            boolean open = content.getVisibility() != View.VISIBLE;
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText((open ? "▾  " : "▸  ") + title);
            if (open && onOpen != null) onOpen.run();
        });
        parent.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(content, new LinearLayout.LayoutParams(-1, -2));
        return content;
    }

    // ---- small widgets ----

    /** Rounded status label. */
    public TextView chip(LinearLayout parent, String text, Tone tone) {
        TextView chip = new TextView(activity);
        chip.setTextSize(13);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int h = dp(activity, 12);
        int v = dp(activity, 4);
        chip.setPadding(h, v, h, v);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.bottomMargin = dp(activity, 12);
        parent.addView(chip, params);
        setChip(chip, text, tone);
        return chip;
    }

    public void setChip(TextView chip, String text, Tone tone) {
        chip.setText(text);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(activity, 999));
        shape.setColor(background(tone));
        chip.setBackground(shape);
        chip.setTextColor(onBackground(tone));
    }

    /** One line of a setup checklist: ✓ done, ● current, ○ later. */
    public TextView stepRow(LinearLayout parent, int index, String title, StepState state) {
        TextView row = label(parent, "", 15, false);
        ((LinearLayout.LayoutParams) row.getLayoutParams()).bottomMargin = dp(activity, 6);
        setStep(row, index, title, state);
        return row;
    }

    public void setStep(TextView row, int index, String title, StepState state) {
        String mark = switch (state) {
            case DONE -> "✓";
            case CURRENT -> "●";
            case LATER -> "○";
        };
        row.setText(mark + "  " + index + ". " + title);
        row.setTypeface(state == StepState.CURRENT
                ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        row.setTextColor(state == StepState.CURRENT ? color(activity, R.attr.colorPrimary)
                : color(activity, R.attr.colorOnSurfaceVariant));
    }

    /** Switch with a caption under it. Returns the switch. */
    public MaterialSwitch switchRow(LinearLayout parent, String title, String caption) {
        MaterialSwitch toggle = new MaterialSwitch(activity);
        toggle.setText(title);
        toggle.setTextSize(16);
        toggle.setTextColor(color(activity, R.attr.colorOnSurface));
        toggle.setMinHeight(dp(activity, 48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, 4);
        parent.addView(toggle, params);
        if (caption != null && !caption.isEmpty()) caption(parent, caption);
        return toggle;
    }

    // ---- buttons ----

    public MaterialButton filled(String title, Runnable action) {
        return filled(body, title, action);
    }

    public MaterialButton outlined(String title, Runnable action) {
        return outlined(body, title, action);
    }

    public MaterialButton textButton(String title, Runnable action) {
        return textButton(body, title, action);
    }

    public MaterialButton filled(LinearLayout parent, String title, Runnable action) {
        return material(parent, title, R.attr.materialButtonStyle, action);
    }

    public MaterialButton outlined(LinearLayout parent, String title, Runnable action) {
        return material(parent, title, R.attr.materialButtonOutlinedStyle, action);
    }

    public MaterialButton textButton(LinearLayout parent, String title, Runnable action) {
        return material(parent, title, R.attr.borderlessButtonStyle, action);
    }

    /** Outlined button in the error role for removal. */
    public MaterialButton dangerOutlined(LinearLayout parent, String title, Runnable action) {
        MaterialButton button = outlined(parent, title, action);
        int error = color(activity, R.attr.colorError);
        button.setTextColor(error);
        button.setStrokeColor(ColorStateList.valueOf(error));
        return button;
    }

    public MaterialButton navRow(String title, Runnable action) {
        return navRow(body, title, action);
    }

    /** Full-width list row with a trailing chevron. */
    public MaterialButton navRow(LinearLayout parent, String title, Runnable action) {
        MaterialButton button = new MaterialButton(activity, null, R.attr.borderlessButtonStyle);
        button.setText(title + "   ›");
        button.setAllCaps(false);
        button.setTextColor(color(activity, R.attr.colorOnSurface));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(activity, 52));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(activity, 12));
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
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

    private LinearLayout cardWith(LinearLayout parent, int fill, int stroke) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardElevation(0);
        card.setRadius(dp(activity, 20));
        card.setStrokeWidth(dp(activity, 1));
        card.setStrokeColor(stroke);
        card.setCardBackgroundColor(fill);
        LinearLayout inner = new LinearLayout(activity);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        inner.setPadding(pad, pad, pad, dp(activity, 8));
        card.addView(inner, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, 12);
        parent.addView(card, params);
        return inner;
    }

    private MaterialButton material(LinearLayout parent, String title, int styleAttr, Runnable action) {
        MaterialButton button = new MaterialButton(activity, null, styleAttr);
        button.setText(title);
        button.setAllCaps(false);
        button.setMinHeight(dp(activity, 48));
        button.setInsetTop(dp(activity, 4));
        button.setInsetBottom(dp(activity, 4));
        button.setCornerRadius(dp(activity, 24));
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
        text.setLineSpacing(0, heading ? 1.2f : 1.4f);
        text.setTextColor(color(activity, R.attr.colorOnSurface));
        if (heading) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, heading ? 8 : 10);
        parent.addView(text, params);
        return text;
    }

    private int background(Tone tone) {
        return switch (tone) {
            case POSITIVE -> color(activity, R.attr.colorPrimaryContainer);
            case ACTIVE -> color(activity, R.attr.colorTertiaryContainer);
            case NEUTRAL -> color(activity, R.attr.colorSurfaceVariant);
            case ATTENTION -> color(activity, R.attr.colorErrorContainer);
        };
    }

    private int onBackground(Tone tone) {
        return switch (tone) {
            case POSITIVE -> color(activity, R.attr.colorOnPrimaryContainer);
            case ACTIVE -> color(activity, R.attr.colorOnTertiaryContainer);
            case NEUTRAL -> color(activity, R.attr.colorOnSurfaceVariant);
            case ATTENTION -> color(activity, R.attr.colorOnErrorContainer);
        };
    }

    private int foreground(Tone tone) {
        return switch (tone) {
            case POSITIVE -> color(activity, R.attr.colorPrimary);
            case ACTIVE -> color(activity, R.attr.colorTertiary);
            case NEUTRAL -> color(activity, R.attr.colorOnSurfaceVariant);
            case ATTENTION -> color(activity, R.attr.colorError);
        };
    }

    private static int color(android.content.Context context, int attr) {
        return MaterialColors.getColor(context, attr, 0);
    }

    public static int dp(android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
