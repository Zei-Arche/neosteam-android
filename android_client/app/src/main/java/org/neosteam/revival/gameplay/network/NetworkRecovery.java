package org.neosteam.revival.gameplay.network;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Foreground network recovery policy. Callbacks and state changes run on one owner thread. */
public final class NetworkRecovery {
    public static final long HEARTBEAT_MS = 600_000;
    private static final long HANDOFF_DEBOUNCE_MS = 750;
    private static final long CONNECT_DEADLINE_MS = 35_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long FIRST_RETRY_MS = 1_000;

    public enum Kind {
        NONE("Offline"), WIFI("Wi-Fi"), CELLULAR("Cellular"), ETHERNET("Ethernet"),
        VPN("VPN"), OTHER("Network");

        private final String label;

        Kind(String label) { this.label = label; }

        public String label() { return label; }
    }

    public record Route(long networkHandle, Kind kind, boolean internet, boolean validated,
                        boolean metered, boolean blocked) {
        public Route {
            Objects.requireNonNull(kind, "kind");
        }

        public static Route unavailable() {
            return new Route(0, Kind.NONE, false, false, false, false);
        }

        public boolean usableForPublicServer() {
            return networkHandle != 0 && internet && validated && !blocked;
        }
    }

    public enum State {
        MONITORING, CONNECTED, WAITING_FOR_NETWORK, RECONNECT_AVAILABLE, RECONNECTING, RECONNECT_FAILED
    }

    public record Snapshot(Route route, State state, int attempt) {}

    @FunctionalInterface
    public interface Cancellation {
        void cancel();
    }

    @FunctionalInterface
    public interface Scheduler {
        // Deliver after the call returns, on the controller's owner thread.
        Cancellation after(long delayMillis, Runnable action);
    }

    public interface Transport {
        // Start a fresh connection, authentication, and world admission on this route. The
        // adapter reports completion with this attempt ID and a fresh increasing world ID.
        void reconnect(Route route, long attemptId);
        void cancelReconnect(long attemptId);
        // Revoke this exact world and close its stale sockets. Do not replay queued game actions.
        void releaseWorld(long ownershipId);
        // Queue the frame on every active world channel under this exact ownership, including
        // the primary when gameplay is delegated. False means the adapter could not queue it.
        boolean enqueueHeartbeat(long ownershipId, byte[] frame);
    }

    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final Transport transport;
    private final Predicate<Route> routePolicy;
    private final Consumer<Snapshot> observer;
    private Route route = Route.unavailable();
    private Route attemptRoute;
    private Snapshot lastPublished;
    private Cancellation handoffTimer;
    private Cancellation heartbeatTimer;
    private Cancellation retryTimer;
    private Cancellation deadlineTimer;
    private long ownership;
    private long ownedNetwork;
    private long lastOwnership;
    private long nextHeartbeat;
    private long nextAttemptId;
    private long attemptId;
    private int attempt;
    private boolean foreground;
    private boolean recovering;
    private boolean exhausted;
    private boolean closed;

    public NetworkRecovery(Scheduler scheduler, LongSupplier clock, Transport transport,
                           Predicate<Route> routePolicy, Consumer<Snapshot> observer) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transport = transport;
        this.routePolicy = Objects.requireNonNull(routePolicy, "routePolicy");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public static byte[] heartbeatFrame() {
        // Golden empty-packet framing is u16 LE payload length followed by u16 LE opcode.
        return new byte[]{0, 0, (byte) 0x90, 0};
    }

    public void resume() {
        if (closed || foreground) return;
        foreground = true;
        updateOwnership();
        publish();
    }

    public void pause() {
        if (closed || !foreground) return;
        foreground = false;
        cancelHandoff();
        cancelHeartbeat();
        clearAttempts();
        publish();
    }

    public void close() {
        if (closed) return;
        pause();
        closed = true;
        releaseOwnership();
    }

    public void setRoute(Route next) {
        Objects.requireNonNull(next, "route");
        if (closed || route.equals(next)) return;
        route = next;
        if (attemptRoute != null && !matchesAttemptRoute()) clearAttempts();
        updateOwnership();
        publish();
    }

    public void adoptWorld(long ownershipId, long networkHandle) {
        if (closed || transport == null || ownershipId <= lastOwnership || networkHandle == 0) return;
        clearAttempts();
        releaseOwnership();
        ownership = ownershipId;
        lastOwnership = ownershipId;
        ownedNetwork = networkHandle;
        nextHeartbeat = clock.getAsLong() + HEARTBEAT_MS;
        recovering = false;
        exhausted = false;
        updateOwnership();
        publish();
    }

    public void worldLost(long ownershipId) {
        if (closed || ownership == 0 || ownershipId != ownership) return;
        recoverWorld();
    }

    public void leaveWorld(long ownershipId) {
        if (closed || ownership == 0 || ownershipId != ownership) return;
        clearAttempts();
        releaseOwnership();
        recovering = false;
        exhausted = false;
        publish();
    }

