// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.data.backup.BackupRepositoryImpl
import ch.lkmc.neutrodyne.core.data.backup.NoSnapshotScheduler
import ch.lkmc.neutrodyne.core.data.exporting.ExportRepositoryImpl
import ch.lkmc.neutrodyne.core.data.importing.ImportRepositoryImpl
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.importing.LaneImportWorkScheduler
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.domain.ExportRepository
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.domain.SnapshotScheduler
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReaderFactory
import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriterFactory
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.jvm.opml.XmlPullOpmlReader
import ch.lkmc.neutrodyne.feeds.jvm.parse.PullParserFactory
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import ch.lkmc.neutrodyne.feeds.opml.OpmlReader
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * `:core:data`'s desktop bindings (03): the JVM parser pair (kxml2 via service discovery) and,
 * from M3 row C3a, the JVM import/export factories. `PayloadSource`/`ExportDestination` join
 * through `@ContributesBinding` on `FilePayloadSource`/`FileExportDestination`. Row C3b adds
 * the import/backup repositories and the `import-backup` lane's `ImportWorkScheduler` —
 * provider-bound like the repositories because the lane needs the concrete type while the
 * port stays `internal` (`LaneImportWorkScheduler`'s own note).
 */
@BindingContainer
@ContributesTo(AppScope::class)
object DesktopDataBindings {
    /** kxml2 through `META-INF/services` discovery (the `PullParserFactory` doc). */
    @Provides
    fun pullParserFactory(): PullParserFactory = PullParserFactory.Discovered

    @Provides
    @SingleIn(AppScope::class)
    fun feedParser(factory: PullParserFactory): FeedParser = XmlPullFeedParser(factory)

    @Provides
    @SingleIn(AppScope::class)
    fun showNotesSanitizer(): ShowNotesSanitizer = JsoupShowNotesSanitizer()

    /** The OPML import reader (05 Sniff and parse) — kxml2-backed on the desktop. */
    @Provides
    @SingleIn(AppScope::class)
    fun opmlReader(factory: PullParserFactory): OpmlReader = XmlPullOpmlReader(factory)

    /** The capped ZIP reader (05 Archive guard) for backup/Takeout imports. */
    @Provides
    @SingleIn(AppScope::class)
    fun archiveReaderFactory(): ArchiveReaderFactory = ZipGuard()

    /** The deterministic backup ZIP writer (05 Writing); callers stage and move atomically. */
    @Provides
    @SingleIn(AppScope::class)
    fun archiveWriterFactory(): ArchiveWriterFactory = ZipArchiveWriter()

    /** D34: the desktop has no Auto Backup — the scheduler does nothing. */
    @Provides
    fun snapshotScheduler(): SnapshotScheduler = NoSnapshotScheduler

    /** The impl type is `internal` to this module, so the provider is too. */
    @Provides
    @SingleIn(AppScope::class)
    internal fun exportRepository(impl: ExportRepositoryImpl): ExportRepository = impl

    /** M3 C3b: preview → commit → lane fetch host; `internal` impl, internal provider. */
    @Provides
    @SingleIn(AppScope::class)
    internal fun importRepository(impl: ImportRepositoryImpl): ImportRepository = impl

    /** M3 C3b: backup writes and restore claims; the claims wake the `import-backup` lane. */
    @Provides
    @SingleIn(AppScope::class)
    internal fun backupRepository(impl: BackupRepositoryImpl): BackupRepository = impl

    /**
     * M3 C3b: the `import-backup` lane's scheduler, constructed here — the lane injects the
     * concrete type (for its run-tracking hooks) while `ImportRepositoryImpl`,
     * `BackupRepositoryImpl`, `RestoreRunner` and `AutoSnapshotProducer` see the port.
     * `poker` stays a `Provider` so `runner -> lane -> scheduler -> poker -> runner` never
     * forms a construction cycle.
     */
    @Provides
    @SingleIn(AppScope::class)
    internal fun laneImportWorkScheduler(
        db: NeutrodyneDatabase,
        poker: Provider<JobLanePoker>,
    ): LaneImportWorkScheduler = LaneImportWorkScheduler(db, poker)

    @Provides
    @SingleIn(AppScope::class)
    internal fun importWorkScheduler(impl: LaneImportWorkScheduler): ImportWorkScheduler = impl
}
