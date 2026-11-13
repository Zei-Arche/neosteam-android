package org.neosteam.revival;

import android.app.Activity;
import android.view.View;

final class HandshakeOverlay {
    private HandshakeOverlay() {}

    static View wrap(Activity activity, View content) {
        return content;
    }
}
