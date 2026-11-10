// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PlaySessionEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.sync.api.RemoteSessionOffer
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 10 Now playing and handoff: applies a newer remote `session` while this device is not
 * playing — resolves the episode, writes `play_session` under `applying = 1` (so the adoption
 * itself never echoes back as a local capture) and arms the "Continue on this device" offer.
 * While this device plays, the session stays raw in `sync_clock`; a pause, stop, item
 * transition or restart re-evaluates it without a redelivery.
 *
 * Offer state is in-memory only: `dismiss` hides the card for this process run (a strictly
 * newer remote write may offer again) and `invalidate` drops everything on unlink/relink or
 * revocation. The arm [epoch] makes an asynchronous publish safe: the device-name lookup runs
 * off every lock and transaction, and the parked publish re-proves eligibility and the epoch
 * it armed under before it lands — a local start, an unlink, a dismissal or a newer session
 * while it resolved can never surface a stale card.
 */
class SessionAdopter
    constructor(
        private val db: NeutrodyneDatabase,
        private val playback: PlaybackSyncPort,
        private val names: DeviceNames,
        private val clock: Clock,
        private val secrets: SecretStore,
        private val sharePasswords: () -> Boolean,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val clockStore = SyncClockStore(db)
        private val matcher = EpisodeMatcher(db.backupDao())

        private val _offer = MutableStateFlow<RemoteSessionOffer?>(null)

        /** The current "Continue on this device" offer, or `null` (08's card input). */
        val offer: StateFlow<RemoteSessionOffer?> = _offer.asStateFlow()

        /** Publishes run off the evaluate path — the device-name lookup may hit the network. */
        private val pendingOffer = MutableStateFlow<PendingOffer?>(null)

        /**
         * Serializes the offer's in-memory transitions. It is never acquired from inside a
         * Room transaction — the engine's session notification fires after the page's commit —
         * so holding it across the publish re-check's reads cannot invert with Room's writer.
         */
        private val mutex = Mutex()

        @Volatile
        private var started = false

        /** The newest clock of the dismissed session; same-or-older sessions never re-offer. */
        private var dismissedClock: Hlc? = null

        private var offeredClock: Hlc? = null

        /**
         * Bumped on every arm, dismissal and invalidation: the epoch a [PendingOffer] armed
         * under is its staleness token for the asynchronous publish.
         */
        private var epoch = 0

        /** Starts the boundary collectors and the first evaluate; initializers call this once. */
        fun start() {
            if (started) return
            started = true
            scope.launch { pendingOffer.collect { drainOffers() } }
            scope.launch {
                playback.active.collect { active ->
                    // A playing device shows no card; the next pause boundary re-evaluates.
                    if (active.isPlaying) _offer.value = null
                }
            }
            scope.launch { playback.events.collect { evaluate() } }
            // A restart re-evaluates the durable record once the player reports itself paused.
            scope.launch { evaluate() }
        }

        /** Publishes the newest armed offer; tests drive it directly for determinism. */
        internal suspend fun drainOffers() {
            val pending = pendingOffer.value ?: return
            pendingOffer.value = null
            publishOffer(pending)
        }

        /**
         * A playback boundary, a committed remote session or a restart re-evaluates the stored
         * session. The stored record alone is not the winner: pending local session writes
         * merge over it, so a device that played since the remote write wins its own session.
         */
        suspend fun evaluate() {
            if (playing()) return
            if (!db.isSyncLinked()) {
                _offer.value = null
                return
            }
            // An absent record never clears the offer: the remote write may still be inside
            // the page transaction this evaluate raced (session records update, never delete).
            val raw = clockStore.get(SESSION_WIRE, SESSION_RID) ?: return
            val merged = SyncRecordMerger.merge(raw.state, pendingSession(), RecordState(SyncCollection.SESSION))
            consider(merged.record, raw.by)
        }

        /** 08's dismiss: hides the card; the same-or-older session does not return this run. */
        fun dismiss() =
            mutate { armed ->
                dismissedClock = offeredClock ?: armed?.maxClock ?: dismissedClock
                offeredClock = null
            }

        /** Unlink, relink or revocation: every in-memory offer state resets. */
        fun invalidate() =
            mutate {
                dismissedClock = null
                offeredClock = null
            }

        /** The scheduler's link-state edge: a (re)link re-evaluates the seeded record. */
        suspend fun linkStateChanged() {
            if (db.isSyncLinked()) evaluate() else invalidate()
        }

        private suspend fun consider(
            record: RecordState,
            by: String?,
        ) {
            // The dismissal snapshot shares the publish's lock discipline: a dismiss racing
            // this consider can never pass its arm a stale clock.
            val dismissed = mutex.withLock { dismissedClock }
            val pending = eligible(record, by, dismissed)
            if (pending == null) {
                val newest = record.maxClock
                mutex.withLock {
                    val offered = offeredClock
                    // Only a record at least as new as the published one may clear the offer.
                    if (offered == null || (newest != null && newest >= offered)) {
                        _offer.value = null
                    }
                }
                return
            }
            val adopted = adopt(pending, dismissed) ?: return
            mutex.withLock {
                pendingOffer.value = adopted.copy(epoch = ++epoch)
            }
        }

        /** All of 10's offer conditions; `null` means nothing to offer. */
        private suspend fun eligible(
            record: RecordState,
            by: String?,
            dismissed: Hlc?,
        ): PendingOffer? {
            val state = db.syncStateDao().get() ?: return null
            if (!state.enabled || state.linkedAt == null) return null
            if (by == null || by == state.deviceId) return null
            val maxClock = record.maxClock ?: return null
            if (clock.now() - maxClock.millis > OFFER_MAX_AGE_MS) return null
            if (dismissed != null && maxClock <= dismissed) return null
            val episodeField = record.fields["episode"] ?: return null
            // A local write still winning the episode means this device played since (10).
            if (state.nodeId != null && episodeField.c?.nodeId?.value == state.nodeId) return null
            val ref = episodeField.v as? JsonObject ?: return null
            val id =
                runCatching { RecordId.fromWire(SyncCollection.EPISODE, ref) as? RecordId.Episode }
                    .getOrNull() ?: return null
            val podcast = db.podcastDao().bySyncId(id.podcast.value) ?: return null
            val matched =
                matcher.match(podcast.id, id.key.value, record.match)
                    ?: stubOrNull(podcast.id, id.key.value, record)
                    ?: return null
            return PendingOffer(matched, record, by, maxClock, state.linkIdentity())
        }

        /** The same stub semantics as an episode record: enclosure or YouTube hints only. */
        private suspend fun stubOrNull(
            podcastId: Long,
            key: String,
            record: RecordState,
        ): Long? {
            val hints = record.match
            if (hints?.enc == null && hints?.ytId == null) return null
            val inserted = db.episodeDao().insertStub(episodeEntity(podcastId, key, hints, clock.now()))
            if (inserted != -1L) return inserted
            return db.episodeDao().byIdentityKey(podcastId, key)?.id
        }

        /**
         * Re-proves the candidate under the same writer as the adoption. A local start between
         * the initial reads and this writer owns its session and live-valued outbox rows; an
         * adoption must never replace that literal underneath the local capture's newer clock.
         * No offer mutex is held while taking Room's writer.
         */
        private suspend fun adopt(
            pending: PendingOffer,
            dismissed: Hlc?,
        ): PendingOffer? {
            val now = clock.now()
            return db.withWriteTransaction {
                val state = db.syncStateDao().get() ?: return@withWriteTransaction null
                if (playing() || state.linkIdentity() != pending.link) return@withWriteTransaction null
                val raw = clockStore.get(SESSION_WIRE, SESSION_RID) ?: return@withWriteTransaction null
                val merged = SyncRecordMerger.merge(raw.state, pendingSession(), RecordState(SyncCollection.SESSION))
                val fresh = eligible(merged.record, raw.by, dismissed) ?: return@withWriteTransaction null
                if (fresh.record != pending.record || fresh.by != pending.by || fresh.episodeId != pending.episodeId) {
                    return@withWriteTransaction null
                }
                val columns = contextColumns(fresh.record.fields["context"]?.v as? JsonObject)
                if (playing()) return@withWriteTransaction null
                db.syncStateDao().withApplying {
                    db.playSessionDao().ensureNow(now)
                    val current = db.playSessionDao().get()
                    if (current == null || !current.matches(fresh.episodeId, columns)) {
                        db.playSessionDao().restoreSession(
                            currentEpisodeId = fresh.episodeId,
                            contextType = columns.type,
                            contextId = columns.id,
                            contextOrder = columns.order,
                            contextFilterFlags = columns.flags,
                            contextMediaFilter = columns.media,
                            contextMinSortDate = columns.minSortDate,
                            contextAnchorEpisodeId = columns.anchorEpisodeId,
                            contextAnchorSortDate = columns.anchorSortDate,
                            onlyWhenIdle = false,
                            now = now,
                        )
                    }
                }
                fresh
            }
        }

        /**
         * Resolves the card fields and the writer's name off every lock — the lookup may hit
         * the network — then lands only if the epoch it armed under is still current and the
         * stored session still proves the armed offer. A local play since, an unlink, a
         * dismissal or a newer session while it resolved each make the publish a no-op.
         */
        private suspend fun publishOffer(pending: PendingOffer) {
            val episode = db.episodeDao().byId(pending.episodeId) ?: return
            val name = names.name(pending.by) ?: pending.by
            mutex.withLock {
                if (pending.epoch != epoch) return
                if (playing()) return
                if (!db.isSyncLinked()) return
                val raw = clockStore.get(SESSION_WIRE, SESSION_RID) ?: return
                val merged =
                    SyncRecordMerger.merge(raw.state, pendingSession(), RecordState(SyncCollection.SESSION))
                val fresh = eligible(merged.record, raw.by, dismissedClock) ?: return
                if (fresh.link != pending.link || fresh.episodeId != pending.episodeId ||
                    fresh.maxClock != pending.maxClock
                ) {
                    return
                }
                offeredClock = pending.maxClock
                _offer.value =
                    RemoteSessionOffer(
                        episodeId = pending.episodeId,
                        title = episode.title,
                        podcastTitle = db.podcastDao().byId(episode.podcastId)?.title ?: "",
                        positionMs = db.positionDao().byEpisode(pending.episodeId)?.positionMs ?: 0L,
                        deviceName = name,
                    )
            }
        }

        /** The remote `context` object as `play_session` context columns, resolved to local ids. */
        private suspend fun contextColumns(context: JsonObject?): ContextColumns {
            val type = (context?.get("type") as? JsonPrimitive)?.contentOrNull
            return when (type) {
                "GROUP" -> {
                    val uuid = (context?.get("group") as? JsonPrimitive)?.contentOrNull
                    val groupId = uuid?.let { db.groupDao().groupByUuid(it)?.id }
                    contextBase(context, if (groupId == null) ContextType.EXTERNAL else ContextType.GROUP, groupId)
                }

                "PODCAST" -> {
                    val syncId = (context?.get("podcast") as? JsonPrimitive)?.contentOrNull
                    val podcastId = syncId?.let { db.podcastDao().bySyncId(it)?.id }
                    contextBase(
                        context,
                        if (podcastId == null) ContextType.EXTERNAL else ContextType.PODCAST,
                        podcastId,
                    )
                }

                "ALL", "UNGROUPED", "DOWNLOADS" -> {
                    contextBase(context, ContextType.valueOf(type), null)
                }

                else -> {
                    contextBase(context, ContextType.EXTERNAL, null)
                }
            }
        }

        private suspend fun contextBase(
            context: JsonObject?,
            type: ContextType,
            id: Long?,
        ): ContextColumns {
            val order =
                (context?.get("order") as? JsonPrimitive)?.contentOrNull?.let {
                    runCatching { FeedOrder.valueOf(it) }.getOrNull()
                } ?: FeedOrder.NEWEST_FIRST
            val filters = context?.get("filters") as? JsonObject
            val flags = ((filters?.get("flags") as? JsonPrimitive)?.longOrNull ?: 0L).toInt()
            val media =
                (filters?.get("media") as? JsonPrimitive)?.contentOrNull?.let {
                    runCatching { MediaFilter.valueOf(it) }.getOrNull()
                } ?: MediaFilter.ALL
            val anchorId =
                (context?.get("anchor") as? JsonObject)?.let { ref ->
                    (
                        runCatching {
                            RecordId.fromWire(SyncCollection.EPISODE, ref) as? RecordId.Episode
                        }.getOrNull()
                    )?.let { anchor ->
                        db.podcastDao().bySyncId(anchor.podcast.value)?.let { podcast ->
                            matcher.match(podcast.id, anchor.key.value, null)
                        }
                    }
                }
            return ContextColumns(
                type = type,
                id = id,
                order = order,
                flags = flags,
                media = media,
                minSortDate = (context?.get("minSortDate") as? JsonPrimitive)?.longOrNull,
                anchorEpisodeId = anchorId,
                anchorSortDate = (context?.get("anchorSortDate") as? JsonPrimitive)?.longOrNull,
            )
        }

        /**
         * Pending session outbox rows as merge state — the mirror of `SyncApplier.pendingState`
         * for the one record the adopter evaluates outside the apply transaction.
         */
        private suspend fun pendingSession(): RecordState? {
            val rows = db.syncOutboxDao().rowsFor(SESSION_WIRE, listOf(SESSION_RID))
            if (rows.isEmpty()) return null
            val fields = mutableMapOf<String, FieldState>()
            val reader = SourceReader(db, secrets, sharePasswords)
            for (row in rows) {
                if (row.field == "~rekey") continue
                val rowClock = outboxClock(row) ?: continue
                if (row.field == "*") {
                    val all = reader.allFields(SyncCollection.SESSION, SESSION_RID)
                    all.fields.forEach { (name, value) -> fields[name] = value.copy(c = rowClock) }
                    continue
                }
                val value =
                    row.value?.let {
                        runCatching { SyncJson.parseToJsonElement(it) }.getOrNull()
                    } ?: reader.sessionField(row.field) ?: continue
                fields[row.field] = FieldState(value, rowClock)
            }
            if (fields.isEmpty()) return null
            return RecordState(SyncCollection.SESSION, fields)
        }

        private fun playing(): Boolean = playback.active.value.isPlaying

        /**
         * Runs [block] under [mutex] with the armed pending it replaces; a non-suspending
         * caller takes the lock when free and otherwise queues the same mutation — the
         * ordering a parked publish checks is kept either way. Every mutation bumps [epoch]
         * and drops the armed and shown offer.
         */
        private inline fun mutate(crossinline block: (PendingOffer?) -> Unit) {
            val mutation = {
                epoch++
                val armed = pendingOffer.value
                pendingOffer.value = null
                _offer.value = null
                block(armed)
            }
            if (mutex.tryLock()) {
                try {
                    mutation()
                } finally {
                    mutex.unlock()
                }
            } else {
                scope.launch { mutex.withLock { mutation() } }
            }
        }

        private fun PlaySessionEntity.matches(
            episodeId: Long,
            c: ContextColumns,
        ): Boolean =
            currentEpisodeId == episodeId &&
                contextType == c.type &&
                contextId == c.id &&
                contextOrder == c.order &&
                contextFilterFlags == c.flags &&
                contextMediaFilter == c.media &&
                contextMinSortDate == c.minSortDate &&
                contextAnchorEpisodeId == c.anchorEpisodeId &&
                contextAnchorSortDate == c.anchorSortDate

        private data class PendingOffer(
            val episodeId: Long,
            val record: RecordState,
            val by: String,
            val maxClock: Hlc,
            val link: LinkIdentity,
            val epoch: Int = 0,
        )

        private fun SyncStateEntity.linkIdentity(): LinkIdentity =
            LinkIdentity(serverUrl, accountId, deviceId, nodeId, linkedAt)

        /** Activity timestamps and cursors do not change the linked identity. */
        private data class LinkIdentity(
            val serverUrl: String?,
            val accountId: String?,
            val deviceId: String?,
            val nodeId: String?,
            val linkedAt: Long?,
        )

        private data class ContextColumns(
            val type: ContextType?,
            val id: Long?,
            val order: FeedOrder = FeedOrder.NEWEST_FIRST,
            val flags: Int = 0,
            val media: MediaFilter = MediaFilter.ALL,
            val minSortDate: Long? = null,
            val anchorEpisodeId: Long? = null,
            val anchorSortDate: Long? = null,
        )

        private companion object {
            const val SESSION_WIRE = "session"
            const val SESSION_RID = "current"
            const val OFFER_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        }
    }
