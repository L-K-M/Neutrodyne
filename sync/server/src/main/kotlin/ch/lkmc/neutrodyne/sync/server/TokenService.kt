// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse

internal class TokenService(
    private val store: AuthStore,
    private val inputs: ServerInputs,
) {
    suspend fun authenticate(
        raw: String,
        kind: TokenKind,
    ): AccountPrincipal {
        if (kind != TokenKind.DEVICE && kind != TokenKind.WEB_SESSION) unauthorized()
        val prefix = if (kind == TokenKind.DEVICE) "nds_" else "ndw_"
        if (!raw.startsWith(prefix) || !raw.substring(prefix.length).matches(Regex("[A-Za-z0-9_-]{43}"))) unauthorized()
        val hash = AuthSecrets.hash(raw)
        val now = inputs.wallMs()
        val (principal, token) = store.readAuth { requireToken(hash, kind, now) to checkNotNull(token(hash)) }
        // Avoid queueing every authenticated read behind the writer just for last_used_at.
        if (token.lastUsedAt == null || now - token.lastUsedAt >= HOUR_MS) {
            store.writeAuth {
                requireToken(hash, kind, now)
                val current = checkNotNull(token(hash))
                if (current.lastUsedAt == null || now - current.lastUsedAt >= HOUR_MS) {
                    val expiry = if (kind == TokenKind.WEB_SESSION) now + SESSION_MS else current.expiresAt
                    save(current.copy(lastUsedAt = now, expiresAt = expiry))
                }
            }
        }
        return principal
    }

    suspend fun valid(principal: AccountPrincipal): Boolean =
        store.readAuth {
            runCatching {
                requireToken(principal.tokenHash, principal.tokenKind, inputs.wallMs())
            }.isSuccess
        }

    suspend fun logout(principal: AccountPrincipal) {
        val now = inputs.wallMs()
        store.writeAuth {
            requireToken(principal.tokenHash, principal.tokenKind, now)
            revoke(checkNotNull(token(principal.tokenHash)), now)
            audit(principal.accountId, "logout", now)
        }
    }

    companion object {
        val SESSION_MS = 30 * DAY_MS
    }
}

internal fun AuthRead.requireToken(
    hash: String,
    kind: TokenKind,
    now: Long,
): AccountPrincipal {
    val token = token(hash) ?: unauthorized()
    if (!AuthSecrets.same(hash, token.hash) || token.kind != kind) unauthorized()
    val account = account(checkNotNull(token.accountId)) ?: unauthorized()
    // Disabled accounts deliberately remain distinguishable from a revoked credential.
    if (account.disabledAt != null) forbidden()
    if (!token.active(now)) unauthorized()
    if (kind == TokenKind.DEVICE) {
        val device = token.deviceId?.let(::device) ?: unauthorized()
        if (device.accountId != account.id || device.revokedAt != null) unauthorized()
    }
    return AccountPrincipal(account.id, account.role, kind, token.hash, token.deviceId)
}

internal fun AuthWrite.issueDevice(
    account: AuthAccount,
    device: DeviceInfoDto,
    raw: String,
    now: Long,
): TokenResponse {
    ProtocolValidation.device(device)
    if (device(device.id) != null) invalidAuth()
    if (account.disabledAt != null) forbidden()
    save(AuthDevice(device.copy(name = device.name.trim()), account.id, now, now))
    save(AuthToken(AuthSecrets.hash(raw), account.id, device.id, TokenKind.DEVICE, now, now))
    return TokenResponse(raw, account.id.toString(), device.id, account.username)
}

internal fun AuthWrite.issueWeb(
    account: AuthAccount,
    raw: String,
    now: Long,
    label: String?,
): String {
    if (account.disabledAt != null) forbidden()
    save(
        AuthToken(
            AuthSecrets.hash(raw),
            account.id,
            null,
            TokenKind.WEB_SESSION,
            now,
            now,
            now + TokenService.SESSION_MS,
            label = label,
        ),
    )
    return raw
}

internal fun AuthWrite.revoke(
    token: AuthToken,
    now: Long,
) {
    save(token.copy(revokedAt = now))
    token.accountId?.let { emit(ServerEvent.Revoked(it, tokenHash = token.hash)) }
}
