// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.NdAssistChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdFilterChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedCandidate
import ch.lkmc.neutrodyne.core.model.FeedPreview
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.add_already_subscribed
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_hide
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_password
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_show
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_username
import ch.lkmc.neutrodyne.core.ui.resources.add_candidate_episodes
import ch.lkmc.neutrodyne.core.ui.resources.add_choose_title
import ch.lkmc.neutrodyne.core.ui.resources.add_empty_feed
import ch.lkmc.neutrodyne.core.ui.resources.add_episodes_count
import ch.lkmc.neutrodyne.core.ui.resources.add_field_label
import ch.lkmc.neutrodyne.core.ui.resources.add_helper
import ch.lkmc.neutrodyne.core.ui.resources.add_last_episode
import ch.lkmc.neutrodyne.core.ui.resources.add_looking_up
import ch.lkmc.neutrodyne.core.ui.resources.add_might_be_subscribed
import ch.lkmc.neutrodyne.core.ui.resources.add_open
import ch.lkmc.neutrodyne.core.ui.resources.add_paste
import ch.lkmc.neutrodyne.core.ui.resources.add_private
import ch.lkmc.neutrodyne.core.ui.resources.add_subscribe
import ch.lkmc.neutrodyne.core.ui.resources.add_subscribing
import ch.lkmc.neutrodyne.core.ui.resources.add_title
import ch.lkmc.neutrodyne.core.ui.resources.membership_add_to
import ch.lkmc.neutrodyne.core.ui.resources.membership_create
import ch.lkmc.neutrodyne.core.ui.resources.membership_name
import ch.lkmc.neutrodyne.core.ui.resources.membership_new
import ch.lkmc.neutrodyne.core.ui.resources.yt_edit_input
import ch.lkmc.neutrodyne.core.ui.resources.yt_long_form_note
import ch.lkmc.neutrodyne.core.ui.resources.yt_network_error
import ch.lkmc.neutrodyne.core.ui.resources.yt_not_found
import ch.lkmc.neutrodyne.core.ui.resources.yt_page_error
import ch.lkmc.neutrodyne.core.ui.resources.yt_playlist_unsupported
import ch.lkmc.neutrodyne.core.ui.resources.yt_rate_limited
import ch.lkmc.neutrodyne.core.ui.resources.yt_rss_offer
import ch.lkmc.neutrodyne.core.ui.resources.yt_subscribe_owner
import ch.lkmc.neutrodyne.core.ui.resources.yt_use_podcast
import ch.lkmc.neutrodyne.core.ui.resources.yt_variants_note
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.FailReason
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The "Add a podcast" sheet's content (08 Add podcast sheet), rendered inside the nav host's
 * `NdModalBottomSheet`. The field's text is `rememberSaveable`; a restored sheet re-resolves its
 * input through [onResolve] and the ViewModel dedupes a resolution that still stands (previews are
 * memory-only, D24). The credential fields stay plain `remember`: 08's saveable set is only the
 * input, the preview id and the chosen groups, so a typed password never lands in the
 * saved-state bundle — a restore re-prompts for it instead (M1b owns persistence, N3).
 *
 * The YouTube + suggest_rss card arrives with M8
 * (deviations recorded in 08, 2026-10-07).
 */
