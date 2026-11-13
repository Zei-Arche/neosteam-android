package org.neosteam.revival.diagnostics;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Debug diagnostics with a bounded private queue and explicit destination. */
public final class CrashReporter {
    private static final String TAG = "NeoSteamDiagnostics";
    private static final int MAX_REPORTS = 16;
    private static final int MAX_REPORT_BYTES = 32 * 1024;
    private static final int MAX_FRAMES = 24;
    private static final int MAX_SYMBOL_CHARS = 160;
    private static final long MIN_UPLOAD_INTERVAL_MS = 5_000;
    private static final long TEMP_MAX_AGE_MS = TimeUnit.DAYS.toMillis(1);
    private static final String REPORT_ID = "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}";
    private static final Pattern REPORT_NAME = Pattern.compile("(native|java|log)-(" + REPORT_ID + ")\\.report");
    private static final Pattern NATIVE_RECORD = Pattern.compile(
        "signal=([0-9]{1,3})\n(?:code=(-?[0-9]{1,10})\n)?events=([0-9]{1,10})\n");
    private static final String EXIT_WATERMARK = "native_exit_watermark";
    private static final String EXIT_SUMMARY = "NeoSteamQA1:";
    private static final Pattern NATIVE_SUMMARY = Pattern.compile(
        EXIT_SUMMARY + "(native-" + REPORT_ID + "\\.report):([0-9]{1,10})");

    public enum Event {
        APP_STARTED, APP_FOREGROUNDED, APP_BACKGROUNDED,
        NETWORK_OFFLINE, NETWORK_WIFI, NETWORK_CELLULAR, NETWORK_OTHER, OTA_INSTALLED,
        TEST_JAVA_CRASH, TEST_NATIVE_CRASH,
        GLES_PROGRAM_READY, GLES_VERTEX_COMPILE_FAILED, GLES_FRAGMENT_COMPILE_FAILED, GLES_PROGRAM_LINK_FAILED
    }

    private static CrashReporter instance;

    private static native boolean installNative(String absoluteDirectory, String reportName);
    private static native void setNativeEvents(int eventMask);
    private static native void crashNativeForTest();

    private final File directory;
    private final String currentNativeReport;
    private final ActivityManager activityManager;
    private final SharedPreferences exitHistory;
    private final URL endpoint;
    private final String packageName;
    private final String versionName;
    private final long versionCode;
    private final boolean nativeInstalled;
    private final ScheduledExecutorService uploader = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean handlingCrash = new AtomicBoolean();
    private final AtomicBoolean logPending = new AtomicBoolean();
    private final Object scheduling = new Object();
    private volatile int eventMask;
    private boolean flushScheduled;
    private boolean flushRequested;
    private long nextAttemptAt;

    public static synchronized CrashReporter start(Context context, String endpoint) {
        if (instance == null) instance = new CrashReporter(context.getApplicationContext(), endpoint);
        return instance;
    }

    @SuppressWarnings("deprecation")
    private CrashReporter(Context context, String url) {
        directory = new File(context.getNoBackupFilesDir(), "diagnostics");
        currentNativeReport = "native-" + UUID.randomUUID() + ".report";
        activityManager = context.getSystemService(ActivityManager.class);
        exitHistory = context.getSharedPreferences("diagnostic_exit_history", Context.MODE_PRIVATE);
        endpoint = parseEndpoint(url);
        packageName = context.getPackageName();
        PackageInfo info = null;
        try {
            info = context.getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            Log.w(TAG, "App version unavailable");
        }
        versionName = info == null ? "" : info.versionName;
        versionCode = info == null ? 0 : Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        nativeInstalled = initializeNative();
        installJavaHandler();
        flush();
    }

