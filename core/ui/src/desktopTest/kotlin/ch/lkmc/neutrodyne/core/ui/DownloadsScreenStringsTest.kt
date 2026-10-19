// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.download.api.DownloadNotice
import ch.lkmc.neutrodyne.download.api.DownloadsOverview
import ch.lkmc.neutrodyne.download.api.FolderRejectReason
import ch.lkmc.neutrodyne.download.api.StorageUsage
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownloadsScreenStringsTest {
    private lateinit var previousLocale: Locale

    @BeforeTest fun englishCopy() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    private val overview =
        DownloadsOverview(
            emptyList(),
            emptyList(),
            emptyList(),
            StorageUsage(
                "dir:default",
                0,
                0,
                12_000_000,
                null,
                5L * 1024 * 1024 * 1024,
                true,
            ),
            emptySet(),
        )

    @Test fun everyWaitReasonAndFolderRejectionHasLocalizedText() =
        runTest {
            for (platform in PlatformKind.entries) {
                for (reason in WaitReason.entries) {
                    val text =
                        DownloadStatusText.describe(
                            DownloadState.QUEUED,
                            reason,
                            null,
                            null,
                            null,
                            0,
                            platform,
                        )
                    assertNotNull(
                        text,
                        reason.name,
                    )
                    assertTrue(
                        text.resolve().isNotBlank(),
                        reason.name,
                    )
                }
            }
            for (reason in FolderRejectReason.entries) assertTrue(folderRejectText(reason).resolve().isNotBlank())
        }

    @Test fun everyNoticeHasLocalizedTextOnBothPlatforms() =
        runTest {
            for (platform in PlatformKind.entries) {
                for (notice in DownloadNotice.entries) {
                    assertTrue(
                        downloadNoticeText(
                            notice,
                            overview,
                            platform,
                        ).resolve().isNotBlank(),
                        notice.name,
                    )
                }
            }
            assertTrue(
                downloadNoticeText(
                    DownloadNotice.CAP_REACHED,
                    overview,
                    PlatformKind.DESKTOP,
                ).resolve().contains("5 GB"),
            )
            assertTrue(
                downloadNoticeText(
                    DownloadNotice.MOVE_IN_PROGRESS,
                    overview,
                    PlatformKind.DESKTOP,
                ).resolve().contains("Moving downloads"),
            )
        }

    @Test fun designOrderIsStableAndShowsAtMostTwo() {
        val reverse = DownloadNotice.entries.reversed().toSet()
        assertEquals(
            listOf(
                DownloadNotice.DATA_SAVER,
                DownloadNotice.BACKGROUND_RESTRICTED,
            ),
            visibleDownloadNotices(
                reverse,
                PlatformKind.ANDROID,
            ),
        )
        assertEquals(
            listOf(
                DownloadNotice.CAP_REACHED,
                DownloadNotice.STORAGE_LOW,
            ),
            visibleDownloadNotices(
                reverse,
                PlatformKind.DESKTOP,
            ),
        )
        assertEquals(
            listOf(
                DownloadNotice.ROOT_UNAVAILABLE,
                DownloadNotice.MOVE_IN_PROGRESS,
            ),
            visibleDownloadNotices(
                setOf(
                    DownloadNotice.ORPHAN_FILES,
                    DownloadNotice.MOVE_IN_PROGRESS,
                    DownloadNotice.ROOT_UNAVAILABLE,
                ),
                PlatformKind.ANDROID,
            ),
        )
        assertEquals(
            emptyList(),
            visibleDownloadNotices(
                emptySet(),
                PlatformKind.DESKTOP,
            ),
        )
    }

    @Test fun missingStorageUsesThePlatformWording() =
        runTest {
            for ((
                platform,
                expected,
            ) in listOf(
                PlatformKind.ANDROID to "SD card",
                PlatformKind.DESKTOP to "download folder",
            )) {
                val text =
                    DownloadStatusText.describe(
                        DownloadState.QUEUED,
                        WaitReason.STORAGE,
                        null,
                        DownloadError.STORAGE_UNAVAILABLE,
                        null,
                        0,
                        platform,
                    )
                assertTrue(text!!.resolve().contains(expected))
                assertTrue(
                    downloadNoticeText(
                        DownloadNotice.ROOT_UNAVAILABLE,
                        overview,
                        platform,
                    ).resolve().contains(expected),
                )
            }
        }

    @Test fun bulkSizesPreserveTenthsAndCapSizesStayWhole() =
        runTest {
            assertEquals("1.5 GB", downloadSizeText(1536L * 1024 * 1024).resolve())
            assertEquals("1.5 MB", downloadSizeText(1536L * 1024).resolve())
            assertEquals("5 GB", downloadSizeText(5L * 1024 * 1024 * 1024).resolve())
            assertTrue(downloadSizeText(Long.MAX_VALUE).resolve().isNotEmpty())
        }
}
