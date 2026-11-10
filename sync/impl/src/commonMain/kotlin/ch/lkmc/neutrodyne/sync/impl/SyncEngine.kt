// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.LocalNetworkGate
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.sync.api.SyncReport
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The host of a server URL, for the token origin (`sync:<host>`). */
fun hostOf(url: String): String = url.removePrefix("https://").removePrefix("http://").substringBefore('/')

/**
 * The bare host of a normalised server URL, for the local-network gate's classification: userinfo
 * and an explicit `:port` are dropped, brackets around an IPv6 literal removed, and a multi-colon
 * remainder is treated as the literal itself. `null` for an empty or malformed authority, so the
 * link flow fails it explicitly instead of classifying garbage as a public address.
 */
internal fun bareHostOf(url: String): String? {
    val authority =
        url
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringAfterLast('@')
    val host =
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            val rest = authority.substring(end + 1)
            if (rest.isNotEmpty() &&
                !(rest.startsWith(':') && rest.drop(1).isNotEmpty() && rest.drop(1).all(Char::isDigit))
            ) {
                return null
            }
            authority.substring(1, end)
        } else if (authority.count { it == ':' } == 1) {
            val port = authority.substringAfterLast(':')
            if (port.isEmpty() || port.any { !it.isDigit() }) return null
            authority.substringBeforeLast(':')
        } else {
            authority
        }
    return host.ifEmpty { null }
}

/** The maximum number of push rounds in one `round()` (10 Sync round). */
private const val MAX_ROUNDS = 50

/**
 * 10 Sync round: push the outbox, apply the returned page, drain `hasMore`, then evaluate the
 * mass-change guard. The engine owns no scheduling; `SyncScheduler` (Android) and
 * `DesktopSyncLane` call [round].
 */
