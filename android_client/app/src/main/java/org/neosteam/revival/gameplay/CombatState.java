package org.neosteam.revival.gameplay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * UI-thread combat state. Catalog keys and entity keys are client values. A
 * connected client must supply a verified cast transport and decoded results.
 */
public final class CombatState {
    private static final long RESULT_TIMEOUT_MS = 10_000;
    private static final int MAX_FEEDBACK = 12;

    public enum Relation { HOSTILE, FRIENDLY, SELF }

    public record EntityKey(long world, long id, long generation) {}

    public record Actor(EntityKey key, String name, Relation relation, int health, int maxHealth,
                        boolean selectable, boolean visible, boolean lineOfSight,
                        boolean attackingPlayer, double reticleDistance, double distance) {
        public Actor {
            Objects.requireNonNull(key);
            Objects.requireNonNull(name);
            Objects.requireNonNull(relation);
            if (name.isEmpty() || maxHealth <= 0 || health < 0 || health > maxHealth
                    || !Double.isFinite(reticleDistance) || reticleDistance < 0
                    || !Double.isFinite(distance) || distance < 0) {
                throw new IllegalArgumentException("Invalid combat actor");
            }
        }

        Actor withHealth(int value) {
            return new Actor(key, name, relation, value, maxHealth, selectable, visible,
                    lineOfSight, attackingPlayer, reticleDistance, distance);
        }
    }

    public record Skill(String id, String name, Relation target, double range, long castMs,
                        long cooldownMs) {
        public Skill {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(target);
            if (id.isEmpty() || name.isEmpty() || !Double.isFinite(range) || range < 0
                    || castMs < 0 || cooldownMs < 0) {
                throw new IllegalArgumentException("Invalid combat skill");
            }
        }
    }

    public record Status(String name, long durationMs) {
        public Status {
            Objects.requireNonNull(name);
            if (name.isEmpty() || durationMs <= 0) {
                throw new IllegalArgumentException("Invalid combat status");
            }
        }
    }

    /** Absolute health and optional status from a decoded authoritative event. */
    public record Impact(EntityKey target, int health, Status status) {
        public Impact {
            Objects.requireNonNull(target);
            if (health < 0) {
                throw new IllegalArgumentException("Invalid combat health");
            }
        }
    }

    public record CastIntent(long requestId, String skillId, EntityKey caster, EntityKey target) {}

    @FunctionalInterface
    public interface CastTransport {
        /** Return false if the adapter could not queue the action. */
        boolean submit(CastIntent intent);
    }

    public record Cooldown(Skill skill, long remainingMs) {}
    public record ActiveCast(Skill skill, String target, long remainingMs, boolean awaitingResult) {}
    public record ActiveStatus(String name, long remainingMs) {}

    public record Snapshot(Actor player, Actor target, List<Actor> targets,
                           List<Cooldown> cooldowns, ActiveCast cast, List<ActiveStatus> playerEffects,
                           List<ActiveStatus> targetEffects, List<String> feedback, int defeats) {}

    private record Cast(CastIntent intent, Skill skill, String targetName, long resolvesAt,
                        long deadline) {}

    private final LongSupplier clock;
    private final CastTransport transport;
    private final Map<String, Skill> catalog = new LinkedHashMap<>();
    private final Map<EntityKey, Actor> actors = new LinkedHashMap<>();
    private final Map<String, Long> cooldownEnds = new HashMap<>();
    private final Map<EntityKey, Map<String, Long>> statuses = new HashMap<>();
    private final ArrayDeque<String> feedback = new ArrayDeque<>();
    private final double acquisitionRange;
    private EntityKey playerKey;
    private EntityKey selected;
    private List<EntityKey> cycle = Collections.emptyList();
    private int cycleIndex = -1;
    private Cast pending;
    private long nextRequest = 1;
    private int defeats;
    private boolean assisted = true;

    public CombatState(LongSupplier clock, Actor player, List<Actor> targets, List<Skill> skills,
                       double acquisitionRange, CastTransport transport) {
        this.clock = Objects.requireNonNull(clock);
        if (!Double.isFinite(acquisitionRange) || acquisitionRange < 0) {
            throw new IllegalArgumentException("Invalid targeting range");
        }
        this.acquisitionRange = acquisitionRange;
        this.transport = Objects.requireNonNull(transport);
        for (Skill skill : skills) {
            if (catalog.put(skill.id(), skill) != null) {
                throw new IllegalArgumentException("Duplicate skill");
            }
        }
        replaceActors(player, targets);
    }

