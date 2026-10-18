// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportItemView
import ch.lkmc.neutrodyne.core.model.ImportReportSection
import ch.lkmc.neutrodyne.core.model.ImportSessionView
import ch.lkmc.neutrodyne.core.model.reportSection
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.import_edit_url
import ch.lkmc.neutrodyne.core.ui.resources.import_enter_password
import ch.lkmc.neutrodyne.core.ui.resources.import_remove
import ch.lkmc.neutrodyne.core.ui.resources.import_remove_all
import ch.lkmc.neutrodyne.core.ui.resources.import_remove_item
import ch.lkmc.neutrodyne.core.ui.resources.import_report_already
import ch.lkmc.neutrodyne.core.ui.resources.import_report_attention
import ch.lkmc.neutrodyne.core.ui.resources.import_report_done
import ch.lkmc.neutrodyne.core.ui.resources.import_report_merged
import ch.lkmc.neutrodyne.core.ui.resources.import_report_no_media
import ch.lkmc.neutrodyne.core.ui.resources.import_report_not_imported
import ch.lkmc.neutrodyne.core.ui.resources.import_section_count
import ch.lkmc.neutrodyne.core.ui.resources.import_status_later
import ch.lkmc.neutrodyne.core.ui.resources.import_status_queued
import org.jetbrains.compose.resources.stringResource

internal sealed interface ImportListRow {
    data class Header(
        val section: ImportReportSection,
        val firstOrdinal: Int,
    ) : ImportListRow

    data class Entry(
        val item: ImportItemView,
    ) : ImportListRow
}

@Composable
internal fun ImportSectionHeader(
    section: ImportReportSection,
    session: ImportSessionView,
    enabled: Boolean,
    onRemoveAll: () -> Unit,
) {
    val label =
        stringResource(
            when (section) {
                ImportReportSection.QUEUED -> Res.string.import_status_queued
                ImportReportSection.DONE -> Res.string.import_report_done
                ImportReportSection.ATTENTION -> Res.string.import_report_attention
                ImportReportSection.ALREADY -> Res.string.import_report_already
                ImportReportSection.MERGED -> Res.string.import_report_merged
                ImportReportSection.NO_MEDIA -> Res.string.import_report_no_media
                ImportReportSection.LATER -> Res.string.import_status_later
                ImportReportSection.NOT_IMPORTED -> Res.string.import_report_not_imported
            },
        )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("import-section-$section")) {
        Text(
            stringResource(Res.string.import_section_count, label, session.count(section)),
            style = MaterialTheme.typography.titleSmall,
        )
        if (section == ImportReportSection.NO_MEDIA && session.noMediaOrdinals.isNotEmpty()) {
            NdTextButton(stringResource(Res.string.import_remove_all), onClick = onRemoveAll, enabled = enabled)
        }
    }
}

private fun ImportSessionView.count(section: ImportReportSection): Int {
    fun count(vararg statuses: ImportItemStatus): Int = statuses.sumOf { counts[it] ?: 0 }
    return when (section) {
        ImportReportSection.QUEUED -> {
            count(ImportItemStatus.QUEUED)
        }

        ImportReportSection.DONE -> {
            count(ImportItemStatus.SUBSCRIBED)
        }

        ImportReportSection.ATTENTION -> {
            (
                count(
                    ImportItemStatus.NOT_A_FEED,
                    ImportItemStatus.AUTH_REQUIRED,
                    ImportItemStatus.GONE,
                    ImportItemStatus.FETCH_FAILED,
                ) - deferredCount
            ).coerceAtLeast(0)
        }

        ImportReportSection.ALREADY -> {
            count(ImportItemStatus.ALREADY_SUBSCRIBED)
        }

        ImportReportSection.MERGED -> {
            count(ImportItemStatus.MERGED)
        }

        ImportReportSection.NO_MEDIA -> {
            count(ImportItemStatus.NO_MEDIA)
        }

        ImportReportSection.LATER -> {
            deferredCount
        }

        ImportReportSection.NOT_IMPORTED -> {
            count(
                ImportItemStatus.PREVIEW,
                ImportItemStatus.DUPLICATE_IN_FILE,
                ImportItemStatus.INVALID_URL,
                ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
            )
        }
    }
}

@Composable
internal fun ImportRowActions(
    item: ImportItemView,
    enabled: Boolean,
    onRetry: () -> Unit,
    onEditUrl: () -> Unit,
    onPassword: () -> Unit,
    onRemove: () -> Unit,
) {
    val section = item.reportSection()
    if (item.podcastId == null || section !in ACTION_SECTIONS) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (section == ImportReportSection.ATTENTION) {
            NdTextButton(
                stringResource(Res.string.action_retry),
                onClick = onRetry,
                enabled = enabled,
                modifier = Modifier.testTag("import-retry-${item.ordinal}"),
            )
            NdTextButton(
                stringResource(Res.string.import_edit_url),
                onClick = onEditUrl,
                enabled = enabled,
                modifier = Modifier.testTag("import-edit-${item.ordinal}"),
            )
        }
        if (item.status == ImportItemStatus.AUTH_REQUIRED) {
            NdTextButton(
                stringResource(Res.string.import_enter_password),
                onClick = onPassword,
                enabled = enabled,
                modifier = Modifier.testTag("import-password-${item.ordinal}"),
            )
        }
        val removeLabel = stringResource(Res.string.import_remove_item, displayMetadata(item.title))
        NdTextButton(
            stringResource(Res.string.import_remove),
            onClick = onRemove,
            enabled = enabled,
            modifier =
                Modifier.testTag("import-remove-${item.ordinal}").semantics {
                    contentDescription = removeLabel
                },
        )
    }
}

private val ACTION_SECTIONS =
    setOf(ImportReportSection.QUEUED, ImportReportSection.ATTENTION, ImportReportSection.NO_MEDIA)
