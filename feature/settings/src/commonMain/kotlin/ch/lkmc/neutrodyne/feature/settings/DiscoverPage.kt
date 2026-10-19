// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.DirectoryKeySource
import ch.lkmc.neutrodyne.core.model.ProviderFailure
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.ui.PrivacyInventory
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.discover_apple_links
import ch.lkmc.neutrodyne.feature.settings.resources.discover_apple_links_help
import ch.lkmc.neutrodyne.feature.settings.resources.discover_build_key
import ch.lkmc.neutrodyne.feature.settings.resources.discover_cancel
import ch.lkmc.neutrodyne.feature.settings.resources.discover_country_edit
import ch.lkmc.neutrodyne.feature.settings.resources.discover_country_hint
import ch.lkmc.neutrodyne.feature.settings.resources.discover_disabled
import ch.lkmc.neutrodyne.feature.settings.resources.discover_invalid_country
import ch.lkmc.neutrodyne.feature.settings.resources.discover_key
import ch.lkmc.neutrodyne.feature.settings.resources.discover_key_busy
import ch.lkmc.neutrodyne.feature.settings.resources.discover_key_entry
import ch.lkmc.neutrodyne.feature.settings.resources.discover_key_failed
import ch.lkmc.neutrodyne.feature.settings.resources.discover_key_help
import ch.lkmc.neutrodyne.feature.settings.resources.discover_key_invalid
import ch.lkmc.neutrodyne.feature.settings.resources.discover_key_rejected
import ch.lkmc.neutrodyne.feature.settings.resources.discover_key_remove
import ch.lkmc.neutrodyne.feature.settings.resources.discover_no_key
import ch.lkmc.neutrodyne.feature.settings.resources.discover_open_failed
import ch.lkmc.neutrodyne.feature.settings.resources.discover_pi_disabled
import ch.lkmc.neutrodyne.feature.settings.resources.discover_provider_busy
import ch.lkmc.neutrodyne.feature.settings.resources.discover_provider_failed
import ch.lkmc.neutrodyne.feature.settings.resources.discover_removed
import ch.lkmc.neutrodyne.feature.settings.resources.discover_save
import ch.lkmc.neutrodyne.feature.settings.resources.discover_save_failed
import ch.lkmc.neutrodyne.feature.settings.resources.discover_saved
import ch.lkmc.neutrodyne.feature.settings.resources.discover_secret
import ch.lkmc.neutrodyne.feature.settings.resources.discover_storage
import ch.lkmc.neutrodyne.feature.settings.resources.discover_title
import ch.lkmc.neutrodyne.feature.settings.resources.discover_user_key
import ch.lkmc.neutrodyne.feature.settings.resources.discover_verify
import ch.lkmc.neutrodyne.feature.settings.resources.discover_verifying
import ch.lkmc.neutrodyne.feature.settings.resources.privacy_intro
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DiscoverPage(viewModel: DiscoverSettingsViewModel = metroViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DiscoverSettingsScreen(
        state,
        viewModel::setCountry,
        viewModel::setProvider,
        viewModel::saveKey,
        viewModel::clearKey,
        viewModel::cancelKeyEntry,
    )
}