    private CombatState(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        acquisitionRange = 10;
        transport = null;
        for (Skill skill : Arrays.asList(
                new Skill("strike", "Strike", Relation.HOSTILE, 5, 0, 900),
                new Skill("steam-burst", "Steam Burst", Relation.HOSTILE, 10, 650, 3000),
                new Skill("field-mend", "Field Mend", Relation.SELF, 0, 300, 4000))) {
            catalog.put(skill.id(), skill);
        }
        replaceActors(new Actor(new EntityKey(1, 1, 1), "Mechanic", Relation.SELF, 100, 100,
                true, true, true, false, 0, 0), Arrays.asList(
                practiceTarget(10, "Scout Piyo", 52, 2, 2, true),
                practiceTarget(11, "Wandering Piyo", 72, 1, 4, false),
                practiceTarget(12, "Watchful Piyo", 80, 3, 8, false)));
    }

    public static CombatState practice() {
        return practice(() -> System.nanoTime() / 1_000_000);
    }

    public static CombatState practice(LongSupplier clock) {
        return new CombatState(clock);
    }

    private static Actor practiceTarget(long id, String name, int health, double screen,
                                        double distance, boolean attacking) {
        return new Actor(new EntityKey(1, id, 1), name, Relation.HOSTILE, health, health, true,
                true, true, attacking, screen, distance);
    }

    public List<Skill> skills() {
        return immutable(new ArrayList<>(catalog.values()));
    }

    public boolean isPractice() {
        return transport == null;
    }

    public boolean assistedTargeting() {
        return assisted;
    }

    public void setAssistedTargeting(boolean enabled) {
        assisted = enabled;
    }

    /**
     * Replaces scene observations as a unit. The player lifetime scopes all
     * selections, casts, statuses, and timers.
     */
    public void replaceActors(Actor player, List<Actor> targets) {
        if (player.relation() != Relation.SELF) {
            throw new IllegalArgumentException("Player has invalid relation");
        }
        Map<EntityKey, Actor> next = new LinkedHashMap<>();
        next.put(player.key(), player);
        for (Actor actor : targets) {
            if (actor.relation() == Relation.SELF || actor.key().world() != player.key().world()
                    || next.put(actor.key(), actor) != null) {
                throw new IllegalArgumentException("Target is outside this scene or duplicated");
            }
        }
        if (!player.key().equals(playerKey)) {
            pending = null;
            cooldownEnds.clear();
            statuses.clear();
            clearTarget();
            feedback.clear();
        }
        playerKey = player.key();
        actors.clear();
        actors.putAll(next);
        statuses.keySet().retainAll(next.keySet());
        if (!selectable(actors.get(selected))) {
            selected = null;
        }
    }

    public boolean selectTarget(EntityKey key) {
        Actor target = actors.get(key);
        if (!selectable(target)) {
            return false;
        }
        resetCycle();
        selected = key;
        return true;
    }

    public void clearTarget() {
        selected = null;
        resetCycle();
    }

    public void cycleTarget() {
        if (cycle.isEmpty()) {
            List<EntityKey> order = new ArrayList<>();
            for (Actor actor : candidates(acquisitionRange, false)) {
                order.add(actor.key());
            }
            cycle = order;
        }
        for (int i = 0; i < cycle.size(); ++i) {
            cycleIndex = (cycleIndex + 1) % cycle.size();
            EntityKey key = cycle.get(cycleIndex);
            if (eligible(actors.get(key), acquisitionRange, false)) {
                selected = key;
                return;
            }
        }
        resetCycle();
        addFeedback("No hostile target in range");
    }

