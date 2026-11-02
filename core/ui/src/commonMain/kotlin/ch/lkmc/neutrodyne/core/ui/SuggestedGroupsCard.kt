// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.model.CategoryCount
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.suggested_groups_chip
import ch.lkmc.neutrodyne.core.ui.resources.suggested_groups_create
import ch.lkmc.neutrodyne.core.ui.resources.suggested_groups_dismiss
import ch.lkmc.neutrodyne.core.ui.resources.suggested_groups_title
import org.jetbrains.compose.resources.stringResource

/** Category suggestions are library groups, independent of the directory recommendation rows. */
@Composable
@Suppress("LongParameterList")
public fun SuggestedGroupsCard(
    suggestions: List<CategoryCount>,
    selected: Set<String>,
    busy: Boolean,
    onToggle: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    Card(modifier.fillMaxWidth().padding(8.dp).testTag("suggested-groups")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.suggested_groups_title), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { suggestion ->
                    FilterChip(
                        selected = suggestion.category in selected,
                        onClick = { onToggle(suggestion.category) },
                        enabled = !busy,
                        label = {
                            Text(
                                stringResource(
                                    Res.string.suggested_groups_chip,
                                    suggestion.category,
                                    suggestion.podcastIds.size,
                                ),
                            )
                        },
                    )
                }
            }
            TextButton(onClick = onCreate, enabled = !busy && selected.isNotEmpty()) {
                Text(stringResource(Res.string.suggested_groups_create))
            }
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
            ) { Text(stringResource(Res.string.suggested_groups_dismiss)) }
        }
    }
}
