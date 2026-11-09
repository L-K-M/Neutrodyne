// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakePlaybackSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 10 Now playing and handoff: the adopter's durable-session rules — a remote session stays raw
 * while this device plays and surfaces once it goes idle, a newer local play wins, the offer
 * honours the writer/expiry/dismissal/clear rules, and a stale async publish can never land.
 * Both platforms run this base against a real Room database.
 */
abstract class SessionAdopterTestBase {
    /**
     * The platform supplies the in-memory database (bundled driver on desktop, Robolectric). The
     * query context is an [kotlinx.coroutines.test.UnconfinedTestDispatcher] sharing [scheduler]
     * so Room work stays on the test's virtual clock.
     */
    abstract fun newTestDb(scheduler: TestCoroutineScheduler): NeutrodyneDatabase

    /** A [SessionAdopter] for tests: no devices to name, an idle scope that never starts. */
    private fun testSessionAdopter(
        db: NeutrodyneDatabase,
        clock: Clock,
        secrets: SecretStore = FakeSecretStore(),
        names: DeviceNames = DeviceNames { null },
        playback: PlaybackSyncPort = NoPlaybackSync,
        scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    ): SessionAdopter =
        SessionAdopter(
            db = db,
            playback = playback,
            names = names,
            clock = clock,
            secrets = secrets,
            sharePasswords = { false },
            scope = scope,
        )

