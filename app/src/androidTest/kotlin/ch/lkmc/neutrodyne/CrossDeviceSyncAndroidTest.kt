// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.util.Log
import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.common.getOrNull
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.domain.OrderedRow
import ch.lkmc.neutrodyne.core.domain.planOrderKeyWrites
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.sync.SyncAddress
import ch.lkmc.neutrodyne.core.testing.sync.canonicalSyncSnapshot
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.KeyInput
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.LinkMethod
import ch.lkmc.neutrodyne.sync.api.LinkState
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.api.SyncRoundSummary
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import ch.lkmc.neutrodyne.sync.impl.RawSyncRecord
import ch.lkmc.neutrodyne.sync.protocol.OrderKey
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * The Android half of E12 (`CrossDeviceSyncTest`, 09 End-to-end journeys): a host-driven phase
 * driver over the real `AndroidAppGraph`, Room and `SyncController`/`KtorSyncClient`.
 *
 * The host installs `files/cross-device/config.json` with `adb run-as` (stdin, so the password
 * never reaches the command line or logcat), then invokes one phase at a time:
 *
 * ```
 * adb shell am instrument -e crossDevice true -e crossDevicePhase seed|link|edit|sync|dump \
 *   -w ch.lkmc.neutrodyne.test/ch.lkmc.neutrodyne.NeutrodyneTestRunner
 * ```
 *
 * `NeutrodyneTestRunner` excludes this class before discovery unless `crossDevice=true` (09's AGP
 * test engine treats assumptions as failures). The class never starts an Activity: the public
 * sync service flow is enough. Every phase writes `files/cross-device/result.json` with safe
 * counts/errors only; the host reads it with `run-as`.
 *
 * Scope: this is the Android driver only. The desktop half, the host coordinator, the nightly job
 * and the real-device/emulator proof are owned elsewhere and remain unverified.
 */
@RunWith(AndroidJUnit4::class)
class CrossDeviceSyncAndroidTest {
    @Test
    fun runRequestedPhase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("crossDevice") == "true") {
            "CrossDeviceSyncAndroidTest requires the crossDevice=true instrumentation argument"
        }
        val phase =
            arguments.getString("crossDevicePhase")
                ?: throw AssertionError("crossDevicePhase instrumentation argument is required")
        val application =
            instrumentation.targetContext.applicationContext as NeutrodyneApplication
        runBlocking(Dispatchers.IO) {
            CrossDevicePhaseRunner(application, phase).run()
        }
    }
}

/**
 * One phase invocation. The config is read from the app's private `files/cross-device/` path;
 * the ownership marker proves the same run owns the database between phases. Every failure is
 * written to `result.json` with a field/phase message only, then rethrown so the instrumentation
 * status stays red.
 */
