package org.neosteam.revival.gameplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Local practice interactions and inventory transactions. */
public final class InteractionModel {
    /** Validate and apply all item and gold changes together, or reject all changes. */
    public interface Economy {
        boolean exchange(Map<String, Integer> itemChanges, int coinChange);
    }

    public record Npc(String id, String name, String role) {}
    public record Option(String id, String label) {}
    public record Dialogue(Npc npc, String text, List<Option> options) {}
    public record Result(boolean success, String message) {}
    public enum QuestState { AVAILABLE, ACTIVE, READY, COMPLETE }

    public record QuestProgress(
        String title, String description, String objective, int progress, int required,
        String reward, QuestState state, boolean tracked) {}

    public record VendorOffer(String itemId, String itemName, int buyPrice, int sellPrice) {}
    public record Trade(String partner, String offeredItem, String requestedItem, int quantity) {}

    private static final Npc FOREMAN = new Npc("foreman", "Foreman Mina", "Quest giver");
    private static final Npc MERCHANT = new Npc("merchant", "Quartermaster Jun", "Vendor");
    private static final List<Npc> NPCS = Collections.unmodifiableList(Arrays.asList(FOREMAN, MERCHANT));
    private static final List<VendorOffer> OFFERS = Collections.unmodifiableList(Arrays.asList(
        new VendorOffer("tonic", "Recovery Tonic", 10, 4),
        new VendorOffer("gear", "Copper Gear", 12, 5),
        new VendorOffer("wrench", "Rivet Wrench", 30, 12),
        new VendorOffer("jacket", "Workshop Jacket", 24, 9)));
    private static final int PIYO_REQUIRED = 2;
    private static final int QUEST_GOLD = 15;
    private static final int MAX_TRADE_QUANTITY = 10;

    private final Economy economy;
    private Npc speaker;
    private boolean followUp;
    private QuestState questState = QuestState.AVAILABLE;
    private int defeats;
    private boolean tracked;
    private Trade trade;

    private InteractionModel(Economy economy) {
        this.economy = Objects.requireNonNull(economy);
    }

    public static InteractionModel practice(Economy economy) {
        return new InteractionModel(economy);
    }

    public List<Npc> npcs() {
        return NPCS;
    }

    public Dialogue openDialogue(String npcId) {
        for (Npc npc : NPCS) {
            if (npc.id().equals(npcId)) {
                speaker = npc;
                followUp = false;
                return currentDialogue().get();
            }
        }
        throw new IllegalArgumentException("Unknown practice NPC");
    }

    public Optional<Dialogue> currentDialogue() {
        if (speaker == null) {
            return Optional.empty();
        }
        List<Option> options = new ArrayList<>();
        String text;
        if (speaker.equals(FOREMAN)) {
            text = switch (questState) {
                case AVAILABLE -> followUp
                    ? "Piyo have been wandering into the yard. Talk to me after you clear two."
                    : "I need someone to clear Piyo from the workshop yard.";
                case ACTIVE -> "You have cleared " + defeats + " of " + PIYO_REQUIRED + " Piyo.";
                case READY -> "The yard is clear. Your reward is ready.";
                case COMPLETE -> "The crew can work in the yard again. Thank you.";
            };
            if (questState == QuestState.AVAILABLE) {
                options.add(new Option("accept", "Accept quest"));
                if (!followUp) {
                    options.add(new Option("details", "Ask about the Piyo"));
                }
            } else if (questState == QuestState.READY) {
                options.add(new Option("turn-in", "Collect reward"));
            }
        } else {
            text = followUp ? "I buy and sell workshop supplies. Review the price before confirming."
                : "Workshop supplies are available at the quartermaster's counter.";
            options.add(new Option("shop", "Browse vendor"));
            if (!followUp) {
                options.add(new Option("details", "Ask about supplies"));
            }
        }
        options.add(new Option("leave", "End conversation"));
        return Optional.of(new Dialogue(speaker, text, Collections.unmodifiableList(options)));
    }

