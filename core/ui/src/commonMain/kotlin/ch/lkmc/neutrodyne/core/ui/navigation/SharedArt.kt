// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneMotion
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey

/** Destination identity, separate from Coil's versioned byte identity. */
public object SharedKeys {
    public fun cover(
        tab: TopLevelKey,
        podcastId: Long,
    ): String = "${tab.sharedId}:cover:$podcastId"

    public fun episodeArt(
        tab: TopLevelKey,
        episodeId: Long,
    ): String = "${tab.sharedId}:episode-art:$episodeId"
}

private val TopLevelKey.sharedId: String
    get() =
        when (this) {
            FeedsKey -> "feeds"
            LibraryKey -> "library"
            UpNextKey -> "upnext"
            DownloadsKey -> "downloads"
            DiscoverKey -> "discover"
        }

/** Null outside the navigation host, in coexisting panes, or under reduced motion. */
public val LocalSharedTransitionScope: ProvidableCompositionLocal<SharedTransitionScope?> = compositionLocalOf { null }

/**
 * Uses the entry's real Nav3 visibility clock. Never manufacture another visibility scope:
 * doing so breaks predictive cancellation and expanded movable-entry ownership.
 */
@Composable
public fun Modifier.sharedArt(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    if (LocalReducedMotion.current || !LocalSharedArtEnabled.current) return this
    val entry = LocalNavAnimatedContentScope.current
    val bounds = NeutrodyneMotion.navigation<androidx.compose.ui.geometry.Rect>()
    return with(shared) {
        // THUMB and HERO are distinct layers. Keep both drawable through a cancelled handoff;
        // sharedElement's visibility-only layer gate drops one Android frame at that boundary.
        sharedBounds(
            rememberSharedContentState(key),
            animatedVisibilityScope = entry,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            boundsTransform = { _, _ -> bounds },
        )
    }
}

/** Prefetched pager neighbours can contain the same episode; only the settled page may match. */
public val LocalSharedArtEnabled: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }
