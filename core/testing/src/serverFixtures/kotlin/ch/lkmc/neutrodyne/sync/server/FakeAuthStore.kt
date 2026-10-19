// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.SyncCollection

/** Scripted domain ports. Real SQLite tests own concurrency and durable rollback evidence. */
internal class FakeAuthStore : AuthStore {
    val data = FakeAuthTransaction()

    override suspend fun <T> readAuth(block: AuthRead.() -> T): T = data.block()

    override suspend fun <T> writeAuth(block: AuthWrite.() -> T): T = data.block()
}

internal class FakeAuthTransaction : AuthWrite {
    val owners = mutableMapOf<Long, AuthAccount>()
    val credentials = mutableMapOf<String, AuthToken>()
    val installations = mutableMapOf<String, AuthDevice>()
    val requests = mutableMapOf<String, AuthLink>()
    val events = mutableListOf<ServerEvent>()
    val auditEntries = mutableListOf<AuditEntry>()
    val auditEvents = mutableListOf<String>()
    val counts = mutableMapOf<Long, Map<SyncCollection, Int>>()
    val purges = mutableListOf<Pair<Long, String>>()

    override fun accounts(): List<AuthAccount> = owners.values.toList()

    override fun account(id: Long): AuthAccount? = owners[id]

    override fun account(username: String): AuthAccount? = owners.values.singleOrNull { it.username == username }

    override fun token(hash: String): AuthToken? = credentials[hash]

    override fun tokens(
        accountId: Long?,
        kind: TokenKind,
    ): List<AuthToken> =
        credentials.values.filter {
            it.accountId ==
                accountId &&
                it.kind == kind
        }

    override fun device(id: String): AuthDevice? = installations[id]

    override fun devices(accountId: Long): List<AuthDevice> = installations.values.filter { it.accountId == accountId }

    override fun link(deviceHash: String): AuthLink? = requests[deviceHash]

    override fun linkByUserHash(userHash: String): AuthLink? = requests.values.singleOrNull { it.userHash == userHash }

    override fun liveCounts(accountId: Long): Map<SyncCollection, Int> = counts[accountId].orEmpty()

    override fun recentAudit(limit: Int): List<AuditEntry> = auditEntries.asReversed().take(limit.coerceIn(1, 200))

    override fun storedRecordCount(): Long = counts.values.sumOf { it.values.sum().toLong() }

    override fun pendingLinks(now: Long): Int =
        requests.values.count {
            it.expiresAt > now && it.consumedAt == null &&
                it.deniedAt == null
        }

    override fun createAccount(
        username: String,
        role: AccountRole,
        passwordHash: String?,
        epoch: String,
        now: Long,
        quota: Long,
    ): AuthAccount =
        AuthAccount(
            (owners.keys.maxOrNull() ?: 0) + 1,
            username,
            role,
            passwordHash,
            now.takeIf {
                passwordHash != null
            },
            null,
            quota,
        ).also(::save)

    override fun save(account: AuthAccount) {
        owners[account.id] = account
    }

    override fun save(token: AuthToken) {
        credentials[token.hash] = token
    }

    override fun save(device: AuthDevice) {
        installations[device.info.id] = device
    }

    override fun save(link: AuthLink) {
        requests[link.deviceHash] = link
    }

    override fun purgeLibrary(
        accountId: Long,
        epoch: String,
    ) {
        purges += accountId to epoch
    }

    override fun deleteAccount(accountId: Long) {
        owners.remove(accountId)
        credentials.entries.removeAll { it.value.accountId == accountId }
        installations.entries.removeAll { it.value.accountId == accountId }
        requests.entries.removeAll { it.value.accountId == accountId }
        counts.remove(accountId)
    }

    override fun audit(
        accountId: Long?,
        event: String,
        now: Long,
        ip: String?,
    ) {
        auditEvents += event
        auditEntries += AuditEntry(accountId, now, event, ip)
    }

    override fun emit(event: ServerEvent) {
        events += event
    }
}
