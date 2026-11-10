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
import ch.lkmc.neutrodyne.sync.api.LinkPurpose
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.PendingDevice
import ch.lkmc.neutrodyne.sync.api.RemoteSessionOffer
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncDevice
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import ch.lkmc.neutrodyne.sync.api.SyncPreferenceError
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import ch.lkmc.neutrodyne.sync.api.SyncActivity as SyncLiveActivity

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

/** 10 Errors: the `sync_state.lastError` marker the engine's rounds and the status map treat as a revoked link. */
internal const val SYNC_ERROR_REVOKED = "Revoked"

/**
 * The one "actually linked" predicate (05 Restore while linked, 08 Restore-while-linked
 * prompts): the status's `Linked` branch, the controller's own linked context and the restore
 * coordinator's eligibility read share it, so a stale `linkedAt` (copied database, revoked
 * token, disabled switch or missing token) can never be mistaken for a live link. The caller
 * supplies the token presence and the configured server URL.
 */
internal fun SyncStateEntity?.isActuallyLinked(hasToken: Boolean): Boolean =
    this != null && enabled && linkedAt != null && lastError != SYNC_ERROR_REVOKED && hasToken

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
    private val playback: ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort,
    private val linkFlow: LinkFlowImpl,
    private val tokenStore: SyncTokenStore,
    private val clock: Clock,
    private val address: SyncServerAddress,
    private val flags: SyncFlags,
    private val build: BuildInfo,
    private val merger: FirstLinkMerger,
    private val effects: SyncApplyEffects,
    private val settingsCapture: SettingsCapture,
    private val gate: LocalNetworkGate,
    private val errors: NetErrorClassifier,
    private val scheduler: SyncScheduler,
    private val events: SyncEventsClient,
    private val adopter: SessionAdopter,
    private val activity: SyncActivityState,
    private val noticeBus: SyncNoticeBus,
    private val secrets: ch.lkmc.neutrodyne.core.domain.SecretStore,
    private val credentials: ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator,
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

    override val remoteSession: StateFlow<RemoteSessionOffer?> = adopter.offer
    private val _syncing = MutableStateFlow(false)
    override val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _notices = MutableSharedFlow<SyncNotice>(extraBufferCapacity = 16)

    /** The controller's own notices plus the pull-side ones the engine posts on the bus. */
    override val notices: Flow<SyncNotice> = merge(_notices, noticeBus.notices)

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

    /**
     * Publications are serialized so a refresh that read an older `sync_state` can never
     * publish over a newer status: the reads cross a real dispatcher boundary, and a parked
     * reader resuming after the revocation would otherwise leave the stale status on
     * screen — nothing re-triggers the refresh. Declared before `init`: the refresh `init`
     * launches runs on the injected dispatcher and can start before the constructor returns.
     */
    private val statusMutex = Mutex()

    // Authorization and an admitted disconnect must not replace each other's account or token.
    private val identityMutex = Mutex()

    private data class DisconnectIdentity(
        val serverUrl: String?,
        val accountId: String?,
        val deviceId: String?,
        val nodeId: String?,
        val token: String?,
    ) {
        override fun toString(): String = "DisconnectIdentity(redacted)"
    }

    init {
        scope.launch { refreshStatus() }
        // 10 Live updates: the status card's activity line follows the round counter and the
        // stream's connected flag — refresh whenever the derived value could change.
        scope.launch {
            combine(activity.count, events.connected) { _, _ -> currentActivity() }
                .distinctUntilChanged()
                .collect { refreshStatus() }
        }
        // 10 Mass-change guard: the prompts follow `sync_held`, so a batch the guard holds, a
        // resolution, or any other writer republishes without a manual refresh. STAGED and
        // DEFERRED rows are the engine's bookkeeping and never surface as user prompts.
        scope.launch {
            db.syncHeldDao().observeAll().collect { rows ->
                _heldChanges.value = rows.mapNotNull(::heldPrompt)
            }
        }
    }

    /** 10 Live updates: an in-flight round outranks the stream's LIVE, which outranks IDLE. */
    private fun currentActivity(): SyncLiveActivity =
        when {
            activity.active -> SyncLiveActivity.SYNCING
            events.connected.value -> SyncLiveActivity.LIVE
            else -> SyncLiveActivity.IDLE
        }

    suspend fun refreshStatus() {
        statusMutex.withLock {
            refreshStatusLocked()
        }
    }

    private suspend fun refreshStatusLocked() {
        val url = address.serverUrl()
        val state = db.syncStateDao().get()
        val token = url?.let { tokenStore.load(hostOf(it)) }
        _status.value =
            when {
                url == null || state == null -> {
                    SyncStatus.NotConfigured
                }

                state.lastError == SYNC_ERROR_REVOKED -> {
                    SyncStatus.Revoked(url)
                }

                state.isActuallyLinked(token != null) -> {
                    SyncStatus.Linked(
                        serverUrl = url,
                        accountId = state.accountId.orEmpty(),
                        deviceId = state.deviceId.orEmpty(),
                        username = address.username(),
                        nodeId = state.nodeId.orEmpty(),
                        lastSyncAtMs = state.lastSyncAt,
                        activity = currentActivity(),
                        problem = lastRoundProblem.value,
                    )
                }

                state.deviceId != null && state.linkedAt == null -> {
                    SyncStatus.LinkUnfinished(url)
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
        val nodeId = pendingNodeId ?: return
        val deviceId = pendingDeviceId ?: return
        // The auth runs as the tracked link job: cancel/supersede ends it before a stale
        // response can write this or the next session's token and state.
        linkJob?.cancel()
        linkJob =
            scope.launch {
                linkFlow.authorising()
                try {
                    val token = client.redeemInvite(server.serverUrl, normaliseUserCode(code), deviceInfo())
                    authorised(server, token, nodeId, deviceId)
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
        val nodeId = pendingNodeId ?: return
        val deviceId = pendingDeviceId ?: return
        linkJob?.cancel()
        linkJob =
            scope.launch {
                linkFlow.authorising()
                try {
                    val token = client.login(server.serverUrl, username, password.concatToString(), deviceInfo())
                    authorised(server, token, nodeId, deviceId)
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
        // An empty account offers no choice: only the named upload confirmation can continue it.
        if (linkFlow.state.value is ch.lkmc.neutrodyne.sync.api.LinkState.ConfirmUpload &&
            choice != FirstLinkChoice.MERGE
        ) {
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
        refreshGateVerdict()
        return try {
            val response = client.linkApprove(server, token, normaliseUserCode(userCode))
            val purpose =
                when (response.purpose) {
                    "device" -> LinkPurpose.DEVICE

                    "web" -> LinkPurpose.WEB

                    // The server knows only `device`/`web` (10 Link codes): an unknown purpose
                    // can never be rendered as a device grant — fail closed instead.
                    else -> return Outcome.Failure(SyncProblem.Server(400))
                }
            Outcome.Success(
                PendingDevice(
                    userCode = normaliseUserCode(userCode),
                    deviceName = response.deviceName,
                    platform = response.platform,
                    appVersion = response.appVersion,
                    requestedAtMs = parseEpochMilliseconds(response.requestedAt),
                    purpose = purpose,
                    sameNetwork = response.sameNetwork,
                ),
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            Outcome.Failure(mapApprovalFailure(failure))
        }
    }

    override suspend fun confirmLink(
        userCode: String,
        approve: Boolean,
    ): Outcome<Unit, SyncProblem> {
        val (server, token) = linkedContext() ?: return Outcome.Failure(SyncProblem.Revoked)
        refreshGateVerdict()
        return try {
            client.linkConfirm(server, token, normaliseUserCode(userCode), approve)
            Outcome.Success(Unit)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            Outcome.Failure(mapApprovalFailure(failure))
        }
    }

    override suspend fun devices(): Outcome<List<SyncDevice>, SyncProblem> {
        val (server, token) = linkedContext() ?: return Outcome.Failure(SyncProblem.Revoked)
        refreshGateVerdict()
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
        val expected = identityMutex.withLock { disconnectIdentity() }
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
        return admittedDisconnect(expected) {
            expected.serverUrl?.let { runCatching { client.logout() } }
            db.syncStateDao().unlink()
            settingsCapture.clear()
            expected.serverUrl?.let { tokenStore.clear(hostOf(it)) }
            // 10 Passwords: the account's staged auth snapshots leave with the link — under the
            // coordinator, like every credential delete.
            credentials.withCredentialCommit { secrets.dropStagedAuthSnapshots() }
            // 10 Scheduling: unlinking cancels every sync work name before the state refresh.
            scheduler.onUnlinked()
            lastRoundProblem.value = null
            refreshStatus()
            Outcome.Success(Unit)
        }
    }

    override suspend fun deleteServerData(confirm: String): Outcome<Unit, SyncProblem> {
        if (confirm != DELETE_CONFIRMATION) return Outcome.Failure(SyncProblem.Server(400))
        val expected =
            identityMutex.withLock {
                if (linkedContext() == null) null else disconnectIdentity()
            } ?: return Outcome.Failure(SyncProblem.Revoked)
        val url = expected.serverUrl ?: return Outcome.Failure(SyncProblem.Revoked)
        refreshGateVerdict()
        return admittedDisconnect(expected) {
            try {
                client.reset(DELETE_CONFIRMATION, RESET_MODE_PURGE, null)
                db.syncStateDao().unlink()
                settingsCapture.clear()
                tokenStore.clear(hostOf(url))
                credentials.withCredentialCommit { secrets.dropStagedAuthSnapshots() }
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
    }

    /** Read only while holding [identityMutex], which also owns authorization's writes. */
    private suspend fun disconnectIdentity(): DisconnectIdentity {
        val url = address.serverUrl()
        val state = db.syncStateDao().get()
        return DisconnectIdentity(
            url,
            state?.accountId,
            state?.deviceId,
            state?.nodeId,
            url?.let { tokenStore.load(hostOf(it)) },
        )
    }

    /**
     * After admission the application owns the request and cleanup. Leaving the screen cancels
     * only its await; the RPC remains cancellable by its normal timeout or application shutdown.
     * Recheck after the optional push so an old confirmation never disconnects a newer link.
     */
    private suspend fun admittedDisconnect(
        expected: DisconnectIdentity,
        operation: suspend () -> Outcome<Unit, SyncProblem>,
    ): Outcome<Unit, SyncProblem> {
        coroutineContext.ensureActive()
        return scope
            .async(start = CoroutineStart.UNDISPATCHED) {
                identityMutex.withLock {
                    if (disconnectIdentity() != expected) {
                        Outcome.Failure(SyncProblem.Revoked)
                    } else {
                        operation()
                    }
                }
            }.await()
    }

    override suspend fun renameDevice(
        deviceId: String,
        name: String,
    ): Outcome<Unit, SyncProblem> {
        val (server, token) = linkedContext() ?: return Outcome.Failure(SyncProblem.Revoked)
        refreshGateVerdict()
        return try {
            client.renameDevice(deviceId, name.trim())
            Outcome.Success(Unit)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            Outcome.Failure(mapDeviceFailure(failure))
        }
    }

    override suspend fun revokeDevice(deviceId: String): Outcome<Unit, SyncProblem> {
        val (server, token) = linkedContext() ?: return Outcome.Failure(SyncProblem.Revoked)
        refreshGateVerdict()
        return try {
            client.revokeDevice(deviceId)
            Outcome.Success(Unit)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            Outcome.Failure(mapDeviceFailure(failure))
        }
    }

    override fun resolveHeld(
        id: Long,
        resolution: HeldResolution,
    ) {
        // Decide later changes nothing: the HELD row and its banner stay.
        if (resolution == HeldResolution.DECIDE_LATER) return
        scope.launch {
            // The row is read and gated inside the write transaction: an answer can only apply a
            // batch that is still HELD — a row another path resolved, deleted or returned to
            // STAGED/DEFERRED meanwhile is a no-op — and the apply commits or rolls back together
            // with the row's delete (10 Mass-change guard step 3).
            db.withWriteTransaction<Unit> {
                val row = db.syncHeldDao().byId(id) ?: return@withWriteTransaction
                val batch = MassChangeGuard.heldBatch(row.batch) ?: return@withWriteTransaction
                var keepRow = false
                db.syncStateDao().withApplying<Unit> {
                    when (resolution) {
                        // 10 Mass-change guard 4: an explicit Apply is no exception — the
                        // loaded, playing item's removal never runs while it plays; the
                        // remainder becomes a DEFERRED row for the local settle.
                        HeldResolution.APPLY -> {
                            val deferred =
                                MassChangeGuard.apply(db, batch, clock.now()) { syncId ->
                                    MassChangeGuard.playingRemovalDeferred(
                                        db,
                                        playback.active.value,
                                        syncId,
                                    )
                                }
                            if (deferred.isNotEmpty()) {
                                MassChangeGuard.defer(db, row.id, deferred)
                                keepRow = true
                            }
                        }

                        HeldResolution.KEEP_MINE -> {
                            MassChangeGuard.keepMine(db, batch, clock)
                        }

                        HeldResolution.DECIDE_LATER -> {
                            Unit
                        }
                    }
                }
                if (!keepRow) db.syncHeldDao().delete(id)
            }
        }
    }

    override fun dismissRemoteSession() {
        adopter.dismiss()
    }

    override suspend fun setSyncPlaybackSettings(enabled: Boolean): Outcome<Unit, SyncPreferenceError> =
        flags.withPreferenceCommit {
            kotlin.coroutines.coroutineContext.ensureActive()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                if (address.setSyncPlaybackSettings(enabled) is Outcome.Failure) {
                    return@withContext Outcome.Failure(SyncPreferenceError.WriteFailed)
                }
                flags.syncPlaybackSettings = enabled
                // Reconcile only after persistence; enabling then drains durable remote settings.
                settingsCapture.reconcile()
                if (enabled) effects.drainSettings()
                Outcome.Success(Unit)
            }
        }

    override suspend fun setShareFeedPasswords(enabled: Boolean): Outcome<Unit, SyncPreferenceError> =
        flags.withPreferenceCommit {
            kotlin.coroutines.coroutineContext.ensureActive()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                if (address.setShareFeedPasswords(enabled) is Outcome.Failure) {
                    return@withContext Outcome.Failure(SyncPreferenceError.WriteFailed)
                }
                flags.sharePasswords = enabled
                Outcome.Success(Unit)
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
     * behind `ACCESS_LOCAL_NETWORK`. Both consents run before the discovery request is sent — the
     * plaintext confirmation is meaningless once traffic left, and without the permission the
     * request would only time out (10 Local-network gate).
     */
    private suspend fun preflight(address: String): DiscoveredServer? {
        val normalized =
            discovery.normalise(address) ?: run {
                linkFlow.failed(SyncProblem.Server(400))
                return null
            }
        val host =
            bareHostOf(normalized) ?: run {
                linkFlow.failed(SyncProblem.Server(400))
                return null
            }
        if (normalized.startsWith("http://", ignoreCase = true) && !gate.allowsPlainHttp(host)) {
            linkFlow.failed(SyncProblem.InsecureTransport)
            return null
        }
        if (normalized.startsWith("http://", ignoreCase = true)) {
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
        } else {
            // No prompt ran, so the SYNC bypass must follow the gate's real verdict; otherwise an
            // already-granted ACCESS_LOCAL_NETWORK would leave discovery denied (10 gate 3).
            gate.onPermissionResult(gate.hasPermission())
        }
        return when (val result = discovery.discover(normalized)) {
            is DiscoveryResult.Failure -> {
                linkFlow.failed(result.problem)
                null
            }

            is DiscoveryResult.Success -> {
                result.server
            }
        }
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
                authorised(server, token, checkNotNull(pendingNodeId), checkNotNull(pendingDeviceId))
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
        nodeId: NodeId,
        deviceId: String,
    ): Unit =
        identityMutex.withLock {
            // The identity is the session the submit belongs to: a superseded or cancelled job must
            // never write it into a newer session's state.
            coroutineContext.ensureActive()
            tokenStore.save(hostOf(server.serverUrl), token.token)
            address.setServerUrl(server.serverUrl)
            token.username.takeIf { it.isNotBlank() }?.let { address.setUsername(it) }
            db.withWriteTransaction {
                val existing = db.syncStateDao().get()
                // A different server or account is a different profile: the old profile's
                // staged, held and deferred removals must never run against this library.
                // A same-profile reauth — new device id, node id or token — keeps them.
                if (existing == null ||
                    existing.serverUrl != server.serverUrl ||
                    existing.accountId != token.accountId
                ) {
                    db.syncHeldDao().deleteAll()
                }
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

            coroutineContext.ensureActive()
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
        db.syncStateDao().enableLinked(clock.now())
        // 10 Settings capture: the link is durable, so the recorder installs before the state
        // refresh — a local edit between link and install must not escape the outbox.
        settingsCapture.reconcile()
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
                    // 10 Settings capture: install through the same reconcile as the switch —
                    // the merge left the link durable and any remote values already drained.
                    settingsCapture.reconcile()
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

    /**
     * Applies the gate's live verdict before a direct control request. These methods have no
     * preceding preflight/round, so after a process restart the SYNC bypass would still be `false`
     * until one ran: an already-granted `ACCESS_LOCAL_NETWORK` would fail with
     * `LocalNetworkPermissionDenied` (10 Local-network gate 3). No prompt is shown; the gate stays
     * the only writer of the bypass.
     */
    private fun refreshGateVerdict() {
        gate.onPermissionResult(gate.hasPermission())
    }

    /** The linked server's URL and device token, or `null` when this device cannot act. */
    private suspend fun linkedContext(): Pair<String, String>? {
        val url = address.serverUrl() ?: return null
        val state = db.syncStateDao().get()
        val token = tokenStore.load(hostOf(url))
        return if (token != null && state.isActuallyLinked(hasToken = true)) url to token else null
    }

    /** Maps a transport failure and remembers a revocation. */
    private suspend fun tracked(failure: Throwable): SyncProblem {
        val problem = classify(failure)
        if (problem == SyncProblem.Revoked) markRevoked()
        return problem
    }

    private suspend fun markRevoked() {
        db.syncStateDao().markRevoked(SYNC_ERROR_REVOKED)
        settingsCapture.clear()
        // 10 Scheduling: a revoked token cancels every sync work name, as an unlink does.
        scheduler.onUnlinked()
        refreshStatus()
    }

    private suspend fun clearTransientError() {
        db.syncStateDao().clearTransientError(SYNC_ERROR_REVOKED)
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

    /**
     * `link/approve` and `link/confirm` are authenticated calls: a 401 is the dead token like on
     * every other endpoint (10 Errors: `Revoked`, persisted via [tracked]), while their 404 is the
     * unknown, expired or already-used code — never the server address. The unauthenticated
     * `link/start`/`link/token` poll keeps [mapAuthFailure]'s `LINK` reading (401 = bad code state).
     */
    private suspend fun mapApprovalFailure(failure: Throwable): SyncProblem {
        if (failure is SyncTransportException && failure.status == 404) return SyncProblem.InvalidCode
        return tracked(failure)
    }

    /** A 404 on `PATCH`/`DELETE /devices/{id}` is the gone row, not a wrong server (10 Errors). */
    private suspend fun mapDeviceFailure(failure: Throwable): SyncProblem {
        if (failure is SyncTransportException && failure.status == 404) return SyncProblem.DeviceGone
        return tracked(failure)
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
     * One `sync_held` row as a [MassChangePrompt], or `null` when the row is not a user prompt:
     * STAGED/DEFERRED rows are the guard's bookkeeping, and a malformed row must never kill the
     * observer or starve the valid rows behind it. The summary carries only title and name lists
     * (complete — the UI truncates later); the guard records no device name yet.
     */
    private fun heldPrompt(row: SyncHeldEntity): MassChangePrompt? =
        runCatching {
            MassChangeGuard.heldBatch(row.batch) ?: return null
            val summary =
                runCatching { SyncJson.parseToJsonElement(row.summary).jsonObject }.getOrNull()
            MassChangePrompt(
                id = row.id,
                deviceName = null,
                podcastTitles = summaryList(summary, "podcasts"),
                groupNames = summaryList(summary, "groups"),
            )
        }.getOrNull()

    /** One summary string list; a malformed member reads as absent rather than failing the row. */
    private fun summaryList(
        summary: kotlinx.serialization.json.JsonObject?,
        key: String,
    ): List<String> =
        runCatching { summary?.get(key)?.jsonArray?.map { it.jsonPrimitive.content } }
            .getOrNull()
            ?: emptyList()
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