    public boolean cast(String skillId) {
        update();
        Skill skill = catalog.get(skillId);
        if (skill == null) {
            return reject("Unknown skill");
        }
        if (actors.get(playerKey).health() == 0) {
            return reject("Cannot cast while defeated");
        }
        if (pending != null) {
            return reject("A cast is already in progress");
        }
        long now = clock.getAsLong();
        if (remaining(cooldownEnds.get(skillId), now) > 0) {
            return reject(skill.name() + " is cooling down");
        }
        Actor target = skill.target() == Relation.SELF ? actors.get(playerKey) : actors.get(selected);
        if (target == null && skill.target() == Relation.HOSTILE && assisted) {
            List<Actor> candidates = candidates(skill.range(), true);
            if (!candidates.isEmpty()) {
                target = candidates.get(0);
                selected = target.key();
            }
        }
        String problem = castProblem(skill, target);
        if (problem != null) {
            return reject(problem);
        }
        CastIntent intent = new CastIntent(nextRequest++, skill.id(), playerKey, target.key());
        long resolvesAt = after(now, skill.castMs());
        pending = new Cast(intent, skill, target.name(), resolvesAt, after(resolvesAt, RESULT_TIMEOUT_MS));
        if (transport != null && !transport.submit(intent)) {
            pending = null;
            return reject("Could not queue " + skill.name());
        }
        if (skill.castMs() > 0) {
            addFeedback("Casting " + skill.name() + " on " + target.name());
        }
        update();
        return true;
    }

    /**
     * Resolves a transport request once. Cooldown is supplied by the adapter
     * in milliseconds from receipt. Damage and status use applyImpact.
     */
    public boolean resolveCast(long requestId, boolean accepted, long cooldownMs) {
        update();
        if (isPractice() || pending == null || pending.intent().requestId() != requestId
                || cooldownMs < 0) {
            return false;
        }
        Cast cast = pending;
        pending = null;
        if (accepted) {
            cooldownEnds.put(cast.skill().id(), after(clock.getAsLong(), cooldownMs));
            addFeedback(cast.skill().name() + " accepted");
        } else {
            addFeedback(cast.skill().name() + " rejected");
        }
        return true;
    }

    /**
     * Accepts an ordered, already validated event for a current entity key.
     * The adapter owns ordering and deduplication before this boundary.
     */
    public boolean applyImpact(Impact impact) {
        if (isPractice()) {
            return false;
        }
        return apply(impact, clock.getAsLong());
    }

    public void update() {
        long now = clock.getAsLong();
        if (pending != null) {
            if (isPractice() && now >= pending.resolvesAt()) {
                completePracticeCast();
            } else if (!isPractice() && now >= pending.deadline()) {
                addFeedback(pending.skill().name() + " response timed out");
                pending = null;
            }
        }
        cooldownEnds.values().removeIf(end -> now >= end);
        for (Map<String, Long> effects : statuses.values()) {
            effects.values().removeIf(end -> now >= end);
        }
        statuses.values().removeIf(Map::isEmpty);
    }

    public boolean recoverPracticePlayer() {
        Actor player = actors.get(playerKey);
        if (!isPractice() || player.health() > 0) {
            return false;
        }
        actors.put(playerKey, player.withHealth(player.maxHealth()));
        statuses.remove(playerKey);
        addFeedback("Recovered from defeat");
        return true;
    }

    public Snapshot snapshot() {
        long now = clock.getAsLong();
        List<Actor> targets = new ArrayList<>(actors.values());
        targets.remove(actors.get(playerKey));
        List<Cooldown> cooldowns = new ArrayList<>();
        for (Skill skill : catalog.values()) {
            cooldowns.add(new Cooldown(skill, remaining(cooldownEnds.get(skill.id()), now)));
        }
        ActiveCast cast = pending == null ? null : new ActiveCast(pending.skill(),
                pending.targetName(), remaining(pending.resolvesAt(), now),
                !isPractice() && now >= pending.resolvesAt());
        return new Snapshot(actors.get(playerKey), actors.get(selected), immutable(targets),
                immutable(cooldowns), cast, effects(playerKey, now), effects(selected, now),
                immutable(new ArrayList<>(feedback)), defeats);
    }

    private List<ActiveStatus> effects(EntityKey key, long now) {
        List<ActiveStatus> result = new ArrayList<>();
        Map<String, Long> effects = statuses.get(key);
        if (effects != null) {
            for (Map.Entry<String, Long> entry : effects.entrySet()) {
                long duration = remaining(entry.getValue(), now);
                if (duration > 0) {
                    result.add(new ActiveStatus(entry.getKey(), duration));
                }
            }
        }
        return immutable(result);
    }

