// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.credentials.DesktopSecretStore
import ch.lkmc.neutrodyne.core.data.credentials.IdentitySecretProtection
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.repo.PodcastMerger
import ch.lkmc.neutrodyne.core.data.repo.PodcastRepositoryImpl
import ch.lkmc.neutrodyne.core.domain.ImportSessionOperations
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeDownloadLifecycle
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.junit4.MockWebServerRule
import okio.FileSystem
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Import report ownership over real credential probes and the desktop secret store. */
class ImportCredentialOrderTest {
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
        merger =
            PodcastMerger(
                db,
                coordinator,
                clock,
                downloads =
                    Provider {
                        FakeDownloadLifecycle { db.downloadDao().removeUnclaimed(it) }
                    },
            ),
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
        downloads = Provider { FakeDownloadLifecycle { db.downloadDao().removeUnclaimed(it) } },
    )

    private fun newSecretsStore(
        root: File,
        scope: CoroutineScope,
    ) = DesktopSecretStore(db, appDirsFor(root), coordinator, clock, IdentitySecretProtection, scope)

    @Test fun newerPasswordSubmissionWinsAfterAnOlderProbeCompletes() =
        runBlocking {
            val root = Files.createTempDirectory("nd-review-creds").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = newSecretsStore(root, scope)
            val bundle = newFetcher(root)
            val oldProbeEntered = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val sequence = AtomicInteger()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (sequence.getAndIncrement() == 0) {
                            oldProbeEntered.complete(Unit)
                            runBlocking { releaseOld.await() }
                        }
                        return mockResponse(body = rssBody(items = arrayOf(rssItem("e1"))))
                    }
                }
            try {
                val url = server.url("/feed").toString()
                val id = seedPodcast(db, url, feedKey = UrlNormalizer.forIdentity(url)!!, needsCredentials = true)
                val repository = repo(store, bundle)
                val operations = ImportSessionOperations(scope)
                val old =
                    checkNotNull(
                        operations.start(id) {
                            repository.setCredentials(id, BasicCredentials("older", "old"))
                        },
                    )
                withTimeout(10_000) { oldProbeEntered.await() }
                val newer = operations.start(id) { repository.setCredentials(id, BasicCredentials("newer", "new")) }
                assertEquals(null, newer, "A newer route must not accept an overlapping credential probe")
                releaseOld.complete(Unit)
                assertIs<Outcome.Success<Unit>>(old.await())
                val accepted =
                    checkNotNull(
                        operations.start(id) {
                            repository.setCredentials(id, BasicCredentials("newer", "new"))
                        },
                    )
                assertIs<Outcome.Success<Unit>>(accepted.await())
                assertEquals(
                    "newer",
                    store.get(UrlNormalizer.origin(url)!!)!!.username,
                    "The latest submitted credentials must remain stored after the older route's probe completes",
                )
            } finally {
                releaseOld.complete(Unit)
                bundle.close()
                scope.cancel()
                root.deleteRecursively()
                db.close()
            }
        }

    private companion object {
        const val NOW = 1_791_000_000_000L
    }
}
