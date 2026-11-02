// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.download_folder_inside_app
import ch.lkmc.neutrodyne.core.ui.resources.download_folder_not_directory
import ch.lkmc.neutrodyne.core.ui.resources.download_folder_not_writable
import ch.lkmc.neutrodyne.core.ui.resources.download_folder_unsupported
import ch.lkmc.neutrodyne.core.ui.resources.download_notice_background
import ch.lkmc.neutrodyne.core.ui.resources.download_notice_cap
import ch.lkmc.neutrodyne.core.ui.resources.download_notice_data_saver
import ch.lkmc.neutrodyne.core.ui.resources.download_notice_low
import ch.lkmc.neutrodyne.core.ui.resources.download_notice_move
import ch.lkmc.neutrodyne.core.ui.resources.download_notice_notifications
import ch.lkmc.neutrodyne.core.ui.resources.download_notice_orphans
import ch.lkmc.neutrodyne.core.ui.resources.download_notice_root_android
import ch.lkmc.neutrodyne.core.ui.resources.download_notice_root_desktop
import ch.lkmc.neutrodyne.core.ui.resources.download_notice_youtube
import ch.lkmc.neutrodyne.core.ui.resources.download_size_b
import ch.lkmc.neutrodyne.core.ui.resources.download_size_gb
import ch.lkmc.neutrodyne.core.ui.resources.download_size_gb_decimal
import ch.lkmc.neutrodyne.core.ui.resources.download_size_kb
import ch.lkmc.neutrodyne.core.ui.resources.download_size_mb
import ch.lkmc.neutrodyne.core.ui.resources.download_size_mb_decimal
import ch.lkmc.neutrodyne.core.ui.resources.playback_youtube_disabled
import ch.lkmc.neutrodyne.core.ui.resources.playback_youtube_engine_failed
import ch.lkmc.neutrodyne.core.ui.resources.playback_youtube_not_in_apk
import ch.lkmc.neutrodyne.core.ui.resources.playback_youtube_not_in_apk_desktop
import ch.lkmc.neutrodyne.core.ui.resources.playback_youtube_not_yet
import ch.lkmc.neutrodyne.core.ui.resources.playback_youtube_not_yet_desktop
import ch.lkmc.neutrodyne.download.api.DownloadNotice
import ch.lkmc.neutrodyne.download.api.DownloadsOverview
import ch.lkmc.neutrodyne.download.api.FolderRejectReason

/** Binary units match the frozen storage cap choices; one decimal keeps bulk estimates useful. */
public fun downloadSizeText(bytes: Long): UiText =
    when {
        bytes >= GIB -> scaledSizeText(bytes, GIB, Res.string.download_size_gb, Res.string.download_size_gb_decimal)
        bytes >= MIB -> scaledSizeText(bytes, MIB, Res.string.download_size_mb, Res.string.download_size_mb_decimal)
        bytes >= KIB -> UiText.Res(Res.string.download_size_kb, listOf(roundedUnits(bytes, KIB)))
        else -> UiText.Res(Res.string.download_size_b, listOf(bytes.coerceAtLeast(0)))
    }

private fun scaledSizeText(
    bytes: Long,
    unit: Long,
    whole: org.jetbrains.compose.resources.StringResource,
    decimal: org.jetbrains.compose.resources.StringResource,
): UiText {
    // Split before multiplying so even Long.MAX_VALUE bytes cannot overflow.
    val tenths = bytes / unit * SIZE_DECIMAL_SCALE + (bytes % unit * SIZE_DECIMAL_SCALE + unit / 2) / unit
    return if (tenths % SIZE_DECIMAL_SCALE == 0L) {
        UiText.Res(whole, listOf(tenths / SIZE_DECIMAL_SCALE))
    } else {
        UiText.Res(decimal, listOf(tenths / SIZE_DECIMAL_SCALE, tenths % SIZE_DECIMAL_SCALE))
    }
}

private fun roundedUnits(
    bytes: Long,
    unit: Long,
): Long = bytes / unit + if (bytes % unit >= unit / 2) 1 else 0

private const val SIZE_DECIMAL_SCALE = 10L
private const val KIB = 1_024L
private const val MIB = KIB * KIB
private const val GIB = MIB * KIB

/** Design order is independent of the producer set and enum declaration order. */
internal fun visibleDownloadNotices(
    notices: Set<DownloadNotice>,
    platform: PlatformKind,
): List<DownloadNotice> =
    NOTICE_ORDER.filter { it in notices && (platform == PlatformKind.ANDROID || it !in ANDROID_NOTICES) }.take(
        MAX_NOTICES,
    )

