// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdScrollbarStyle
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
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
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
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
    fun chartRowsScrollToOffscreenTiles() =
        runComposeUiTest {
            var thumb = Color.Transparent
            setContent {
                CompositionLocalProvider(
                    LocalAppNavigator provides navigator,
                    LocalScrollbars provides NdScrollbarStyle(enabled = true, alpha = 1f),
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        thumb = MaterialTheme.colorScheme.onSurface
                        DiscoverScreen(
                            DiscoverState(
                                charts =
                                    listOf(
                                        ChartRow(null, null, (1..15).map { hit("Chart show $it") }),
                                    ),
                            ),
                            {},
                            {},
                            navigator,
                        )
                    }
                }
            }
            val before = onNodeWithTag("chart-row:").captureToImage().toPixelMap()
            assertEquals(thumb.toArgb(), before[before.width / 4, before.height - 4].toArgb())
            assertNotEquals(thumb.toArgb(), before[before.width * 3 / 4, before.height - 4].toArgb())
            onNodeWithTag("chart-row:").performScrollToNode(hasText("Chart show 15"))
            onNodeWithText("Chart show 15").assertIsDisplayed()
            val after = onNodeWithTag("chart-row:").captureToImage().toPixelMap()
            assertNotEquals(thumb.toArgb(), after[after.width / 4, after.height - 4].toArgb())
            assertEquals(thumb.toArgb(), after[after.width * 3 / 4, after.height - 4].toArgb())
            onNodeWithText("Chart show 15").performClick()
            assertEquals<List<RecordingAppNavigator.Call>>(
                listOf(RecordingAppNavigator.Call.PushDetail(PodcastPreviewKey("https://example.test/Chart show 15"))),
                navigator.calls,
            )
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
}
