// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import androidx.work.WorkerFactory
import ch.lkmc.neutrodyne.SnapshotNowReceiver
import ch.lkmc.neutrodyne.core.artwork.NeutrodyneImageLoaderFactory
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.domain.ExportRepository
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.ImportReportPoster
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.domain.NewEpisodePoster
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.work.MetroWorkerFactory
import ch.lkmc.neutrodyne.youtube.YouTubeBindingsModule
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import kotlinx.coroutines.CoroutineScope

/**
 * The Android main process's graph (01 Dependency injection, D82). It exists only in the main process
 * (D73): `:ytx` builds [YtxGraph], `:acra` builds nothing.
 */
@DependencyGraph(AppScope::class, bindingContainers = [YouTubeBindingsModule::class])
interface AndroidAppGraph {
    val application: Application

    @ApplicationScope
    val appScope: CoroutineScope

    val initializers: Set<AppInitializer>

    val workerFactory: WorkerFactory

    val buildInfo: BuildInfo

    /** `MainActivity` collects `appearance.*` through it into `AppearancePrefs` (08 App scheme). */
    val settingsRepository: SettingsRepository

    val groupChannelSync: GroupChannelSync

    val newEpisodePoster: NewEpisodePoster

    /** Every feature's navigation entries (01 Feature entry installers). */
    val entryInstallers: Set<EntryProviderInstaller>

    /** `MainActivity` maps `openState` onto the start-up gate (01 Splash and start-up gate). */
    val databaseOpener: DatabaseOpener

    /**
     * M1b: the Keystore-backed store — graph tests prove `CredentialLookup` resolves to it, so
     * `AuthInterceptor` can never be left reading `None` in the app process.
     */
    val secretStore: SecretStore

    /** What `AuthInterceptor` injects: the same singleton as [secretStore]. */
    val credentialLookup: CredentialLookup

    /**
     * M3 C1: the OPML/backup export service — the graph test proves it binds through the
     * Android seam (SAF destination + FileProvider share URIs), not a common default.
     */
    val exportRepository: ExportRepository

    /**
     * M3 C2: the import and backup lanes' repositories — bound to the real impls so a feature
     * row (D-rows) never resolves a stub, and the `import_backup` report poster.
     */
    val importRepository: ImportRepository

    val backupRepository: BackupRepository

    val importReportPoster: ImportReportPoster

    /** The Coil `SingletonImageLoader.Factory` installed in `NeutrodyneApplication` (08 Coil ImageLoader). */
    val imageLoaderFactory: NeutrodyneImageLoaderFactory

    /**
     * `metroViewModel()`/`assistedMetroViewModel()` resolve through `LocalMetroViewModelFactory`,
     * provided with this binding at the root (01 Feature entry installers).
     */
    val metroViewModelFactory: MetroViewModelFactory

    @Binds
    val MetroWorkerFactory.bindWorkerFactory: WorkerFactory

    /**
     * Member injection for the framework-created bmgr trigger (05 Testing with bmgr): the
     * manifest receiver is constructed before the graph, so `onReceive` asks for its
     * `Lazy<BackupRepository>` and `@ApplicationScope` scope here.
     */
    fun inject(receiver: SnapshotNowReceiver)

    /**
     * The one database accessor (02 Error handling and recovery): blocks a background caller until
     * the open finishes, throws on the main thread before that — callers hold this lazily.
     */
    @Provides
    fun provideDatabase(opener: DatabaseOpener): NeutrodyneDatabase = opener.requireDatabase()

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Application,
        ): AndroidAppGraph
    }
}
