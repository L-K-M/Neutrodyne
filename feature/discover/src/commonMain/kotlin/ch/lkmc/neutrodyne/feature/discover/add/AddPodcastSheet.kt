// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedCandidate
import ch.lkmc.neutrodyne.core.model.FeedPreview
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
import ch.lkmc.neutrodyne.core.ui.resources.add_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The "Add a podcast" sheet's content (08 Add podcast sheet), rendered inside the nav host's
 * `NdModalBottomSheet`. The field's text is `rememberSaveable`; a restored sheet re-resolves its
 * input through [onResolve] and the ViewModel dedupes a resolution that still stands (previews are
 * memory-only, D24).
 *
 * The "Add to groups" chips arrive with M2's group feed; the YouTube + suggest_rss card with M8
 * (deviations recorded in 08, 2026-10-07).
 */
@Composable
internal fun AddPodcastSheet(
    initialInput: String?,
    state: AddPodcastUiState,
    onResolve: (String) -> Unit,
    onCredentials: (BasicCredentials) -> Unit,
    onCancelResolve: () -> Unit,
    onInputChanged: (String) -> Unit,
    onCandidate: (String) -> Unit,
    onSubscribe: () -> Unit,
    onOpenPodcast: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf(initialInput.orEmpty()) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }

    // A pre-filled input (deep link, restored field) resolves at once; the VM dedupes remounts.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        if (input.isNotBlank()) onResolve(input)
    }

    val step = state.step
    val inputStep = step as? AddSheetStep.Input
    val alreadyId = alreadySubscribedId(step)
    // The primary action resolves the field until a subscribable preview stands, then subscribes
    // (08: "Enter runs Subscribe when it is enabled"). NoMedia previews are not subscribable.
    val canSubscribe =
        step is AddSheetStep.Preview &&
            !step.subscribing &&
            alreadyId == null &&
            (step.preview.emptyFeed || step.preview.episodeCount > 0)

    // Subscribe's single submit: preview → subscribe, auth fields → credentialed resolve,
    // otherwise resolve the field's input. Enter, the button and a failed state's Retry share it.
    fun submit() {
        when {
            canSubscribe -> onSubscribe()
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
                onInputChanged(it)
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
                            onInputChanged(it)
                        }
                    },
                    icon = NdIcons.ContentPaste,
                    contentDescription = stringResource(Res.string.add_paste),
                )
            },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
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
            AddSheetStep.Resolving ->
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
            is AddSheetStep.Choosing ->
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
                            onInputChanged(candidate.url)
                            onCandidate(candidate.url)
                        }
                    }
                }
            is AddSheetStep.Preview -> PreviewCard(step)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP, Alignment.End),
            modifier = Modifier.fillMaxWidth().padding(vertical = BUTTON_GAP),
        ) {
            NdTextButton(
                label = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
            if (alreadyId != null) {
                NdButton(
                    label = stringResource(Res.string.add_open),
                    onClick = { onOpenPodcast(alreadyId) },
                )
            } else {
                NdButton(
                    label = stringResource(Res.string.add_subscribe),
                    enabled = input.isNotBlank() && step !is AddSheetStep.Resolving,
                    onClick = ::submit,
                )
            }
        }
    }
}

/** The podcast an exact duplicate or subscribe-time dedupe hit already holds, if any. */
private fun alreadySubscribedId(step: AddSheetStep): Long? =
    (step as? AddSheetStep.Preview)?.let { s ->
        s.alreadySubscribedId ?: s.preview.alreadySubscribed?.takeIf { it.exact }?.podcastId
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
            modifier = Modifier.fillMaxWidth(),
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
                    icon = NdIcons.VisibilityOff,
                    contentDescription =
                        stringResource(
                            if (showPassword) Res.string.add_auth_hide else Res.string.add_auth_show,
                        ),
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier.fillMaxWidth().padding(top = FIELD_GAP),
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
            preview.alreadySubscribed?.let { dup ->
                Text(
                    stringResource(
                        if (dup.exact) {
                            Res.string.add_already_subscribed
                        } else {
                            Res.string.add_might_be_subscribed
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (dup.exact) {
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
