package org.neosteam.revival.gameplay;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Native Android windows for local practice interactions. */
public final class InteractionPanel {
    private enum Tab { TALK, QUESTS, VENDOR, TRADE }

    private static final int ERROR = 0xffffa59e;

    private final Context context;
    private final InteractionModel model;
    private final Supplier<String> inventorySummary;
    private final LinearLayout root;
    private Tab tab = Tab.TALK;
    private InteractionModel.Result lastResult;

    public InteractionPanel(Context context, InteractionModel model, Supplier<String> inventorySummary) {
        this.context = context;
        this.model = model;
        this.inventorySummary = inventorySummary;
        root = GameplayViews.stack(context);
        refresh();
    }

    public View view() {
        return root;
    }

    public void refresh() {
        root.removeAllViews();
        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        addTab(tabs, Tab.TALK, "Talk");
        addTab(tabs, Tab.QUESTS, "Quests");
        addTab(tabs, Tab.VENDOR, "Vendor");
        addTab(tabs, Tab.TRADE, "Trade");
        root.addView(tabs);
        if (lastResult != null) {
            TextView feedback = text(root, lastResult.message(), 14,
                lastResult.success() ? GameplayViews.ACCENT : ERROR, false);
            feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
        switch (tab) {
            case TALK -> showDialogue();
            case QUESTS -> showQuests();
            case VENDOR -> showVendor();
            case TRADE -> showTrade();
        }
    }

    private void addTab(LinearLayout tabs, Tab target, String label) {
        Button button = GameplayViews.button(context, label, target == tab, ignored -> {
            tab = target;
            lastResult = null;
            refresh();
        });
        button.setEnabled(target != tab);
        button.setPadding(0, 0, 0, 0);
        tabs.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1f));
    }

    private void showDialogue() {
        for (InteractionModel.Npc npc : model.npcs()) {
            root.addView(button("Talk to " + npc.name(), () -> {
                model.openDialogue(npc.id());
                lastResult = null;
                refresh();
            }));
        }
        Optional<InteractionModel.Dialogue> dialogue = model.currentDialogue();
        if (!dialogue.isPresent()) {
            return;
        }
        InteractionModel.Dialogue page = dialogue.get();
        text(root, page.npc().name(), 20, GameplayViews.TEXT, true);
        text(root, page.text(), 16, GameplayViews.TEXT, false);
        for (InteractionModel.Option option : page.options()) {
            root.addView(button(option.label(), () -> {
                lastResult = model.choose(option.id());
                if (lastResult.success() && option.id().equals("shop")) {
                    tab = Tab.VENDOR;
                }
                refresh();
            }));
        }
    }

    private void showQuests() {
        for (InteractionModel.QuestProgress quest : model.quests()) {
            text(root, quest.title(), 20, GameplayViews.TEXT, true);
            String status = switch (quest.state()) {
                case AVAILABLE -> "Available";
                case ACTIVE -> "In progress";
                case READY -> "Ready to turn in";
                case COMPLETE -> "Complete";
            };
            text(root, status, 14, GameplayViews.ACCENT, true);
            text(root, quest.description(), 16, GameplayViews.TEXT, false);
            text(root, quest.objective() + "  " + quest.progress() + " / " + quest.required(),
                16, GameplayViews.TEXT, false);
            text(root, "Reward  " + quest.reward(), 14, GameplayViews.MUTED, false);
            if (quest.state() == InteractionModel.QuestState.ACTIVE ||
                    quest.state() == InteractionModel.QuestState.READY) {
                root.addView(button(quest.tracked() ? "Stop tracking" : "Track quest", () ->
                    showResult(model.trackQuest(!quest.tracked()))));
            }
        }
    }

    private void showVendor() {
        text(root, "Quartermaster Jun", 20, GameplayViews.TEXT, true);
        text(root, inventorySummary.get(), 14, GameplayViews.MUTED, false);
        for (InteractionModel.VendorOffer offer : model.vendorOffers()) {
            text(root, offer.itemName(), 16, GameplayViews.TEXT, true);
            LinearLayout row = new LinearLayout(context);
            addAction(row, "Buy · " + offer.buyPrice() + " gold", () -> vendorOrder(offer, true));
            addAction(row, "Sell · " + offer.sellPrice() + " gold", () -> vendorOrder(offer, false));
            root.addView(row);
        }
    }

    private void vendorOrder(InteractionModel.VendorOffer offer, boolean buying) {
        String action = buying ? "Buy" : "Sell";
        int price = buying ? offer.buyPrice() : offer.sellPrice();
        String unitPrice = (buying ? "Pay " : "Receive ") + price + " gold per item.";
        chooseQuantity(action + " " + offer.itemName(), unitPrice,
            99, quantity -> {
                int total = quantity * price;
                new AlertDialog.Builder(context)
                    .setTitle(action + " " + quantity + " × " + offer.itemName())
                    .setMessage((buying ? "Pay " : "Receive ") + total + " gold.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(action, (dialog, which) -> showResult(buying
                        ? model.buy(offer.itemId(), quantity) : model.sell(offer.itemId(), quantity)))
                    .show();
            });
    }

    private void showTrade() {
        text(root, "Peer trade", 20, GameplayViews.TEXT, true);
        text(root, inventorySummary.get(), 14, GameplayViews.MUTED, false);
        Optional<InteractionModel.Trade> trade = model.trade();
        if (!trade.isPresent()) {
            root.addView(button("Trade with practice partner", () -> showResult(model.openTrade())));
            return;
        }
        InteractionModel.Trade offer = trade.get();
        text(root, offer.partner(), 16, GameplayViews.TEXT, true);
        String details = offer.quantity() > 0
            ? "Give " + offer.quantity() + " × " + offer.offeredItem() + "\nReceive "
                + offer.quantity() + " × " + offer.requestedItem()
            : "Offer Copper Gear in exchange for Recovery Tonic.";
        text(root, details, 16, GameplayViews.TEXT, false);
        root.addView(button(offer.quantity() == 0 ? "Set offer" : "Change offer", () ->
            chooseQuantity("Offer Copper Gear", "One Recovery Tonic per Copper Gear.", 10,
                quantity -> showResult(model.setOffer("gear", quantity)))));
        Button confirm = button("Confirm trade", () -> new AlertDialog.Builder(context)
            .setTitle("Confirm trade")
            .setMessage(details)
            .setNegativeButton("Go back", null)
            .setPositiveButton("Trade", (dialog, which) -> showResult(model.confirmTrade()))
            .show());
        confirm.setEnabled(offer.quantity() > 0);
        root.addView(confirm);
        root.addView(button("Cancel trade", () -> {
            model.cancelTrade();
            lastResult = new InteractionModel.Result(true, "Trade canceled.");
            refresh();
        }));
    }

    private void chooseQuantity(String title, String message, int max, IntConsumer onConfirm) {
        LinearLayout content = GameplayViews.stack(context);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView detail = new TextView(context);
        detail.setText(message);
        content.addView(detail);
        EditText quantity = new EditText(context);
        quantity.setInputType(InputType.TYPE_CLASS_NUMBER);
        quantity.setSingleLine(true);
        quantity.setHint("Quantity");
        quantity.setText("1");
        quantity.selectAll();
        content.addView(quantity);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(title).setView(content)
            .setNegativeButton("Cancel", null).setPositiveButton("Review", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(button -> {
                int value;
                try {
                    value = Integer.parseInt(quantity.getText().toString());
                } catch (NumberFormatException invalid) {
                    value = 0;
                }
                if (value < 1 || value > max) {
                    quantity.setError("Enter a quantity from 1 to " + max + ".");
                    return;
                }
                dialog.dismiss();
                onConfirm.accept(value);
            }));
        dialog.show();
    }

    private void showResult(InteractionModel.Result result) {
        lastResult = result;
        refresh();
    }

    private void addAction(LinearLayout row, String label, Runnable action) {
        row.addView(button(label, action),
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    private Button button(String label, Runnable action) {
        return GameplayViews.button(context, label, false, view -> action.run());
    }

    private TextView text(LinearLayout layout, String value, int size, int color, boolean bold) {
        TextView label = GameplayViews.text(context, value, size, color, bold);
        label.setPadding(0, dp(6), 0, dp(6));
        layout.addView(label);
        return label;
    }

    private int dp(int value) {
        return GameplayViews.dp(context, value);
    }
}
