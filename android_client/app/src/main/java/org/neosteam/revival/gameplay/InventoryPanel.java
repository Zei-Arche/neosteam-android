package org.neosteam.revival.gameplay;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;

final class InventoryPanel {
    private enum Page { BAG, EQUIPMENT, STATS }

    private final Context context;
    private final InventoryState state;
    private final LinearLayout root;
    private final LinearLayout content;
    private final TextView balance;
    private final TextView feedback;
    private final Button[] tabs = new Button[Page.values().length];
    private Page page = Page.BAG;

    InventoryPanel(Context context, InventoryState state) {
        this.context = context;
        this.state = state;
        root = GameplayViews.stack(context);
        root.addView(GameplayViews.text(context, "Inventory", 24, GameplayViews.TEXT, true));
        balance = GameplayViews.text(context, "", 14, GameplayViews.ACCENT, true);
        root.addView(balance);
        LinearLayout navigation = new LinearLayout(context);
        addTab(navigation, Page.BAG, "Bag");
        addTab(navigation, Page.EQUIPMENT, "Equipment");
        addTab(navigation, Page.STATS, "Stats");
        root.addView(navigation);
        feedback = GameplayViews.text(context, "", 14, GameplayViews.MUTED, false);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        root.addView(feedback);
        content = GameplayViews.stack(context);
        root.addView(content);
        refresh();
    }

    View view() { return root; }

    void refresh() {
        InventoryState.Snapshot snapshot = state.snapshot();
        balance.setText(snapshot.gold() + " gold · " + snapshot.usedSlots() + "/" + snapshot.capacity() + " bag slots");
        for (Page candidate : Page.values()) {
            GameplayViews.styleButton(tabs[candidate.ordinal()], candidate == page);
        }
        content.removeAllViews();
        switch (page) {
            case BAG -> showBag(snapshot);
            case EQUIPMENT -> showEquipment(snapshot);
            case STATS -> showStats(snapshot);
        }
    }

    private void addTab(LinearLayout navigation, Page candidate, String label) {
        Button button = GameplayViews.button(context, label, candidate == page, ignored -> {
            page = candidate;
            feedback.setVisibility(View.GONE);
            refresh();
        });
        tabs[candidate.ordinal()] = button;
        navigation.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void showBag(InventoryState.Snapshot snapshot) {
        if (snapshot.bag().isEmpty()) {
            addRow("Your bag is empty", null);
            return;
        }
        for (Map.Entry<InventoryState.Item, Integer> entry : snapshot.bag().entrySet()) {
            InventoryState.Item item = entry.getKey();
            Button equip = item.slot == null ? null : GameplayViews.button(context, "Equip", false,
                ignored -> report(state.equip(item) ? "Equipped " + item.label : "Unable to equip " + item.label));
            if (equip != null) equip.setContentDescription("Equip " + item.label);
            addRow(item.label + " ×" + entry.getValue(), equip);
        }
    }

    private void showEquipment(InventoryState.Snapshot snapshot) {
        for (InventoryState.Slot slot : InventoryState.Slot.values()) {
            InventoryState.Item item = snapshot.equipment().get(slot);
            Button remove = item == null ? null : GameplayViews.button(context, "Unequip", false,
                ignored -> report(state.unequip(slot) ? "Returned " + item.label + " to the bag" : "Bag has no room"));
            if (remove != null) remove.setContentDescription("Unequip " + item.label);
            addRow(slot.label + "  ·  " + (item == null ? "Empty" : item.label), remove);
        }
    }

    private void showStats(InventoryState.Snapshot snapshot) {
        addRow(snapshot.pointsAvailable() + " unassigned stat points", null);
        boolean hasDraft = false;
        for (InventoryState.StatLine line : snapshot.stats()) {
            InventoryState.Stat stat = line.stat();
            hasDraft |= line.draft() != 0;
            LinearLayout actions = new LinearLayout(context);
            Button minus = GameplayViews.button(context, "−", false, ignored -> {
                state.removeDraftPoint(stat);
                refresh();
            });
            minus.setContentDescription("Remove pending " + stat.label + " point");
            minus.setEnabled(line.draft() > 0);
            Button plus = GameplayViews.button(context, "+", false, ignored -> {
                state.stagePoint(stat);
                refresh();
            });
            plus.setContentDescription("Add pending " + stat.label + " point");
            plus.setEnabled(snapshot.pointsAvailable() > 0);
            int target = GameplayViews.dp(context, 48);
            actions.addView(minus, new LinearLayout.LayoutParams(target, -2));
            actions.addView(plus, new LinearLayout.LayoutParams(target, -2));
            addRow(stat.label + "  " + line.current() + (line.draft() == 0 ? "" : " +" + line.draft()), actions);
        }
        LinearLayout actions = new LinearLayout(context);
        Button discard = GameplayViews.button(context, "Discard", false, ignored -> {
            state.discardPoints();
            report("Draft discarded");
        });
        Button apply = GameplayViews.button(context, "Apply points", true,
            ignored -> report(state.commitPoints() ? "Stat points applied" : "Stage points before applying"));
        discard.setEnabled(hasDraft);
        apply.setEnabled(hasDraft);
        actions.addView(discard, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(apply, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(actions);
    }

    private void addRow(String label, View trailing) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GameplayViews.card(row);
        row.addView(GameplayViews.text(context, label, 15, GameplayViews.TEXT, true),
            new LinearLayout.LayoutParams(0, -2, 1));
        if (trailing != null) row.addView(trailing, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, -2);
        position.topMargin = GameplayViews.dp(context, 8);
        content.addView(row, position);
    }

    private void report(String message) {
        feedback.setText(message);
        feedback.setVisibility(View.VISIBLE);
        refresh();
    }
}
