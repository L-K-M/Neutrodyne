// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import kotlin.random.Random

/** Shared services stay lazy until the band-200 initializer or an explicit UI request. */
@ContributesTo(AppScope::class)
@BindingContainer
object UpdateBindings {
    @Provides internal fun parser(impl: UpdateManifestParser): UpdateParser = impl

    @Provides internal fun source(impl: GitHubUpdateSource): UpdateSource = impl

    @Provides internal fun store(impl: UpdateCheckStore): UpdateStore = impl

    @Provides internal fun selector(impl: DesktopAssetSelector): UpdateAssetSelector = impl

    @Provides internal fun timeline(): VerificationTimeline = VerificationTimeline()

    @Provides internal fun jitter(): UpdateJitter = UpdateJitter { Random.nextLong(JITTER_LIMIT_EXCLUSIVE_MS) }

    private const val JITTER_LIMIT_EXCLUSIVE_MS = 3_600_001L
}
