// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.app.Application
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.database.AndroidDatabaseFactory
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabaseConstructor
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.TestClock
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.io.File
import java.util.UUID

class ArtworkTestApplication :
    Application(),
    GraphHolder {
    override lateinit var graph: Any
}

internal class ArtworkHostFixture(
    val app: Application,
    factoryOverride: DatabaseFactory? = null,
) : Closeable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val clock = TestClock()
    var enqueues = 0
    val scheduler =
        ArtworkSyncScheduler(
            object : ArtworkWorkScheduler {
                override suspend fun enqueue() {
                    enqueues++
                }
            },
            scope,
        )
    val factory =
        factoryOverride ?: object : DatabaseFactory by AndroidDatabaseFactory(app) {
            override fun exists(): Boolean = false

            override fun builder(): RoomDatabase.Builder<NeutrodyneDatabase> =
                Room.inMemoryDatabaseBuilder(context = app, factory = NeutrodyneDatabaseConstructor::initialize)
        }
    val opener = DatabaseOpener(factory, AndroidSQLiteDriver(), Dispatchers.IO, clock, true)
    val db: NeutrodyneDatabase get() = opener.requireDatabase()
    val paths = StoragePaths(app)
    val codec = AndroidArtworkCodec()
    val monograms = AndroidMonogramRenderer()
    val store by lazy {
        DefaultArtworkStore(
            db,
            paths,
            AndroidArtworkContentUris(app),
            scope,
            scheduler,
            Dispatchers.IO,
        )
    }
    val access = ArtworkProviderAccess(opener, Provider { store }, codec, monograms, Dispatchers.IO)

    suspend fun open() {
        opener.awaitOpen()
    }

    suspend fun podcast(
        key: String,
        url: String? = null,
        title: String = "Synthetic Show",
    ): Long =
        db.podcastDao().insertPodcast(
            PodcastEntity(
                syncId = UUID.randomUUID().toString(),
                sourceType = SourceType.RSS,
                feedUrl = "https://synthetic.test/feed/$key",
                feedKey = "synthetic/$key",
                title = title,
                artworkKey = key,
                artworkUrl = url,
                status = PodcastStatus.ACTIVE,
                initialFetch = false,
                subscribedAt = clock.now(),
            ),
        )

    override fun close() {
        scope.cancel()
        runCatching { db.close() }
        File(app.filesDir, "artwork").deleteRecursively()
    }
}
