// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import java.sql.Connection
import java.sql.ResultSet

/** Shares the store's transaction/lifetime confinement, without exposing JDBC to services. */
internal open class JdbcAuthRead(
    private val jdbc: Connection,
) : AuthRead {
    private var active = true
    private val owner = Thread.currentThread().threadId()
    protected val connection: Connection get() {
        check(active && owner == Thread.currentThread().threadId()) { "Transaction scope has ended" }
        return jdbc
    }

    fun end() {
        active = false
    }

    override fun accounts(): List<AuthAccount> = connection.query("SELECT * FROM account", row = { it.authAccount() })

    override fun account(id: Long): AuthAccount? =
        connection
            .query("SELECT * FROM account WHERE id=?", id) {
                it.authAccount()
            }.singleOrNull()

    override fun account(username: String): AuthAccount? =
        connection
            .query("SELECT * FROM account WHERE username=?", username) {
                it.authAccount()
            }.singleOrNull()

    override fun token(hash: String): AuthToken? =
        connection
            .query("SELECT * FROM token WHERE hash=?", hash) {
                it.authToken()
            }.singleOrNull()

    override fun tokens(
        accountId: Long?,
        kind: TokenKind,
    ): List<AuthToken> =
        connection.query(
            "SELECT * FROM token WHERE account_id IS ? AND kind=?",
            accountId,
            kind.wire(),
        ) { it.authToken() }

    override fun device(id: String): AuthDevice? =
        connection
            .query("SELECT * FROM device WHERE id=?", id) {
                it.authDevice()
            }.singleOrNull()

    override fun devices(accountId: Long): List<AuthDevice> =
        connection.query("SELECT * FROM device WHERE account_id=?", accountId) {
            it.authDevice()
        }

    override fun link(deviceHash: String): AuthLink? =
        connection
            .query("SELECT * FROM link_request WHERE device_code_hash=?", deviceHash) {
                it.authLink()
            }.singleOrNull()

    override fun linkByUserHash(userHash: String): AuthLink? =
        connection
            .query("SELECT * FROM link_request WHERE user_code=?", userHash) {
                it.authLink()
            }.singleOrNull()

    override fun liveCounts(accountId: Long): Map<SyncCollection, Int> =
        connection
            .query("SELECT coll,COUNT(*) FROM record WHERE account_id=? AND dead=0 GROUP BY coll", accountId) {
                SyncCollection.fromWire(it.getString(1)) to it.getInt(2)
            }.toMap()

    override fun pendingLinks(now: Long): Int =
        connection
            .query(
                "SELECT COUNT(*) FROM link_request WHERE expires_at>? AND consumed_at IS NULL AND denied_at IS NULL",
                now,
            ) {
                it.getInt(1)
            }.single()
}

