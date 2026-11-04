// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The device sync switches as a synchronous cache (10 Settings capture). `SyncServerAddress` is
 * suspend (DataStore); the engine's `ChangeBuilder` reads [sharePasswords] at push time, so the
 * controller refreshes this cache after every settings write and at start-up.
 */
@Inject
@SingleIn(AppScope::class)
class SyncFlags {
    @Volatile
    var sharePasswords: Boolean = false

    @Volatile
    var syncPlaybackSettings: Boolean = true
}
