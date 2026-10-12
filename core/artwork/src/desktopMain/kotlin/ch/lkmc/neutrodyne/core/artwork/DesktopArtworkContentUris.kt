// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okio.Path

/** The `file:` URI of the pinned file (MPRIS `mpris:artUrl`, 08); empty when nothing is pinned. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopArtworkContentUris : ArtworkContentUris {
    override fun of(
        key: String,
        version: Int,
        path: Path?,
    ): String = path?.let { "file://$it" } ?: ""
}
