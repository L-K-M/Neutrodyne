// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bgactor;

import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/**
 * The bgstart leg's inert external actor: a plain background service — never a
 * foreground component — that BINDS the target's exported MediaLibraryService
 * like a third-party media controller (the in-app case reached the same state
 * through MediaController.connect()). It only binds: the target's session
 * grants no player commands to untrusted controllers ("Controller isn't
 * allowed to call command= 2"), so commanding the session is the trusted
 * `:tools:bgtrigger` package's job, never this one's.
 *
 * The FGS-start capability evaluation stamps the CALLER's uid state at bind
 * time, so the bind waits until this process has demoted past TOP
 * (IMPORTANCE_SERVICE or worse — the throwaway activity that started it has
 * finished): a binding made while foreground would leave a granting record —
 * exactly the capability a real denial must not carry.
 *
 * The activity bootstrap leaves a second grant: the activity-starter FGS
 * exemption (REASON_ACTIVITY_STARTER — a ~10 s grace from the last activity
 * launch/finish in the caller uid, the same window bg-activity-start uses).
 * A bind inside that window stamps code:ACTIVITY_STARTER on the binding's
 * allow record, which the guarded-stage probe correctly reads as a granting
 * capability. The record is sticky, so the bind must also wait out the
 * grace before it is created.
 */
public final class BgActorService extends Service {
    private static final String TAG = "BgActor";
    static final String TARGET_PACKAGE = "ch.lkmc.neutrodyne";
    static final String TARGET_SERVICE =
        "ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService";
    static final String BIND_ACTION = "androidx.media3.session.MediaLibraryService";

    private static final long POLL_MS = 500;
    // > the ~10 s activity-starter grace, counted from service start (the
    // bootstrap activity launches and finishes ~0.3 s before this service).
    private static final long MIN_BIND_AGE_MS = 15_000;
    // The demotion gate itself can be slow — r8 api36 reached IMPORTANCE_SERVICE
    // only near the 60 s mark — so the deadline must out-wait the gate.
    private static final long BIND_DEADLINE_MS = 90_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ServiceConnection connection =
        new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) { }

            @Override
            public void onServiceDisconnected(ComponentName name) { }
        };
    private final ServiceConnection selfConnection =
        new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) { }

            @Override
            public void onServiceDisconnected(ComponentName name) { }
        };
    private final IBinder selfBinder = new android.os.Binder();

    private boolean bound;
    private boolean selfBound;
    private long startedAt;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startedAt = SystemClock.elapsedRealtime();
        maybeBind();
        return START_NOT_STICKY;
    }

    private void maybeBind() {
        if (bound) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - startedAt;
        if (elapsed > BIND_DEADLINE_MS) {
            failBind("bind deadline elapsed", ownImportance(), elapsed);
            return;
        }
        int importance = ownImportance();
        if (importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
            || elapsed < MIN_BIND_AGE_MS) {
            handler.postDelayed(this::maybeBind, POLL_MS);
            return;
        }
        boolean accepted;
        try {
            // The application context owns the binding, so the ServiceRecord's
            // client link survives this service's own teardown (a started
            // background service is idle-stopped at ~60 s while the leg still
            // needs the binding to exist).
            accepted =
                getApplicationContext()
                    .bindService(
                        new Intent(BIND_ACTION)
                            .setComponent(new ComponentName(TARGET_PACKAGE, TARGET_SERVICE)),
                        connection,
                        BIND_AUTO_CREATE);
        } catch (RuntimeException e) {
            failBind("bindService threw " + e, importance, elapsed);
            return;
        }
        if (!accepted) {
            failBind("bindService returned false", importance, elapsed);
            return;
        }
        bound = true;
        // A started, unbound background service is explicitly stopped when its
        // app has been idle ~60 s ("Stopping service due to app idle"), which
        // would drop the target binding mid-leg. A service with a client is
        // never idle — bind this service to itself so the leg's window stays
        // open. A self-bind keeps the uid at its own demoted importance and
        // stamps nothing on the target's allow records.
        selfBound =
            bindService(
                new Intent(this, BgActorService.class),
                selfConnection,
                BIND_AUTO_CREATE);
        if (!selfBound) {
            Log.e(TAG, "BGACTOR-FAIL self-bind returned false");
        }
    }

    /**
     * Loud, greppable terminal failure carrying the caller state at bind time:
     * a false return leaves no dumpsys record for the driver to find, so the
     * cause must be unmistakable in logcat. `targetPackageVisible` directly
     * reports the Android 11+ visibility decision; `servicesMatchingAction`
     * reports what the same filter lets a queryIntentServices see.
     */
    private void failBind(String reason, int importance, long elapsed) {
        boolean targetVisible;
        try {
            getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            targetVisible = true;
        } catch (PackageManager.NameNotFoundException e) {
            targetVisible = false;
        }
        int matching =
            getPackageManager()
                .queryIntentServices(new Intent(BIND_ACTION), 0)
                .size();
        Log.e(
            TAG,
            "BGACTOR-FAIL " + reason
                + " uid=" + Process.myUid()
                + " importance=" + importance
                + " elapsedMs=" + elapsed
                + " targetPackageVisible=" + targetVisible
                + " servicesMatchingAction=" + matching);
        stopSelf();
    }

    private int ownImportance() {
        ActivityManager activityManager =
            (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        int pid = Process.myPid();
        for (ActivityManager.RunningAppProcessInfo info
             : activityManager.getRunningAppProcesses()) {
            if (info.pid == pid) {
                return info.importance;
            }
        }
        return ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (bound) {
            bound = false;
            getApplicationContext().unbindService(connection);
        }
        if (selfBound) {
            selfBound = false;
            unbindService(selfConnection);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return selfBinder;
    }
}
