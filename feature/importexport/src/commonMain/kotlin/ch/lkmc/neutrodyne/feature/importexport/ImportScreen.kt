// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.paging.insertSeparators
import androidx.paging.map
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdAssistChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdBanner
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdFilterChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdLoading
import ch.lkmc.neutrodyne.core.designsystem.components.NdOutlinedButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdProgress
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.rememberMonogramPainter
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupProposal
import ch.lkmc.neutrodyne.core.model.IMPORT_DEFERRED_DETAIL
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemFilter
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportItemView
import ch.lkmc.neutrodyne.core.model.ImportReportSection
import ch.lkmc.neutrodyne.core.model.ImportSessionView
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.reportSection
import ch.lkmc.neutrodyne.core.ui.EmptyState
import ch.lkmc.neutrodyne.core.ui.FeedErrorText
import ch.lkmc.neutrodyne.core.ui.NavBackButton
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionNotice
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.action_save
import ch.lkmc.neutrodyne.core.ui.resources.back
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_name_empty
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_name_long
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_no_members
import ch.lkmc.neutrodyne.core.ui.resources.import_cancelled_title
import ch.lkmc.neutrodyne.core.ui.resources.import_chip_duplicate
import ch.lkmc.neutrodyne.core.ui.resources.import_chip_invalid
import ch.lkmc.neutrodyne.core.ui.resources.import_chip_youtube
import ch.lkmc.neutrodyne.core.ui.resources.import_counts_join
import ch.lkmc.neutrodyne.core.ui.resources.import_deferred_hint
import ch.lkmc.neutrodyne.core.ui.resources.import_done_added
import ch.lkmc.neutrodyne.core.ui.resources.import_done_attention
import ch.lkmc.neutrodyne.core.ui.resources.import_done_title
import ch.lkmc.neutrodyne.core.ui.resources.import_failure_address_hint
import ch.lkmc.neutrodyne.core.ui.resources.import_failure_retry_hint
import ch.lkmc.neutrodyne.core.ui.resources.import_filter_attention
import ch.lkmc.neutrodyne.core.ui.resources.import_filter_new
import ch.lkmc.neutrodyne.core.ui.resources.import_filter_not_imported
import ch.lkmc.neutrodyne.core.ui.resources.import_group_excluded_default
import ch.lkmc.neutrodyne.core.ui.resources.import_group_excluded_section
import ch.lkmc.neutrodyne.core.ui.resources.import_group_excluded_wrapper
import ch.lkmc.neutrodyne.core.ui.resources.import_group_existing
import ch.lkmc.neutrodyne.core.ui.resources.import_group_include
import ch.lkmc.neutrodyne.core.ui.resources.import_group_members
import ch.lkmc.neutrodyne.core.ui.resources.import_group_rename
import ch.lkmc.neutrodyne.core.ui.resources.import_group_rename_title
import ch.lkmc.neutrodyne.core.ui.resources.import_groups_master
import ch.lkmc.neutrodyne.core.ui.resources.import_groups_section
import ch.lkmc.neutrodyne.core.ui.resources.import_header_in
import ch.lkmc.neutrodyne.core.ui.resources.import_load_error
import ch.lkmc.neutrodyne.core.ui.resources.import_newpipe_service
import ch.lkmc.neutrodyne.core.ui.resources.import_no_media_hint
import ch.lkmc.neutrodyne.core.ui.resources.import_opt_notify
import ch.lkmc.neutrodyne.core.ui.resources.import_opt_treat_played
import ch.lkmc.neutrodyne.core.ui.resources.import_options_section
import ch.lkmc.neutrodyne.core.ui.resources.import_podcast_count
import ch.lkmc.neutrodyne.core.ui.resources.import_private_feed
import ch.lkmc.neutrodyne.core.ui.resources.import_progress
import ch.lkmc.neutrodyne.core.ui.resources.import_report_already
import ch.lkmc.neutrodyne.core.ui.resources.import_report_merged
import ch.lkmc.neutrodyne.core.ui.resources.import_report_merged_count
import ch.lkmc.neutrodyne.core.ui.resources.import_search
import ch.lkmc.neutrodyne.core.ui.resources.import_select_item
import ch.lkmc.neutrodyne.core.ui.resources.import_select_none
import ch.lkmc.neutrodyne.core.ui.resources.import_source_unknown
import ch.lkmc.neutrodyne.core.ui.resources.import_status_already
import ch.lkmc.neutrodyne.core.ui.resources.import_status_auth
import ch.lkmc.neutrodyne.core.ui.resources.import_status_done
import ch.lkmc.neutrodyne.core.ui.resources.import_status_failed
import ch.lkmc.neutrodyne.core.ui.resources.import_status_gone
import ch.lkmc.neutrodyne.core.ui.resources.import_status_later
import ch.lkmc.neutrodyne.core.ui.resources.import_status_no_media
import ch.lkmc.neutrodyne.core.ui.resources.import_status_not_a_feed
import ch.lkmc.neutrodyne.core.ui.resources.import_status_not_imported
import ch.lkmc.neutrodyne.core.ui.resources.import_status_queued
import ch.lkmc.neutrodyne.core.ui.resources.import_stop
import ch.lkmc.neutrodyne.core.ui.resources.import_stop_body
import ch.lkmc.neutrodyne.core.ui.resources.import_stop_keep
import ch.lkmc.neutrodyne.core.ui.resources.import_stop_title
import ch.lkmc.neutrodyne.core.ui.resources.import_subscribe
import ch.lkmc.neutrodyne.core.ui.resources.import_subscribe_count
import ch.lkmc.neutrodyne.core.ui.resources.import_target_label
import ch.lkmc.neutrodyne.core.ui.resources.import_target_name
import ch.lkmc.neutrodyne.core.ui.resources.import_target_new
import ch.lkmc.neutrodyne.core.ui.resources.import_target_none
import ch.lkmc.neutrodyne.core.ui.resources.import_title
import ch.lkmc.neutrodyne.core.ui.resources.import_unavailable_body
import ch.lkmc.neutrodyne.core.ui.resources.import_unavailable_title
import ch.lkmc.neutrodyne.core.ui.resources.import_warn_ignored
import ch.lkmc.neutrodyne.core.ui.resources.import_warn_linked
import ch.lkmc.neutrodyne.core.ui.resources.import_warn_salvaged
import ch.lkmc.neutrodyne.core.ui.resources.import_warn_truncated
import ch.lkmc.neutrodyne.core.ui.resources.import_youtube_count
import ch.lkmc.neutrodyne.core.ui.resources.import_youtube_later
import ch.lkmc.neutrodyne.core.ui.resources.import_youtube_resolution_failed
import ch.lkmc.neutrodyne.core.ui.resources.selection_select_all
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val MONOGRAM = 40.dp

