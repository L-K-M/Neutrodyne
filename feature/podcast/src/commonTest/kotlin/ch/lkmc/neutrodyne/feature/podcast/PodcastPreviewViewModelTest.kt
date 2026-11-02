// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.podcast

import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.model.AlreadySubscribed
import ch.lkmc.neutrodyne.core.model.FeedPreview
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.testing.FakeAddPodcastResolver
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeSubscribeUseCase
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastPreviewViewModelTest : MainDispatcherTest() {
    private val resolver = FakeAddPodcastResolver()
    private val subscribe = FakeSubscribeUseCase()
    private val groups = FakeGroupRepository()

    private fun model() = PodcastPreviewViewModel(PREVIEW.feedUrl, resolver, subscribe, groups)

    @Test
    fun previewIsReadOnlyUntilConfirmationAndPassesGroupIds() =
        runTest {
            resolver.nextPreview = Outcome.Success(PREVIEW)
            val vm = model()
            advanceUntilIdle()
            assertEquals(listOf("preview:${PREVIEW.feedUrl}"), resolver.inputs)
            assertEquals(PREVIEW, vm.state.value.preview)
            assertTrue(subscribe.subscribed.isEmpty())
            vm.subscribe(setOf(2, 4))
            advanceUntilIdle()
            assertEquals(listOf(PREVIEW.previewId to setOf(2L, 4L)), subscribe.subscribed)
            assertNotNull(vm.consumeDone())
            assertNull(vm.consumeDone())
            vm.viewModelScope.cancel()
        }

    @Test
    fun recreatedViewModelFetchesMemoryOnlyPreviewAgain() =
        runTest {
            resolver.nextPreview = Outcome.Success(PREVIEW)
            val first = model()
            advanceUntilIdle()
            first.viewModelScope.cancel()
            val restored = model()
            advanceUntilIdle()
            assertEquals(2, resolver.inputs.size)
            assertTrue(subscribe.subscribed.isEmpty())
            restored.viewModelScope.cancel()
        }

    @Test
    fun fetchAndSubscribeFailuresAreExplicitAndRetryable() =
        runTest {
            resolver.nextPreview = Outcome.Failure(AddPodcastError.NoMedia)
            val vm = model()
            advanceUntilIdle()
            assertEquals(AddPodcastError.NoMedia, vm.state.value.fetchError)
            vm.subscribe(emptySet())
            assertTrue(subscribe.subscribed.isEmpty())
            resolver.nextPreview = Outcome.Success(PREVIEW)
            vm.retry()
            advanceUntilIdle()
            subscribe.nextOutcome = Outcome.Failure(SubscribeError.Storage)
            vm.subscribe(emptySet())
            advanceUntilIdle()
            assertEquals(SubscribeError.Storage, vm.state.value.subscribeError)
            assertNull(vm.state.value.doneId)
            vm.viewModelScope.cancel()
        }

    @Test
    fun exactDuplicatesOpenWithoutSubscribingAndRaceDuplicateStaysOpenable() =
        runTest {
            resolver.nextPreview = Outcome.Success(PREVIEW.copy(alreadySubscribed = AlreadySubscribed(7, true)))
            val vm = model()
            advanceUntilIdle()
            vm.subscribe(emptySet())
            assertTrue(subscribe.subscribed.isEmpty())
            assertEquals(7L, vm.state.value.openId)
            vm.viewModelScope.cancel()
            resolver.nextPreview = Outcome.Success(PREVIEW)
            subscribe.nextOutcome = Outcome.Failure(SubscribeError.AlreadySubscribed(9))
            val race = model()
            advanceUntilIdle()
            race.subscribe(emptySet())
            advanceUntilIdle()
            assertEquals(9L, race.state.value.openId)
            assertNull(race.state.value.subscribeError)
            assertNull(race.state.value.doneId)
            race.viewModelScope.cancel()
        }

    @Test
    fun failedGroupWriteReturnsToAnActionableState() =
        runTest {
            val failing =
                object : GroupRepository by groups {
                    override suspend fun create(
                        draft: GroupDraft,
                        memberIds: Set<Long>,
                    ): Outcome<Long, GroupError> {
                        error("Synthetic storage failure")
                    }
                }
            resolver.nextPreview = Outcome.Success(PREVIEW)
            val vm = PodcastPreviewViewModel(PREVIEW.feedUrl, resolver, subscribe, failing)
            advanceUntilIdle()
            vm.createGroup("Tech")
            advanceUntilIdle()
            assertNotNull(vm.state.value.groupError)
            assertTrue(!vm.state.value.creatingGroup)
            assertNull(vm.state.value.createdGroupId)
            assertTrue(groups.groups.value.isEmpty())
            vm.viewModelScope.cancel()
        }

    @Test
    fun inlineGroupsUseRepositoryValidationAndCanBeSelectedImmediately() =
        runTest {
            resolver.nextPreview = Outcome.Success(PREVIEW)
            val vm = model()
            advanceUntilIdle()
            vm.createGroup(" ")
            advanceUntilIdle()
            assertNotNull(vm.state.value.groupError)
            vm.createGroup("Tech")
            advanceUntilIdle()
            val id = assertNotNull(vm.state.value.createdGroupId)
            vm.subscribe(setOf(id))
            advanceUntilIdle()
            assertEquals(setOf(id), subscribe.subscribed.single().second)
            vm.viewModelScope.cancel()
        }
}

internal val PREVIEW =
    FeedPreview(
        previewId = "preview",
        feedUrl = "https://example.test/feed",
        title = "Example show",
        author = "Publisher",
        description = null,
        artworkUrl = null,
        link = null,
        categories = emptyList(),
        language = "en",
        explicit = false,
        episodeCount = 0,
        latestEpisodeAt = null,
        episodes = emptyList(),
        hasOlderPages = false,
        isPrivate = false,
        alreadySubscribed = null,
        emptyFeed = true,
    )
