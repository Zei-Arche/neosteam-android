package org.neosteam.revival.performance;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import org.neosteam.revival.ota.ContentUpdater;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Streams an installed or packaged Ogg track using a file descriptor and asynchronous decoding. */
public final class StreamingAudio {
    private final Context context;
    private final AudioManager audioManager;
    private final ExecutorService assets;
    private final ContentUpdater content;
    private final Consumer<String> status;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change < 0) stop();
    };
    private final AudioAttributes attributes = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    private AudioFocusRequest focus;
    private MediaPlayer player;
    private volatile int generation;
    private boolean active;
    private boolean focusGranted;

    public StreamingAudio(Context context, ExecutorService assets, ContentUpdater content, Consumer<String> status) {
        this.context = context;
        this.assets = assets;
        this.content = content;
        this.status = status;
        audioManager = context.getSystemService(AudioManager.class);
    }

    public void toggle() {
        if (active) {
            stop();
            return;
        }
        active = true;
        int request = ++generation;
        status.accept("Finding Ogg");
        try {
            assets.execute(() -> findTrack(request));
        } catch (RejectedExecutionException closed) {
            stop();
        }
    }

    @SuppressWarnings("deprecation")
    public void stop() {
        generation++;
        active = false;
        if (player != null) {
            player.release();
            player = null;
        }
        if (audioManager != null && focusGranted) {
            focusGranted = false;
            if (Build.VERSION.SDK_INT >= 26 && focus != null) audioManager.abandonAudioFocusRequest(focus);
            else audioManager.abandonAudioFocus(focusListener);
        }
        status.accept("Audio stopped");
    }

    public boolean isActive() { return active; }

    private void findTrack(int request) {
        if (request != generation) return;
        AssetFileDescriptor downloaded = openDownloadedTrack();
        AssetFileDescriptor ready = downloaded == null ? openPackagedTrack(request) : downloaded;
        boolean posted = main.post(() -> {
            try (AssetFileDescriptor track = ready) {
                if (!active || request != generation) return;
                if (track == null) {
                    stop();
                    status.accept("No streamable Ogg available");
                } else {
                    prepare(track, request, downloaded != null);
                }
            } catch (IOException | IllegalStateException error) {
                stop();
                status.accept("Could not open the Ogg stream");
            }
        });
        if (!posted && ready != null) {
            try {
                ready.close();
            } catch (IOException ignored) {
                // The callback queue closed before playback.
            }
        }
    }

    private AssetFileDescriptor openDownloadedTrack() {
        try {
            ContentUpdater.ActiveContent installed = content.activeContent();
            for (String path : installed.paths()) {
                if (!path.endsWith(".ogg")) continue;
                File track = installed.asset(path);
                if (track != null) {
                    long length = track.length();
                    return new AssetFileDescriptor(ParcelFileDescriptor.open(track,
                        ParcelFileDescriptor.MODE_READ_ONLY), 0, length);
                }
            }
        } catch (IOException unavailable) {
            // A packaged stream can still be used.
        }
        return null;
    }

    private AssetFileDescriptor openPackagedTrack(int request) {
        try (BufferedReader index = new BufferedReader(new InputStreamReader(
                context.getAssets().open("asset-index.txt"), StandardCharsets.UTF_8))) {
            int scanned = 0;
            for (String path; request == generation && scanned < 20_000 && (path = index.readLine()) != null; scanned++) {
                if (path.startsWith("converted/") && path.endsWith(".ogg")) {
                    return context.getAssets().openFd(path);
                }
            }
        } catch (IOException unavailable) {
            // Packaged Ogg is optional and must be stored uncompressed.
        }
        return null;
    }

    private void prepare(AssetFileDescriptor track, int request, boolean downloaded) throws IOException {
        player = new MediaPlayer();
        player.setAudioAttributes(attributes);
        player.setDataSource(track.getFileDescriptor(), track.getStartOffset(), track.getLength());
        player.setOnPreparedListener(prepared -> {
            if (request != generation || prepared != player) return;
            if (!requestFocus()) {
                stop();
                status.accept("Audio focus unavailable");
                return;
            }
            try {
                prepared.start();
                status.accept(downloaded ? "Streaming downloaded Ogg" : "Streaming packaged Ogg");
            } catch (IllegalStateException unavailable) {
                stop();
                status.accept("Ogg playback failed");
            }
        });
        player.setOnCompletionListener(complete -> {
            if (complete == player) {
                stop();
                status.accept("Audio complete");
            }
        });
        player.setOnErrorListener((failed, what, extra) -> {
            if (failed == player) {
                stop();
                status.accept("Ogg playback failed");
            }
            return true;
        });
        player.prepareAsync();
        status.accept("Preparing Ogg stream");
    }

    @SuppressWarnings("deprecation")
    private boolean requestFocus() {
        if (audioManager == null) return false;
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener).build();
            result = audioManager.requestAudioFocus(focus);
        } else {
            result = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        }
        focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focusGranted;
    }
}
