// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangesPage
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ServerDiscovery.normalise]: only the HTTP(S) scheme is canonicalised. An uppercase or mixed
 * scheme must reach preflight as plain `http` (so it is refused or confirmed), while the authority,
 * path and query keep the exact case typed — a path prefix and a query token are case-sensitive.
 */
class ServerDiscoveryTest {
    private val discovery = ServerDiscovery(UnusedSyncClient())

    @Test
    fun onlyHttpSchemesAreLowercased() {
        assertEquals("http://sync.example", discovery.normalise("HTTP://sync.example"))
        assertEquals("http://sync.example", discovery.normalise("HtTp://sync.example"))
        assertEquals("https://sync.example", discovery.normalise("HTTPS://sync.example"))
        assertEquals("https://sync.example", discovery.normalise("HtTpS://sync.example"))
        assertEquals("http://sync.example", discovery.normalise("http://sync.example"))
        assertEquals("https://sync.example", discovery.normalise("https://sync.example"))
    }

    @Test
    fun authorityPathAndQueryCaseSurvive() {
        assertEquals(
            "http://SYNC.EXAMPLE/Path?Query=Token",
            discovery.normalise("HTTP://SYNC.EXAMPLE/Path?Query=Token"),
        )
        assertEquals(
            "https://SYNC.EXAMPLE/Prefix/Path",
            discovery.normalise("HTTPS://SYNC.EXAMPLE/Prefix/Path"),
        )
    }

    @Test
    fun aMissingSchemeIsHttpsAndSurroundingSpaceIsTrimmed() {
        assertEquals("https://sync.example", discovery.normalise("  sync.example  "))
        assertEquals("https://sync.example/Prefix", discovery.normalise("sync.example/Prefix"))
    }

    @Test
    fun trailingSlashesAreTrimmed() {
        assertEquals("http://sync.example/path", discovery.normalise("HTTP://sync.example/path/"))
        assertEquals("http://sync.example", discovery.normalise("HTTP://sync.example///"))
    }

    @Test
    fun emptyAuthoritiesAreRejected() {
        assertNull(discovery.normalise(""))
        assertNull(discovery.normalise("   "))
        assertNull(discovery.normalise("HTTP://"))
        assertNull(discovery.normalise("https://"))
    }
}

/** [ServerDiscovery.normalise] is pure; no method of this client is ever called. */
private class UnusedSyncClient : SyncClient {
    private fun unused(): Nothing = error("normalise must not call the client")

    override suspend fun discover(baseUrl: String): DiscoveryDocument = unused()

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        device: DeviceInfoDto,
    ): TokenResponse = unused()

    override suspend fun redeemInvite(
        baseUrl: String,
        code: String,
        device: DeviceInfoDto,
    ): TokenResponse = unused()

    override suspend fun linkStart(
        baseUrl: String,
        device: DeviceInfoDto,
    ): LinkStartResponse = unused()

    override suspend fun linkToken(
        baseUrl: String,
        deviceCode: String,
    ): TokenResponse = unused()

    override suspend fun linkApprove(
        baseUrl: String,
        token: String,
        userCode: String,
    ): LinkApproveResponse = unused()

    override suspend fun linkConfirm(
        baseUrl: String,
        token: String,
        userCode: String,
        approve: Boolean,
    ) = unused()

    override suspend fun logout() = unused()

    override suspend fun devices(): List<DeviceDto> = unused()

    override suspend fun renameDevice(
        deviceId: String,
        name: String,
    ): DeviceDto = unused()

    override suspend fun revokeDevice(deviceId: String) = unused()

    override suspend fun account(): AccountSummary = unused()

    override suspend fun sync(
        since: String?,
        changes: List<ChangeDto>,
    ): SyncResponse = unused()

    override suspend fun changes(
        since: String?,
        limit: Int,
    ): ChangesPage = unused()

    override suspend fun reset(
        confirm: String,
        mode: String,
        beforeCursor: String?,
    ): AccountSummary = unused()
}
