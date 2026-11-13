package org.neosteam.revival.gameplay.network;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Activity binding for default-route monitoring and foreground reconnect consent. */
public final class AndroidNetworkRecovery implements AutoCloseable {
    private final Activity activity;
    private final NetworkRecovery recovery;
    private final AndroidNetworkMonitor monitor;
    private final Consumer<NetworkRecovery.Snapshot> observer;
    private final boolean canReconnect;
    private AlertDialog dialog;
    private NetworkRecovery.Snapshot snapshot;
    private Prompt lastPrompt;
    private boolean foreground;

    private record Prompt(long handle, boolean metered, NetworkRecovery.State state, int attempt) {}

    public AndroidNetworkRecovery(Activity activity, NetworkRecovery.Transport transport,
                                  Consumer<NetworkRecovery.Snapshot> observer) {
        this(activity, transport, NetworkRecovery.Route::usableForPublicServer, observer);
    }

    public AndroidNetworkRecovery(Activity activity, NetworkRecovery.Transport transport,
                                  Predicate<NetworkRecovery.Route> routePolicy,
                                  Consumer<NetworkRecovery.Snapshot> observer) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.observer = Objects.requireNonNull(observer, "observer");
        canReconnect = transport != null;
        Handler main = new Handler(Looper.getMainLooper());
        recovery = new NetworkRecovery((delay, action) -> {
            main.postDelayed(action, delay);
            return () -> main.removeCallbacks(action);
        }, SystemClock::elapsedRealtime, transport, routePolicy, this::stateChanged);
        monitor = new AndroidNetworkMonitor(activity, recovery::setRoute);
    }

    public void onResume() {
        if (foreground) return;
        foreground = true;
        monitor.start();
        recovery.resume();
        maybePrompt();
    }

    public void onPause() {
        if (!foreground) return;
        foreground = false;
        dismissPrompt();
        lastPrompt = null;
        recovery.pause();
        monitor.stop();
    }

    @Override
    public void close() {
        onPause();
        recovery.close();
    }

    /** Session callbacks use this controller on the activity's UI thread. */
    public NetworkRecovery session() {
        return recovery;
    }

    private void stateChanged(NetworkRecovery.Snapshot next) {
        snapshot = next;
        observer.accept(next);
        if (next.state() == NetworkRecovery.State.CONNECTED) lastPrompt = null;
        maybePrompt();
    }

    private void maybePrompt() {
        if (!foreground || !canReconnect || snapshot == null || activity.isFinishing()
                || activity.isDestroyed()) return;
        var state = snapshot.state();
        if (state != NetworkRecovery.State.RECONNECT_AVAILABLE
                && state != NetworkRecovery.State.RECONNECT_FAILED
                && state != NetworkRecovery.State.RECONNECTING) {
            dismissPrompt();
            return;
        }
        var route = snapshot.route();
        Prompt prompt = new Prompt(route.networkHandle(), route.metered(), state, snapshot.attempt());
        if (prompt.equals(lastPrompt)) return;
        lastPrompt = prompt;
        dismissPrompt();
        if (state == NetworkRecovery.State.RECONNECTING) {
            dialog = new AlertDialog.Builder(activity)
                .setTitle("Reconnecting")
                .setMessage("Attempt " + snapshot.attempt() + " on " + route.kind().label() + ".")
                .setNegativeButton("Cancel", (ignored, which) -> {
                    if (foreground && prompt.equals(lastPrompt)) {
                        lastPrompt = new Prompt(route.networkHandle(), route.metered(),
                            NetworkRecovery.State.RECONNECT_AVAILABLE, 0);
                        recovery.cancelReconnect();
                    }
                })
                .setCancelable(false)
                .show();
            return;
        }
        String action = state == NetworkRecovery.State.RECONNECT_FAILED ? "Try again" : "Reconnect";
        String message = state == NetworkRecovery.State.RECONNECT_FAILED
            ? "The reconnect attempts ended. You can start another session."
            : "Your game connection ended. Reconnect to start a fresh session.";
        if (route.metered()) message += " The current connection is metered.";
        dialog = new AlertDialog.Builder(activity)
            .setTitle(route.kind().label() + " available")
            .setMessage(message)
            .setNegativeButton("Stay offline", null)
            .setPositiveButton(action, (ignored, which) -> {
                if (foreground && prompt.equals(lastPrompt)) recovery.requestReconnect();
            })
            .show();
    }

    private void dismissPrompt() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }
}
