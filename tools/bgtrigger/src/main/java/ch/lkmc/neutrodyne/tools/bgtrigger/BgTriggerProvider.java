// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bgtrigger;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * The bgstart leg's trusted-command endpoint: `content call` methods that drive
 * the target's restored media session through a native Media3 MediaController —
 * exactly the surface a real companion app (Wear, a car head unit) uses.
 *
 * Why a Media3 connect and not the framework MediaSessionRecord relay: the r13
 * evidence showed the relay reaches MediaSessionLegacyStub inside the target,
 * where Media3 re-evaluates isTrustedForMediaControl — and that check resolves
 * the calling package with the TARGET's PackageManager. API 30+ visibility
 * hides this package (it bound nothing), so the controller was judged
 * untrusted and play() was omitted: "Package
 * ch.lkmc.neutrodyne.tools.bgtrigger doesn't exist" + "Calling play() omitted
 * due to COMMAND_PLAY_PAUSE not being available" (r13 api31 logcat). A
 * MediaController connect IS a bindService to the target's exported
 * MediaLibraryService: bound clients are visible to the bound app, so the
 * package resolves and the enabled notification listener then passes the NLS
 * branch of the trust check. The stamped allow record on this bind — a
 * BFGS-state caller — is exactly what the card-stage evidence measures.
 *
 * Methods (shell `content call --uri content://...provider --method X`):
 *   status  — "trusted=1 sessions=N target=0|1": the connect landed AND the
 *             session granted commands (untrusted controllers connect with an
 *             empty command set — "Controller isn't allowed to call command").
 *   prepare — controller.prepare(): surfaces the durable restored queue as a
 *             PAUSED PlaybackState so SystemUI can render the media card.
 *   play    — controller.play(): the external trusted play that must produce
 *             the genuine FSSNAE denial on a demoted target.
 */
public final class BgTriggerProvider extends ContentProvider {
    private static final String TAG = "BgTrigger";
    static final String TARGET_PACKAGE = "ch.lkmc.neutrodyne";
    static final String TARGET_SERVICE =
        "ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService";
    private static final long CONNECT_TIMEOUT_MS = 20_000;
    private static final long CALL_TIMEOUT_MS = 5_000;

    private volatile MediaController controller;
    private volatile boolean connectLanded;

    @Override
    public boolean onCreate() {
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

    private static boolean hasCommands(MediaController c) {
        return c.getAvailableCommands().contains(Player.COMMAND_PLAY_PAUSE);
    }

    /**
     * MediaController methods must run on the thread whose looper built the
     * controller: buildAsync() from a binder thread adopts the main looper, so
     * every controller call is marshalled there. The async connect stays on the
     * calling thread — blocking the main looper while its completion runs there
     * would deadlock.
     */
    private static <T> T onApplicationLooper(Callable<T> action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
        FutureTask<T> task = new FutureTask<>(action);
        new Handler(Looper.getMainLooper()).post(task);
        return task.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Lazily connects; the connect's own bind makes this package visible to
     * the target, after which listener membership grants real commands. A
     * connected controller's command set is stamped once at connect and never
     * renegotiated — a controller that landed before the listener service
     * bound stays untrusted forever (the r14 "the trusted trigger never saw
     * the session" leg), so a commandless controller is released and the next
     * call re-evaluates trust from scratch.
     */
    private MediaController controller() {
        synchronized (this) {
            MediaController c = controller;
            if (c != null && commandsOf(c)) return c;
            if (c != null) {
                release(c);
                controller = null;
            }
            Context context = getContext();
            SessionToken token =
                new SessionToken(
                    context, new ComponentName(TARGET_PACKAGE, TARGET_SERVICE));
            try {
                ListenableFuture<MediaController> future =
                    new MediaController.Builder(context, token).buildAsync();
                c = future.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Log.e(TAG, "BGTRIGGER-FAIL connect " + e);
                connectLanded = false;
                return null;
            }
            connectLanded = true;
            if (!commandsOf(c)) {
                release(c);
                return null;
            }
            controller = c;
            return c;
        }
    }

    private static boolean commandsOf(MediaController c) {
        try {
            return onApplicationLooper(() -> hasCommands(c));
        } catch (Exception e) {
            Log.e(TAG, "BGTRIGGER-FAIL commands " + e);
            return false;
        }
    }

    private static void release(MediaController c) {
        try {
            onApplicationLooper(() -> {
                c.release();
                return null;
            });
        } catch (Exception e) {
            Log.e(TAG, "BGTRIGGER-FAIL release " + e);
        }
    }

    private void status(Bundle out) {
        MediaController c = controller();
        out.putString(
            "result",
            "trusted=" + (c != null ? 1 : 0)
                + " sessions=" + (connectLanded ? 1 : 0)
                + " target=" + (connectLanded ? 1 : 0));
    }

    private void command(Bundle out, boolean prepare) {
        MediaController c = controller();
        if (c == null) {
            Log.e(TAG, "BGTRIGGER-FAIL command while unconnected or untrusted");
            out.putString("result", "trusted=0");
            return;
        }
        try {
            if (prepare) {
                int items = onApplicationLooper(() -> {
                    c.prepare();
                    return c.getMediaItemCount();
                });
                Log.i(TAG, "BGTRIGGER-CMD prepare issued items=" + items);
                out.putString("result", "prepared=1");
            } else {
                onApplicationLooper(() -> {
                    c.play();
                    return null;
                });
                Log.i(TAG, "BGTRIGGER-CMD play issued");
                out.putString("result", "played=1");
            }
        } catch (Exception e) {
            Log.e(TAG, "BGTRIGGER-FAIL command " + e);
            out.putString("result", "error=" + e.getClass().getSimpleName() + ":" + e.getMessage());
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
