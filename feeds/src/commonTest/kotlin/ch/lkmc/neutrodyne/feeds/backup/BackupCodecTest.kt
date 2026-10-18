// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.backup

import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer
import okio.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupCodecTest {
    private val codec = BackupCodec()
    private val stagingCodec = BackupCodec(BackupCodec.Mode.STAGING)

    /**
     * 50,000 nested arrays (~100 KiB): far beyond any recursive tree reader's stack, yet
     * cheap — unknown-member skipping stays iterative at any depth.
     */
    private val deepArray = "[".repeat(DEEP_ARRAYS) + "0" + "]".repeat(DEEP_ARRAYS)

    private val overrides =
        OverridesV1(1.5f, true, 2f, 100L, 200L, true, 3, "WIFI_ONLY", true, "ON_PLAYED", false, true, 60)

    private fun manifest() =
        BackupManifest(
            createdAt = "2026-10-17T00:00:00Z",
            kind = BackupKind.MANUAL,
            app = AppInfoV1(versionName = "0.1", versionCode = 1, abi = "arm64-v8a"),
            dbSchemaVersion = 12,
            episodeKeysVersion = 1,
            urlNormalizerVersion = 1,
            installationId = "inst-1",
            counts = CountsV1(0, 0, 0, 0, 0, 0, 0),
            entries = listOf(EntryInfo(BackupCodec.ENTRY_LIBRARY, 2, "ab", 1)),
        )

    private fun sourceOf(text: String): Source = Buffer().writeUtf8(text)

    @Test
    fun manifestRoundTripsWithEveryOptionalField() {
        val full =
            manifest().copy(
                kind = BackupKind.SCHEDULED,
                app = AppInfoV1("1.2", 34L, "x86_64", platform = "linux"),
                snapshotLevel = 2,
                includesCredentials = true,
            )
        assertEquals(full, codec.decodeManifest(sourceOf(codec.encodeManifest(full))))
    }

    @Test
    fun manifestAlwaysWritesFormatAndVersions() {
        // The sniff rule needs `format` in the file, so sparse encoding must still emit it.
        val encoded = codec.encodeManifest(manifest())
        assertTrue(encoded.contains("\"format\":\"neutrodyne-backup\""), encoded)
        assertTrue(encoded.contains("\"formatVersion\":1"), encoded)
        assertTrue(encoded.contains("\"minReaderVersion\":1"), encoded)
        assertFalse(encoded.contains("snapshotLevel"))
        assertFalse(encoded.contains("includesCredentials"))
    }

    @Test
    fun minReaderVersionIsItsOwnConstantNotFormatVersion() {
        // 05 Versioning: minReaderVersion is the oldest reader that restores the file
        // correctly and stays 1 across compatible formatVersion bumps, so the wire
        // default must not track FORMAT_VERSION — the next compatible bump would
        // otherwise lock out every v1 reader.
        assertEquals(1, BackupCodec.MIN_READER_VERSION)
        assertEquals(BackupCodec.MIN_READER_VERSION, manifest().minReaderVersion)
    }

    @Test
    fun manifestChecksFormatBeforeDecoding() {
        val encoded = codec.encodeManifest(manifest())
        assertFailsWith<BackupCodecException.NotABackup> {
            codec.decodeManifest(sourceOf(encoded.replace("\"neutrodyne-backup\"", "\"other\"")))
        }
        assertFailsWith<BackupCodecException.NotABackup> {
            codec.decodeManifest(sourceOf(encoded.replace("\"format\":\"neutrodyne-backup\",", "")))
        }
        assertFailsWith<BackupCodecException.NotABackup> {
            codec.decodeManifest(sourceOf(encoded.replace("\"neutrodyne-backup\"", "7")))
        }
    }

    @Test
    fun manifestRefusesNewerMinReaderVersionButNotNewerFormat() {
        val encoded = codec.encodeManifest(manifest())
        val newer =
            assertFailsWith<BackupCodecException.NewerFormat> {
                codec.decodeManifest(sourceOf(encoded.replace("\"minReaderVersion\":1", "\"minReaderVersion\":2")))
            }
        assertEquals(2, newer.minReaderVersion)
        // A newer formatVersion an old reader may still read: only minReaderVersion refuses.
        assertEquals(
            2,
            codec.decodeManifest(sourceOf(encoded.replace("\"formatVersion\":1", "\"formatVersion\":2"))).formatVersion,
        )
    }

    @Test
    fun anUndecodableNewerManifestStillReportsNewerFormat() {
        // 05 Versioning: "update the app" must win over "corrupt" exactly when a newer
        // writer changed the v1 shape — here minReaderVersion 2 plus a BackupKind name
        // and a missing required field that the v1 decode cannot survive.
        val encoded = codec.encodeManifest(manifest())
        val unknownKind =
            assertFailsWith<BackupCodecException.NewerFormat> {
                codec.decodeManifest(
                    sourceOf(
                        encoded
                            .replace("\"minReaderVersion\":1", "\"minReaderVersion\":2")
                            .replace("\"MANUAL\"", "\"WEEKLY\""),
                    ),
                )
            }
        assertEquals(2, unknownKind.minReaderVersion)
        val missingField =
            assertFailsWith<BackupCodecException.NewerFormat> {
                codec.decodeManifest(
                    sourceOf(
                        encoded
                            .replace("\"minReaderVersion\":1", "\"minReaderVersion\":7")
                            .replace("\"kind\":\"MANUAL\",", ""),
                    ),
                )
            }
        assertEquals(7, missingField.minReaderVersion)
        // A malformed minReaderVersion is no version claim at all: plain Corrupt.
        assertFailsWith<BackupCodecException.Corrupt> {
            codec.decodeManifest(
                sourceOf(encoded.replace("\"minReaderVersion\":1", "\"minReaderVersion\":\"x\"")),
            )
        }
    }

    @Test
    fun manifestRejectsOversizeAndCorruptInput() {
        val padded = codec.encodeManifest(manifest()).padEnd(BackupCodec.MANIFEST_MAX_BYTES.toInt() + 1)
        assertFailsWith<BackupCodecException.TooLarge> { codec.decodeManifest(sourceOf(padded)) }
        assertFailsWith<BackupCodecException.Corrupt> { codec.decodeManifest(sourceOf("not json")) }
        // Valid JSON but no string `format` member: NotABackup, not Corrupt.
        assertFailsWith<BackupCodecException.NotABackup> { codec.decodeManifest(sourceOf("[1,2]")) }
    }

    @Test
    fun manifestFormatProbeReadsOnlyTheFormatField() {
        assertEquals(BackupCodec.FORMAT, codec.manifestFormat(sourceOf(codec.encodeManifest(manifest()))))
        // A foreign manifest's value is reported, not hidden: the caller compares it.
        assertEquals("other", codec.manifestFormat(sourceOf("{\"format\":\"other\"}")))
        assertNull(codec.manifestFormat(sourceOf("{}")))
        assertNull(codec.manifestFormat(sourceOf("not json")))
        assertNull(codec.manifestFormat(sourceOf("{\"format\":5}")))
        val oversized = "{}".padEnd(BackupCodec.MANIFEST_MAX_BYTES.toInt() + 1)
        assertNull(codec.manifestFormat(sourceOf(oversized)))
    }

    @Test
    fun libraryRoundTripsWithEveryOptionalField() {
        val library =
            LibraryV1(
                podcasts =
                    listOf(
                        PodcastV1(
                            key = "k",
                            feedUrl = "https://f",
                            source = "YOUTUBE_CHANNEL",
                            aliases = listOf("a"),
                            podcastGuid = "guid",
                            youtubeChannelId = "UCx",
                            youtubeVariants = 2,
                            title = "T",
                            customTitle = "CT",
                            artworkUrl = "https://a",
                            link = "https://l",
                            subscribedAt = 7L,
                            includeInAll = false,
                            episodeOrder = "OLDEST_FIRST",
                            settings = overrides,
                            credentialOrigin = "https://f",
                            syncId = "sync",
                            guidAmbiguous = listOf("g1", "g2"),
                        ),
                    ),
                groups =
                    listOf(
                        GroupV1(
                            uuid = "u",
                            name = "g",
                            sortOrder = 0,
                            orderKey = "ok",
                            colorArgb = 0xFF,
                            iconKey = "ic",
                            kind = "SMART",
                            rule = JsonPrimitive(4),
                            feedOrder = "OLDEST_FIRST",
                            playOrder = "SHUFFLED",
                            filterFlags = 3,
                            mediaFilter = "AUDIO",
                            hideOlderThanDays = 5,
                            showAsTab = false,
                            createdAt = 9L,
                            settings = overrides,
                            members = listOf(MemberV1("k", addedAt = 1L, orderKey = "mo")),
                        ),
                    ),
                credentials = listOf(CredentialV1("o", "u", "p")),
            )
        val roundTrip = codec.decodeLibrary(sourceOf(codec.encodeLibrary(library)))
        assertEquals(library, roundTrip)
        assertEquals(overrides, roundTrip.groups[0].settings)
        assertEquals("sync", roundTrip.podcasts[0].syncId)
        assertEquals("ok", roundTrip.groups[0].orderKey)
        assertEquals("mo", roundTrip.groups[0].members[0].orderKey)
    }

    @Test
    fun sparseEncodingOmitsAbsentOptionalsAndNulls() {
        val encoded =
            codec.encodeLibrary(
                LibraryV1(
                    podcasts = listOf(PodcastV1(key = "k", feedUrl = "u", title = "t", subscribedAt = 1)),
                    groups = emptyList(),
                ),
            )
        assertFalse(encoded.contains("syncId"))
        assertFalse(encoded.contains("podcastGuid"))
        assertFalse(encoded.contains("null"))
        assertFalse(encoded.contains("credentials"))
    }

    @Test
    fun unknownFieldsAreIgnoredEverywhere() {
        val encoded = codec.encodeManifest(manifest())
        val withUnknown = encoded.replaceRange(0, 1, "{\"extra\":[1,{\"deep\":null}],")
        assertEquals(manifest(), codec.decodeManifest(sourceOf(withUnknown)))

        val queue =
            codec.decodeQueue(
                sourceOf(
                    """{"zzz":true,"upNext":[{"p":"a","k":"b","kv":2,"extra":5}],"session":{"current":{"p":"a","k":"b","x":{}}}}""",
                ),
            )
        assertEquals(UpNextRefV1("a", "b", 2), queue.upNext[0])
    }

    @Test
    fun deepUnknownFieldsAreSkippedNotMaterialised() {
        // Forward compatibility (05 Archive): unknown members skip iteratively, so a field
        // nested beyond any recursive reader's reach is still ignored — never a crash.
        val withDeep = codec.encodeManifest(manifest()).dropLast(1) + ",\"future\":" + deepArray + "}"
        assertEquals(manifest(), codec.decodeManifest(sourceOf(withDeep)))
        // The version gate still decides before the v1 decode reaches the unknown field.
        val newer =
            assertFailsWith<BackupCodecException.NewerFormat> {
                codec.decodeManifest(sourceOf(withDeep.replace("\"minReaderVersion\":1", "\"minReaderVersion\":2")))
            }
        assertEquals(2, newer.minReaderVersion)
        assertEquals(
            LibraryV1(emptyList(), emptyList()),
            codec.decodeLibrary(sourceOf("{\"podcasts\":[],\"groups\":[],\"future\":$deepArray}")),
        )
        assertEquals(
            QueueV1(emptyList()),
            codec.decodeQueue(sourceOf("{\"upNext\":[],\"future\":$deepArray}")),
        )
        assertEquals(
            SettingsV1(emptyList()),
            codec.decodeSettings(sourceOf("{\"values\":[],\"future\":$deepArray}")),
        )
    }

    @Test
    fun manifestFormatReadsFormatPastDeepUnknownFields() {
        assertEquals(
            BackupCodec.FORMAT,
            codec.manifestFormat(sourceOf("{\"format\":\"${BackupCodec.FORMAT}\",\"future\":$deepArray}")),
        )
        // A `format` that is itself a deep container is "no string format", not a crash.
        assertNull(codec.manifestFormat(sourceOf("{\"format\":$deepArray}")))
        assertFailsWith<BackupCodecException.NotABackup> {
            codec.decodeManifest(sourceOf("{\"format\":$deepArray}"))
        }
    }

    @Test
    fun elementFieldsBeyondTheTreeReadersDepthAreCorrupt() {
        // `rule` and `value` stay JsonElement/JsonPrimitive typed: their values still pass
        // through the tree reader, so a container beyond its stack depth is a shape the
        // DTOs reject — Corrupt, never the Error.
        assertFailsWith<BackupCodecException.Corrupt> {
            codec.decodeSettings(
                sourceOf("{\"values\":[{\"key\":\"k\",\"type\":\"Bool\",\"value\":$deepArray}]}"),
            )
        }
        assertFailsWith<BackupCodecException.Corrupt> {
            codec.decodeLibrary(
                sourceOf(
                    "{\"podcasts\":[],\"groups\":[{\"uuid\":\"u\",\"name\":\"g\",\"sortOrder\":0,\"rule\":$deepArray}]}",
                ),
            )
        }
    }

    @Test
    fun manifestGateOrderHoldsForScalarsAndMalformedInput() {
        // A well-formed non-object has no `format` member (NotABackup); malformed JSON is
        // Corrupt (05 Versioning's gate order).
        assertFailsWith<BackupCodecException.NotABackup> { codec.decodeManifest(sourceOf("123")) }
        assertFailsWith<BackupCodecException.NotABackup> { codec.decodeManifest(sourceOf("true")) }
        assertFailsWith<BackupCodecException.Corrupt> { codec.decodeManifest(sourceOf("[1,")) }
        assertFailsWith<BackupCodecException.Corrupt> {
            codec.decodeManifest(
                sourceOf("{\"format\":\"neutrodyne-backup\""),
            )
        }
    }

    @Test
    fun libraryRejectsOversizeAndCorruptInput() {
        val big = " ".repeat(BackupCodec.LIBRARY_MAX_BYTES.toInt() + 1)
        assertFailsWith<BackupCodecException.TooLarge> { codec.decodeLibrary(sourceOf(big)) }
        assertFailsWith<BackupCodecException.Corrupt> { codec.decodeLibrary(sourceOf("{")) }
    }

    @Test
    fun queueAndSettingsRoundTrip() {
        val queue =
            QueueV1(
                upNext = listOf(UpNextRefV1("p1", "k1", 2, "ok1")),
                session =
                    SessionV1(
                        current = EpisodeRefV1("p2", "k2"),
                        contextType = "GROUP",
                        contextGroupUuid = "gu",
                        order = "OLDEST_FIRST",
                        filterFlags = 1,
                        mediaFilter = "VIDEO",
                        minSortDate = 3L,
                        anchor = EpisodeRefV1("p3", "k3", 2),
                        anchorSortDate = 4L,
                    ),
            )
        assertEquals(queue, codec.decodeQueue(sourceOf(codec.encodeQueue(queue))))

        val settings =
            SettingsV1(
                listOf(
                    SettingValueV1("theme", "Theme", value = JsonPrimitive("DARK")),
                    SettingValueV1("limit", "Limit", value = JsonPrimitive(5)),
                    SettingValueV1("feeds", "Feeds", values = listOf("a", "b")),
                    SettingValueV1("empty", "Empty"),
                ),
            )
        assertEquals(settings, codec.decodeSettings(sourceOf(codec.encodeSettings(settings))))
    }

    @Test
    fun anOldEpisodeRefArrayDecodesAsUpNext() {
        // EpisodeRefV1-shaped entries (no ok) remain readable as UpNextRefV1.
        val queue = codec.decodeQueue(sourceOf("""{"upNext":[{"p":"a","k":"b","kv":1}]}"""))
        assertEquals(listOf(UpNextRefV1("a", "b", 1)), queue.upNext)
        assertNull(queue.session)
    }

    @Test
    fun archiveModeStripsTheStagingOnlyFieldClock() {
        val line = line().copy(fc = mapOf("pl" to 100L, "pos" to 200L))
        val encoded = codec.encodeEpisodeLine(line)
        assertFalse(encoded.contains("\"fc\""), encoded)
        assertNull(decodeAll(encoded).single().fc)
        // Staging mode keeps it: the field clock survives the first-link hop.
        assertEquals(line, decodeAll(stagingCodec.encodeEpisodeLine(line)).single())
    }

    @Test
    fun episodeLineRoundTripsEveryField() {
        assertEquals(line(), decodeAll(codec.encodeEpisodeLine(line())).single())
    }

    @Test
    fun episodeLinesStreamAndCountOversizedAndUndecodableLines() {
        val fat = "x".repeat(BackupCodec.EPISODE_LINE_MAX_BYTES.toInt())
        val payload =
            listOf(
                """{"p":"a","k":"b","ts":0}""",
                """{"p":"x","k":"y","t":"$fat","ts":0}""",
                "not json",
                """{"p":1}""",
                """{"p":"c","k":"d","ts":5}""",
            ).joinToString("\n") + "\n"
        val lines = codec.episodeLines(sourceOf(payload))
        val decoded = mutableListOf<EpisodeLineV1>()
        lines.use { l -> for (line in l) decoded += line }
        assertEquals(listOf("b", "d"), decoded.map { it.k })
        assertEquals(listOf(0L, 5L), decoded.map { it.ts })
        assertEquals(3, lines.skippedLines)
    }

    @Test
    fun episodeLineAtTheCapDecodesOneBytePastItSkips() {
        val prefix = """{"p":"a","k":"b","t":""""
        val suffix = """","ts":0}"""
        val pad = "x".repeat(BackupCodec.EPISODE_LINE_MAX_BYTES.toInt() - prefix.length - suffix.length)
        val exact = prefix + pad + suffix
        val payload = exact + "\n" + exact + "x" + "\n" + exact + "\n"
        val lines = codec.episodeLines(sourceOf(payload))
        var decoded = 0
        lines.use { l -> for (line in l) decoded++ }
        assertEquals(2, decoded)
        assertEquals(1, lines.skippedLines)
    }

    @Test
    fun episodeLinesSkipMalformedUtf8() {
        // Raw FF FE inside a string is undecodable UTF-8; 05 Validation counts the line
        // as skipped rather than importing its replacement characters.
        val payload =
            Buffer()
                .writeUtf8("{\"p\":\"a\",\"k\":\"b\",\"t\":\"")
                .write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
                .writeUtf8("\",\"ts\":0}\n")
                .writeUtf8("{\"p\":\"c\",\"k\":\"d\",\"ts\":5}\n")
        val lines = codec.episodeLines(payload)
        val decoded = mutableListOf<EpisodeLineV1>()
        lines.use { l -> for (line in l) decoded += line }
        assertEquals(listOf("d"), decoded.map { it.k })
        assertEquals(1, lines.skippedLines)
    }

    @Test
    fun validReplacementCharactersDoNotDiscardEpisodeState() {
        val expected = EpisodeLineV1(p = "example.com/rss", k = "episode-1", t = "Episode \uFFFD", pl = 5L, ts = 10L)
        val encoded = codec.encodeEpisodeLine(expected)
        for (ending in listOf("\n", "\r\n", "")) {
            val lines = codec.episodeLines(sourceOf(encoded + ending))
            val decoded = mutableListOf<EpisodeLineV1>()
            lines.use { stream -> for (line in stream) decoded += line }

            assertEquals(listOf(expected), decoded, "valid Unicode must round-trip for ending=$ending")
            assertEquals(0, lines.skippedLines)
        }
    }

    @Test
    fun episodeLinesHandleMissingTerminatorCrlfAndBlankLines() {
        val payload = "{\"p\":\"a\",\"k\":\"b\",\"ts\":0}\r\n\r\n{\"p\":\"c\",\"k\":\"d\",\"ts\":5}"
        val lines = codec.episodeLines(sourceOf(payload))
        val decoded = mutableListOf<EpisodeLineV1>()
        lines.use { l -> for (line in l) decoded += line }
        assertEquals(listOf("b", "d"), decoded.map { it.k })
        assertEquals(1, lines.skippedLines) // the blank middle line counts as undecodable
    }

    @Test
    fun anEmptyEpisodeFileYieldsNoLines() {
        codec.episodeLines(sourceOf("")).use { assertFalse(it.hasNext()) }
    }

    @Test
    fun closingDropsPendingAndPrefetchedLines() {
        val payload = "{\"p\":\"a\",\"k\":\"b\",\"ts\":0}\n{\"p\":\"c\",\"k\":\"d\",\"ts\":5}\n"
        // A consumed-then-closed iterator must not replay the last returned line.
        val consumed = codec.episodeLines(sourceOf(payload))
        assertEquals("b", consumed.next().k)
        consumed.close()
        assertFalse(consumed.hasNext())
        assertFailsWith<NoSuchElementException> { consumed.next() }
        // A prefetched-but-unconsumed line dies with the reader too.
        val prefetched = codec.episodeLines(sourceOf(payload))
        assertTrue(prefetched.hasNext())
        prefetched.close()
        assertFalse(prefetched.hasNext())
        assertFailsWith<NoSuchElementException> { prefetched.next() }
    }

    @Test
    fun entryNamesAreExactlyTheBackupWhitelist() {
        assertEquals(
            setOf(
                "manifest.json",
                "library.json",
                "episodes.jsonl",
                "queue.json",
                "settings.json",
                "subscriptions.opml",
            ),
            BackupCodec.ENTRY_NAMES,
        )
    }

    private fun line(
        p: String = "feed",
        k: String = "key",
        ts: Long = 9L,
    ) = EpisodeLineV1(
        p = p,
        k = k,
        g = "guid",
        t = "title 🎧",
        d = 1L,
        u = "https://x/a.mp3",
        ty = "audio/mpeg",
        dur = 60L,
        yt = "vid",
        l = "https://page",
        pl = 2L,
        pc = 3,
        st = 4L,
        lp = 5L,
        pos = 6L,
        posAt = 7L,
        posSrc = "LOCAL",
        fav = true,
        dis = 8L,
        dl = true,
        md = 61L,
        ts = ts,
    )

    private companion object {
        const val DEEP_ARRAYS = 50_000
    }

    private fun decodeAll(text: String): List<EpisodeLineV1> {
        val out = mutableListOf<EpisodeLineV1>()
        codec.episodeLines(sourceOf(text)).use { it.forEachRemaining(out::add) }
        return out
    }
}
