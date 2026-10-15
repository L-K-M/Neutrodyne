// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.common.Origin
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.credentials.DesktopSecretStore
import ch.lkmc.neutrodyne.core.data.credentials.IdentitySecretProtection
import ch.lkmc.neutrodyne.core.data.fetch.RedirectHop
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
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
 * `SubscribeUseCaseImpl`'s credential commit (03 Subscribe transaction; 02 credential): a preview
 * that carries credentials puts them under the ORIGINAL request's origin inside
 * `withCredentialCommit`, links `podcast.credentialId` in the same commit, and cleans the put up
 * when the transaction commits no reference. The store is a real `DesktopSecretStore` on a temp
 * data dir — the reference-counted cleanup only exists against real `credential` rows.
 */
class SubscribeUseCaseTest {
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
        root = Files.createTempDirectory("nd-subscribe-creds").toFile()
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

    private fun useCase(secrets: SecretStore = store) =
        newSubscribe(
            db = db,
            cache = resolverBundle.cache,
            resolver = resolverBundle.resolver,
            scheduler = scheduler,
            settings = settings,
            clock = clock,
            secrets = secrets,
            coordinator = coordinator,
        )

    private suspend fun awaitLoaded() =
        withContext(Dispatchers.Default) {
            withTimeout(REAL_TIMEOUT_MS) { store.awaitLoaded() }
        }

    private suspend fun awaitReal(check: suspend () -> Boolean) =
        withContext(Dispatchers.Default) {
            withTimeout(REAL_TIMEOUT_MS) {
                while (!check()) delay(POLL_MS)
            }
        }

    /**
     * 02's literal orphan-sweep statement (db-maintenance step 2), run inside the coordinator the
     * way `db-maintenance` will once the maintenance worker lands.
     */
    private suspend fun runCredentialSweep() =
        coordinator.withCredentialCommit {
            db.useWriterConnection {
                it.execSql(
                    "DELETE FROM credential WHERE origin <> 'podcastindex' AND" +
                        " origin NOT LIKE 'sync:%' AND id NOT IN" +
                        " (SELECT credentialId FROM podcast WHERE credentialId IS NOT NULL)",
                )
            }
        }

    @Test
    fun subscribePersistsThePreviewCredentialsUnderTheRequestedOrigin() =
        runTest {
            val url = "https://a.example.com/feed"
            resolverBundle.cache.put(
                previewId = url,
                inputUrl = url,
                feed = parsedFeed(items = listOf(parsedEpisode(0))),
                meta = fetchMeta(finalUrl = url),
                hops = emptyList(),
                credentials = BasicCredentials("alice", "s3cret"),
            )

            val id =
                assertIs<Outcome.Success<Long>>(useCase().useCase(url, emptySet())).value

            val row = assertNotNull(db.podcastDao().byId(id))
            val credRow =
                db
                    .credentialDao()
                    .byOrigin("https://a.example.com")
                    .single()
            assertEquals(credRow.id, row.credentialId)
            assertEquals("alice", store.get("https://a.example.com")!!.username)
            // The published map answers the interceptor's per-hop lookup.
            assertEquals(
                basicAuthHeader("alice", "s3cret"),
                store.basicAuthorization(Origin("https", "a.example.com", 443)),
            )
        }

    @Test
    fun subscribeKeepsTheCredentialOnTheRequestedOriginAcrossACrossOriginRedirect() =
        runTest {
            val requested = "https://a.example.com/feed"
            val moved = "https://b.example.com/feed"
            resolverBundle.cache.put(
                previewId = moved,
                inputUrl = requested,
                feed = parsedFeed(items = listOf(parsedEpisode(0))),
                meta = fetchMeta(finalUrl = moved, requestedUrl = requested, permanentUrl = moved),
                hops = listOf(RedirectHop(url = requested, status = 301)),
                credentials = BasicCredentials("u", "p"),
            )

            val id =
                assertIs<Outcome.Success<Long>>(useCase().useCase(moved, emptySet())).value

            // The podcast follows the permanent move; the credential stays where it was sent.
            assertEquals(moved, db.podcastDao().byId(id)!!.feedUrl)
            val credRow =
                db
                    .credentialDao()
                    .byOrigin("https://a.example.com")
                    .single()
            assertEquals(credRow.id, db.podcastDao().byId(id)!!.credentialId)
            assertTrue(db.credentialDao().byOrigin("https://b.example.com").isEmpty())
            assertNull(store.basicAuthorization(Origin("https", "b.example.com", 443)))
        }

