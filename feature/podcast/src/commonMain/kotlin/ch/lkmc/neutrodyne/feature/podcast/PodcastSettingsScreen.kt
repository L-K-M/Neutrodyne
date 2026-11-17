// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdLoading
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.EngravedHeading
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedInfo
import ch.lkmc.neutrodyne.core.model.FeedMove
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.core.ui.CredentialFields
import ch.lkmc.neutrodyne.core.ui.DownloadSettingEdit
import ch.lkmc.neutrodyne.core.ui.DownloadSettingsRows
import ch.lkmc.neutrodyne.core.ui.DownloadSettingsScope
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.FeedErrorText
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.NavBackButton
import ch.lkmc.neutrodyne.core.ui.SettingsChoiceRow
import ch.lkmc.neutrodyne.core.ui.SettingsRow
import ch.lkmc.neutrodyne.core.ui.SettingsSwitchRow
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.radio.RadioSwitch
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_copy
import ch.lkmc.neutrodyne.core.ui.resources.action_save
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_title
import ch.lkmc.neutrodyne.core.ui.resources.import_chip_youtube
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
import ch.lkmc.neutrodyne.core.ui.resources.ps_show_in_all
import ch.lkmc.neutrodyne.core.ui.resources.section_feed
import ch.lkmc.neutrodyne.core.ui.resources.section_general
import ch.lkmc.neutrodyne.core.ui.resources.yt_include_live
import ch.lkmc.neutrodyne.core.ui.resources.yt_include_shorts
import ch.lkmc.neutrodyne.core.ui.resources.yt_live_future_note
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.ndTextFieldFocus
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Podcast settings (08 Podcast settings — General and Feed of M1): a scroll list of rows; writes
 * are fire-and-forget repository calls while `setCredentials` surfaces its `AddPodcastError` as a
 * snackbar and `editFeedUrl`'s returns as the dialog's inline error (08's Edit-URL dialog keeps
 * the dialog open on failure). M2 adds effective notification and refresh settings before Feed.
 */
@Composable
internal fun PodcastSettingsScreen(
    state: PodcastSettingsUiState,
    onCustomTitle: (String?) -> Unit,
    onOrderChange: (FeedOrder) -> Unit,
    onIncludeInAll: (Boolean) -> Unit,
    onEditFeedUrl: suspend (String) -> UiText?,
    onCredentials: (BasicCredentials) -> Unit,
    onRefreshInterval: (Int?) -> Unit,
    onNotifications: (Boolean?) -> Unit,
    modifier: Modifier = Modifier,
    onDownloadEdit: (DownloadSettingEdit) -> Unit = {},
    onVariants: (Int) -> Unit = {},
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
        val scroll = rememberScrollState()
        val bars = LocalScrollbars.current
        val barColor = ndScrollbarColor(bars)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .ndScrollbar(scroll, bars, barColor),
        ) {
            SectionHeader(stringResource(Res.string.section_general))
            GeneralSection(detail, state.effectiveOrder, onCustomTitle, onIncludeInAll, onOrderChange)

            state.scoped?.let {
                DownloadSettingsRows(
                    it,
                    DownloadSettingsScope.PODCAST,
                    LocalPlatformKind.current,
                    onDownloadEdit,
                    invalidFields = state.invalidFields,
                )
            }
            PodcastEffectiveSettings(state, onRefreshInterval, onNotifications)

            if (detail.sourceType == SourceType.YOUTUBE_CHANNEL) {
                SectionHeader(stringResource(Res.string.import_chip_youtube))
                val variants = detail.youtubeVariants
                SettingsSwitchRow(
                    icon = NdIcons.Settings,
                    summary = null,
                    title = stringResource(Res.string.yt_include_shorts),
                    checked = variants and YouTubeVariantBits.SHORTS != 0,
                    onCheckedChange = { enabled ->
                        onVariants(toggleVariant(variants, YouTubeVariantBits.SHORTS, enabled))
                    },
                )
                SettingsSwitchRow(
                    icon = NdIcons.Settings,
                    summary = null,
                    title = stringResource(Res.string.yt_include_live),
                    checked = variants and YouTubeVariantBits.LIVE != 0,
                    onCheckedChange = { enabled ->
                        onVariants(toggleVariant(variants, YouTubeVariantBits.LIVE, enabled))
                    },
                )
                Text(stringResource(Res.string.yt_live_future_note), modifier = Modifier.padding(horizontal = 16.dp))
            }

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
    onIncludeInAll: (Boolean) -> Unit,
    onOrderChange: (FeedOrder) -> Unit,
) {
    var titleDialog by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        SettingsRow(
            icon = NdIcons.Edit,
            title = stringResource(Res.string.ps_custom_title),
            // The read model exposes only the effective title; the dialog pre-fills it (deviation).
            summary = detail.displayTitle,
            onClick = { titleDialog = true },
        )
        SettingsSwitchRow(
            icon = NdIcons.DynamicFeed,
            summary = null,
            title = stringResource(Res.string.ps_show_in_all),
            checked = detail.includeInAll,
            onCheckedChange = onIncludeInAll,
        )
        SettingsChoiceRow(
            icon = NdIcons.Sort,
            title = stringResource(Res.string.ps_episode_order),
            summary = null,
            options = FeedOrder.entries,
            selected = order,
            label = {
                stringResource(
                    if (it ==
                        FeedOrder.NEWEST_FIRST
                    ) {
                        Res.string.order_newest
                    } else {
                        Res.string.order_oldest
                    },
                )
            },
            onSelect = onOrderChange,
            tag = { "order:${it.name}" },
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
    onEditFeedUrl: suspend (String) -> UiText?,
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
        SettingsRow(
            icon = NdIcons.RssFeed,
            title = stringResource(Res.string.ps_feed_address),
            summary =
                if (revealed) {
                    feedInfo.feedUrl
                } else {
                    feedInfo.redactedUrl + " · " + stringResource(Res.string.ps_feed_reveal)
                },
            onClick =
                if (revealed) {
                    null
                } else {
                    {
                        if (feedInfo.isPrivate) revealWarning = true else revealed = true
                    }
                },
            trailing =
                if (revealed) {
                    {
                        NdIconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(feedInfo.feedUrl))
                                scope.launch { snackbar.showSnackbar(copiedLabel) }
                            },
                            icon = NdIcons.Link,
                            contentDescription = stringResource(Res.string.action_copy),
                        )
                    }
                } else {
                    null
                },
        )

        SettingsRow(
            icon = NdIcons.History,
            title = stringResource(Res.string.ps_last_refresh),
            summary = lastRefreshSummary(feedInfo, nowMs).asString(),
            onClick = null,
        )

        if (feedInfo.moves.isNotEmpty()) {
            SettingsRow(
                icon = NdIcons.ArrowForward,
                title = stringResource(Res.string.ps_feed_moves),
                summary = feedInfo.moves.map { moveLine(it, nowMs).asString() }.joinToString("\n"),
                onClick = null,
            )
        }

        // RSS-only: a YouTube channel has neither a feed URL to move nor Basic-auth
        // credentials to set (03's credential flow applies to HTTP feeds).
        if (detail.sourceType == SourceType.RSS) {
            SettingsRow(
                icon = NdIcons.Edit,
                title = stringResource(Res.string.ps_edit_url),
                summary = feedInfo.pendingNewFeedUrl,
                onClick = { editDialog = true },
            )
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
            onSubmit = onEditFeedUrl,
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
    // A feed that never fetched has no outcome to report — "Never" alone, not "Never · OK".
    if (feedInfo.lastSuccessAt == null && feedInfo.lastErrorKind == null) {
        return UiText.Res(Res.string.ps_never)
    }
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

@Composable
internal fun SectionHeader(title: String) {
    EngravedHeading(title, Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
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
        dismiss = NdDialogAction(stringResource(Res.string.action_cancel), onClick = onDismiss),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(Res.string.ps_custom_title)) },
            singleLine = true,
            modifier = Modifier.ndTextFieldFocus(),
        )
    }
}