internal class JdbcAuthWrite(
    connection: Connection,
) : JdbcAuthRead(connection),
    AuthWrite {
    val events = mutableListOf<ServerEvent>()

    override fun createAccount(
        username: String,
        role: AccountRole,
        passwordHash: String?,
        epoch: String,
        now: Long,
        quota: Long,
    ): AuthAccount {
        connection.update(
            "INSERT INTO account(username,role,password_hash,password_set_at,epoch,created_at,quota_records) VALUES (?,?,?,?,?,?,?)",
            username,
            role.wire(),
            passwordHash,
            now.takeIf {
                passwordHash !=
                    null
            },
            epoch,
            now,
            quota,
        )
        return checkNotNull(account(username))
    }

    override fun save(account: AuthAccount) {
        connection.update(
            "UPDATE account SET password_hash=?,password_set_at=?,disabled_at=?,role=? WHERE id=?",
            account.passwordHash,
            account.passwordSetAt,
            account.disabledAt,
            account.role.wire(),
            account.id,
        )
    }

    override fun save(token: AuthToken) {
        connection.update(
            """INSERT INTO token(hash,account_id,device_id,kind,created_at,last_used_at,expires_at,revoked_at,consumed_at,label)
            VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(hash) DO UPDATE SET last_used_at=excluded.last_used_at,
            expires_at=excluded.expires_at,revoked_at=excluded.revoked_at,consumed_at=excluded.consumed_at""",
            token.hash,
            token.accountId,
            token.deviceId,
            token.kind.wire(),
            token.createdAt,
            token.lastUsedAt,
            token.expiresAt,
            token.revokedAt,
            token.consumedAt,
            token.label,
        )
    }

    override fun save(device: AuthDevice) {
        val d = device.info
        connection.update(
            """INSERT INTO device(id,account_id,name,platform,app_version,node_id,created_at,last_seen_at,revoked_at)
            VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,
            last_seen_at=excluded.last_seen_at,revoked_at=excluded.revoked_at""",
            d.id,
            device.accountId,
            d.name,
            d.platform,
            d.appVersion,
            d.nodeId,
            device.createdAt,
            device.lastSeenAt,
            device.revokedAt,
        )
    }

    override fun save(link: AuthLink) {
        connection.update(
            """INSERT INTO link_request(device_code_hash,user_code,device_json,purpose,start_ip,created_at,expires_at,
            account_id,approver_token_hash,approved_at,denied_at,consumed_at,lookups,last_poll_at,interval_s)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(device_code_hash) DO UPDATE SET
            account_id=excluded.account_id,approver_token_hash=excluded.approver_token_hash,approved_at=excluded.approved_at,
            denied_at=excluded.denied_at,consumed_at=excluded.consumed_at,lookups=excluded.lookups,
            last_poll_at=excluded.last_poll_at,interval_s=excluded.interval_s""",
            link.deviceHash,
            link.userHash,
            SyncJson.encodeToString(DeviceInfoDto.serializer(), link.device),
            link.purpose.wire,
            link.startIp,
            link.createdAt,
            link.expiresAt,
            link.accountId,
            link.approverHash,
            link.approvedAt,
            link.deniedAt,
            link.consumedAt,
            link.lookups,
            link.lastPollAt,
            link.intervalSeconds,
        )
    }

    override fun purgeLibrary(
        accountId: Long,
        epoch: String,
    ) {
        connection.update("DELETE FROM record WHERE account_id=?", accountId)
        connection.update("DELETE FROM identity_redirect WHERE account_id=?", accountId)
        connection.update("UPDATE account SET epoch=?,seq=0,min_cursor=0 WHERE id=?", epoch, accountId)
        connection.update("UPDATE device SET last_cursor=NULL WHERE account_id=?", accountId)
    }

    override fun audit(
        accountId: Long?,
        event: String,
        now: Long,
        ip: String?,
    ) {
        connection.update("INSERT INTO audit(account_id,at,event,ip) VALUES (?,?,?,?)", accountId, now, event, ip)
    }

    override fun emit(event: ServerEvent) {
        connection
        events += event
    }
}

private fun ResultSet.authAccount() =
    AuthAccount(
        getLong("id"),
        getString("username"),
        AccountRole.valueOf(getString("role").uppercase()),
        getString("password_hash"),
        nullableLong("password_set_at"),
        nullableLong("disabled_at"),
        getLong("quota_records"),
    )

private fun ResultSet.authToken() =
    AuthToken(
        getString("hash"),
        nullableLong("account_id"),
        getString("device_id"),
        TokenKind.valueOf(getString("kind").uppercase()),
        getLong("created_at"),
        nullableLong("last_used_at"),
        nullableLong("expires_at"),
        nullableLong("revoked_at"),
        nullableLong("consumed_at"),
        getString("label"),
    )

private fun ResultSet.authDevice() =
    AuthDevice(
        DeviceInfoDto(
            getString("id"),
            getString("name"),
            getString("platform"),
            getString("app_version").orEmpty(),
            getString("node_id"),
        ),
        getLong("account_id"),
        getLong("created_at"),
        nullableLong("last_seen_at"),
        nullableLong("revoked_at"),
    )

private fun ResultSet.authLink() =
    AuthLink(
        getString("device_code_hash"),
        getString("user_code"),
        SyncJson.decodeFromString(DeviceInfoDto.serializer(), getString("device_json")),
        LinkPurpose.valueOf(getString("purpose").uppercase()),
        getString("start_ip"),
        getLong("created_at"),
        getLong("expires_at"),
        nullableLong("account_id"),
        getString("approver_token_hash"),
        nullableLong("approved_at"),
        nullableLong("denied_at"),
        nullableLong("consumed_at"),
        getInt("lookups"),
        nullableLong("last_poll_at"),
        getInt("interval_s"),
    )

private fun ResultSet.nullableLong(column: String): Long? = getLong(column).let { if (wasNull()) null else it }

private fun TokenKind.wire() = name.lowercase()

private fun AccountRole.wire() = name.lowercase()
