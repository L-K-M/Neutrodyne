// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncHeldEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.LocalNetworkGate
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.network.NetErrorClassifier
import ch.lkmc.neutrodyne.sync.api.DiscoveredServer
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.HeldResolution
import ch.lkmc.neutrodyne.sync.api.LinkFlow
import ch.lkmc.neutrodyne.sync.api.LinkMethod
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.PendingDevice
import ch.lkmc.neutrodyne.sync.api.RemoteSessionOffer
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncDevice
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.PROTOCOL_VERSION
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/** Which unauthenticated call a failure came from, so a 401 maps to the right problem. */
internal enum class LinkAuthKind { LOGIN, INVITE, LINK }

/** RFC 8628 poll outcomes the link-token endpoint reports as `400 {"error": …}`. */
private const val AUTHORIZATION_PENDING = "authorization_pending"
private const val SLOW_DOWN = "slow_down"
private const val EXPIRED_TOKEN = "expired_token"
private const val ACCESS_DENIED = "access_denied"

private const val MIN_LINK_INTERVAL_SECONDS = 5
private const val SLOW_DOWN_INCREMENT_SECONDS = 5
private const val UNLINK_PUSH_BUDGET_MS = 5_000L
private const val DELETE_CONFIRMATION = "DELETE"
private const val RESET_MODE_PURGE = "purge"

/** `ImportOptions` enum names a pending restore's options carry (05). */
private const val RESTORE_MODE_REPLACE = "REPLACE"
private const val REACH_ALL_DEVICES = "ALL_DEVICES"

/**
 * The restore-intent fields of `import_session.optionsJson` sync reads (05 Restore while
 * linked): a minimal mirror of `:core:data`'s own JSON, unreachable by module rule; unknown
 * fields are ignored, so a later milestone's options decode unchanged.
 */
@kotlinx.serialization.Serializable
private data class RestoreIntentOptions(
    val restoreMode: String? = null,
    val restoreRequestedAt: Long? = null,
    val linkedReach: String? = null,
)

/** 10 Errors: `lastError` marker the scheduler and status map treat as a revoked link. */
internal const val SYNC_ERROR_REVOKED = "Revoked"

