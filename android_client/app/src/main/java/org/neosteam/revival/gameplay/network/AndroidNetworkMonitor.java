package org.neosteam.revival.gameplay.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import java.util.Objects;
import java.util.function.Consumer;

/** Observes the app's default network while its activity is foregrounded. */
public final class AndroidNetworkMonitor {
    private final ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<NetworkRecovery.Route> listener;
    private Watch watch;
    private Network active;
    private NetworkCapabilities capabilities;
    private boolean blocked;

    public AndroidNetworkMonitor(Context context, Consumer<NetworkRecovery.Route> listener) {
        connectivity = context.getSystemService(ConnectivityManager.class);
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void start() {
        if (watch != null || connectivity == null) return;
        Watch replacement = new Watch();
        try {
            connectivity.registerDefaultNetworkCallback(replacement);
        } catch (SecurityException denied) {
            listener.accept(NetworkRecovery.Route.unavailable());
            return;
        }
        watch = replacement;
        try {
            // Initial snapshot outside the callback. Later updates use callback arguments.
            active = connectivity.getActiveNetwork();
            capabilities = active == null ? null : connectivity.getNetworkCapabilities(active);
        } catch (SecurityException denied) {
            active = null;
            capabilities = null;
        }
        publish();
    }

    public void stop() {
        Watch previous = watch;
        if (previous == null) return;
        watch = null;
        try {
            connectivity.unregisterNetworkCallback(previous);
        } finally {
            active = null;
            capabilities = null;
            blocked = false;
            publish();
        }
    }

    private void publish() {
        if (active == null || capabilities == null) {
            listener.accept(NetworkRecovery.Route.unavailable());
            return;
        }
        NetworkCapabilities caps = capabilities;
        NetworkRecovery.Kind kind = NetworkRecovery.Kind.OTHER;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) kind = NetworkRecovery.Kind.VPN;
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) kind = NetworkRecovery.Kind.WIFI;
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) kind = NetworkRecovery.Kind.CELLULAR;
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) kind = NetworkRecovery.Kind.ETHERNET;
        listener.accept(new NetworkRecovery.Route(active.getNetworkHandle(), kind,
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), blocked));
    }

    private final class Watch extends ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            post(() -> {
                if (network.equals(active)) return;
                active = network;
                capabilities = null;
                blocked = false;
                publish();
            });
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities updated) {
            post(() -> {
                if (!network.equals(active)) return;
                capabilities = updated;
                publish();
            });
        }

        @Override
        public void onBlockedStatusChanged(Network network, boolean accessBlocked) {
            post(() -> {
                if (!network.equals(active)) return;
                blocked = accessBlocked;
                publish();
            });
        }

        @Override
        public void onLost(Network network) {
            post(() -> {
                if (!network.equals(active)) return;
                active = null;
                capabilities = null;
                blocked = false;
                publish();
            });
        }

        private void post(Runnable event) {
            main.post(() -> {
                if (watch == this) event.run();
            });
        }
    }
}
