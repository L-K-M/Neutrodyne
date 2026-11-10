// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeLibraryMerger
import ch.lkmc.neutrodyne.core.testing.FakeLocalNetworkGate
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
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
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real [LinkedRestoreCoordinatorImpl.actualLinkedHost] over a Room database, a scripted
 * [FakeSyncServerAddress] and a [SyncTokenStore]: the eligibility answer must match the
 * controller's `Linked` predicate, so a stale `linkedAt` (copied database, revoked token,
 * disabled switch, missing token or unconfigured server) never claims a live link.
 */
class LinkedRestoreCoordinatorImplTest {
    private val harness = Harness()

    @AfterTest
    fun close() {
        harness.close()
    }

    @Test
    fun validLinkReturnsTheHost() =
        runTest {
            harness.address.updateServerUrl("https://sync.example")
            harness.saveToken("sync.example")
            harness.seedLinked()

            assertEquals(Outcome.Success("sync.example"), harness.coordinator.actualLinkedHost())
        }

    @Test
    fun missingTokenIsNotLinked() =
        runTest {
            harness.address.updateServerUrl("https://sync.example")
            harness.seedLinked()

            assertEquals(Outcome.Success(null), harness.coordinator.actualLinkedHost())
        }

    @Test
    fun revokedIsNotLinked() =
        runTest {
            harness.address.updateServerUrl("https://sync.example")
            harness.saveToken("sync.example")
            harness.seedLinked(enabled = true, lastError = SYNC_ERROR_REVOKED)
            harness.secrets.failure = IllegalStateException("locked")

            assertEquals(Outcome.Success(null), harness.coordinator.actualLinkedHost())
        }

    @Test
    fun disabledIsNotLinked() =
        runTest {
            harness.address.updateServerUrl("https://sync.example")
            harness.saveToken("sync.example")
            harness.seedLinked(enabled = false)
            harness.secrets.failure = IllegalStateException("locked")

            assertEquals(Outcome.Success(null), harness.coordinator.actualLinkedHost())
        }

    @Test
    fun unconfiguredServerIsNotLinked() =
        runTest {
            harness.address.updateServerUrl(null)
            harness.seedLinked()

            assertEquals(Outcome.Success(null), harness.coordinator.actualLinkedHost())
        }

    @Test
    fun unfinishedLinkIsNotLinked() =
        runTest {
            harness.address.updateServerUrl("https://sync.example")
            harness.saveToken("sync.example")
            harness.seedLinked(linkedAt = null)
            harness.secrets.failure = IllegalStateException("locked")

            assertEquals(Outcome.Success(null), harness.coordinator.actualLinkedHost())
        }

    @Test
    fun thePreviewHostUsesTheNormalizedOriginWithoutCredentials() =
        runTest {
            val url = "https://USER:example-password@SYNC.EXAMPLE:443/Prefix"
            harness.address.updateServerUrl(url)
            harness.saveToken(hostOf(url))
            harness.seedLinked()

            assertEquals(Outcome.Success("sync.example"), harness.coordinator.actualLinkedHost())
        }

    @Test
    fun unreadableTokenIsAnErrorRatherThanAnUnlinkedAnswer() =
        runTest {
            harness.address.updateServerUrl("https://sync.example")
            harness.seedLinked()
            harness.secrets.failure = IllegalStateException("locked")

            assertEquals(Outcome.Failure(BackupError.Io), harness.coordinator.actualLinkedHost())
        }

