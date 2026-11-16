package org.librehora;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot, connection changes and the hourly check: sync if it is due. */
public class NetReceiver extends BroadcastReceiver {
    static final String ACTION_CHECK = "org.librehora.CHECK";

    @Override
    public void onReceive(Context context, Intent intent) {
        SyncService.maybeStart(context, false);
    }
}
