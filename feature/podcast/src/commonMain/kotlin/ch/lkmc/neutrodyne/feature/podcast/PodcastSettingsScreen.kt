// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdLoading
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedInfo
import ch.lkmc.neutrodyne.core.model.FeedMove
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.FeedErrorText
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.NavBackButton
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_copy
import ch.lkmc.neutrodyne.core.ui.resources.action_save
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_password
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_title
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_username
import ch.lkmc.neutrodyne.core.ui.resources.order_newest
import ch.lkmc.neutrodyne.core.ui.resources.order_oldest
import ch.lkmc.neutrodyne.core.ui.resources.podcast_copy_feed
import ch.lkmc.neutrodyne.core.ui.resources.podcast_copy_feed_warning
import ch.lkmc.neutrodyne.core.ui.resources.podcast_loading
import ch.lkmc.neutrodyne.core.ui.resources.podcast_settings
import ch.lkmc.neutrodyne.core.ui.resources.ps_credentials
import ch.lkmc.neutrodyne.core.ui.resources.ps_custom_title
import ch.lkmc.neutrodyne.core.ui.resources.ps_edit_url
import ch.lkmc.neutrodyne.core.ui.resources.ps_edit_url_title
import ch.lkmc.neutrodyne.core.ui.resources.ps_episode_order
import ch.lkmc.neutrodyne.core.ui.resources.ps_feed_address
import ch.lkmc.neutrodyne.core.ui.resources.ps_feed_copied
import ch.lkmc.neutrodyne.core.ui.resources.ps_feed_moves
import ch.lkmc.neutrodyne.core.ui.resources.ps_feed_reveal
import ch.lkmc.neutrodyne.core.ui.resources.ps_last_refresh
import ch.lkmc.neutrodyne.core.ui.resources.ps_move_line
import ch.lkmc.neutrodyne.core.ui.resources.ps_move_reason_feed_url
import ch.lkmc.neutrodyne.core.ui.resources.ps_move_reason_other
import ch.lkmc.neutrodyne.core.ui.resources.ps_move_reason_redirect
import ch.lkmc.neutrodyne.core.ui.resources.ps_never
import ch.lkmc.neutrodyne.core.ui.resources.ps_ok
import ch.lkmc.neutrodyne.core.ui.resources.section_feed
import ch.lkmc.neutrodyne.core.ui.resources.section_general
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Podcast settings (08 Podcast settings — General and Feed of M1): a scroll list of rows; writes
 * are fire-and-forget repository calls while `editFeedUrl`/`setCredentials` surface their
 * `AddPodcastError` as a snackbar. The playback/downloads/notifications/refresh sections arrive
 * with their milestones; "Show in All" waits for the M5 scope-settings read model (deviation in
 * 08), and the M1b edit-address/credentials rows hide behind [FEED_ACCOUNT_CONTROLS_ENABLED].
 */
@Composable
internal fun PodcastSettingsScreen(
    state: PodcastSettingsUiState,
    onCustomTitle: (String?) -> Unit,
    onOrderChange: (FeedOrder) -> Unit,
    onEditFeedUrl: (String) -> Unit,
    onCredentials: (BasicCredentials) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.detail
    val feedInfo = state.feedInfo

    Column(modifier = modifier.fillMaxSize()) {
        NdTopAppBar(
            title =
                stringResource(Res.string.podcast_settings) +
                    (detail?.displayTitle?.let { " · $it" } ?: ""),
            navigation = { NavBackButton() },
        )
        if (!state.loaded || detail == null) {
            Column(Modifier.fillMaxWidth().padding(LOADING_PADDING)) {
                NdLoading()
                Text(
                    stringResource(Res.string.podcast_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LOADING_TEXT_GAP),
                )
            }
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(Res.string.section_general))
            GeneralSection(detail, state.effectiveOrder, onCustomTitle, onOrderChange)

            if (feedInfo != null) {
                SectionHeader(stringResource(Res.string.section_feed))
                FeedSection(feedInfo, detail, onEditFeedUrl, onCredentials)
            }
        }
    }
}

