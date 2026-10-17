// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import ch.lkmc.neutrodyne.core.navigation.GroupSettingsKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeScopeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionRequester
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
@Suppress("TooManyFunctions") // Keep the settings and entry-point journeys together.
class GroupSettingsScreenTest {
    private lateinit var previousLocale: Locale
    private val groups = FakeGroupRepository()
    private val scopes = FakeScopeSettingsRepository()
    private val navigator = RecordingAppNavigator()
    private val store = ViewModelStore()
    private val group by lazy {
        runBlocking { groups.create(GroupDraft("News")) }
        groups.groups.value.single()
    }

    @BeforeTest fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @AfterTest fun tearDown() {
        store.clear()
        Locale.setDefault(previousLocale)
    }

    @Test fun manualOnlyAndOwnAttributionWithoutOtherSettingsSections() =
        runComposeUiTest {
            val state = state(SettingOverrides(0, true))
            setPage { GroupSettingsScreen(state, {}, {}, {}) }
            onNodeWithText("Group settings · News").assertIsDisplayed()
            onNodeWithText("Manual only").assertIsDisplayed()
            onNode(isToggleable()).assertIsOn()
            onAllNodesWithText("From group 'News'").assertCountEquals(2)
            listOf("General", "Feed", "YouTube", "Episode order", "Hide older than").forEach {
                onNodeWithText(it).assertDoesNotExist()
            }
        }

    @Test fun refreshChooserWritesManualOnlyAndInheritPreviewsAppDefault() =
        runComposeUiTest {
            val picked = mutableListOf<Int?>()
            val state = state(SettingOverrides(120, null)).copy(inherited = SettingOverrides(0, false))
            setPage { GroupSettingsScreen(state, picked::add, {}, {}) }
            onNodeWithText("Refresh interval").performClick()
            onNodeWithText("Use default (Manual only, App default)").assertIsDisplayed()
            onNodeWithText("Manual only").performClick()
            assertEquals(listOf<Int?>(0), picked)
            onNodeWithText("Refresh interval").performClick()
            onNodeWithText("Use default (Manual only, App default)").performClick()
            assertEquals(listOf(0, null), picked)
        }

    @Test fun notificationSwitchAndChooserWriteOnOffAndInherit() =
        runComposeUiTest {
            val picked = mutableListOf<Boolean?>()
            val state = state().copy(inherited = SettingOverrides(60, true))
            setPage { GroupSettingsScreen(state, {}, picked::add, {}) }
            onNodeWithContentDescription("New episodes").assertIsOff().performClick()
            assertEquals(listOf<Boolean?>(true), picked)
            onNodeWithText("New episodes").performClick()
            onNodeWithText("Use default (On, App default)").performClick()
            onNodeWithText("New episodes").performClick()
            onNode(isSelectable() and hasText("Off")).performClick()
            assertEquals(listOf(true, null, false), picked)
        }

    @Test fun appDefaultAttributionAndDesktopPermissionControls() =
        runComposeUiTest {
            val state =
                state().copy(
                    scoped =
                        ScopedSettingsView(
                            SettingOverrides(),
                            SettingOverrides(null, true),
                            SettingField.entries.associateWith { SettingSource.AppDefault },
                        ),
                )
            setPage { GroupSettingsScreen(state, {}, {}, {}) }
            onNodeWithText("Manual only").assertIsDisplayed()
            onAllNodesWithText("App default").assertCountEquals(2)
            onNodeWithText("Notifications are blocked").assertDoesNotExist()
            onNodeWithText("Open settings").assertDoesNotExist()
        }

    @Test fun deniedPermissionKeepsSwitchOnAndOpensSettings() =
        runComposeUiTest {
            val requester = PermissionRequester()
            val state = state(SettingOverrides(null, true))
            setPage(requester) { GroupSettingsScreen(state, {}, {}, {}) }
            onNode(isToggleable()).assertIsOn()
            onNodeWithText("Notifications are blocked").assertIsDisplayed()
            onNodeWithText("Open settings").performClick()
            assertEquals(1, requester.opened)
        }

    @Test fun grantedPermissionHidesBlockedNotice() =
        runComposeUiTest {
            val requester = PermissionRequester().apply { granted = true }
            val state = state(SettingOverrides(null, true))
            setPage(requester) { GroupSettingsScreen(state, {}, {}, {}) }
            onNodeWithText("Notifications are blocked").assertDoesNotExist()
            onNodeWithText("Open settings").assertDoesNotExist()
        }

