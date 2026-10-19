// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.time.Instant

internal sealed interface LinkPoll {
    data class Device(
        val response: TokenResponse,
    ) : LinkPoll

    data class Web(
        val cookie: String,
    ) : LinkPoll

    data class Error(
        val code: String,
    ) : LinkPoll
}

internal class LinkService(
    private val store: AuthStore,
    private val inputs: ServerInputs,
    private val verificationUri: String,
) {
    private val secrets = AuthSecrets(inputs)
    private val approvalMutex = Mutex()
    private val failedApprovals = linkedMapOf<Long, MutableList<Long>>()

    suspend fun start(
        device: DeviceInfoDto,
        ip: String,
    ): LinkStartResponse {
        ProtocolValidation.device(device)
        return start(device.copy(name = device.name.trim()), ip, LinkPurpose.DEVICE)
    }

    /** W8 calls this server-side; a public poller cannot select or switch the purpose. */
    suspend fun startWeb(ip: String): LinkStartResponse =
        start(
            DeviceInfoDto(UUID.randomUUID().toString(), "Web browser", "web", "web", "0000000000000001"),
            ip,
            LinkPurpose.WEB,
        )

    private suspend fun start(
        device: DeviceInfoDto,
        ip: String,
        purpose: LinkPurpose,
    ): LinkStartResponse {
        val now = inputs.wallMs()
        // Retry the unlikely user-code collision without replacing somebody else's request.
        repeat(CODE_ATTEMPTS) {
            val deviceCode = secrets.secret()
            val userCode = secrets.code(8)
            val link =
                AuthLink(
                    AuthSecrets.hash(deviceCode),
                    AuthSecrets.hash(userCode),
                    device,
                    purpose,
                    ip,
                    now,
                    now + LINK_MS,
                )
            val inserted =
                store.writeAuth {
                    if (pendingLinks(now) >= MAX_PENDING) throw AuthThrottled(60)
                    if (linkByUserHash(link.userHash) != null || link(link.deviceHash) != null) return@writeAuth false
                    save(link)
                    true
                }
            if (inserted) return LinkStartResponse(deviceCode, AuthSecrets.display(userCode), verificationUri, 600, 5)
        }
        throw AuthThrottled(60)
    }

    suspend fun preview(
        principal: AccountPrincipal,
        userCode: String,
        ip: String,
    ): LinkApproveResponse =
        approvalMutex.withLock {
            approvalAttempt(principal.accountId)
            val now = inputs.wallMs()
            val hash = AuthSecrets.hash(AuthSecrets.normalizeCode(userCode))
            val result =
                store.writeAuth {
                    requireToken(principal.tokenHash, principal.tokenKind, now)
                    val link = linkByUserHash(hash) ?: return@writeAuth null
                    if (!AuthSecrets.same(hash, link.userHash) || !link.pending(now)) return@writeAuth null
                    val next = link.copy(lookups = link.lookups + 1)
                    // Persist failed lookups too, so a rollback cannot reset the guessing budget.
                    save(next)
                    if (next.lookups >= MAX_LOOKUPS) return@writeAuth null
                    if (link.accountId != null &&
                        (link.accountId != principal.accountId || link.approverHash != principal.tokenHash)
                    ) {
                        return@writeAuth null
                    }
                    save(next.copy(accountId = principal.accountId, approverHash = principal.tokenHash))
                    LinkApproveResponse(
                        link.device.name,
                        link.device.platform,
                        link.device.appVersion,
                        Instant.fromEpochMilliseconds(link.createdAt).toString(),
                        link.purpose.wire,
                        link.startIp == ip,
                    )
                }
            if (result == null) {
                failedApproval(principal.accountId)
                missingAuth()
            }
            result
        }

    suspend fun confirm(
        principal: AccountPrincipal,
        userCode: String,
        approve: Boolean,
    ): Unit =
        approvalMutex.withLock {
            approvalAttempt(principal.accountId)
            val now = inputs.wallMs()
            val hash = AuthSecrets.hash(AuthSecrets.normalizeCode(userCode))
            val confirmed =
                store.writeAuth {
                    requireToken(principal.tokenHash, principal.tokenKind, now)
                    val link = linkByUserHash(hash) ?: return@writeAuth false
                    if (!link.pending(now) || link.accountId != principal.accountId ||
                        link.approverHash != principal.tokenHash
                    ) {
                        return@writeAuth false
                    }
                    save(if (approve) link.copy(approvedAt = now) else link.copy(deniedAt = now))
                    audit(principal.accountId, if (approve) "link_approved" else "link_denied", now)
                    true
                }
            if (!confirmed) {
                failedApproval(principal.accountId)
                missingAuth()
            }
        }

    suspend fun pollDevice(deviceCode: String): LinkPoll = poll(deviceCode, LinkPurpose.DEVICE)

    suspend fun pollWeb(deviceCode: String): LinkPoll = poll(deviceCode, LinkPurpose.WEB)

    private suspend fun poll(
        deviceCode: String,
        purpose: LinkPurpose,
    ): LinkPoll {
        val now = inputs.wallMs()
        val hash = AuthSecrets.hash(deviceCode)
        val raw = secrets.secret(if (purpose == LinkPurpose.DEVICE) "nds_" else "ndw_")
        return store.writeAuth {
            val link = link(hash) ?: return@writeAuth LinkPoll.Error("expired_token")
            if (!AuthSecrets.same(hash, link.deviceHash) || link.purpose != purpose || link.consumedAt != null ||
                now >= link.expiresAt ||
                link.lookups >= MAX_LOOKUPS
            ) {
                return@writeAuth LinkPoll.Error("expired_token")
            }
            if (link.deniedAt != null) return@writeAuth LinkPoll.Error("access_denied")
            val last = link.lastPollAt
            if (last != null && now - last < link.intervalSeconds * 1000L) {
                save(link.copy(lastPollAt = now, intervalSeconds = (link.intervalSeconds + 5).coerceAtMost(600)))
                return@writeAuth LinkPoll.Error("slow_down")
            }
            save(link.copy(lastPollAt = now))
            if (link.approvedAt == null) return@writeAuth LinkPoll.Error("authorization_pending")
            val principal =
                runCatching {
                    requireToken(
                        checkNotNull(link.approverHash),
                        checkNotNull(token(checkNotNull(link.approverHash))).kind,
                        now,
                    )
                }.getOrNull()
            if (principal == null || principal.accountId != link.accountId) {
                save(link.copy(consumedAt = now))
                return@writeAuth LinkPoll.Error("access_denied")
            }
            val account = checkNotNull(account(principal.accountId))
            val result =
                if (purpose == LinkPurpose.DEVICE) {
                    LinkPoll.Device(issueDevice(account, link.device, raw, now))
                } else {
                    LinkPoll.Web(issueWeb(account, raw, now, "Web browser"))
                }
            save(link.copy(lastPollAt = now, consumedAt = now))
            audit(account.id, "link_redeemed", now)
            result
        }
    }

    @Synchronized private fun approvalAttempt(accountId: Long) {
        val now = inputs.wallMs()
        failedApprovals.entries.removeAll { (_, times) ->
            times.removeAll { now - it >= APPROVAL_WINDOW_MS }
            times.isEmpty()
        }
        if (failedApprovals[accountId].orEmpty().size >= MAX_LOOKUPS) throw AuthThrottled(900)
        if (failedApprovals.size >= MAX_PENDING && accountId !in failedApprovals) throw AuthThrottled(900)
    }

    @Synchronized private fun failedApproval(accountId: Long) {
        failedApprovals.getOrPut(accountId) { mutableListOf() }.add(inputs.wallMs())
    }

    private fun AuthLink.pending(now: Long): Boolean =
        now < expiresAt && consumedAt == null && deniedAt == null && approvedAt == null && lookups < MAX_LOOKUPS

    private companion object {
        const val MAX_PENDING = 1000
        const val MAX_LOOKUPS = 5
        const val CODE_ATTEMPTS = 8
        const val LINK_MS = 600_000L
        const val APPROVAL_WINDOW_MS = 900_000L
    }
}
