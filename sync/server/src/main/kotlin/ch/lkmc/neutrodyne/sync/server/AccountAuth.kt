// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse

internal class AuthThrottled(
    val retrySeconds: Long,
) : RuntimeException("rate_limited")

/** Bounded, in-memory failed-login buckets. No sleeping request retains a hash permit.
 * After an hour's burst the delay remains until success or 24 hours without a failure.
 */
internal class LoginAttempts(
    private val inputs: ServerInputs,
) {
    private data class Failure(
        val count: Int,
        val first: Long,
        val last: Long,
        val next: Long,
    )

    private val failures = linkedMapOf<String, Failure>()

    @Synchronized fun check(username: String) {
        val now = inputs.wallMs()
        failures.entries.removeAll { now - it.value.last >= 24 * HOUR_MS }
        val failure = failures[username]
        if (failure != null && failure.next > now) throw AuthThrottled((failure.next - now + 999) / 1000)
        if (failure == null && failures.size >= MAX_BUCKETS) throw AuthThrottled(60)
    }

    @Synchronized fun failed(username: String) {
        val now = inputs.wallMs()
        val old = failures[username]
        if (old == null && failures.size >= MAX_BUCKETS) throw AuthThrottled(60)
        val reset = old == null || (old.count < FAILURE_LIMIT && now - old.first >= HOUR_MS)
        val count = if (reset) 1 else checkNotNull(old).count + 1
        val delay =
            if (count < FAILURE_LIMIT) {
                0L
            } else {
                (60_000L shl (count - FAILURE_LIMIT).coerceAtMost(6)).coerceAtMost(HOUR_MS)
            }
        failures[username] = Failure(count, if (reset) now else checkNotNull(old).first, now, now + delay)
    }

    @Synchronized fun succeeded(username: String) {
        failures.remove(username)
    }

    private companion object {
        const val FAILURE_LIMIT = 10
        const val MAX_BUCKETS = 10_000
    }
}