/**
 * The `ImportKey` screen (05 Preview contract, 08 Import): one route body for the session's
 * whole lifecycle — PREVIEW shows header/warnings/groups/options over the selectable paged
 * rows, COMMITTED/FETCHING swaps the editing chrome for progress, DONE/CANCELLED shows the
 * report with fix-ups. Every rendered value comes from [ImportUiState] plus the repository-paged
 * [items]; nothing here touches storage, files or the network.
 */
@Composable
internal fun ImportScreen(
    state: ImportUiState,
    items: Flow<PagingData<ImportItemView>>,
    onQuery: (String) -> Unit,
    onFilter: (ImportItemFilter) -> Unit,
    onToggle: (Int, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onImportGroups: (Boolean) -> Unit,
    onIncludeGroup: (String, Boolean) -> Unit,
    onRenameGroup: suspend (String, String) -> GroupError?,
    onTargetGroup: (Long?) -> Unit,
    onTargetGroupName: suspend (String) -> GroupError?,
    onTreatExisting: (Boolean) -> Unit,
    onNotify: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancelPreview: () -> Unit,
    onStopImport: () -> Unit,
    onBack: () -> Unit,
    onRetry: (ImportItemView) -> Unit = {},
    onRemove: (ImportItemView) -> Unit = {},
    onRemoveAll: () -> Unit = {},
    onEditUrl: suspend (ImportItemView, String) -> UiText? = { _, _ -> null },
    onPassword: suspend (ImportItemView, BasicCredentials) -> UiText? = { _, _ -> null },
    artworkFor: (Long) -> Flow<ArtworkRef?> = { flowOf(null) },
) {
    var fixUpFor by remember { mutableStateOf<Pair<ImportItemView, ImportFixUp>?>(null) }
    val session = state.session
    val editable = state.previewEditable
    var showStopDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            NdTopAppBar(
                title =
                    when {
                        session?.state == ImportState.CANCELLED -> {
                            stringResource(Res.string.import_cancelled_title)
                        }

                        else -> {
                            stringResource(Res.string.import_title)
                        }
                    },
                navigation = { NavBackButton() },
                actions = {
                    if (editable) {
                        NdTextButton(
                            label = stringResource(Res.string.action_cancel),
                            enabled = !state.busy,
                            onClick = onCancelPreview,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded -> {
                    NdLoading(Modifier.align(Alignment.Center))
                }

                session == null -> {
                    ImportUnavailableBody(onBack)
                }

                else -> {
                    SessionBody(
                        session = session,
                        state = state,
                        items = items,
                        onQuery = onQuery,
                        onFilter = onFilter,
                        onToggle = onToggle,
                        onSelectAll = onSelectAll,
                        onSelectNone = onSelectNone,
                        onImportGroups = onImportGroups,
                        onIncludeGroup = onIncludeGroup,
                        onRenameGroup = onRenameGroup,
                        onTargetGroup = onTargetGroup,
                        onTargetGroupName = onTargetGroupName,
                        onTreatExisting = onTreatExisting,
                        onNotify = onNotify,
                        onConfirm = onConfirm,
                        onStop = { showStopDialog = true },
                        onRetry = onRetry,
                        onRemove = onRemove,
                        onRemoveAll = onRemoveAll,
                        onEditUrl = { fixUpFor = it to ImportFixUp.URL },
                        onPassword = { fixUpFor = it to ImportFixUp.PASSWORD },
                        artworkFor = artworkFor,
                    )
                }
            }
            fixUpFor?.let { (item, kind) ->
                key(item.ordinal, kind) {
                    ImportFixUpDialog(
                        kind = kind,
                        onUrl = { onEditUrl(item, it) },
                        onPassword = { onPassword(item, it) },
                        onDismiss = { fixUpFor = null },
                    )
                }
            }
            if (showStopDialog) {
                NdDialog(
                    onDismissRequest = { showStopDialog = false },
                    title = stringResource(Res.string.import_stop_title),
                    text = stringResource(Res.string.import_stop_body),
                    confirm =
                        NdDialogAction(
                            label = stringResource(Res.string.import_stop),
                            role = NdActionRole.DESTRUCTIVE,
                        ) {
                            showStopDialog = false
                            onStopImport()
                        },
                    dismiss =
                        NdDialogAction(label = stringResource(Res.string.import_stop_keep)) {
                            showStopDialog = false
                        },
                )
            }
        }
    }
}

/** Invalid key, deleted session or expired id — the same explicit dead end, never a crash. */
@Composable
internal fun ImportUnavailableBody(onBack: () -> Unit) {
    EmptyState(
        icon = NdIcons.LinkOff,
        title = stringResource(Res.string.import_unavailable_title),
        body = stringResource(Res.string.import_unavailable_body),
        actionLabel = stringResource(Res.string.back),
        onAction = onBack,
    )
}

@Composable
private fun SessionBody(
    session: ImportSessionView,
    state: ImportUiState,
    items: Flow<PagingData<ImportItemView>>,
    onQuery: (String) -> Unit,
    onFilter: (ImportItemFilter) -> Unit,
    onToggle: (Int, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onImportGroups: (Boolean) -> Unit,
    onIncludeGroup: (String, Boolean) -> Unit,
    onRenameGroup: suspend (String, String) -> GroupError?,
    onTargetGroup: (Long?) -> Unit,
    onTargetGroupName: suspend (String) -> GroupError?,
    onTreatExisting: (Boolean) -> Unit,
    onNotify: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onStop: () -> Unit,
    onRetry: (ImportItemView) -> Unit,
    onRemove: (ImportItemView) -> Unit,
    onRemoveAll: () -> Unit,
    onEditUrl: (ImportItemView) -> Unit,
    onPassword: (ImportItemView) -> Unit,
    artworkFor: (Long) -> Flow<ArtworkRef?>,
) {
    val preview = session.state == ImportState.PREVIEW
    val rowsEnabled = !state.busy && session.state != ImportState.CANCELLED
    val rows =
        remember(items, preview) {
            items.map { data ->
                val entries = data.map<ImportItemView, ImportListRow> { ImportListRow.Entry(it) }
                if (preview) {
                    entries
                } else {
                    entries.insertSeparators { before, after ->
                        val next = (after as? ImportListRow.Entry)?.item
                        val previous = (before as? ImportListRow.Entry)?.item
                        if (next != null && next.reportSection() != previous?.reportSection()) {
                            ImportListRow.Header(next.reportSection(), next.ordinal)
                        } else {
                            null
                        }
                    }
                }
            }
        }
    val paging = rows.collectAsLazyPagingItems()
    val fetching = session.state == ImportState.COMMITTED || session.state == ImportState.FETCHING
    var renameFor by remember { mutableStateOf<GroupProposal?>(null) }
    var showTargetPicker by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item(key = "header", contentType = "header") {
                SessionHeader(session)
            }
            if (preview) {
                item(key = "warnings", contentType = "header") {
                    Warnings(session)
                }
                if (state.proposals.isNotEmpty() || session.options.importGroups) {
                    item(key = "groupsHead", contentType = "section") {
                        SectionLabel(stringResource(Res.string.import_groups_section))
                        GroupsBlock(
                            proposals = state.proposals,
                            importGroups = session.options.importGroups,
                            enabled = !state.busy,
                            onImportGroups = onImportGroups,
                            onIncludeGroup = onIncludeGroup,
                            onRename = { renameFor = it },
                        )
                    }
                }
                item(key = "options", contentType = "section") {
                    SectionLabel(stringResource(Res.string.import_options_section))
                    OptionsBlock(
                        session = session,
                        state = state,
                        enabled = !state.busy,
                        onPickTarget = { showTargetPicker = true },
                        onTreatExisting = onTreatExisting,
                        onNotify = onNotify,
                    )
                }
                item(key = "chips", contentType = "controls") {
                    ChipsRow(state.filter, onFilter)
                }
                item(key = "search", contentType = "controls") {
                    SearchField(state.query, onQuery)
                }
                item(key = "bulk", contentType = "controls") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NdTextButton(
                            label = stringResource(Res.string.selection_select_all),
                            enabled = !state.busy,
                            onClick = onSelectAll,
                        )
                        NdTextButton(
                            label = stringResource(Res.string.import_select_none),
                            enabled = !state.busy,
                            onClick = onSelectNone,
                        )
                    }
                }
            } else if (fetching) {
                item(key = "progress", contentType = "header") {
                    ProgressHeader(session)
                }
            } else {
                item(key = "report", contentType = "header") {
                    ReportHeader(session)
                }
                item(key = "reportChips", contentType = "controls") {
                    ChipsRow(state.filter, onFilter, report = true)
                }
            }
            items(
                count = paging.itemCount,
                key =
                    paging.itemKey {
                        when (it) {
                            is ImportListRow.Entry -> "row-${it.item.ordinal}"
                            is ImportListRow.Header -> "section-${it.section}-${it.firstOrdinal}"
                        }
                    },
                contentType = paging.itemContentType { if (it is ImportListRow.Header) "section" else "row" },
            ) { index ->
                when (val row = paging[index]) {
                    null -> {
                        Unit
                    }

                    is ImportListRow.Header -> {
                        ImportSectionHeader(row.section, session, rowsEnabled, onRemoveAll)
                    }

                    is ImportListRow.Entry -> {
                        ImportRow(
                            item = row.item,
                            preview = preview,
                            enabled = rowsEnabled,
                            onToggle = onToggle,
                            artworkFor = artworkFor,
                            onRetry = { onRetry(row.item) },
                            onRemove = { onRemove(row.item) },
                            onEditUrl = { onEditUrl(row.item) },
                            onPassword = { onPassword(row.item) },
                        )
                    }
                }
            }
            if (paging.itemCount == 0 && preview) {
                item(key = "empty", contentType = "header") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        when {
                            paging.loadState.hasError -> {
                                NdBanner(
                                    message = stringResource(Res.string.import_load_error),
                                    primary =
                                        NdDialogAction(
                                            stringResource(Res.string.action_retry),
                                            onClick = paging::retry,
                                        ),
                                )
                            }

                            paging.loadState.isIdle -> {
                                Text(
                                    stringResource(Res.string.group_edit_no_members),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            else -> {
                                NdLoading()
                            }
                        }
                    }
                }
            }
        }
        BottomBar(
            session = session,
            busy = state.busy,
            onConfirm = onConfirm,
            onStop = onStop,
        )

        renameFor?.let { proposal ->
            NameDialog(
                title = stringResource(Res.string.import_group_rename_title),
                initial = displayMetadata(proposal.name),
                onDismiss = { renameFor = null },
            ) { name ->
                val error = onRenameGroup(proposal.nameKey, name)
                if (error == null) renameFor = null
                error
            }
        }
        if (showTargetPicker) {
            TargetGroupDialog(
                groups = state.existingGroups,
                selectedId = session.options.targetGroupId,
                selectedName = session.options.targetGroupName,
                onPick = {
                    onTargetGroup(it)
                    showTargetPicker = false
                },
                onNewName = onTargetGroupName,
                onDismiss = { showTargetPicker = false },
            )
        }
    }
}

