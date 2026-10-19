// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.domain.ArtworkRepository
import ch.lkmc.neutrodyne.core.model.ArtworkColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** W2 must exercise these same cases against Room/file availability, not claim fake-only proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkRepositoryContractTest {
    private val fake = FakeArtworkRepository()
    private val repository: ArtworkRepository = fake
    private val primary = ArtworkColors("episode", 2, 0xFFAA8800.toInt(), 0xFF886600.toInt())
    private val podcast = ArtworkColors("podcast", 4, 0xFF448800.toInt(), 0xFF335500.toInt())

    @Test fun absentAndUnprocessedEmitNull() =
        runTest {
            repository.observeColors(primary.key).test {
                assertNull(awaitItem())
                fake.colors.value = mapOf(primary.key to primary.copy(seedArgb = null, avgArgb = null))
                expectNoEvents()
                fake.colors.value = mapOf(primary.key to primary)
                assertEquals(primary, awaitItem())
            }
        }

    @Test fun completedMonochromeAndTransparentNeverUsePodcastSeed() =
        runTest {
            fake.podcastArtworkKeys.value = mapOf(7L to podcast.key)
            val monochrome = primary.copy(seedArgb = null)
            fake.colors.value = mapOf(primary.key to monochrome, podcast.key to podcast)
            repository.observeColors(primary.key, 7L).test {
                assertEquals(monochrome, awaitItem())
                val transparent = primary.copy(seedArgb = null, avgArgb = 0)
                fake.colors.value = fake.colors.value + (primary.key to transparent)
                assertEquals(transparent, awaitItem())
            }
        }

    @Test fun fallbackFollowsPodcastKeyAndVersionUntilPrimaryCompletes() =
        runTest {
            fake.podcastArtworkKeys.value = mapOf(7L to podcast.key)
            fake.colors.value = mapOf(podcast.key to podcast)
            repository.observeColors(primary.key, 7L).test {
                assertEquals(podcast, awaitItem())
                val changed = podcast.copy(key = "replacement", version = 5)
                fake.colors.value = fake.colors.value + (changed.key to changed)
                expectNoEvents()
                fake.podcastArtworkKeys.value = mapOf(7L to changed.key)
                assertEquals(changed, awaitItem())
                fake.colors.value = fake.colors.value + (changed.key to changed.copy(version = 6))
                assertEquals(changed.copy(version = 6), awaitItem())
                fake.colors.value = fake.colors.value + (primary.key to primary)
                assertEquals(primary, awaitItem())
                fake.colors.value = fake.colors.value - primary.key
                assertEquals(changed.copy(version = 6), awaitItem())
                fake.podcastArtworkKeys.value = emptyMap()
                assertNull(awaitItem())
            }
        }

    @Test fun unprocessedPrimaryUsesCompletedMonochromeFallback() =
        runTest {
            fake.podcastArtworkKeys.value = mapOf(7L to podcast.key)
            val monochrome = podcast.copy(seedArgb = null)
            fake.colors.value = mapOf(primary.key to primary.copy(avgArgb = null), podcast.key to monochrome)
            repository.observeColors(primary.key, 7L).test {
                assertEquals(monochrome, awaitItem())
                fake.colors.value = fake.colors.value + (podcast.key to podcast.copy(avgArgb = null))
                assertNull(awaitItem())
            }
        }

    @Test fun missingFallbackDoesNotSelectUnrelatedRows() =
        runTest {
            fake.colors.value = mapOf(podcast.key to podcast)
            fake.podcastArtworkKeys.value = mapOf(9L to podcast.key)
            repository.observeColors(primary.key, 7L).test { assertNull(awaitItem()) }
            repository.observeColors(primary.key).test { assertNull(awaitItem()) }
        }

    @Test fun switchingCurrentItemCancelsOldIdentity() =
        runTest {
            fake.colors.value = mapOf(primary.key to primary, podcast.key to podcast)
            val current = MutableStateFlow(primary.key)
            current.flatMapLatest { repository.observeColors(it) }.test {
                assertEquals(primary, awaitItem())
                current.value = podcast.key
                assertEquals(podcast, awaitItem())
                fake.colors.value = fake.colors.value + (primary.key to primary.copy(version = 3))
                expectNoEvents()
                fake.colors.value = fake.colors.value + (podcast.key to podcast.copy(version = 5))
                assertEquals(podcast.copy(version = 5), awaitItem())
            }
        }
}
