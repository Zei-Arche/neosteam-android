package org.neosteam.revival.gameplay;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.neosteam.revival.ota.ContentUpdater;

import java.util.Collections;
import java.util.concurrent.ExecutorService;

public final class GameplayScreen {
    private static final String[] SECTIONS = {"Combat", "NPCs & quests", "Inventory", "Chat", "Performance"};
    private static final int PERFORMANCE = 4;

    public static final class Session {
        private final InventoryState inventory = new InventoryState();
        private final CombatState combat = CombatState.practice();
        private final InteractionModel interactions = InteractionModel.practice(inventory::exchange);
        private final ChatSession chat = ChatSession.practice();
        private int section;
    }

    private final Activity activity;
    private final Session session;
    private final LinearLayout root;
    private final FrameLayout body;
    private final TextView status;
    private final Button[] tabs = new Button[SECTIONS.length];
    private final View[] pages = new View[SECTIONS.length];
    private final CombatPanel combat;
    private final InteractionPanel interactions;
    private final InventoryPanel inventory;
    private final ChatPanel chat;
    private final ExecutorService streams;
    private final ContentUpdater content;
    private PerformancePanel performance;
    private boolean foreground;
    private String route = "Network status unavailable";

    public GameplayScreen(Activity activity, Session session, ExecutorService streams,
                          ContentUpdater content, Runnable showContent) {
        this.activity = activity;
        this.session = session;
        this.streams = streams;
        this.content = content;
        root = GameplayViews.stack(activity);
        root.setBackgroundColor(GameplayViews.BACKGROUND);
        root.setFocusableInTouchMode(true);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            applyInsets(view, insets);
            return insets;
        });

        LinearLayout heading = GameplayViews.stack(activity);
        int padding = GameplayViews.dp(activity, 16);
        boolean landscape = activity.getResources().getConfiguration().orientation
            == Configuration.ORIENTATION_LANDSCAPE;
        int headerInset = GameplayViews.dp(activity, landscape ? 6 : 10);
        heading.setPadding(padding, headerInset, padding, headerInset);
        heading.addView(GameplayViews.text(activity, landscape ? "NeoSteam · Local practice" : "NeoSteam",
            landscape ? 17 : 24, GameplayViews.TEXT, true));
        if (!landscape) {
            heading.addView(GameplayViews.text(activity, "Practice · Local", 13, GameplayViews.ACCENT, true));
        }
        status = GameplayViews.text(activity, "Starting native core", 11, GameplayViews.MUTED, false);
        status.setMaxLines(landscape ? 1 : 2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.addView(GameplayViews.button(activity, "Content updates", false, ignored -> showContent.run()));
        LinearLayout.LayoutParams statusPosition = new LinearLayout.LayoutParams(0, -2, 1);
        statusPosition.setMarginStart(GameplayViews.dp(activity, 8));
        statusRow.addView(status, statusPosition);
        heading.addView(statusRow);
        root.addView(heading);

        combat = new CombatPanel(activity, session.combat, this::recordDefeat);
        interactions = new InteractionPanel(activity, session.interactions, session.inventory::summary);
        inventory = new InventoryPanel(activity, session.inventory);
        chat = new ChatPanel(activity, session.chat);
        pages[0] = scrollPage(combat.view());
        pages[1] = scrollPage(interactions.view());
        pages[2] = scrollPage(inventory.view());
        pages[3] = chat.view();

        body = new FrameLayout(activity);
        body.setPadding(padding, 0, padding, 0);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout navigation = new LinearLayout(activity);
        navigation.setBackgroundColor(GameplayViews.SURFACE);
        int gap = GameplayViews.dp(activity, 4);
        navigation.setPadding(gap, gap, gap, gap);
        for (int i = 0; i < tabs.length; i++) {
            int destination = i;
            String label = i == PERFORMANCE ? "Perf" : SECTIONS[i];
            Button button = GameplayViews.button(activity, label, i == session.section,
                ignored -> open(destination));
            if (i == PERFORMANCE) button.setContentDescription(SECTIONS[i]);
            tabs[i] = button;
            LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(0, -2, 1);
            position.setMargins(gap, 0, gap, 0);
            navigation.addView(button, position);
        }
        root.addView(navigation);
        open(session.section);
    }

    public View view() { return root; }

    public void setNativeStatus(String message) { status.setText(message); }

    public void setForeground(boolean active) {
        foreground = active;
        combat.setForeground(active && session.section == 0);
        if (performance != null) performance.setActive(active && session.section == PERFORMANCE);
    }

    public void trimResources() {
        if (performance != null) performance.trim();
    }

    public void setNetworkRoute(String message) {
        route = message;
        if (performance != null) performance.setRoute(message);
    }

    private ScrollView scrollPage(View content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, GameplayViews.dp(activity, 12));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private void open(int section) {
        if (body.getChildCount() > 0 && section == session.section) return;
        if (session.section == 3 && section != 3 && root.getWindowToken() != null) {
            InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
        if (performance != null && session.section == PERFORMANCE) performance.setActive(false);
        if (session.section == 0) combat.setForeground(false);
        session.section = section;
        switch (section) {
            case 0 -> combat.refresh();
            case 1 -> interactions.refresh();
            case 2 -> inventory.refresh();
            case 3 -> chat.refresh();
            case PERFORMANCE -> {
                if (performance == null) {
                    performance = new PerformancePanel(activity, streams, content);
                    performance.setRoute(route);
                    pages[PERFORMANCE] = performance.view();
                }
            }
            default -> throw new IllegalArgumentException("Unknown gameplay section");
        }
        for (int i = 0; i < tabs.length; i++) GameplayViews.styleButton(tabs[i], i == section);
        body.removeAllViews();
        body.addView(pages[section], new FrameLayout.LayoutParams(-1, -1));
        setForeground(foreground);
        root.requestFocus();
    }

    private void recordDefeat() {
        session.interactions.recordObjective("piyo", 1);
        boolean stored = session.inventory.exchange(Collections.singletonMap("gear", 1), 0);
        Toast.makeText(activity, stored ? "Copper Gear added to the bag" : "Bag full · Copper Gear left behind",
            Toast.LENGTH_SHORT).show();
    }

    @SuppressWarnings("deprecation")
    private static void applyInsets(View view, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
        } else {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        }
    }
}
