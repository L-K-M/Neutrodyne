// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.database.OpenResult
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 11's Busy rule through `AppBusySignal`: playing audio or an active/runnable transfer.
 * Paused playback does not count, and the download side joins only once the database is
 * open — before that, resolving the controller would block (or throw on the EDT).
 *
 * `seen` collects on an unconfined dispatcher so every emission lands in write order;
 * `combine`'s latest-per-source race at subscription is why the assertions read `seen.last`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppBusySignalTest {
    private val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val openState = MutableStateFlow<DatabaseOpenState>(DatabaseOpenState.Pending)
    private val downloads = FakeDownloadController()

    private fun signal() = AppBusySignal(nowPlaying, openState) { downloads }

    @Test
    fun `playing audio is busy and pausing is not`() =
        runTest {
            val seen = collectBusy()
            assertThat(seen.last()).isFalse()

            nowPlaying.value = testNowPlaying()
            assertThat(seen.last()).isTrue()

            nowPlaying.value = testNowPlaying().copy(isPlaying = false)
            assertThat(seen.last()).isFalse()
        }

    @Test
    fun `a transfer counts only after the database opens`() =
        runTest {
            downloads.busy.value = true
            val seen = collectBusy()
            // The download side waits on the open — a pending gate reports idle.
            assertThat(seen.last()).isFalse()

            openState.value = DatabaseOpenState.Opened(OpenResult(created = false, recovered = null))
            assertThat(seen.last()).isTrue()

            downloads.busy.value = false
            assertThat(seen.last()).isFalse()
        }

    @Test
    fun `either source alone keeps the signal busy`() =
        runTest {
            downloads.busy.value = true
            openState.value = DatabaseOpenState.Opened(OpenResult(created = false, recovered = null))
            val seen = collectBusy()
            assertThat(seen.last()).isTrue()

            nowPlaying.value = testNowPlaying()
            downloads.busy.value = false
            assertThat(seen.last()).isTrue()

            nowPlaying.value = testNowPlaying().copy(isPlaying = false)
            assertThat(seen.last()).isFalse()
        }

    private suspend fun kotlinx.coroutines.test.TestScope.collectBusy(): MutableList<Boolean> {
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            signal().busy.collect { seen += it }
        }
        return seen
    }
}
