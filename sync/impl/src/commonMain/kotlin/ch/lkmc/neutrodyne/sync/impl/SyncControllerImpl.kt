// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncHeldEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.HeldResolution
import ch.lkmc.neutrodyne.sync.api.LinkFlow
import ch.lkmc.neutrodyne.sync.api.LinkMethod
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.RemoteSessionOffer
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncDevice
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceRenameRequest
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The [SyncController] application service (10 Client sync engine, 08 Sync screens). The status is
 * derived from `sync_state` and the secret store; the engine runs the rounds; the link flow is the
 * state machine the setup screen follows.
 */
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class SyncControllerImpl(
    private val db: NeutrodyneDatabase,
    private val client: SyncClient,
    private val engine: SyncEngine,
    private val linkFlow: LinkFlowImpl,
    private val tokenStore: SyncTokenStore,
    private val clock: Clock,
    private val secrets: SecretStore,
    private val settings: SettingsSyncPort,
    private val playback: PlaybackSyncPort,
    private val syncIngestHook: SyncIngestHook,
    private val channelSync: GroupChannelSync,
    private val address: ch.lkmc.neutrodyne.core.domain.SyncServerAddress,
    private val flags: SyncFlags,
    @ch.lkmc.neutrodyne.core.common.ApplicationScope private val scope: kotlinx.coroutines.CoroutineScope,
) : SyncController {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    override val link: LinkFlow? = linkFlow

    private val _heldChanges = MutableStateFlow<List<MassChangePrompt>>(emptyList())
    override val heldChanges: StateFlow<List<MassChangePrompt>> = _heldChanges.asStateFlow()

    override val remoteSession = MutableStateFlow<RemoteSessionOffer?>(null)
    private val _syncing = MutableStateFlow(false)
    override val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _notices = MutableSharedFlow<SyncNotice>(extraBufferCapacity = 16)
    override val notices: Flow<SyncNotice> = _notices

    init {
        scope.launch { refreshStatus() }
    }

    suspend fun refreshStatus() {
        val state = db.syncStateDao().get()
        _status.value =
            when {
                state == null || address.serverUrl() == null -> {
                    SyncStatus.NotConfigured
                }

                state.linkedAt == null && state.deviceId != null -> {
                    SyncStatus.LinkUnfinished(address.serverUrl().orEmpty())
                }

                state.enabled && state.linkedAt != null -> {
                    SyncStatus.Linked(
                        serverUrl = address.serverUrl().orEmpty(),
                        accountId = state.accountId.orEmpty(),
                        deviceId = state.deviceId.orEmpty(),
                        username = null,
                        nodeId = state.nodeId.orEmpty(),
                        lastSyncAtMs = state.lastSyncAt,
                        problem = null,
                    )
                }

                else -> {
                    SyncStatus.NotConfigured
                }
            }
    }

    override fun startLink(
        serverUrl: String,
        method: LinkMethod,
    ) {
        linkFlow.discovering()
    }

    override fun discover(address: String) {
        linkFlow.discovering()
    }

    override fun grantLocalNetwork(granted: Boolean) = Unit

    override fun confirmInsecure() = Unit

    override fun submitInvite(code: String) = Unit

    override fun submitPassword(
        username: String,
        password: CharArray,
    ) = Unit

    override fun chooseFirstLink(choice: FirstLinkChoice) = Unit

    override fun syncNow() {
        scope.launch {
            _syncing.value = true
            try {
                engine.round(SyncRoundReason.SYNC_NOW)
            } finally {
                _syncing.value = false
                refreshStatus()
            }
        }
    }

    override fun unlink() {
        scope.launch {
            db.syncStateDao().unlink()
            tokenStore.clear(hostOf(address.serverUrl().orEmpty()))
            refreshStatus()
        }
    }

    override fun deleteServerData(confirm: String) = Unit

    override fun renameDevice(
        deviceId: String,
        name: String,
    ) = Unit

    override fun revokeDevice(deviceId: String) = Unit

    override fun resolveHeld(
        id: Long,
        resolution: HeldResolution,
    ) {
        scope.launch {
            val row = db.syncHeldDao().byId(id) ?: return@launch
            val batch = runCatching { SyncJson.decodeFromString<HeldBatch>(row.batch) }.getOrNull() ?: return@launch
            when (resolution) {
                HeldResolution.APPLY -> {
                    db.withWriteTransaction<Unit> {
                        db.syncStateDao().withApplying<Unit> {
                            MassChangeGuard.apply(db, batch, clock.now())
                        }
                    }
                }

                HeldResolution.KEEP_MINE -> {
                    db.withWriteTransaction<Unit> {
                        db.syncStateDao().withApplying<Unit> {
                            MassChangeGuard.keepMine(db, batch, clock)
                        }
                    }
                }

                HeldResolution.DECIDE_LATER -> {
                    return@launch
                }
            }
            db.syncHeldDao().delete(id)
            refreshHeld()
        }
    }

    override fun dismissRemoteSession() {
        remoteSession.value = null
    }

    override fun setSyncPlaybackSettings(enabled: Boolean) = Unit

    override fun setShareFeedPasswords(enabled: Boolean) = Unit

    private suspend fun refreshHeld() {
        _heldChanges.value =
            db.syncHeldDao().all().mapNotNull { row ->
                val batch =
                    runCatching { SyncJson.decodeFromString<HeldBatch>(row.batch) }.getOrNull()
                        ?: return@mapNotNull null
                val summary =
                    runCatching { SyncJson.parseToJsonElement(row.summary).jsonObject }.getOrNull()
                MassChangePrompt(
                    id = row.id,
                    deviceName = null,
                    podcastTitles =
                        summary?.get("podcasts")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    groupNames = summary?.get("groups")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                )
            }
    }
}
