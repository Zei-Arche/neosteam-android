package org.neosteam.revival.diagnostics;

import android.app.Application;

public final class DiagnosticsApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Diagnostics.start(this);
    }
}
