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
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope

/** The sync engine's graph bindings (10 Client sync engine, 01 Dependency injection). */
@ContributesTo(AppScope::class)
@BindingContainer
object SyncBindings {
    @Provides
    fun playbackSyncPort(): PlaybackSyncPort = ch.lkmc.neutrodyne.core.domain.NoPlaybackSync

    @Provides
    @SingleIn(AppScope::class)
    fun syncTokenStore(secrets: SecretStore): SyncTokenStore = SyncTokenStore(secrets)

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
    ): SyncScheduler =
        SyncScheduler(
            db = db,
            round = { reason -> engine.round(reason) },
            playback = playback,
            network = network,
            clock = clock,
            scope = scope,
            hooks = hooks,
        )

    /** The first-link merge; the controller drives it from the link state machine. */
    @Provides
    @SingleIn(AppScope::class)
    fun firstLinkMerger(
        db: NeutrodyneDatabase,
        client: SyncClient,
        applier: SyncApplier,
        engine: SyncEngine,
        clock: Clock,
    ): FirstLinkMerger = FirstLinkMerger(db, client, applier, engine, clock)
}
