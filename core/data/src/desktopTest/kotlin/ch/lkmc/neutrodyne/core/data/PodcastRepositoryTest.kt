// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.credentials.DesktopSecretStore
import ch.lkmc.neutrodyne.core.data.credentials.IdentitySecretProtection
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.repo.PodcastMerger
import ch.lkmc.neutrodyne.core.data.repo.PodcastRepositoryImpl
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.ChangeOrigin
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit4.MockWebServerRule
import okio.FileSystem
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `PodcastRepositoryImpl` over a real database: the `FeedMove` host contract, the artwork
 * version on the detail model and multi-podcast unsubscribe atomicity (03).
 */
class PodcastRepositoryTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private val clock = TestClock(NOW)
    private val db = newDb(clock)
    private val settings = FakeSettingsRepository()
    private val scheduler = FakeRefreshScheduler()
    private val coordinator = FakeCredentialCommitCoordinator()

    private fun repo(
        secrets: SecretStore = FakeSecretStore(),
        fetcherBundle: FetcherBundle? = null,
    ) = PodcastRepositoryImpl(
        db = db,
        refresher = newRefresher(db, emptyMap(), clock, settings),
        scheduler = scheduler,
        sanitizer = JsoupShowNotesSanitizer(),
        clock = clock,
        merger = PodcastMerger(db, coordinator, clock),
        resolver = unusedPreviewResolver,
        secrets = secrets,
        coordinator = coordinator,
        fetcher = Provider { fetcherBundle?.fetcher ?: error("no probe fetcher in this fixture") },
        tempFiles =
            fetcherBundle?.tempFiles
                ?: FeedTempFiles(
                    storagePathsFor(File("build/tmp/m1b-repo").resolve(UUID.randomUUID().toString())),
                    FileSystem.SYSTEM,
                ),
    )

    @Test
    fun aFeedMoveCarriesTheIdentityFormHost() =
        runTest {
            // Pin: alias URLs are persisted in `forIdentity` (scheme-less) form, so the moves
            // list's host extraction is `substringBefore('/')`, not a scheme strip.
            val id = seedPodcast(db, "feeds.example.com/show", feedKey = "feeds.example.com/show")
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(
                    url = "old.example.com/feed",
                    podcastId = id,
                    reason = AliasReason.REDIRECT,
                    addedAt = 42L,
                ),
            )

            val info = assertNotNull(repo().observeFeedInfo(id).first())

            assertEquals("old.example.com", info.moves.single().fromHost)
            assertEquals(AliasReason.REDIRECT, info.moves.single().reason)
        }

    @Test
    fun thePodcastDetailCarriesTheStoredArtworkVersion() =
        runTest {
            val id = seedPodcast(db, "https://a.example.com/f")
            val key = db.podcastDao().byId(id)!!.artworkKey
            db.artworkDao().upsert(ArtworkEntity(key = key, version = 7))

            val detail = assertNotNull(repo().observePodcast(id).first())

            assertEquals(7, detail.artwork.version)
        }

    @Test
    fun aMultiPodcastUnsubscribeIsAtomic() =
        runTest {
            // A crash mid-list must not leave a half-unsubscribed library (03 Unsubscribe).
            val a = seedPodcast(db, "https://a.example.com/f")
            val b = seedPodcast(db, "https://b.example.com/f")
            db.useWriterConnection {
                it.execSql(
                    "CREATE TRIGGER fail_unsub BEFORE DELETE ON podcast" +
                        " WHEN old.id = $b BEGIN SELECT RAISE(ABORT, 'unsub'); END",
                )
            }

            val result = runCatching { repo().unsubscribe(listOf(a, b), ChangeOrigin.LOCAL) }

            assertTrue(result.isFailure, "unsubscribe must propagate the DAO fault")
            assertNotNull(db.podcastDao().byId(a))
            assertNotNull(db.podcastDao().byId(b))
        }

    @Test
    fun anUnsubscribeOfOnePodcastStillCommits() =
        runTest {
            val id = seedPodcast(db, "https://a.example.com/f")

            val removed = repo().unsubscribe(listOf(id), ChangeOrigin.LOCAL)

            assertEquals(listOf(id), removed)
            assertNull(db.podcastDao().byId(id))
            assertTrue(scheduler.rescheduleCount > 0)
        }

    // --- 03 setCredentials ----------------------------------------------------------------------------

    private fun newSecretsStore(
        root: File,
        scope: CoroutineScope,
    ) = DesktopSecretStore(db, appDirsFor(root), coordinator, clock, IdentitySecretProtection, scope)

    /**
     * A refused probe rejects the pair with `AuthRequired` and stores nothing (03 Basic auth —
     * the feed answered 401 for exactly these credentials, so keeping them would just fail every
     * refresh the same way). `needsCredentials` stays set for the next prompt.
     */
    @Test
    fun aRefusedCredentialsProbeStoresNothing() =
        runTest {
            val root = Files.createTempDirectory("nd-setcreds").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = newSecretsStore(root, scope)
            val bundle = newFetcher(root)
            try {
                val feedUrl = server.url("/feed").toString()
                val id =
                    seedPodcast(
                        db,
                        feedUrl,
                        feedKey = UrlNormalizer.forIdentity(feedUrl)!!,
                        needsCredentials = true,
                    )
                server.enqueue(mockResponse(401, "", "WWW-Authenticate" to "Basic realm=\"members\""))

                val outcome =
                    repo(secrets = store, fetcherBundle = bundle)
                        .setCredentials(id, BasicCredentials("u", "p"))

                assertEquals(
                    AddPodcastError.AuthRequired("members"),
                    assertIs<Outcome.Failure<AddPodcastError>>(outcome).error,
                )
                val row = assertNotNull(db.podcastDao().byId(id))
                assertTrue(row.needsCredentials)
                assertNull(row.credentialId)
                assertTrue(db.credentialDao().byOrigin(UrlNormalizer.origin(feedUrl)!!).isEmpty())
                assertNull(store.get(UrlNormalizer.origin(feedUrl)!!))
                assertTrue(scheduler.nowRequests.isEmpty())
            } finally {
                bundle.close()
                scope.cancel()
                root.deleteRecursively()
            }
        }

    /**
     * A good probe: the credential lands under the feed's origin, the podcast row links it and
     * only `needsCredentials` clears — a `gone`/failure state is the refresher's business — then
     * a forced manual refresh of just that podcast proves the pair end to end (03 On refresh).
     */
    @Test
    fun setCredentialsStoresLinksClearsTheFlagAndSchedulesRefresh() =
        runTest {
            val root = Files.createTempDirectory("nd-setcreds").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = newSecretsStore(root, scope)
            val bundle = newFetcher(root)
            try {
                val feedUrl = server.url("/feed").toString()
                val id =
                    seedPodcast(
                        db,
                        feedUrl,
                        feedKey = UrlNormalizer.forIdentity(feedUrl)!!,
                        needsCredentials = true,
                    )
                server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("e1")))))

                val outcome =
                    repo(secrets = store, fetcherBundle = bundle)
                        .setCredentials(id, BasicCredentials("u", "p"))

                assertIs<Outcome.Success<Unit>>(outcome)
                val origin = UrlNormalizer.origin(feedUrl)!!
                val row = assertNotNull(db.podcastDao().byId(id))
                val credRow = db.credentialDao().byOrigin(origin).single()
                assertEquals(credRow.id, row.credentialId)
                assertFalse(row.needsCredentials)
                assertEquals("u", store.get(origin)!!.username)
                assertEquals(
                    listOf(
                        FakeRefreshScheduler.NowRequest(
                            scope = RefreshScope.Podcasts(listOf(id)),
                            force = true,
                            pagesOnly = false,
                            origin = RefreshOrigin.MANUAL,
                        ),
                    ),
                    scheduler.nowRequests,
                )
            } finally {
                bundle.close()
                scope.cancel()
                root.deleteRecursively()
            }
        }

    /**
     * The probe's temp body belongs to the caller of `fetch` (03): `setCredentials` must not
     * leave it behind in the feeds temp dir.
     */
    @Test
    fun setCredentialsDeletesTheProbeBody() =
        runTest {
            val root = Files.createTempDirectory("nd-setcreds").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = newSecretsStore(root, scope)
            val bundle = newFetcher(root)
            try {
                val feedUrl = server.url("/feed").toString()
                val id =
                    seedPodcast(
                        db,
                        feedUrl,
                        feedKey = UrlNormalizer.forIdentity(feedUrl)!!,
                        needsCredentials = true,
                    )
                server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("e1")))))

                repo(secrets = store, fetcherBundle = bundle)
                    .setCredentials(id, BasicCredentials("u", "p"))

                val feedsDir = File(root, "cache/feeds")
                assertTrue(
                    feedsDir.listFiles().orEmpty().none { it.name.startsWith("tmp-") },
                    "probe body left in ${feedsDir.absolutePath}: ${feedsDir.list().orEmpty().toList()}",
                )
            } finally {
                bundle.close()
                scope.cancel()
                root.deleteRecursively()
            }
        }

    private companion object {
        const val NOW = 1_791_000_000_000L
    }
}
