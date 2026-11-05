// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.BrandMark
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow

internal data class MatrixRow(
    val name: String,
    val row: EpisodeRow,
    val live: RowLive? = null,
    val caps: RowCaps = RowCaps.FULL,
)

internal val matrixRows: List<MatrixRow> =
    buildList {
        val row = testEpisodeRow(1, title = "A long episode title that wraps at large text sizes")
        add(MatrixRow("unplayed", row))
        add(MatrixRow("new", row.copy(isNew = true)))
        add(MatrixRow("in-progress", row.copy(startedAt = 1), RowLive(positionMs = 120_000)))
        add(MatrixRow("playing", row, RowLive(isNowPlaying = true, isPlaying = true)))
        add(MatrixRow("paused", row, RowLive(isNowPlaying = true)))
        add(MatrixRow("played", row.copy(playedAt = 1)))
        add(MatrixRow("offline", row, caps = RowCaps.FULL.copy(offline = true)))
        add(MatrixRow("video", row.copy(isVideo = true)))
        add(MatrixRow("youtube", row.copy(sourceType = SourceType.YOUTUBE_CHANNEL, externalMediaId = "abcdefghijk")))
        add(
            MatrixRow(
                "external",
                row.copy(sourceType = SourceType.YOUTUBE_CHANNEL, externalMediaId = "abcdefghijk"),
                caps = RowCaps.FULL.copy(inAppPlayback = false, downloads = false),
            ),
        )
        add(MatrixRow("unavailable", row.copy(availability = Availability.UNAVAILABLE)))
        DownloadState.entries.forEach { state ->
            add(
                MatrixRow(
                    "download-${state.name.lowercase().replace('_', '-')}",
                    row,
                    RowLive(
                        downloadState = state,
                        downloadedBytes = 40,
                        totalBytes = 100,
                        lastError = DownloadError.NETWORK_IO.takeIf { state == DownloadState.FAILED },
                    ),
                ),
            )
        }
        WaitReason.entries.filter { it != WaitReason.NONE }.forEach { reason ->
            add(
                MatrixRow(
                    "wait-${reason.name.lowercase().replace('_', '-')}",
                    row,
                    RowLive(
                        downloadState = DownloadState.QUEUED,
                        waitReason = reason,
                        nextAttemptAt = TestClock.DEFAULT_NOW + 60_000,
                    ),
                ),
            )
        }
    }

internal data class MatrixAppearance(
    val theme: String,
    val font: Float,
    val direction: LayoutDirection = LayoutDirection.Ltr,
    val contrast: Double = 0.0,
) {
    val name: String get() =
        "$theme-f${(font * 100).toInt()}-" +
            "${if (direction == LayoutDirection.Rtl) "rtl" else "ltr"}-c${(contrast * 100).toInt()}"
}

internal enum class MatrixTarget { ANDROID_PR, ANDROID_FULL, DESKTOP }

internal val matrixRowPages = matrixRows.chunked(2)
internal val matrixHighContrast =
    listOf(
        MatrixAppearance("light", 1f, contrast = 1.0),
        MatrixAppearance("dark", 1f, contrast = 1.0),
    )

/** Android's ar-XB method captures the same FULL pairs in RTL. Pages preserve the file budget. */
internal fun matrixAppearances(target: MatrixTarget): List<MatrixAppearance> =
    when (target) {
        MatrixTarget.DESKTOP -> {
            listOf(
                MatrixAppearance("light", 1f),
                MatrixAppearance("dark", 1f),
                MatrixAppearance("light", 1.5f),
                MatrixAppearance("dark", 1.5f),
                MatrixAppearance("dark", 1.5f, LayoutDirection.Rtl),
            )
        }

        MatrixTarget.ANDROID_FULL -> {
            listOf("light", "dark", "black").flatMap { theme ->
                listOf(1f, 1.5f, 2f).map { font -> MatrixAppearance(theme, font) }
            }
        }

        MatrixTarget.ANDROID_PR -> {
            listOf(
                MatrixAppearance("light", 1f),
                MatrixAppearance("dark", 1f),
            )
        }
    }

@Composable
internal fun MatrixSurface(
    appearance: MatrixAppearance,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    NeutrodyneTheme(
        AppearancePrefs(dynamicColor = false, pureBlack = appearance.theme == "black"),
        SystemUiState(dark = appearance.theme != "light", contrast = appearance.contrast, reducedMotion = true),
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, appearance.font),
            LocalLayoutDirection provides appearance.direction,
            LocalUiClock provides TestClock(),
        ) {
            Surface(
                Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column { content() }
            }
        }
    }
}

@Composable
internal fun MatrixEpisodePage(rows: List<MatrixRow>) {
    Column {
        rows.forEach { row ->
            Text(row.name, style = MaterialTheme.typography.labelSmall)
            MatrixEpisodeRow(row)
        }
    }
}

@Composable
internal fun MatrixEpisodeRow(state: MatrixRow) {
    EpisodeRow(state.row, state.live, EpisodeRowStyle.FEED, state.caps, true, null, {}, Modifier.fillMaxWidth())
}

@Composable
internal fun MatrixBrand() {
    Column {
        BrandMark(Modifier.size(96.dp).padding(16.dp))
        Row {
            listOf(
                NdIcons.DynamicFeed,
                NdIcons.GridView,
                NdIcons.QueueMusic,
                NdIcons.Download,
                NdIcons.Explore,
                NdIcons.Settings,
            ).forEach {
                Icon(it, null)
            }
        }
        Row {
            listOf(
                NdIcons.DynamicFeedFilled,
                NdIcons.GridViewFilled,
                NdIcons.QueueMusicFilled,
                NdIcons.DownloadFilled,
                NdIcons.ExploreFilled,
            ).forEach {
                Icon(it, null)
            }
        }
        Text("Neutrodyne", color = MaterialTheme.colorScheme.primary)
        Text(
            "Surface and container contrast",
            Modifier.background(MaterialTheme.colorScheme.primaryContainer),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

internal enum class MatrixSupplement { BRAND, MONOGRAM, COVER_TILE }

@Composable
internal fun MatrixSupplementContent(subject: MatrixSupplement) {
    when (subject) {
        MatrixSupplement.BRAND -> {
            MatrixBrand()
        }

        MatrixSupplement.MONOGRAM -> {
            CoverArt(null, rememberMonogram("Ärzte Talk"), Modifier.size(152.dp), title = "Ärzte Talk")
        }

        MatrixSupplement.COVER_TILE -> {
            CoverTile(
                ch.lkmc.neutrodyne.core.testing
                    .testLibraryTile(1, displayTitle = "A long podcast title"),
                true,
                null,
                emptyList(),
                {},
                {},
                Modifier.size(152.dp),
            )
        }
    }
}
