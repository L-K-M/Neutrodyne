// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/** Persisted fetch-detail marker shared by report classifiers and queries. Keep its value stable. */
const val IMPORT_DEFERRED_DETAIL: String = "DEFERRED"

/** 05's report sections, in display order. Queued work leads the progress view. */
enum class ImportReportSection { QUEUED, DONE, ATTENTION, ALREADY, MERGED, NO_MEDIA, LATER, NOT_IMPORTED }

fun ImportItemView.reportSection(): ImportReportSection =
    when (status) {
        ImportItemStatus.QUEUED -> {
            ImportReportSection.QUEUED
        }

        ImportItemStatus.SUBSCRIBED -> {
            ImportReportSection.DONE
        }

        ImportItemStatus.NOT_A_FEED, ImportItemStatus.AUTH_REQUIRED, ImportItemStatus.GONE -> {
            ImportReportSection.ATTENTION
        }

        ImportItemStatus.FETCH_FAILED -> {
            if (errorDetail == IMPORT_DEFERRED_DETAIL) ImportReportSection.LATER else ImportReportSection.ATTENTION
        }

        ImportItemStatus.ALREADY_SUBSCRIBED -> {
            ImportReportSection.ALREADY
        }

        ImportItemStatus.MERGED -> {
            ImportReportSection.MERGED
        }

        ImportItemStatus.NO_MEDIA -> {
            ImportReportSection.NO_MEDIA
        }

        ImportItemStatus.PREVIEW, ImportItemStatus.DUPLICATE_IN_FILE,
        ImportItemStatus.INVALID_URL, ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
        -> {
            ImportReportSection.NOT_IMPORTED
        }
    }