@Composable
internal fun AddPodcastSheet(
    initialInput: String?,
    state: AddPodcastUiState,
    onResolve: (String) -> Unit,
    onUsePodcast: () -> Unit = {},
    onSubscribeOwner: () -> Unit = {},
    onCredentials: (BasicCredentials) -> Unit,
    onCancelResolve: () -> Unit,
    onInputChange: (String) -> Unit,
    onCandidate: (String) -> Unit,
    onSubscribe: (Set<Long>) -> Unit,
    onCreateGroup: (String) -> Unit = {},
    onConsumeCreatedGroup: () -> Unit = {},
    onOpenPodcast: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosenGroups by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var newGroupName by rememberSaveable { mutableStateOf<String?>(null) }
    val currentOnConsumeCreatedGroup by rememberUpdatedState(onConsumeCreatedGroup)
    LaunchedEffect(state.createdGroupId) {
        state.createdGroupId?.let { id ->
            chosenGroups = (chosenGroups + id).distinct()
            newGroupName = null
            currentOnConsumeCreatedGroup()
        }
    }
    var input by rememberSaveable { mutableStateOf(initialInput.orEmpty()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }

    // A pre-filled input (deep link, restored field) resolves at once; the VM dedupes remounts.
    val currentOnResolve by rememberUpdatedState(onResolve)
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        if (input.isNotBlank()) currentOnResolve(input)
    }

    val step = state.step
    val inputStep = step as? AddSheetStep.Input
    val channel = step as? AddSheetStep.Channel
    val subscribing = (step as? AddSheetStep.Preview)?.subscribing == true || channel?.subscribing == true
    val alreadyId = channel?.alreadySubscribedId ?: alreadySubscribedId(step)
    // The primary action resolves the field until a subscribable preview stands, then subscribes
    // (08: "Enter runs Subscribe when it is enabled"). NoMedia previews are not subscribable.
    val canSubscribe =
        (step is AddSheetStep.Preview || channel != null) &&
            !state.creatingGroup &&
            !subscribing &&
            alreadyId == null &&
            (
                channel != null ||
                    (step is AddSheetStep.Preview && (step.preview.emptyFeed || step.preview.episodeCount > 0))
            )

    // Enter and the bottom button share this rule (08: "Enter runs Subscribe when it is
    // enabled"): a running lookup or subscribe may not be re-submitted, and while `Choose`
    // lists candidates only a candidate row advances — the field's value no longer leads.
    val submitEnabled =
        input.isNotBlank() &&
            !state.creatingGroup &&
            step !is AddSheetStep.Resolving &&
            step !is AddSheetStep.Choosing &&
            !subscribing && step !is AddSheetStep.ChannelFailure

    // Subscribe's single submit: preview → subscribe, auth fields → credentialed resolve,
    // otherwise resolve the field's input. Enter, the button and a failed state's Retry share it.
    fun submit() {
        when {
            canSubscribe -> onSubscribe(chosenGroups.toSet())
            inputStep?.auth == true -> onCredentials(BasicCredentials(username, password))
            input.isNotBlank() -> onResolve(input)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SHEET_PADDING),
    ) {
        Text(
            stringResource(Res.string.add_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = TITLE_GAP),
        )

        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                onInputChange(it)
            },
            label = { Text(stringResource(Res.string.add_field_label)) },
            supportingText = {
                if (inputStep?.error != null) {
                    Text(inputStep.error.asString(), color = MaterialTheme.colorScheme.error)
                } else {
                    Text(stringResource(Res.string.add_helper))
                }
            },
            isError = inputStep?.error != null,
            trailingIcon = {
                NdIconButton(
                    onClick = {
                        clipboard.getText()?.text?.let {
                            input = it
                            onInputChange(it)
                        }
                    },
                    icon = NdIcons.ContentPaste,
                    contentDescription = stringResource(Res.string.add_paste),
                )
            },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (submitEnabled) submit() }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )

        when (step) {
            is AddSheetStep.Input -> {
                if (step.auth) {
                    AuthFields(
                        username = username,
                        onUsername = { username = it },
                        password = password,
                        onPassword = { password = it },
                        showPassword = showPassword,
                        onTogglePassword = { showPassword = !showPassword },
                        onSubmit = ::submit,
                    )
                } else if (step.error != null) {
                    NdTextButton(
                        label = stringResource(Res.string.action_retry),
                        onClick = ::submit,
                    )
                }
            }

            AddSheetStep.Resolving -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
                    modifier = Modifier.fillMaxWidth().padding(vertical = ROW_GAP),
                ) {
                    Text(
                        stringResource(Res.string.add_looking_up),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    NdTextButton(
                        label = stringResource(Res.string.action_cancel),
                        onClick = onCancelResolve,
                    )
                }
            }

            is AddSheetStep.Choosing -> {
                Column(Modifier.fillMaxWidth().padding(top = ROW_GAP)) {
                    Text(
                        stringResource(Res.string.add_choose_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = ROW_GAP),
                    )
                    for (candidate in step.candidates) {
                        // A pick becomes the field's value and re-resolves (08).
                        CandidateRow(candidate) {
                            input = candidate.url
                            onInputChange(candidate.url)
                            onCandidate(candidate.url)
                        }
                    }
                }
            }

            is AddSheetStep.ChannelFailure -> {
                ChannelFailure(step.result, {
                    val owner = (step.result as? ChannelResolution.PlaylistUnsupported)?.ownerChannelId
                    if (owner != null) input = YouTubeFeedUrls.channelPage(owner)
                    onSubscribeOwner()
                }, onEdit = {
                    onCancelResolve()
                    focusRequester.requestFocus()
                }) {
                    onResolve(input)
                }
            }

            is AddSheetStep.Channel -> {
                ChannelCard(step) {
                    step.alternative?.let { input = it.feedUrl }
                    onUsePodcast()
                }
            }

            is AddSheetStep.Preview -> {
                PreviewCard(step)
            }
        }
        if (step is AddSheetStep.Preview || channel != null) {
            Text(stringResource(Res.string.membership_add_to))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.groups.forEach { group ->
                    NdFilterChip(
                        selected = group.id in chosenGroups,
                        label = group.name,
                        onClick = {
                            chosenGroups =
                                if (group.id in chosenGroups) chosenGroups - group.id else chosenGroups + group.id
                        },
                        enabled = !subscribing,
                    )
                }
                NdAssistChip(
                    label = stringResource(Res.string.membership_new),
                    onClick = { newGroupName = "" },
                    enabled = !subscribing && !state.creatingGroup,
                )
            }
            newGroupName?.let { name ->
                OutlinedTextField(
                    value = name,
                    onValueChange = { newGroupName = it },
                    label = { Text(stringResource(Res.string.membership_name)) },
                    singleLine = true,
                    isError = state.groupError != null,
                    supportingText = state.groupError?.let { { Text(it.asString()) } },
                    enabled = !state.creatingGroup && !subscribing,
                )
                NdTextButton(
                    label = stringResource(Res.string.membership_create),
                    onClick = { onCreateGroup(name) },
                    enabled = !state.creatingGroup && !subscribing,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP, Alignment.End),
            modifier = Modifier.fillMaxWidth().padding(vertical = BUTTON_GAP),
        ) {
            // While the lookup runs its own inline Cancel is the only one: a second
            // identically-labelled button would dismiss instead (scrim/back still can).
            if (step !is AddSheetStep.Resolving) {
                NdTextButton(
                    label = stringResource(Res.string.action_cancel),
                    onClick = onDismiss,
                )
            }
            if (alreadyId != null) {
                NdButton(
                    label = stringResource(Res.string.add_open),
                    onClick = { onOpenPodcast(alreadyId) },
                )
            } else {
                NdButton(
                    label = stringResource(Res.string.add_subscribe),
                    enabled = submitEnabled,
                    onClick = ::submit,
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(
    step: AddSheetStep.Channel,
    onUsePodcast: () -> Unit,
) {
    val resolved = step.resolution
    Column(Modifier.fillMaxWidth().padding(top = ROW_GAP)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val title = resolved.title ?: resolved.channelId
            CoverArt(
                ref = resolved.avatarUrl?.let { ArtworkRef(it, it, 0) },
                monogram = rememberMonogram(title),
                title = title,
                modifier = Modifier.size(PREVIEW_COVER),
            )
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = COVER_GAP))
        }
        Text(
            stringResource(
                if (step.variants ==
                    YouTubeVariantBits.LONG_FORM
                ) {
                    Res.string.yt_long_form_note
                } else {
                    Res.string.yt_variants_note
                },
            ),
        )
        if (step.alreadySubscribedId != null) Text(stringResource(Res.string.add_already_subscribed))
        step.alternative?.let { hit ->
            Text(stringResource(Res.string.yt_rss_offer))
            Text(hit.title)
            NdTextButton(
                label = stringResource(Res.string.yt_use_podcast),
                enabled = !step.subscribing,
                onClick = onUsePodcast,
            )
        }
        step.subscribeError?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
        if (step.subscribing) Text(stringResource(Res.string.add_subscribing))
    }
}