    @Test
    fun aRemoteSessionIsAdoptedAndOfferedWhileIdle() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                val podcast = seedPodcastAndEpisode(db)
                val adopter = testSessionAdopter(db, TestClock(NOW))
                setPosition(db, episodeId(db, podcast, EPISODE_KEY), 42_000L)

                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY), OTHER_DEVICE)
                adopter.evaluate()
                adopter.drainOffers()

                assertEquals(
                    episodeId(db, podcast, EPISODE_KEY),
                    db.playSessionDao().get()?.currentEpisodeId,
                )
                val offer = adopter.offer.value
                assertNotNull(offer, "an offer was published")
                assertEquals(42_000L, offer.positionMs)
                assertEquals(OTHER_DEVICE, offer.deviceName, "no name lookup falls back to the id")
            } finally {
                db.close()
            }
        }

    @Test
    fun aPlayingDeviceKeepsTheSessionRawWithoutAnOffer() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                seedPodcastAndEpisode(db)
                val adopter = testSessionAdopter(db, TestClock(NOW), playback = FakePlaybackSyncPort(playing = true))

                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY), OTHER_DEVICE)
                adopter.evaluate()

                assertNull(adopter.offer.value)
                assertNull(db.playSessionDao().get()?.currentEpisodeId, "no adoption while playing")
                val raw = SyncClockStore(db).get(SESSION_WIRE, SESSION_RID)
                assertNotNull(raw, "the remote session stays raw in sync_clock")
                assertEquals(OTHER_DEVICE, raw.by)
            } finally {
                db.close()
            }
        }

    @Test
    fun theStoredSessionSurfacesAfterPauseAndSurvivesARestart() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                val podcast = seedPodcastAndEpisode(db)
                val playback = FakePlaybackSyncPort(playing = true)
                val adopter = testSessionAdopter(db, TestClock(NOW), playback = playback)

                // While playing the session stays raw in sync_clock.
                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY), OTHER_DEVICE)
                adopter.evaluate()
                assertNull(adopter.offer.value)
                assertNull(db.playSessionDao().get()?.currentEpisodeId)

                // The pause boundary re-evaluates the stored record without a redelivery.
                playback.setPlaying(false)
                adopter.evaluate()
                adopter.drainOffers()
                assertEquals(episodeId(db, podcast, EPISODE_KEY), db.playSessionDao().get()?.currentEpisodeId)
                assertNotNull(adopter.offer.value)

                // A restarted process re-evaluates the same durable record — nothing re-pushes.
                val restarted = testSessionAdopter(db, TestClock(NOW), playback = playback)
                restarted.evaluate()
                restarted.drainOffers()
                assertNotNull(restarted.offer.value)
                assertEquals(
                    episodeId(db, podcast, EPISODE_KEY),
                    restarted.offer.value?.episodeId,
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerPendingLocalSessionWinsAndSuppressesTheOffer() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                seedPodcastAndEpisode(db)
                val adopter = testSessionAdopter(db, TestClock(NOW))

                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY, millis = NOW - 1_000L), OTHER_DEVICE)
                // A local play captured after the remote write carries this node's newer clock.
                pendLocalSession(db, PODCAST_SYNC, OTHER_KEY, Hlc.of(NOW - 500L, 0, THIS_NODE_ID))
                adopter.evaluate()
                adopter.drainOffers()

                assertNull(adopter.offer.value, "a local play since the remote session wins")
                assertNull(db.playSessionDao().get()?.currentEpisodeId)
            } finally {
                db.close()
            }
        }

    @Test
    fun aDismissedSessionNeverReturnsUntilANewerWrite() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                seedPodcastAndEpisode(db)
                val adopter = testSessionAdopter(db, TestClock(NOW))
                val record = remoteSession(PODCAST_SYNC, EPISODE_KEY)
                storeRaw(db, record, OTHER_DEVICE)
                adopter.evaluate()
                adopter.drainOffers()
                assertNotNull(adopter.offer.value)

                adopter.dismiss()
                assertNull(adopter.offer.value)

                // A redelivery of the same-or-older session does not re-offer in this process run.
                adopter.evaluate()
                adopter.drainOffers()
                assertNull(adopter.offer.value, "the same session is not re-offered")

                // A strictly newer remote write is a different offer.
                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY, millis = NOW - 100L), OTHER_DEVICE)
                adopter.evaluate()
                adopter.drainOffers()
                assertNotNull(adopter.offer.value)
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerRemoteSessionSupersedesTheOffer() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                val podcast = seedPodcastAndEpisode(db)
                val adopter = testSessionAdopter(db, TestClock(NOW))
                setPosition(db, episodeId(db, podcast, OTHER_KEY), 7_000L)
                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY), OTHER_DEVICE)
                adopter.evaluate()
                adopter.drainOffers()

                storeRaw(db, remoteSession(PODCAST_SYNC, OTHER_KEY, millis = NOW - 500L), OTHER_DEVICE)
                adopter.evaluate()
                adopter.drainOffers()

                val offer = adopter.offer.value
                assertNotNull(offer)
                assertEquals(episodeId(db, podcast, OTHER_KEY), offer.episodeId)
                assertEquals(7_000L, offer.positionMs)
            } finally {
                db.close()
            }
        }

    @Test
    fun anOwnDeviceSessionIsNeverOffered() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                seedPodcastAndEpisode(db)
                val adopter = testSessionAdopter(db, TestClock(NOW))
                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY), THIS_DEVICE)
                adopter.evaluate()
                adopter.drainOffers()
                assertNull(adopter.offer.value)
                assertNull(db.playSessionDao().get()?.currentEpisodeId)
            } finally {
                db.close()
            }
        }

    @Test
    fun anExpiredSessionIsNeverOffered() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                seedPodcastAndEpisode(db)
                val adopter = testSessionAdopter(db, TestClock(NOW))
                storeRaw(
                    db,
                    remoteSession(PODCAST_SYNC, EPISODE_KEY, millis = NOW - OFFER_MAX_AGE_MS - 1_000L),
                    OTHER_DEVICE,
                )
                adopter.evaluate()
                adopter.drainOffers()
                assertNull(adopter.offer.value)
                assertNull(db.playSessionDao().get()?.currentEpisodeId)
            } finally {
                db.close()
            }
        }

    @Test
    fun aClearedRemoteSessionClearsTheOffer() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                seedPodcastAndEpisode(db)
                val adopter = testSessionAdopter(db, TestClock(NOW))
                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY), OTHER_DEVICE)
                adopter.evaluate()
                adopter.drainOffers()
                assertNotNull(adopter.offer.value)

                // The writer cleared the item: a newer record whose episode is `null`.
                storeRaw(db, remoteSession(PODCAST_SYNC, null, millis = NOW - 100L), OTHER_DEVICE)
                adopter.evaluate()
                adopter.drainOffers()
                assertNull(adopter.offer.value)
            } finally {
                db.close()
            }
        }

    @Test
    fun aGroupContextMapsToLocalIds() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                val podcast = seedPodcastAndEpisode(db)
                val groupId =
                    db.withWriteTransaction {
                        db.groupDao().insert(groupEntity(uuid = GROUP_UUID, name = "G", orderKey = "a0"))
                    }
                val anchorId = episodeId(db, podcast, OTHER_KEY)
                val adopter = testSessionAdopter(db, TestClock(NOW))
                storeRaw(
                    db,
                    remoteSession(
                        PODCAST_SYNC,
                        EPISODE_KEY,
                        context =
                            contextJson(
                                "type" to JsonPrimitive("GROUP"),
                                "group" to JsonPrimitive(GROUP_UUID),
                                "order" to JsonPrimitive("OLDEST_FIRST"),
                                "filters" to
                                    JsonObject(
                                        mapOf(
                                            "flags" to JsonPrimitive(5),
                                            "media" to JsonPrimitive("VIDEO"),
                                        ),
                                    ),
                                "minSortDate" to JsonPrimitive(1_234L),
                                "anchor" to episodeRef(PODCAST_SYNC, OTHER_KEY),
                                "anchorSortDate" to JsonPrimitive(987L),
                            ),
                    ),
                    OTHER_DEVICE,
                )
                adopter.evaluate()

                val session = db.playSessionDao().get()
                assertNotNull(session)
                assertEquals(ContextType.GROUP, session.contextType)
                assertEquals(groupId, session.contextId)
                assertEquals(FeedOrder.OLDEST_FIRST, session.contextOrder)
                assertEquals(5, session.contextFilterFlags)
                assertEquals(MediaFilter.VIDEO, session.contextMediaFilter)
                assertEquals(1_234L, session.contextMinSortDate)
                assertEquals(anchorId, session.contextAnchorEpisodeId)
                assertEquals(987L, session.contextAnchorSortDate)
            } finally {
                db.close()
            }
        }

    @Test
    fun aPodcastContextMapsToLocalIdsAndAnUnresolvableGroupDegrades() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                seedPodcastAndEpisode(db)
                val podcastId = db.podcastDao().bySyncId(PODCAST_SYNC)!!.id
                val adopter = testSessionAdopter(db, TestClock(NOW))
                storeRaw(
                    db,
                    remoteSession(
                        PODCAST_SYNC,
                        EPISODE_KEY,
                        context =
                            contextJson(
                                "type" to JsonPrimitive("PODCAST"),
                                "podcast" to JsonPrimitive(PODCAST_SYNC),
                            ),
                    ),
                    OTHER_DEVICE,
                )
                adopter.evaluate()
                var session = db.playSessionDao().get()
                assertEquals(ContextType.PODCAST, session?.contextType)
                assertEquals(podcastId, session?.contextId)

                // A group the peer cannot resolve describes nothing the feed understands.
                storeRaw(
                    db,
                    remoteSession(
                        PODCAST_SYNC,
                        OTHER_KEY,
                        millis = NOW - 500L,
                        context =
                            contextJson(
                                "type" to JsonPrimitive("GROUP"),
                                "group" to JsonPrimitive("00000000-0000-4000-8000-0000000000ff"),
                            ),
                    ),
                    OTHER_DEVICE,
                )
                adopter.evaluate()
                session = db.playSessionDao().get()
                assertEquals(ContextType.EXTERNAL, session?.contextType)
                assertNull(session?.contextId)
            } finally {
                db.close()
            }
        }

    @Test
    fun adoptionBumpsTheGenerationOnceAndLeavesNoLocalEcho() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                seedPodcastAndEpisode(db)
                val adopter = testSessionAdopter(db, TestClock(NOW))
                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY), OTHER_DEVICE)

                adopter.evaluate()
                assertEquals(1L, db.playSessionDao().get()?.generation, "one adoption bumps once")

                // The identical decision re-evaluates without another write.
                adopter.evaluate()
                assertEquals(1L, db.playSessionDao().get()?.generation)
                assertNull(db.syncOutboxDao().get(SESSION_WIRE, SESSION_RID, "episode"), "no echo row")
                assertNull(db.syncOutboxDao().get(SESSION_WIRE, SESSION_RID, "context"), "no echo row")
                assertFalse(db.syncStateDao().isApplying(), "the applying flag is restored")
            } finally {
                db.close()
            }
        }

    @Test
    fun anUnresolvableEpisodeWithEnclosureHintsStubs() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                val podcastId = seedPodcast(db)
                val adopter = testSessionAdopter(db, TestClock(NOW))
                storeRaw(
                    db,
                    remoteSession(
                        PODCAST_SYNC,
                        "g:new",
                        hints = MatchHints(enc = "https://cdn.example/new.mp3", guid = "new"),
                    ),
                    OTHER_DEVICE,
                )
                adopter.evaluate()
                adopter.drainOffers()

                val stub = db.episodeDao().byIdentityKey(podcastId, "g:new")
                assertNotNull(stub, "the hinted reference stubs like an episode record")
                assertEquals(stub.id, db.playSessionDao().get()?.currentEpisodeId)
                assertEquals(stub.id, adopter.offer.value?.episodeId)
            } finally {
                db.close()
            }
        }

    @Test
    fun anUnresolvableSessionFailsSafelyWithoutAnOffer() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                seedPodcastAndEpisode(db)
                val adopter = testSessionAdopter(db, TestClock(NOW))
                // An episode the matcher cannot find and no hints to stub: nothing is adopted.
                storeRaw(db, remoteSession(PODCAST_SYNC, "g:absent"), OTHER_DEVICE)
                adopter.evaluate()
                adopter.drainOffers()
                assertNull(adopter.offer.value)
                assertNull(db.playSessionDao().get()?.currentEpisodeId)
            } finally {
                db.close()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aStaleNameCompletionDoesNotPublishAfterALocalStart() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                seedPodcastAndEpisode(db)
                val gate = CompletableDeferred<Unit>()
                val adopter =
                    testSessionAdopter(
                        db,
                        TestClock(NOW),
                        names =
                            DeviceNames {
                                gate.await()
                                "B"
                            },
                    )
                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY), OTHER_DEVICE)
                adopter.evaluate()

                val drain = launch { adopter.drainOffers() }
                runCurrent() // parked inside the name lookup
                // A local play captured while the name resolved: the offer is stale.
                pendLocalSession(db, PODCAST_SYNC, OTHER_KEY, Hlc.of(NOW, 0, THIS_NODE_ID))
                gate.complete(Unit)
                advanceUntilIdle()
                drain.join()

                assertNull(adopter.offer.value, "a played-since device must not publish the stale offer")
            } finally {
                db.close()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aStaleNameCompletionDoesNotPublishAfterUnlink() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                seedPodcastAndEpisode(db)
                val gate = CompletableDeferred<Unit>()
                val adopter =
                    testSessionAdopter(
                        db,
                        TestClock(NOW),
                        names =
                            DeviceNames {
                                gate.await()
                                "B"
                            },
                    )
                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY), OTHER_DEVICE)
                adopter.evaluate()

                val drain = launch { adopter.drainOffers() }
                runCurrent() // parked inside the name lookup
                db.syncStateDao().unlink()
                gate.complete(Unit)
                advanceUntilIdle()
                drain.join()

                assertNull(adopter.offer.value, "an unlinked device must not publish the stale offer")
            } finally {
                db.close()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aStaleNameCompletionDoesNotPublishANewerSessionArrived() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                val podcast = seedPodcastAndEpisode(db)
                val gate = CompletableDeferred<Unit>()
                val adopter =
                    testSessionAdopter(
                        db,
                        TestClock(NOW),
                        names =
                            DeviceNames {
                                gate.await()
                                "B"
                            },
                    )
                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY), OTHER_DEVICE)
                adopter.evaluate()

                val drain = launch { adopter.drainOffers() }
                runCurrent() // parked inside the name lookup
                // A newer remote session lands while the first publish resolves its name.
                storeRaw(db, remoteSession(PODCAST_SYNC, OTHER_KEY, millis = NOW - 500L), OTHER_DEVICE)
                adopter.evaluate()
                gate.complete(Unit)
                advanceUntilIdle()
                drain.join()

                assertNull(adopter.offer.value, "the superseded offer never publishes")
                adopter.drainOffers()
                assertEquals(
                    episodeId(db, podcast, OTHER_KEY),
                    adopter.offer.value?.episodeId,
                    "the newer session's own publish still lands",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun anAcknowledgedLocalSessionKeepsWinningInRaw() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                seedPodcastAndEpisode(db)
                val adopter = testSessionAdopter(db, TestClock(NOW))
                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY, millis = NOW - 2_000L), OTHER_DEVICE)

                // Our session write pends, then the push ack lands before the echo applies.
                val ourClock = Hlc.of(NOW - 500L, 0, THIS_NODE_ID)
                pendLocalSession(db, PODCAST_SYNC, OTHER_KEY, ourClock)
                val effects =
                    SyncApplyEffects(
                        db,
                        FakeSettingsSyncPort(),
                        TestSettingsWriteCoordinator(),
                        { true },
                        FakeSecretStore(),
                        FakeCredentialCommitCoordinator(),
                        "__absent_cache__".toPath(),
                    )
                effects.acknowledge(
                    listOf(
                        ChangeDto(
                            coll = SESSION_WIRE,
                            id = JsonPrimitive(SESSION_RID),
                            fields =
                                mapOf(
                                    "episode" to FieldValue(episodeRef(PODCAST_SYNC, OTHER_KEY), ourClock.toString()),
                                    "context" to
                                        FieldValue(
                                            contextJson("type" to JsonPrimitive("EXTERNAL")),
                                            ourClock.toString(),
                                        ),
                                ),
                        ),
                    ),
                    listOf(ChangeResultDto(0, "applied")),
                )

                assertNull(db.syncOutboxDao().get(SESSION_WIRE, SESSION_RID, "episode"), "the ack deletes the rows")
                val raw = SyncClockStore(db).get(SESSION_WIRE, SESSION_RID)
                assertEquals(
                    THIS_NODE,
                    raw
                        ?.state
                        ?.fields
                        ?.get("episode")
                        ?.c
                        ?.nodeId
                        ?.value,
                    "the ack merges the sent literal into raw, so the echo gap cannot revert it",
                )
                adopter.evaluate()
                adopter.drainOffers()
                assertNull(adopter.offer.value, "a played-since device never offers the older remote session")
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalStartBeforeTheAdoptionWriteKeepsItsSession() =
        runTest {
            val db = newTestDb(testScheduler)
            val reachedWrite = java.util.concurrent.CountDownLatch(1)
            val continueWrite = java.util.concurrent.CountDownLatch(1)
            val reads =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val clock =
                object : Clock {
                    override fun now(): Long {
                        if (reads.incrementAndGet() == 2) {
                            reachedWrite.countDown()
                            check(continueWrite.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        }
                        return NOW
                    }

                    override fun elapsedRealtime(): Long = NOW
                }
            try {
                seedLink(db)
                val podcast = seedPodcastAndEpisode(db)
                val localId = episodeId(db, podcast, OTHER_KEY)
                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY), OTHER_DEVICE)
                val adopter = testSessionAdopter(db, clock)
                val evaluation = async(Dispatchers.Default) { adopter.evaluate() }
                try {
                    assertTrue(
                        withContext(Dispatchers.IO) { reachedWrite.await(10, java.util.concurrent.TimeUnit.SECONDS) },
                    )
                    db.withWriteTransaction {
                        db.playSessionDao().ensureNow(NOW)
                        val row = requireNotNull(db.playSessionDao().get())
                        db.playSessionDao().write(row.copy(currentEpisodeId = localId, generation = row.generation + 1))
                        db.syncOutboxDao().upsert(
                            SyncOutboxEntity(
                                coll = SESSION_WIRE,
                                rid = SESSION_RID,
                                field = "episode",
                                hlc = (NOW + 1) shl 16,
                                nodeId = THIS_NODE,
                                value = null,
                            ),
                        )
                    }
                } finally {
                    continueWrite.countDown()
                }
                evaluation.await()
                assertEquals(
                    localId,
                    db.playSessionDao().get()?.currentEpisodeId,
                    "a remote adoption must not overwrite a newer local start",
                )
                assertEquals(
                    episodeRef(PODCAST_SYNC, OTHER_KEY),
                    SourceReader(db, FakeSecretStore(), {
                        false
                    }).sessionField("episode"),
                )
                adopter.drainOffers()
                assertNull(adopter.offer.value)
            } finally {
                continueWrite.countDown()
                db.close()
            }
        }

    @Test
    fun startingPlaybackAfterEligibilityPreventsTheAdoptionWrite() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLink(db)
                seedPodcastAndEpisode(db)
                val playback = FakePlaybackSyncPort()
                var reads = 0
                val clock =
                    object : Clock {
                        override fun now(): Long {
                            if (++reads == 2) playback.setPlaying(true)
                            return NOW
                        }

                        override fun elapsedRealtime(): Long = NOW
                    }
                storeRaw(db, remoteSession(PODCAST_SYNC, EPISODE_KEY), OTHER_DEVICE)
                val adopter = testSessionAdopter(db, clock, playback = playback)
                adopter.evaluate()
                assertTrue(playback.active.value.isPlaying)
                assertNull(db.playSessionDao().get()?.currentEpisodeId, "eligibility must be rechecked at the write")
                adopter.drainOffers()
                assertNull(adopter.offer.value)
            } finally {
                db.close()
            }
        }

    private suspend fun seedLink(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(
                    SyncStateEntity(
                        enabled = true,
                        nodeId = THIS_NODE,
                        deviceId = THIS_DEVICE,
                        linkedAt = NOW - 10_000L,
                        hlc = 1L shl 16,
                    ),
                )
        }
    }

    private suspend fun seedPodcast(db: NeutrodyneDatabase): Long {
        db.withWriteTransaction {
            db
                .podcastDao()
                .insertPodcast(
                    podcastEntity(syncId = PODCAST_SYNC, feedUrl = FEED_URL, feedKey = FEED_URL, title = "P"),
                )
        }
        return db.podcastDao().bySyncId(PODCAST_SYNC)!!.id
    }

    /** Inserts the podcast and both episodes the remote sessions name; returns the podcast id. */
    private suspend fun seedPodcastAndEpisode(db: NeutrodyneDatabase): Long {
        val podcast = seedPodcast(db)
        db.withWriteTransaction {
            db
                .ingestDao()
                .insertEpisodes(
                    listOf(
                        episodeEntity(podcastId = podcast, identityKey = EPISODE_KEY),
                        episodeEntity(podcastId = podcast, identityKey = OTHER_KEY),
                    ),
                )
        }
        return podcast
    }

    private suspend fun episodeId(
        db: NeutrodyneDatabase,
        podcastId: Long,
        key: String,
    ): Long = db.episodeDao().byIdentityKey(podcastId, key)!!.id

    private suspend fun setPosition(
        db: NeutrodyneDatabase,
        episodeId: Long,
        positionMs: Long,
    ) {
        db.withWriteTransaction {
            db
                .positionDao()
                .upsert(
                    EpisodePositionEntity(
                        episodeId = episodeId,
                        positionMs = positionMs,
                        positionSource = PositionSource.STREAM,
                        updatedAt = NOW,
                    ),
                )
        }
    }

    private suspend fun storeRaw(
        db: NeutrodyneDatabase,
        record: RecordState,
        by: String,
    ) {
        db.withWriteTransaction {
            SyncClockStore(db).put(SESSION_WIRE, SESSION_RID, RawSyncRecord(record, by = by))
        }
    }

    /** A pending local session capture: literal field rows at this node's clock. */
    private suspend fun pendLocalSession(
        db: NeutrodyneDatabase,
        podcastSyncId: String,
        key: String,
        clock: Hlc,
    ) {
        db.withWriteTransaction {
            db
                .syncOutboxDao()
                .upsert(
                    SyncOutboxEntity(
                        coll = SESSION_WIRE,
                        rid = SESSION_RID,
                        field = "episode",
                        hlc = clock.packed,
                        nodeId = clock.nodeId.value,
                        value = episodeRef(podcastSyncId, key).toString(),
                    ),
                )
            db
                .syncOutboxDao()
                .upsert(
                    SyncOutboxEntity(
                        coll = SESSION_WIRE,
                        rid = SESSION_RID,
                        field = "context",
                        hlc = clock.packed,
                        nodeId = clock.nodeId.value,
                        value = contextJson("type" to JsonPrimitive("EXTERNAL")).toString(),
                    ),
                )
        }
    }

    private companion object {
        private const val NOW = 1_800_000_000_000L
        private const val OTHER_NODE = "bbbbbbbbbbbbbbbb"
        private const val THIS_NODE = "aaaaaaaaaaaaaaaa"
        private const val OTHER_DEVICE = "device-b"
        private const val THIS_DEVICE = "device-a"
        private const val PODCAST_SYNC = "00000000-0000-4000-8000-0000000000aa"
        private const val FEED_URL = "https://a.example/feed"
        private const val EPISODE_KEY = "g:one"
        private const val OTHER_KEY = "g:two"
        private const val GROUP_UUID = "00000000-0000-4000-8000-0000000000bb"
        private const val OFFER_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
        private const val SESSION_WIRE = "session"
        private const val SESSION_RID = "current"
        private val THIS_NODE_ID = NodeId(THIS_NODE)
        private val OTHER_NODE_ID = NodeId(OTHER_NODE)

        private fun episodeRef(
            podcastSyncId: String,
            key: String,
        ): JsonObject =
            JsonObject(
                mapOf(
                    "p" to JsonPrimitive(podcastSyncId),
                    "k" to JsonPrimitive(key),
                ),
            )

        private fun contextJson(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*entries))

        /** A minimal remote session record as device B wrote it (10: `episode` + `context`). */
        private fun remoteSession(
            podcastSyncId: String,
            episodeKey: String?,
            millis: Long = NOW - 1_000L,
            context: JsonObject = contextJson("type" to JsonPrimitive("EXTERNAL")),
            hints: MatchHints? = null,
            node: NodeId = OTHER_NODE_ID,
        ): RecordState {
            val clock = Hlc.of(millis, 0, node)
            return RecordState(
                SyncCollection.SESSION,
                mapOf(
                    "episode" to
                        FieldState(
                            episodeKey?.let { episodeRef(podcastSyncId, it) } ?: JsonNull,
                            clock,
                        ),
                    "context" to FieldState(context, clock),
                ),
                match = hints,
            )
        }
    }
}
