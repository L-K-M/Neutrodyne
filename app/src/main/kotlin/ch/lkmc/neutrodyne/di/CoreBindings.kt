// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import android.os.Handler
import android.os.Looper
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.platform.AndroidBuildInfo
import ch.lkmc.neutrodyne.platform.AndroidPlatformInfo
import ch.lkmc.neutrodyne.platform.DeviceClock
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

/** Platform basics of the Android main process (01 Components and scopes, the `CoreBindings` row). */
@ContributesTo(AppScope::class)
@BindingContainer
object CoreBindings {
    private const val TAG = "AppScope"

    @Provides
    @Dispatcher(NeutrodyneDispatchers.IO)
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(NeutrodyneDispatchers.Default)
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * The process-wide scope. An uncaught failure is logged; debug builds rethrow it on the main thread so
     * bugs crash fast (01 Errors, Debug build type).
     */
    @Provides
    @SingleIn(AppScope::class)
    @ApplicationScope
    fun applicationScope(
        @Dispatcher(NeutrodyneDispatchers.Default) dispatcher: CoroutineDispatcher,
        buildInfo: BuildInfo,
    ): CoroutineScope {
        val handler =
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, throwable) { "uncaught failure in the application scope" }
                if (buildInfo.debug) Handler(Looper.getMainLooper()).post { throw throwable }
            }
        return CoroutineScope(SupervisorJob() + dispatcher + handler)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun clock(): Clock = DeviceClock

    @Provides
    @SingleIn(AppScope::class)
    fun buildInfo(): BuildInfo = AndroidBuildInfo.create()

    @Provides
    @SingleIn(AppScope::class)
    fun platformInfo(): PlatformInfo = AndroidPlatformInfo()

    @Provides
    @SingleIn(AppScope::class)
    fun storagePaths(application: Application): StoragePaths = StoragePaths(application)

    /** The subscribe transaction's fractional-index port (10 Ordered lists). */
    @Provides
    fun orderKeys(): OrderKeys = OrderKeys { last -> OrderKey.after(last) }
}