@Composable
private fun GeneralSection(
    detail: PodcastDetail,
    order: FeedOrder,
    onCustomTitle: (String?) -> Unit,
    onOrderChange: (FeedOrder) -> Unit,
) {
    var titleDialog by rememberSaveable { mutableStateOf(false) }
    var orderDialog by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        SettingsRow(
            icon = NdIcons.Edit,
            title = stringResource(Res.string.ps_custom_title),
            // The read model exposes only the effective title; the dialog pre-fills it (deviation).
            summary = detail.displayTitle,
            onClick = { titleDialog = true },
        )
        SettingsRow(
            icon = NdIcons.Sort,
            title = stringResource(Res.string.ps_episode_order),
            summary =
                stringResource(
                    if (order == FeedOrder.NEWEST_FIRST) {
                        Res.string.order_newest
                    } else {
                        Res.string.order_oldest
                    },
                ),
            onClick = { orderDialog = true },
        )
    }

    if (titleDialog) {
        CustomTitleDialog(
            current = detail.displayTitle,
            onSubmit = {
                titleDialog = false
                onCustomTitle(it)
            },
            onDismiss = { titleDialog = false },
        )
    }
    if (orderDialog) {
        OrderDialog(
            current = order,
            onPick = {
                orderDialog = false
                onOrderChange(it)
            },
            onDismiss = { orderDialog = false },
        )
    }
}

/**
 * 08's Feed section: the redacted address reveals on tap (private feeds warn first), moves and
 * last refresh/error read from `FeedInfo`, and "Edit feed address"/"Username and password" open
 * their dialogs. `pendingNewFeedUrl` has no row of its own at M1a.
 */
@Composable
private fun FeedSection(
    feedInfo: FeedInfo,
    detail: PodcastDetail,
    onEditFeedUrl: (String) -> Unit,
    onCredentials: (BasicCredentials) -> Unit,
) {
    val nowMs = LocalUiClock.current.now()
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val copiedLabel = stringResource(Res.string.ps_feed_copied)

    var revealed by rememberSaveable { mutableStateOf(false) }
    var revealWarning by rememberSaveable { mutableStateOf(false) }
    var editDialog by rememberSaveable { mutableStateOf(false) }
    var credentialsDialog by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(Res.string.ps_feed_address)) },
            supportingContent = {
                Text(
                    if (revealed) {
                        feedInfo.feedUrl
                    } else {
                        feedInfo.redactedUrl + " · " + stringResource(Res.string.ps_feed_reveal)
                    },
                )
            },
            leadingContent = { Icon(NdIcons.RssFeed, contentDescription = null) },
            trailingContent = {
                if (revealed) {
                    NdIconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(feedInfo.feedUrl))
                            scope.launch { snackbar.showSnackbar(copiedLabel) }
                        },
                        icon = NdIcons.Link,
                        contentDescription = stringResource(Res.string.action_copy),
                    )
                }
            },
            modifier =
                Modifier
                    .clickable {
                        if (revealed) return@clickable
                        if (feedInfo.isPrivate) {
                            revealWarning = true
                        } else {
                            revealed = true
                        }
                    }.semantics { role = Role.Button },
        )

        ListItem(
            headlineContent = { Text(stringResource(Res.string.ps_last_refresh)) },
            supportingContent = { Text(lastRefreshSummary(feedInfo, nowMs).asString()) },
            leadingContent = { Icon(NdIcons.History, contentDescription = null) },
        )

        if (feedInfo.moves.isNotEmpty()) {
            ListItem(
                headlineContent = { Text(stringResource(Res.string.ps_feed_moves)) },
                supportingContent = {
                    Column {
                        feedInfo.moves.forEach { move ->
                            Text(moveLine(move, nowMs).asString())
                        }
                    }
                },
                leadingContent = { Icon(NdIcons.ArrowForward, contentDescription = null) },
            )
        }

        // M1b's edit-address and credentials rows stay hidden while their repository methods throw.
        if (FEED_ACCOUNT_CONTROLS_ENABLED && detail.sourceType == SourceType.RSS) {
            SettingsRow(
                icon = NdIcons.Edit,
                title = stringResource(Res.string.ps_edit_url),
                summary = feedInfo.pendingNewFeedUrl,
                onClick = { editDialog = true },
            )
        }
        if (FEED_ACCOUNT_CONTROLS_ENABLED) {
            SettingsRow(
                icon = NdIcons.Key,
                title = stringResource(Res.string.ps_credentials),
                summary = null,
                onClick = { credentialsDialog = true },
            )
        }
    }

    if (revealWarning) {
        NdDialog(
            onDismissRequest = { revealWarning = false },
            icon = NdIcons.Lock,
            title = stringResource(Res.string.podcast_copy_feed),
            text = stringResource(Res.string.podcast_copy_feed_warning),
            confirm =
                NdDialogAction(stringResource(Res.string.ps_feed_reveal)) {
                    revealWarning = false
                    revealed = true
                },
            dismiss =
                NdDialogAction(stringResource(Res.string.action_cancel)) { revealWarning = false },
        )
    }
    if (editDialog) {
        EditUrlDialog(
            current = feedInfo.feedUrl,
            onSubmit = {
                editDialog = false
                onEditFeedUrl(it)
            },
            onDismiss = { editDialog = false },
        )
    }
    if (credentialsDialog) {
        SettingsCredentialsDialog(
            onSubmit = {
                credentialsDialog = false
                onCredentials(it)
            },
            onDismiss = { credentialsDialog = false },
        )
    }
}