    public boolean requestReconnect() {
        if (closed || !foreground || !recovering || !usable() || attemptRoute != null) return false;
        attemptRoute = route;
        exhausted = false;
        startAttempt();
        return true;
    }

    public void cancelReconnect() {
        if (attemptRoute == null) return;
        clearAttempts();
        publish();
    }

    public void reconnectSucceeded(long id, long ownershipId) {
        if (id == 0 || id > nextAttemptId) return;
        if (id != attemptId) {
            // A cancelled operation can still win a transport race. Dispose only the
            // returned lifetime, keeping current and previously admitted ownership safe.
            if (ownershipId > lastOwnership) transport.releaseWorld(ownershipId);
            return;
        }
        if (ownershipId <= lastOwnership) {
            reconnectFailed(id);
            return;
        }
        finishAttempt();
        adoptWorld(ownershipId, route.networkHandle());
    }

    public void reconnectFailed(long id) {
        if (id == 0 || id != attemptId) return;
        finishAttempt();
        retryOrExhaust();
    }

    private void startAttempt() {
        if (!foreground || !matchesAttemptRoute()) {
            clearAttempts();
            publish();
            return;
        }
        ++attempt;
        long id = ++nextAttemptId;
        attemptId = id;
        deadlineTimer = scheduler.after(CONNECT_DEADLINE_MS, () -> {
            if (attemptId == id) {
                finishAttempt();
                transport.cancelReconnect(id);
                retryOrExhaust();
            }
        });
        publish();
        if (attemptId == id) transport.reconnect(route, id);
    }

    private void retryOrExhaust() {
        if (attempt < MAX_ATTEMPTS && foreground && matchesAttemptRoute()) {
            long delay = FIRST_RETRY_MS << (attempt - 1);
            retryTimer = scheduler.after(delay, () -> {
                retryTimer = null;
                startAttempt();
            });
        } else {
            exhausted = attempt >= MAX_ATTEMPTS;
            clearAttempts();
        }
        publish();
    }

    private boolean matchesAttemptRoute() {
        return attemptRoute != null && usable() && attemptRoute.networkHandle() == route.networkHandle()
            && attemptRoute.metered() == route.metered();
    }

    private void updateOwnership() {
        if (!foreground || ownership == 0) return;
        if (onOwnedRoute()) {
            cancelHandoff();
            scheduleHeartbeat();
        } else {
            cancelHeartbeat();
            if (handoffTimer == null) {
                long watched = ownership;
                handoffTimer = scheduler.after(HANDOFF_DEBOUNCE_MS, () -> {
                    handoffTimer = null;
                    if (foreground && ownership == watched && !onOwnedRoute()) recoverWorld();
                });
            }
        }
    }

    private void scheduleHeartbeat() {
        if (heartbeatTimer != null) return;
        long watched = ownership;
        long delay = Math.max(0, nextHeartbeat - clock.getAsLong());
        heartbeatTimer = scheduler.after(delay, () -> {
            heartbeatTimer = null;
            if (!foreground || ownership != watched || !onOwnedRoute()) return;
            if (transport.enqueueHeartbeat(watched, heartbeatFrame())) {
                nextHeartbeat = clock.getAsLong() + HEARTBEAT_MS;
                scheduleHeartbeat();
            } else {
                recoverWorld();
            }
        });
    }

    private void recoverWorld() {
        recovering = true;
        exhausted = false;
        releaseOwnership();
        publish();
    }

    private void releaseOwnership() {
        long revoked = ownership;
        ownership = 0;
        ownedNetwork = 0;
        cancelHandoff();
        cancelHeartbeat();
        if (revoked != 0) transport.releaseWorld(revoked);
    }

    private boolean onOwnedRoute() {
        return ownedNetwork == route.networkHandle() && usable();
    }

    private boolean usable() {
        return route.networkHandle() != 0 && !route.blocked() && routePolicy.test(route);
    }

    private void finishAttempt() {
        attemptId = 0;
        if (deadlineTimer != null) {
            deadlineTimer.cancel();
            deadlineTimer = null;
        }
    }

    private void clearAttempts() {
        long cancelled = attemptId;
        finishAttempt();
        if (retryTimer != null) {
            retryTimer.cancel();
            retryTimer = null;
        }
        attemptRoute = null;
        attempt = 0;
        if (cancelled != 0) transport.cancelReconnect(cancelled);
    }

    private void cancelHandoff() {
        if (handoffTimer != null) {
            handoffTimer.cancel();
            handoffTimer = null;
        }
    }

    private void cancelHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
    }

    private void publish() {
        State state;
        if (ownership != 0) state = State.CONNECTED;
        else if (!recovering) state = State.MONITORING;
        else if (!usable()) state = State.WAITING_FOR_NETWORK;
        else if (attemptRoute != null) state = State.RECONNECTING;
        else if (exhausted) state = State.RECONNECT_FAILED;
        else state = State.RECONNECT_AVAILABLE;
        Snapshot snapshot = new Snapshot(route, state, attempt);
        if (!snapshot.equals(lastPublished)) {
            lastPublished = snapshot;
            observer.accept(snapshot);
        }
    }
}
