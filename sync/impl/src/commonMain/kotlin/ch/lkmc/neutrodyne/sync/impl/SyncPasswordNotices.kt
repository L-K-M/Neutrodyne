// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.LocalNetworkGate
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.model.settings.SyncSettingKeys
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Instant

/**
 * A daily, device-local security check (10 Passwords). The app scope owns the request;
 * cancelling a window collector does not cancel it. A pending result waits for a collector
 * instead of being lost in a replay-zero event bus during startup.
 */
class SyncPasswordNotices(
    private val db: NeutrodyneDatabase,
    private val client: SyncClient,
    private val address: SyncServerAddress,
    private val tokens: SyncTokenStore,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val network: NetworkMonitor,
    private val gate: LocalNetworkGate,
    private val identity: SyncIdentityLock,
    private val scope: CoroutineScope,
    private val onRevoked: suspend (SyncStateEntity) -> Unit = {},
) {
    private val startup = Mutex()
    private val delivery = Mutex()
    private var request: Deferred<Pending?>? = null
    private var delivered = false

    val notices: Flow<SyncNotice> =
        flow {
            val pending =
                startup
                    .withLock {
                        request ?: scope.async { fetch() }.also { request = it }
                    }.await() ?: return@flow
            delivery.withLock {
                if (delivered || !isCurrent(pending.profile)) return@withLock
                emit(SyncNotice.PasswordSet(pending.date))
                delivered = true
                // A failed durable receipt may repeat after restart, rather than suppress a
                // security notice that was never delivered. It never changes the attempt time.
                try {
                    val journal = readJournal()
                    settings.set(
                        SyncSettingKeys.ACCOUNT_NOTICE_STATE,
                        SyncJson.encodeToString(journal.copy(seen = journal.seen + (pending.key to pending.stamp))),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Delivery happened; a missing receipt may repeat after restart.
                }
            }
        }

    private data class Context(
        val profile: SyncStateEntity,
        val transport: SyncClient,
        val key: String,
    )

    private data class Pending(
        val profile: SyncStateEntity,
        val key: String,
        val stamp: String,
        val date: String,
    )

    @Serializable
    private data class Journal(
        val attemptedAt: Long? = null,
        val seen: Map<String, String> = emptyMap(),
    )

    private suspend fun fetch(): Pending? {
        return try {
            val context =
                identity.withLock {
                    val profile = db.syncStateDao().get() ?: return@withLock null
                    if (!profile.enabled || profile.linkedAt == null || profile.lastError == SYNC_ERROR_REVOKED) {
                        return@withLock null
                    }
                    val url = address.serverUrl() ?: return@withLock null
                    if (url != profile.serverUrl || !network.status.value.isConnected) return@withLock null
                    val host = bareHostOf(url) ?: return@withLock null
                    gate.onPermissionResult(gate.hasPermission())
                    if (gate.needsPermission(host) && !gate.hasPermission()) return@withLock null
                    if (url.startsWith("http://", ignoreCase = true) && !gate.allowsPlainHttp(host)) {
                        return@withLock null
                    }
                    val token = tokens.load(hostOf(url)) ?: return@withLock null
                    val account = profile.accountId ?: return@withLock null
                    val key = (url.length.toString() + ":" + url + account).encodeUtf8().sha256().hex()
                    val journal = readJournal()
                    val now = clock.now()
                    if (now < 0) return@withLock null
                    val previous = journal.attemptedAt?.takeIf { it >= 0 }
                    if (previous != null && (now <= previous || now - previous < DAY_MS)) return@withLock null
                    // Persist before HTTP: even an interrupted or failed request consumes its
                    // daily slot. A failed journal write must not issue an unthrottled request.
                    val stored =
                        settings.set(
                            SyncSettingKeys.ACCOUNT_NOTICE_STATE,
                            SyncJson.encodeToString(journal.copy(attemptedAt = now)),
                        )
                    if (stored !is Outcome.Success) return@withLock null
                    Context(profile, client.withCredentials(url, token) ?: client, key)
                } ?: return null

            val summary =
                try {
                    context.transport.account()
                } catch (failure: SyncTransportException) {
                    if (failure.status == 401) onRevoked(context.profile)
                    throw failure
                }
            if (!isCurrent(context.profile)) return null
            val instant = summary.passwordSetAt?.let { Instant.parse(it) } ?: return null
            val stamp = instant.toString()
            if (readJournal().seen[context.key] == stamp) return null
            Pending(context.profile, context.key, stamp, stamp.substringBefore('T'))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Best-effort startup check. The durable daily slot remains consumed; no
            // response or exception text is exported as a notice or bookkeeping value.
            null
        }
    }

    private suspend fun isCurrent(profile: SyncStateEntity): Boolean =
        identity.withLock {
            val current = db.syncStateDao().get()
            runCatching { requireSyncProfile(profile, current) }.isSuccess && address.serverUrl() == profile.serverUrl
        }

    private suspend fun readJournal(): Journal {
        // A store read failure is not an empty journal: doing so would erase the
        // only daily-throttle evidence and permit an unthrottled request.
        val stored = settings.getOrThrow(SyncSettingKeys.ACCOUNT_NOTICE_STATE)
        if (stored.isBlank()) return Journal()
        return try {
            SyncJson.decodeFromString<Journal>(stored)
        } catch (_: SerializationException) {
            Journal()
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1_000L
    }
}