@Composable
@Suppress("LongParameterList")
internal fun DiscoverSettingsScreen(
    state: DiscoverSettingsState,
    onCountry: (String) -> Unit,
    onProvider: (ProviderId, Boolean) -> Unit,
    onKey: (String, CharArray) -> Unit,
    onRemove: () -> Unit,
    onCancelKey: () -> Unit,
) {
    var country by remember(state.country) { mutableStateOf(state.country) }
    var editing by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    val appleLinks = LocalPlatformActions.current.appleLinks
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(title = stringResource(Res.string.discover_title), navigation = { SettingsBackButton() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.privacy_intro))
            state.providers.forEach { provider ->
                val destination = PrivacyInventory.destinations.first { it.provider == provider.id }
                SettingsSwitchRow(
                    title = stringResource(destination.title),
                    summary = providerSummary(provider)?.let { stringResource(it) },
                    checked = provider.enabled,
                    onCheckedChange = { onProvider(provider.id, it) },
                    modifier = Modifier.testTag("provider-${provider.id}"),
                )
                Text(stringResource(destination.body), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                country,
                { country = it },
                label = { Text(stringResource(Res.string.discover_country_edit)) },
                singleLine = true,
                modifier = Modifier.testTag("discover-country"),
            )
            Text(stringResource(Res.string.discover_country_hint, state.systemCountry))
            TextButton(onClick = { onCountry(country) }) { Text(stringResource(Res.string.discover_save)) }
            state.providers.firstOrNull { it.needsKey && !it.enabled }?.let { provider ->
                Text(
                    stringResource(
                        when (provider.keySource) {
                            DirectoryKeySource.NONE -> Res.string.discover_no_key
                            DirectoryKeySource.USER -> Res.string.discover_user_key
                            DirectoryKeySource.BUILD -> Res.string.discover_build_key
                        },
                    ),
                )
            }
            Text(stringResource(Res.string.discover_storage))
            TextButton(onClick = { editing = true }, enabled = !state.busy) {
                Text(stringResource(Res.string.discover_key_entry))
            }
            if (state.providers.any { it.id == ProviderId.PODCAST_INDEX && it.keySource == DirectoryKeySource.USER }) {
                TextButton(
                    onClick = onRemove,
                    enabled = !state.busy,
                ) { Text(stringResource(Res.string.discover_key_remove)) }
            }
            if (state.busy) {
                Text(stringResource(Res.string.discover_verifying))
                TextButton(onClick = onCancelKey) { Text(stringResource(Res.string.discover_cancel)) }
            }
            state.notice?.let { Text(stringResource(noticeText(it)), modifier = Modifier.testTag("discover-notice")) }
            if (appleLinks != null) {
                Text(stringResource(Res.string.discover_apple_links_help))
                TextButton(onClick = { openFailed = appleLinks.openSettings() == OpenResult.NO_HANDLER }) {
                    Text(stringResource(Res.string.discover_apple_links))
                }
                if (openFailed) Text(stringResource(Res.string.discover_open_failed))
            }
        }
    }
    if (editing) {
        KeyEditor(onSubmit = onKey, onClose = { editing = false }, onCancel = onCancelKey)
    }
}

@Composable
private fun KeyEditor(
    onSubmit: (String, CharArray) -> Unit,
    onClose: () -> Unit,
    onCancel: () -> Unit,
) {
    // Deliberately remember, never rememberSaveable: rotation/process recreation drops the drafts.
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        onDispose {
            key = ""
            secret = ""
        }
    }
    val dismiss = {
        key = ""
        secret = ""
        onCancel()
        onClose()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(Res.string.discover_key_entry)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.discover_key_help))
                OutlinedTextField(
                    key,
                    { key = it },
                    label = { Text(stringResource(Res.string.discover_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true,
                    modifier = Modifier.testTag("pi-key"),
                )
                OutlinedTextField(
                    secret,
                    { secret = it },
                    label = { Text(stringResource(Res.string.discover_secret)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true,
                    modifier = Modifier.testTag("pi-secret"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val enteredKey = key
                val enteredSecret = secret.toCharArray()
                key = ""
                secret = ""
                onSubmit(enteredKey, enteredSecret)
                onClose()
            }, enabled = key.isNotBlank() && secret.isNotEmpty()) { Text(stringResource(Res.string.discover_verify)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(Res.string.discover_cancel)) } },
    )
}

internal fun providerSummary(provider: ProviderInfo): StringResource? =
    when {
        !provider.enabled -> {
            if (provider.id ==
                ProviderId.PODCAST_INDEX
            ) {
                Res.string.discover_pi_disabled
            } else {
                Res.string.discover_disabled
            }
        }

        provider.needsKey && !provider.hasKey -> {
            Res.string.discover_no_key
        }

        (provider.status as? ProviderStatus.Failed)?.reason == ProviderFailure.CREDENTIALS -> {
            Res.string.discover_key_rejected
        }

        provider.status is ProviderStatus.Failed -> {
            Res.string.discover_provider_failed
        }

        provider.status == ProviderStatus.RateLimited -> {
            Res.string.discover_provider_busy
        }

        provider.keySource == DirectoryKeySource.USER -> {
            Res.string.discover_user_key
        }

        provider.keySource == DirectoryKeySource.BUILD -> {
            Res.string.discover_build_key
        }

        else -> {
            null
        }
    }

private fun noticeText(notice: DiscoverNotice): StringResource =
    when (notice) {
        DiscoverNotice.SAVED -> Res.string.discover_saved
        DiscoverNotice.REMOVED -> Res.string.discover_removed
        DiscoverNotice.INVALID_COUNTRY -> Res.string.discover_invalid_country
        DiscoverNotice.SAVE_FAILED -> Res.string.discover_save_failed
        DiscoverNotice.KEY_FAILED -> Res.string.discover_key_failed
        DiscoverNotice.KEY_BUSY -> Res.string.discover_key_busy
        DiscoverNotice.KEY_INVALID -> Res.string.discover_key_invalid
    }