internal fun downloadNoticeText(
    notice: DownloadNotice,
    overview: DownloadsOverview,
    platform: PlatformKind,
): UiText =
    when (notice) {
        DownloadNotice.DATA_SAVER -> {
            UiText.Res(Res.string.download_notice_data_saver)
        }

        DownloadNotice.BACKGROUND_RESTRICTED -> {
            UiText.Res(Res.string.download_notice_background)
        }

        DownloadNotice.NOTIFICATIONS_OFF -> {
            UiText.Res(Res.string.download_notice_notifications)
        }

        DownloadNotice.CAP_REACHED -> {
            UiText.Res(
                Res.string.download_notice_cap,
                listOf(
                    downloadSizeText(overview.storage.capBytes ?: 0),
                ),
            )
        }

        DownloadNotice.STORAGE_LOW -> {
            UiText.Res(
                Res.string.download_notice_low,
                listOf(
                    overview.inProgress.count {
                        it.status.waitReason ==
                            WaitReason.STORAGE &&
                            it.status.lastError == DownloadError.STORAGE_FULL
                    },
                ),
            )
        }

        DownloadNotice.ROOT_UNAVAILABLE -> {
            UiText.Res(
                if (platform ==
                    PlatformKind.DESKTOP
                ) {
                    Res.string.download_notice_root_desktop
                } else {
                    Res.string.download_notice_root_android
                },
            )
        }

        DownloadNotice.YOUTUBE_PAUSED -> {
            UiText.Res(Res.string.download_notice_youtube)
        }

        // The frozen overview exposes no move counters; do not invent progress.
        DownloadNotice.MOVE_IN_PROGRESS -> {
            UiText.Res(Res.string.download_notice_move)
        }

        DownloadNotice.ORPHAN_FILES -> {
            UiText.Res(
                Res.string.download_notice_orphans,
                listOf(downloadSizeText(overview.storage.orphanBytes)),
            )
        }
    }

public fun folderRejectText(reason: FolderRejectReason): UiText =
    UiText.Res(
        when (reason) {
            FolderRejectReason.NOT_A_DIRECTORY -> Res.string.download_folder_not_directory
            FolderRejectReason.NOT_WRITABLE -> Res.string.download_folder_not_writable
            FolderRejectReason.INSIDE_APP_DIRECTORIES -> Res.string.download_folder_inside_app
            FolderRejectReason.UNSUPPORTED -> Res.string.download_folder_unsupported
        },
    )

private const val MAX_NOTICES = 2
private val ANDROID_NOTICES =
    setOf(
        DownloadNotice.DATA_SAVER,
        DownloadNotice.BACKGROUND_RESTRICTED,
        DownloadNotice.NOTIFICATIONS_OFF,
    )
private val NOTICE_ORDER =
    listOf(
        DownloadNotice.DATA_SAVER,
        DownloadNotice.BACKGROUND_RESTRICTED,
        DownloadNotice.NOTIFICATIONS_OFF,
        DownloadNotice.CAP_REACHED,
        DownloadNotice.STORAGE_LOW,
        DownloadNotice.ROOT_UNAVAILABLE,
        DownloadNotice.YOUTUBE_PAUSED,
        DownloadNotice.MOVE_IN_PROGRESS,
        DownloadNotice.ORPHAN_FILES,
    )

public fun downloadExternalReasonText(
    reason: ExternalReason,
    platform: PlatformKind,
): UiText =
    UiText.Res(
        when (reason) {
            ExternalReason.NOT_YET_AVAILABLE -> {
                if (platform ==
                    PlatformKind.DESKTOP
                ) {
                    Res.string.playback_youtube_not_yet_desktop
                } else {
                    Res.string.playback_youtube_not_yet
                }
            }

            ExternalReason.NOT_IN_THIS_APK -> {
                if (platform ==
                    PlatformKind.DESKTOP
                ) {
                    Res.string.playback_youtube_not_in_apk_desktop
                } else {
                    Res.string.playback_youtube_not_in_apk
                }
            }

            ExternalReason.DISABLED_BY_USER -> {
                Res.string.playback_youtube_disabled
            }

            ExternalReason.ENGINE_FAILED -> {
                Res.string.playback_youtube_engine_failed
            }
        },
    )
