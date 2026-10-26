// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bgtrigger;

import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * The membership that makes this uid trusted for media control: while the
 * component sits in `enabled_notification_listeners`,
 * `MediaSessionManager.isTrustedForMediaControl` accepts this package — the
 * same surface a real companion controller relies on. The service itself is
 * inert; notification events are never read.
 */
public final class BgTriggerListenerService extends NotificationListenerService {
    private static final String TAG = "BgTrigger";

    @Override
    public void onListenerConnected() {
        Log.i(TAG, "BGTRIGGER-LSN connected");
    }

    @Override
    public void onListenerDisconnected() {
        Log.i(TAG, "BGTRIGGER-LSN disconnected");
    }
}
