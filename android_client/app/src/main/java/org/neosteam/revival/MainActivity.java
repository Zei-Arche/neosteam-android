package org.neosteam.revival;

import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.FrameLayout;

import org.neosteam.revival.diagnostics.Diagnostics;
import org.neosteam.revival.gameplay.GameplayScreen;
import org.neosteam.revival.gameplay.network.AndroidNetworkRecovery;
import org.neosteam.revival.ota.ContentUpdater;
import org.neosteam.revival.ota.ContentUpdateScreen;
import org.neosteam.revival.update.VersionGateScreen;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    static {
        System.loadLibrary("neosteam");
    }

    private static native String inspectNativeCore(AssetManager assets, int glb, int ktx2, int downloadedCount);
    private GameplayScreen.Session session;
    private GameplayScreen gameplay;
    private AndroidNetworkRecovery network;
    private ContentUpdater content;
    private ContentUpdateScreen contentScreen;
    private View gameplayView;
    private VersionGateScreen versionGate;
    private final ExecutorService streams = Executors.newSingleThreadExecutor();
    private Future<?> startup;
    private String startupStatus;
    private int inspection;
    private boolean foreground;
    private boolean showingContent;
    private boolean checkingEntry;
    private boolean showContentAfterCheck;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        Object retained = getLastNonConfigurationInstance();
        session = retained instanceof GameplayScreen.Session existing ? existing : new GameplayScreen.Session();
        content = new ContentUpdater(new File(getNoBackupFilesDir(), "ota-content"), BuildConfig.DEBUG);
        gameplay = new GameplayScreen(this, session, streams, content, () -> showContent(false));
        gameplayView = HandshakeOverlay.wrap(this, gameplay.view());
        contentScreen = new ContentUpdateScreen(this, content, BuildConfig.OTA_MANIFEST_URL,
            () -> {
                Diagnostics.otaInstalled();
                refreshContent();
            }, () -> checkEntry(false));
        FrameLayout root = new FrameLayout(this);
        root.addView(gameplayView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(contentScreen.view(), new FrameLayout.LayoutParams(-1, -1));
        contentScreen.view().setVisibility(View.GONE);
        if (!BuildConfig.RELEASE_MANIFEST_URL.isEmpty()) {
            versionGate = new VersionGateScreen(this, BuildConfig.RELEASE_MANIFEST_URL, BuildConfig.VERSION_CODE,
                BuildConfig.DEBUG, this::admitEntry);
            root.addView(versionGate.view(), new FrameLayout.LayoutParams(-1, -1));
            versionGate.view().setVisibility(View.GONE);
        }
        setContentView(root);
        boolean startWithContent = savedInstanceState == null ? !BuildConfig.OTA_MANIFEST_URL.isEmpty()
            : savedInstanceState.getBoolean("content-screen");
        checkEntry(startWithContent);
        network = new AndroidNetworkRecovery(this, null, snapshot -> {
            var route = snapshot.route();
            Diagnostics.route(route.kind());
            gameplay.setNetworkRoute("Network: " + route.kind().label()
                + (route.validated() ? " · validated" : "")
                + (route.metered() && route.internet() ? " · metered" : ""));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        gameplay.setForeground(!showingContent && !checkingEntry);
        contentScreen.setForeground(true);
        network.onResume();
        if (startupStatus == null && startup == null) refreshContent();
        Diagnostics.onResume(this);
    }

    private void showContent(boolean autoCheck) {
        showingContent = true;
        gameplay.setForeground(false);
        gameplayView.setVisibility(View.GONE);
        contentScreen.view().setVisibility(View.VISIBLE);
        contentScreen.show(autoCheck);
    }

    private void checkEntry(boolean openContentOnSuccess) {
        showContentAfterCheck = openContentOnSuccess;
        if (versionGate == null) {
            admitEntry();
            return;
        }
        checkingEntry = true;
        showingContent = false;
        contentScreen.hide();
        contentScreen.view().setVisibility(View.GONE);
        gameplayView.setVisibility(View.GONE);
        gameplay.setForeground(false);
        versionGate.view().setVisibility(View.VISIBLE);
        versionGate.check();
    }

    private void admitEntry() {
        checkingEntry = false;
        if (versionGate != null) versionGate.view().setVisibility(View.GONE);
        if (showContentAfterCheck) {
            showContentAfterCheck = false;
            showContent(true);
            return;
        }
        showingContent = false;
        contentScreen.hide();
        contentScreen.view().setVisibility(View.GONE);
        gameplayView.setVisibility(View.VISIBLE);
        gameplay.setForeground(foreground);
    }

    private void refreshContent() {
        startupStatus = null;
        if (startup != null) startup.cancel(true);
        int request = ++inspection;
        AssetManager assets = getAssets();
        startup = streams.submit(() -> {
            String result = inspectContent(assets);
            runOnUiThread(() -> {
                if (request == inspection && !isDestroyed() && !isFinishing()) {
                    startup = null;
                    startupStatus = result;
                    gameplay.setNativeStatus(result);
                }
            });
        });
    }

    private String inspectContent(AssetManager assets) {
        try {
            ContentUpdater.ActiveContent installed = content.activeContent();
            try (ParcelFileDescriptor glb = openFirst(installed, ".glb");
                 ParcelFileDescriptor ktx2 = openFirst(installed, ".ktx2")) {
                return inspectNativeCore(assets, glb == null ? -1 : glb.getFd(),
                    ktx2 == null ? -1 : ktx2.getFd(), installed.installed() ? installed.paths().size() : -1);
            }
        } catch (IOException unavailable) {
            return inspectNativeCore(assets, -1, -1, -1) + "\nDownloaded content unavailable";
        }
    }

    private static ParcelFileDescriptor openFirst(ContentUpdater.ActiveContent installed, String extension)
        throws IOException {
        for (String path : installed.paths()) {
            if (!path.endsWith(extension)) continue;
            File asset = installed.asset(path);
            if (asset != null) return ParcelFileDescriptor.open(asset, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        return null;
    }

    @Override
    protected void onPause() {
        Diagnostics.onPause();
        foreground = false;
        contentScreen.setForeground(false);
        network.onPause();
        gameplay.setForeground(false);
        if (startupStatus == null && startup != null) {
            inspection++;
            startup.cancel(true);
            startup = null;
        }
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && gameplay != null) gameplay.trimResources();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (gameplay != null) gameplay.trimResources();
    }

    @Override
    protected void onDestroy() {
        if (versionGate != null) versionGate.close();
        contentScreen.close();
        network.close();
        streams.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("content-screen", showingContent || (checkingEntry && showContentAfterCheck));
    }

    @Override
    @SuppressWarnings("deprecation")
    public Object onRetainNonConfigurationInstance() {
        return session;
    }
}