/** "N podcasts [and M YouTube channels] in <source>" plus the format-independent salvage flags. */
@Composable
private fun SessionHeader(session: ImportSessionView) {
    val total = session.counts.values.sum()
    val podcastCount = total - session.youtubeCount
    val counts =
        if (session.youtubeCount > 0) {
            stringResource(
                Res.string.import_counts_join,
                pluralStringResource(Res.plurals.import_podcast_count, podcastCount, podcastCount),
                pluralStringResource(
                    Res.plurals.import_youtube_count,
                    session.youtubeCount,
                    session.youtubeCount,
                ),
            )
        } else {
            pluralStringResource(Res.plurals.import_podcast_count, total, total)
        }
    ListItem(
        headlineContent = {
            Text(
                stringResource(
                    Res.string.import_header_in,
                    counts,
                    session.sourceName?.let(::displayMetadata) ?: stringResource(Res.string.import_source_unknown),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/** SALVAGED / IGNORED_OUTLINES / TRUNCATED_NAMES / LINKED_LISTS (05's warning table). */
@Composable
private fun Warnings(session: ImportSessionView) {
    Column {
        for (warning in session.warnings) {
            val text =
                when (warning.code) {
                    "SALVAGED" -> {
                        stringResource(Res.string.import_warn_salvaged)
                    }

                    "IGNORED_OUTLINES" -> {
                        pluralStringResource(
                            Res.plurals.import_warn_ignored,
                            warning.count,
                            warning.count,
                        )
                    }

                    "TRUNCATED_NAMES" -> {
                        pluralStringResource(
                            Res.plurals.import_warn_truncated,
                            warning.count,
                            warning.count,
                        )
                    }

                    "LINKED_LISTS" -> {
                        pluralStringResource(
                            Res.plurals.import_warn_linked,
                            warning.count,
                            warning.count,
                        )
                    }

                    else -> {
                        warning.detail?.let(::displayMetadata) ?: continue
                    }
                }
            NdBanner(
                message = text,
                icon = NdIcons.Info,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (session.recoveredBySalvage && session.warnings.none { it.code == "SALVAGED" }) {
            NdBanner(
                message = stringResource(Res.string.import_warn_salvaged),
                icon = NdIcons.Info,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun GroupsBlock(
    proposals: List<GroupProposal>,
    importGroups: Boolean,
    enabled: Boolean,
    onImportGroups: (Boolean) -> Unit,
    onIncludeGroup: (String, Boolean) -> Unit,
    onRename: (GroupProposal) -> Unit,
) {
    val masterLabel = stringResource(Res.string.import_groups_master)
    Column {
        ListItem(
            headlineContent = { Text(masterLabel) },
            trailingContent = {
                Switch(
                    checked = importGroups,
                    enabled = enabled,
                    onCheckedChange = onImportGroups,
                    modifier = Modifier.semantics { contentDescription = masterLabel },
                )
            },
        )
        if (importGroups) {
            for (proposal in proposals) {
                GroupProposalRow(proposal, enabled, onIncludeGroup, onRename)
            }
        }
    }
}

@Composable
private fun GroupProposalRow(
    proposal: GroupProposal,
    enabled: Boolean,
    onIncludeGroup: (String, Boolean) -> Unit,
    onRename: (GroupProposal) -> Unit,
) {
    val reason = proposal.excludedReason
    val name = displayMetadata(proposal.name)
    val includeLabel = stringResource(Res.string.import_group_include, name)
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                NdTextButton(
                    label = stringResource(Res.string.import_group_rename),
                    enabled = enabled,
                    onClick = { onRename(proposal) },
                )
            }
        },
        supportingContent = {
            if (reason != null) {
                Text(excludedReasonText(reason, name))
            } else {
                Text(
                    pluralStringResource(
                        Res.plurals.import_group_members,
                        proposal.memberCount,
                        proposal.memberCount,
                    ) +
                        if (proposal.existingGroupId != null) {
                            " · " + stringResource(Res.string.import_group_existing)
                        } else {
                            ""
                        },
                )
            }
        },
        trailingContent = {
            Switch(
                checked = proposal.included,
                enabled = enabled,
                onCheckedChange = { onIncludeGroup(proposal.nameKey, it) },
                modifier = Modifier.semantics { contentDescription = includeLabel },
            )
        },
    )
}

@Composable
private fun excludedReasonText(
    reason: String,
    name: String,
): String =
    when (reason) {
        EXCLUDED_WRAPPER -> stringResource(Res.string.import_group_excluded_wrapper, name)
        EXCLUDED_GPODDER_SECTION -> stringResource(Res.string.import_group_excluded_section, name)
        else -> stringResource(Res.string.import_group_excluded_default)
    }

@Composable
private fun OptionsBlock(
    session: ImportSessionView,
    state: ImportUiState,
    enabled: Boolean,
    onPickTarget: () -> Unit,
    onTreatExisting: (Boolean) -> Unit,
    onNotify: (Boolean) -> Unit,
) {
    val targetLabel =
        session.options.targetGroupId?.let { id ->
            state.existingGroups.firstOrNull { it.id == id }?.name
        } ?: session.options.targetGroupName
    val treatPlayedLabel = stringResource(Res.string.import_opt_treat_played)
    val notifyLabel = stringResource(Res.string.import_opt_notify)
    Column {
        ListItem(
            headlineContent = { Text(stringResource(Res.string.import_target_label)) },
            supportingContent = {
                Text(targetLabel?.let(::displayMetadata) ?: stringResource(Res.string.import_target_none))
            },
            modifier = Modifier.clickable(enabled = enabled, onClick = onPickTarget),
        )
        ListItem(
            headlineContent = { Text(treatPlayedLabel) },
            trailingContent = {
                Switch(
                    checked = session.options.treatExistingAsPlayed,
                    enabled = enabled,
                    onCheckedChange = onTreatExisting,
                    modifier = Modifier.semantics { contentDescription = treatPlayedLabel },
                )
            },
        )
        ListItem(
            headlineContent = { Text(notifyLabel) },
            trailingContent = {
                Switch(
                    checked = session.options.notifyNewEpisodes,
                    enabled = enabled,
                    onCheckedChange = onNotify,
                    modifier = Modifier.semantics { contentDescription = notifyLabel },
                )
            },
        )
        NotificationPermissionNotice(
            enabled = state.notificationsBlocked,
            requester = LocalPlatformActions.current.notifications,
        )
    }
}

@Composable
private fun ChipsRow(
    filter: ImportItemFilter,
    onFilter: (ImportItemFilter) -> Unit,
    report: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NdFilterChip(
            selected = filter == ImportItemFilter.ALL,
            onClick = { onFilter(ImportItemFilter.ALL) },
            label = stringResource(Res.string.selection_select_all),
        )
        if (!report) {
            NdFilterChip(
                selected = filter == ImportItemFilter.NEW_ONLY,
                onClick = { onFilter(ImportItemFilter.NEW_ONLY) },
                label = stringResource(Res.string.import_filter_new),
            )
        }
        NdFilterChip(
            selected = filter == ImportItemFilter.NOT_IMPORTED,
            onClick = { onFilter(ImportItemFilter.NOT_IMPORTED) },
            label = stringResource(Res.string.import_filter_not_imported),
        )
        NdFilterChip(
            selected = filter == ImportItemFilter.NEEDS_ATTENTION,
            onClick = { onFilter(ImportItemFilter.NEEDS_ATTENTION) },
            label = stringResource(Res.string.import_filter_attention),
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true,
        placeholder = { Text(stringResource(Res.string.import_search)) },
        leadingIcon = { Icon(NdIcons.Search, contentDescription = null) },
    )
}

/** Monogram, title and host only (05: never the raw URL); disabled statuses keep no toggle. */
@Composable
private fun ImportRow(
    item: ImportItemView,
    preview: Boolean,
    enabled: Boolean,
    onToggle: (Int, Boolean) -> Unit,
    artworkFor: (Long) -> Flow<ArtworkRef?>,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onEditUrl: () -> Unit,
    onPassword: () -> Unit,
) {
    val title = displayMetadata(item.title)
    val selectionLabel = stringResource(Res.string.import_select_item, title)
    ListItem(
        leadingContent = {
            val podcastId = item.podcastId
            if (!preview && podcastId != null) {
                val art by remember(podcastId, artworkFor) { artworkFor(podcastId) }
                    .collectAsStateWithLifecycle(initialValue = null)
                CoverArt(
                    ref = art,
                    monogram = rememberMonogram(title),
                    modifier = Modifier.size(MONOGRAM).testTag("import-cover-${item.ordinal}"),
                )
            } else {
                Image(
                    painter = rememberMonogramPainter(rememberMonogram(title)),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(MONOGRAM)
                            .clip(RoundedCornerShape(8.dp))
                            .testTag("import-monogram-${item.ordinal}"),
                )
            }
        },
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(displayMetadata(item.host), maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusChips(item, preview)
                if (!preview && item.reportSection() == ImportReportSection.ATTENTION) {
                    val fallback =
                        when (item.status) {
                            ImportItemStatus.NOT_A_FEED -> FeedErrorKind.NOT_A_FEED
                            ImportItemStatus.AUTH_REQUIRED -> FeedErrorKind.HTTP_AUTH
                            ImportItemStatus.GONE -> FeedErrorKind.HTTP_GONE
                            else -> FeedErrorKind.UNKNOWN
                        }
                    val errorKind = FeedErrorKind.entries.firstOrNull { it.name == item.errorDetail } ?: fallback
                    Text(FeedErrorText.describe(errorKind).asString(), style = MaterialTheme.typography.bodySmall)
                }
                statusDescription(item)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (!preview) ImportRowActions(item, enabled, onRetry, onEditUrl, onPassword, onRemove)
            }
        },
        trailingContent = {
            if (preview && item.selectable) {
                Checkbox(
                    checked = item.selected,
                    enabled = enabled,
                    onCheckedChange = { onToggle(item.ordinal, it) },
                    modifier =
                        Modifier.testTag("import-toggle-${item.ordinal}").semantics {
                            contentDescription = selectionLabel
                        },
                )
            }
        },
    )
}

@Composable
private fun StatusChips(
    item: ImportItemView,
    preview: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp),
    ) {
        if (item.isPrivate) {
            Icon(
                NdIcons.Key,
                contentDescription = stringResource(Res.string.import_private_feed),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val label = statusLabel(item, preview)
        if (label != null) {
            NdAssistChip(onClick = {}, label = label, enabled = false)
        }
        if (item.kind == ImportItemKind.YOUTUBE) {
            NdAssistChip(
                onClick = {},
                label = stringResource(Res.string.import_chip_youtube),
                leadingIcon = NdIcons.SmartDisplay,
                enabled = false,
            )
        }
    }
}

/** Every persisted status has an explicit display label (05's session/item table). */
@Composable
private fun statusLabel(
    item: ImportItemView,
    preview: Boolean,
): String? =
    when (item.status) {
        ImportItemStatus.PREVIEW -> {
            if (preview) null else stringResource(Res.string.import_status_not_imported)
        }

        ImportItemStatus.QUEUED -> {
            stringResource(Res.string.import_status_queued)
        }

        ImportItemStatus.SUBSCRIBED -> {
            stringResource(Res.string.import_status_done)
        }

        ImportItemStatus.ALREADY_SUBSCRIBED -> {
            stringResource(if (preview) Res.string.import_status_already else Res.string.import_report_already)
        }

        ImportItemStatus.MERGED -> {
            stringResource(Res.string.import_report_merged)
        }

        ImportItemStatus.DUPLICATE_IN_FILE -> {
            stringResource(Res.string.import_chip_duplicate)
        }

        ImportItemStatus.INVALID_URL -> {
            stringResource(Res.string.import_chip_invalid)
        }

        ImportItemStatus.YOUTUBE_UNSUPPORTED_YET -> {
            stringResource(Res.string.import_status_not_imported)
        }

        ImportItemStatus.NOT_A_FEED -> {
            stringResource(Res.string.import_status_not_a_feed)
        }

        ImportItemStatus.NO_MEDIA -> {
            stringResource(Res.string.import_status_no_media)
        }

        ImportItemStatus.AUTH_REQUIRED, ImportItemStatus.GONE -> {
            stringResource(Res.string.import_filter_attention)
        }

        ImportItemStatus.FETCH_FAILED -> {
            stringResource(
                if (item.errorDetail ==
                    IMPORT_DEFERRED_DETAIL
                ) {
                    Res.string.import_status_later
                } else {
                    Res.string.import_status_failed
                },
            )
        }
    }

@Composable
private fun statusDescription(item: ImportItemView): String? =
    when (item.status) {
        ImportItemStatus.INVALID_URL -> {
            item.errorDetail?.takeIf { it.startsWith("newpipe_service:") }?.let {
                stringResource(Res.string.import_newpipe_service, it.substringAfter(':'))
            }
        }

        ImportItemStatus.YOUTUBE_UNSUPPORTED_YET -> {
            stringResource(Res.string.import_youtube_later)
        }

        ImportItemStatus.NOT_A_FEED -> {
            stringResource(Res.string.import_failure_address_hint)
        }

        ImportItemStatus.NO_MEDIA -> {
            stringResource(Res.string.import_no_media_hint)
        }

        ImportItemStatus.AUTH_REQUIRED -> {
            stringResource(Res.string.import_status_auth)
        }

        ImportItemStatus.GONE -> {
            stringResource(Res.string.import_status_gone)
        }

        ImportItemStatus.FETCH_FAILED -> {
            stringResource(
                if (item.errorDetail ==
                    IMPORT_DEFERRED_DETAIL
                ) {
                    Res.string.import_deferred_hint
                } else if (item.errorDetail == "YOUTUBE_RESOLUTION_FAILED") {
                    Res.string.import_youtube_resolution_failed
                } else {
                    Res.string.import_failure_retry_hint
                },
            )
        }

        else -> {
            null
        }
    }

/** COMMITTED/FETCHING: determinate "Fetched X of Y" while the worker runs (05 §7). */
@Composable
private fun ProgressHeader(session: ImportSessionView) {
    val counts = session.counts
    val settled = FETCH_OUTCOMES.sumOf { counts[it] ?: 0 }
    val total = settled + (counts[ImportItemStatus.QUEUED] ?: 0)
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            stringResource(Res.string.import_progress, settled, total),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        NdProgress.Linear(progress = if (total > 0) settled.toFloat() / total else null)
    }
}

/** DONE/CANCELLED tallies, excluding deferred channels from the attention total. */
@Composable
private fun ReportHeader(session: ImportSessionView) {
    val counts = session.counts
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            if (session.state == ImportState.CANCELLED) {
                stringResource(Res.string.import_cancelled_title)
            } else {
                stringResource(Res.string.import_done_title)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        val added = counts[ImportItemStatus.SUBSCRIBED] ?: 0
        Text(
            pluralStringResource(Res.plurals.import_done_added, added, added),
            style = MaterialTheme.typography.bodyMedium,
        )
        ReportLine(Res.string.import_report_merged_count, counts[ImportItemStatus.MERGED] ?: 0)
        ReportLine(
            Res.string.import_report_already,
            counts[ImportItemStatus.ALREADY_SUBSCRIBED] ?: 0,
        )
        val attention =
            (counts.entries.filter { it.key.needsAttention() }.sumOf { it.value } - session.deferredCount)
                .coerceAtLeast(
                    0,
                )
        if (attention > 0) {
            Text(
                pluralStringResource(Res.plurals.import_done_attention, attention, attention),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ReportLine(
    label: org.jetbrains.compose.resources.StringResource,
    count: Int,
) {
    if (count <= 0) return
    Text(
        stringResource(label, count),
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun ImportItemStatus.needsAttention(): Boolean =
    this == ImportItemStatus.FETCH_FAILED ||
        this == ImportItemStatus.NO_MEDIA ||
        this == ImportItemStatus.NOT_A_FEED ||
        this == ImportItemStatus.AUTH_REQUIRED ||
        this == ImportItemStatus.GONE

/** The bottom action: "Subscribe" in preview, "Stop" while fetching, nothing once settled. */
@Composable
private fun BottomBar(
    session: ImportSessionView,
    busy: Boolean,
    onConfirm: () -> Unit,
    onStop: () -> Unit,
) {
    when (session.state) {
        ImportState.PREVIEW -> {
            Surface(shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    NdButton(
                        label =
                            if (session.selectedCount > 0) {
                                pluralStringResource(
                                    Res.plurals.import_subscribe_count,
                                    session.selectedCount,
                                    session.selectedCount,
                                )
                            } else {
                                // Zero selected rows can still move already-subscribed
                                // memberships — confirm stays enabled (05's member-only commit).
                                stringResource(Res.string.import_subscribe)
                            },
                        enabled = !busy,
                        onClick = onConfirm,
                    )
                }
            }
        }

        ImportState.COMMITTED,
        ImportState.FETCHING,
        -> {
            Surface(shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    NdOutlinedButton(
                        label = stringResource(Res.string.import_stop),
                        enabled = !busy,
                        onClick = onStop,
                    )
                }
            }
        }

        else -> {}
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Rename/new-name dialog: calls the suspend validator, keeps the typed error inline and only
 * closes on success — the ViewModel's `NameTaken`-is-reuse rule means a duplicate name is a
 * valid pick, not an error.
 */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (String) -> GroupError?,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<GroupError?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    NdDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = title,
        confirm =
            NdDialogAction(label = stringResource(Res.string.action_save)) {
                if (submitting) return@NdDialogAction
                submitting = true
                scope.launch {
                    try {
                        // The ViewModel reports storage failures; keep the form open on failure.
                        suspendRunCatching { error = onSubmit(name) }
                    } finally {
                        submitting = false
                    }
                }
            },
        dismiss = NdDialogAction(label = stringResource(Res.string.action_cancel), onClick = onDismiss),
    ) {
        Column {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                singleLine = true,
                enabled = !submitting,
                isError = error != null,
                supportingText = { error?.let { Text(groupErrorText(it)) } },
                label = { Text(stringResource(Res.string.import_target_name)) },
            )
        }
    }
}

/** "Put everything into group…": existing groups, a new name, or none. */
@Composable
private fun TargetGroupDialog(
    groups: List<Group>,
    selectedId: Long?,
    selectedName: String?,
    onPick: (Long?) -> Unit,
    onNewName: suspend (String) -> GroupError?,
    onDismiss: () -> Unit,
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    if (naming) {
        NameDialog(
            title = stringResource(Res.string.import_target_new),
            initial = selectedName?.let(::displayMetadata) ?: "",
            onDismiss = { naming = false },
        ) { name ->
            val error = onNewName(name)
            if (error == null) onDismiss()
            error
        }
    } else {
        TargetGroupList(
            groups = groups,
            selectedId = selectedId,
            selectedName = selectedName,
            onPick = onPick,
            onNewName = { naming = true },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun TargetGroupList(
    groups: List<Group>,
    selectedId: Long?,
    selectedName: String?,
    onPick: (Long?) -> Unit,
    onNewName: () -> Unit,
    onDismiss: () -> Unit,
) {
    NdDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.import_target_label),
        dismiss = NdDialogAction(label = stringResource(Res.string.action_cancel), onClick = onDismiss),
    ) {
        LazyColumn {
            item(key = "none") {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.import_target_none)) },
                    trailingContent = {
                        if (selectedId == null && selectedName == null) {
                            Icon(NdIcons.Check, contentDescription = null)
                        }
                    },
                    modifier = Modifier.clickable { onPick(null) },
                )
            }
            items(count = groups.size, key = { groups[it].id }) { index ->
                val group = groups[index]
                ListItem(
                    headlineContent = { Text(displayMetadata(group.name)) },
                    trailingContent = {
                        if (selectedId == group.id) Icon(NdIcons.Check, contentDescription = null)
                    },
                    modifier = Modifier.clickable { onPick(group.id) },
                )
            }
            item(key = "new") {
                ListItem(
                    headlineContent = {
                        Text(
                            selectedName?.let(::displayMetadata)
                                ?: stringResource(Res.string.import_target_new),
                        )
                    },
                    trailingContent = {
                        if (selectedName != null) Icon(NdIcons.Check, contentDescription = null)
                    },
                    modifier = Modifier.clickable { onNewName() },
                )
            }
        }
    }
}

@Composable
private fun groupErrorText(error: GroupError): String =
    when (error) {
        is GroupError.NameTooLong -> stringResource(Res.string.group_edit_name_long, error.max)
        else -> stringResource(Res.string.group_edit_name_empty)
    }

private const val EXCLUDED_WRAPPER = "wrapper"
private const val EXCLUDED_GPODDER_SECTION = "gpodder_section"

private val FETCH_OUTCOMES =
    setOf(
        ImportItemStatus.SUBSCRIBED,
        ImportItemStatus.MERGED,
        ImportItemStatus.NOT_A_FEED,
        ImportItemStatus.NO_MEDIA,
        ImportItemStatus.AUTH_REQUIRED,
        ImportItemStatus.GONE,
        ImportItemStatus.FETCH_FAILED,
    )

private val DISPLAY_URL = Regex("""(?i)[a-z][a-z0-9+.-]*://[^\s<>"{}|\\^`]+""")

/** Imported labels can contain private URLs; show only their host without altering stored data. */
internal fun displayMetadata(value: String): String =
    DISPLAY_URL.replace(value) { match ->
        val authority =
            match.value
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.startsWith('[')) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
    }