    private boolean initializeNative() {
        try {
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                Log.w(TAG, "Diagnostic queue unavailable");
                return false;
            }
            System.loadLibrary("neosteam");
            if (installNative(directory.getAbsolutePath(), currentNativeReport)) return true;
        } catch (RuntimeException | LinkageError ignored) {
            // The Java handler can still run.
        }
        Log.w(TAG, "Native crash hook unavailable");
        return false;
    }

    private void installJavaHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
            try {
                if (handlingCrash.compareAndSet(false, true)) {
                    queue("java", "java_crash", javaDetails(exception), eventMask);
                }
            } catch (Throwable ignored) {
                // Capture is best effort. Android still receives the original exception.
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, exception);
                } else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    public synchronized void event(Event event) {
        int bit = 1 << event.ordinal();
        if ((eventMask & bit) != 0) return;
        eventMask |= bit;
        if (nativeInstalled) setNativeEvents(eventMask);
        if (event == Event.APP_STARTED || event == Event.TEST_NATIVE_CRASH) publishExitSummary();
    }

    private void publishExitSummary() {
        if (Build.VERSION.SDK_INT < 30 || activityManager == null) return;
        try {
            byte[] summary = (EXIT_SUMMARY + currentNativeReport + ":" + eventMask)
                .getBytes(StandardCharsets.US_ASCII);
            activityManager.setProcessStateSummary(summary);
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Android process-exit summary unavailable");
        }
    }

    /** Schedule a sweep on startup or foreground. Concurrent requests are coalesced. */
    public void flush() {
        synchronized (scheduling) {
            flushRequested = true;
            if (!flushScheduled) scheduleSweep();
        }
    }

    private void scheduleSweep() {
        flushScheduled = true;
        long delay = Math.max(0, nextAttemptAt - SystemClock.elapsedRealtime());
        uploader.schedule(this::sweep, delay, TimeUnit.MILLISECONDS);
    }

    private void sweep() {
        synchronized (scheduling) {
            flushRequested = false;
            nextAttemptAt = SystemClock.elapsedRealtime() + MIN_UPLOAD_INTERVAL_MS;
        }
        try {
            pruneTemporaryFiles();
            if (Build.VERSION.SDK_INT >= 30 && activityManager != null) importNativeExits();
            File[] files = queuedReports();
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                if (i >= MAX_REPORTS || file.length() > MAX_REPORT_BYTES || file.length() == 0) {
                    remove(file);
                    continue;
                }
                if (endpoint != null) attempt(file);
            }
        } catch (RuntimeException ignored) {
            Log.w(TAG, "Diagnostic queue sweep failed");
        } finally {
            synchronized (scheduling) {
                flushScheduled = false;
                if (flushRequested) scheduleSweep();
            }
        }
    }

    @android.annotation.TargetApi(30)
    private void importNativeExits() {
        long seen = exitHistory.getLong(EXIT_WATERMARK, 0);
        long latest = seen;
        try {
            for (ApplicationExitInfo exit : activityManager.getHistoricalProcessExitReasons(packageName, 0, MAX_REPORTS)) {
                if (exit.getTimestamp() <= seen || exit.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE) continue;
                byte[] summary = exit.getProcessStateSummary();
                if (summary == null || summary.length > 128) continue;
                Matcher match = NATIVE_SUMMARY.matcher(new String(summary, StandardCharsets.US_ASCII));
                if (!match.matches()) continue;
                File target = new File(directory, match.group(1));
                if (!target.isFile()) {
                    int signal = exit.getStatus();
                    if (signal <= 0 || signal > 127) continue;
                    byte[] fallback = ("signal=" + signal + "\nevents=" + match.group(2) + "\n")
                        .getBytes(StandardCharsets.US_ASCII);
                    persist(target, fallback);
                }
                latest = Math.max(latest, exit.getTimestamp());
            }
            if (latest > seen) exitHistory.edit().putLong(EXIT_WATERMARK, latest).commit();
        } catch (IOException | RuntimeException unavailable) {
            Log.w(TAG, "Could not recover an Android native crash report");
        }
    }

    private File[] queuedReports() {
        File[] files = directory.listFiles((dir, name) ->
            !name.equals(currentNativeReport) && REPORT_NAME.matcher(name).matches());
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files;
    }

    private void pruneTemporaryFiles() {
        long cutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS;
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".report.tmp")
            && REPORT_NAME.matcher(name.substring(0, name.length() - 4)).matches());
        if (files == null) return;
        for (File file : files) {
            if (file.lastModified() < cutoff) remove(file);
        }
    }

    private void attempt(File file) {
        Matcher name = REPORT_NAME.matcher(file.getName());
        if (!name.matches()) return;
        String reportId = name.group(2);
        byte[] payload;
        try {
            byte[] saved = readReport(file);
            payload = "native".equals(name.group(1)) ? nativePayload(saved, reportId) : saved;
        } catch (IOException | JSONException | NumberFormatException ignored) {
            Log.w(TAG, "Could not read queued diagnostic report");
            return;
        }
        if (payload == null) {
            remove(file);
            return;
        }
        try {
            if (upload(payload, reportId)) remove(file);
        } catch (IOException ignored) {
            Log.i(TAG, "Diagnostic report retained for retry");
        }
    }

    private boolean upload(byte[] bytes, String reportId) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        try {
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(4_000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Idempotency-Key", reportId);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (var output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) return true;
            Log.i(TAG, "Diagnostic report retained after HTTP " + status);
            return false;
        } finally {
            connection.disconnect();
        }
    }

    public void queueLog() {
        if (!logPending.compareAndSet(false, true)) return;
        int snapshot = eventMask;
        uploader.execute(() -> {
            try {
                queue("log", "diagnostic_log", new JSONObject(), snapshot);
                File[] files = queuedReports();
                for (int i = MAX_REPORTS; i < files.length; i++) remove(files[i]);
                flush();
            } catch (IOException | JSONException ignored) {
                Log.w(TAG, "Could not queue diagnostic log");
            } finally {
                logPending.set(false);
            }
        });
    }

    public void triggerJavaCrash() {
        event(Event.TEST_JAVA_CRASH);
        throw new IllegalStateException("NeoSteam QA test crash");
    }

    public void triggerNativeCrashForTest() {
        if (!nativeInstalled) return;
        event(Event.TEST_NATIVE_CRASH);
        crashNativeForTest();
    }

    private void queue(String prefix, String kind, JSONObject report, int mask) throws IOException, JSONException {
        String id = UUID.randomUUID().toString();
        File target = new File(directory, prefix + "-" + id + ".report");
        byte[] bytes = payload(id, kind, report, mask);
        if (bytes.length > MAX_REPORT_BYTES) return;
        persist(target, bytes);
    }

    private static void persist(File target, byte[] bytes) throws IOException {
        File stage = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(stage)) {
                output.write(bytes);
                output.getFD().sync();
            }
            Os.rename(stage.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException error) {
            throw new IOException("Could not publish a diagnostic report", error);
        } finally {
            remove(stage);
        }
    }

    private byte[] nativePayload(byte[] saved, String id) throws JSONException {
        Matcher record = NATIVE_RECORD.matcher(new String(saved, StandardCharsets.US_ASCII));
        if (!record.matches()) return null;
        int signal = Integer.parseInt(record.group(1));
        long events = Long.parseLong(record.group(3));
        if (events > 0xffffffffL) return null;
        JSONObject report = new JSONObject().put("signal", signal);
        if (record.group(2) != null) report.put("code", Integer.parseInt(record.group(2)));
        else report.put("source", "android_exit");
        return payload(id, "native_crash", report, (int) events);
    }

    private byte[] payload(String id, String kind, JSONObject report, int mask) throws JSONException {
        JSONArray events = new JSONArray();
        for (Event event : Event.values()) {
            if ((mask & (1 << event.ordinal())) != 0) events.put(event.name());
        }
        return new JSONObject()
            .put("schema_version", 1)
            .put("report_id", id)
            .put("kind", kind)
            .put("package_name", packageName)
            .put("version_name", versionName == null ? "" : versionName)
            .put("version_code", versionCode)
            .put("android_api", Build.VERSION.SDK_INT)
            .put("events", events)
            .put("report", report)
            .toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JSONObject javaDetails(Throwable exception) throws JSONException {
        JSONArray frames = new JSONArray();
        StackTraceElement[] trace = exception.getStackTrace();
        for (int i = 0; i < Math.min(trace.length, MAX_FRAMES); i++) {
            StackTraceElement frame = trace[i];
            frames.put(new JSONObject()
                .put("class", bounded(frame.getClassName()))
                .put("method", bounded(frame.getMethodName()))
                .put("line", frame.getLineNumber()));
        }
        return new JSONObject().put("exception", bounded(exception.getClass().getName())).put("frames", frames);
    }

    private static String bounded(String symbol) {
        return symbol.substring(0, Math.min(symbol.length(), MAX_SYMBOL_CHARS));
    }

    private static byte[] readReport(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (output.size() + count > MAX_REPORT_BYTES) throw new IOException("Oversized diagnostic report");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void remove(File file) {
        if (file.exists() && !file.delete()) Log.w(TAG, "Could not remove a diagnostic queue file");
    }

    private static URL parseEndpoint(String configured) {
        if (configured == null || configured.isEmpty()) return null;
        try {
            URI uri = new URI(configured);
            String host = uri.getHost();
            if (host != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                && ("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "[::1]".equals(host))))) {
                return uri.toURL();
            }
        } catch (MalformedURLException | URISyntaxException ignored) {
            // Do not log the configured destination.
        }
        Log.w(TAG, "Invalid diagnostic endpoint");
        return null;
    }
}
