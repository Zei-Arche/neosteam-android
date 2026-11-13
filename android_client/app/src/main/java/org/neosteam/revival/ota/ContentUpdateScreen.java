package org.neosteam.revival.ota;

import android.app.Activity;
import android.graphics.Insets;
import android.graphics.Typeface;
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
import java.io.InterruptedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground content check. Partial bundles remain on disk when the screen pauses. */
public final class ContentUpdateScreen implements AutoCloseable {
    private final Activity activity;
    private final ContentUpdater updater;
    private final URL manifest;
    private final Runnable onInstalled;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScrollView root;
    private final LinearLayout layout;
    private final TextView status;
    private final TextView detail;
    private final ProgressBar progress;
    private final Button check;
    private final Button cancel;
    private ContentUpdater.Cancellation operation;
    private boolean visible;
    private boolean foreground;
    private boolean autoCheck;
    private boolean closed;

    public ContentUpdateScreen(Activity activity, ContentUpdater updater, String manifestUrl,
                               Runnable onInstalled, Runnable onContinue) {
        this.activity = activity;
        this.updater = updater;
        this.onInstalled = onInstalled;
        try {
            manifest = manifestUrl.isEmpty() ? null : new URL(manifestUrl);
        } catch (MalformedURLException invalid) {
            throw new IllegalArgumentException("Invalid configured content endpoint", invalid);
        }

        root = new ScrollView(activity);
        root.setFillViewport(true);
        root.setBackgroundColor(0xff0b1521);
        root.setOnApplyWindowInsetsListener(this::applyInsets);
        layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp(24), dp(16), dp(24), dp(16));
        root.addView(layout, new ScrollView.LayoutParams(-1, -2));
        TextView title = text("Content updates", 27);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        layout.addView(title);
        status = text(manifest == null ? "Content server is not configured" : "Ready to check for updates", 17);
        status.setTextColor(0xfff4b66a);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(status, 14);
        detail = text(manifest == null ? "The app can use its packaged assets." : "", 14);
        add(detail, 4);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        add(progress, 12);

        LinearLayout controls = new LinearLayout(activity);
        check = button("Check for updates", this::start);
        check.setEnabled(manifest != null);
        cancel = button("Pause download", this::pauseDownload);
        cancel.setVisibility(View.GONE);
        controls.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        add(controls, 14);
        add(button("Continue to NeoSteam", onContinue), 2);
    }

    public View view() { return root; }

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

    public void show(boolean checkAutomatically) {
        visible = true;
        autoCheck = checkAutomatically;
        if (foreground && autoCheck) start();
    }

    public void hide() {
        visible = false;
        autoCheck = false;
        pauseDownload();
    }

    public void setForeground(boolean active) {
        foreground = active;
        if (!active && operation != null) {
            autoCheck = visible;
            pauseDownload();
        } else if (active && visible && autoCheck) {
            start();
        }
    }

    private void start() {
        if (closed || !visible || !foreground || manifest == null || operation != null) return;
        autoCheck = false;
        ContentUpdater.Cancellation request = new ContentUpdater.Cancellation();
        operation = request;
        setWorking(true);
        status.setText("Checking for updates");
        detail.setText("");
        progress.setProgress(0);
        worker.execute(() -> {
            try {
                ContentUpdater.UpdateResult result = updater.update(manifest, request,
                    new java.util.function.Consumer<ContentUpdater.Progress>() {
                        private String stage;
                        private long nextRender;

                        @Override
                        public void accept(ContentUpdater.Progress value) {
                            long now = android.os.SystemClock.uptimeMillis();
                            if (value.stage().equals(stage) && now < nextRender) return;
                            stage = value.stage();
                            nextRender = now + 100;
                            activity.runOnUiThread(() -> {
                                if (operation == request && !request.isCancelled()) render(value);
                            });
                        }
                    });
                activity.runOnUiThread(() -> {
                    if (!finish(request)) return;
                    status.setText(result.downloadedBundles() == 0 ? "Content is up to date" : "Content updated");
                    detail.setText(result.assetCount() + " verified assets");
                    onInstalled.run();
                });
            } catch (InterruptedIOException interrupted) {
                activity.runOnUiThread(() -> {
                    if (!finish(request)) return;
                    if (request.isCancelled()) {
                        status.setText("Download paused");
                        detail.setText("Check again to resume the download.");
                        if (autoCheck && foreground) start();
                    } else {
                        showFailure(interrupted);
                    }
                });
            } catch (IOException | RuntimeException failed) {
                activity.runOnUiThread(() -> {
                    if (finish(request)) {
                        if (request.isCancelled()) {
                            status.setText("Download paused");
                            detail.setText("Check again to resume the download.");
                            if (autoCheck && foreground) start();
                        } else {
                            showFailure(failed);
                        }
                    }
                });
            }
        });
    }

    private void showFailure(Exception failed) {
        status.setText("Content update failed");
        String message = failed.getMessage();
        detail.setText(message == null ? "Try again when the content server is available." : message);
    }

    private boolean finish(ContentUpdater.Cancellation request) {
        if (closed || operation != request) return false;
        operation = null;
        setWorking(false);
        return true;
    }

    private void pauseDownload() {
        if (operation != null && !operation.isCancelled()) {
            status.setText("Pausing download");
            cancel.setEnabled(false);
            operation.cancel();
        }
    }

    private void render(ContentUpdater.Progress value) {
        status.setText(switch (value.stage()) {
            case "checking" -> "Checking for updates";
            case "verifying" -> "Verifying content";
            case "downloading" -> "Downloading content";
            case "publishing" -> "Activating verified content";
            default -> "Checking content";
        });
        long total = value.totalBytes();
        progress.setIndeterminate(total <= 0);
        if (total > 0) {
            long done = Math.max(0, Math.min(value.completedBytes(), total));
            progress.setProgress((int) (1000.0 * done / total));
            detail.setText(String.format(Locale.ROOT, "%.1f / %.1f MiB · %d / %d assets",
                done / 1048576.0, total / 1048576.0, value.completedAssets(), value.totalAssets()));
        }
    }

    private void setWorking(boolean active) {
        check.setEnabled(!active && manifest != null);
        cancel.setEnabled(active);
        cancel.setVisibility(active ? View.VISIBLE : View.GONE);
        progress.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    @Override
    public void close() {
        closed = true;
        if (operation != null) operation.cancel();
        worker.shutdownNow();
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(0xffeff5fa);
        return view;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void add(View view, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        layout.addView(view, params);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