    @Test
    fun aFailedSubscribeRemovesAnUnreferencedCredential() =
        runTest {
            // The ingest's `emptyKind` rolls the transaction back after the put committed.
            val url = "https://a.example.com/list"
            resolverBundle.cache.put(
                previewId = url,
                inputUrl = url,
                feed = parsedFeed(items = emptyList(), medium = "podcastL"),
                meta = fetchMeta(finalUrl = url),
                hops = emptyList(),
                credentials = BasicCredentials("u", "p"),
            )

            assertIs<Outcome.Failure<SubscribeError>>(useCase().useCase(url, emptySet()))

            assertTrue(db.credentialDao().byOrigin("https://a.example.com").isEmpty())
            assertNull(store.get("https://a.example.com"))
        }

    @Test
    fun aDedupedSubscribeKeepsACredentialStillReferencedElsewhere() =
        runTest {
            // An existing podcast already references the origin's row.
            val origin = "https://a.example.com"
            val existing = seedPodcast(db, "$origin/one", feedKey = UrlNormalizer.forIdentity("$origin/one")!!)
            val rowId = store.put(origin, "old", "oldpass".toCharArray())!!
            db.podcastDao().setCredentialId(existing, rowId)

            // A re-subscribe of the same feed dedupes — after `put` replaced the shared row's
            // secret (03 "one credential per origin"). The replace must survive the dedupe.
            val url = "$origin/one"
            resolverBundle.cache.put(
                previewId = url,
                inputUrl = url,
                feed = parsedFeed(items = listOf(parsedEpisode(0))),
                meta = fetchMeta(finalUrl = url),
                hops = emptyList(),
                credentials = BasicCredentials("new", "newpass"),
            )

            val outcome = useCase().useCase(url, emptySet())

            assertEquals(
                SubscribeError.AlreadySubscribed(existing),
                assertIs<Outcome.Failure<SubscribeError>>(outcome).error,
            )
            assertEquals(
                rowId,
                db
                    .credentialDao()
                    .byOrigin(origin)
                    .single()
                    .id,
            )
            assertEquals("newpass", store.get(origin)!!.secret.concatToString())
        }

    @Test
    fun theCredentialSweepWaitsForAnInFlightSubscribeCommit() =
        runTest {
            val gate = GatedSecretStore(store)
            val url = "https://a.example.com/feed"
            resolverBundle.cache.put(
                previewId = url,
                inputUrl = url,
                feed = parsedFeed(items = listOf(parsedEpisode(0))),
                meta = fetchMeta(finalUrl = url),
                hops = emptyList(),
                credentials = BasicCredentials("u", "p"),
            )
            // A genuinely orphaned row the sweep must still delete — proof it ran.
            store.put("https://orphan.test", "o", "x".toCharArray())

            // The subscribe parks inside `withCredentialCommit`: the row exists, the podcast's
            // `credentialId` not yet.
            val subscribe =
                CoroutineScope(Dispatchers.Default).async {
                    useCase(gate).useCase(url, emptySet())
                }
            awaitReal { gate.putCommitted.isCompleted }
            val sweepRan = CompletableDeferred<Unit>()
            val sweep =
                CoroutineScope(Dispatchers.Default).launch {
                    runCredentialSweep()
                    sweepRan.complete(Unit)
                }

            withContext(Dispatchers.Default) { delay(PARK_MS) }
            assertFalse(sweepRan.isCompleted, "the sweep entered while the commit was open")

            gate.release.complete(Unit)
            val id = assertIs<Outcome.Success<Long>>(subscribe.await()).value
            sweep.join()

            val credRow = db.credentialDao().byOrigin("https://a.example.com").single()
            assertEquals(credRow.id, db.podcastDao().byId(id)!!.credentialId)
            assertTrue(db.credentialDao().byOrigin("https://orphan.test").isEmpty())
        }

    @Test
    fun anAbandonedPreviewPersistsNothing() =
        runTest {
            resolverBundle.cache.put(
                previewId = "https://a.example.com/feed",
                inputUrl = "https://a.example.com/feed",
                feed = parsedFeed(items = listOf(parsedEpisode(0))),
                meta = fetchMeta(finalUrl = "https://a.example.com/feed"),
                hops = emptyList(),
                credentials = BasicCredentials("u", "p"),
            )

            resolverBundle.cache.remove("https://a.example.com/feed")

            assertNull(store.get("https://a.example.com"))
            assertTrue(
                db
                    .credentialDao()
                    .byOrigin("https://a.example.com")
                    .isEmpty(),
            )
        }

    private companion object {
        const val REAL_TIMEOUT_MS = 10_000L
        const val POLL_MS = 25L

        /** How long the sweep gets to prove it is parked on the coordinator. */
        const val PARK_MS = 300L
    }
}