    private class Harness {
        val db = TestDb.inMemory()
        val clock = TestClock()
        val secrets = FakeSecretStore()
        val settings = FakeSettingsSyncPort()
        val address = FakeSyncServerAddress(serverUrl = null)
        val tokens = SyncTokenStore(secrets)
        val credentials = FakeCredentialCommitCoordinator()
        val activity = SyncActivityState()
        val writeLocks = TestSettingsWriteCoordinator()
        private val root = createTempDirectory("nd-linked-restore")
        val storagePaths =
            StoragePaths(
                AppDirs(
                    data = root.resolve("data"),
                    config = root.resolve("config"),
                    cache = root.resolve("cache"),
                    state = root.resolve("state"),
                    logs = root.resolve("logs"),
                    downloadsDefault = root.resolve("downloads"),
                ),
            )
        val client = StubSyncClient()
        val applier =
            SyncApplier(
                db = db,
                clock = clock,
                secrets = secrets,
                settings = settings,
                playback = NoPlaybackSync,
                syncIngestHook = SyncIngestHook.None,
                channelSync =
                    object : GroupChannelSync {
                        override suspend fun sync() = Unit

                        override suspend fun onUuidChanged(
                            oldUuid: String,
                            newUuid: String,
                        ) = Unit

                        override suspend fun onDeleted(uuid: String) = Unit
                    },
                sharePasswords = { false },
                coordinator = credentials,
                notices = SyncNoticeBus(),
                deviceNames = DeviceNames { emptyList() },
            )
        val effects =
            SyncApplyEffects(
                db,
                settings,
                writeLocks,
                { address.syncPlaybackSettings() },
                secrets,
                credentials,
                storagePaths.cacheDir.toPath(),
            )
        val capture = SettingsCapture(db, settings, writeLocks) { address.syncPlaybackSettings() }
        val engine =
            SyncEngine(
                db = db,
                client = client,
                applier = applier,
                clock = clock,
                secrets = secrets,
                sharePasswords = { false },
                address = address,
                tokens = tokens,
                activity = activity,
                gate = FakeLocalNetworkGate(),
                effects = effects,
                capture = capture,
                notices = SyncNoticeBus(),
            )
        val merger =
            FirstLinkMerger(
                db = db,
                client = client,
                applier = applier,
                engine = engine,
                libraryMerger = FakeLibraryMerger(),
                storagePaths = storagePaths,
                effects = effects,
                clock = clock,
                activity = activity,
                secrets = secrets,
                coordinator = credentials,
            )
        val coordinator =
            LinkedRestoreCoordinatorImpl(
                db = db,
                controller = FakeSyncController(),
                merger = merger,
                address = address,
                tokenStore = tokens,
            )

        fun close() {
            db.close()
            root.toFile().deleteRecursively()
        }

        suspend fun saveToken(host: String) {
            tokens.save(host, "token")
        }

        suspend fun seedLinked(
            enabled: Boolean = true,
            linkedAt: Long? = 1L,
            lastError: String? = null,
        ): SyncStateEntity {
            val state =
                SyncStateEntity(
                    enabled = enabled,
                    serverUrl = "https://sync.example",
                    accountId = "a",
                    deviceId = "d",
                    cursor = "c",
                    nodeId = "5eed5eed5eed5eed",
                    protocol = 1,
                    linkedAt = linkedAt,
                    lastSyncAt = 1L,
                    lastError = lastError,
                )
            db.syncStateDao().upsert(state)
            return state
        }
    }
}

/** Every call is unused by the eligibility read; the constructor only stores it. */
private class StubSyncClient : SyncClient {
    override suspend fun discover(baseUrl: String): DiscoveryDocument = TODO()

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        device: DeviceInfoDto,
    ): TokenResponse = TODO()

    override suspend fun redeemInvite(
        baseUrl: String,
        code: String,
        device: DeviceInfoDto,
    ): TokenResponse = TODO()

    override suspend fun linkStart(
        baseUrl: String,
        device: DeviceInfoDto,
    ): LinkStartResponse = TODO()

    override suspend fun linkToken(
        baseUrl: String,
        deviceCode: String,
    ): TokenResponse = TODO()

    override suspend fun linkApprove(
        baseUrl: String,
        token: String,
        userCode: String,
    ): LinkApproveResponse = TODO()

    override suspend fun linkConfirm(
        baseUrl: String,
        token: String,
        userCode: String,
        approve: Boolean,
    ): Unit = TODO()

    override suspend fun logout(): Unit = TODO()

    override suspend fun devices(): List<DeviceDto> = TODO()

    override suspend fun renameDevice(
        deviceId: String,
        name: String,
    ): DeviceDto = TODO()

    override suspend fun revokeDevice(deviceId: String): Unit = TODO()

    override suspend fun account(): AccountSummary = TODO()

    override suspend fun sync(
        since: String?,
        changes: List<ChangeDto>,
    ): SyncResponse = TODO()

    override suspend fun changes(
        since: String?,
        limit: Int,
    ): ChangesPage = TODO()

    override suspend fun reset(
        confirm: String,
        mode: String,
        beforeCursor: String?,
    ): AccountSummary = TODO()
}
