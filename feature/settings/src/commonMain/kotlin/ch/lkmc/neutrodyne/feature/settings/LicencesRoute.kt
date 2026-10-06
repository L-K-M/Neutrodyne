// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdLoading
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.about_licence_android
import ch.lkmc.neutrodyne.feature.settings.resources.about_licence_desktop
import ch.lkmc.neutrodyne.feature.settings.resources.licences_close
import ch.lkmc.neutrodyne.feature.settings.resources.licences_empty
import ch.lkmc.neutrodyne.feature.settings.resources.licences_library_count
import ch.lkmc.neutrodyne.feature.settings.resources.licences_search
import ch.lkmc.neutrodyne.feature.settings.resources.licences_section_bundled
import ch.lkmc.neutrodyne.feature.settings.resources.licences_section_libraries
import ch.lkmc.neutrodyne.feature.settings.resources.licences_source
import ch.lkmc.neutrodyne.feature.settings.resources.settings_licences
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Tag the shells put on manually defined `aboutlibraries.json` entries for bundled components
 * (FFmpeg, the OpenJDK runtime); they render in their own section (01 AboutLibraries).
 */
private const val BUNDLED_TAG: String = "bundled"

private sealed interface LicenceData {
    data object Loading : LicenceData

    /** [libs] is null when the build carries no `aboutlibraries.json`. */
    data class Loaded(val libs: Libs?) : LicenceData
}

/**
 * Settings › Licences (01 AboutLibraries and the Licences screen; 08 About). AboutLibraries
 * ships no Compose UI artifact, so [Libs] is rendered with the app's own components. The [source]
 * is provided by the shells' graphs (nullable so the page still works where the shell binds
 * nothing); tests pass a fake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LicencesRoute(source: LicencesSource?) {
    val data by produceState<LicenceData>(LicenceData.Loading, source) {
        value = LicenceData.Loaded(source?.load())
    }
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.settings_licences),
            navigation = { SettingsBackButton() },
        )
        when (val state = data) {
            LicenceData.Loading -> NdLoading()
            is LicenceData.Loaded -> LicenceList(state.libs)
        }
    }
}

@Composable
private fun LicenceList(libs: Libs?) {
    val libraries = libs?.libraries.orEmpty().sortedBy { it.name.lowercase() }
    if (libraries.isEmpty()) {
        LicenceEmpty()
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val (bundled, regular) = libraries.partition { it.tag == BUNDLED_TAG }
    val shownRegular = regular.filtered(query)
    val shownBundled = bundled.filtered(query)

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(Res.string.licences_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SCREEN_PADDING, vertical = FIELD_GAP),
            )
            LicenceStatement()
            SectionHeader(
                title = stringResource(Res.string.licences_section_libraries),
                count = shownRegular.size,
            )
        }
        items(shownRegular, key = { it.uniqueId }) { library ->
            LicenceRow(library) { detailId = library.uniqueId }
        }
        if (shownBundled.isNotEmpty()) {
            item {
                SectionHeader(
                    title = stringResource(Res.string.licences_section_bundled),
                    count = shownBundled.size,
                )
            }
            items(shownBundled, key = { it.uniqueId }) { library ->
                LicenceRow(library) { detailId = library.uniqueId }
            }
        }
    }

    libraries.firstOrNull { it.uniqueId == detailId }?.let { library ->
        LicenceDetail(library) { detailId = null }
    }
}

private fun List<Library>.filtered(query: String): List<Library> {
    if (query.isBlank()) return this
    return filter { library ->
        library.name.contains(query, ignoreCase = true) ||
            library.licenses.any { it.name.contains(query, ignoreCase = true) }
    }
}

@Composable
private fun LicenceEmpty() {
    Column(
        Modifier.fillMaxSize().padding(SCREEN_PADDING),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(Res.string.licences_empty))
    }
}

/**
 * The licence statement sits on top of the list (08 About › Licences). The platform variant is
 * chosen off `LocalPlatformActions.share` — null on the desktop (D83) — so the screen needs no
 * `BuildInfo` binding to render.
 */
@Composable
private fun LicenceStatement() {
    val platform = when (LocalPlatformActions.current.share) {
        null -> Res.string.about_licence_desktop
        else -> Res.string.about_licence_android
    }
    Text(
        stringResource(platform),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = SCREEN_PADDING, vertical = FIELD_GAP),
    )
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        "$title · " + pluralStringResource(Res.plurals.licences_library_count, count, count),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = SCREEN_PADDING, vertical = HEADER_GAP),
    )
}

@Composable
private fun LicenceRow(library: Library, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(library.name) },
        supportingContent = {
            val licences = library.licenses.joinToString { it.name }
            Text(listOfNotNull(library.artifactVersion, licences.ifBlank { null }).joinToString(" · "))
        },
        trailingContent = { Icon(NdIcons.ArrowForwardIos, contentDescription = null) },
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
    )
}

/** The licence detail (08 About › Licences): name, version, source and the full licence text. */
@Composable
private fun LicenceDetail(library: Library, onDismiss: () -> Unit) {
    NdDialog(
        onDismissRequest = onDismiss,
        icon = NdIcons.Article,
        title = library.name,
        text = buildString {
            append(stringResource(Res.string.licences_source, library.uniqueId))
            library.website?.let { append('\n').append(it) }
            for (licence in library.licenses) {
                append("\n\n").append(licence.name)
                licence.licenseContent?.let { append("\n\n").append(it) }
            }
        },
        confirm = NdDialogAction(
            label = stringResource(Res.string.licences_close),
            onClick = onDismiss,
        ),
    )
}

private val SCREEN_PADDING = 16.dp
private val FIELD_GAP = 8.dp
private val HEADER_GAP = 12.dp
