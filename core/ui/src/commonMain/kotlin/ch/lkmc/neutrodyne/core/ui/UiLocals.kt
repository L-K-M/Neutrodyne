// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.model.settings.YouTubeRowArt
import kotlin.time.TimeSource

/**
 * The wall clock UI labels read (08: rows and headers show relative dates). The shells provide the
 * bound device clock; tests provide `TestClock`. The fallback keeps previews/tests compile-clean.
 */
public val LocalUiClock: androidx.compose.runtime.ProvidableCompositionLocal<Clock> =
    staticCompositionLocalOf { UiSystemClock }

private object UiSystemClock : Clock {
    // 01's Clock contract needs real monotonic milliseconds: one process-wide origin, not a
    // mark-and-read in the same expression (that always yields 0).
    private val origin = TimeSource.Monotonic.markNow()

    override fun now(): Long =
        kotlin.time.Clock.System
            .now()
            .toEpochMilliseconds()

    override fun elapsedRealtime(): Long =
        origin
            .elapsedNow()
            .inWholeMilliseconds
}

/** Which platform the UI runs on — picks per-platform copy (07's storage wording, 11's labels). */
public val LocalPlatformKind: androidx.compose.runtime.ProvidableCompositionLocal<PlatformKind> =
    staticCompositionLocalOf { PlatformKind.ANDROID }

/** Appearance › YouTube row art; shells collect the persisted choice once for all rows. */
public val LocalYouTubeRowArt: androidx.compose.runtime.ProvidableCompositionLocal<YouTubeRowArt> =
    staticCompositionLocalOf { YouTubeRowArt.VIDEO_THUMBNAIL }