internal class AccountAuth(
    private val store: AuthStore,
    private val inputs: ServerInputs,
    private val passwords: PasswordHasher,
    private val setup: SetupService,
    private val defaultQuota: Long = ServerDefaults.QUOTA_RECORDS,
    private val attempts: LoginAttempts = LoginAttempts(inputs),
) {
    private val secrets = AuthSecrets(inputs)

    suspend fun login(
        username: String,
        password: String,
        device: DeviceInfoDto,
        ip: String? = null,
    ): TokenResponse {
        val account = verifyPassword(username, password, ip)
        val raw = secrets.secret("nds_")
        return store.writeAuth {
            val current = account(account.id) ?: unauthorized()
            // Hashing must not hold the writer; reject a concurrent password change/disable.
            if (current.passwordHash != account.passwordHash) unauthorized()
            val result = issueDevice(current, device, raw, inputs.wallMs())
            audit(current.id, "login", inputs.wallMs(), ip)
            result
        }
    }

    suspend fun loginWeb(
        username: String,
        password: String,
        label: String?,
        ip: String? = null,
    ): String {
        val account = verifyPassword(username, password, ip)
        val raw = secrets.secret("ndw_")
        return store.writeAuth {
            val current = account(account.id) ?: unauthorized()
            if (current.passwordHash != account.passwordHash) unauthorized()
            issueWeb(
                current,
                raw,
                inputs.wallMs(),
                label?.take(64),
            ).also { audit(current.id, "login", inputs.wallMs(), ip) }
        }
    }

    private suspend fun verifyPassword(
        username: String,
        password: String,
        ip: String?,
    ): AuthAccount {
        // Invalid names use a single anonymous bucket and still do dummy password work.
        val name = runCatching { AuthSecrets.username(username) }.getOrDefault("")
        attempts.check(name)
        val account = store.readAuth { account(name) }
        val matches = passwords.verify(password, account?.passwordHash)
        if (!matches || account == null) {
            attempts.failed(name)
            store.writeAuth { audit(account?.id, "login_failed", inputs.wallMs(), ip) }
            unauthorized()
        }
        if (account.disabledAt != null) forbidden()
        attempts.succeeded(name)
        return account
    }

    suspend fun changePassword(
        principal: AccountPrincipal,
        currentPassword: String?,
        newPassword: String,
    ) {
        val account =
            store.readAuth {
                requireToken(principal.tokenHash, principal.tokenKind, inputs.wallMs())
                checkNotNull(account(principal.accountId))
            }
        if (account.passwordHash != null &&
            !passwords.verify(currentPassword.orEmpty(), account.passwordHash)
        ) {
            unauthorized()
        }
        val hash = passwords.hash(newPassword)
        val now = inputs.wallMs()
        store.writeAuth {
            requireToken(principal.tokenHash, principal.tokenKind, now)
            val current = checkNotNull(account(principal.accountId))
            if (current.passwordHash != account.passwordHash) unauthorized()
            save(current.copy(passwordHash = hash, passwordSetAt = current.passwordSetAt ?: now))
            tokens(current.id, TokenKind.WEB_SESSION)
                .filter {
                    it.hash != principal.tokenHash && it.active(now)
                }.forEach { revoke(it, now) }
            audit(current.id, "password_changed", now)
        }
    }

    /** Web callers pass their actor for a transactional recheck; CLI callers hold the OS lease. */
    suspend fun createAccount(
        username: String,
        role: AccountRole,
        password: String? = null,
        quota: Long = defaultQuota,
        actor: AccountPrincipal? = null,
    ): AuthAccount {
        val name = AuthSecrets.username(username)
        val hash = password?.let { passwords.hash(it) }
        val epoch = secrets.epoch()
        val account =
            store.writeAuth {
                actor?.let { requireWebAdmin(it, inputs.wallMs()) }
                if (account(name) != null) invalidAuth()
                createAccount(
                    name,
                    role,
                    hash,
                    epoch,
                    inputs.wallMs(),
                    quota,
                ).also { audit(it.id, "account_created", inputs.wallMs()) }
            }
        if (role == AccountRole.ADMIN) setup.initialize()
        return account
    }

    suspend fun disable(accountId: Long) {
        val now = inputs.wallMs()
        store.writeAuth {
            val account = account(accountId) ?: missingAuth()
            save(account.copy(disabledAt = now))
            for (kind in TokenKind.entries) {
                tokens(accountId, kind).filter { it.active(now) }.forEach { revoke(it, now) }
            }
            emit(ServerEvent.Revoked(accountId))
            audit(accountId, "account_disabled", now)
        }
    }
}

internal class InviteService(
    private val store: AuthStore,
    private val inputs: ServerInputs,
) {
    private val secrets = AuthSecrets(inputs)

    suspend fun create(
        accountId: Long,
        actor: AccountPrincipal? = null,
    ): String {
        val code = secrets.code(16)
        val now = inputs.wallMs()
        store.writeAuth {
            actor?.let { requireWebAdmin(it, now) }
            val account = account(accountId) ?: missingAuth()
            if (account.disabledAt != null) forbidden()
            save(AuthToken(AuthSecrets.hash(code), accountId, null, TokenKind.INVITE, now, expiresAt = now + DAY_MS))
            audit(accountId, "invite_created", now)
        }
        return AuthSecrets.display(code)
    }

    suspend fun redeem(
        code: String,
        device: DeviceInfoDto,
    ): TokenResponse {
        val hash = AuthSecrets.hash(AuthSecrets.normalizeCode(code))
        val raw = secrets.secret("nds_")
        val now = inputs.wallMs()
        return store.writeAuth {
            val token = token(hash) ?: unauthorized()
            if (!AuthSecrets.same(hash, token.hash) || token.kind != TokenKind.INVITE || !token.active(now)) {
                unauthorized()
            }
            val account = account(checkNotNull(token.accountId)) ?: unauthorized()
            val response = issueDevice(account, device, raw, now)
            save(token.copy(consumedAt = now))
            audit(account.id, "invite_redeemed", now)
            response
        }
    }
}
