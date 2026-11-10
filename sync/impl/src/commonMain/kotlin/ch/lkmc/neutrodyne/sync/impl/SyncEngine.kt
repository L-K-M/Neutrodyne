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
import ch.lkmc.neutrodyne.sync.api.SyncRoundFailure
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.api.SyncRoundSummary
import ch.lkmc.neutrodyne.sync.protocol.ChangeStatus
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/** The host of a server URL, for the token origin (`sync:<host>`). */
fun hostOf(url: String): String = withoutScheme(url).substringBefore('/')

/**
 * The bare host of a normalised server URL, for the local-network gate's classification: userinfo
 * and an explicit `:port` are dropped, brackets around an IPv6 literal removed, and a multi-colon
 * remainder is treated as the literal itself. `null` for an empty or malformed authority, so the
 * link flow fails it explicitly instead of classifying garbage as a public address.
 */
internal fun bareHostOf(url: String): String? {
    val authority =
        withoutScheme(url)
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

/**
 * Drops a leading `http://`/`https://` in any letter case, so a persisted address typed with an
 * uppercase scheme still yields the same token origin and gate classification. Any other prefix is
 * kept: only the two HTTP schemes exist.
 */
private fun withoutScheme(url: String): String =
    when {
        url.startsWith("https://", ignoreCase = true) -> url.substring("https://".length)
        url.startsWith("http://", ignoreCase = true) -> url.substring("http://".length)
        else -> url
    }

/** The maximum number of push rounds in one `round()` (10 Sync round). */
private const val MAX_ROUNDS = 50

/** A server admission failure retains the replay's accepted clock for later repair. */
internal class SyncReplayRejectedException(
    val code: String?,
) : Exception("The server rejected a full sync replay")

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
    private val onSessionApplied: suspend () -> Unit = {},
    /**
     * Re-primes the volatile preference cache from the store before the round's first local
     * effect or push — the persisted share gate, not the restart default, decides whether the
     * outgoing changes carry auth. Bound by `SyncBindings`; bare test engines may omit it.
     */
    private val refreshPreferences: suspend () -> Unit = {},
    /** Lazy first-link merger edge; recovery stays inside the round mutex. */
    private val recoverExpiredCursor: (suspend (SyncStateEntity, SyncClient) -> SyncReport)? = null,
    private val identity: SyncIdentityLock = SyncIdentityLock(),
    internal val diagnosticsRuntime: SyncRuntimeDiagnostics = SyncRuntimeDiagnostics(),
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
        val observation = RoundObservation(diagnosticsRuntime.currentTicket())
        try {
            // Identity writers may need a first-link round themselves. Snapshot before the
            // round mutex so another round never holds it while waiting for that writer.
            val snapshot = captureRound()
            val report = roundMutex.withLock { roundWithRecovery(reason, snapshot, observation) }
            observation.ticket?.let {
                diagnosticsRuntime.completed(it, SyncRoundSummary(reason, clock.now(), report))
            }
            return report
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            observation.ticket?.let {
                diagnosticsRuntime.completed(
                    it,
                    SyncRoundSummary(
                        reason = reason,
                        completedAtMs = clock.now(),
                        report = null,
                        failure =
                            when (failure) {
                                is SyncTransportException -> SyncRoundFailure.TRANSPORT
                                is ProtocolException -> SyncRoundFailure.PROTOCOL
                                else -> SyncRoundFailure.UNEXPECTED
                            },
                        httpStatus = (failure as? SyncTransportException)?.status?.takeIf { it in 100..599 },
                        protocolCode = (failure as? ProtocolException)?.code?.wire,
                    ),
                )
            }
            throw failure
        } finally {
            activity.end()
        }
    }

    private class RoundObservation(
        var ticket: SyncDiagnosticTicket?,
    )

    /** The round mutex stays held for both the full pull and its resumed push. */
    private suspend fun roundWithRecovery(
        reason: SyncRoundReason,
        snapshot: RoundSnapshot,
        observation: RoundObservation,
    ): SyncReport =
        try {
            roundLocked(reason, snapshot, observation)
        } catch (failure: SyncTransportException) {
            val recover = recoverExpiredCursor
            val profile = snapshot.state
            if (failure.status != STATUS_CURSOR_EXPIRED || failure.code != SyncErrorCode.CURSOR_EXPIRED.wire ||
                recover == null || profile?.enabled != true || profile.linkedAt == null ||
                profile.lastError == SYNC_ERROR_REVOKED
            ) {
                throw failure
            }
            requireSyncProfile(profile, db.syncStateDao().get())
            check(address.serverUrl() == profile.serverUrl) { "Sync profile changed during recovery" }
            val pull = markingRevocation(profile) { recover(profile, checkNotNull(snapshot.client)) }
            requireSyncProfile(profile, db.syncStateDao().get())
            check(address.serverUrl() == profile.serverUrl) { "Sync profile changed during recovery" }
            // The mutex is already held: a nested public round would deadlock. The
            // resumed round pushes the durable replays using the newly adopted cursor.
            val push = roundLocked(reason, snapshot, observation, RoundPurpose.RECOVERY_PUSH)
            requireSyncProfile(profile, db.syncStateDao().get())
            notices.post(
                ch.lkmc.neutrodyne.sync.api.SyncNotice
                    .Resynced(push.pushed),
            )
            push.copy(
                applied = pull.applied + push.applied,
                stale = pull.stale + push.stale,
                pulled = pull.pulled + push.pulled,
                held = pull.held + push.held,
                durationMs = pull.durationMs + push.durationMs,
            )
        }

    private enum class RoundPurpose { NORMAL, RECOVERY_PUSH }

    private class RoundSnapshot(
        val serverUrl: String?,
        val state: SyncStateEntity?,
        val client: SyncClient?,
    )

    /** Samples network time before local apply work; only the captured profile may store it. */
    internal suspend fun observeServerTime(
        profile: SyncStateEntity?,
        serverTime: String,
        sentAtMs: Long,
        receivedAtMs: Long,
    ): SyncClockOffset? {
        if (profile == null) return null
        val serverMs = runCatching { Instant.parse(serverTime).toEpochMilliseconds() }.getOrNull() ?: return null
        val correction = estimateSyncClockOffset(serverMs, sentAtMs, receivedAtMs) ?: return null
        db.withWriteTransaction {
            val state = checkNotNull(db.syncStateDao().get())
            requireSyncProfile(profile, state)
            db.syncStateDao().setClockOffset(correction.offsetMs)
            repairSyncClock(db, state, correction.adjustedReceivedAtMs)
        }
        return correction
    }

    private suspend fun captureRound(): RoundSnapshot =
        identity.withLock {
            val serverUrl = address.serverUrl() ?: return@withLock RoundSnapshot(null, null, null)
            val token = tokens.load(hostOf(serverUrl)) ?: return@withLock RoundSnapshot(null, null, null)
            val state = db.syncStateDao().get()
            if (state?.serverUrl != null) {
                check(state.serverUrl == serverUrl) { "Saved server does not match the linked profile" }
            }
            RoundSnapshot(serverUrl, state, client.withCredentials(serverUrl, token) ?: client)
        }

    private suspend fun verifyRound(snapshot: RoundSnapshot) {
        val profile = snapshot.state ?: return
        requireSyncProfile(profile, db.syncStateDao().get())
        if (profile.serverUrl !=
            null
        ) {
            check(address.serverUrl() == snapshot.serverUrl) { "Sync profile changed during round" }
        }
    }

    private suspend fun roundLocked(
        reason: SyncRoundReason,
        snapshot: RoundSnapshot,
        observation: RoundObservation,
        purpose: RoundPurpose = RoundPurpose.NORMAL,
    ): SyncReport {
        gate.onPermissionResult(gate.hasPermission())
        val started = clock.now()
        val transport = snapshot.client ?: return SyncReport(0, 0, 0, 0, 0, 0, clock.now() - started)
        // Reuse the captured state; diagnostics must not add a second address/token read.
        observation.ticket = diagnosticsRuntime.activate(snapshot.state.diagnosticProfile(snapshot.serverUrl))
        if (snapshot.state?.lastError == SYNC_ERROR_REVOKED) return SyncReport(0, 0, 0, 0, 0, 0, clock.now() - started)
        if (snapshot.state?.enabled == false && reason != SyncRoundReason.FIRST_LINK) {
            return SyncReport(0, 0, 0, 0, 0, 0, clock.now() - started)
        }
        verifyRound(snapshot)
        // The volatile share gate is only as fresh as its last hydration — a restart's cache
        // would withhold auth until a retoggle, so every round re-reads the persisted switches
        // before the first effect or push consults them (10 Settings capture).
        refreshPreferences()
        // 10 Durable apply effects: a marker an earlier committed page left behind retries before
        // this round's first request — the drain is local (Room and DataStore), so it recovers
        // while offline too, and the push below can never echo over an unapplied server value.
        capture.reconcile()
        effects.drainSettings()
        effects.drainPasswords()
        // 10 Mass-change guard 1: the share threshold's denominator is the local library as the
        // round starts — before this pull's adds and dedupes can dilute the removals' share.
        val localPodcastsAtStart = localPodcastCount()
        var pushed = 0
        var applied = 0
        var stale = 0
        var rejected = 0
        var pulled = 0
        var held = 0
        var sessionApplied = false
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
            verifyRound(snapshot)
            val sentAt = clock.now()
            val response = markingRevocation(snapshot.state) { transport.sync(state?.cursor, changes) }
            val receivedAt = clock.now()
            verifyRound(snapshot)
            pushed += changes.size
            // 10 Passwords: the acknowledgement commits under the credential coordinator —
            // the acknowledged auths' exact sent snapshots stage first (outside the writer),
            // then the outbox deletes and the immutable reference binds land in one Room
            // transaction, so a replayed push reasserts accepted bytes, not live ones.
            effects.acknowledge(changes, response.results, snapshot.state)
            applied += response.records.size
            val page = applier.applyPage(response.records, roundId, expectedProfile = snapshot.state)
            // 10 Pull and apply step 5: the committed page's settings effects drain right after
            // the commit, under the same per-key lock a local set takes — and the page's durable
            // credential effects under the credential-commit coordinator.
            effects.drainSettings()
            effects.drainPasswords()
            stale += page.stale
            sessionApplied = sessionApplied || page.sessionApplied
            // Staged removals count as held only after the verdict below.
            feedsNeedingCredentials += page.feedsNeedingCredentials
            pulled += response.records.size
            commitProgress(response.records, response.cursor, snapshot.state)
            observeServerTime(snapshot.state, response.serverTime, sentAt, receivedAt)
            var cursor = response.cursor
            var hasMore = response.hasMore
            var guard = 0
            while (hasMore && guard < MAX_ROUNDS) {
                guard++
                val pageSentAt = clock.now()
                val next = markingRevocation(snapshot.state) { transport.changes(cursor, ProtocolLimits.MAX_RECORDS) }
                val pageReceivedAt = clock.now()
                verifyRound(snapshot)
                applied += next.records.size
                pulled += next.records.size
                val later = applier.applyPage(next.records, roundId, expectedProfile = snapshot.state)
                sessionApplied = sessionApplied || later.sessionApplied
                feedsNeedingCredentials += later.feedsNeedingCredentials
                // Each later page's effects drain with its own commit, not only at the round's end.
                effects.drainSettings()
                effects.drainPasswords()
                commitProgress(next.records, next.cursor, snapshot.state)
                observeServerTime(snapshot.state, next.serverTime, pageSentAt, pageReceivedAt)
                cursor = next.cursor
                hasMore = next.hasMore
            }
            if (purpose == RoundPurpose.RECOVERY_PUSH) {
                // A rejected original replay must neither loop unchanged nor announce
                // success. Accepted entries above are ACKed; the rejection keeps its clock.
                response.results.firstOrNull { it.status == ChangeStatus.REJECTED.wire }?.let {
                    throw SyncReplayRejectedException(it.code)
                }
            }
            if (db.syncOutboxDao().count() == 0) break
        }

        held += completePull(roundId, localPodcastsAtStart, sessionApplied, feedsNeedingCredentials, snapshot.state)
        db.withWriteTransaction {
            snapshot.state?.let { requireSyncProfile(it, db.syncStateDao().get()) }
            db.syncStateDao().recordSyncAt(clock.now())
        }
        return SyncReport(pushed, applied, stale, rejected, pulled, held, clock.now() - started)
    }

    /** Settle full-pull removals through the same guard as an incremental round. */
    internal suspend fun completePull(
        roundId: Long,
        localPodcastsAtStart: Int,
        sessionApplied: Boolean,
        feedsNeedingCredentials: Set<Long>,
        expectedProfile: SyncStateEntity? = null,
    ): Int {
        var held = 0
        // 10 Mass-change guard 2: the staged removals settle in one writer. Below the
        // thresholds the batch applies here; above it became the held prompt row. The
        // provisional row is retained until that outcome commits with it, and `applying = 1`
        // keeps the cascaded deletes from echoing back through the outbox.
        db.withWriteTransaction {
            if (expectedProfile != null) requireSyncProfile(expectedProfile, db.syncStateDao().get())
            db.syncStateDao().withApplying {
                for (verdict in MassChangeGuard.evaluate(db, roundId, localPodcastsAtStart)) {
                    when (verdict) {
                        is MassChangeGuard.Verdict.Held -> {
                            held += verdict.batch.podcasts.size + verdict.batch.groups.size
                        }

                        is MassChangeGuard.Verdict.Approved -> {
                            // 10 step 4: an already-DEFERRED batch holds while the item stays
                            // loaded (stop or item change releases it — a pause-scheduled
                            // round like this one does not); a fresh batch defers only while
                            // the loaded item actually plays.
                            val deferred =
                                MassChangeGuard.apply(db, verdict.batch, clock.now()) { syncId ->
                                    if (verdict.alreadyDeferred) {
                                        MassChangeGuard.loadedRemovalDeferred(db, playback.active.value, syncId)
                                    } else {
                                        MassChangeGuard.playingRemovalDeferred(db, playback.active.value, syncId)
                                    }
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
        // 10 Now playing: a committed session record re-evaluates once per round — the raw
        // record already holds every page's winner, so one check covers multi-page pulls.
        if (sessionApplied) onSessionApplied()
        // 10 Passwords: one notice per pull for the feeds that still need a credential.
        if (feedsNeedingCredentials.isNotEmpty()) {
            notices.post(
                ch.lkmc.neutrodyne.sync.api.SyncNotice
                    .FeedPasswordNeeded(feedsNeedingCredentials.toList()),
            )
        }
        return held
    }

    private suspend fun commitProgress(
        records: List<RecordDto>,
        cursor: String,
        profile: SyncStateEntity?,
    ) {
        db.withWriteTransaction {
            if (profile != null) requireSyncProfile(profile, db.syncStateDao().get())
            raiseHlc(records)
            storeCursor(cursor)
        }
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

    /**
     * 10 Errors: a rejected token revokes the link on every caller — worker and lane rounds too,
     * not just the controller's "Sync now" — so the marker is written next to the outbox.
     */
    private suspend fun <T> markingRevocation(
        expectedProfile: SyncStateEntity? = null,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (failure: SyncTransportException) {
            if (failure.status == STATUS_REVOKED && expectedProfile != null) {
                db.withWriteTransaction {
                    requireSyncProfile(expectedProfile, db.syncStateDao().get())
                    markRevoked()
                }
                throw SyncOwnedRevocationException(expectedProfile, failure)
            }
            throw failure
        }

    private suspend fun markRevoked() {
        db.syncStateDao().markRevoked(SYNC_ERROR_REVOKED)
    }

    private companion object {
        const val STATUS_REVOKED = 401
        const val STATUS_CURSOR_EXPIRED = 410
    }
}