/**
 * The [SyncController] application service (10 Client sync engine, 08 Sync screens). The status is
 * derived from `sync_state` and the secret store; the engine runs the rounds; the link flow is the
 * state machine the setup screen follows, driven here with the real client calls.
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
    private val secrets: ch.lkmc.neutrodyne.core.domain.SecretStore,
    private val clock: Clock,
    private val address: SyncServerAddress,
    private val flags: SyncFlags,
    private val settingsCapture: SettingsCapture,
    private val build: BuildInfo,
    private val merger: FirstLinkMerger,
    private val gate: LocalNetworkGate,
    private val errors: NetErrorClassifier,
    private val scheduler: SyncScheduler,
    private val events: SyncEventsClient,
    private val noticeBus: SyncNoticeBus,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
    @ch.lkmc.neutrodyne.core.common.ApplicationScope private val scope: CoroutineScope,
) : SyncController {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * The state machine plus its cancel: cancelling the flow's job stops the code polling too.
     */
    override val link: LinkFlow? =
        object : LinkFlow {
            override val state: StateFlow<ch.lkmc.neutrodyne.sync.api.LinkState>
                get() = linkFlow.state

            override fun cancel() {
                linkJob?.cancel()
                linkFlow.cancel()
            }
        }

    private val _heldChanges = MutableStateFlow<List<MassChangePrompt>>(emptyList())
    override val heldChanges: StateFlow<List<MassChangePrompt>> = _heldChanges.asStateFlow()

    override val remoteSession = MutableStateFlow<RemoteSessionOffer?>(null)
    private val _syncing = MutableStateFlow(false)
    override val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _notices = MutableSharedFlow<SyncNotice>(extraBufferCapacity = 16)
    override val notices: Flow<SyncNotice> = _notices

    /** The device's "Share feed passwords" switch (10 Settings); the settings screen binds it. */
    private val _shareFeedPasswords = MutableStateFlow(false)
    override val shareFeedPasswords: StateFlow<Boolean> = _shareFeedPasswords.asStateFlow()

    /** The running flow's cancellation handle and the answers the flow waits for. */
    private var linkJob: Job? = null
    private var insecureDecision: CompletableDeferred<Boolean>? = null
    private var permissionDecision: CompletableDeferred<Boolean>? = null

    /** What the current flow knows about the server and this device. */
    private var pendingServer: DiscoveredServer? = null
    private var pendingNodeId: NodeId? = null
    private var pendingDeviceId: String? = null
    private var accountName: String? = null

    /** A non-terminal round problem shown on the status card until the next good round. */
    private val lastRoundProblem = MutableStateFlow<SyncProblem?>(null)

    private val discovery = ServerDiscovery(client)

    init {
        scope.launch { refreshStatus() }
        // The engine's rounds post one-shot notices through the shared bus; relay them into the
        // controller's flow so the shells collect one channel.
        scope.launch { noticeBus.shared.collect { _notices.emit(it) } }
        // 10 Live updates: the status card's activity line follows the round flag and the
        // stream's connection state.
        scope.launch {
            combine(_syncing, events.connected) { _, _ -> }.collect {
                (_status.value as? SyncStatus.Linked)?.let {
                    _status.value = it.copy(activity = activity())
                }
            }
        }
    }

    suspend fun refreshStatus() {
        val url = address.serverUrl()
        val state = db.syncStateDao().get()
        val token = url?.let { tokenStore.load(hostOf(it)) }
        _shareFeedPasswords.value = address.shareFeedPasswords()
        _status.value =
            when {
                url == null || state == null -> {
                    SyncStatus.NotConfigured
                }

                state.lastError == SYNC_ERROR_REVOKED -> {
                    SyncStatus.Revoked(url)
                }

                state.enabled && state.linkedAt != null && token != null -> {
                    SyncStatus.Linked(
                        serverUrl = url,
                        accountId = state.accountId.orEmpty(),
                        deviceId = state.deviceId.orEmpty(),
                        username = address.username(),
                        nodeId = state.nodeId.orEmpty(),
                        lastSyncAtMs = state.lastSyncAt,
                        activity = activity(),
                        problem = lastRoundProblem.value,
                    )
                }

                state.deviceId != null && state.linkedAt == null -> {
                    // 05 Restore while linked: an ALL_DEVICES Replace session pending here is the
                    // resume point, so the status says so instead of offering the link choices.
                    if (pendingAllDevicesRestore()) {
                        SyncStatus.FinishingRestore(url)
                    } else {
                        SyncStatus.LinkUnfinished(url)
                    }
                }

                // A linked database without a token: a copied data directory or a restored install.
                state.deviceId != null || state.serverUrl == null -> {
                    SyncStatus.Reconnect(url, address.username())
                }

                // A deliberate unlink keeps `sync_state.serverUrl`; the library stays as it is.
                else -> {
                    SyncStatus.NotConfigured
                }
            }
        // 10 Settings capture: the recorder follows the committed link and the switch; the
        // persisted switch is the authority, so refresh the cache before deciding.
        flags.syncPlaybackSettings = address.syncPlaybackSettings()
        syncSettingsRecorder(state)
    }

    /** The status card's activity line: a round trumps the stream's connection (08 Sync screens). */
    private fun activity(): SyncActivity =
        when {
            _syncing.value -> SyncActivity.SYNCING
            events.connected.value -> SyncActivity.LIVE
            else -> SyncActivity.IDLE
        }

    /**
     * 10 Settings capture: installs the local synced-key recorder while the link is committed
     * (`enabled`, `linkedAt`, not revoked) and "Sync playback settings" is on; clears it while
     * unlinked or the switch is off. Without this the recorder only exists in tests, so a local
     * edit after the link records no outbox intent in production.
     */
    private fun syncSettingsRecorder(state: SyncStateEntity?) {
        val linked = state != null && state.enabled && state.linkedAt != null && state.lastError != SYNC_ERROR_REVOKED
        if (linked && flags.syncPlaybackSettings) settingsCapture.install() else settingsCapture.clear()
    }

    override fun startLink(
        serverUrl: String,
        method: LinkMethod,
    ) {
        linkJob?.cancel()
        insecureDecision = null
        permissionDecision = null
        pendingNodeId = NodeId.random()
        pendingDeviceId = newDeviceId()
        linkJob =
            scope.launch {
                try {
                    runLink(serverUrl, method)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    linkFlow.failed(classify(failure))
                }
            }
    }

    override fun discover(address: String) {
        linkJob?.cancel()
        insecureDecision = null
        permissionDecision = null
        pendingNodeId = NodeId.random()
        pendingDeviceId = newDeviceId()
        linkJob =
            scope.launch {
                try {
                    linkFlow.discovering()
                    val server = preflight(address) ?: return@launch
                    pendingServer = server
                    linkFlow.awaitingInput(LinkMethod.CODE, server, disclosure(server))
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    linkFlow.failed(classify(failure))
                }
            }
    }

    override fun grantLocalNetwork(granted: Boolean) {
        gate.onPermissionResult(granted)
        permissionDecision?.complete(granted)
    }

    override fun confirmInsecure() {
        insecureDecision?.complete(true)
    }

    override fun submitInvite(code: String) {
        val server = pendingServer ?: return
        scope.launch {
            linkFlow.authorising()
            try {
                val token = client.redeemInvite(server.serverUrl, normaliseUserCode(code), deviceInfo())
                authorised(server, token)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                linkFlow.failed(mapAuthFailure(failure, LinkAuthKind.INVITE))
            }
        }
    }

    override fun submitPassword(
        username: String,
        password: CharArray,
    ) {
        val server = pendingServer ?: return
        scope.launch {
            linkFlow.authorising()
            try {
                val token = client.login(server.serverUrl, username, password.concatToString(), deviceInfo())
                authorised(server, token)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                linkFlow.failed(mapAuthFailure(failure, LinkAuthKind.LOGIN))
            } finally {
                password.fill('\u0000')
            }
        }
    }

    override fun chooseFirstLink(choice: FirstLinkChoice) {
        val state = linkFlow.state.value
        // An empty account offers no choice: only the named upload confirmation can continue it.
        if (state is ch.lkmc.neutrodyne.sync.api.LinkState.ConfirmUpload &&
            choice != FirstLinkChoice.MERGE
        ) {
            return
        }
        if (choice == FirstLinkChoice.USE_DEVICE) {
            // 10 First-link choices: the second confirmation follows the choice's staging pull,
            // so it can name what the other devices will remove; continuing runs the merge.
            if (state is ch.lkmc.neutrodyne.sync.api.LinkState.ConfirmReplaceEverywhere) {
                linkJob = scope.launch { beginMerge(choice) }
                return
            }
            linkJob = scope.launch { stageReplaceEverywhere() }
            return
        }
        linkJob = scope.launch { beginMerge(choice) }
    }

    override fun syncNow() {
        scope.launch {
            _syncing.value = true
            try {
                engine.round(SyncRoundReason.SYNC_NOW)
                lastRoundProblem.value = null
                clearTransientError()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                val problem = classify(failure)
                if (problem == SyncProblem.Revoked) {
                    markRevoked()
                } else {
                    lastRoundProblem.value = problem
                }
            } finally {
                _syncing.value = false
                refreshStatus()
            }
        }
    }

    override suspend fun approveLink(userCode: String): Outcome<PendingDevice, SyncProblem> {
        val (server, token) = linkedContext() ?: return Outcome.Failure(SyncProblem.Revoked)
        return try {
            val response = client.linkApprove(server, token, normaliseUserCode(userCode))
            Outcome.Success(
                PendingDevice(
                    userCode = normaliseUserCode(userCode),
                    deviceName = response.deviceName,
                    platform = response.platform,
                    appVersion = response.appVersion,
                    requestedAtMs = parseEpochMilliseconds(response.requestedAt),
                ),
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            Outcome.Failure(mapAuthFailure(failure, LinkAuthKind.LINK))
        }
    }

    override suspend fun confirmLink(
        userCode: String,
        approve: Boolean,
    ): Outcome<Unit, SyncProblem> {
        val (server, token) = linkedContext() ?: return Outcome.Failure(SyncProblem.Revoked)
        return try {
            client.linkConfirm(server, token, normaliseUserCode(userCode), approve)
            Outcome.Success(Unit)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            Outcome.Failure(mapAuthFailure(failure, LinkAuthKind.LINK))
        }
    }

    override suspend fun devices(): Outcome<List<SyncDevice>, SyncProblem> {
        val (server, token) = linkedContext() ?: return Outcome.Failure(SyncProblem.Revoked)
        return try {
            val current = db.syncStateDao().get()?.deviceId
            Outcome.Success(
                client.devices().map { device ->
                    SyncDevice(
                        id = device.id,
                        name = device.name,
                        platform = device.platform,
                        appVersion = device.appVersion,
                        lastSeenAtMs = device.lastSeenAt?.let(::parseEpochMilliseconds),
                        current = device.id == current,
                        stale = device.stale,
                    )
                },
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            Outcome.Failure(tracked(failure))
        }
    }

    override suspend fun unlink(): Outcome<Unit, SyncProblem> {
        // One push round with a 5-s budget first, so the last edits reach the other devices; an
        // unreachable server must not block the local unlink.
        try {
            withContext(io) {
                withTimeoutOrNull(UNLINK_PUSH_BUDGET_MS) { engine.round(SyncRoundReason.SYNC_NOW) }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            lastRoundProblem.value = classify(failure)
        }
        address.serverUrl()?.let { url -> runCatching { client.logout() } }
        removeProtectedSnapshots()
        db.syncStateDao().unlink()
        address.serverUrl()?.let { tokenStore.clear(hostOf(it)) }
        // 10 Scheduling: unlinking cancels every sync work name before the state refresh.
        scheduler.onUnlinked()
        lastRoundProblem.value = null
        refreshStatus()
        return Outcome.Success(Unit)
    }

    override suspend fun deleteServerData(confirm: String): Outcome<Unit, SyncProblem> {
        if (confirm != DELETE_CONFIRMATION) return Outcome.Failure(SyncProblem.Server(400))
        val (url, token) = linkedContext() ?: return Outcome.Failure(SyncProblem.Revoked)
        return try {
            client.reset(DELETE_CONFIRMATION, RESET_MODE_PURGE, null)
            removeProtectedSnapshots()
            db.syncStateDao().unlink()
            tokenStore.clear(hostOf(url))
            scheduler.onUnlinked()
            lastRoundProblem.value = null
            refreshStatus()
            Outcome.Success(Unit)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            Outcome.Failure(tracked(failure))
        }
    }

    override suspend fun renameDevice(
        deviceId: String,
        name: String,
    ): Outcome<Unit, SyncProblem> {
        val (server, token) = linkedContext() ?: return Outcome.Failure(SyncProblem.Revoked)
        return try {
            client.renameDevice(deviceId, name.trim())
            Outcome.Success(Unit)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            Outcome.Failure(tracked(failure))
        }
    }

    override suspend fun revokeDevice(deviceId: String): Outcome<Unit, SyncProblem> {
        val (server, token) = linkedContext() ?: return Outcome.Failure(SyncProblem.Revoked)
        return try {
            client.revokeDevice(deviceId)
            Outcome.Success(Unit)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            Outcome.Failure(tracked(failure))
        }
    }

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

    override suspend fun setSyncPlaybackSettings(enabled: Boolean) {
        address.setSyncPlaybackSettings(enabled)
        flags.syncPlaybackSettings = enabled
        syncSettingsRecorder(db.syncStateDao().get())
    }

    override suspend fun setShareFeedPasswords(enabled: Boolean) {
        address.setShareFeedPasswords(enabled)
        flags.sharePasswords = enabled
        _shareFeedPasswords.value = enabled
        if (enabled) {
            captureSharedPasswords()
        } else {
            // Turning it off pushes nothing: the pending rows can never leave while off, and a
            // later switch-on re-captures fresh ones (10 Feed passwords).
            db.syncOutboxDao().deleteFieldOf("podcast", "auth")
        }
    }

    /**
     * 10 Feed passwords: turning the switch on pushes `auth` for every podcast with a credential —
     * one capture per podcast, so acknowledged fields from before the switch ride again.
     */
    private suspend fun captureSharedPasswords() {
        if (db.syncStateDao().get()?.enabled != true) return
        for (syncId in db.podcastDao().syncIdsWithCredentials()) {
            db.withWriteTransaction { db.syncOutboxDao().captureIntent("podcast", syncId, "auth", "null") }
        }
    }

    private suspend fun runLink(
        serverUrl: String,
        method: LinkMethod,
    ) {
        linkFlow.discovering()
        val server = preflight(serverUrl) ?: return
        pendingServer = server
        linkFlow.awaitingInput(method, server, disclosure(server))
        when (method) {
            LinkMethod.CODE -> runDeviceCode(server)
            LinkMethod.INVITE, LinkMethod.PASSWORD -> Unit
        }
    }

    /**
     * Normalises and checks the typed address: a plain `http://` to a public address is refused
     * before any request, a local one asks for confirmation, and Android 17 gates local networks
     * behind `ACCESS_LOCAL_NETWORK`.
     */
    private suspend fun preflight(address: String): DiscoveredServer? {
        val normalized =
            discovery.normalise(address) ?: run {
                linkFlow.failed(SyncProblem.Server(400))
                return null
            }
        val host = hostOf(normalized)
        if (normalized.startsWith("http://") && !gate.allowsPlainHttp(host)) {
            linkFlow.failed(SyncProblem.InsecureTransport)
            return null
        }
        val discovered =
            when (val result = discovery.discover(normalized)) {
                is DiscoveryResult.Failure -> {
                    linkFlow.failed(result.problem)
                    return null
                }

                is DiscoveryResult.Success -> {
                    result.server
                }
            }
        if (discovered.serverUrl.startsWith("http://")) {
            val confirmed = awaitInsecure(host)
            if (!confirmed) {
                linkFlow.showAddress()
                return null
            }
        }
        if (gate.needsPermission(host)) {
            val granted = awaitPermission(host)
            if (!granted) {
                linkFlow.failed(SyncProblem.LocalNetworkPermissionDenied)
                return null
            }
        }
        return discovered
    }

    private suspend fun runDeviceCode(server: DiscoveredServer) {
        val start =
            try {
                client.linkStart(server.serverUrl, deviceInfo())
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                linkFlow.failed(mapAuthFailure(failure, LinkAuthKind.LINK))
                return
            }
        linkFlow.showCode(start.userCode, start.verificationUri, clock.now() + start.expiresIn * 1_000L)
        var intervalSeconds = start.interval.coerceAtLeast(MIN_LINK_INTERVAL_SECONDS)
        while (true) {
            delay(intervalSeconds * 1_000L)
            try {
                val token = client.linkToken(server.serverUrl, start.deviceCode)
                linkFlow.authorising()
                authorised(server, token)
                return
            } catch (failure: SyncProblemException) {
                when (failure.error) {
                    AUTHORIZATION_PENDING -> {
                        continue
                    }

                    SLOW_DOWN -> {
                        intervalSeconds += SLOW_DOWN_INCREMENT_SECONDS
                    }

                    EXPIRED_TOKEN -> {
                        linkFlow.failed(SyncProblem.InvalidCode)
                        return
                    }

                    ACCESS_DENIED -> {
                        linkFlow.failed(SyncProblem.LinkDeclined)
                        return
                    }

                    else -> {
                        linkFlow.failed(SyncProblem.Server(400))
                        return
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                linkFlow.failed(classify(failure))
                return
            }
        }
    }

    /** Stores the token and the link identity, then decides the first-link step (10 First-link choices). */
    private suspend fun authorised(
        server: DiscoveredServer,
        token: TokenResponse,
    ) {
        tokenStore.save(hostOf(server.serverUrl), token.token)
        address.setServerUrl(server.serverUrl)
        token.username.takeIf { it.isNotBlank() }?.let { address.setUsername(it) }
        val nodeId = checkNotNull(pendingNodeId)
        val deviceId = checkNotNull(pendingDeviceId)
        db.withWriteTransaction {
            val existing = db.syncStateDao().get()
            db.syncStateDao().upsert(
                (existing ?: SyncStateEntity()).copy(
                    enabled = false,
                    applying = false,
                    serverUrl = server.serverUrl,
                    accountId = token.accountId,
                    deviceId = deviceId,
                    nodeId = nodeId.value,
                    protocol = PROTOCOL_VERSION,
                    cursor = null,
                    hlc = existing?.hlc ?: 0,
                    linkedAt = null,
                    lastSyncAt = null,
                    lastError = null,
                ),
            )
        }
        lastRoundProblem.value = null
        accountName = token.username
        refreshStatus()

        val summary = client.account()
        accountName = summary.username
        val localPodcasts = db.podcastDao().allForExport().size
        val localGroups = db.groupDao().groupsInOrder().size
        when {
            summary.counts.podcasts == 0 && summary.counts.groups == 0 -> {
                if (localPodcasts == 0 && localGroups == 0) {
                    completeEmptyLink()
                } else {
                    linkFlow.confirmUpload(summary.username, hostOf(server.serverUrl))
                }
            }

            localPodcasts == 0 && localGroups == 0 -> {
                beginMerge(FirstLinkChoice.USE_SERVER)
            }

            else -> {
                linkFlow.chooseFirstLink(localPodcasts, localGroups, summary.counts.podcasts, summary.counts.groups)
            }
        }
    }

    /** Nothing to combine: the link completes without a merge (both libraries are empty). */
    private suspend fun completeEmptyLink() {
        db.withWriteTransaction {
            db.syncStateDao().get()?.let { db.syncStateDao().upsert(it.copy(enabled = true, linkedAt = clock.now())) }
        }
        linkFlow.linked(accountName.orEmpty(), deviceName())
        scheduler.onLinked()
        refreshStatus()
    }

    private suspend fun beginMerge(choice: FirstLinkChoice) {
        _syncing.value = true
        try {
            linkFlow.merging(ch.lkmc.neutrodyne.sync.api.LinkPhase.DOWNLOADING, 0, 1)
            when (
                val result =
                    merger.merge(choice) { phase, done, total -> linkFlow.merging(phase, done, total) }
            ) {
                is FirstLinkResult.Completed -> {
                    linkFlow.linked(accountName.orEmpty(), deviceName())
                    scheduler.onLinked()
                    refreshStatus()
                }

                is FirstLinkResult.Failed -> {
                    linkFlow.failed(SyncProblem.MergeFailed)
                    refreshStatus()
                }
            }
        } finally {
            _syncing.value = false
        }
    }

    /**
     * 10 "Use this device's library everywhere": the staging pull whose snapshot feeds the
     * second confirmation. The staged file survives until the confirmed merge completes, so
     * `useDeviceEverywhere` resumes it instead of pulling again.
     */
    private suspend fun stageReplaceEverywhere() {
        _syncing.value = true
        try {
            linkFlow.merging(ch.lkmc.neutrodyne.sync.api.LinkPhase.DOWNLOADING, 0, 1)
            try {
                val (podcasts, groups) = merger.stageResetEffect()
                linkFlow.confirmReplaceEverywhere(podcasts, groups)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                linkFlow.failed(classify(failure))
            }
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun disclosure(server: DiscoveredServer): ch.lkmc.neutrodyne.sync.api.SyncDisclosure =
        ch.lkmc.neutrodyne.sync.api.SyncDisclosure(
            host = hostOf(server.serverUrl),
            privateFeedLinks =
                db
                    .podcastDao()
                    .allForExport()
                    .count { it.needsCredentials || it.credentialId != null },
            encrypted = server.serverUrl.startsWith("https://"),
        )

    private suspend fun deviceInfo(): DeviceInfoDto {
        val nodeId = checkNotNull(pendingNodeId) { "no link in progress" }
        val deviceId = checkNotNull(pendingDeviceId) { "no link in progress" }
        val name =
            (address.deviceName()?.takeIf { it.isNotBlank() } ?: defaultDeviceName())
                .trim()
                .take(ProtocolLimits.MAX_DEVICE_NAME)
        return DeviceInfoDto(
            id = deviceId,
            name = name,
            platform = platformWire(),
            appVersion = build.versionName,
            nodeId = nodeId.value,
        )
    }

    private fun defaultDeviceName(): String =
        when (build.platform) {
            BuildInfo.Platform.ANDROID -> {
                "Android"
            }

            BuildInfo.Platform.DESKTOP -> {
                when (build.desktop?.os) {
                    DesktopOs.WINDOWS -> "Windows"
                    DesktopOs.MACOS -> "macOS"
                    DesktopOs.LINUX -> "Linux"
                    null -> "Desktop"
                }
            }
        }

    private fun platformWire(): String =
        when (build.platform) {
            BuildInfo.Platform.ANDROID -> "android"
            BuildInfo.Platform.DESKTOP -> build.desktop?.os?.wire ?: "linux"
        }

    private suspend fun deviceName(): String = address.deviceName().orEmpty()

    /**
     * Waits for the Android 17 local-network answer. The deferred exists before the state is
     * published, so a fast grant cannot arrive before the flow starts waiting.
     */
    private suspend fun awaitInsecure(host: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        insecureDecision = deferred
        linkFlow.insecure(host)
        return try {
            deferred.await()
        } finally {
            insecureDecision = null
        }
    }

    /** Waits for the local-network permission answer; see [awaitInsecure]. */
    private suspend fun awaitPermission(host: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        permissionDecision = deferred
        linkFlow.needsLocalNetwork(host)
        return try {
            deferred.await()
        } finally {
            permissionDecision = null
        }
    }

    /** The linked server's URL and device token, or `null` when this device cannot act. */
    private suspend fun linkedContext(): Pair<String, String>? {
        val url = address.serverUrl() ?: return null
        val state = db.syncStateDao().get()
        val token = tokenStore.load(hostOf(url))
        return if (state?.enabled == true && state.linkedAt != null && token != null) url to token else null
    }

    /**
     * 10 Durable apply effects: unlink cancels the drain and removes the account's protected
     * snapshots before the bookkeeping that references them is cleared.
     */
    private suspend fun removeProtectedSnapshots() {
        val account = db.syncStateDao().get()?.accountId ?: return
        if (account.isBlank()) return
        for (origin in secrets.originsWithPrefix("$PENDING_ORIGIN_PREFIX$account:")) {
            secrets.remove(origin)
        }
    }

    /** Maps a transport failure and remembers a revocation. */
    private suspend fun tracked(failure: Throwable): SyncProblem {
        val problem = classify(failure)
        if (problem == SyncProblem.Revoked) markRevoked()
        return problem
    }

    private suspend fun markRevoked() {
        db.syncStateDao().get()?.let { db.syncStateDao().upsert(it.copy(lastError = SYNC_ERROR_REVOKED)) }
        // 10 Scheduling: a revoked token cancels every sync work name, as an unlink does.
        scheduler.onUnlinked()
        refreshStatus()
    }

    private suspend fun clearTransientError() {
        val state = db.syncStateDao().get() ?: return
        if (state.lastError != null && state.lastError != SYNC_ERROR_REVOKED) {
            db.syncStateDao().upsert(state.copy(lastError = null))
        }
    }

    /** Transport failure → [SyncProblem]; expected failures never throw (10 Errors). */
    private fun classify(failure: Throwable): SyncProblem {
        if (failure is SyncTransportException) {
            return when (failure.status) {
                401 -> SyncProblem.Revoked
                403, 404, 408, 410, 413, 421, 429 -> SyncProblem.Server(failure.status)
                426 -> SyncProblem.AppTooOld
                in 500..599 -> SyncProblem.Unreachable("server_${failure.status}")
                else -> SyncProblem.Server(failure.status)
            }
        }
        if (failure is SyncProblemException) return SyncProblem.Server(400)
        val netError =
            try {
                errors.classify(failure)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                NetError.Other(failure::class.simpleName ?: "other")
            }
        return when (netError) {
            NetError.Offline -> SyncProblem.Offline
            NetError.Timeout -> SyncProblem.Unreachable("timeout")
            NetError.DnsFailure -> SyncProblem.Unreachable("dns")
            NetError.ConnectionFailed -> SyncProblem.Unreachable("connection_failed")
            NetError.LocalNetworkUnsupported -> SyncProblem.LocalNetworkPermissionDenied
            is NetError.Tls -> SyncProblem.CertificateRejected
            NetError.Cancelled -> SyncProblem.Unreachable("cancelled")
            is NetError.Other -> SyncProblem.Unreachable(netError.type)
        }
    }

    /** A 401 means different things on the three unauthenticated entry points (10 Errors). */
    private fun mapAuthFailure(
        failure: Throwable,
        kind: LinkAuthKind,
    ): SyncProblem {
        if (failure is SyncTransportException) {
            return when (failure.status) {
                401 -> {
                    when (kind) {
                        LinkAuthKind.LOGIN -> SyncProblem.WrongPassword
                        LinkAuthKind.INVITE -> SyncProblem.InviteInvalid
                        LinkAuthKind.LINK -> SyncProblem.InvalidCode
                    }
                }

                426 -> {
                    SyncProblem.AppTooOld
                }

                else -> {
                    classify(failure)
                }
            }
        }
        return classify(failure)
    }

    /**
     * 05 Restore while linked: a `PREVIEW` backup session with an ALL_DEVICES Replace request
     * (`restoreRequestedAt` present, `linkedReach` ALL_DEVICES). The options mirror decodes
     * only the three fields sync needs — `:core:data`'s own mirror is out of reach by module
     * rule, and unknown fields stay ignored.
     */
    private suspend fun pendingAllDevicesRestore(): Boolean {
        for (session in db.importDao().backupPreviewSessions()) {
            val encoded = session.optionsJson ?: continue
            val options =
                runCatching {
                    SyncJson.decodeFromString<RestoreIntentOptions>(encoded)
                }.getOrNull() ?: continue
            if (options.restoreRequestedAt != null && options.restoreMode == RESTORE_MODE_REPLACE &&
                options.linkedReach == REACH_ALL_DEVICES
            ) {
                return true
            }
        }
        return false
    }

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
                    groupNames =
                        summary?.get("groups")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                )
            }
    }
}

/** `spaces and hyphens ignored, case-insensitive`, as the server normalises codes (10 Link methods). */
internal fun normaliseUserCode(code: String): String = code.filterNot { it == ' ' || it == '-' }.uppercase()

/** A fresh UUIDv4-shaped device ID for one link (10 Authentication and device linking). */
internal fun newDeviceId(): String {
    val bytes = ByteArray(16)
    Random.nextBytes(bytes)
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}

private fun parseEpochMilliseconds(iso: String): Long =
    runCatching {
        kotlin.time.Instant
            .parse(iso)
            .toEpochMilliseconds()
    }.getOrDefault(0L)
