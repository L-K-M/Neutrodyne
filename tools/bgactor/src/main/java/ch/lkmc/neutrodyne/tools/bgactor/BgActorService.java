// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bgactor;

import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

/**
 * The bgstart leg's inert external actor: a plain background service — never an
 * activity, never a foreground component — that BINDS the target's exported
 * MediaLibraryService like a third-party media controller (the in-app case reached
 * the same state through MediaController.connect()).
 *
 * The FGS-start capability evaluation stamps the CALLER's uid state at bind time,
 * so the bind waits until this process has demoted below TOP
 * (IMPORTANCE_SERVICE or worse): a binding made while foreground would leave a
 * granting record — exactly the capability a real denial must not carry.
 */
public final class BgActorService extends Service {
    static final String TARGET_PACKAGE = "ch.lkmc.neutrodyne";
    static final String TARGET_SERVICE =
        "ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService";
    static final String BIND_ACTION = "androidx.media3.session.MediaLibraryService";

    private static final long POLL_MS = 500;
    private static final long BIND_DEADLINE_MS = 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ServiceConnection connection =
        new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) { }

            @Override
            public void onServiceDisconnected(ComponentName name) { }
        };

    private boolean bound;
    private long startedAt;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startedAt = SystemClock.elapsedRealtime();
        maybeBind();
        return START_NOT_STICKY;
    }

    private void maybeBind() {
        if (bound
            || SystemClock.elapsedRealtime() - startedAt > BIND_DEADLINE_MS) {
            return;
        }
        if (ownImportance() < ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) {
            handler.postDelayed(this::maybeBind, POLL_MS);
            return;
        }
        bound =
            bindService(
                new Intent(BIND_ACTION)
                    .setComponent(new ComponentName(TARGET_PACKAGE, TARGET_SERVICE)),
                connection,
                BIND_AUTO_CREATE);
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
            unbindService(connection);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