class SyncEngine(
    private val db: NeutrodyneDatabase,
    private val client: SyncClient,
    private val applier: SyncApplier,
    private val playback: PlaybackSyncPort,
    private val clock: Clock,
    private val secrets: SecretStore,
    private val sharePasswords: () -> Boolean,
    private val address: ch.lkmc.neutrodyne.core.domain.SyncServerAddress,
    private val tokens: SyncTokenStore,
    private val activity: SyncActivityState,
    private val gate: LocalNetworkGate,
    private val effects: SyncApplyEffects,
    private val capture: SettingsCapture,
    private val notices: SyncNoticeBus,
) {
    private val reader = OutboxReader(db, clock)
    private val builder = ChangeBuilder(db, secrets, sharePasswords)

    /**
     * Rounds serialize: an in-process round, a `SyncWorker` round and the controller's "Sync now"
     * may race; interleaved pushes would confuse the outbox ACK arithmetic.
     */
    private val roundMutex = Mutex()

    /** Marks the shared [SyncActivity] flag for the round's whole duration, including failures. */
    suspend fun round(reason: SyncRoundReason): SyncReport {
        activity.begin()
        try {
            return roundMutex.withLock { roundLocked(reason) }
        } finally {
            activity.end()
        }
    }

    private suspend fun roundLocked(reason: SyncRoundReason): SyncReport {
        // The gate owns the SYNC bypass: apply its live verdict before the round's first request,
        // so a permission granted before the process started works after a restart and a
        // revocation blocks the round (10 Local-network gate 3-4). No prompt is shown here.
        gate.onPermissionResult(gate.hasPermission())
        val started = clock.now()
        val serverUrl = address.serverUrl()
        val token = serverUrl?.let { tokens.load(hostOf(it)) }
        if (serverUrl == null || token == null) {
            return SyncReport(0, 0, 0, 0, 0, 0, clock.now() - started)
        }
        // A revoked link stays dormant until a re-link clears the marker: no periodic round and
        // no "Sync now" reaches the server again (10 Errors: 401 → Revoked stops rounds).
        if (db.syncStateDao().get()?.lastError == SYNC_ERROR_REVOKED) {
            return SyncReport(0, 0, 0, 0, 0, 0, clock.now() - started)
        }
        // 10 Durable apply effects: a marker an earlier committed page left behind retries before
        // this round's first request — the drain is local (Room and DataStore), so it recovers
        // while offline too, and the push below can never echo over an unapplied server value.
        capture.reconcile()
        effects.drainSettings()
        effects.drainPasswords()
        var pushed = 0
        var applied = 0
        var stale = 0
        var rejected = 0
        var pulled = 0
        var held = 0
        val feedsNeedingCredentials = LinkedHashSet<Long>()
        val roundId = clock.now()

        // Reasons that never pull on their own: a round with nothing to push is a no-op.
        val noPullReasons = setOf(SyncRoundReason.OUTBOX, SyncRoundReason.PLAYBACK)
        var rounds = 0
        while (rounds < MAX_ROUNDS) {
            rounds++
            val rows = capture.prepareForPush(reader.next(), reason == SyncRoundReason.FIRST_LINK)
            val changes = builder.build(rows)
            if (changes.isEmpty() && (rounds > 1 || reason in noPullReasons)) break
            val state = db.syncStateDao().get()
            val response = markingRevocation { client.sync(state?.cursor, changes) }
            pushed += changes.size
            // 10 Passwords: the acknowledgement commits under the credential coordinator —
            // the acknowledged auths' exact sent snapshots stage first (outside the writer),
            // then the outbox deletes and the immutable reference binds land in one Room
            // transaction, so a replayed push reasserts accepted bytes, not live ones.
            effects.acknowledge(changes, response.results)
            applied += response.records.size
            val page = applier.applyPage(response.records, roundId)
            // 10 Pull and apply step 5: the committed page's settings effects drain right after
            // the commit, under the same per-key lock a local set takes — and the page's durable
            // credential effects under the credential-commit coordinator.
            effects.drainSettings()
            effects.drainPasswords()
            stale += page.stale
            held += page.held
            feedsNeedingCredentials += page.feedsNeedingCredentials
            pulled += response.records.size
            raiseHlc(response.records)
            storeCursor(response.cursor)
            var cursor = response.cursor
            var hasMore = response.hasMore
            var guard = 0
            while (hasMore && guard < MAX_ROUNDS) {
                guard++
                val next = markingRevocation { client.changes(cursor, ProtocolLimits.MAX_RECORDS) }
                applied += next.records.size
                pulled += next.records.size
                val later = applier.applyPage(next.records, roundId)
                feedsNeedingCredentials += later.feedsNeedingCredentials
                // Each later page's effects drain with its own commit, not only at the round's end.
                effects.drainSettings()
                effects.drainPasswords()
                raiseHlc(next.records)
                storeCursor(next.cursor)
                cursor = next.cursor
                hasMore = next.hasMore
            }
            if (db.syncOutboxDao().count() == 0) break
        }

        // 10 Mass-change guard 2: the staged removals settle in one writer. Below the
        // thresholds the batch applies here; above it became the held prompt row. The
        // provisional row is retained until that outcome commits with it, and `applying = 1`
        // keeps the cascaded deletes from echoing back through the outbox.
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                for (verdict in MassChangeGuard.evaluate(db, roundId, localPodcastCount())) {
                    when (verdict) {
                        is MassChangeGuard.Verdict.Held -> {
                            held += verdict.batch.podcasts.size + verdict.batch.groups.size
                        }

                        is MassChangeGuard.Verdict.Approved -> {
                            val deferred =
                                MassChangeGuard.apply(db, verdict.batch, clock.now()) { syncId ->
                                    playingPodcast(syncId)
                                }
                            if (deferred.isEmpty()) {
                                db.syncHeldDao().delete(verdict.rowId)
                            } else {
                                MassChangeGuard.defer(db, verdict.rowId, deferred)
                            }
                        }
                    }
                }
            }
        }
        // 10 Passwords: one notice per pull for the feeds that still need a credential.
        if (feedsNeedingCredentials.isNotEmpty()) {
            notices.post(
                ch.lkmc.neutrodyne.sync.api.SyncNotice
                    .FeedPasswordNeeded(feedsNeedingCredentials.toList()),
            )
        }
        db.syncStateDao().recordSyncAt(clock.now())
        return SyncReport(pushed, applied, stale, rejected, pulled, held, clock.now() - started)
    }

    private suspend fun raiseHlc(records: List<RecordDto>) {
        val max =
            records.maxOfOrNull { record ->
                record.fields.values
                    .mapNotNull { it.c }
                    .maxOfOrNull { runCatching { Hlc.parse(it) }.getOrNull()?.packed ?: 0L } ?: 0L
            } ?: 0L
        if (max > 0) db.syncStateDao().raiseHlc(max)
    }

    private suspend fun storeCursor(cursor: String) {
        db.syncStateDao().storeCursor(cursor)
    }

    private suspend fun localPodcastCount(): Int = db.podcastDao().allForExport().size

    /** 10 Mass-change guard 4: the loaded, playing item's podcast defers its removal. */
    private suspend fun playingPodcast(syncId: String): Boolean {
        val active = playback.active.value
        val episodeId = active.episodeId ?: return false
        if (!active.isPlaying) return false
        val episode = db.episodeDao().byId(episodeId) ?: return false
        return db.podcastDao().byId(episode.podcastId)?.syncId == syncId
    }

    /**
     * 10 Errors: a rejected token revokes the link on every caller — worker and lane rounds too,
     * not just the controller's "Sync now" — so the marker is written next to the outbox.
     */
    private suspend fun <T> markingRevocation(block: suspend () -> T): T =
        try {
            block()
        } catch (failure: SyncTransportException) {
            if (failure.status == STATUS_REVOKED) markRevoked()
            throw failure
        }

    private suspend fun markRevoked() {
        db.syncStateDao().markRevoked(SYNC_ERROR_REVOKED)
    }

    private companion object {
        const val STATUS_REVOKED = 401
    }
}