@Composable
private fun ChannelFailure(
    result: ChannelResolution,
    onOwner: () -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
) {
    val message =
        when (result) {
            ChannelResolution.NotFound -> {
                Res.string.yt_not_found
            }

            is ChannelResolution.PlaylistUnsupported -> {
                Res.string.yt_playlist_unsupported
            }

            is ChannelResolution.Failed -> {
                when (result.reason) {
                    FailReason.NETWORK -> Res.string.yt_network_error
                    FailReason.RATE_LIMITED -> Res.string.yt_rate_limited
                    else -> Res.string.yt_page_error
                }
            }

            else -> {
                Res.string.yt_page_error
            }
        }
    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
    val ownerId = (result as? ChannelResolution.PlaylistUnsupported)?.ownerChannelId
    if (result is ChannelResolution.PlaylistUnsupported && ownerId != null) {
        NdTextButton(
            label = stringResource(Res.string.yt_subscribe_owner, result.ownerTitle ?: ownerId),
            onClick = onOwner,
        )
    }
    if (result is ChannelResolution.Failed && result.retryable) {
        NdTextButton(label = stringResource(Res.string.action_retry), onClick = onRetry)
    } else {
        NdTextButton(label = stringResource(Res.string.yt_edit_input), onClick = onEdit)
    }
}

/** The podcast an exact duplicate or subscribe-time dedupe hit already holds, if any. */
private fun alreadySubscribedId(step: AddSheetStep): Long? =
    (step as? AddSheetStep.Preview)?.let { s ->
        s.alreadySubscribedId ?: s.preview.alreadySubscribed
            ?.takeIf { it.exact }
            ?.podcastId
    }

