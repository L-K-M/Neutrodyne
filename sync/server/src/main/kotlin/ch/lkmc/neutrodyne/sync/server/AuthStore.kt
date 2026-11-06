// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto

/** Auth uses the same serialized writer and post-commit event ordering as records. Callbacks
 * are blocking DB-only work; hashing, randomness, response encoding and file I/O happen outside them.
 */
internal interface AuthStore {
    suspend fun <T> readAuth(block: AuthRead.() -> T): T

    suspend fun <T> writeAuth(block: AuthWrite.() -> T): T
}

internal interface AuthRead {
    fun accounts(): List<AuthAccount>

    fun account(id: Long): AuthAccount?

    fun account(username: String): AuthAccount?

    fun token(hash: String): AuthToken?

    fun tokens(
        accountId: Long?,
        kind: TokenKind,
    ): List<AuthToken>

    fun device(id: String): AuthDevice?

    fun devices(accountId: Long): List<AuthDevice>

    fun link(deviceHash: String): AuthLink?

    fun linkByUserHash(userHash: String): AuthLink?

    fun liveCounts(accountId: Long): Map<SyncCollection, Int>

    fun recentAudit(limit: Int): List<AuditEntry>

    fun storedRecordCount(): Long

    fun pendingLinks(now: Long): Int
}

internal interface AuthWrite : AuthRead {
    fun createAccount(
        username: String,
        role: AccountRole,
        passwordHash: String?,
        epoch: String,
        now: Long,
        quota: Long,
    ): AuthAccount

    fun save(account: AuthAccount)

    fun save(token: AuthToken)

    fun save(device: AuthDevice)

    fun save(link: AuthLink)

    fun purgeLibrary(
        accountId: Long,
        epoch: String,
    )

    fun deleteAccount(accountId: Long)

    fun audit(
        accountId: Long?,
        event: String,
        now: Long,
        ip: String? = null,
    )

    fun emit(event: ServerEvent)
}

internal data class AuthAccount(
    val id: Long,
    val username: String,
    val role: AccountRole,
    val passwordHash: String?,
    val passwordSetAt: Long?,
    val disabledAt: Long?,
    val quotaRecords: Long = ServerDefaults.QUOTA_RECORDS,
)

internal data class AuthToken(
    val hash: String,
    val accountId: Long?,
    val deviceId: String?,
    val kind: TokenKind,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
    val expiresAt: Long? = null,
    val revokedAt: Long? = null,
    val consumedAt: Long? = null,
    val label: String? = null,
) {
    fun active(now: Long): Boolean = revokedAt == null && consumedAt == null && (expiresAt == null || expiresAt > now)
}

internal data class AuthDevice(
    val info: DeviceInfoDto,
    val accountId: Long,
    val createdAt: Long,
    val lastSeenAt: Long? = null,
    val revokedAt: Long? = null,
)

internal enum class LinkPurpose(
    val wire: String,
) {
    DEVICE("device"),
    WEB("web"),
}

/** Both lookup codes are hashed, including the low-entropy user code. No raw code is persisted. */
internal data class AuthLink(
    val deviceHash: String,
    val userHash: String,
    val device: DeviceInfoDto,
    val purpose: LinkPurpose,
    val startIp: String,
    val createdAt: Long,
    val expiresAt: Long,
    val accountId: Long? = null,
    val approverHash: String? = null,
    val approvedAt: Long? = null,
    val deniedAt: Long? = null,
    val consumedAt: Long? = null,
    val lookups: Int = 0,
    val lastPollAt: Long? = null,
    val intervalSeconds: Int = 5,
)
