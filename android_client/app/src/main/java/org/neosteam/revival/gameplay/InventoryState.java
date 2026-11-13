package org.neosteam.revival.gameplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local practice inventory and character points. The game server owns live changes. */
public final class InventoryState {
    public enum Slot {
        WEAPON("Weapon"), HEAD("Head"), BODY("Body"), HANDS("Hands"),
        LEGS("Legs"), FEET("Feet"), ACCESSORY("Accessory");

        public final String label;
        Slot(String label) { this.label = label; }
    }

    public enum Item {
        TONIC("tonic", "Recovery Tonic", 20, null),
        GEAR("gear", "Copper Gear", 20, null),
        WRENCH("wrench", "Rivet Wrench", 1, Slot.WEAPON),
        JACKET("jacket", "Workshop Jacket", 1, Slot.BODY);

        public final String id;
        public final String label;
        public final int stackLimit;
        public final Slot slot;

        Item(String id, String label, int stackLimit, Slot slot) {
            this.id = id;
            this.label = label;
            this.stackLimit = stackLimit;
            this.slot = slot;
        }

        private static Item fromId(String id) {
            for (Item item : values()) {
                if (item.id.equals(id)) return item;
            }
            return null;
        }
    }

    public enum Stat {
        STRENGTH("Strength"), CONSTITUTION("Constitution"),
        DEXTERITY("Dexterity"), INTELLIGENCE("Intelligence");

        public final String label;
        Stat(String label) { this.label = label; }
    }

    public record StatLine(Stat stat, int current, int draft) {}
    public record Snapshot(Map<Item, Integer> bag, Map<Slot, Item> equipment,
                           int gold, int usedSlots, int capacity, int pointsAvailable,
                           List<StatLine> stats) {}

    private static final int CAPACITY = 20;
    private final Map<Item, Integer> bag = new LinkedHashMap<>();
    private final EnumMap<Slot, Item> equipment = new EnumMap<>(Slot.class);
    private final int[] stats = {8, 8, 7, 6};
    private final int[] draft = new int[Stat.values().length];
    private int gold = 100;
    private int points = 5;

    public InventoryState() {
        bag.put(Item.TONIC, 3);
        bag.put(Item.GEAR, 1);
        bag.put(Item.WRENCH, 1);
        bag.put(Item.JACKET, 1);
    }

    /** The dialogue, vendor and trade model uses this single atomic economy operation. */
    public synchronized boolean exchange(Map<String, Integer> changes, int goldChange) {
        long nextGold = (long) gold + goldChange;
        if (changes == null || nextGold < 0 || nextGold > Integer.MAX_VALUE) return false;
        Map<Item, Integer> next = new LinkedHashMap<>(bag);
        for (Map.Entry<String, Integer> change : changes.entrySet()) {
            Item item = Item.fromId(change.getKey());
            Integer quantity = change.getValue();
            if (item == null || quantity == null || !adjust(next, item, quantity)) return false;
        }
        if (usedSlots(next) > CAPACITY) return false;
        replaceBag(next);
        gold = (int) nextGold;
        return true;
    }

    public synchronized boolean equip(Item item) {
        if (item == null || item.slot == null || equipment.get(item.slot) == item) return false;
        Map<Item, Integer> next = new LinkedHashMap<>(bag);
        if (!adjust(next, item, -1)) return false;
        Item previous = equipment.get(item.slot);
        if (previous != null && !adjust(next, previous, 1)) return false;
        if (usedSlots(next) > CAPACITY) return false;
        replaceBag(next);
        equipment.put(item.slot, item);
        return true;
    }

    public synchronized boolean unequip(Slot slot) {
        Item item = equipment.get(slot);
        if (item == null) return false;
        Map<Item, Integer> next = new LinkedHashMap<>(bag);
        if (!adjust(next, item, 1) || usedSlots(next) > CAPACITY) return false;
        replaceBag(next);
        equipment.remove(slot);
        return true;
    }

    public synchronized boolean stagePoint(Stat stat) {
        if (points == draftedPoints()) return false;
        draft[stat.ordinal()]++;
        return true;
    }

    public synchronized boolean removeDraftPoint(Stat stat) {
        if (draft[stat.ordinal()] == 0) return false;
        draft[stat.ordinal()]--;
        return true;
    }

    public synchronized boolean commitPoints() {
        int total = draftedPoints();
        if (total == 0) return false;
        points -= total;
        for (int i = 0; i < draft.length; i++) {
            stats[i] += draft[i];
            draft[i] = 0;
        }
        return true;
    }

    public synchronized void discardPoints() {
        for (int i = 0; i < draft.length; i++) draft[i] = 0;
    }

    public synchronized Snapshot snapshot() {
        List<StatLine> lines = new ArrayList<>(stats.length);
        for (Stat stat : Stat.values()) {
            int index = stat.ordinal();
            lines.add(new StatLine(stat, stats[index], draft[index]));
        }
        return new Snapshot(Collections.unmodifiableMap(new LinkedHashMap<>(bag)),
            Collections.unmodifiableMap(new EnumMap<>(equipment)), gold,
            (int) usedSlots(bag), CAPACITY, points - draftedPoints(),
            Collections.unmodifiableList(lines));
    }

    public synchronized String summary() {
        return gold + " gold · " + usedSlots(bag) + "/" + CAPACITY + " bag slots";
    }

    private int draftedPoints() {
        int total = 0;
        for (int amount : draft) total += amount;
        return total;
    }

    private static boolean adjust(Map<Item, Integer> items, Item item, int change) {
        long next = (long) items.getOrDefault(item, 0) + change;
        if (next < 0 || next > Integer.MAX_VALUE) return false;
        if (next == 0) items.remove(item);
        else items.put(item, (int) next);
        return true;
    }

    private static long usedSlots(Map<Item, Integer> items) {
        long total = 0;
        for (Map.Entry<Item, Integer> entry : items.entrySet()) {
            int limit = entry.getKey().stackLimit;
            total += ((long) entry.getValue() + limit - 1) / limit;
        }
        return total;
    }

    private void replaceBag(Map<Item, Integer> next) {
        bag.clear();
        bag.putAll(next);
    }
}
