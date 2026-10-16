// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.dl_wait_storage_android
import ch.lkmc.neutrodyne.core.ui.resources.dl_wait_storage_desktop
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `EpisodeRowSummary.describe` takes the platform like the visible status line (07's storage
 * wording): a STORAGE wait with `STORAGE_UNAVAILABLE` speaks "download folder" on the desktop,
 * not the hardcoded Android "SD card" the spoken summary previously emitted.
 */
class EpisodeRowSummaryTest {
    private val storageWaitLive =
        RowLive(
            downloadState = DownloadState.QUEUED,
            waitReason = WaitReason.STORAGE,
            lastError = DownloadError.STORAGE_UNAVAILABLE,
        )

    @Test
    fun describeUsesTheDesktopStorageWordingOnDesktop() {
        val joined =
            assertIs<UiText.Joined>(
                EpisodeRowSummary.describe(
                    testEpisodeRow(1),
                    storageWaitLive,
                    TestClock.DEFAULT_NOW,
                    platform = PlatformKind.DESKTOP,
                ),
            )
        assertTrue(
            UiText.Res(Res.string.dl_wait_storage_desktop) in joined.parts,
            "desktop summary must use the download-folder wording (parts: ${joined.parts})",
        )
    }

    @Test
    fun describeUsesTheAndroidStorageWordingOnAndroid() {
        val joined =
            assertIs<UiText.Joined>(
                EpisodeRowSummary.describe(
                    testEpisodeRow(1),
                    storageWaitLive,
                    TestClock.DEFAULT_NOW,
                    platform = PlatformKind.ANDROID,
                ),
            )
        assertTrue(
            UiText.Res(Res.string.dl_wait_storage_android) in joined.parts,
            "android summary must use the SD-card wording (parts: ${joined.parts})",
        )
    }
}
