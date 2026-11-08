// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.YouTubeEngineDiagnostics
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * The default [YouTubeEngineDiagnostics] (09's YOUTUBE card + 11's engine-child row):
 * `:youtube:impl` binds the real provider once the engine updater lands; until then this
 * answers honestly — nothing installed, no child.
 */
@ContributesBinding(AppScope::class)
internal class UnavailableEngine
    @Inject
    constructor() : YouTubeEngineDiagnostics {
        override suspend fun installedVersion(): String? = null

        override suspend fun health(): String = "unavailable"

        override suspend fun childStatus(): String? = null
    }
