package org.neosteam.revival.diagnostics;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.neosteam.revival.BuildConfig;
import org.neosteam.revival.gameplay.network.NetworkRecovery;

import java.net.URI;
import java.util.Locale;

import static org.neosteam.revival.diagnostics.CrashReporter.Event.*;

/** Debug build hooks for diagnostics capture and explicitly requested loopback QA. */
public final class Diagnostics {
    private static final String TAG = "NeoSteamQA";
    private static final String QA_ACTION = "org.neosteam.revival.QA_CRASH";
    private static volatile CrashReporter reporter;

    private Diagnostics() {}

    static void start(Context context) {
        reporter = CrashReporter.start(context, BuildConfig.DIAGNOSTICS_URL);
        reporter.event(APP_STARTED);
    }

    public static void onResume(Activity activity) {
        CrashReporter active = reporter;
        if (active == null) return;
        active.event(APP_FOREGROUNDED);
        active.flush();
        if (!loopbackEndpoint()) return;
        String action = activity.getIntent().getStringExtra(QA_ACTION);
        if (action == null) return;
        activity.getIntent().removeExtra(QA_ACTION);
        new Handler(Looper.getMainLooper()).post(() -> {
            switch (action) {
                case "java" -> active.triggerJavaCrash();
                case "native" -> active.triggerNativeCrashForTest();
                case "log" -> active.queueLog();
                default -> { }
            }
        });
    }

    private static boolean loopbackEndpoint() {
        if (BuildConfig.DIAGNOSTICS_URL.isEmpty()) return false;
        String host = URI.create(BuildConfig.DIAGNOSTICS_URL).getHost().toLowerCase(Locale.ROOT);
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
    }

    public static void onPause() { event(APP_BACKGROUNDED); }

    public static void route(NetworkRecovery.Kind kind) {
        event(switch (kind) {
            case NONE -> NETWORK_OFFLINE;
            case WIFI -> NETWORK_WIFI;
            case CELLULAR -> NETWORK_CELLULAR;
            default -> NETWORK_OTHER;
        });
    }

    public static void otaInstalled() { event(OTA_INSTALLED); }

    public static void shaderCompiled(String vendor, String renderer, String version, String shadingLanguage) {
        Log.i(TAG, "GLES preview program ready | " + vendor + " | " + renderer + " | "
            + version + " | " + shadingLanguage);
        event(GLES_PROGRAM_READY);
    }

    public static void shaderCompileFailed(boolean vertex, String driverLog) {
        Log.e(TAG, (vertex ? "Vertex" : "Fragment") + " shader compilation failed | " + bounded(driverLog));
        reportShaderFailure(vertex ? GLES_VERTEX_COMPILE_FAILED : GLES_FRAGMENT_COMPILE_FAILED);
    }

    public static void shaderLinkFailed(String driverLog) {
        Log.e(TAG, "Preview program linking failed | " + bounded(driverLog));
        reportShaderFailure(GLES_PROGRAM_LINK_FAILED);
    }

    private static void reportShaderFailure(CrashReporter.Event value) {
        CrashReporter active = reporter;
        if (active != null) {
            active.event(value);
            active.queueLog();
        }
    }

    private static String bounded(String log) {
        return log == null ? "" : log.substring(0, Math.min(2048, log.length()));
    }

    private static void event(CrashReporter.Event value) {
        CrashReporter active = reporter;
        if (active != null) active.event(value);
    }
}
