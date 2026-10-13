// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.ColumnTypeConverters
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteDriver
import ch.lkmc.neutrodyne.core.database.migration.ALL_MIGRATIONS
import kotlin.coroutines.CoroutineContext

/**
 * Schema version 2 — the 27 V1 tables plus D98's `episode_guid_provenance` and
 * `podcast.guidCoverageSince` (02 Tables). The `@Database` entity list, indices and converters are
 * exported to `schemas/ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase/<version>.json`
 * (frozen once a tagged release contains it); `1.json` stays untouched (V1 was never released).
 */
@Database(
    entities = [
        PodcastEntity::class,
        PodcastUrlAliasEntity::class,
        CredentialEntity::class,
        PodcastSettingsEntity::class,
        PodcastGroupEntity::class,
        PodcastGroupMemberEntity::class,
        PodcastGroupSettingsEntity::class,
        EpisodeEntity::class,
        EpisodeDescriptionEntity::class,
        EpisodeTranscriptEntity::class,
        EpisodeAltEnclosureEntity::class,
        PersonEntity::class,
        FundingEntity::class,
        ChapterEntity::class,
        EpisodeStateEntity::class,
        EpisodeGuidProvenanceEntity::class,
        EpisodePositionEntity::class,
        QueueEntryEntity::class,
        PlaySessionEntity::class,
        DownloadEntity::class,
        ArtworkEntity::class,
        ImportSessionEntity::class,
        ImportItemEntity::class,
        SyncStateEntity::class,
        SyncOutboxEntity::class,
        SyncClockEntity::class,
        SyncParkedEntity::class,
        SyncHeldEntity::class,
    ],
    version = NeutrodyneDatabase.VERSION,
    exportSchema = true,
)
@ConstructedBy(NeutrodyneDatabaseConstructor::class)
@ColumnTypeConverters(NeutrodyneConverters::class)
abstract class NeutrodyneDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao

    abstract fun episodeDao(): EpisodeDao

    abstract fun ingestDao(): IngestDao

    abstract fun feedDao(): FeedDao

    abstract fun groupDao(): GroupDao

    abstract fun scopeSettingsDao(): ScopeSettingsDao

    abstract fun episodeStateDao(): EpisodeStateDao

    abstract fun positionDao(): PositionDao

    abstract fun queueDao(): QueueDao

    abstract fun playSessionDao(): PlaySessionDao

    abstract fun downloadDao(): DownloadDao

    abstract fun artworkDao(): ArtworkDao

    abstract fun chapterDao(): ChapterDao

    abstract fun credentialDao(): CredentialDao

    abstract fun importDao(): ImportDao

    abstract fun backupDao(): BackupDao

    abstract fun maintenanceDao(): MaintenanceDao

    abstract fun syncStateDao(): SyncStateDao

    abstract fun syncOutboxDao(): SyncOutboxDao

    abstract fun syncClockDao(): SyncClockDao

    abstract fun syncParkedDao(): SyncParkedDao

    abstract fun syncHeldDao(): SyncHeldDao

    companion object {
        const val VERSION = 2
        const val FILE_NAME = "neutrodyne.db"

        /**
         * The one builder path (02 Database builder and connections): bundled driver, query
         * coroutine context on `@Dispatcher(IO)`, WAL, migrations, and the open callbacks.
         * `supportsOptimizeMask` selects `PRAGMA optimize=0x10002` (bundled driver) over the plain
         * pragma (framework driver) in `onOpen`. `fallbackToDestructiveMigration*` is never used.
         */
        fun build(
            factory: DatabaseFactory,
            driver: SQLiteDriver,
            io: CoroutineContext,
            cb: RoomDatabase.Callback,
        ): NeutrodyneDatabase =
            factory
                .builder()
                .setDriver(driver)
                .setQueryCoroutineContext(io)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*ALL_MIGRATIONS)
                .addCallback(cb)
                .build()
    }
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "KotlinNoActualForExpect")
expect object NeutrodyneDatabaseConstructor : RoomDatabaseConstructor<NeutrodyneDatabase> {
    override fun initialize(): NeutrodyneDatabase
}
