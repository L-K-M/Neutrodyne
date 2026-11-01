// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.model.DownloadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.BufferedSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RssCrashProofTest {
    @Test fun unchangedProofAcrossRangeRetryAndRestartStillUsesThreeWrites() =
        runTest {
            for (restart in listOf(false, true)) {
                TransferRig().use { http ->
                    val a = mediaBytes(262_144)
                    val cut = 131_072
                    val written = CountDownLatch(1)
                    http.server.enqueue(heldBody(a, cut, written))
                    val rangeStart = if (restart) 0 else cut
                    http.server.enqueue(
                        response(
                            a.copyOfRange(cut, a.size),
                            206,
                            "ETag" to "\"A\"",
                            "Content-Type" to "audio/mpeg",
                            "Content-Range" to "bytes $rangeStart-${a.lastIndex}/${a.size}",
                        ),
                    )
                    if (restart) {
                        http.server.enqueue(response(a, 200, "ETag" to "\"A\"", "Content-Type" to "audio/mpeg"))
                    }
                    try {
                        EngineRig(this, sourceFactory = { f ->
                            rss(f, http, afterAppend = { if (it.length() >= cut) written.countDown() })
                        }).use { f ->
                            val id = f.seed(enclosureUrl = http.server.url("/media").toString()).single()
                            f.audit()
                            f.engine.drain(drainRequest())
                            assertEquals(listOf("RESOLVING", "DOWNLOADING", "COMPLETED"), f.writes())
                            val row = f.row(id)
                            f.files.openPart(row.rootId, assertNotNull(row.relativePath)).withOpen {
                                assertContentEquals(a, it.contents())
                            }
                            assertNull(http.server.takeRequest().headers["Range"])
                            val retry = http.server.takeRequest()
                            assertEquals("bytes=$cut-", retry.headers["Range"])
                            assertEquals("\"A\"", retry.headers["If-Range"])
                            if (restart) assertNull(http.server.takeRequest().headers["Range"])
                        }
                    } finally {
                        written.countDown()
                    }
                }
            }
        }

    @Test fun lostTokenRejectsReplacementBeforeAnyReplacementByte() =
        runTest {
            TransferRig().use { http ->
                val a = mediaBytes(262_144)
                val written = CountDownLatch(1)
                var rejected = false
                var bAppended = false
                http.server.enqueue(heldBody(a, 131_072, written))
                http.server.enqueue(
                    response(
                        a.copyOf().also { it.fill(42, 3) },
                        200,
                        "ETag" to "\"B\"",
                        "Content-Type" to "audio/mpeg",
                    ),
                )
                try {
                    EngineRig(this, sourceFactory = { f ->
                        rss(f, http, beforeStarted = { metadata, part ->
                            if (metadata.etag == "\"B\"") {
                                assertEquals(0L, part.length())
                                val old = f.row(1)
                                assertEquals(
                                    1,
                                    f.dao.transition(
                                        old.copy(state = DownloadState.QUEUED, runnerToken = null),
                                        old.state,
                                        "desk:old",
                                    ),
                                )
                                assertEquals(1, f.dao.claim(1, "desk:new"))
                                rejected = true
                            }
                        }, afterAppend = {
                            if (rejected) bAppended = true
                            if (it.length() >= 131_072) written.countDown()
                        })
                    }).use { f ->
                        val id = f.seed(enclosureUrl = http.server.url("/media").toString()).single()
                        f.engine.drain(drainRequest("desk:old"))
                        assertTrue(rejected)
                        assertFalse(bAppended)
                        assertEquals("desk:new", f.row(id).runnerToken)
                        assertEquals(DownloadState.RESOLVING, f.row(id).state)
                        assertEquals("\"A\"", f.row(id).etag)
                        assertTrue(f.indexed.isEmpty())
                        assertFalse("move" in f.faults.operations)
                        f.files.openPart("old", DownloadPaths.part(id)).withOpen { assertEquals(0L, it.length()) }
                    }
                } finally {
                    written.countDown()
                }
            }
        }

    @Test fun replacementCrashSnapshotRecoversExactOriginalBytes() =
        runTest {
            TransferRig().use { http ->
                val a = mediaBytes(1_048_576)
                val b = a.copyOf().also { it.fill(42, 3) }
                val aWritten = CountDownLatch(1)
                val releaseB = CountDownLatch(1)
                val snapshot = CompletableDeferred<Pair<DownloadEntity, ByteArray>>()
                var accepted = 0
                var beforeB: DownloadEntity? = null
                var partialA = 0L
                http.server.enqueue(heldBody(a, 131_072, aWritten))
                http.server.enqueue(heldBody(b, 262_144, releaseB, "\"B\""))
                val captured =
                    try {
                        EngineRig(this, sourceFactory = { f ->
                            rss(f, http, onStarted = { metadata, part ->
                                accepted++
                                if (accepted == 2) {
                                    assertEquals("\"B\"", metadata.etag)
                                    assertEquals(0L, part.length())
                                    beforeB = f.row(1)
                                }
                            }, afterAppend = { part ->
                                val length = part.length()
                                if (accepted == 1 && length >= 131_072) {
                                    partialA = length
                                    aWritten.countDown()
                                }
                                if (accepted == 2 && length > 65_536) {
                                    // The actual writer is suspended here: no append/truncate/finish can race
                                    // the DAO read or synced read-back. Capture BEFORE cleanup mutates the row.
                                    part.sync()
                                    snapshot.complete(f.row(1) to part.contents())
                                    awaitCancellation()
                                }
                            })
                        }).use { f ->
                            f.seed(enclosureUrl = http.server.url("/media").toString())
                            val runner = async { f.engine.drain(drainRequest("desk:crashed")) }
                            try {
                                snapshot.await()
                            } finally {
                                runner.cancelAndJoin()
                            }
                        }
                    } finally {
                        aWritten.countDown()
                        releaseB.countDown()
                    }
                val (row, prefix) = captured
                assertEquals(DownloadState.DOWNLOADING, row.state)
                assertEquals("desk:crashed", row.runnerToken)
                assertTrue(prefix.size > 65_536 && prefix.size < a.size)
                assertContentEquals(b.copyOf(prefix.size), prefix)
                val first = http.server.takeRequest()
                assertNull(first.headers["Range"])
                val replacement = http.server.takeRequest()
                assertEquals("bytes=$partialA-", replacement.headers["Range"])
                assertEquals("\"A\"", replacement.headers["If-Range"])
                val retained = prefix.size - 65_536
                http.server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            assertEquals("bytes=$retained-", request.headers["Range"])
                            return if (request.headers["If-Range"] == "\"A\"") {
                                response(
                                    a.copyOfRange(retained, a.size),
                                    206,
                                    "ETag" to "\"A\"",
                                    "Content-Type" to "audio/mpeg",
                                    "Content-Range" to "bytes $retained-${a.lastIndex}/${a.size}",
                                )
                            } else {
                                assertEquals("\"B\"", request.headers["If-Range"])
                                response(a, 200, "ETag" to "\"A\"", "Content-Type" to "audio/mpeg")
                            }
                        }
                    }
                EngineRig(this, sourceFactory = { rss(it, http) }).use { fresh ->
                    val id = fresh.seed(enclosureUrl = http.server.url("/media").toString()).single()
                    assertEquals(row.episodeId, id)
                    assertEquals(1, fresh.dao.removeUnclaimed(listOf(id)))
                    fresh.dao.insert(row)
                    fresh.files.openPart(row.rootId, assertNotNull(row.tempPath)).withOpen {
                        it.append(prefix, prefix.size)
                        it.sync()
                        assertContentEquals(prefix, it.contents())
                    }
                    ReconcileRig(fresh).reconciler.reconcile()
                    assertEquals(DownloadStopReasons.PROCESS_DEATH, fresh.row(id).lastStopReason)
                    assertNull(fresh.row(id).runnerToken)
                    fresh.engine.drain(drainRequest("desk:fresh"))
                    val completed = fresh.row(id)
                    assertEquals(DownloadState.COMPLETED, completed.state)
                    assertEquals(completed.finalUri, fresh.indexed[id])
                    val actual =
                        fresh.files
                            .openPart(completed.rootId, assertNotNull(completed.relativePath))
                            .withOpen { it.contents() }
                    val wire = http.server.takeRequest()
                    println(
                        "crash proof=${row.etag}, beforeB=${beforeB?.etag}, " +
                            "B prefix=${prefix.size}, retained=$retained, " +
                            "recovery If-Range=${wire.headers["If-Range"]}, final size=${actual.size}, " +
                            "first mismatch=${actual.indices.firstOrNull { actual[it] != a[it] }}",
                    )
                    assertTrue(a.contentEquals(actual), "Completed file must be exact A after logical crash recovery")
                    assertEquals("\"B\"", beforeB?.etag, "Replacement proof precedes its first append")
                    assertEquals("\"B\"", row.etag)
                }
            }
        }

    private fun heldBody(
        bytes: ByteArray,
        prefix: Int,
        release: CountDownLatch,
        tag: String = "\"A\"",
    ) = MockResponse
        .Builder()
        .body(
            object : MockResponseBody {
                override val contentLength = bytes.size.toLong()

                override fun writeTo(sink: BufferedSink) {
                    sink.write(bytes, 0, prefix)
                    sink.flush()
                    check(release.await(15, TimeUnit.SECONDS))
                }
            },
        ).setHeader("ETag", tag)
        .setHeader("Content-Type", "audio/mpeg")
        .onResponseEnd(SocketEffect.CloseSocket())
        .build()

    private fun rss(
        f: EngineRig,
        http: TransferRig,
        beforeStarted: suspend (TransferMetadata, PartFile) -> Unit = { _, _ -> },
        onStarted: suspend (TransferMetadata, PartFile) -> Unit = { _, _ -> },
        afterAppend: suspend (PartFile) -> Unit = {},
    ): TransferSource {
        val source =
            RssTransferSource(
                http.clients,
                CredentialLookup.None,
                f.db.episodeDao(),
                f.roots,
                f.files,
                f.verification,
                http.network,
                f.clock,
                PlatformKind.DESKTOP,
            )
        return object : TransferSource by source {
            override suspend fun transfer(
                plan: TransferPlan,
                part: PartFile,
                sink: ProgressSink,
            ): TransferResult =
                source.transfer(
                    plan,
                    object : PartFile by part {
                        override suspend fun append(
                            buffer: ByteArray,
                            count: Int,
                        ) {
                            part.append(buffer, count)
                            afterAppend(part)
                        }
                    },
                    object : ProgressSink by sink {
                        override suspend fun started(metadata: TransferMetadata) {
                            beforeStarted(metadata, part)
                            sink.started(metadata)
                            onStarted(metadata, part)
                        }
                    },
                )
        }
    }
}