/** `AuthRequired`'s username/password fields (08; 03 Basic auth). */
@Composable
private fun AuthFields(
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = ROW_GAP)) {
        OutlinedTextField(
            value = username,
            onValueChange = onUsername,
            label = { Text(stringResource(Res.string.add_auth_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().contentType(ContentType.Username),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text(stringResource(Res.string.add_auth_password)) },
            singleLine = true,
            visualTransformation =
                if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                NdIconButton(
                    onClick = onTogglePassword,
                    icon = if (showPassword) NdIcons.VisibilityOff else NdIcons.Visibility,
                    contentDescription =
                        stringResource(
                            if (showPassword) Res.string.add_auth_hide else Res.string.add_auth_show,
                        ),
                )
            },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier.fillMaxWidth().contentType(ContentType.Password).padding(top = FIELD_GAP),
        )
    }
}

/** A `Choose` row: title and "N episodes · source" (08 Add podcast sheet). */
@Composable
private fun CandidateRow(
    candidate: FeedCandidate,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(candidate.title ?: candidate.url) },
        supportingContent = {
            Text(
                listOfNotNull(
                    candidate.episodeCount?.let {
                        pluralStringResource(Res.plurals.add_candidate_episodes, it, it)
                    },
                    candidate.source,
                ).joinToString(" · "),
            )
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(NeutrodyneShapes.Thumbnail)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button },
    )
}

/**
 * The `Feed(preview)` card (08): cover or monogram, title, "author · N episodes · latest {date}",
 * the "Private feed" row and the empty-feed / duplicate / subscribe-error lines.
 */
@Composable
private fun PreviewCard(step: AddSheetStep.Preview) {
    val preview = step.preview
    Surface(
        shape = NeutrodyneShapes.Thumbnail,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = ROW_GAP),
    ) {
        Column(Modifier.padding(CARD_PADDING)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PreviewCover(preview)
                Column(Modifier.padding(start = COVER_GAP)) {
                    Text(
                        preview.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        previewSubtitle(preview),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (preview.isPrivate) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = CARD_GAP),
                ) {
                    Icon(
                        NdIcons.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(CHIP_ICON),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(Res.string.add_private),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = CHIP_GAP),
                    )
                }
            }
            if (preview.emptyFeed) {
                Text(
                    stringResource(Res.string.add_empty_feed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = CARD_GAP),
                )
            }
            // A subscribe-time dedupe hit (step.alreadySubscribedId) is an exact match even when
            // the preview's earlier check was tentative — show "Already subscribed" either way.
            val alreadyExact =
                step.alreadySubscribedId != null || preview.alreadySubscribed?.exact == true
            if (alreadyExact || preview.alreadySubscribed != null) {
                Text(
                    stringResource(
                        if (alreadyExact) {
                            Res.string.add_already_subscribed
                        } else {
                            Res.string.add_might_be_subscribed
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (alreadyExact) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    modifier = Modifier.padding(top = CARD_GAP),
                )
            }
            step.subscribeError?.let { error ->
                Text(
                    error.asString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = CARD_GAP),
                )
            }
            // 08's "subscribing indicator": the transaction re-fetches an evicted preview and
            // can take a moment, so the card reports progress while Subscribe sits disabled.
            if (step.subscribing) {
                Text(
                    stringResource(Res.string.add_subscribing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = CARD_GAP),
                )
            }
        }
    }
}

/**
 * The preview's cover. The URL is not a pinned artwork row yet (the subscribe transaction writes
 * it), so it is a plain `ArtworkRef(key = url)` — `ArtworkRefMapper` falls back to the URL and
 * the monogram paints placeholder and error states.
 */
@Composable
private fun PreviewCover(preview: FeedPreview) {
    val ref = preview.artworkUrl?.let { ArtworkRef(key = it, url = it, version = 0) }
    CoverArt(
        ref = ref,
        monogram = rememberMonogram(preview.title),
        title = preview.title,
        modifier = Modifier.size(PREVIEW_COVER),
    )
}

/** "Author · 214 episodes · latest 2 Oct" — the wireframe's card meta line. */
@Composable
private fun previewSubtitle(preview: FeedPreview): String =
    listOfNotNull(
        preview.author?.takeIf { it.isNotBlank() },
        pluralStringResource(
            Res.plurals.add_episodes_count,
            preview.episodeCount,
            preview.episodeCount,
        ),
        preview.latestEpisodeAt?.let {
            val (day, month) = FeedDates.dayMonth(it)
            stringResource(Res.string.add_last_episode, "$day $month")
        },
    ).joinToString(" · ")

private val SHEET_PADDING = 24.dp
private val TITLE_GAP = 16.dp
private val ROW_GAP = 12.dp
private val FIELD_GAP = 8.dp
private val BUTTON_GAP = 12.dp
private val CARD_PADDING = 16.dp
private val CARD_GAP = 8.dp
private val COVER_GAP = 16.dp
private val PREVIEW_COVER = 64.dp
private val CHIP_ICON = 18.dp
private val CHIP_GAP = 8.dp
