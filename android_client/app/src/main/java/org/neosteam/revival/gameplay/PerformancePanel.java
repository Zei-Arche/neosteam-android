package org.neosteam.revival.gameplay;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.neosteam.revival.ota.ContentUpdater;
import org.neosteam.revival.performance.ScenePreview;
import org.neosteam.revival.performance.StreamingAudio;

import java.util.concurrent.ExecutorService;

final class PerformancePanel {
    private final LinearLayout root;
    private final ScenePreview preview;
    private final StreamingAudio audio;
    private final TextView route;

    PerformancePanel(Context context, ExecutorService streams, ContentUpdater content) {
        root = GameplayViews.stack(context);
        TextView metrics = GameplayViews.text(context, "Starting GLES preview", 12, GameplayViews.TEXT, false);
        preview = new ScenePreview(context, streams, metrics::setText);
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(metrics);

        TextView playback = GameplayViews.text(context, "Audio stopped", 12, GameplayViews.MUTED, false);
        Button play = GameplayViews.button(context, "Play Ogg", false, ignored -> toggleAudio());
        audio = new StreamingAudio(context, streams, content, message -> {
            playback.setText(message);
            play.setText(isAudioActive() ? "Stop audio" : "Play Ogg");
        });
        route = GameplayViews.text(context, "Network status unavailable", 12, GameplayViews.MUTED, false);
        LinearLayout controls = new LinearLayout(context);
        controls.addView(GameplayViews.button(context, "Rotate left", false, ignored -> preview.rotate(-20)),
            new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(GameplayViews.button(context, "Rotate right", false, ignored -> preview.rotate(20)),
            new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(play, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(controls);
        root.addView(playback);
        root.addView(route);
    }

    View view() { return root; }

    void setActive(boolean active) {
        preview.setForeground(active);
        if (!active && audio.isActive()) audio.stop();
    }

    void trim() { preview.trimResources(); }

    void setRoute(String message) { route.setText(message); }

    private void toggleAudio() { audio.toggle(); }

    private boolean isAudioActive() { return audio.isActive(); }
}
