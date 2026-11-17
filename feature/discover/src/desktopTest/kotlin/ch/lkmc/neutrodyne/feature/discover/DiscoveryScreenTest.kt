// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.DirectoryKey
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastPreviewKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeSearchRepository
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class DiscoveryScreenTest {
    private val navigator = RecordingAppNavigator()
    private lateinit var locale: Locale

    @BeforeTest fun setup() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @AfterTest fun cleanup() {
        Locale.setDefault(locale)
    }

    @Test
    fun changedRecipientsWaitForTheUpdatedDisclosureFrame() =
        runComposeUiTest {
            val dispatcher = StandardTestDispatcher()
            Dispatchers.setMain(dispatcher)
            val repository = FakeSearchRepository()
            repository.providers.value =
                listOf(ProviderInfo(ProviderId.APPLE, true), ProviderInfo(ProviderId.FYYD, false)).toImmutableList()
            val vm = DiscoverViewModel(repository, FakeGroupRepository())
            try {
                dispatcher.scheduler.advanceUntilIdle()
                setContent {
                    CompositionLocalProvider(LocalAppNavigator provides navigator) {
                        NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                            DiscoverRoute(vm)
                        }
                    }
                }
                waitForIdle()
                onNodeWithText("Searches are sent to Apple").assertIsDisplayed()
                onNode(hasSetTextAction()).performTextInput("news")
                runOnIdle { dispatcher.scheduler.advanceUntilIdle() }
                assertEquals(listOf("news"), repository.queries)
                mainClock.autoAdvance = false
                runOnIdle {
                    repository.providers.value =
                        repository.providers.value
                            .map { it.copy(enabled = true) }
                            .toImmutableList()
                    dispatcher.scheduler.advanceUntilIdle()
                }
                // Provider collection and search work drain while Compose cannot draw new recipients.
                assertEquals(listOf("news"), repository.queries)
                mainClock.advanceTimeByFrame()
                waitForIdle()
                mainClock.advanceTimeByFrame()
                waitForIdle()
                onNodeWithText("Searches are sent to Apple, fyyd").assertIsDisplayed()
                runOnIdle { dispatcher.scheduler.advanceUntilIdle() }
                assertEquals(listOf("news", "news"), repository.queries)
            } finally {
                vm.viewModelScope.cancel()
                Dispatchers.resetMain()
                mainClock.autoAdvance = true
            }
        }

    @Test
    fun unknownProvidersDoNotClaimDirectoriesAreDisabled() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        DiscoverScreen(DiscoverState(), {}, {}, navigator)
                    }
                }
            }
            onNodeWithText("Enable a directory in Discover settings").assertDoesNotExist()
            onNodeWithText("Searches are sent to", substring = true).assertDoesNotExist()
        }

    @Test
    fun enterAndImeOpenFullDirectoryEvenForShortQuery() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        DiscoverScreen(DiscoverState(input = "AI"), {}, {}, navigator)
                    }
                }
            }
            onNode(hasSetTextAction()).performClick().performKeyInput { pressKey(Key.Enter) }
            onNode(hasSetTextAction()).performImeAction()
            assertEquals<List<RecordingAppNavigator.Call>>(
                List(2) { RecordingAppNavigator.Call.PushDetail(DirectoryKey("AI", null)) },
                navigator.calls,
            )
        }

    @Test
    fun linkAndImportChipsRouteToExistingEntryPoints() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        DiscoverScreen(DiscoverState(input = "https://private.test/feed"), {}, {}, navigator)
                    }
                }
            }
            onNodeWithText("Add this link").performClick()
            onNodeWithText("Import").performClick()
            assertEquals<List<RecordingAppNavigator.Call>>(
                listOf(
                    RecordingAppNavigator.Call.Push(AddPodcastKey("https://private.test/feed")),
                    RecordingAppNavigator.Call.Push(ImportFileKey),
                ),
                navigator.calls,
            )
        }

    @Test
    fun subscribedHitsOpenLibraryDetailAndOthersOpenMemoryPreview() =
        runComposeUiTest {
            val hits = persistentListOf(hit("Subscribed show", 7L), hit("New show"))
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        DirectoryScreen(
                            DirectoryKey("news", null),
                            DirectoryState(loading = false, hits = hits),
                            {},
                            navigator,
                        )
                    }
                }
            }
            onNodeWithText("Subscribed show").performClick()
            onNodeWithText("New show").performClick()
            assertEquals<List<RecordingAppNavigator.Call>>(
                listOf(
                    RecordingAppNavigator.Call.PushDetail(PodcastKey(7)),
                    RecordingAppNavigator.Call.PushDetail(PodcastPreviewKey("https://example.test/New show")),
                ),
                navigator.calls,
            )
        }

    @Test
    fun repeatedFeedUrlsListEveryHit() =
        runComposeUiTest {
            // Apple's charts can list one feed under two shows; a repeated lazy-list key crashes.
            val hits =
                persistentListOf(
                    hit("Listing A").copy(feedUrl = SHARED_FEED),
                    hit("Listing B").copy(feedUrl = SHARED_FEED),
                )
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        DirectoryScreen(
                            DirectoryKey("", null),
                            DirectoryState(loading = false, hits = hits),
                            {},
                            navigator,
                        )
                    }
                }
            }
            onNodeWithText("Listing A").assertIsDisplayed()
            onNodeWithText("Listing B").assertIsDisplayed()
        }

    @Test
    fun repeatedFeedUrlsInTheTunedChartListEveryHit() =
        runComposeUiTest {
            val hits =
                listOf(hit("Listing A").copy(feedUrl = SHARED_FEED), hit("Listing B").copy(feedUrl = SHARED_FEED))
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        DiscoverScreen(DiscoverState(chart = TunedChart(null, hits)), {}, {}, navigator)
                    }
                }
            }
            onNodeWithText("Listing A").performScrollTo().assertIsDisplayed()
            onNodeWithText("Listing B").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun addByUrlWaitsUntilTheDirectoryHasAnswered() =
        runComposeUiTest {
            var state by mutableStateOf(DirectoryState(loading = true))
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        DirectoryScreen(DirectoryKey("", null), state, {}, navigator)
                    }
                }
            }
            onNodeWithText(ADD_BY_URL).assertDoesNotExist()

            state = DirectoryState(loading = false)
            onNodeWithText(ADD_BY_URL).assertIsDisplayed()

            state = DirectoryState(loading = false, chartError = ProviderStatus.RateLimited)
            onNodeWithText(ADD_BY_URL).assertIsDisplayed()
        }

    @Test
    fun explicitChartFailureOffersRetry() =
        runComposeUiTest {
            var retries = 0
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        DirectoryScreen(
                            DirectoryKey(
                                "",
                                null,
                            ),
                            DirectoryState(loading = false, chartError = ProviderStatus.RateLimited),
                            {
                                retries++
                            },
                            navigator,
                        )
                    }
                }
            }
            onNodeWithText("Charts unavailable").assertIsDisplayed()
            onNodeWithText("Retry").performClick()
            assertEquals(1, retries)
        }

    @Test
    fun disclosureListsEffectiveRecipientsAndPartialFailureIsVisible() =
        runComposeUiTest {
            val result =
                SearchResults(
                    "news",
                    persistentListOf(hit("A show")),
                    persistentMapOf(
                        ProviderId.APPLE to ProviderStatus.RateLimited,
                        ProviderId.FYYD to ProviderStatus.Ok,
                    ),
                    true,
                )
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        DiscoverScreen(
                            DiscoverState(
                                input = "news",
                                results = result,
                                providers =
                                    listOf(
                                        ProviderInfo(ProviderId.APPLE, true),
                                        ProviderInfo(ProviderId.FYYD, true),
                                        ProviderInfo(ProviderId.PODCAST_INDEX, true),
                                    ),
                            ),
                            {},
                            {},
                            navigator,
                        )
                    }
                }
            }
            onNodeWithText("Searches are sent to Apple, fyyd").assertIsDisplayed()
            onNodeWithText("Apple is busy; showing available results").assertIsDisplayed()
            onNodeWithText("A show").assertIsDisplayed()
        }

    @Test
    fun offlineAndDisabledStatesHaveActionableText() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        SearchStatus(
                            SearchResults(
                                "news",
                                persistentListOf(),
                                persistentMapOf(ProviderId.APPLE to ProviderStatus.Failed(NetError.Offline)),
                                true,
                            ),
                            {},
                            {},
                        )
                    }
                }
            }
            onNodeWithText("You're offline").assertIsDisplayed()
            onNodeWithText("Retry").assertIsDisplayed()
        }

    @Test
    fun stationsAndPresetsTuneTheDialAndTheTrayShowsTheTunedChart() =
        runComposeUiTest {
            val tuned = mutableListOf<ChartGenre?>()
            val visible = mutableListOf<ChartGenre?>()
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        DiscoverScreen(
                            DiscoverState(
                                presets = listOf(null, ChartGenre.TECHNOLOGY),
                                chart = TunedChart(null, (1..12).map { hit("Chart show $it") }),
                            ),
                            {},
                            {},
                            navigator,
                            onChartVisible = { visible += it },
                            onTune = { tuned += it },
                        )
                    }
                }
            }
            assertEquals(listOf<ChartGenre?>(null), visible)
            onNodeWithTag("station:").assertIsSelected()
            onNodeWithTag("station:${ChartGenre.HISTORY.id}").assertIsNotSelected().performScrollTo().performClick()
            onNodeWithTag("preset:${ChartGenre.TECHNOLOGY.id}").performClick()
            assertEquals(listOf<ChartGenre?>(ChartGenre.HISTORY, ChartGenre.TECHNOLOGY), tuned)
            onNodeWithText("Top podcasts").assertIsDisplayed()
            onNodeWithText("Chart show 11").assertDoesNotExist()
            onNodeWithText("More").performClick()
            onNodeWithText("Chart show 3").performScrollTo().performClick()
            assertEquals<List<RecordingAppNavigator.Call>>(
                listOf(
                    RecordingAppNavigator.Call.PushDetail(DirectoryKey("", null)),
                    RecordingAppNavigator.Call.PushDetail(PodcastPreviewKey("https://example.test/Chart show 3")),
                ),
                navigator.calls,
            )
        }

    @Test
    fun aFailedChartOffersRetryAndChartsLeaveWithApple() =
        runComposeUiTest {
            var state by mutableStateOf(
                DiscoverState(chart = TunedChart(ChartGenre.NEWS, failure = ProviderStatus.RateLimited)),
            )
            var retried = 0
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        DiscoverScreen(state, {}, {}, navigator, onChartRetry = { retried++ })
                    }
                }
            }
            onNodeWithText("Popular in News").assertIsDisplayed()
            onNodeWithText("Charts unavailable").assertIsDisplayed()
            onNodeWithText("Retry").performClick()
            assertEquals(1, retried)
            state = DiscoverState(providers = listOf(ProviderInfo(ProviderId.APPLE, false)))
            waitForIdle()
            onNodeWithTag("station:").assertDoesNotExist()
            onNodeWithText("Top podcasts").assertDoesNotExist()
            onNodeWithText(CHARTS_OFF).assertIsDisplayed()
            onNodeWithText("Settings").performClick()
            assertEquals<List<RecordingAppNavigator.Call>>(
                listOf(RecordingAppNavigator.Call.PushDetail(SettingsKey(SettingsPage.DISCOVER))),
                navigator.calls,
            )
            state = DiscoverState(chart = TunedChart(failure = ProviderStatus.Disabled))
            waitForIdle()
            onNodeWithTag("station:").assertDoesNotExist()
            onNodeWithText(CHARTS_OFF).assertIsDisplayed()
            state = DiscoverState(providers = listOf(ProviderInfo(ProviderId.APPLE, true)), input = "news")
            waitForIdle()
            onNodeWithText(CHARTS_OFF).assertDoesNotExist()
            // With Apple on, and while the providers are still unknown, a blank search keeps the tray.
            for (providers in listOf(listOf(ProviderInfo(ProviderId.APPLE, true)), null)) {
                state = DiscoverState(providers = providers)
                waitForIdle()
                onNodeWithText(CHARTS_OFF).assertDoesNotExist()
                onNodeWithText("Top podcasts").assertExists()
            }
        }

    private fun hit(
        title: String,
        subscribed: Long? = null,
    ) = DirectoryHit(
        "https://example.test/$title",
        title,
        providers = persistentSetOf(ProviderId.APPLE),
        subscribedPodcastId = subscribed,
    )

    private companion object {
        const val SHARED_FEED = "https://example.test/shared"
        const val ADD_BY_URL = "Add by URL"
        const val CHARTS_OFF = "Charts come from Apple, which is switched off in Discover settings"
    }
}
