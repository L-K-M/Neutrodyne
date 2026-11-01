// SPDX-License-Identifier: Unlicense
@file:Suppress("ktlint:standard:max-line-length")

package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** Cross-module file fixture, generated with the real writer. No server dependency enters client code. */
object ServerExportFixture {
    @JvmStatic fun main(args: Array<String>) =
        runBlocking {
            val output = Path.of(args.single())
            Files.createDirectories(output.parent)
            AuthFixture().use { f ->
                val account = f.account()
                val principal = f.principal(f.grant(account))
                val fixture = WriterFixture()
                val history =
                    fixture.write(episode(), "fav", JsonPrimitive(true)).copy(
                        match =
                            MatchHints(
                                guid = "ep",
                                title = "Server episode",
                                enc = "https://cdn.example.com/ep.mp3",
                            ),
                    )
                val result =
                    f.services.records.push(
                        principal,
                        SyncRequest(
                            null,
                            listOf(
                                fixture.podcast(),
                                fixture.group(),
                                fixture.write(member(), "in", JsonPrimitive(true)),
                                history,
                                // Deliberately no matching episode row: the server must export a sparse stub.
                                fixture.write(
                                    episode(
                                        episode = "g:queued",
                                        coll = ch.lkmc.neutrodyne.sync.protocol.SyncCollection.UPNEXT,
                                    ),
                                    "in",
                                    JsonPrimitive(true),
                                ),
                                fixture
                                    .write(
                                        episode(episode = "g:queue-known"),
                                        "fav",
                                        JsonPrimitive(false),
                                    ).copy(match = MatchHints(enc = "https://cdn.example.com/known.mp3")),
                                fixture.write(
                                    episode(
                                        episode = "g:queue-known",
                                        coll = ch.lkmc.neutrodyne.sync.protocol.SyncCollection.UPNEXT,
                                    ),
                                    "in",
                                    JsonPrimitive(true),
                                ),
                                fixture.write(
                                    episode(),
                                    "pos",
                                    JsonObject(
                                        mapOf(
                                            "ms" to JsonPrimitive(12000),
                                            "dur" to JsonPrimitive(60000),
                                            "src" to JsonPrimitive("STREAM"),
                                        ),
                                    ),
                                ),
                                fixture.write(setting, "v", JsonPrimitive(1.5)),
                                fixture.write(podcastA, "s.playbackSpeed", JsonPrimitive(1.25)),
                                fixture.write(
                                    key(ch.lkmc.neutrodyne.sync.protocol.SyncCollection.SETTING, "ui.private"),
                                    "v",
                                    JsonPrimitive("never-export"),
                                ),
                            ),
                        ),
                    )
                check(result.results.none { it.status == "rejected" })
                f.store.snapshot(account.id).use { AccountArchiveWriter(f.inputs).export(it, output) }
            }
            println("Generated real server export fixture")
        }
}
