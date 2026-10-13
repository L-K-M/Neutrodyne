// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import android.content.Context
import android.util.Xml
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.WorkerCreator
import ch.lkmc.neutrodyne.core.common.WorkerKey
import ch.lkmc.neutrodyne.core.data.refresh.RefreshWorker
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.jvm.parse.PullParserFactory
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** `:core:data`'s Android bindings (03): the platform parser pair and the `refresh-*` worker entry. */
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

    /** `RefreshWorker`'s entry in the map `MetroWorkerFactory` reads (01 Dependency injection). */
    @Provides
    @IntoMap
    @WorkerKey(RefreshWorker::class)
    fun refreshWorker(factory: RefreshWorker.Factory): WorkerCreator =
        { context: Context, params: WorkerParameters -> factory.create(context, params) }
}
