// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.common.Origin
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.credentials.DesktopSecretStore
import ch.lkmc.neutrodyne.core.data.credentials.IdentitySecretProtection
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `PodcastRepositoryImpl.unsubscribe`'s credential side (02 "Unsubscribe and merge"; 03 commit
 * ordering): the cascade's last-reference credential delete runs inside
 * `withCredentialCommit`, so it can never interleave with a subscribe's put→reference window and
 * it deletes the row only when no podcast still points at it. A real `DesktopSecretStore` keeps
 * the file half honest: a deleted row must also drop the file entry once the reconcile lands.
 */
class UnsubscribeUseCaseTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val settings = FakeSettingsRepository()
    private val scheduler = FakeRefreshScheduler()

    private lateinit var root: File
    private lateinit var resolverBundle: ResolverBundle
    private lateinit var storeScope: CoroutineScope
    private lateinit var store: DesktopSecretStore
    private lateinit var coordinator: FakeCredentialCommitCoordinator

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("nd-unsubscribe-creds").toFile()
        resolverBundle = newResolver(root, db, clock)
        coordinator = FakeCredentialCommitCoordinator()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store =
            DesktopSecretStore(
                db = db,
                appDirs = appDirsFor(root),
                coordinator = coordinator,
                clock = clock,
                protection = IdentitySecretProtection,
                scope = storeScope,
            )
    }

    @AfterTest
    fun tearDown() {
        storeScope.cancel()
        resolverBundle.close()
        root.deleteRecursively()
    }

    private val poster = FakeNewEpisodePoster()

    private fun unsubscribe() =
        newUnsubscribe(
            db = db,
            scheduler = scheduler,
            clock = clock,
            secrets = store,
            coordinator = coordinator,
            poster = poster,
        )

    private suspend fun awaitReal(check: suspend () -> Boolean) =
        withContext(Dispatchers.Default) {
            withTimeout(REAL_TIMEOUT_MS) {
                while (!check()) delay(POLL_MS)
            }
        }

    @Test
    fun unsubscribeDeletesTheLastReferenceCredential() =
        runTest {
            val origin = "https://a.example.com"
            val feed = "$origin/feed"
            val id = seedPodcast(db, feed, feedKey = UrlNormalizer.forIdentity(feed)!!)
            store.put(origin, "u", "p".toCharArray())!!.let { credentialId ->
                db.podcastDao().setCredentialId(id, credentialId)
            }

            unsubscribe()(listOf(id))

            assertNull(db.podcastDao().byId(id))
            assertTrue(db.credentialDao().byOrigin(origin).isEmpty())
            awaitReal { store.get(origin) == null }
        }

    @Test
    fun unsubscribeKeepsACredentialSharedWithAnotherPodcast() =
        runTest {
            val origin = "https://a.example.com"
            val one = "$origin/one"
            val two = "$origin/two"
            val a = seedPodcast(db, one, feedKey = UrlNormalizer.forIdentity(one)!!)
            val b = seedPodcast(db, two, feedKey = UrlNormalizer.forIdentity(two)!!)
            val rowId = store.put(origin, "u", "p".toCharArray())!!
            db.podcastDao().setCredentialId(a, rowId)
            db.podcastDao().setCredentialId(b, rowId)

            unsubscribe()(listOf(a))

            assertNull(db.podcastDao().byId(a))
            assertEquals(rowId, assertNotNull(db.podcastDao().byId(b)).credentialId)
            assertEquals(
                rowId,
                db
                    .credentialDao()
                    .byOrigin(origin)
                    .single()
                    .id,
            )
            assertEquals(basicAuthHeader("u", "p"), store.basicAuthorization(Origin("https", "a.example.com", 443)))
        }

    /**
     * The race 02's coordinator exists for: a subscribe parked between its `put` and the
     * `credentialId` commit holds a credential row a concurrent unsubscribe's last-reference
     * delete would otherwise remove from under it (FK violation). The unsubscribe must wait.
     */
    @Test
    fun unsubscribeWaitsForAnInFlightCredentialCommit() =
        runTest {
            val origin = "https://a.example.com"
            val oldFeed = "$origin/old"
            val p1 = seedPodcast(db, oldFeed, feedKey = UrlNormalizer.forIdentity(oldFeed)!!)
            val rowId = store.put(origin, "u", "p".toCharArray())!!
            db.podcastDao().setCredentialId(p1, rowId)

            // A subscribe for a second feed on the same origin parks mid-commit.
            val gate = GatedSecretStore(store)
            val newFeed = "$origin/new"
            resolverBundle.cache.put(
                previewId = newFeed,
                inputUrl = newFeed,
                feed = parsedFeed(items = listOf(parsedEpisode(0))),
                meta = fetchMeta(finalUrl = newFeed),
                hops = emptyList(),
                credentials = BasicCredentials("u2", "p2"),
            )
            val subscribe =
                newSubscribe(
                    db = db,
                    cache = resolverBundle.cache,
                    resolver = resolverBundle.resolver,
                    scheduler = scheduler,
                    settings = settings,
                    clock = clock,
                    secrets = gate,
                    coordinator = coordinator,
                )

            val subscribeJob =
                CoroutineScope(Dispatchers.Default).async {
                    subscribe.useCase(newFeed, emptySet())
                }
            awaitReal { gate.putCommitted.isCompleted }

            // `unsubscribe` reaches `withCredentialCommit` and parks on the held coordinator.
            val removed = CompletableDeferred<List<Long>>()
            val unsubscribeJob =
                CoroutineScope(Dispatchers.Default).launch {
                    removed.complete(unsubscribe()(listOf(p1)))
                }
            withContext(Dispatchers.Default) { delay(PARK_MS) }
            assertFalse(removed.isCompleted, "unsubscribe entered while the commit was open")

            gate.release.complete(Unit)
            // The subscribe committed — the row it referenced survived the waiting cascade.
            assertIs<Outcome.Success<Long>>(subscribeJob.await())
            assertEquals(listOf(p1), removed.await())
            unsubscribeJob.join()

            // P2 references the row the cascade tried to delete last-reference — it survives.
            val p2 = assertNotNull(db.podcastDao().byFeedKey(UrlNormalizer.forIdentity(newFeed)!!))
            assertEquals(rowId, p2.credentialId)
            assertEquals(
                rowId,
                db
                    .credentialDao()
                    .byOrigin(origin)
                    .single()
                    .id,
            )
            assertNull(db.podcastDao().byId(p1))
        }

    @Test
    fun notificationCleanupSeesOnlyCommittedRemovals() =
        runTest {
            val id = seedPodcast(db, "https://cleanup.example.com/feed")
            poster.onRemove = { ids ->
                assertEquals(listOf(id), ids)
                assertNull(db.podcastDao().byId(id))
            }

            assertEquals(listOf(id), unsubscribe()(listOf(id, Long.MAX_VALUE)))
            assertEquals(listOf(listOf(id)), poster.removals)
            unsubscribe()(listOf(id))
            assertEquals(1, poster.removals.size)
        }

    @Test
    fun failedUnsubscribeLeavesNotificationsAlone() =
        runTest {
            val id = seedPodcast(db, "https://cleanup.example.com/feed")
            db.useWriterConnection {
                it.execSql(
                    "CREATE TRIGGER fail_cleanup BEFORE DELETE ON podcast " +
                        "BEGIN SELECT RAISE(ABORT, 'unsub'); END",
                )
            }

            assertTrue(runCatching { unsubscribe()(listOf(id)) }.isFailure)
            assertTrue(poster.removals.isEmpty())
            assertNotNull(db.podcastDao().byId(id))
        }

    @Test
    fun notificationFailureDoesNotFailCommittedUnsubscribe() =
        runTest {
            val id = seedPodcast(db, "https://cleanup.example.com/feed")
            poster.onRemove = { error("notification service unavailable") }

            assertEquals(listOf(id), unsubscribe()(listOf(id)))
            assertNull(db.podcastDao().byId(id))
        }

    private companion object {
        const val REAL_TIMEOUT_MS = 10_000L
        const val POLL_MS = 25L

        /** How long a parked contender gets to prove it cannot enter the coordinator. */
        const val PARK_MS = 300L
    }
}
