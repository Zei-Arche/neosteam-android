package org.neosteam.revival.gameplay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Native controls for combat selection, actions, and authoritative feedback. */
public final class CombatPanel {
    private static final int TEXT = GameplayViews.TEXT;
    private static final int MUTED = GameplayViews.MUTED;
    private static final int ACCENT = GameplayViews.ACCENT;
    private static final ColorStateList ACTION_TEXT = new ColorStateList(
            new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{MUTED, TEXT});

    private final CombatState state;
    private final Runnable onDefeat;
    private final LinearLayout root;
    private final LinearLayout targets;
    private final TextView playerHealth;
    private final ProgressBar healthBar;
    private final TextView targetInfo;
    private final TextView castInfo;
    private final TextView feedback;
    private final Button recover;
    private final Map<String, Button> skillButtons = new LinkedHashMap<>();
    private final Map<CombatState.EntityKey, Button> targetButtons = new LinkedHashMap<>();
    private int deliveredDefeats;
    private boolean foreground;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!foreground || !root.isAttachedToWindow()) return;
            refresh();
        }
    };

    public CombatPanel(Context context, CombatState state, Runnable onDefeat) {
        this.state = Objects.requireNonNull(state);
        this.onDefeat = Objects.requireNonNull(onDefeat);
        deliveredDefeats = state.snapshot().defeats();

        root = GameplayViews.stack(context);
        root.setPadding(dp(4), dp(12), dp(4), dp(16));

        playerHealth = label(root, 15, TEXT);
        healthBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        healthBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        healthBar.setContentDescription("Player health");
        root.addView(healthBar, new LinearLayout.LayoutParams(-1, dp(12)));

        heading("Target");
        targetInfo = label(root, 15, TEXT);
        Switch assisted = new Switch(context);
        assisted.setText("Assisted targeting");
        assisted.setTextColor(TEXT);
        assisted.setMinHeight(dp(48));
        assisted.setChecked(state.assistedTargeting());
        assisted.setOnCheckedChangeListener((button, enabled) -> state.setAssistedTargeting(enabled));
        root.addView(assisted);

        LinearLayout targetActions = new LinearLayout(context);
        root.addView(targetActions);
        Button cycle = button(targetActions, "Cycle target");
        Button clear = button(targetActions, "Clear target");
        for (Button button : new Button[]{cycle, clear}) {
            button.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        }
        cycle.setOnClickListener(view -> {
            state.cycleTarget();
            refresh();
        });
        clear.setOnClickListener(view -> {
            state.clearTarget();
            refresh();
        });

        targets = new LinearLayout(context);
        targets.setOrientation(LinearLayout.VERTICAL);
        root.addView(targets);
        heading("Skills");
        castInfo = label(root, 14, MUTED);
        for (CombatState.Skill skill : state.skills()) {
            Button action = button(root, skill.name());
            action.setOnClickListener(view -> {
                state.cast(skill.id());
                refresh();
            });
            skillButtons.put(skill.id(), action);
        }
        recover = button(root, "Recover");
        recover.setOnClickListener(view -> {
            state.recoverPracticePlayer();
            refresh();
        });
        heading("Combat log");
        feedback = label(root, 14, MUTED);

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) { refresh(); }

            @Override
            public void onViewDetachedFromWindow(View view) {
                root.removeCallbacks(tick);
            }
        });
        refresh();
    }

    public View view() {
        return root;
    }

    void setForeground(boolean active) {
        foreground = active;
        if (active) refresh();
        else root.removeCallbacks(tick);
    }

    public void refresh() {
        state.update();
        CombatState.Snapshot snapshot = state.snapshot();
        CombatState.Actor player = snapshot.player();
        setText(playerHealth, player.name() + " · " + player.health() + "/" + player.maxHealth()
                + " HP" + effects(snapshot.playerEffects()));
        healthBar.setMax(player.maxHealth());
        healthBar.setProgress(player.health());
        recover.setVisibility(state.isPractice() && player.health() == 0 ? View.VISIBLE : View.GONE);

        CombatState.Actor target = snapshot.target();
        setText(targetInfo, target == null ? "No target selected"
                : target.name() + " · " + target.health() + "/" + target.maxHealth() + " HP"
                + effects(snapshot.targetEffects()));
        updateTargets(snapshot);
        CombatState.ActiveCast cast = snapshot.cast();
        setText(castInfo, cast == null ? (player.health() > 0 ? "Ready" : "Defeated")
                : cast.skill().name() + " → " + cast.target() + " · "
                + (cast.awaitingResult() ? "Awaiting result" : time(cast.remainingMs())));
        for (CombatState.Cooldown cooldown : snapshot.cooldowns()) {
            Button button = skillButtons.get(cooldown.skill().id());
            String title = cooldown.skill().name();
            if (cooldown.remainingMs() > 0) {
                title += " · " + time(cooldown.remainingMs());
            } else if (cooldown.skill().castMs() > 0) {
                title += " · " + time(cooldown.skill().castMs()) + " cast";
            }
            setText(button, title);
            button.setEnabled(player.health() > 0 && cast == null && cooldown.remainingMs() == 0);
        }
        StringBuilder log = new StringBuilder();
        for (int i = Math.max(0, snapshot.feedback().size() - 4); i < snapshot.feedback().size(); ++i) {
            if (log.length() > 0) {
                log.append('\n');
            }
            log.append(snapshot.feedback().get(i));
        }
        setText(feedback, log.toString());
        while (deliveredDefeats < snapshot.defeats()) {
            ++deliveredDefeats;
            if (state.isPractice()) {
                onDefeat.run();
            }
        }
        root.removeCallbacks(tick);
        if (foreground && root.isAttachedToWindow()
                && (cast != null || !snapshot.playerEffects().isEmpty() || !snapshot.targetEffects().isEmpty()
                    || snapshot.cooldowns().stream().anyMatch(cooldown -> cooldown.remainingMs() > 0))) {
            root.postDelayed(tick, 150);
        }
    }

    private void updateTargets(CombatState.Snapshot snapshot) {
        Map<CombatState.EntityKey, Button> current = new LinkedHashMap<>();
        for (CombatState.Actor actor : snapshot.targets()) {
            Button button = targetButtons.get(actor.key());
            if (button == null) {
                button = button(targets, actor.name());
                button.setOnClickListener(view -> {
                    state.selectTarget(actor.key());
                    refresh();
                });
            }
            current.put(actor.key(), button);
            boolean selected = snapshot.target() != null && snapshot.target().key().equals(actor.key());
            String stateText = actor.health() == 0 ? "defeated" : actor.health() + "/" + actor.maxHealth() + " HP";
            setText(button, (selected ? "Selected · " : "") + actor.name() + " · " + stateText);
            button.setEnabled(actor.selectable() && actor.visible() && actor.health() > 0);
            if (button.isSelected() != selected) {
                GameplayViews.styleButton(button, selected);
                if (!selected) {
                    button.setTextColor(ACTION_TEXT);
                }
            }
        }
        for (Map.Entry<CombatState.EntityKey, Button> entry : targetButtons.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                targets.removeView(entry.getValue());
            }
        }
        targetButtons.clear();
        targetButtons.putAll(current);
    }

    private void heading(String text) {
        TextView heading = label(root, 16, ACCENT);
        heading.setPadding(0, dp(14), 0, dp(4));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setText(text);
    }

    private TextView label(LinearLayout container, int size, int color) {
        TextView label = new TextView(root.getContext());
        label.setTextSize(size);
        label.setTextColor(color);
        container.addView(label);
        return label;
    }

    private Button button(LinearLayout container, String text) {
        Button button = GameplayViews.button(root.getContext(), text, false, null);
        button.setTextColor(ACTION_TEXT);
        container.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * root.getResources().getDisplayMetrics().density);
    }

    private static String effects(List<CombatState.ActiveStatus> statuses) {
        StringBuilder label = new StringBuilder();
        for (CombatState.ActiveStatus status : statuses) {
            label.append(" · ").append(status.name()).append(' ').append(time(status.remainingMs()));
        }
        return label.toString();
    }

    private static void setText(TextView view, String text) {
        if (!text.contentEquals(view.getText())) {
            view.setText(text);
        }
    }

    private static String time(long durationMs) {
        long tenths = durationMs / 100 + (durationMs % 100 == 0 ? 0 : 1);
        return (tenths / 10) + "." + (tenths % 10) + "s";
    }
}