    public Result choose(String optionId) {
        Optional<Dialogue> current = currentDialogue();
        if (!current.isPresent()) {
            return fail("Open a conversation first.");
        }
        boolean found = false;
        for (Option option : current.get().options()) {
            if (option.id().equals(optionId)) {
                found = true;
                break;
            }
        }
        if (!found) {
            return fail("That dialogue option is unavailable.");
        }
        return switch (optionId) {
            case "accept" -> {
                questState = QuestState.ACTIVE;
                tracked = true;
                yield ok("Quest accepted.");
            }
            case "details" -> {
                followUp = true;
                yield ok("Dialogue updated.");
            }
            case "turn-in" -> {
                if (!economy.exchange(Collections.singletonMap("tonic", 1), QUEST_GOLD)) {
                    yield fail("There is no room for the reward. Free a slot and try again.");
                }
                questState = QuestState.COMPLETE;
                tracked = false;
                yield ok("Quest complete. Received 15 gold and 1 Recovery Tonic.");
            }
            case "shop" -> ok("Vendor opened.");
            case "leave" -> {
                speaker = null;
                followUp = false;
                yield ok("Conversation ended.");
            }
            default -> fail("That dialogue option is unavailable.");
        };
    }

    public List<QuestProgress> quests() {
        return Collections.singletonList(new QuestProgress(
            "Clear the workshop yard", "Help Foreman Mina clear the yard. Return to Mina for the reward.",
            "Defeat Piyo", defeats, PIYO_REQUIRED, "15 gold and 1 Recovery Tonic", questState, tracked));
    }

    public Result trackQuest(boolean enable) {
        if (questState != QuestState.ACTIVE && questState != QuestState.READY) {
            return fail("Accept the quest before tracking it.");
        }
        tracked = enable;
        return ok(enable ? "Quest is tracked." : "Quest tracking cleared.");
    }

    public void recordObjective(String key, int quantity) {
        if (questState != QuestState.ACTIVE || !"piyo".equals(key) || quantity <= 0) {
            return;
        }
        defeats += Math.min(quantity, PIYO_REQUIRED - defeats);
        if (defeats == PIYO_REQUIRED) {
            questState = QuestState.READY;
        }
    }

    public List<VendorOffer> vendorOffers() {
        return OFFERS;
    }

    public Result buy(String itemId, int quantity) {
        return vendorExchange(itemId, quantity, true);
    }

    public Result sell(String itemId, int quantity) {
        return vendorExchange(itemId, quantity, false);
    }

    private Result vendorExchange(String itemId, int quantity, boolean buying) {
        if (quantity < 1 || quantity > 99) {
            return fail("Choose a quantity from 1 to 99.");
        }
        for (VendorOffer offer : OFFERS) {
            if (offer.itemId().equals(itemId)) {
                int price = quantity * (buying ? offer.buyPrice() : offer.sellPrice());
                if (!economy.exchange(Collections.singletonMap(itemId, buying ? quantity : -quantity),
                        buying ? -price : price)) {
                    return fail(buying ? "You need enough gold and space to buy this."
                        : "You do not have that quantity available to sell.");
                }
                return ok((buying ? "Bought " : "Sold ") + quantity + " × " + offer.itemName() + ".");
            }
        }
        return fail("The vendor does not trade that item.");
    }

    public Result openTrade() {
        if (trade == null) {
            trade = new Trade("Practice partner Jae", "Copper Gear", "Recovery Tonic", 0);
        }
        return ok("Review the exchange before confirming.");
    }

    public Optional<Trade> trade() {
        return Optional.ofNullable(trade);
    }

    public Result setOffer(String itemId, int quantity) {
        if (trade == null) {
            return fail("Open a trade first.");
        }
        if (!"gear".equals(itemId) || quantity < 1 || quantity > MAX_TRADE_QUANTITY) {
            return fail("Offer 1 to 10 Copper Gears.");
        }
        trade = new Trade(trade.partner(), trade.offeredItem(), trade.requestedItem(), quantity);
        return ok("Offer ready to review.");
    }

    public Result confirmTrade() {
        if (trade == null || trade.quantity() == 0) {
            return fail("Set an offer before confirming.");
        }
        Map<String, Integer> changes = new LinkedHashMap<>();
        changes.put("gear", -trade.quantity());
        changes.put("tonic", trade.quantity());
        if (!economy.exchange(Collections.unmodifiableMap(changes), 0)) {
            return fail("The required gear or inventory space is unavailable.");
        }
        int quantity = trade.quantity();
        trade = null;
        return ok("Traded " + quantity + " Copper Gear for " + quantity + " Recovery Tonic.");
    }

    public void cancelTrade() {
        trade = null;
    }

    private static Result ok(String message) {
        return new Result(true, message);
    }

    private static Result fail(String message) {
        return new Result(false, message);
    }
}
