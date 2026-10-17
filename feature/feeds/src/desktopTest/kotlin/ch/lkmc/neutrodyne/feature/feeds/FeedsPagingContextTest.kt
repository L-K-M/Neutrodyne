// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.lifecycle.ViewModelStore
import androidx.paging.testing.asSnapshot
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FeedsPagingContextTest : MainDispatcherTest() {
    @Test
    fun deferredMapAndDaySeparatorsUseDefaultDispatcher() =
        runTest {
            val contexts = mutableMapOf<Callback, MutableList<CoroutineContext>>()
            val defaultDispatcher =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        // Capture the caller of the context switch: an outer flowOn has no
                        // deferred Paging callback on its stack, so it cannot satisfy this test.
                        val stack = Thread.currentThread().stackTrace
                        val callback =
                            when {
                                stack.any { it.className.contains("FeedsUiStateKt\$withDayHeaders\$1") } -> Callback.MAP

                                stack.any {
                                    it.className.contains(
                                        "FeedsUiStateKt\$withDayHeaders\$2",
                                    )
                                } -> Callback.DAY_SEPARATOR

                                else -> null
                            }
                        if (callback != null) contexts.getOrPut(callback) { mutableListOf() }.add(context)
                        dispatcher.dispatch(context, block)
                    }
                }
            val feeds = FakeFeedRepository()
            feeds.rows.value = listOf(testEpisodeRow(1), testEpisodeRow(2))
            val viewModel =
                FeedsViewModel(
                    feeds,
                    FakeEpisodeRepository(),
                    FakeRefreshController(),
                    FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                    FakePodcastRepository(),
                    TestClock(),
                    FakeEpisodeLiveStateSource(),
                    FakeGroupRepository(),
                    FakeSettingsRepository(),
                    defaultDispatcher,
                )

            val store = ViewModelStore().apply { put("feeds", viewModel) }
            try {
                val items = viewModel.pagingFor(FeedSource.All).take(1).asSnapshot()
                assertEquals(2, items.filterIsInstance<FeedItem.Episode>().size)
                assertEquals(1, items.filterIsInstance<FeedItem.Day>().size)
                for (callback in Callback.entries) {
                    val observed = contexts[callback].orEmpty()
                    assertTrue(observed.isNotEmpty(), "$callback must switch to Default inside its deferred callback")
                    observed.forEach { assertSame(defaultDispatcher, it[ContinuationInterceptor]) }
                }
            } finally {
                store.clear()
            }
        }

    private enum class Callback { MAP, DAY_SEPARATOR }
}
