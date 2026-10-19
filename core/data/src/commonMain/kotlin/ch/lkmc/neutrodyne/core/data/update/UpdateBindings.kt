// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import kotlin.random.Random

/** No startup contribution or shell accessor: W4-W6 opt into these lazy app-scoped bindings. */
@ContributesTo(AppScope::class)
@BindingContainer
internal object UpdateBindings {
    @Provides fun parser(impl: UpdateManifestParser): UpdateParser = impl

    @Provides fun source(impl: GitHubUpdateSource): UpdateSource = impl

    @Provides fun store(impl: UpdateCheckStore): UpdateStore = impl

    @Provides fun selector(impl: DesktopAssetSelector): UpdateAssetSelector = impl

    @Provides fun timeline(): VerificationTimeline = VerificationTimeline()

    @Provides fun jitter(): UpdateJitter = UpdateJitter { Random.nextLong(JITTER_LIMIT_EXCLUSIVE_MS) }

    private const val JITTER_LIMIT_EXCLUSIVE_MS = 3_600_001L
}
