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
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.database.DesktopDatabaseFactory
import ch.lkmc.neutrodyne.core.database.StrictMigrations
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.desktop.platform.DesktopBuildInfo
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.platform.DesktopPlatformFacts
import ch.lkmc.neutrodyne.sync.protocol.OrderKey
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The desktop counterparts of `:app`'s `CoreBindings` (01 Components and scopes): dispatchers, the
 * process scope, the clock and the database plumbing. `BuildInfo`, `PlatformInfo` and the job
 * runner arrive with the M0b shell.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object DesktopCoreBindings {
    private const val TAG = "AppScope"

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
    ): CoroutineScope {
        val handler =
            CoroutineExceptionHandler {
                _,
                throwable,
                ->
                Log.e(TAG, throwable) { "uncaught failure in the application scope" }
            }
        return CoroutineScope(SupervisorJob() + dispatcher + handler)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun clock(): Clock = DesktopClock

    @Provides
    @SingleIn(AppScope::class)
    fun databaseFactory(dirs: AppDirs): DatabaseFactory = DesktopDatabaseFactory(dirs)

    /** Development runs (`InstallKind.DEV`) rethrow migration failures; packaged images quarantine. */
    @Provides
    @StrictMigrations
    fun strictMigrations(): Boolean = DesktopBuildInfo.installKind == InstallKind.DEV

    @Provides
    @SingleIn(AppScope::class)
    fun storagePaths(dirs: AppDirs): StoragePaths = StoragePaths(dirs)

    @Provides
    @SingleIn(AppScope::class)
    fun platformInfo(): PlatformInfo = DesktopPlatformFacts

    @Provides
    @SingleIn(AppScope::class)
    fun buildInfo(): BuildInfo = DesktopPlatformFacts.buildInfo()

    /**
     * Interim no-op power port: `:desktop:system`'s `OsPowerMonitor` lands with MD2 (11). The job
     * runner's clock-drift detector still catches every wake it misses.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun powerMonitor(): PowerMonitor =
        object : PowerMonitor {
            override val events: kotlinx.coroutines.flow.Flow<PowerEvent> = kotlinx.coroutines.flow.emptyFlow()
        }

    /** The subscribe transaction's fractional-index port (10 Ordered lists). */
    @Provides
    fun orderKeys(): OrderKeys = OrderKeys { last -> OrderKey.after(last) }
}
