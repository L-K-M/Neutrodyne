// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.suggested_groups_first
import org.jetbrains.compose.resources.stringResource

/**
 * 08's suggested-groups card in its pre-M7 form: `observeCategoryCounts` (03, M7) does not
 * exist yet, so the card offers only the "Create your first group" action. The caller wires
 * [onClick] to the group editor (`GroupEditKey(null)`).
 */
@Composable
public fun SuggestedGroupsCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth().padding(CARD_MARGIN)) {
        Text(
            stringResource(Res.string.suggested_groups_first),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(CARD_PADDING),
        )
    }
}

private val CARD_MARGIN = 8.dp
private val CARD_PADDING = 16.dp
