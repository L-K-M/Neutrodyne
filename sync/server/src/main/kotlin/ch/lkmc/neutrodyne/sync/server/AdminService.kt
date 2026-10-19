// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class AccountListing(
    val account: AuthAccount,
    val devices: Int,
    val lastSeen: Long?,
)

internal data class BrowserSession(
    val id: String,
    val label: String,
    val lastUsed: Long?,
    val current: Boolean,
)

internal data class LibraryPage(
    val titles: List<String>,
    val next: Long?,
)

internal enum class AccountAction { ENABLE, DISABLE, DELETE }

/** Admin entry points use a web credential; CLI callers are authorized by the OS data-directory lease. */
internal class AdminService(
    private val store: AuthStore,
    private val records: SyncStore,
    private val inputs: ServerInputs,
) {
    suspend fun accounts(actor: AccountPrincipal? = null): List<AccountListing> =
        store.readAuth {
            actor?.let { requireWebAdmin(it, inputs.wallMs()) }
            accounts().map { owner ->
                val devices = devices(owner.id)
                val lastSeen =
                    (
                        devices.mapNotNull { it.lastSeenAt } +
                            tokens(owner.id, TokenKind.WEB_SESSION).mapNotNull { it.lastUsedAt }
                    ).maxOrNull()
                AccountListing(owner, devices.count { it.revokedAt == null }, lastSeen)
            }
        }

    suspend fun accountId(username: String): Long =
        store.readAuth {
            account(AuthSecrets.username(username))?.id ?: missingAuth()
        }

    suspend fun change(
        id: Long,
        action: AccountAction,
        confirmation: String = "",
        actor: AccountPrincipal? = null,
    ) {
        val now = inputs.wallMs()
        store.writeAuth {
            actor?.let { requireWebAdmin(it, now) }
            val target = account(id) ?: missingAuth()
            if (action == AccountAction.DELETE && confirmation != target.username) invalidAuth()
            when (action) {
                AccountAction.ENABLE -> save(target.copy(disabledAt = null))
                AccountAction.DISABLE -> save(target.copy(disabledAt = now))
                AccountAction.DELETE -> Unit
            }
            if (action != AccountAction.ENABLE) {
                TokenKind.entries.forEach { kind ->
                    tokens(id, kind).filter { it.active(now) }.forEach { revoke(it, now) }
                }
                emit(ServerEvent.Revoked(id))
            }
            if (action == AccountAction.DELETE) deleteAccount(id)
            audit(id, "admin_" + action.name.lowercase(), now)
        }
    }

    suspend fun resetPasswordInvite(
        actor: AccountPrincipal,
        id: Long,
    ): String {
        val secrets = AuthSecrets(inputs)
        val code = secrets.code(16)
        val now = inputs.wallMs()
        store.writeAuth {
            requireWebAdmin(actor, now)
            val target = account(id) ?: missingAuth()
            if (target.disabledAt != null) forbidden()
            save(target.copy(passwordHash = null, passwordSetAt = null))
            TokenKind.entries.forEach { kind ->
                tokens(id, kind).filter { it.active(now) }.forEach { revoke(it, now) }
            }
            save(AuthToken(AuthSecrets.hash(code), id, null, TokenKind.INVITE, now, expiresAt = now + DAY_MS))
            emit(ServerEvent.Revoked(id))
            audit(id, "admin_reset_password", now)
            audit(id, "invite_created", now)
        }
        return AuthSecrets.display(code)
    }

    suspend fun passwordSetAt(actor: AccountPrincipal): Long? =
        store.readAuth {
            requireToken(actor.tokenHash, TokenKind.WEB_SESSION, inputs.wallMs())
            checkNotNull(account(actor.accountId)).passwordSetAt
        }

    suspend fun sessions(actor: AccountPrincipal): List<BrowserSession> =
        store.readAuth {
            requireToken(actor.tokenHash, TokenKind.WEB_SESSION, inputs.wallMs())
            tokens(actor.accountId, TokenKind.WEB_SESSION).filter { it.active(inputs.wallMs()) }.map {
                BrowserSession(it.hash, it.label ?: "Web browser", it.lastUsedAt, it.hash == actor.tokenHash)
            }
        }

    suspend fun revokeSession(
        actor: AccountPrincipal,
        id: String,
    ) {
        val now = inputs.wallMs()
        store.writeAuth {
            requireToken(actor.tokenHash, TokenKind.WEB_SESSION, now)
            val token =
                token(id)?.takeIf { it.accountId == actor.accountId && it.kind == TokenKind.WEB_SESSION }
                    ?: missingAuth()
            revoke(token, now)
            audit(actor.accountId, "web_session_revoked", now)
        }
    }

    suspend fun library(
        actor: AccountPrincipal,
        after: Long,
    ): LibraryPage {
        if (after < 0) invalidAuth()
        store.readAuth { requireToken(actor.tokenHash, TokenKind.WEB_SESSION, inputs.wallMs()) }
        return records.read(actor.accountId) {
            val rows = page(after, ProtocolLimits.MAX_RECORDS)
            val titles =
                rows
                    .filter {
                        it.deadAtMs == null && it.raw.mergedInto == null &&
                            it.key.collection in setOf(SyncCollection.PODCAST, SyncCollection.GROUP)
                    }.map { row ->
                        val fields = row.raw.fields
                        val names =
                            if (row.key.collection ==
                                SyncCollection.GROUP
                            ) {
                                listOf("name")
                            } else {
                                listOf("customTitle", "title")
                            }
                        val title =
                            names.firstNotNullOfOrNull {
                                (fields[it]?.v as? JsonPrimitive)?.contentOrNull
                            }
                        row.key.collection.wire + ": " + (title ?: "Untitled")
                    }
            LibraryPage(titles, rows.lastOrNull()?.seq?.takeIf { rows.size == ProtocolLimits.MAX_RECORDS })
        }
    }
}

internal fun AuthRead.requireWebAdmin(
    actor: AccountPrincipal,
    now: Long,
) {
    val current = requireToken(actor.tokenHash, TokenKind.WEB_SESSION, now)
    if (current.role != AccountRole.ADMIN) forbidden()
}
