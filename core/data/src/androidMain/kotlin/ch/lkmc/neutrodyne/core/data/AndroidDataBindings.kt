// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.util.Xml
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.WorkerCreator
import ch.lkmc.neutrodyne.core.common.WorkerKey
import ch.lkmc.neutrodyne.core.data.exporting.ExportRepositoryImpl
import ch.lkmc.neutrodyne.core.data.refresh.RefreshWorker
import ch.lkmc.neutrodyne.core.domain.ExportRepository
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
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** `:core:data`'s Android bindings (03, 05): the platform parser pair, the M3 I/O bindings and the `refresh-*` worker entry. */
@BindingContainer
@ContributesTo(AppScope::class)
object AndroidDataBindings {
    /** AOSP's `XmlPullParser` (KXmlParser) behind the JVM parser's factory port. */
    @Provides
    fun pullParserFactory(): PullParserFactory = PullParserFactory { Xml.newPullParser() }

    @Provides
    @SingleIn(AppScope::class)
    fun feedParser(factory: PullParserFactory): FeedParser = XmlPullFeedParser(factory)

    @Provides
    @SingleIn(AppScope::class)
    fun showNotesSanitizer(): ShowNotesSanitizer = JsoupShowNotesSanitizer()

    /** The OPML read cascade over the same AOSP parser (05 Sniff and parse, row C1). */
    @Provides
    @SingleIn(AppScope::class)
    fun opmlReader(factory: PullParserFactory): OpmlReader = XmlPullOpmlReader(factory)

    /** The archive guard pair (05 Archive guard): `java.util.zip` behind the common ports. */
    @Provides
    @SingleIn(AppScope::class)
    fun archiveReaderFactory(): ArchiveReaderFactory = ZipGuard()

    @Provides
    @SingleIn(AppScope::class)
    fun archiveWriterFactory(): ArchiveWriterFactory = ZipArchiveWriter()

    /** The `ContentResolver` seam the M3 payload/destination adapters run on. */
    @Provides
    fun contentResolver(application: Application): ContentResolver = application.contentResolver

    /**
     * `ExportRepository` binds per platform (05 Destinations): Android's `SafExportDestination`
     * lands with row C1; the desktop's `FileExportDestination` follows with row C3 — the
     * binding stays out of common code so the desktop graph cannot fail before its port exists.
     */
    @Provides
    internal fun exportRepository(impl: ExportRepositoryImpl): ExportRepository = impl

    /** `RefreshWorker`'s entry in the map `MetroWorkerFactory` reads (01 Dependency injection). */
    @Provides
    @IntoMap
    @WorkerKey(RefreshWorker::class)
    fun refreshWorker(factory: RefreshWorker.Factory): WorkerCreator =
        { context: Context, params: WorkerParameters -> factory.create(context, params) }
}
