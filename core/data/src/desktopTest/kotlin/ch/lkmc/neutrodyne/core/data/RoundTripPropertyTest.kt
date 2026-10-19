// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.importing.ImportClassifier
import ch.lkmc.neutrodyne.core.data.importing.ImportCommitter
import ch.lkmc.neutrodyne.core.data.importing.ImportSessions
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.model.CreateImportOptions
import ch.lkmc.neutrodyne.core.model.ExportLayout
import ch.lkmc.neutrodyne.core.model.ExportRequest
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import ch.lkmc.neutrodyne.feeds.jvm.opml.XmlPullOpmlReader
import ch.lkmc.neutrodyne.feeds.opml.OpmlLayout
import ch.lkmc.neutrodyne.feeds.opml.OpmlWriter
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * PLAN M3 acceptance 2 (and 7's membership half): random canonical groups — names carrying
 * `,` `/` `%`, emoji and a literal U+FFFD — with overlaps, empty groups and multi-memberships
 * round-trip through the real `OpmlExporter`/`OpmlWriter` into a real `ImportSessions` +
 * `ImportCommitter` on an empty Room database. Group order and the membership relation are
 * identical; group uuids and row ids are not equality keys under the nameKey/feedKey rules
 * (05 Merge). All OrderKeys on both sides are the real `OrderKey` port ([ProtocolOrderKeys]).
 *
 * Bounded decisions this test encodes instead of re-litigating (05 OPML export / Import):
 * flat layout promises only the membership relation — group order, empty groups, colours and
 * icons are out of its file — and a document with zero feed outlines is `ImportError.Empty`,
 * not a property failure.
 */
class RoundTripPropertyTest {
    private val clock = TestClock()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("nd-roundtrip").toFile()
    }

    @AfterTest
    fun tearDown() {
        appScope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun groupedRoundTripPreservesGroupOrderAndMemberships() =
        runTest {
            for (seed in SEEDS) {
                val source = TestDb.inMemory(clock = clock)
                val target = newTarget()
                try {
                    seedRandomLibrary(source, seed)
                    val bytes = export(source, OpmlLayout.GROUPED)
                    assertCommitted(target, bytes)
                    assertLibraryEqual(source, target.db)
                } finally {
                    source.close()
                    target.db.close()
                }
            }
        }

    @Test
    fun groupedRoundTripKeepsEmptyGroupsAndSpecialNames() =
        runTest {
            val source = TestDb.inMemory(clock = clock)
            val target = newTarget()
            try {
                // Comma, slash and percent are percent-escaped in `category`; emoji and a literal
                // U+FFFD are valid Unicode that must not be mangled (A6's fix rejects malformed
                // bytes, never valid text).
                val names = listOf("news, tech", "a/b", "100% legit", "🎧📻 radio", "queen\uFFFD day")
                val keys = ProtocolOrderKeys.rewrite(names.size)
                val groupIds =
                    names.mapIndexed { i, name ->
                        source.groupDao().insertGroupOrIgnore(groupEntity(name = name, orderKey = keys[i]))
                    }
                // "100% legit" stays empty — the grouped layout promises empty folders survive.
                val p0 = seedFeed(source, 0)
                val p1 = seedFeed(source, 1)
                source.groupDao().insertMember(memberEntity(groupIds[0], p0, ProtocolOrderKeys.rewrite(2)[0]))
                source.groupDao().insertMember(memberEntity(groupIds[0], p1, ProtocolOrderKeys.rewrite(2)[1]))
                source.groupDao().insertMember(memberEntity(groupIds[1], p0, ProtocolOrderKeys.rewrite(1)[0]))
                source.groupDao().insertMember(memberEntity(groupIds[3], p0, ProtocolOrderKeys.rewrite(1)[0]))

                val bytes = export(source, OpmlLayout.GROUPED)
                assertCommitted(target, bytes)
                assertLibraryEqual(source, target.db)
            } finally {
                source.close()
                target.db.close()
            }
        }

    @Test
    fun flatRoundTripKeepsOnlyTheMembershipRelation() =
        runTest {
            val source = TestDb.inMemory(clock = clock)
            val target = newTarget()
            try {
                val names = listOf("first, kept", "second/kept", "empty stays local")
                val keys = ProtocolOrderKeys.rewrite(names.size)
                val groupIds =
                    names.mapIndexed { i, name ->
                        source.groupDao().insertGroupOrIgnore(groupEntity(name = name, orderKey = keys[i]))
                    }
                val p0 = seedFeed(source, 0)
                val p1 = seedFeed(source, 1)
                source.groupDao().insertMember(memberEntity(groupIds[0], p0, ProtocolOrderKeys.rewrite(1)[0]))
                source.groupDao().insertMember(memberEntity(groupIds[1], p0, ProtocolOrderKeys.rewrite(1)[0]))
                source.groupDao().insertMember(memberEntity(groupIds[1], p1, ProtocolOrderKeys.rewrite(1)[0]))

                val bytes = export(source, OpmlLayout.FLAT)
                assertCommitted(target, bytes)

                // Flat keeps `category` tags only: the relation must hold but group order, the
                // empty group and colours are not promised (05 Structure). Compare unordered.
                val targetMemberships = membershipsByName(target.db)
                assertEquals(
                    mapOf(
                        "first, kept" to setOf(feedKeyOf(FEEDS[0])),
                        "second/kept" to setOf(feedKeyOf(FEEDS[0]), feedKeyOf(FEEDS[1])),
                    ),
                    targetMemberships,
                )
            } finally {
                source.close()
                target.db.close()
            }
        }

    @Test
    fun onlyEmptyGroupsIsTheDocumentedEmpty() =
        runTest {
            val source = TestDb.inMemory(clock = clock)
            val target = newTarget()
            try {
                source
                    .groupDao()
                    .insertGroupOrIgnore(groupEntity(name = "empty", orderKey = ProtocolOrderKeys.rewrite(1)[0]))
                val bytes = export(source, OpmlLayout.GROUPED)

                target.payloads.register(OPML_URI, bytes = bytes, displayName = "empty.opml")
                // 05's failure table: folder outlines but no feed outlines → Empty, not a
                // zero-item session (the parse itself succeeded — no manufactured failure).
                val created = target.sessions.create(ImportSource(OPML_URI, "empty.opml"), CreateImportOptions())
                assertEquals(ImportError.Empty, assertIs<Outcome.Failure<*>>(created).error)
            } finally {
                source.close()
                target.db.close()
            }
        }

    @Test
    fun importIntoExistingAddsMembershipsWithoutDuplicating() =
        runTest {
            val source = TestDb.inMemory(clock = clock)
            val target = newTarget()
            try {
                val g =
                    source
                        .groupDao()
                        .insertGroupOrIgnore(
                            groupEntity(name = "shared", orderKey = ProtocolOrderKeys.rewrite(1)[0]),
                        )
                val p0 = seedFeed(source, 0)
                val p1 = seedFeed(source, 1)
                source.groupDao().insertMember(memberEntity(g, p0, ProtocolOrderKeys.rewrite(2)[0]))
                source.groupDao().insertMember(memberEntity(g, p1, ProtocolOrderKeys.rewrite(2)[1]))

                // The importing device already has feed 0 and its own group.
                val before =
                    target.db
                        .podcastDao()
                        .insertPodcast(
                            podcastEntity(
                                feedUrl = FEEDS[0],
                                feedKey = feedKeyOf(FEEDS[0]),
                                title = "Local title",
                                subscribedAt = 42L,
                                syncId = "bbbbbbbb-1111-4111-8111-bbbbbbbbbbbb",
                            ),
                        )
                target
                    .db
                    .groupDao()
                    .insertGroupOrIgnore(groupEntity(name = "existing", orderKey = ProtocolOrderKeys.rewrite(1)[0]))

                assertCommitted(target, export(source, OpmlLayout.GROUPED))

                // Matched on feedKey: same row, same local identity — no duplicate.
                val matched = assertNotNull(target.db.podcastDao().byFeedKey(feedKeyOf(FEEDS[0])))
                assertEquals(before, matched.id)
                assertEquals("bbbbbbbb-1111-4111-8111-bbbbbbbbbbbb", matched.syncId)
                assertEquals(42L, matched.subscribedAt)
                assertEquals("Local title", matched.title)
                assertEquals(
                    setOf(feedKeyOf(FEEDS[0]), feedKeyOf(FEEDS[1])),
                    target.db
                        .podcastDao()
                        .allForExport()
                        .map { it.feedKey }
                        .toSet(),
                )

                // The imported group appended after the existing tail; feed 0 joined it.
                val groupNames =
                    target.db
                        .groupDao()
                        .groupsInOrder()
                        .map { it.name }
                assertEquals(listOf("existing", "shared"), groupNames)
                val shared =
                    target.db
                        .groupDao()
                        .groupsInOrder()
                        .single { it.name == "shared" }
                assertEquals(
                    setOf(feedKeyOf(FEEDS[0]), feedKeyOf(FEEDS[1])),
                    target.db
                        .groupDao()
                        .membersOf(shared.id)
                        .map { member ->
                            checkNotNull(target.db.podcastDao().byId(member.podcastId)).feedKey
                        }.toSet(),
                )
            } finally {
                source.close()
                target.db.close()
            }
        }

    // --- Harness: real exporter + real session/commit path over two Room databases -------------

    private class Target(
        val db: NeutrodyneDatabase,
        val payloads: FakePayloadSource,
        val sessions: ImportSessions,
        val committer: ImportCommitter,
    )

    private fun newTarget(): Target {
        val db = TestDb.inMemory(clock = clock)
        val payloads = FakePayloadSource()
        val sessions =
            ImportSessions(
                db = db,
                payloads =
                    PayloadStore(
                        storagePaths = storagePathsFor(root),
                        fileSystem = FileSystem.SYSTEM,
                        payloads = payloads,
                        httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
                        netErrors = testClassifier,
                        io = Dispatchers.Default,
                    ),
                classifier = ImportClassifier(db, YouTubeUrlClassifier()),
                opmlReader = XmlPullOpmlReader.discovered(),
                archives = ZipGuard(),
                clock = clock,
                appScope = appScope,
                io = Dispatchers.Default,
            )
        val committer =
            ImportCommitter(
                db = db,
                secrets = FakeSecretStore(),
                coordinator = FakeCredentialCommitCoordinator(),
                workScheduler = FakeImportWorkScheduler(),
                refresh = FakeRefreshController(),
                channels = FakeGroupChannelSync(),
                orderKeys = ProtocolOrderKeys,
                clock = clock,
                newSyncId = { Uuid.random().toString() },
            )
        return Target(db, payloads, sessions, committer)
    }

    /** Real `OpmlExporter` snapshot → real `OpmlWriter` bytes (the same code `BackupWriter` uses). */
    private suspend fun export(
        source: NeutrodyneDatabase,
        layout: OpmlLayout,
    ): ByteArray {
        val request =
            ExportRequest(
                layout =
                    when (layout) {
                        OpmlLayout.GROUPED -> ExportLayout.GROUPED
                        OpmlLayout.FLAT -> ExportLayout.FLAT
                    },
            )
        val built =
            OpmlExporter(source, FakeSecretStore(), clock, Dispatchers.Default).build(request)
                ?: error("export produced no document")
        val buffer = Buffer()
        OpmlWriter.write(buffer, built.document, layout)
        return buffer.readByteArray()
    }

    private suspend fun assertCommitted(
        target: Target,
        bytes: ByteArray,
    ) {
        target.payloads.register(OPML_URI, bytes = bytes, displayName = "roundtrip.opml")
        val created = target.sessions.create(ImportSource(OPML_URI, "roundtrip.opml"), CreateImportOptions())
        val sessionId = assertIs<Outcome.Success<Long>>(created).value
        assertIs<Outcome.Success<*>>(target.committer.confirm(sessionId))
    }

    // --- Seeding + comparison ----------------------------------------------------------------

    /** Deterministic per seed: distinct names, subsets drawn from [Random], forced edge shapes. */
    private suspend fun seedRandomLibrary(
        source: NeutrodyneDatabase,
        seed: Int,
    ) {
        val random = Random(seed)
        val names = NAME_POOL.shuffled(random).take(3 + random.nextInt(4))
        val groupKeys = ProtocolOrderKeys.rewrite(names.size)
        val groupIds =
            names.mapIndexed { i, name ->
                source.groupDao().insertGroupOrIgnore(groupEntity(name = name, orderKey = groupKeys[i]))
            }

        val feedCount = 4 + random.nextInt(5)
        val feedIds = (0 until feedCount).map { seedFeed(source, it) }
        for ((index, podcastId) in feedIds.withIndex()) {
            // Feed 0 multi-belongs, feed 1 stays ungrouped, the rest draw a random subset.
            val memberOf =
                when (index) {
                    0 -> groupIds.take(2)
                    1 -> emptyList()
                    else -> groupIds.filter { random.nextBoolean() }
                }
            val memberKeys = if (memberOf.isEmpty()) emptyList() else ProtocolOrderKeys.rewrite(memberOf.size)
            memberOf.forEachIndexed { i, groupId ->
                source.groupDao().insertMember(memberEntity(groupId, podcastId, memberKeys[i]))
            }
        }
    }

    private suspend fun seedFeed(
        source: NeutrodyneDatabase,
        index: Int,
    ): Long =
        source
            .podcastDao()
            .insertPodcast(
                podcastEntity(
                    feedUrl = FEEDS[index],
                    feedKey = feedKeyOf(FEEDS[index]),
                    title = "Show $index",
                    subscribedAt = 1_000L + index,
                ),
            )

    /** The logical view the AC compares: group-name order and name → member feedKey sets. */
    private suspend fun assertLibraryEqual(
        expected: NeutrodyneDatabase,
        actual: NeutrodyneDatabase,
    ) {
        assertEquals(
            expected.groupDao().groupsInOrder().map { it.name },
            actual.groupDao().groupsInOrder().map { it.name },
            "group order",
        )
        assertEquals(membershipsByName(expected), membershipsByName(actual), "membership relation")
        assertEquals(
            expected
                .podcastDao()
                .allForExport()
                .map { it.feedKey }
                .sorted(),
            actual
                .podcastDao()
                .allForExport()
                .map { it.feedKey }
                .sorted(),
            "subscribed feeds",
        )
    }

    private suspend fun membershipsByName(db: NeutrodyneDatabase): Map<String, Set<String>> =
        db.groupDao().groupsInOrder().associate { group ->
            group.name to
                db
                    .groupDao()
                    .membersOf(group.id)
                    .map { checkNotNull(db.podcastDao().byId(it.podcastId)).feedKey }
                    .toSet()
        }

    private companion object {
        const val OPML_URI = "file:///roundtrip.opml"

        val SEEDS = listOf(11, 42, 2026)

        /** Already `GroupNames`-normalised: ≤40 code points, collapsed whitespace, distinct keys. */
        val NAME_POOL =
            listOf(
                "news, tech",
                "a/b",
                "100% legit",
                "🎧📻 radio",
                "queen\uFFFD day",
                "morning",
                "commute",
                "music",
                "longform",
                "video",
            )

        val FEEDS = (0 until 10).map { "https://feeds.example.com/show-$it/rss.xml" }

        fun feedKeyOf(feedUrl: String): String = checkNotNull(UrlNormalizer.forIdentity(feedUrl))
    }
}