/**
 * 08's Edit-URL dialog: the submit returns the failure's `UiText` — shown inline under the field
 * with the dialog left open — while `null` closes it. A second tap during an in-flight edit is
 * ignored; [onDismiss] may still cancel it (the launch dies with the dialog's scope).
 */
@Composable
private fun EditUrlDialog(
    current: String,
    onSubmit: suspend (String) -> UiText?,
    onDismiss: () -> Unit,
) {
    // A typed URL can contain a password, so its draft must stay out of saved state.
    var text by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    NdDialog(
        onDismissRequest = onDismiss,
        icon = NdIcons.Edit,
        title = stringResource(Res.string.ps_edit_url_title),
        confirm =
            NdDialogAction(stringResource(Res.string.action_save)) {
                val url = text.trim()
                if (url.isEmpty() || submitting) return@NdDialogAction
                submitting = true
                scope.launch {
                    error = onSubmit(url)
                    submitting = false
                    if (error == null) onDismiss()
                }
            },
        dismiss = NdDialogAction(stringResource(Res.string.action_cancel), onClick = onDismiss),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            label = { Text(stringResource(Res.string.ps_edit_url_title)) },
            isError = error != null,
            supportingText = {
                error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            },
            singleLine = true,
            modifier = Modifier.ndTextFieldFocus(),
        )
    }
}

@Composable
internal fun SettingsCredentialsDialog(
    onSubmit: (BasicCredentials) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    // Secrets never enter saved state: a recreated dialog starts with an empty password.
    var password by remember { mutableStateOf("") }
    NdDialog(
        onDismissRequest = onDismiss,
        icon = NdIcons.Key,
        title = stringResource(Res.string.add_auth_title),
        confirm =
            NdDialogAction(stringResource(Res.string.action_save)) {
                onSubmit(BasicCredentials(username, password))
            },
        dismiss = NdDialogAction(stringResource(Res.string.action_cancel), onClick = onDismiss),
    ) {
        CredentialFields(
            username = username,
            onUsernameChange = { username = it },
            password = password,
            onPasswordChange = { password = it },
        )
    }
}

private val LOADING_PADDING = 32.dp
private val LOADING_TEXT_GAP = 16.dp

private fun toggleVariant(
    variants: Int,
    bit: Int,
    enabled: Boolean,
): Int = (if (enabled) variants or bit else variants and bit.inv()) or YouTubeVariantBits.LONG_FORM
