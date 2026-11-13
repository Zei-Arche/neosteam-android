package org.neosteam.revival.gameplay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class GameplayViews {
    static final int BACKGROUND = 0xff0b1521;
    static final int SURFACE = 0xff101b29;
    static final int PANEL = 0xff182638;
    static final int BORDER = 0xff34465a;
    static final int TEXT = 0xffeff5fa;
    static final int MUTED = 0xffb6c6d6;
    static final int ACCENT = 0xfff4b66a;
    static final int INK = 0xff19202a;

    private GameplayViews() {}

    static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    static LinearLayout stack(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static TextView text(Context context, String value, int size, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    static Button button(Context context, String label, boolean selected, View.OnClickListener action) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(13);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(context, 48));
        button.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4));
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(action);
        styleButton(button, selected);
        return button;
    }

    static void styleButton(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? INK : TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(selected ? ACCENT : PANEL));
    }

    static void card(View view) {
        Context context = view.getContext();
        GradientDrawable background = new GradientDrawable();
        background.setColor(SURFACE);
        background.setCornerRadius(dp(context, 10));
        background.setStroke(dp(context, 1), BORDER);
        view.setBackground(background);
        int inset = dp(context, 12);
        view.setPadding(inset, inset, inset, inset);
    }
}
