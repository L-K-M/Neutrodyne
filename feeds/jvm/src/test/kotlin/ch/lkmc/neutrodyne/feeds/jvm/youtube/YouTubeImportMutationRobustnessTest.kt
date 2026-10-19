// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.youtube

import ch.lkmc.neutrodyne.feeds.jvm.parse.MutationRobustnessTest
import ch.lkmc.neutrodyne.feeds.youtube.ImportParseException
import ch.lkmc.neutrodyne.feeds.youtube.LibreTubeBackupParser
import ch.lkmc.neutrodyne.feeds.youtube.NewPipeSubscriptions
import ch.lkmc.neutrodyne.feeds.youtube.TakeoutSubscriptionsParser
import ch.lkmc.neutrodyne.feeds.youtube.UrlListParser
import okio.Buffer
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Format providers run in the existing mutationTest task's 128 MiB process. */
class YouTubeImportMutationRobustnessTest {
    @Test
    fun everyImportProviderReturnsOrRejectsWithinItsBudget() {
        val root = File(System.getProperty("neutrodyne.rootDir"), "feeds/src/test/resources/import")
        val parsers =
            listOf(
                "newpipe/subscriptions.json" to NewPipeSubscriptions::parse,
                "libretube/groups.json" to LibreTubeBackupParser::parse,
                "takeout/subscriptions.csv" to TakeoutSubscriptionsParser::parse,
                "urllist/mixed.txt" to UrlListParser::parse,
            )
        val worker = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        try {
            val mutator = MutationRobustnessTest()
            for ((path, parse) in parsers) {
                val bytes = File(root, path).readBytes()
                val random = Random(path.hashCode())
                repeat(System.getProperty("neutrodyne.mutationIterations").toIntOrNull() ?: 20) {
                    for (mutant in mutator.mutantsOf(bytes, random)) {
                        worker
                            .submit<Unit> {
                                try {
                                    parse(Buffer().write(mutant))
                                } catch (_: ImportParseException) {
                                }
                            }.get(5, TimeUnit.SECONDS)
                    }
                }
            }
        } finally {
            worker.shutdownNow()
        }
    }
}
