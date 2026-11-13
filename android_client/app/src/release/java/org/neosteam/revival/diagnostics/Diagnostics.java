package org.neosteam.revival.diagnostics;

import android.app.Activity;

import org.neosteam.revival.gameplay.network.NetworkRecovery;

/** Release variants omit crash collection, uploads and test actions. */
public final class Diagnostics {
    private Diagnostics() {}

    public static void onResume(Activity activity) {}
    public static void onPause() {}
    public static void route(NetworkRecovery.Kind kind) {}
    public static void otaInstalled() {}
    public static void shaderCompiled(String vendor, String renderer, String version, String shadingLanguage) {}
    public static void shaderCompileFailed(boolean vertex, String driverLog) {}
    public static void shaderLinkFailed(String driverLog) {}
}
