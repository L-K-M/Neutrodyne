// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The group editor through `runComposeUiTest` (05 Group edit): name typing and inline errors,
 * palette and icon-sheet choices, member search/toggle, the Save hand-off and the
 * Tab/Enter/Escape keys. `installFakeImageLoader` keeps tile art off the network.
 */
@OptIn(ExperimentalTestApi::class)
class GroupEditScreenTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun nameErrorsRenderInline() =
        runComposeUiTest {
            var uiState by mutableStateOf(GroupEditUiState(loaded = true, draft = GroupEditDraft(name = "Dup")))
            setEditor({ uiState })
            runOnIdle { uiState = uiState.copy(nameError = GroupError.NameEmpty) }
            onNodeWithText("Enter a name").assertIsDisplayed()
            runOnIdle { uiState = uiState.copy(nameError = GroupError.NameTooLong(40)) }
            onNodeWithText("Use at most 40 characters").assertIsDisplayed()
            runOnIdle { uiState = uiState.copy(nameError = GroupError.NameTaken(2)) }
            onNodeWithText("A group named 'Dup' already exists").assertIsDisplayed()
        }

    @Test
    fun paletteChoiceAndIconSheetWriteTheDraft() =
        runComposeUiTest {
            var uiState by mutableStateOf(GroupEditUiState(loaded = true))
            setEditor({ uiState }, onEdit = { uiState = uiState.copy(draft = it) })
            onNodeWithContentDescription("teal").performClick()
            assertEquals(GroupPalette.COLORS[4], uiState.draft.colorArgb)
            onNodeWithTag("groupIconPicker").performClick()
            onNodeWithContentDescription("newspaper").performClick()
            assertEquals("newspaper", uiState.draft.iconKey)
            // Picking an item dismisses the sheet.
            onNodeWithContentDescription("gavel").assertDoesNotExist()
        }

    @Test
    fun searchFiltersAndClickTogglesAMember() =
        runComposeUiTest {
            var uiState by mutableStateOf(
                GroupEditUiState(
                    loaded = true,
                    tiles =
                        listOf(testLibraryTile(7, displayTitle = "Alpha"), testLibraryTile(8, displayTitle = "Beta")),
                ),
            )
            setEditor(
                { uiState },
                onSearch = { uiState = uiState.copy(search = it) },
                onToggleMember = { uiState = uiState.copy(draft = uiState.draft.toggle(it)) },
            )
            onNodeWithTag("groupEditorGrid").performScrollToNode(hasTestTag("groupMember8"))
            onNodeWithTag("groupMember8")
                .assertHasClickAction()
                .assertTextContains("Beta")
                .assertIsOff()
                .performClick()
            onNodeWithTag("groupMember8").assertIsOn()
            onNodeWithTag("groupEditorGrid").performScrollToNode(hasTestTag("groupMemberSearch"))
            onNodeWithTag("groupMemberSearch").performTextInput("alpha")
            onNodeWithText("Alpha").assertIsDisplayed()
            onNodeWithText("Beta").assertDoesNotExist()
        }

    @Test
    fun saveSeesTheFinalDraft() =
        runComposeUiTest {
            var uiState by mutableStateOf(GroupEditUiState(loaded = true, tiles = listOf(testLibraryTile(7))))
            var savedDraft: GroupEditDraft? = null
            setEditor(
                { uiState },
                onEdit = { uiState = uiState.copy(draft = it) },
                onToggleMember = { uiState = uiState.copy(draft = uiState.draft.toggle(it)) },
                onSave = { savedDraft = uiState.draft },
            )
            onNodeWithTag("groupName").performTextInput("News")
            onNodeWithTag("groupEditorGrid").performScrollToNode(hasTestTag("groupMember7"))
            onNodeWithTag("groupMember7").performClick()
            onNodeWithTag("groupEditorGrid").performScrollToNode(hasContentDescription("red"))
            onNodeWithContentDescription("red").performClick()
            onNodeWithText("Save").performClick()
            assertEquals(
                GroupEditDraft(name = "News", colorArgb = GroupPalette.COLORS[0], memberIds = setOf(7L)),
                savedDraft,
            )
        }

    @Test
    fun tabMovesFocusAndEnterSaves() =
        runComposeUiTest {
            var saved = false
            setEditor(onSave = { saved = true })
            onNodeWithTag("groupName").performClick()
            onNodeWithTag("groupName").assertIsFocused()
            onNodeWithTag("groupName").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("groupName").assertIsNotFocused()
            onNodeWithText("Save").requestFocus().assertIsFocused()
            onNodeWithText("Save").performKeyInput { pressKey(Key.Enter) }
            assertTrue(saved)
        }

    @Test
    fun escapeClosesTheEditor() =
        runComposeUiTest {
            var closed = 0
            setEditor(onClose = { closed++ })
            onNodeWithTag("groupName").performClick()
            onNodeWithTag("groupName").performKeyInput { pressKey(Key.Escape) }
            assertEquals(1, closed)
        }

    /** Mirrors the route's wiring; [state] is a lambda so harness writes recompose. */
    private fun ComposeUiTest.setEditor(
        state: () -> GroupEditUiState = { GroupEditUiState(loaded = true) },
        groupId: Long? = null,
        onEdit: (GroupEditDraft) -> Unit = {},
        onSearch: (String) -> Unit = {},
        onToggleMember: (Long) -> Unit = {},
        onSave: () -> Unit = {},
        onDelete: () -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        setContent {
            CompositionLocalProvider(LocalPlatformKind provides PlatformKind.DESKTOP) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    GroupEditScreen(
                        state(),
                        groupId,
                        onEdit,
                        onSearch,
                        onToggleMember,
                        onSave,
                        onDelete,
                        onClose,
                    )
                }
            }
        }
        waitForIdle()
    }
}

private fun GroupEditDraft.toggle(id: Long): GroupEditDraft =
    copy(memberIds = if (id in memberIds) memberIds - id else memberIds + id)
