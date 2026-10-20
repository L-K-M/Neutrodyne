// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.app.Application
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArtworkInitializerTest {
    @Test fun committedSourceChangesScheduleWorkAndArtworkWritesDoNotLoop() =
        runBlocking {
            ArtworkHostFixture(RuntimeEnvironment.getApplication()).use { fixture ->
                val enqueues = Channel<Unit>(Channel.UNLIMITED)
                val scheduler =
                    ArtworkSyncScheduler(
                        object : ArtworkWorkScheduler {
                            override suspend fun enqueue() {
                                enqueues.send(Unit)
                            }
                        },
                        fixture.scope,
                    )
                val initializer =
                    ArtworkInitializer(
                        fixture.opener,
                        Provider { fixture.store },
                        Provider { fixture.db },
                        fixture.scope,
                        scheduler,
                        Dispatchers.IO,
                    )
                assertEquals(300, initializer.order)
                initializer.run()
                withTimeout(5_000) { enqueues.receive() }
                // No pin hint: the committed podcast insert itself must reach the artwork lane.
                val key = "m-" + "d".repeat(40)
                fixture.podcast(key)
                withTimeout(5_000) { enqueues.receive() }
                fixture.db.artworkDao().upsert(ArtworkEntity(key))
                assertNull(withTimeoutOrNull(2_500) { enqueues.receive() })
            }
        }
}
