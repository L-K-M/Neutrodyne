// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
// Screen journeys share four named group IDs.
@Suppress("TooManyFunctions", "DestructuringDeclarationWithTooManyEntries")
class GroupsManageScreenTest {
    private lateinit var previousLocale: Locale
    private val groups = FakeGroupRepository()
    private val calls = mutableListOf<List<Long>>()
    private val repository =
        object : GroupRepository by groups {
            override suspend fun reorder(groupIdsInOrder: List<Long>): Outcome<Unit, GroupError> {
                calls += groupIdsInOrder.toList()
                return groups.reorder(groupIdsInOrder)
            }
        }
    private val store = ViewModelStore()
    private lateinit var vm: GroupsManageViewModel

    @BeforeTest fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @AfterTest fun tearDown() {
        store.clear()
        Locale.setDefault(previousLocale)
    }

    @Test fun mouseDragPreviewsAndCommitsOnceOnRelease() =
        runComposeUiTest {
            val (a, b, c, d) = seed()
            setManager()
            val start = onNodeWithTag("groupDrag$a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
            val end = onNodeWithTag("groupRow$d").fetchSemanticsNode().boundsInRoot.center
            onRoot().performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, 20f), delayMillis = 50)
            }
            waitForIdle()
            onRoot().performMouseInput { moveTo(Offset(start.x, end.y), delayMillis = 500) }
            waitUntil {
                vm.uiState.value.groups
                    .map { it.id } == listOf(b, c, d, a)
            }
            assertTrue(calls.isEmpty())
            onRoot().performMouseInput { release() }
            waitUntil { calls.size == 1 && !vm.uiState.value.busy }
            assertEquals(listOf(listOf(b, c, d, a)), calls)
            assertTrue(
                onNodeWithTag("groupRow$a").fetchSemanticsNode().boundsInRoot.top >
                    onNodeWithTag("groupRow$d").fetchSemanticsNode().boundsInRoot.top,
            )
        }

    @Test fun releaseImmediatelyAfterTheLastMouseMotionKeepsTheDrop() =
        runComposeUiTest {
            val ids = seed()
            val a = ids.first()
            val d = ids.last()
            setManager()
            val start = onNodeWithTag("groupDrag$a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
            val end = onNodeWithTag("groupRow$d").fetchSemanticsNode().boundsInRoot.center
            onRoot().performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, 20f), delayMillis = 50)
            }
            waitForIdle()
            onRoot().performMouseInput {
                moveTo(Offset(start.x, end.y), delayMillis = 16)
                release()
            }
            waitForIdle()
            assertEquals(listOf(ids.drop(1) + a), calls)
        }

    @Test fun disposingTheDraggedHandleCancelsWithoutAWrite() =
        runComposeUiTest {
            val ids = seed(count = 50)
            val a = ids.first()
            setManager()
            val start = onNodeWithTag("groupDrag$a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
            onRoot().performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, 20f), delayMillis = 50)
            }
            waitUntil { vm.uiState.value.draggingId == a }
            onNodeWithTag("groupsManageList").performScrollToIndex(49)
            // Move focus into the visible list so off-screen items can leave composition.
            onNodeWithTag("groupRow${ids.last()}").requestFocus()
            waitUntil { vm.uiState.value.draggingId == null }
            onNodeWithTag("groupDrag$a", useUnmergedTree = true).assertDoesNotExist()
            onRoot().performMouseInput { release() }
            assertTrue(calls.isEmpty())
        }

    @Test fun touchDragSupportsEscapeCancellationAndDrop() =
        runComposeUiTest {
            val ids = seed()
            val a = ids.first()
            val d = ids.last()
            setManager()
            val start = onNodeWithTag("groupDrag$a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
            val end = onNodeWithTag("groupRow$d").fetchSemanticsNode().boundsInRoot.center
            onRoot().performTouchInput {
                down(start)
                moveTo(start + Offset(0f, 20f), delayMillis = 50)
            }
            waitForIdle()
            onRoot().performTouchInput { moveTo(Offset(start.x, end.y), delayMillis = 500) }
            waitUntil {
                vm.uiState.value.groups
                    .map { it.id } != ids
            }
            onNodeWithTag("groupRow$a").requestFocus()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            onRoot().performTouchInput { up() }
            waitForIdle()
            assertEquals(
                ids,
                vm.uiState.value.groups
                    .map { it.id },
            )
            assertTrue(calls.isEmpty())
            onRoot().performTouchInput {
                down(start)
                moveTo(start + Offset(0f, 20f), delayMillis = 50)
            }
            waitForIdle()
            onRoot().performTouchInput { moveTo(Offset(start.x, end.y), delayMillis = 500) }
            waitUntil {
                vm.uiState.value.groups
                    .map { it.id } != ids
            }
            onRoot().performTouchInput { up() }
            waitUntil { calls.size == 1 && !vm.uiState.value.busy }
            assertEquals(ids.drop(1) + a, calls.single())
        }

    @Test fun accessibilityMovesAndBoundaryActionsUseTheSameWrite() =
        runComposeUiTest {
            val (a, b, c, d) = seed()
            setManager()
            onNodeWithTag("groupRow$c").performCustomAccessibilityActionWithLabel("Move up")
            waitUntil { calls.size == 1 && !vm.uiState.value.busy }
            assertEquals(listOf(a, c, b, d), calls.last())
            onNodeWithTag("groupRow$c").performCustomAccessibilityActionWithLabel("Move down")
            waitUntil { calls.size == 2 && !vm.uiState.value.busy }
            assertEquals(listOf(a, b, c, d), calls.last())
            onNodeWithTag("groupRow$d").performCustomAccessibilityActionWithLabel("Move to top")
            waitUntil { calls.size == 3 && !vm.uiState.value.busy }
            assertEquals(listOf(d, a, b, c), calls.last())
        }

    @Test fun altArrowsMoveTheFocusedRow() =
        runComposeUiTest {
            val (a, b, c, d) = seed()
            setManager()
            onNodeWithTag("groupRow$b").requestFocus()
            onNodeWithTag("groupRow$b").performKeyInput {
                keyDown(Key.AltLeft)
                pressKey(Key.DirectionUp)
                keyUp(Key.AltLeft)
            }
            waitUntil { calls.size == 1 && !vm.uiState.value.busy }
            assertEquals(listOf(b, a, c, d), calls.last())
            onNodeWithTag("groupRow$b").performKeyInput {
                keyDown(Key.AltLeft)
                pressKey(Key.DirectionDown)
                keyUp(Key.AltLeft)
            }
            waitUntil { calls.size == 2 && !vm.uiState.value.busy }
            assertEquals(listOf(a, b, c, d), calls.last())
        }

    @Test fun tapOpensEditorAndNewGroupOpensCreate() =
        runComposeUiTest {
            val (a) = seed()
            setManager()
            assertFalse(
                onNodeWithTag(
                    "groupDrag$a",
                    useUnmergedTree = true,
                ).fetchSemanticsNode().config.contains(SemanticsActions.OnClick),
            )
            onNodeWithTag("groupRow$a").performClick()
            assertEquals(GroupEditKey(a), vm.consumeNavigation())
            onNodeWithText("New group", useUnmergedTree = true).performClick()
            assertEquals(GroupEditKey(null), vm.consumeNavigation())
        }

    @Test fun tabSwitchUpdatesVisibilityAndOverflowCanDelete() =
        runComposeUiTest {
            val (a) = seed()
            setManager()
            onNodeWithTag("groupTab$a").assertIsOn().performClick()
            waitUntil {
                !vm.uiState.value.busy &&
                    !vm.uiState.value.groups
                        .first()
                        .showAsTab
            }
            onNodeWithTag("groupTab$a").assertIsOff()
            onNodeWithText("Not shown as tab", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("groupMenu$a").performClick()
            onNodeWithText("Delete group").performClick()
            waitUntil {
                !vm.uiState.value.busy &&
                    vm.uiState.value.groups
                        .none { it.id == a }
            }
            assertEquals("One", vm.consumeDeleted()!!.name)
        }

    @Test fun contextMenuOffersMoveActions() =
        runComposeUiTest {
            val (a, b, c, d) = seed()
            setManager()
            onNodeWithTag("groupRow$d").performMouseInput {
                click(center, button = androidx.compose.ui.test.MouseButton.Secondary)
            }
            onNodeWithText("Move to top").performClick()
            waitUntil { calls.size == 1 && !vm.uiState.value.busy }
            assertEquals(listOf(d, a, b, c), calls.last())
        }

    @Test fun emptyStateCreatesTheFirstGroup() =
        runComposeUiTest {
            setManager()
            onNodeWithText("No groups yet").assertIsDisplayed()
            onNodeWithText("New group", useUnmergedTree = true).performClick()
            assertEquals(GroupEditKey(null), vm.consumeNavigation())
        }

    @Test fun undoIsOfferedBeforeAnotherGroupCanBeDeleted() =
        runComposeUiTest {
            val (a, b) = seed()
            val host = SnackbarHostState()
            val navigator = RecordingAppNavigator()
            val owner =
                object : LifecycleOwner {
                    override val lifecycle =
                        LifecycleRegistry
                            .createUnsafe(
                                this,
                            ).apply { currentState = Lifecycle.State.RESUMED }
                }
            setManager {
                CompositionLocalProvider(
                    LocalSnackbarHost provides host,
                    LocalAppNavigator provides navigator,
                    LocalLifecycleOwner provides owner,
                ) {
                    GroupsManageRoute(vm)
                    SnackbarHost(host)
                }
            }
            runOnIdle { vm.delete(a) }
            waitUntil { host.currentSnackbarData != null && !vm.uiState.value.busy }
            runOnIdle { vm.delete(b) }
            waitForIdle()
            assertTrue(
                vm.uiState.value.groups
                    .any { it.id == b },
            )
            onNodeWithTag("groupMenu$b").performClick()
            onNodeWithText("Delete group").assertIsNotEnabled()
            runOnIdle { host.currentSnackbarData!!.dismiss() }
            waitUntil { vm.uiState.value.deleted == null }
            onNodeWithText("Delete group").performClick()
            waitUntil { host.currentSnackbarData != null && !vm.uiState.value.busy }
            runOnIdle { host.currentSnackbarData!!.performAction() }
            waitUntil {
                vm.uiState.value.deleted == null &&
                    vm.uiState.value.groups
                        .any { it.id == b }
            }
        }

    private fun seed(count: Int = 4): List<Long> =
        runBlocking {
            List(count) { listOf("One", "Two", "Three", "Four").getOrElse(it) { "Group $it" } }.map {
                assertIs<Outcome.Success<Long>>(groups.create(GroupDraft(it))).value
            }
        }

    private fun ComposeUiTest.setManager(
        content: @Composable () -> Unit = {
            val state by vm.uiState.collectAsState()
            GroupsManageScreen(
                state,
                vm::startDrag,
                vm::dragOver,
                vm::drop,
                vm::cancelDrag,
                vm::move,
                vm::showAsTab,
                vm::openEditor,
                { vm.openEditor() },
                vm::delete,
                {},
                vm::retry,
            )
        },
    ) {
        vm = GroupsManageViewModel(repository)
        store.put("manage", vm)
        setContent {
            CompositionLocalProvider(LocalPlatformKind provides PlatformKind.DESKTOP) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) { content() }
            }
        }
        waitUntil { vm.uiState.value.loaded }
        waitForIdle()
    }
}
