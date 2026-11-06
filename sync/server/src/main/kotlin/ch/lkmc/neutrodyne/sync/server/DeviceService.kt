// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceDto
import kotlin.time.Instant

internal class DeviceService(
    private val store: AuthStore,
    private val inputs: ServerInputs,
) {
    suspend fun list(principal: AccountPrincipal): List<DeviceDto> =
        store.readAuth {
            requireToken(principal.tokenHash, principal.tokenKind, inputs.wallMs())
            devices(principal.accountId).filter { it.revokedAt == null }.map { it.dto(principal) }
        }

    suspend fun rename(
        principal: AccountPrincipal,
        id: String,
        name: String,
    ): DeviceDto {
        ProtocolValidation.deviceName(name)
        return store.writeAuth {
            requireToken(principal.tokenHash, principal.tokenKind, inputs.wallMs())
            val device = ownDevice(principal, id)
            val updated = device.copy(info = device.info.copy(name = name.trim()))
            save(updated)
            updated.dto(principal)
        }
    }

    suspend fun revoke(
        principal: AccountPrincipal,
        id: String,
    ) {
        val now = inputs.wallMs()
        store.writeAuth {
            requireToken(principal.tokenHash, principal.tokenKind, now)
            val device = ownDevice(principal, id)
            save(device.copy(revokedAt = now))
            tokens(
                principal.accountId,
                TokenKind.DEVICE,
            ).filter { it.deviceId == id && it.active(now) }.forEach { revoke(it, now) }
            emit(ServerEvent.Revoked(principal.accountId, deviceId = id))
            audit(principal.accountId, "device_revoked", now)
        }
    }

    private fun AuthRead.ownDevice(
        principal: AccountPrincipal,
        id: String,
    ): AuthDevice = device(id)?.takeIf { it.accountId == principal.accountId && it.revokedAt == null } ?: missingAuth()

    private fun AuthDevice.dto(principal: AccountPrincipal): DeviceDto =
        DeviceDto(
            info.id,
            info.name,
            info.platform,
            info.appVersion,
            lastSeenAt?.let { Instant.fromEpochMilliseconds(it).toString() },
            info.id == principal.deviceId,
            inputs.wallMs() - (lastSeenAt ?: createdAt) > 180 * DAY_MS,
        )
}
