// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover.add

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedCandidate
import ch.lkmc.neutrodyne.core.model.FeedPreview
import ch.lkmc.neutrodyne.core.testing.FakeAddPodcastResolver
import ch.lkmc.neutrodyne.core.testing.FakeSubscribeUseCase
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sheet's resolution pipeline (08 Add podcast sheet; 03 Add podcast flow) against
 * [FakeAddPodcastResolver]/[FakeSubscribeUseCase]: resolve → preview/choose/auth/failure steps,
 * then subscribe's success, dedupe and error branches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddPodcastViewModelTest : MainDispatcherTest() {
    private val resolver = FakeAddPodcastResolver()
    private val subscribe = FakeSubscribeUseCase()
    private val viewModel = AddPodcastViewModel(resolver, subscribe)

    @Test
    fun resolveShowsPreviewOnFeed() =
        runTest {
            resolver.nextResolution = AddResolution.Feed(Preview)
            viewModel.resolve(" https://example.com/feed ")
            advanceUntilIdle()

            assertIs<AddSheetStep.Preview>(viewModel.uiState.value.step)
            assertEquals(listOf("https://example.com/feed"), resolver.inputs)
        }

    @Test
    fun resolveBlankInputIsIgnored() =
        runTest {
            viewModel.resolve("   ")
            advanceUntilIdle()

            assertIs<AddSheetStep.Input>(viewModel.uiState.value.step)
            assertTrue(resolver.inputs.isEmpty())
        }

    @Test
    fun failureReturnsToInputWithError() =
        runTest {
            resolver.nextResolution = AddResolution.Failure(AddPodcastError.NotAFeed)
            viewModel.resolve("https://example.com")
            advanceUntilIdle()

            val step = assertIs<AddSheetStep.Input>(viewModel.uiState.value.step)
            assertTrue(step.error != null)
            assertTrue(!step.auth)
        }

    @Test
    fun authRequiredShowsCredentialFields() =
        runTest {
            resolver.nextResolution = AddResolution.Failure(AddPodcastError.AuthRequired(realm = null))
            viewModel.resolve("https://example.com/feed")
            advanceUntilIdle()

            assertTrue(assertIs<AddSheetStep.Input>(viewModel.uiState.value.step).auth)
        }

    @Test
    fun credentialRetryPassesThemThrough() =
        runTest {
            resolver.nextResolution = AddResolution.Failure(AddPodcastError.AuthRequired(realm = null))
            viewModel.resolve("https://example.com/feed")
            advanceUntilIdle()
            resolver.nextResolution = AddResolution.Feed(Preview)
            viewModel.resolveWithCredentials(BasicCredentials("u", "p"))
            advanceUntilIdle()

            assertEquals(
                listOf("https://example.com/feed" to BasicCredentials("u", "p")),
                resolver.credentialInputs,
            )
            assertIs<AddSheetStep.Preview>(viewModel.uiState.value.step)
        }

    @Test
    fun chooseListsCandidates() =
        runTest {
            val candidates = listOf(FeedCandidate("https://a/feed", "A", 10, "html"))
            resolver.nextResolution = AddResolution.Choose(candidates)
            viewModel.resolve("https://a")
            advanceUntilIdle()

            assertEquals(candidates, assertIs<AddSheetStep.Choosing>(viewModel.uiState.value.step).candidates)
        }

    @Test
    fun repeatResolveOfSettledInputIsSkipped() =
        runTest {
            resolver.nextResolution = AddResolution.Feed(Preview)
            viewModel.resolve("https://example.com/feed")
            advanceUntilIdle()
            viewModel.resolve("https://example.com/feed")
            advanceUntilIdle()

            assertEquals(1, resolver.inputs.size)
        }

    @Test
    fun inputChangeDropsThePreview() =
        runTest {
            resolver.nextResolution = AddResolution.Feed(Preview)
            viewModel.resolve("https://example.com/feed")
            advanceUntilIdle()
            viewModel.onInputChanged("https://other.example.com/feed")

            assertIs<AddSheetStep.Input>(viewModel.uiState.value.step)
            assertNull(assertIs<AddSheetStep.Input>(viewModel.uiState.value.step).error)
        }

    @Test
    fun subscribeReportsDone() =
        runTest {
            subscribe.nextOutcome = Outcome.Success(7L)
            resolver.nextResolution = AddResolution.Feed(Preview)
            viewModel.resolve("https://example.com/feed")
            advanceUntilIdle()

            viewModel.subscribe()
            advanceUntilIdle()

            assertEquals(listOf("preview-1" to emptySet<Long>()), subscribe.subscribed)
            assertEquals(SubscribedPodcast(7L, "A Show"), viewModel.uiState.value.done)
        }

    @Test
    fun subscribeDuplicateSurfacesAlreadySubscribed() =
        runTest {
            subscribe.nextOutcome = Outcome.Failure(SubscribeError.AlreadySubscribed(42L))
            resolver.nextResolution = AddResolution.Feed(Preview)
            viewModel.resolve("https://example.com/feed")
            advanceUntilIdle()
            viewModel.subscribe()
            advanceUntilIdle()

            val step = assertIs<AddSheetStep.Preview>(viewModel.uiState.value.step)
            assertEquals(42L, step.alreadySubscribedId)
            assertTrue(!step.subscribing)
            assertNull(viewModel.uiState.value.done)
        }

    @Test
    fun subscribeFailureShowsInlineError() =
        runTest {
            subscribe.nextOutcome = Outcome.Failure(SubscribeError.Storage)
            resolver.nextResolution = AddResolution.Feed(Preview)
            viewModel.resolve("https://example.com/feed")
            advanceUntilIdle()
            viewModel.subscribe()
            advanceUntilIdle()

            val step = assertIs<AddSheetStep.Preview>(viewModel.uiState.value.step)
            assertTrue(step.subscribeError != null)
            assertNull(viewModel.uiState.value.done)
        }

    @Test
    fun subscribeWithoutPreviewDoesNothing() =
        runTest {
            viewModel.subscribe()
            advanceUntilIdle()

            assertTrue(subscribe.subscribed.isEmpty())
        }

    @Test
    fun cancelResolveReturnsToInput() =
        runTest {
            resolver.nextResolution = AddResolution.Feed(Preview)
            viewModel.resolve("https://example.com/feed")
            viewModel.cancelResolve()

            assertIs<AddSheetStep.Input>(viewModel.uiState.value.step)
        }

    private companion object {
        val Preview =
            FeedPreview(
                previewId = "preview-1",
                feedUrl = "https://example.com/feed",
                title = "A Show",
                author = "An Author",
                description = null,
                artworkUrl = null,
                link = null,
                categories = emptyList(),
                language = "en",
                explicit = null,
                episodeCount = 3,
                latestEpisodeAt = 1_791_072_000_000L,
                episodes = emptyList(),
                hasOlderPages = false,
                isPrivate = false,
                alreadySubscribed = null,
                emptyFeed = false,
            )
    }
}