    @Test fun outOfRangeShowsInlineError() =
        runComposeUiTest {
            val state = state().copy(invalidFields = setOf(SettingField.REFRESH_INTERVAL_MINUTES))
            setPage { GroupSettingsScreen(state, {}, {}, {}) }
            onNodeWithText("Choose a supported value").assertIsDisplayed()
        }

    @Test fun deletedGroupPopsTheSettingsRoute() =
        runComposeUiTest {
            scopes.seedGroup(group.id, group.uuid, group.name)
            val vm = GroupSettingsViewModel(group.id, groups, scopes, FakeSettingsRepository())
            store.put("settings", vm)
            setPage { GroupSettingsRoute(GroupSettingsKey(group.id), vm) }
            onNodeWithText("Group settings · News").assertIsDisplayed()
            runOnIdle { scopes.removeGroup(group.id) }
            waitUntil { navigator.calls.contains(RecordingAppNavigator.Call.Pop) }
            assertEquals(listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Pop), navigator.calls)
        }

    @Test fun manageOverflowOpensGroupSettings() =
        runComposeUiTest {
            val id = group.id
            val vm = GroupsManageViewModel(groups)
            store.put("manage", vm)
            setPage { GroupsManageRoute(vm) }
            onNodeWithTag("groupMenu$id").performClick()
            onNodeWithText("Group settings").performClick()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PushDetail(GroupSettingsKey(id))),
                navigator.calls,
            )
        }

    @Test fun editorFooterOpensGroupSettings() =
        runComposeUiTest {
            val id = group.id
            val vm = GroupEditViewModel(id, SavedStateHandle(), groups, FakePodcastRepository())
            store.put("editor", vm)
            setPage { GroupEditRoute(GroupEditKey(id), vm) }
            onNodeWithTag("groupEditorGrid").performScrollToIndex(6)
            onNodeWithText("Group settings").performScrollTo().performClick()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PushDetail(GroupSettingsKey(id))),
                navigator.calls,
            )
        }

    private fun state(own: SettingOverrides = SettingOverrides()): GroupSettingsUiState =
        GroupSettingsUiState(
            group = group,
            scoped =
                ScopedSettingsView(
                    own,
                    SettingOverrides(
                        if (own.refreshIntervalMinutes == 0) null else own.refreshIntervalMinutes ?: 60,
                        own.notifyNewEpisodes ?: false,
                    ),
                    SettingField.entries.associateWith { field ->
                        val hasOwn =
                            when (field) {
                                SettingField.REFRESH_INTERVAL_MINUTES -> own.refreshIntervalMinutes != null
                                SettingField.NOTIFY_NEW_EPISODES -> own.notifyNewEpisodes != null
                            }
                        if (hasOwn) SettingSource.Group(group.uuid, group.name) else SettingSource.AppDefault
                    },
                ),
            inherited = SettingOverrides(60, false),
            loaded = true,
        )

    private fun ComposeUiTest.setPage(
        requester: NotificationPermissionRequester? = null,
        content: @Composable () -> Unit,
    ) {
        val owner =
            object : LifecycleOwner {
                override val lifecycle =
                    LifecycleRegistry
                        .createUnsafe(
                            this,
                        ).apply { currentState = Lifecycle.State.RESUMED }
            }
        val snackbar = SnackbarHostState()
        setContent {
            CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalPlatformActions provides TestPlatformActions(requester),
                LocalAppNavigator provides navigator,
                LocalSnackbarHost provides snackbar,
                LocalLifecycleOwner provides owner,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) { content() }
            }
        }
        waitForIdle()
    }

    private class PermissionRequester : NotificationPermissionRequester {
        override var granted = false
        var opened = 0

        override fun request(onResult: (Boolean) -> Unit) = onResult(granted)

        override fun openSettings() {
            opened++
        }
    }

    private class TestPlatformActions(
        override val notifications: NotificationPermissionRequester?,
    ) : PlatformActions {
        override val urls = ExternalUrlOpener { OpenResult.OPENED }
        override val share = null
        override val reveal = null
        override val saver = FileSaver { _, _ -> null }
        override val files =
            object : FilePicker {
                override suspend fun pickFile(
                    mimeTypes: List<String>,
                    extensions: List<String>,
                ): String? = null

                override suspend fun pickFolder(title: String): String? = null
            }
    }
}
