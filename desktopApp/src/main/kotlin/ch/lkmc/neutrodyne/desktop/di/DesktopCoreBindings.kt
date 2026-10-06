// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.platform.DesktopPlatformInfo
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Platform basics of the desktop process (01 Components and scopes, the `DesktopCoreBindings`
 * row): the two dispatchers, the `@ApplicationScope` scope, `DesktopClock`, `PlatformInfo` and
 * `StoragePaths` built from the factory's [AppDirs].
 *
 * 01 defines no `Main` dispatcher qualifier (`NeutrodyneDispatchers` is `IO`/`Default` only) —
 * Compose code uses `Dispatchers.Main` from `kotlinx-coroutines-swing` directly, so no `Main`
 * binding exists here.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object DesktopCoreBindings {
    private const val TAG = "AppScope"

    /** Debug builds crash fast on an uncaught scope failure (01 Errors); `halt` keeps the exit unclean. */
    private const val DEBUG_CRASH_EXIT = 1

    @Provides
    @Dispatcher(NeutrodyneDispatchers.IO)
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(NeutrodyneDispatchers.Default)
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @SingleIn(AppScope::class)
    @ApplicationScope
    fun applicationScope(
        @Dispatcher(NeutrodyneDispatchers.Default) dispatcher: CoroutineDispatcher,
        buildInfo: BuildInfo,
        crashReporter: DesktopCrashReporter,
    ): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, throwable) { "uncaught failure in the application scope" }
            if (buildInfo.debug) {
                // Debug builds crash fast (01 Errors); the file records why, halt keeps the exit unclean.
                crashReporter.recordUnhandled(Thread.currentThread(), throwable)
                Runtime.getRuntime().halt(DEBUG_CRASH_EXIT)
            }
        }
        return CoroutineScope(SupervisorJob() + dispatcher + handler)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun clock(): Clock = DesktopClock

    @Provides
    @SingleIn(AppScope::class)
    fun platformInfo(): PlatformInfo = DesktopPlatformInfo()

    @Provides
    @SingleIn(AppScope::class)
    fun storagePaths(dirs: AppDirs): StoragePaths = StoragePaths(dirs)

    /**
     * Interim: no suspend/resume notices exist until `:desktop:system`'s `OsPowerMonitor` (MD2)
     * contributes itself to [AppScope]; `DesktopNetworkMonitor` needs a monitor now. MD2's
     * contribution makes this duplicate and Metro fails the build — delete this then.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun powerMonitor(): PowerMonitor = NoEventsPowerMonitor
}

private object NoEventsPowerMonitor : PowerMonitor {
    override val events: Flow<PowerEvent> = flowOf()
}