private class CrossDevicePhaseRunner(
    private val app: NeutrodyneApplication,
    private val phase: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val resultFile = File(app.filesDir, "cross-device/result.json")

    suspend fun run() {
        var runId: String? = null
        try {
            val config = readConfig()
            validateConfig(config)
            runId = config.runId
            val graph = app.graph
            graph.databaseOpener.awaitOpen()
            val payload =
                when (phase) {
                    "seed" -> seed(graph, config)
                    "link" -> link(graph, config)
                    "edit" -> edit(graph, config)
                    "sync" -> sync(graph, config)
                    "dump" -> dump(graph, config)
                    else -> throw PhaseError("phase", "unknown crossDevicePhase")
                }
            writeResult(result(config.runId, phase, ok = true, error = null, payload))
            Log.i(TAG, "phase=$phase ok=true")
        } catch (failure: PhaseError) {
            writeResult(result(runId, phase, ok = false, error = failure.safeMessage, payload = safeCounts()))
            Log.i(TAG, "phase=$phase ok=false error=${failure.safeMessage}")
            throw AssertionError(failure.safeMessage, failure)
        } catch (failure: Throwable) {
            val safe = PhaseError("phase", failure::class.simpleName ?: "failure")
            writeResult(result(runId, phase, ok = false, error = safe.safeMessage, payload = safeCounts()))
            Log.i(TAG, "phase=$phase ok=false error=${safe.safeMessage}")
            throw AssertionError(safe.safeMessage, failure)
        }
    }

    // --- config and ownership -------------------------------------------------------------------

    private fun readConfig(): CrossDeviceConfig {
        val file = File(app.filesDir, "cross-device/config.json")
        if (!file.isFile) throw PhaseError("config", "config file is missing")
        val root =
            try {
                json.parseToJsonElement(file.readText())
            } catch (_: Exception) {
                throw PhaseError("config", "config file is not valid JSON")
            }
        val obj = root as? JsonObject ?: throw PhaseError("config", "config file must be a JSON object")
        return CrossDeviceConfig(
            runId = obj.string("runId").orEmpty(),
            serverUrl = obj.string("serverUrl").orEmpty(),
            username = obj.string("username").orEmpty(),
            password = obj.string("password").orEmpty(),
            feedBaseUrl = obj.string("feedBaseUrl").orEmpty(),
            edits = parseEdits(obj),
        )
    }

    private fun parseEdits(obj: JsonObject): List<CrossDeviceEdit> {
        val array = obj["edits"] as? JsonArray ?: return emptyList()
        return array.mapIndexed { index, element ->
            val entry =
                element as? JsonObject
                    ?: throw PhaseError("config", "edits[$index] must be an object")
            CrossDeviceEdit(
                op =
                    entry.string("op")
                        ?: throw PhaseError("config", "edits[$index].op is required"),
                podcast = entry.int("podcast"),
                episode = entry.int("episode"),
                group = entry.int("group"),
                value = entry.long("value"),
                fav = entry.bool("fav"),
                before = entry.int("before"),
            )
        }
    }

    private fun validateConfig(config: CrossDeviceConfig) {
        if (config.runId.isBlank()) throw PhaseError("config", "field 'runId' is required")
        requireLoopbackHttp(config.serverUrl, "serverUrl")
        if (config.username.isBlank()) throw PhaseError("config", "field 'username' is required")
        if (config.password.isEmpty()) throw PhaseError("config", "field 'password' is required")
        requireLoopbackHttp(config.feedBaseUrl, "feedBaseUrl")
    }

    /**
     * The server and feed base must be loopback `http` with an explicit port and no credentials,
     * query or fragment: the future host reaches them through `adb reverse`, and an arbitrary
     * remote server is refused before any request.
     */
    private fun requireLoopbackHttp(
        value: String,
        field: String,
    ) {
        val uri =
            try {
                URI(value)
            } catch (_: Exception) {
                throw PhaseError("config", "field '$field' must be a loopback http URL")
            }
        val host = uri.host
        val loopback = host != null && (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true))
        if (!loopback || uri.scheme != "http" || uri.port <= 0 ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
        ) {
            throw PhaseError(
                "config",
                "field '$field' must be http://127.0.0.1:<port> or http://localhost:<port>" +
                    " with no credentials, query or fragment",
            )
        }
    }

    private fun requireOwnership(
        runId: String,
        phase: String,
    ) {
        val markerFile = File(app.filesDir, "cross-device/ownership.json")
        if (!markerFile.isFile) {
            throw PhaseError(phase, "no ownership marker; run the seed phase first")
        }
        val marker =
            try {
                json.parseToJsonElement(markerFile.readText()).jsonObject
            } catch (_: Exception) {
                throw PhaseError(phase, "ownership marker is not valid JSON")
            }
        if (marker.string("runId") != runId) {
            throw PhaseError(phase, "ownership marker belongs to a different run")
        }
    }

    private fun writeOwnership(
        runId: String,
        podcastCount: Int,
        groupCount: Int,
        episodeCount: Int,
    ) {
        val file = File(app.filesDir, "cross-device/ownership.json")
        file.parentFile?.mkdirs()
        file.writeText(
            canonicalJson(
                buildJsonObject {
                    put("runId", runId)
                    put("seededAtMs", System.currentTimeMillis())
                    put("podcastCount", podcastCount)
                    put("groupCount", groupCount)
                    put("episodeCount", episodeCount)
                },
            ),
        )
    }

    // --- seed -----------------------------------------------------------------------------------

    /**
     * The deterministic 50-podcast fixture, written through the production DAOs before any link.
     * The capture triggers stay silent while `sync_state.enabled = 0`, so the seed is bootstrap
     * state, exactly as 09's E12 describes. The app must be fresh: an existing library or link is
     * refused rather than cleared.
     */
    private suspend fun seed(
        graph: ch.lkmc.neutrodyne.di.AndroidAppGraph,
        config: CrossDeviceConfig,
    ): JsonObject {
        val db = graph.databaseOpener.requireDatabase()
        val fixture = crossDeviceFixture(config.runId, config.feedBaseUrl)

        val existingPodcasts = db.podcastDao().allForExport()
        val existingGroups = db.groupDao().groupsInOrder()
        val existingEpisodes = db.episodeDao().all()
        val existingQueue = db.queueDao().entries()
        val existingOutbox = db.syncOutboxDao().count()
        val existingClocks = db.syncClockDao().count()
        val existingHeld = db.syncHeldDao().count()
        val existingParked = db.syncParkedDao().count()
        val syncState = db.syncStateDao().get()
        if (existingPodcasts.isNotEmpty() || existingGroups.isNotEmpty() || existingEpisodes.isNotEmpty() ||
            existingQueue.isNotEmpty() || existingOutbox > 0 || existingClocks > 0 || existingHeld > 0 ||
            existingParked > 0
        ) {
            throw PhaseError("seed", "existing non-fixture library or sync state")
        }
        if (syncState != null &&
            (
                syncState.enabled || syncState.linkedAt != null || syncState.serverUrl != null ||
                    syncState.deviceId != null
            )
        ) {
            throw PhaseError("seed", "existing non-fixture link state")
        }

        db.withWriteTransaction {
            val podcastIds =
                fixture.podcasts.map { podcast ->
                    db.podcastDao().insertPodcast(
                        PodcastEntity(
                            syncId = podcast.syncId,
                            sourceType = SourceType.RSS,
                            feedUrl = podcast.feedUrl,
                            feedKey = podcast.feedKey,
                            title = podcast.title,
                            author = podcast.author,
                            link = podcast.link,
                            artworkKey = podcast.artworkKey,
                            status = PodcastStatus.ACTIVE,
                            initialFetch = false,
                            subscribedAt = T_BASE,
                            guidCoverageSince = T_BASE,
                            nextRefreshAt = T_BASE + REFRESH_FAR_FUTURE_MS,
                        ),
                    )
                }
            val episodeIds =
                fixture.episodes.map { episode ->
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(
                                EpisodeEntity(
                                    podcastId = podcastIds[episode.podcastIndex],
                                    identityKey = episode.identityKey,
                                    guid = episode.guid,
                                    title = episode.title,
                                    pubDate = episode.pubDate,
                                    sortDate = episode.pubDate,
                                    feedOrder = episode.feedOrder,
                                    firstSeenAt = T_BASE,
                                    lastSeenAt = T_BASE,
                                    enclosureUrl = episode.enclosureUrl,
                                    enclosureType = episode.enclosureType,
                                    enclosureLength = episode.enclosureLength,
                                    durationMs = episode.durationMs,
                                    link = episode.link,
                                    contentHash = episode.identityKey.hashCode().toLong(),
                                ),
                            ),
                        ).single()
                }

            // Representative, disjoint user state: played / in-progress / favourite / Up next.
            fixture.episodes.forEachIndexed { index, episode ->
                val episodeId = episodeIds[index]
                when (index % 6) {
                    0 -> {
                        db.episodeStateDao().upsert(
                            EpisodeStateEntity(
                                episodeId = episodeId,
                                playedAt = T_BASE + index,
                                playCount = 1,
                                lastPlayedAt = T_BASE + index,
                                updatedAt = T_BASE + index,
                            ),
                        )
                    }

                    1 -> {
                        db.episodeStateDao().upsert(
                            EpisodeStateEntity(
                                episodeId = episodeId,
                                startedAt = T_BASE + index,
                                updatedAt = T_BASE + index,
                            ),
                        )
                        db.positionDao().upsert(
                            EpisodePositionEntity(
                                episodeId = episodeId,
                                positionMs = 30_000L + index,
                                durationMs = episode.durationMs,
                                positionSource = PositionSource.STREAM,
                                updatedAt = T_BASE + index,
                            ),
                        )
                    }

                    2 -> {
                        db.episodeStateDao().upsert(
                            EpisodeStateEntity(
                                episodeId = episodeId,
                                isFavorite = true,
                                updatedAt = T_BASE + index,
                            ),
                        )
                    }
                }
            }

            val queueOrderKeys = OrderKey.rewrite(QUEUE_COUNT)
            var queueIndex = 0
            fixture.episodes.forEachIndexed { index, _ ->
                if (index % 6 == 3) {
                    db.queueDao().insert(
                        QueueEntryEntity(
                            episodeId = episodeIds[index],
                            orderKey = queueOrderKeys[queueIndex++],
                            addedAt = T_BASE,
                        ),
                    )
                }
            }

            val groupOrderKeys = OrderKey.rewrite(fixture.groups.size)
            fixture.groups.forEachIndexed { groupIndex, group ->
                val groupId =
                    db.groupDao().insert(
                        PodcastGroupEntity(
                            uuid = group.uuid,
                            name = group.name,
                            nameKey = group.nameKey,
                            orderKey = groupOrderKeys[groupIndex],
                            createdAt = T_BASE,
                            updatedAt = T_BASE,
                        ),
                    )
                val memberKeys = OrderKey.rewrite(group.memberPodcastIndices.size)
                group.memberPodcastIndices.forEachIndexed { memberIndex, podcastIndex ->
                    db.groupDao().insertMember(
                        PodcastGroupMemberEntity(
                            groupId = groupId,
                            podcastId = podcastIds[podcastIndex],
                            orderKey = memberKeys[memberIndex],
                            addedAt = T_BASE,
                        ),
                    )
                }
            }
        }

        writeOwnership(config.runId, fixture.podcasts.size, fixture.groups.size, fixture.episodes.size)
        return buildJsonObject {
            put("seeded", true)
            put("podcasts", fixture.podcasts.size)
            put("episodes", fixture.episodes.size)
            put("groups", fixture.groups.size)
            put("members", fixture.groups.sumOf { it.memberPodcastIndices.size })
            put("upNext", QUEUE_COUNT)
            put("counts", counts(db))
        }
    }

    // --- link -----------------------------------------------------------------------------------

    /**
     * The real first-link flow: `startLink(PASSWORD)` -> loopback `confirmInsecure` ->
     * `submitPassword` -> `chooseFirstLink(MERGE)`. The actual `LinkState` machine drives the
     * answers; no token or profile row is inserted directly, and the password `CharArray` is
     * cleared after use.
     */
    private suspend fun link(
        graph: ch.lkmc.neutrodyne.di.AndroidAppGraph,
        config: CrossDeviceConfig,
    ): JsonObject {
        val db = graph.databaseOpener.requireDatabase()
        requireOwnership(config.runId, "link")
        val controller = graph.syncController
        val state = db.syncStateDao().get()
        if (state != null &&
            (state.enabled || state.linkedAt != null || state.serverUrl != null || state.deviceId != null)
        ) {
            throw PhaseError("link", "device is already configured or linked")
        }
        if (controller.link?.state?.value != LinkState.Address) {
            throw PhaseError("link", "a link flow is already running")
        }

        val password = config.password.toCharArray()
        var passwordSubmitted = false
        var mergeChosen = false
        try {
            controller.startLink(config.serverUrl, LinkMethod.PASSWORD)
            val deadline = System.nanoTime() + LINK_TIMEOUT_MS * 1_000_000
            while (true) {
                val state =
                    controller.link?.state?.value
                        ?: throw PhaseError("link", "link flow is unavailable")
                when (state) {
                    is LinkState.InsecureConnection -> {
                        controller.confirmInsecure()
                    }

                    is LinkState.NeedsLocalNetworkPermission -> {
                        controller.grantLocalNetwork(true)
                    }

                    is LinkState.AwaitingInput -> {
                        if (passwordSubmitted) {
                            throw PhaseError("link", "link returned to AwaitingInput after submitPassword")
                        }
                        passwordSubmitted = true
                        try {
                            controller.submitPassword(config.username, password)
                        } finally {
                            password.fill('\u0000')
                        }
                    }

                    is LinkState.ChooseFirstLink, is LinkState.ConfirmUpload -> {
                        if (mergeChosen) {
                            throw PhaseError("link", "link returned to the first-link choice twice")
                        }
                        mergeChosen = true
                        controller.chooseFirstLink(FirstLinkChoice.MERGE)
                    }

                    is LinkState.Linked -> {
                        break
                    }

                    is LinkState.Failed -> {
                        throw PhaseError("link", "link failed: ${state.error}")
                    }

                    else -> {}
                }
                if (System.nanoTime() >= deadline) {
                    throw PhaseError("link", "timed out waiting for the link flow (last=${state.safeName()})")
                }
                delay(POLL_MS)
            }
        } finally {
            password.fill('\u0000')
        }

        val status = controller.status.value
        if (status !is SyncStatus.Linked) {
            throw PhaseError("link", "link did not reach the linked status")
        }
        return buildJsonObject {
            put("linked", true)
            put("counts", counts(db))
        }
    }

    // --- edit -----------------------------------------------------------------------------------

    /**
     * The bounded offline edit sequence. Every operation is keyed by fixture index and writes
     * through the normal DAO writers, so the existing capture triggers own the HLC and outbox;
     * no outbox or accepted-clock row is injected.
     */
    private suspend fun edit(
        graph: ch.lkmc.neutrodyne.di.AndroidAppGraph,
        config: CrossDeviceConfig,
    ): JsonObject {
        val db = graph.databaseOpener.requireDatabase()
        requireOwnership(config.runId, "edit")
        val fixture = crossDeviceFixture(config.runId, config.feedBaseUrl)
        val edits = config.edits.ifEmpty { defaultEdits() }
        var applied = 0
        for (edit in edits) {
            db.withWriteTransaction { applyEdit(db, edit, fixture) }
            applied++
        }
        val outbox = db.syncOutboxDao().count()
        if (outbox == 0) {
            throw PhaseError("edit", "no outbox rows were captured")
        }
        return buildJsonObject {
            put("editsApplied", applied)
            put("outboxRows", outbox)
            put("counts", counts(db))
        }
    }

    private suspend fun applyEdit(
        db: NeutrodyneDatabase,
        edit: CrossDeviceEdit,
        fixture: CrossDeviceFixture,
    ) {
        val now = System.currentTimeMillis()
        when (edit.op) {
            "played" -> {
                val episodeId = requireEpisode(db, fixture, edit).id
                db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = episodeId, updatedAt = now))
                db.episodeStateDao().markPlayed(
                    listOf(episodeId),
                    edit.value ?: throw PhaseError("edit", "played requires value"),
                )
            }

            "unplayed" -> {
                val episodeId = requireEpisode(db, fixture, edit).id
                db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = episodeId, updatedAt = now))
                db.episodeStateDao().markUnplayed(listOf(episodeId), now)
            }

            "position" -> {
                val episode = requireEpisode(db, fixture, edit)
                db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = episode.id, updatedAt = now))
                db.episodeStateDao().markStarted(episode.id, now)
                db.positionDao().upsert(
                    EpisodePositionEntity(
                        episodeId = episode.id,
                        positionMs = edit.value ?: throw PhaseError("edit", "position requires value"),
                        durationMs = episode.durationMs,
                        positionSource = PositionSource.STREAM,
                        updatedAt = now,
                    ),
                )
            }

            "fav" -> {
                val episodeId = requireEpisode(db, fixture, edit).id
                db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = episodeId, updatedAt = now))
                db.episodeStateDao().setFavorite(
                    episodeId,
                    edit.fav ?: throw PhaseError("edit", "fav requires fav"),
                    now,
                )
            }

            "membership_add" -> {
                val (groupId, podcastId) = requireMembership(db, fixture, edit)
                val orderKey = ProtocolOrderKeys.after(db.groupDao().lastMemberOrderKey(groupId))
                db.groupDao().insertMember(
                    PodcastGroupMemberEntity(
                        groupId = groupId,
                        podcastId = podcastId,
                        orderKey = orderKey,
                        addedAt = now,
                    ),
                )
            }

            "membership_remove" -> {
                val (groupId, podcastId) = requireMembership(db, fixture, edit)
                db.groupDao().deleteMembers(groupId, listOf(podcastId))
            }

            "group_order" -> {
                val group =
                    fixture.groups.getOrNull(
                        edit.group ?: throw PhaseError("edit", "group_order requires group"),
                    ) ?: throw PhaseError("edit", "unknown group index")
                val current = db.groupDao().groupsInOrder()
                val movingId =
                    current.firstOrNull { it.uuid == group.uuid }?.id
                        ?: throw PhaseError("edit", "fixture group is missing")
                val requested = current.map { it.id }.toMutableList()
                requested.remove(movingId)
                val before = (edit.before ?: requested.size).coerceIn(0, requested.size)
                requested.add(before, movingId)
                val writes =
                    planOrderKeyWrites(
                        current.map { OrderedRow(it.id, it.orderKey) },
                        requested,
                        ProtocolOrderKeys,
                    ) ?: throw PhaseError("edit", "group_order is not a permutation")
                for ((groupId, orderKey) in writes) {
                    db.groupDao().setGroupOrderKey(groupId, orderKey, now)
                }
            }

            "queue_add" -> {
                val episodeId = requireEpisode(db, fixture, edit).id
                val entries = db.queueDao().entries()
                val orderKey =
                    if (edit.before == null) {
                        ProtocolOrderKeys.after(db.queueDao().lastOrderKey())
                    } else {
                        val anchor =
                            entries.getOrNull(edit.before)
                                ?: throw PhaseError("edit", "queue_add before index is out of range")
                        OrderKey.before(anchor.orderKey)
                    }
                db.queueDao().insert(
                    QueueEntryEntity(episodeId = episodeId, orderKey = orderKey, addedAt = now),
                )
            }

            "queue_remove" -> {
                val episodeId = requireEpisode(db, fixture, edit).id
                db.queueDao().removeEpisodes(listOf(episodeId))
            }

            "unsubscribe" -> {
                val podcast =
                    fixture.podcasts.getOrNull(
                        edit.podcast ?: throw PhaseError("edit", "unsubscribe requires podcast"),
                    ) ?: throw PhaseError("edit", "unknown podcast index")
                val row =
                    db.podcastDao().bySyncId(podcast.syncId)
                        ?: throw PhaseError("edit", "fixture podcast is missing")
                db.podcastDao().deleteCascade(row.id, now)
            }

            else -> {
                throw PhaseError("edit", "unknown operation")
            }
        }
    }

    private suspend fun requireEpisode(
        db: NeutrodyneDatabase,
        fixture: CrossDeviceFixture,
        edit: CrossDeviceEdit,
    ): EpisodeEntity {
        val podcastIndex = edit.podcast ?: throw PhaseError("edit", "${edit.op} requires podcast")
        val episodeIndex = edit.episode ?: throw PhaseError("edit", "${edit.op} requires episode")
        val fixtureEpisode =
            fixture.episodes.getOrNull(episodeIndex)
                ?: throw PhaseError("edit", "unknown episode index")
        if (fixtureEpisode.podcastIndex != podcastIndex) {
            throw PhaseError("edit", "episode does not belong to podcast")
        }
        return db.episodeDao().all().firstOrNull { it.identityKey == fixtureEpisode.identityKey }
            ?: throw PhaseError("edit", "fixture episode is missing")
    }

    private suspend fun requireMembership(
        db: NeutrodyneDatabase,
        fixture: CrossDeviceFixture,
        edit: CrossDeviceEdit,
    ): Pair<Long, Long> {
        val groupIndex = edit.group ?: throw PhaseError("edit", "${edit.op} requires group")
        val podcastIndex = edit.podcast ?: throw PhaseError("edit", "${edit.op} requires podcast")
        val group =
            fixture.groups.getOrNull(groupIndex)
                ?: throw PhaseError("edit", "unknown group index")
        val podcast =
            fixture.podcasts.getOrNull(podcastIndex)
                ?: throw PhaseError("edit", "unknown podcast index")
        val groupRow =
            db.groupDao().groupByUuid(group.uuid)
                ?: throw PhaseError("edit", "fixture group is missing")
        val podcastRow =
            db.podcastDao().bySyncId(podcast.syncId)
                ?: throw PhaseError("edit", "fixture podcast is missing")
        return groupRow.id to podcastRow.id
    }

    private fun defaultEdits(): List<CrossDeviceEdit> =
        listOf(
            CrossDeviceEdit("played", podcast = 1, episode = 1, value = T_BASE + 1_000),
            CrossDeviceEdit("unplayed", podcast = 0, episode = 0),
            CrossDeviceEdit("position", podcast = 2, episode = 2, value = 60_000L),
            CrossDeviceEdit("fav", podcast = 3, episode = 3, fav = true),
            CrossDeviceEdit("membership_add", group = 0, podcast = 25),
            CrossDeviceEdit("membership_remove", group = 0, podcast = 0),
            CrossDeviceEdit("group_order", group = 1, before = 0),
            CrossDeviceEdit("queue_add", podcast = 4, episode = 4, before = 0),
            CrossDeviceEdit("queue_remove", podcast = 3, episode = 3),
            CrossDeviceEdit("unsubscribe", podcast = 49),
        )

    // --- sync -----------------------------------------------------------------------------------

    /**
     * A real `SyncController.syncNow()` round. The baseline is armed before the call and the
     * completion barrier is a new `SYNC_NOW` round summary in the local diagnostics, so an empty
     * outbox cannot pass without an RPC: a round that never ran leaves no summary. A failed,
     * revoked or clock-skewed round fails the phase.
     */
    private suspend fun sync(
        graph: ch.lkmc.neutrodyne.di.AndroidAppGraph,
        config: CrossDeviceConfig,
    ): JsonObject {
        val db = graph.databaseOpener.requireDatabase()
        requireOwnership(config.runId, "sync")
        val controller = graph.syncController
        val state = db.syncStateDao().get()
        if (state == null || !state.enabled || state.linkedAt == null) {
            throw PhaseError("sync", "sync state is not linked")
        }
        // The controller publishes its status from an async refresh on creation; give a linked
        // install the bounded time it needs before declaring the device not linked.
        val statusDeadline = System.nanoTime() + STATUS_TIMEOUT_MS * 1_000_000
        while (controller.status.value !is SyncStatus.Linked) {
            if (System.nanoTime() >= statusDeadline) {
                throw PhaseError("sync", "device is not linked")
            }
            delay(POLL_MS)
        }

        val startMs = System.currentTimeMillis()
        controller.syncNow()
        val round = awaitSyncNowRound(controller, startMs)
        if (round.failure != null) {
            throw PhaseError(
                "sync",
                "round failed: ${round.failure} (${round.protocolCode ?: "no code"})",
            )
        }
        val report =
            round.report
                ?: throw PhaseError("sync", "round finished without a report")
        val lastError = db.syncStateDao().get()?.lastError
        if (lastError == SYNC_ERROR_REVOKED || lastError == SyncErrorCode.CLOCK_SKEW.wire) {
            throw PhaseError("sync", "link is revoked or clock-skewed")
        }
        return buildJsonObject {
            put(
                "round",
                buildJsonObject {
                    put("reason", round.reason.name)
                    put("pushed", report.pushed)
                    put("applied", report.applied)
                    put("stale", report.stale)
                    put("rejected", report.rejected)
                    put("pulled", report.pulled)
                    put("held", report.held)
                    put("durationMs", report.durationMs)
                },
            )
            put("counts", counts(db))
        }
    }

    private suspend fun awaitSyncNowRound(
        controller: SyncController,
        startMs: Long,
    ): SyncRoundSummary {
        val deadline = System.nanoTime() + SYNC_TIMEOUT_MS * 1_000_000
        var observed = 0
        while (true) {
            val snapshot = controller.diagnostics().getOrNull()
            if (snapshot != null) {
                observed = snapshot.rounds.size
                val candidate =
                    snapshot.rounds.lastOrNull {
                        it.reason == SyncRoundReason.SYNC_NOW &&
                            it.completedAtMs >= startMs - CLOCK_TOLERANCE_MS
                    }
                if (candidate != null) return candidate
            }
            if (System.nanoTime() >= deadline) {
                throw PhaseError(
                    "sync",
                    "no SYNC_NOW round completed within ${SYNC_TIMEOUT_MS}ms (rounds=$observed)",
                )
            }
            delay(POLL_MS)
        }
    }

    // --- dump -----------------------------------------------------------------------------------

    /**
     * The canonical accepted wire state plus the materialized fixture library and the held batch
     * summary. The wire snapshot comes from `sync_clock` through `:core:testing`'s
     * `canonicalSyncSnapshot`; `auth` fields are stripped and no credential, token, auth
     * reference or filesystem path is written.
     */
    private suspend fun dump(
        graph: ch.lkmc.neutrodyne.di.AndroidAppGraph,
        config: CrossDeviceConfig,
    ): JsonObject {
        val db = graph.databaseOpener.requireDatabase()
        requireOwnership(config.runId, "dump")
        val fixture = crossDeviceFixture(config.runId, config.feedBaseUrl)

        val records = mutableMapOf<SyncAddress, RecordState>()
        var skippedClockRows = 0
        for (row in db.syncClockDao().all()) {
            try {
                val coll = SyncCollection.fromWire(row.coll)
                val id = RecordId.fromRid(coll, row.rid)
                val raw = SyncJson.decodeFromString<RawSyncRecord>(row.clocks)
                records[SyncAddress(coll, id)] = sanitize(raw.state)
            } catch (_: Exception) {
                skippedClockRows++
            }
        }
        val snapshot =
            try {
                canonicalSyncSnapshot(records)
            } catch (_: Exception) {
                throw PhaseError("dump", "canonical snapshot failed")
            }

        val podcastRows = db.podcastDao().allForExport().associateBy { it.syncId }
        val podcastIndexById =
            fixture.podcasts
                .mapIndexedNotNull { index, podcast ->
                    podcastRows[podcast.syncId]?.id?.let { it to index }
                }.toMap()
        val episodeRows = db.episodeDao().all()
        val episodesByKey = episodeRows.associateBy { it.identityKey }
        val groupRows = db.groupDao().groupsInOrder()
        val queueEntries = db.queueDao().entries()
        val queuedEpisodeIds = queueEntries.map { it.episodeId }.toSet()

        val library =
            buildJsonObject {
                put(
                    "podcasts",
                    buildJsonArray {
                        fixture.podcasts.forEach { podcast ->
                            add(
                                buildJsonObject {
                                    put("index", podcast.index)
                                    put("syncId", podcast.syncId)
                                    put("title", podcast.title)
                                    put("subscribed", podcastRows.containsKey(podcast.syncId))
                                },
                            )
                        }
                    },
                )
                put(
                    "episodes",
                    buildJsonArray {
                        fixture.episodes.forEach { episode ->
                            val row = episodesByKey[episode.identityKey]
                            val state = row?.let { db.episodeStateDao().byEpisode(it.id) }
                            val position = row?.let { db.positionDao().byEpisode(it.id) }
                            add(
                                buildJsonObject {
                                    put("podcastIndex", episode.podcastIndex)
                                    put("identityKey", episode.identityKey)
                                    put("title", episode.title)
                                    put("present", row != null)
                                    put("playedAt", JsonPrimitive(state?.playedAt))
                                    put("positionMs", position?.positionMs ?: 0)
                                    put("favorite", state?.isFavorite ?: false)
                                    put("inUpNext", row != null && row.id in queuedEpisodeIds)
                                },
                            )
                        }
                    },
                )
                put(
                    "groups",
                    buildJsonArray {
                        groupRows.forEach { group ->
                            add(
                                buildJsonObject {
                                    put("uuid", group.uuid)
                                    put("name", group.name)
                                    put("orderKey", group.orderKey)
                                    put(
                                        "members",
                                        buildJsonArray {
                                            db.groupDao().membersOf(group.id).forEach { member ->
                                                add(podcastIndexById[member.podcastId] ?: -1)
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
                put(
                    "upNext",
                    buildJsonArray {
                        queueEntries.forEach { entry ->
                            val episodeRow = episodeRows.firstOrNull { it.id == entry.episodeId }
                            val fixtureEpisode =
                                episodeRow?.let { row ->
                                    fixture.episodes.firstOrNull {
                                        it.identityKey ==
                                            row.identityKey
                                    }
                                }
                            add(
                                buildJsonObject {
                                    put("podcastIndex", fixtureEpisode?.podcastIndex ?: -1)
                                    put("identityKey", episodeRow?.identityKey.orEmpty())
                                    put("title", fixtureEpisode?.title ?: episodeRow?.title.orEmpty())
                                    put("orderKey", entry.orderKey)
                                },
                            )
                        }
                    },
                )
            }

        val held =
            buildJsonArray {
                db.syncHeldDao().all().forEach { row ->
                    val summary =
                        runCatching { json.parseToJsonElement(row.summary).jsonObject }.getOrNull()
                    add(
                        buildJsonObject {
                            put("id", row.id)
                            put("heldAt", row.heldAt)
                            put("podcasts", summary.titles("podcasts"))
                            put("groups", summary.titles("groups"))
                        },
                    )
                }
            }

        return buildJsonObject {
            put("wire", json.parseToJsonElement(snapshot.canonicalRaw()))
            put("library", library)
            put("held", held)
            put("skippedClockRows", skippedClockRows)
            put("counts", counts(db))
        }
    }

    // --- shared reading -------------------------------------------------------------------------

    private suspend fun counts(db: NeutrodyneDatabase): JsonObject =
        buildJsonObject {
            put("podcasts", db.podcastDao().allForExport().size)
            put("episodes", db.episodeDao().all().size)
            put("groups", db.groupDao().groupsInOrder().size)
            put("members", db.groupDao().membershipPairs().size)
            put("upNext", db.queueDao().entries().size)
            put("outbox", db.syncOutboxDao().count())
            put("clock", db.syncClockDao().count())
            put("held", db.syncHeldDao().count())
            put("parked", db.syncParkedDao().count())
        }

    private fun safeCounts(): JsonObject =
        try {
            val graph = app.graph
            if (graph.databaseOpener.openState.value !is DatabaseOpenState.Opened) {
                buildJsonObject { }
            } else {
                runBlocking { counts(graph.databaseOpener.requireDatabase()) }
            }
        } catch (_: Throwable) {
            buildJsonObject { }
        }

    private fun writeResult(result: JsonObject) {
        resultFile.parentFile?.mkdirs()
        resultFile.writeText(canonicalJson(result))
    }

    private fun result(
        runId: String?,
        phase: String,
        ok: Boolean,
        error: String?,
        payload: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("runId", JsonPrimitive(runId))
            put("phase", phase)
            put("ok", ok)
            put("error", JsonPrimitive(error))
            payload.forEach { (key, value) -> put(key, value) }
        }

    private fun JsonObject?.titles(key: String): JsonArray =
        buildJsonArray {
            (this@titles?.get(key) as? JsonArray)?.forEach { add(it) }
        }

    private fun JsonObject.string(field: String): String? {
        val primitive = this[field] as? JsonPrimitive ?: return null
        return if (primitive === JsonNull) null else primitive.content
    }

    private fun JsonObject.int(field: String): Int? = (this[field] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(field: String): Long? = (this[field] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(field: String): Boolean? = (this[field] as? JsonPrimitive)?.booleanOrNull
}

private class PhaseError(
    field: String,
    reason: String,
) : Exception("$field: $reason") {
    val safeMessage: String = "$field: $reason"
}

private data class CrossDeviceConfig(
    val runId: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val feedBaseUrl: String,
    val edits: List<CrossDeviceEdit>,
)

private data class CrossDeviceEdit(
    val op: String,
    val podcast: Int? = null,
    val episode: Int? = null,
    val group: Int? = null,
    val value: Long? = null,
    val fav: Boolean? = null,
    val before: Int? = null,
)

private data class FixturePodcast(
    val index: Int,
    val syncId: String,
    val feedUrl: String,
    val feedKey: String,
    val title: String,
    val author: String,
    val link: String,
    val artworkKey: String,
)

private data class FixtureEpisode(
    val podcastIndex: Int,
    val identityKey: String,
    val guid: String,
    val title: String,
    val pubDate: Long,
    val feedOrder: Int,
    val enclosureUrl: String,
    val enclosureType: String,
    val enclosureLength: Long,
    val durationMs: Long,
    val link: String,
)

private data class FixtureGroup(
    val index: Int,
    val uuid: String,
    val name: String,
    val nameKey: String,
    val memberPodcastIndices: List<Int>,
)

private data class CrossDeviceFixture(
    val podcasts: List<FixturePodcast>,
    val episodes: List<FixtureEpisode>,
    val groups: List<FixtureGroup>,
)

/**
 * The deterministic fixture every side agrees on. `OrderKey.rewrite` supplies deterministic order
 * keys (the jittered `after`/`between` are not reproducible); every UUID and episode GUID is
 * derived from `runId` plus the record key.
 */
private fun crossDeviceFixture(
    runId: String,
    feedBaseUrl: String,
): CrossDeviceFixture {
    val base = feedBaseUrl.trimEnd('/')
    val podcasts =
        (0 until PODCAST_COUNT).map { index ->
            val feedUrl = "$base/feed-$index.xml"
            FixturePodcast(
                index = index,
                syncId = fixtureUuid(runId, "podcast:$index"),
                feedUrl = feedUrl,
                feedKey = checkNotNull(UrlNormalizer.forIdentity(feedUrl)),
                title = "Cross Device Feed $index",
                author = "Cross Device Author $index",
                link = "$base/feed-$index",
                artworkKey = "cross-device-artwork-$index",
            )
        }
    val episodes =
        podcasts.map { podcast ->
            val index = podcast.index
            val enclosureUrl = "$base/episode-$index.mp3"
            FixtureEpisode(
                podcastIndex = index,
                identityKey =
                    EpisodeKeys.keyFor(
                        KeyInput(
                            guid = fixtureToken(runId, "episode:$index"),
                            enclosureUrl = enclosureUrl,
                            title = "Cross Device Episode $index",
                            pubDate = T_BASE - index * 1000,
                            link = "$base/episode-$index",
                        ),
                        EpisodeKeys.VERSION,
                    ),
                guid = fixtureToken(runId, "episode:$index"),
                title = "Cross Device Episode $index",
                pubDate = T_BASE - index * 1000,
                feedOrder = 0,
                enclosureUrl = enclosureUrl,
                enclosureType = "audio/mpeg",
                enclosureLength = 1_000_000L + index,
                durationMs = 1_800_000L + index * 1000,
                link = "$base/episode-$index",
            )
        }
    val groups =
        listOf(
            FixtureGroup(
                index = 0,
                uuid = fixtureUuid(runId, "group:0"),
                name = "Cross Device Group A",
                nameKey = fixtureGroupNameKey("Cross Device Group A"),
                memberPodcastIndices = (0 until 25).toList(),
            ),
            FixtureGroup(
                index = 1,
                uuid = fixtureUuid(runId, "group:1"),
                name = "Cross Device Group B",
                nameKey = fixtureGroupNameKey("Cross Device Group B"),
                memberPodcastIndices = (25 until PODCAST_COUNT).toList(),
            ),
        )
    return CrossDeviceFixture(podcasts, episodes, groups)
}

private fun fixtureGroupNameKey(name: String): String =
    (GroupNames.validate(name) as? GroupNames.Validation.Valid)?.nameKey
        ?: throw IllegalStateException("fixture group name is invalid")

private fun fixtureUuid(
    runId: String,
    key: String,
): String {
    val bytes =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$runId:$key".toByteArray(Charsets.UTF_8))
            .copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = bytes.joinToString("") { "%02x".format(it) }
    return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" +
        hex.substring(16, 20) + "-" + hex.substring(20)
}

private fun fixtureToken(
    runId: String,
    key: String,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("$runId:$key".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(32)

private fun sanitize(state: RecordState): RecordState =
    if (state.fields.containsKey("auth")) state.copy(fields = state.fields - "auth") else state

private fun canonicalJson(element: JsonElement): String =
    when (element) {
        is JsonObject -> {
            element.keys.sorted().joinToString(",", "{", "}") { key ->
                "${JsonPrimitive(key)}:${canonicalJson(element.getValue(key))}"
            }
        }

        is JsonArray -> {
            element.joinToString(",", "[", "]") { canonicalJson(it) }
        }

        else -> {
            element.toString()
        }
    }

private fun LinkState.safeName(): String = this::class.simpleName ?: "LinkState"

private const val TAG = "CrossDeviceSync"
private const val PODCAST_COUNT = 50
private const val QUEUE_COUNT = 8
private const val T_BASE = 1_730_000_000_000L
private const val REFRESH_FAR_FUTURE_MS = 365L * 24 * 60 * 60 * 1000
private const val POLL_MS = 50L
private const val LINK_TIMEOUT_MS = 60_000L
private const val SYNC_TIMEOUT_MS = 60_000L
private const val STATUS_TIMEOUT_MS = 5_000L
private const val CLOCK_TOLERANCE_MS = 5_000L
private const val SYNC_ERROR_REVOKED = "Revoked"