/** "{relative} · {OK|error}" for the last-refresh row (08's Feed section). */
@Composable
private fun lastRefreshSummary(
    feedInfo: FeedInfo,
    nowMs: Long,
): UiText {
    val whenText =
        feedInfo.lastSuccessAt?.let { FeedDates.relative(it, nowMs) }
            ?: UiText.Res(Res.string.ps_never)
    val status =
        feedInfo.lastErrorKind?.let { FeedErrorText.describe(it) }
            ?: UiText.Res(Res.string.ps_ok)
    return UiText.Joined(listOf(whenText, status), separator = " · ", suffix = "")
}

/** "{fromHost} · {reason} · {when}" for one recorded feed move. */
@Composable
private fun moveLine(
    move: FeedMove,
    nowMs: Long,
): UiText =
    UiText.Res(
        Res.string.ps_move_line,
        listOf(
            move.fromHost,
            stringResource(
                when (move.reason) {
                    AliasReason.REDIRECT -> Res.string.ps_move_reason_redirect
                    AliasReason.NEW_FEED_URL -> Res.string.ps_move_reason_feed_url
                    else -> Res.string.ps_move_reason_other
                },
            ),
            FeedDates.relative(move.at, nowMs).asString(),
        ),
    )

/** A settings row identical to `:feature:settings`'s internal one (modules cannot share internals). */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { if (summary != null) Text(summary) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(NdIcons.ArrowForwardIos, contentDescription = null) },
        modifier =
            Modifier
                .clickable(onClick = onClick)
                .semantics { role = Role.Button },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(SECTION_PADDING),
    )
}

@Composable
private fun CustomTitleDialog(
    current: String,
    onSubmit: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    NdDialog(
        onDismissRequest = onDismiss,
        icon = NdIcons.Edit,
        title = stringResource(Res.string.ps_custom_title),
        confirm =
            NdDialogAction(stringResource(Res.string.action_save)) {
                onSubmit(text.trim().ifEmpty { null })
            },
        dismiss = NdDialogAction(stringResource(Res.string.action_cancel), onDismiss),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(Res.string.ps_custom_title)) },
            singleLine = true,
        )
    }
}

@Composable
private fun OrderDialog(
    current: FeedOrder,
    onPick: (FeedOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    NdDialog(onDismissRequest = onDismiss, title = stringResource(Res.string.ps_episode_order)) {
        Column {
            for (option in FeedOrder.entries) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == current,
                                role = Role.RadioButton,
                                onClick = { onPick(option) },
                            ).padding(ROW_PADDING),
                ) {
                    RadioButton(selected = option == current, onClick = null)
                    Text(
                        stringResource(
                            if (option == FeedOrder.NEWEST_FIRST) {
                                Res.string.order_newest
                            } else {
                                Res.string.order_oldest
                            },
                        ),
                        modifier = Modifier.padding(start = RADIO_GAP),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditUrlDialog(
    current: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    NdDialog(
        onDismissRequest = onDismiss,
        icon = NdIcons.Edit,
        title = stringResource(Res.string.ps_edit_url_title),
        confirm =
            NdDialogAction(stringResource(Res.string.action_save)) {
                val url = text.trim()
                if (url.isNotEmpty()) onSubmit(url)
            },
        dismiss = NdDialogAction(stringResource(Res.string.action_cancel), onDismiss),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(Res.string.ps_edit_url_title)) },
            singleLine = true,
        )
    }
}

@Composable
private fun SettingsCredentialsDialog(
    onSubmit: (BasicCredentials) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    NdDialog(
        onDismissRequest = onDismiss,
        icon = NdIcons.Key,
        title = stringResource(Res.string.add_auth_title),
        confirm =
            NdDialogAction(stringResource(Res.string.action_save)) {
                onSubmit(BasicCredentials(username, password))
            },
        dismiss = NdDialogAction(stringResource(Res.string.action_cancel), onDismiss),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(Res.string.add_auth_username)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(Res.string.add_auth_password)) },
                singleLine = true,
            )
        }
    }
}

private val SECTION_PADDING = 16.dp
private val ROW_PADDING = 16.dp
private val RADIO_GAP = 16.dp
private val FIELD_GAP = 12.dp
private val LOADING_PADDING = 32.dp
private val LOADING_TEXT_GAP = 16.dp
