// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope

/** The sync engine's graph bindings (10 Client sync engine, 01 Dependency injection). */
@ContributesTo(AppScope::class)
@BindingContainer
object SyncBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun syncTokenStore(secrets: SecretStore): SyncTokenStore = SyncTokenStore(secrets)

    /**
     * 10 Settings capture: the local synced-key recorder. The controller installs it while the
     * link is committed and "Sync playback settings" is on, and clears it otherwise.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun settingsCapture(
        db: NeutrodyneDatabase,
        settings: SettingsSyncPort,
    ): SettingsCapture = SettingsCapture(db, settings)

    /** 10 Durable apply effects: the engine's rounds and the first-link merge share one drain. */
    @Provides
    @SingleIn(AppScope::class)
    fun syncApplyEffects(
        db: NeutrodyneDatabase,
        settings: SettingsSyncPort,
        secrets: SecretStore,
        coordinator: ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator,
    ): SyncApplyEffects = SyncApplyEffects(db, settings, secrets, coordinator)

    @Provides
    @SingleIn(AppScope::class)
    fun linkFlow(): LinkFlowImpl = LinkFlowImpl()

    @Provides
    @SingleIn(AppScope::class)
    fun syncClient(
        clients: NeutrodyneHttpClients,
        tokens: SyncTokenStore,
        address: SyncServerAddress,
    ): SyncClient =
        KtorSyncClient(
            clients,
            tokenProvider = { address.serverUrl()?.let { tokens.load(hostOf(it)) } },
            baseUrlProvider = { address.serverUrl() ?: "" },
        )

    /** The one [SyncApplier]: the engine's rounds and the first-link merge share its rules. */
    @Provides
    @SingleIn(AppScope::class)
    fun syncApplier(
        db: NeutrodyneDatabase,
        clock: Clock,
        secrets: SecretStore,
        settings: SettingsSyncPort,
        playback: PlaybackSyncPort,
        syncIngestHook: SyncIngestHook,
        channelSync: GroupChannelSync,
        flags: SyncFlags,
    ): SyncApplier =
        SyncApplier(
            db = db,
            clock = clock,
            secrets = secrets,
            settings = settings,
            playback = playback,
            syncIngestHook = syncIngestHook,
            channelSync = channelSync,
            sharePasswords = { flags.sharePasswords },
        )

    @Provides
    @SingleIn(AppScope::class)
    fun syncEngine(
        db: NeutrodyneDatabase,
        client: SyncClient,
        applier: SyncApplier,
        clock: Clock,
        secrets: SecretStore,
        flags: SyncFlags,
        address: SyncServerAddress,
        tokens: SyncTokenStore,
        effects: SyncApplyEffects,
        notices: SyncNoticeBus,
        playback: PlaybackSyncPort,
    ): SyncEngine =
        SyncEngine(
            db = db,
            client = client,
            applier = applier,
            clock = clock,
            secrets = secrets,
            sharePasswords = { flags.sharePasswords },
            address = address,
            tokens = tokens,
            effects = effects,
            notices = notices,
            playback = playback,
        )

    /**
     * 10 Scheduling: the shared trigger engine; the platform contributes its [SyncScheduleHooks]
     * (WorkManager on Android, the `sync` lane on the desktop) and the start-up initializer
     * calls `start()`.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun syncScheduler(
        db: NeutrodyneDatabase,
        engine: SyncEngine,
        playback: PlaybackSyncPort,
        network: NetworkMonitor,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
        hooks: SyncScheduleHooks,
        events: Provider<SyncEventsClient>,
    ): SyncScheduler =
        SyncScheduler(
            db = db,
            round = { reason -> engine.round(reason) },
            playback = playback,
            network = network,
            clock = clock,
            scope = scope,
            hooks = hooks,
            releaseDeferred = { engine.releaseDeferred() },
            linkStateChanged = { events().reevaluate() },
        )

    /** The events stream's transport (10 Live updates) — the `SYNC_STREAM` client derivation. */
    @Provides
    @SingleIn(AppScope::class)
    fun syncEventTransport(clients: NeutrodyneHttpClients): SyncEventTransport = KtorSyncEventTransport(clients)

    /**
     * 10 Live updates: the SSE supervisor. Its pokes ride the same scheduler paths a local
     * change takes — a remote `changed` coalesces into a round, a `401` cancels sync work.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun syncEventsClient(
        db: NeutrodyneDatabase,
        transport: SyncEventTransport,
        client: SyncClient,
        address: SyncServerAddress,
        tokens: SyncTokenStore,
        network: NetworkMonitor,
        @ApplicationScope scope: CoroutineScope,
        scheduler: SyncScheduler,
    ): SyncEventsClient =
        SyncEventsClient(
            db = db,
            transport = transport,
            discover = { url -> client.discover(url) },
            address = address,
            tokens = tokens,
            network = network,
            scope = scope,
            onRemoteChanged = { scheduler.onRemoteChanged() },
            onRevoked = { scheduler.onUnlinked() },
        )

    /**
     * The first-link merge; the controller drives it from the link state machine. The
     * `LibraryMerger` port is `:core:data`'s `RestoreMerger` binding (05's one routine).
     */
    @Provides
    @SingleIn(AppScope::class)
    fun firstLinkMerger(
        db: NeutrodyneDatabase,
        client: SyncClient,
        applier: SyncApplier,
        engine: SyncEngine,
        libraryMerger: ch.lkmc.neutrodyne.core.domain.LibraryMerger,
        storagePaths: ch.lkmc.neutrodyne.core.common.StoragePaths,
        settings: SettingsSyncPort,
        secrets: SecretStore,
        coordinator: ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator,
        effects: SyncApplyEffects,
        flags: SyncFlags,
        clock: Clock,
    ): FirstLinkMerger =
        FirstLinkMerger(
            db = db,
            client = client,
            applier = applier,
            engine = engine,
            libraryMerger = libraryMerger,
            storagePaths = storagePaths,
            settings = settings,
            secrets = secrets,
            sharePasswords = { flags.sharePasswords },
            syncPlaybackSettings = { flags.syncPlaybackSettings },
            coordinator = coordinator,
            effects = effects,
            clock = clock,
        )
}
