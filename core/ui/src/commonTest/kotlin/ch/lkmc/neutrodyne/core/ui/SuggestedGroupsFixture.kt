// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ch.lkmc.neutrodyne.core.model.CategoryCount

@Composable
internal fun SuggestedGroupsFixture(
    onCreate: (Set<String>) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    var selected by remember { mutableStateOf(setOf("News")) }
    SuggestedGroupsCard(
        listOf(CategoryCount("Technology", listOf(1, 2, 3)), CategoryCount("News", listOf(2, 4))),
        selected,
        false,
        { selected = if (it in selected) selected - it else selected + it },
        { onCreate(selected) },
        onDismiss,
    )
}
