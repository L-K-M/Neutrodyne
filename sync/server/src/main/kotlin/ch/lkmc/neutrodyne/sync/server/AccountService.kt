// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary
import ch.lkmc.neutrodyne.sync.protocol.dto.LibraryCountsDto
import kotlin.time.Instant

internal class AccountService(
    private val auth: AuthStore,
    private val store: SyncStore,
    private val inputs: ServerInputs,
) {
    private val secrets = AuthSecrets(inputs)

    suspend fun summary(principal: AccountPrincipal): AccountSummary {
        val now = inputs.wallMs()
        return auth.readAuth {
            requireToken(principal.tokenHash, principal.tokenKind, now)
            val owner = checkNotNull(account(principal.accountId))
            val devices = devices(principal.accountId).count { it.revokedAt == null }
            val counts = liveCounts(principal.accountId)
            val notice = owner.passwordSetAt?.takeIf { now - it < 30 * DAY_MS }
            AccountSummary(
                owner.username,
                LibraryCountsDto(
                    counts[SyncCollection.PODCAST] ?: 0,
                    counts[SyncCollection.GROUP] ?: 0,
                    counts[SyncCollection.EPISODE] ?: 0,
                    counts[SyncCollection.UPNEXT] ?: 0,
                    counts[SyncCollection.SETTING] ?: 0,
                ),
                devices,
                owner.quotaRecords,
                counts.values.sum().toLong(),
                notice?.let { Instant.fromEpochMilliseconds(it).toString() },
            )
        }
    }

    /** W7 must coordinate backup deletion/export with this operation before exposing export routes. */
    suspend fun purge(
        principal: AccountPrincipal,
        confirm: String,
    ) {
        if (confirm != "DELETE") invalidAuth()
        val oldEpoch = store.read(principal.accountId) { account.cursor.epoch }
        var epoch = secrets.epoch()
        while (epoch == oldEpoch) epoch = secrets.epoch()
        val now = inputs.wallMs()
        auth.writeAuth {
            requireToken(principal.tokenHash, principal.tokenKind, now)
            purgeLibrary(principal.accountId, epoch)
            for (kind in listOf(TokenKind.DEVICE, TokenKind.WEB_SESSION)) {
                tokens(principal.accountId, kind).filter { it.active(now) }.forEach { revoke(it, now) }
            }
            emit(ServerEvent.Revoked(principal.accountId))
            audit(principal.accountId, "account_purged", now)
        }
    }
}
