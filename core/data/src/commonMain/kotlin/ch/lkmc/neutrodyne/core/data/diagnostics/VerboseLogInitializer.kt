// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.RingBufferLogSink
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.DiagnosticsSettingKeys
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * "Detailed log for 24 hours" (09 Diagnostics): while `diagnostics.verbose_log_until` is in
 * the future the ring sink keeps DEBUG, otherwise INFO. A 60 s ticker flips the level back
 * on expiry — the setting write is the event, not a stored observer.
 */
@ContributesIntoSet(AppScope::class)
internal class VerboseLogInitializer
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val sink: RingBufferLogSink,
        private val clock: Clock,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) : AppInitializer {
        override val order: Int = ORDER

        override suspend fun run() {
            scope.launch {
                settings.observe(DiagnosticsSettingKeys.VERBOSE_LOG_UNTIL).collectLatest { until ->
                    while (true) {
                        val verbose = clock.now() < until
                        sink.minLevel = if (verbose) LogLevel.DEBUG else LogLevel.INFO
                        if (!verbose) break
                        delay(TICK_MS)
                    }
                }
            }
        }

        private companion object {
            const val ORDER = 300
            const val TICK_MS = 60_000L
        }
    }
