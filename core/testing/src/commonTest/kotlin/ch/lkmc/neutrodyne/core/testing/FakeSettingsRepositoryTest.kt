// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fake against the shared contract plus the fake-only surface (09):
 * [FakeSettingsRepository.failNextSet] and the [FakeSettingsRepository.calls] log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeSettingsRepositoryTest : SettingsRepositoryContract() {
    override fun TestScope.createRepository(): SettingsRepository = FakeSettingsRepository()

    @Test
    fun failNextSetFailsExactlyOneSetWithoutStoring() =
        runTest {
            val fake = FakeSettingsRepository()
            fake.failNextSet = SettingsError.WriteFailed

            assertEquals(Outcome.Failure(SettingsError.WriteFailed), fake.set(portableBool, true))
            assertEquals(false, fake.get(portableBool))

            assertEquals(Outcome.Success(Unit), fake.set(portableBool, true))
            assertEquals(true, fake.get(portableBool))
        }

    @Test
    fun concurrentSettingsCallsAreAllRecorded() =
        runTest {
            val fake = FakeSettingsRepository()
            val start = CompletableDeferred<Unit>()
            val workers = 8
            val iterations = 250
            val jobs =
                List(workers) {
                    async(Dispatchers.Default) {
                        start.await()
                        repeat(iterations) {
                            fake.get(portableBool)
                            fake.set(portableBool, true)
                            fake.reset(portableBool)
                        }
                    }
                }
            start.complete(Unit)
            jobs.awaitAll()

            for (operation in listOf("get", "set", "reset")) {
                assertEquals(
                    workers * iterations,
                    fake.calls.count { it == "$operation(${portableBool.name})" },
                    operation,
                )
            }
        }

    @Test
    fun everyCallIsLoggedInOrder() =
        runTest {
            val fake = FakeSettingsRepository()

            fake.get(portableBool)
            fake.set(portableBool, true)
            fake.reset(portableBool)

            assertEquals(
                listOf(
                    "get(feeds.notify_new_episodes)",
                    "set(feeds.notify_new_episodes)",
                    "reset(feeds.notify_new_episodes)",
                ),
                fake.calls,
            )
        }
}
