package org.neosteam.revival.update;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks the release channel before the client admits an entry attempt. */
public final class VersionGateScreen implements AutoCloseable {
    private final Activity activity;
    private final VersionGate gate;
    private final String manifestUrl;
    private final long installedVersion;
    private final Runnable onAdmitted;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScrollView root;
    private final TextView status;
    private final TextView detail;
    private final TextView checksum;
    private final ProgressBar progress;
    private final Button download;
    private final Button retry;
    private String apkUrl;
    private boolean checking;
    private boolean closed;

    public VersionGateScreen(Activity activity, String manifestUrl, long installedVersion,
                             boolean allowLocalHttp, Runnable onAdmitted) {
        this.activity = activity;
        this.gate = new VersionGate(allowLocalHttp);
        this.manifestUrl = manifestUrl;
        this.installedVersion = installedVersion;
        this.onAdmitted = onAdmitted;

        root = new ScrollView(activity);
        root.setFillViewport(true);
        root.setBackgroundColor(0xff0b1521);
        root.setOnApplyWindowInsetsListener(this::applyInsets);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(24), dp(16), dp(24), dp(16));
        root.addView(panel, new ScrollView.LayoutParams(-1, -2));

        status = new TextView(activity);
        status.setTextColor(0xffeff5fa);
        status.setTextSize(26);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(panel, status, 0);
        detail = new TextView(activity);
        detail.setTextColor(0xffeff5fa);
        detail.setTextSize(15);
        add(panel, detail, 8);
        checksum = new TextView(activity);
        checksum.setTextColor(0xffb6c8d7);
        checksum.setTextSize(13);
        checksum.setTextIsSelectable(true);
        add(panel, checksum, 8);
        progress = new ProgressBar(activity);
        add(panel, progress, 12);
        download = button("Download latest APK", this::openDownload);
        add(panel, download, 12);
        retry = button("Retry version check", this::check);
        add(panel, retry, 4);
    }

    public View view() { return root; }

    public void check() {
        if (closed || checking) return;
        checking = true;
        apkUrl = null;
        show("Checking client version", "", "", true);
        worker.execute(() -> {
            try {
                VersionGate.Result result = gate.check(manifestUrl, installedVersion);
                activity.runOnUiThread(() -> {
                    if (closed || activity.isFinishing()) return;
                    checking = false;
                    if (result.updateRequired()) {
                        var release = result.release();
                        apkUrl = release.apkUrl();
                        show("Update required", "Install NeoSteam " + release.versionName() + " (build "
                            + release.versionCode() + ") to continue.", "SHA-256\n" + release.sha256(), false);
                    } else {
                        onAdmitted.run();
                    }
                });
            } catch (IOException failure) {
                activity.runOnUiThread(() -> {
                    if (closed || activity.isFinishing()) return;
                    checking = false;
                    show("Version check unavailable", "Check your connection and retry before entering NeoSteam.",
                        "", false);
                });
            }
        });
    }

    private void show(String title, String description, String hash, boolean loading) {
        status.setText(title);
        detail.setText(description);
        detail.setVisibility(description.isEmpty() ? View.GONE : View.VISIBLE);
        checksum.setText(hash);
        checksum.setVisibility(hash.isEmpty() ? View.GONE : View.VISIBLE);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        download.setVisibility(apkUrl == null ? View.GONE : View.VISIBLE);
        retry.setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    private void openDownload() {
        if (apkUrl == null) return;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            detail.setText("Open the official NeoSteam download page in a browser to install the latest APK.");
        }
    }

    private Button button(String label, Runnable click) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private void add(LinearLayout panel, View child, int top) {
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, -2);
        position.topMargin = dp(top);
        panel.addView(child, position);
    }

    @SuppressWarnings("deprecation")
    private WindowInsets applyInsets(View view, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
        } else {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        }
        return insets;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
