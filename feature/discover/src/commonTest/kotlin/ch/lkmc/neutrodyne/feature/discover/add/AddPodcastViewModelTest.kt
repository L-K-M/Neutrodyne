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
import kotlinx.coroutines.CompletableDeferred
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
            assertEquals(
                SubscribedPodcast(7L, "A Show", viewModel.uiState.value.operationGeneration),
                viewModel.uiState.value.done,
            )
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
    fun staleSubscribeFailureKeepsNewerPreview() =
        runTest {
            resolver.nextResolution = AddResolution.Feed(Preview)
            viewModel.resolve("https://a.example.com/feed")
            advanceUntilIdle()

            // Subscribe A parks at the gate; a newer resolve lands preview B meanwhile.
            subscribe.nextOutcome = Outcome.Failure(SubscribeError.Storage)
            subscribe.gate = CompletableDeferred()
            viewModel.subscribe()
            advanceUntilIdle()

            resolver.nextResolution = AddResolution.Feed(PreviewB)
            viewModel.resolve("https://b.example.com/feed")
            advanceUntilIdle()
            assertEquals(
                PreviewB.previewId,
                assertIs<AddSheetStep.Preview>(viewModel.uiState.value.step).preview.previewId,
            )

            // A's late failure belongs to A's preview and must not clobber B's step.
            subscribe.gate!!.complete(Unit)
            advanceUntilIdle()
            val step = assertIs<AddSheetStep.Preview>(viewModel.uiState.value.step)
            assertEquals(PreviewB.previewId, step.preview.previewId)
            assertNull(step.subscribeError)
            assertTrue(!step.subscribing)

            // The next Subscribe acts on B, not on the stale preview A.
            subscribe.nextOutcome = Outcome.Success(9L)
            viewModel.subscribe()
            advanceUntilIdle()
            assertEquals("preview-b", subscribe.subscribed.last().first)
        }

    @Test
    fun staleSubscribeSuccessDoesNotCloseNewerResolve() =
        runTest {
            resolver.nextResolution = AddResolution.Feed(Preview)
            viewModel.resolve("https://a.example.com/feed")
            advanceUntilIdle()

            // Subscribe A parks at its gate; the address is then edited and B resolved.
            val gate = CompletableDeferred<Unit>()
            subscribe.gate = gate
            subscribe.nextOutcome = Outcome.Success(7L)
            viewModel.subscribe()
            advanceUntilIdle()

            viewModel.onInputChanged("https://b.example.com/feed")
            resolver.nextResolution = AddResolution.Feed(PreviewB)
            viewModel.resolve("https://b.example.com/feed")
            advanceUntilIdle()
            assertEquals(
                PreviewB.previewId,
                assertIs<AddSheetStep.Preview>(viewModel.uiState.value.step).preview.previewId,
            )

            // A's late success belongs to A's operation: it must not set `done` — the route
            // would close B's sheet — nor disturb B's preview.
            gate.complete(Unit)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.done)
            assertEquals(
                PreviewB.previewId,
                assertIs<AddSheetStep.Preview>(viewModel.uiState.value.step).preview.previewId,
            )
        }

    @Test
    fun editingTheAddressInvalidatesAnInFlightSubscribe() =
        runTest {
            resolver.nextResolution = AddResolution.Feed(Preview)
            viewModel.resolve("https://a.example.com/feed")
            advanceUntilIdle()

            // Subscribe A parks at its gate; the field is then edited to B without
            // submitting — the interval review round 3 identified.
            val gate = CompletableDeferred<Unit>()
            subscribe.gate = gate
            subscribe.nextOutcome = Outcome.Success(7L)
            viewModel.subscribe()
            advanceUntilIdle()

            viewModel.onInputChanged("https://b.example.com/feed")

            // A's late success belongs to A's operation: `done` must not land and close a
            // sheet whose field now holds B.
            gate.complete(Unit)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.done)
            assertIs<AddSheetStep.Input>(viewModel.uiState.value.step)
        }

    @Test
    fun staleSubscribeFailureKeepsNewerAttemptSubscribing() =
        runTest {
            resolver.nextResolution = AddResolution.Feed(Preview)
            viewModel.resolve("https://a.example.com/feed")
            advanceUntilIdle()

            // Attempt 1 parks; the same feed is re-resolved and subscribed again. PreviewCache
            // keys previewId on the feed URL, so identical previewIds cannot tell the attempts
            // apart — only the operation generation can.
            val first = CompletableDeferred<Unit>()
            subscribe.gates += first
            subscribe.outcomes += Outcome.Failure(SubscribeError.Storage)
            viewModel.subscribe()
            advanceUntilIdle()

            viewModel.onInputChanged("")
            viewModel.resolve("https://a.example.com/feed")
            advanceUntilIdle()

            val second = CompletableDeferred<Unit>()
            subscribe.gates += second
            subscribe.outcomes += Outcome.Success(9L)
            viewModel.subscribe()
            advanceUntilIdle()

            // Attempt 1's late failure must not clear attempt 2's subscribing flag.
            first.complete(Unit)
            advanceUntilIdle()
            val step = assertIs<AddSheetStep.Preview>(viewModel.uiState.value.step)
            assertTrue(step.subscribing)
            assertNull(step.subscribeError)

            second.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                9L,
                viewModel.uiState.value.done
                    ?.podcastId,
            )
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
                feedUrl = "https://a.example.com/feed",
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

        val PreviewB =
            Preview.copy(previewId = "preview-b", feedUrl = "https://b.example.com/feed", title = "B Show")
    }
}
