// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bgtrigger;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.util.List;

/**
 * The bgstart leg's trusted-command endpoint: `content call` methods that drive
 * the target's restored media session through the FRAMEWORK controller path —
 * `MediaSessionManager.getActiveSessions` hands back MediaController objects
 * that relay through MediaSessionRecord inside system_server. No bindService
 * ever reaches the target's ServiceRecord from this package, so this uid's
 * notification-listener elevation can never stamp a granting allow record on
 * the target — the r8/r11 evidence showed only direct bindings propagate.
 *
 * Methods (shell `content call --uri content://...provider --method X`):
 *   status  — "trusted=1 sessions=N target=0|1": membership + the restored
 *             session's visibility to this controller. trusted=0 means the
 *             listener is not enabled (getActiveSessions throws).
 *   prepare — TransportControls.prepare(): surfaces the durable restored
 *             queue as a PAUSED PlaybackState so SystemUI can render the
 *             media card (the untrusted bgactor's prepare is refused by the
 *             session — "Controller isn't allowed to call command= 2").
 *   play    — TransportControls.play(): the external trusted play that must
 *             produce the genuine FSSNAE denial on a demoted target.
 */
public final class BgTriggerProvider extends ContentProvider {
    private static final String TAG = "BgTrigger";
    static final String TARGET_PACKAGE = "ch.lkmc.neutrodyne";

    private MediaSessionManager sessions;

    @Override
    public boolean onCreate() {
        sessions =
            (MediaSessionManager)
                getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        try {
            switch (method) {
                case "status":
                    status(out);
                    break;
                case "prepare":
                    command(out, true);
                    break;
                case "play":
                    command(out, false);
                    break;
                default:
                    out.putString("result", "error=unknown-method");
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "BGTRIGGER-FAIL " + method + " " + e);
            out.putString(
                "result",
                "error=" + e.getClass().getSimpleName() + ":" + e.getMessage());
        }
        return out;
    }

    private List<MediaController> active() {
        return sessions.getActiveSessions(
            new ComponentName(getContext(), BgTriggerListenerService.class));
    }

    private static MediaController target(List<MediaController> controllers) {
        for (MediaController controller : controllers) {
            if (TARGET_PACKAGE.equals(controller.getPackageName())) {
                return controller;
            }
        }
        return null;
    }

    private void status(Bundle out) {
        List<MediaController> controllers;
        try {
            controllers = active();
        } catch (SecurityException e) {
            out.putString("result", "trusted=0");
            return;
        }
        out.putString(
            "result",
            "trusted=1 sessions=" + controllers.size()
                + " target=" + (target(controllers) != null ? 1 : 0));
    }

    private void command(Bundle out, boolean prepare) {
        List<MediaController> controllers;
        try {
            controllers = active();
        } catch (SecurityException e) {
            Log.e(TAG, "BGTRIGGER-FAIL command while untrusted");
            out.putString("result", "trusted=0");
            return;
        }
        MediaController controller = target(controllers);
        if (controller == null) {
            Log.e(TAG, "BGTRIGGER-FAIL no session for " + TARGET_PACKAGE);
            out.putString("result", "error=no-target-session sessions=" + controllers.size());
            return;
        }
        if (prepare) {
            controller.getTransportControls().prepare();
            Log.i(TAG, "BGTRIGGER-CMD prepare issued");
            out.putString("result", "prepared=1");
        } else {
            controller.getTransportControls().play();
            Log.i(TAG, "BGTRIGGER-CMD play issued");
            out.putString("result", "played=1");
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
