// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import android.app.Application
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okio.Path

/** `content://{applicationId}.artwork/{key}?v={version}` — the `ArtworkProvider` authority (08). */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class AndroidArtworkContentUris(
    application: Application,
) : ArtworkContentUris {
    private val authority = "${application.packageName}.artwork"

    override fun of(
        key: String,
        version: Int,
        path: Path?,
    ): String = "content://$authority/$key?v=$version"
}
