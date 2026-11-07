// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.di

import android.app.Application
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncHeldEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.ui.root.HeldChangesBanner
import ch.lkmc.neutrodyne.heldChangesBanner
import ch.lkmc.neutrodyne.heldChangesBannerFlow
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Android shell's held-changes mapping against the production graph: a persisted
 * `sync_held` batch reaches the controller's prompt, the mapping turns it into the root's
 * `HeldChangesBanner`, and the shell flow resolves the controller only after the start-up gate's
 * database open (01 Application start-up, 08 priority 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HeldChangesBannerTest {
    private val graph = createGraphFactory<AndroidAppGraph.Factory>().create(RuntimeEnvironment.getApplication())

    @After
    fun tearDown() {
        runBlocking {
            if (graph.databaseOpener.openState.value is DatabaseOpenState.Opened) {
                val db = graph.databaseOpener.requireDatabase()
                db.syncHeldDao().all().forEach { db.syncHeldDao().delete(it.id) }
                db.syncStateDao().upsert(SyncStateEntity())
                db.close()
            }
        }
        graph.appScope.cancel()
    }

    /** A persisted batch the controller never resolved must still reach the shell mapping. */
    @Test
    fun aPersistedHeldBatchMapsToTheReviewBanner() =
        runBlocking {
            graph.databaseOpener.awaitOpen()
            val db = graph.databaseOpener.requireDatabase()
            db.syncHeldDao().all().forEach { db.syncHeldDao().delete(it.id) }
            val id = seedHeld(db)
            val controller = graph.syncController
            withTimeout(5_000) { controller.heldChanges.first { prompts -> prompts.any { it.id == id } } }

            val prompt = controller.heldChanges.value.single { it.id == id }
            val banner =
                heldChangesBanner(
                    linkedStatus(),
                    listOf(prompt),
                )
            assertThat(banner).isEqualTo(HeldChangesBanner(id, null, 2, 1))
        }

    /** The shell flow waits for the gate and resolves the controller lazily, never before. */
    @Test
    fun theControllerIsResolvedOnlyAfterTheGateOpens() =
        runBlocking {
            var resolutions = 0
            val job =
                CoroutineScope(Dispatchers.Default + Job()).launch {
                    heldChangesBannerFlow(graph.databaseOpener) {
                        resolutions++
                        graph.syncController
                    }.collect { }
                }
            try {
                // The gate is still closed: the collector must be waiting, not resolving.
                delay(200)
                assertThat(resolutions).isEqualTo(0)

                graph.databaseOpener.awaitOpen()
                withTimeout(5_000) { while (resolutions == 0) delay(10) }
                assertThat(resolutions).isEqualTo(1)
            } finally {
                job.cancel()
            }
        }

    /** Without a committed link the banner stays inert even when the table holds a batch. */
    @Test
    fun theBannerStaysInertUntilTheLinkIsCommitted() =
        runBlocking {
            graph.databaseOpener.awaitOpen()
            val db = graph.databaseOpener.requireDatabase()
            db.syncHeldDao().all().forEach { db.syncHeldDao().delete(it.id) }
            val id = seedHeld(db)
            val controller = graph.syncController
            withTimeout(5_000) { controller.heldChanges.first { prompts -> prompts.any { it.id == id } } }

            assertThat(heldChangesBanner(SyncStatus.NotConfigured, controller.heldChanges.value)).isNull()
            assertThat(
                heldChangesBanner(
                    linkedStatus(),
                    controller.heldChanges.value,
                ),
            ).isEqualTo(HeldChangesBanner(id, null, 2, 1))
        }

    private suspend fun seedHeld(db: NeutrodyneDatabase): Long {
        val batch =
            buildJsonObject {
                put("state", "HELD")
                put(
                    "podcasts",
                    JsonArray(
                        listOf("a", "b").map { rid ->
                            buildJsonObject {
                                put("rid", rid)
                                put("clock", "0:0")
                            }
                        },
                    ),
                )
                put(
                    "groups",
                    JsonArray(
                        listOf("news").map { rid ->
                            buildJsonObject {
                                put("rid", rid)
                                put("clock", "0:0")
                            }
                        },
                    ),
                )
            }.toString()
        val summary =
            buildJsonObject {
                put("podcasts", JsonArray(listOf("A", "B").map(::JsonPrimitive)))
                put("groups", JsonArray(listOf("News").map(::JsonPrimitive)))
            }.toString()
        return db.syncHeldDao().insert(SyncHeldEntity(batch = batch, summary = summary, heldAt = 1))
    }

    private fun linkedStatus(): SyncStatus.Linked =
        SyncStatus.Linked(
            serverUrl = "https://sync.example",
            accountId = "acc",
            deviceId = "dev",
            username = "alice",
            nodeId = "node",
            lastSyncAtMs = 1L,
            problem = null,
        )
}
