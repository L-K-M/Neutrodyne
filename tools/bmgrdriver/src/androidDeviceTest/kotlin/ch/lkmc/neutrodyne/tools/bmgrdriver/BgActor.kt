// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import android.app.Activity
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder

/**
 * The bgstart leg's inert external actor — a plain, non-instrumented process
 * (`<pkg>:bgactor`) that BINDS the target's MediaLibraryService exactly like a
 * third-party media controller (the original in-app case reached the same state via
 * `MediaController.connect()`). It never starts the service and carries no FGS
 * capability, so the binding neither auto-promotes the service nor propagates
 * BFSL (`canBindingClientStartFgsLocked` only honours capable clients). While the
 * target sits bound and demoted, the SystemUI media card's Play button is what
 * asks it for a background foreground start.
 */
internal class BgActorActivity : Activity() {
    /** A translucent, no-history bootstrap: spawn the holder service and leave. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, BgActorService::class.java))
        finishAndRemoveTask()
    }
}

/** Holds the cross-package bind for the duration of the bgstart leg. */
internal class BgActorService : Service() {
    private var bound = false

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) = Unit

            override fun onServiceDisconnected(name: ComponentName) {
                bound = false
            }
        }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!bound) {
            bound =
                bindService(
                    Intent(BgStartSurface.BIND_ACTION)
                        .setComponent(
                            ComponentName(
                                BmgrArming.TARGET_PACKAGE,
                                BgStartSurface.SERVICE_CLASS,
                            ),
                        ),
                    connection,
                    BIND_AUTO_CREATE,
                )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (bound) {
            bound = false
            unbindService(connection)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