    private void completePracticeCast() {
        Cast cast = pending;
        pending = null;
        Actor target = actors.get(cast.intent().target());
        String problem = castProblem(cast.skill(), target);
        if (problem != null || actors.get(playerKey).health() == 0) {
            addFeedback(cast.skill().name() + " interrupted"
                    + (problem == null ? "" : " · " + problem));
            return;
        }
        cooldownEnds.put(cast.skill().id(), after(cast.resolvesAt(), cast.skill().cooldownMs()));
        switch (cast.skill().id()) {
            case "strike" -> hit(target, 24, null, cast.resolvesAt());
            case "steam-burst" -> hit(target, 38, new Status("Scorched", 4000), cast.resolvesAt());
            case "field-mend" -> {
                int restored = Math.min(target.maxHealth() - target.health(), 26);
                apply(new Impact(target.key(), target.health() + restored,
                        new Status("Steady", 3500)), cast.resolvesAt());
            }
            default -> throw new IllegalStateException("Unknown practice skill");
        }
        Actor hostile = actors.get(cast.intent().target());
        if (hostile.relation() == Relation.HOSTILE && hostile.health() > 0) {
            hit(actors.get(playerKey), 6, null, cast.resolvesAt());
        }
    }

    private void hit(Actor target, int damage, Status status, long now) {
        apply(new Impact(target.key(), Math.max(0, target.health() - damage), status), now);
    }

    private boolean apply(Impact impact, long now) {
        Actor actor = actors.get(impact.target());
        if (actor == null || impact.health() > actor.maxHealth()) {
            return false;
        }
        actors.put(actor.key(), actor.withHealth(impact.health()));
        int change = impact.health() - actor.health();
        if (change < 0) {
            addFeedback(actor.name() + " took " + -change + " damage");
        } else if (change > 0) {
            addFeedback(actor.name() + " recovered " + change + " health");
        }
        if (impact.health() == 0) {
            statuses.remove(actor.key());
            if (actor.key().equals(selected)) {
                selected = null;
            }
            if (actor.health() > 0) {
                if (actor.relation() == Relation.HOSTILE) {
                    ++defeats;
                }
                addFeedback(actor.name() + " defeated");
            }
        } else if (impact.status() != null) {
            Status status = impact.status();
            statuses.computeIfAbsent(actor.key(), key -> new LinkedHashMap<>())
                    .put(status.name(), after(now, status.durationMs()));
            addFeedback(actor.name() + " gained " + status.name());
        }
        return true;
    }

    private String castProblem(Skill skill, Actor target) {
        if (!selectable(target) && (target == null || target.relation() != Relation.SELF
                || target.health() == 0)) {
            return "Select a living " + (skill.target() == Relation.FRIENDLY ? "friendly" : "target");
        }
        if (target.relation() != skill.target()) {
            return "Wrong target for " + skill.name();
        }
        if (target.relation() != Relation.SELF) {
            if (target.distance() > skill.range()) {
                return target.name() + " is out of range";
            }
            if (!target.lineOfSight()) {
                return target.name() + " is out of line of sight";
            }
        }
        return null;
    }

    private List<Actor> candidates(double range, boolean requiresLineOfSight) {
        List<Actor> eligible = new ArrayList<>();
        for (Actor actor : actors.values()) {
            if (eligible(actor, range, requiresLineOfSight)) {
                eligible.add(actor);
            }
        }
        eligible.sort(Comparator.comparing((Actor actor) -> !actor.attackingPlayer())
                .thenComparingDouble(Actor::reticleDistance)
                .thenComparingDouble(Actor::distance)
                .thenComparingLong(actor -> actor.key().id())
                .thenComparingLong(actor -> actor.key().generation()));
        return eligible;
    }

    private static boolean eligible(Actor actor, double range, boolean requiresLineOfSight) {
        return selectable(actor) && actor.relation() == Relation.HOSTILE && actor.visible()
                && actor.distance() <= range && (!requiresLineOfSight || actor.lineOfSight());
    }

    private static boolean selectable(Actor actor) {
        return actor != null && actor.selectable() && actor.health() > 0;
    }

    private void resetCycle() {
        cycle = Collections.emptyList();
        cycleIndex = -1;
    }

    private boolean reject(String message) {
        addFeedback(message);
        return false;
    }

    private void addFeedback(String message) {
        if (feedback.size() == MAX_FEEDBACK) {
            feedback.removeFirst();
        }
        feedback.addLast(message);
    }

    private static long after(long now, long duration) {
        return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }

    private static long remaining(Long end, long now) {
        return end == null || now >= end ? 0 : end - now;
    }

    private static <T> List<T> immutable(List<T> list) {
        return Collections.unmodifiableList(list);
    }
}
