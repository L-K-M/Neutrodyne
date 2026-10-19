// SPDX-License-Identifier: Unlicense
@file:Suppress("ktlint:standard:max-line-length")

package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.feeds.backup.AppInfoV1
import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriterFactory
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.backup.BackupManifest
import ch.lkmc.neutrodyne.feeds.backup.CountsV1
import ch.lkmc.neutrodyne.feeds.backup.EntryInfo
import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.EpisodeRefV1
import ch.lkmc.neutrodyne.feeds.backup.GroupV1
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.MemberV1
import ch.lkmc.neutrodyne.feeds.backup.OverridesV1
import ch.lkmc.neutrodyne.feeds.backup.PodcastV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SessionV1
import ch.lkmc.neutrodyne.feeds.backup.SettingValueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import ch.lkmc.neutrodyne.feeds.backup.UpNextRefV1
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.opml.ExportDocument
import ch.lkmc.neutrodyne.feeds.opml.ExportFeed
import ch.lkmc.neutrodyne.feeds.opml.ExportGroup
import ch.lkmc.neutrodyne.feeds.opml.OpmlLayout
import ch.lkmc.neutrodyne.feeds.opml.OpmlWriter
import ch.lkmc.neutrodyne.sync.protocol.EpisodeProgress
import ch.lkmc.neutrodyne.sync.protocol.EpisodeStateRules
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.BufferedSink
import okio.HashingSink
import okio.Path.Companion.toPath
import okio.buffer
import okio.source
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile

internal class AccountArchiveWriter(
    private val inputs: ServerInputs,
    private val archives: ArchiveWriterFactory = ServerZipWriter,
) : AccountBackupWriter {
    private val codec = BackupCodec()

    override suspend fun export(
        snapshot: AccountSnapshot,
        destination: Path,
    ): Path =
        withContext(Dispatchers.IO) {
            privateDirectory(destination.toAbsolutePath().parent)
            val temporary = destination.resolveSibling(".${destination.fileName}.${UUID.randomUUID()}.tmp")
            try {
                write(snapshot, temporary)
                // Validate the finished ZIP and its exact entry receipts before publishing it.
                ZipFile(temporary.toFile()).use { zip ->
                    val manifest =
                        zip.getInputStream(zip.getEntry("manifest.json")).use {
                            codec.decodeManifest(it.source())
                        }
                    check(zip.size() == manifest.entries.size + 1)
                    manifest.entries.forEach { entry ->
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        zip.getInputStream(zip.getEntry(entry.name)).use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                count += size
                                digest.update(buffer, 0, size)
                            }
                        }
                        check(count == entry.bytes && digest.digest().hex() == entry.sha256)
                    }
                }
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                destination
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

    private fun write(
        snapshot: AccountSnapshot,
        path: Path,
    ) {
        val now = inputs.wallMs()
        // Only bounded library/list metadata is materialized. Episode history is streamed separately.
        val metadata = snapshot.records.filter { it.key.collection != SyncCollection.EPISODE }.toList()
        val podcasts =
            metadata
                .filter { it.key.collection == SyncCollection.PODCAST && it.live() }
                .sortedBy { it.key.id.rid }
                .mapNotNull(::podcast)
        val keys = podcasts.associate { checkNotNull(it.syncId) to it.key }
        val members = metadata.filter { it.key.collection == SyncCollection.MEMBER && it.live() }
        val groups =
            metadata
                .filter { it.key.collection == SyncCollection.GROUP && it.live() }
                .sortedWith(compareBy({ it.text("ok").orEmpty() }, { it.key.id.rid }))
                .mapIndexed { index, row ->
                    GroupV1(
                        uuid = row.key.id.rid,
                        name = row.text("name") ?: "Untitled group",
                        sortOrder = index,
                        orderKey = row.text("ok"),
                        colorArgb = row.long("colorArgb")?.toInt(),
                        iconKey = row.text("iconKey"),
                        kind = row.text("kind") ?: "MANUAL",
                        rule =
                            row.raw.fields["rule"]
                                ?.v
                                ?.takeUnless { it == JsonNull },
                        feedOrder = row.text("feedOrder") ?: "NEWEST_FIRST",
                        playOrder =
                            row.text("playOrder") ?: "NEWEST_FIRST",
                        filterFlags = row.long("filterFlags")?.toInt() ?: 0,
                        mediaFilter = row.text("mediaFilter") ?: "ALL",
                        hideOlderThanDays = row.long("hideOlderThanDays")?.toInt(),
                        showAsTab =
                            row.bool("showAsTab") ?: true,
                        createdAt = row.long("createdAt"),
                        settings = overrides(row),
                        members =
                            members
                                .filter { (it.key.id as RecordId.Member).group.value == row.key.id.rid }
                                .sortedWith(compareBy({ it.text("ok").orEmpty() }, { it.key.id.rid }))
                                .mapNotNull {
                                    val id = it.key.id as RecordId.Member
                                    keys[id.podcast.value]?.let { key ->
                                        MemberV1(key, it.long("addedAt"), it.text("ok"))
                                    }
                                },
                    )
                }
        val settings =
            metadata
                .filter { it.key.collection == SyncCollection.SETTING && it.live() }
                .sortedBy { it.key.id.rid }
                .mapNotNull { row ->
                    val type = SYNCED_SETTINGS[row.key.id.rid] ?: return@mapNotNull null
                    val value = row.raw.fields["v"]?.v as? JsonPrimitive ?: return@mapNotNull null
                    SettingValueV1(row.key.id.rid, type, value)
                }
        var lines = 0
        var played = 0
        var progressing = 0
        val allowedQueue = mutableSetOf<String>()
        val wantedQueue =
            metadata
                .filter { it.key.collection == SyncCollection.UPNEXT && it.live() }
                .associateBy { it.key.id.rid }
        val seenQueueEpisodes = mutableSetOf<String>()
        val entries = mutableListOf<EntryInfo>()
        archives.create(path.toString().toPath()).use { zip ->
            fun entry(
                name: String,
                records: () -> Int,
                write: (BufferedSink) -> Unit,
            ) {
                val hashing = HashingSink.sha256(zip.entry(name))
                var bytes = 0L
                val counting =
                    object : okio.ForwardingSink(hashing) {
                        override fun write(
                            source: okio.Buffer,
                            byteCount: Long,
                        ) {
                            super.write(source, byteCount)
                            bytes += byteCount
                        }
                    }
                counting.buffer().use(write)
                entries += EntryInfo(name, bytes, hashing.hash.hex(), records())
            }
            entry("episodes.jsonl", { lines }) { sink ->
                snapshot.records
                    .filter { it.key.collection == SyncCollection.EPISODE && it.live() }
                    .forEach { row ->
                        val id = row.key.id as RecordId.Episode
                        val parent = keys[id.podcast.value] ?: return@forEach
                        if (id.rid in wantedQueue) seenQueueEpisodes += id.rid
                        val effective = EpisodeStateRules.derive(row.raw.fields)
                        if (effective.allowsUpNext && id.rid in wantedQueue) allowedQueue += id.rid
                        if (effective.state == EpisodeProgress.PLAYED) played++
                        if (effective.state == EpisodeProgress.IN_PROGRESS) progressing++
                        val pos = row.raw.fields["pos"]
                        val position = pos?.v as? JsonObject
                        val hint = row.raw.match
                        codec.writeEpisodeLine(
                            sink,
                            EpisodeLineV1(
                                p = parent,
                                k = id.key.value,
                                g = hint?.guid,
                                t = hint?.title ?: "Untitled episode",
                                d =
                                    hint?.date?.let {
                                        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                                    },
                                u = hint?.enc,
                                yt = hint?.ytId,
                                l = hint?.link,
                                pl = effective.playedAt,
                                pc = row.long("playCount")?.toInt() ?: 0,
                                st = effective.startedAt,
                                lp = row.long("lastPlayedAt"),
                                pos = effective.positionMs,
                                posAt = pos?.c?.millis,
                                posSrc = (position?.get("src") as? JsonPrimitive)?.contentOrNull,
                                fav = effective.favourite,
                                md = row.long("measuredDurationMs"),
                                ts =
                                    row.raw.fields
                                        .filterKeys { it != "pos" }
                                        .values
                                        .mapNotNull { it.c?.millis }
                                        .maxOrNull()
                                        ?: 0,
                            ),
                        )
                        lines++
                    }
                wantedQueue.values.filter { it.key.id.rid !in seenQueueEpisodes }.forEach { row ->
                    val id = row.key.id as RecordId.Episode
                    val parent = keys[id.podcast.value] ?: return@forEach
                    codec.writeEpisodeLine(
                        sink,
                        EpisodeLineV1(p = parent, k = id.key.value, t = "Untitled episode", ts = 0),
                    )
                    lines++
                }
            }
            // Sparse Up next records can precede their episode state.
            val queue =
                wantedQueue.values
                    .sortedWith(compareBy({ it.text("ok").orEmpty() }, { it.key.id.rid }))
                    .filter { it.key.id.rid in allowedQueue || it.key.id.rid !in seenQueueEpisodes }
                    .mapNotNull { row ->
                        val id = row.key.id as RecordId.Episode
                        keys[id.podcast.value]?.let { UpNextRefV1(it, id.key.value, ok = row.text("ok")) }
                    }
            val session =
                metadata.firstOrNull { it.key.collection == SyncCollection.SESSION }?.let { row ->
                    val context = row.raw.fields["context"]?.v as? JsonObject

                    fun ref(value: JsonElement?): EpisodeRefV1? {
                        val obj = value as? JsonObject ?: return null
                        val p = (obj["p"] as? JsonPrimitive)?.content ?: return null
                        val k = (obj["k"] as? JsonPrimitive)?.content ?: return null
                        return keys[p]?.let { EpisodeRefV1(it, k) }
                    }
                    val filters = context?.get("filters") as? JsonObject
                    SessionV1(
                        current = ref(row.raw.fields["episode"]?.v),
                        anchor = ref(context?.get("anchor")),
                        contextType = context?.get("type")?.jsonPrimitive?.contentOrNull,
                        contextGroupUuid =
                            context?.get("group")?.jsonPrimitive?.contentOrNull?.takeIf { id ->
                                groups.any {
                                    it.uuid ==
                                        id
                                }
                            },
                        contextPodcastKey = keys[context?.get("podcast")?.jsonPrimitive?.contentOrNull],
                        order = context?.get("order")?.jsonPrimitive?.contentOrNull ?: "NEWEST_FIRST",
                        filterFlags = filters?.get("flags")?.jsonPrimitive?.intOrNull ?: 0,
                        mediaFilter = filters?.get("media")?.jsonPrimitive?.contentOrNull ?: "ALL",
                        minSortDate = context?.get("minSortDate")?.jsonPrimitive?.longOrNull,
                        anchorSortDate = context?.get("anchorSortDate")?.jsonPrimitive?.longOrNull,
                    )
                }
            entry("library.json", {
                podcasts.size + groups.size
            }) { it.writeUtf8(codec.encodeLibrary(LibraryV1(podcasts, groups))) }
            entry("queue.json", { queue.size }) { it.writeUtf8(codec.encodeQueue(QueueV1(queue, session))) }
            entry("settings.json", { settings.size }) { it.writeUtf8(codec.encodeSettings(SettingsV1(settings))) }
            entry("subscriptions.opml", { podcasts.size }) { sink ->
                OpmlWriter.write(
                    sink,
                    ExportDocument(
                        "Neutrodyne subscriptions",
                        now,
                        groups.map { ExportGroup(it.uuid, it.name, it.colorArgb, it.iconKey) },
                        podcasts.map { p ->
                            ExportFeed(
                                p.customTitle ?: p.title,
                                p.title,
                                p.feedUrl,
                                p.link,
                                null,
                                groups.filter { g -> g.members.any { it.podcastKey == p.key } }.map { it.uuid },
                                p.source == "YOUTUBE",
                                p.youtubeVariants,
                            )
                        },
                    ),
                    OpmlLayout.GROUPED,
                )
            }
            val manifest =
                BackupManifest(
                    createdAt = Instant.ofEpochMilli(now).toString(),
                    kind = BackupKind.SCHEDULED,
                    app = AppInfoV1(ServerVersion.current(), 0, "server", "server"),
                    dbSchemaVersion = snapshot.schemaVersion,
                    episodeKeysVersion = 1,
                    urlNormalizerVersion = 1,
                    installationId = snapshot.installationId,
                    counts =
                        CountsV1(
                            podcasts.size,
                            groups.size,
                            lines,
                            played,
                            progressing,
                            queue.size,
                            settings.size,
                        ),
                    entries = entries,
                )
            zip.entry("manifest.json").buffer().use { it.writeUtf8(codec.encodeManifest(manifest)) }
        }
    }

    private fun podcast(row: StoredRecord): PodcastV1? {
        val url = row.text("feedUrl") ?: return null
        val key = UrlNormalizer.forIdentity(url) ?: return null
        return PodcastV1(
            key,
            url,
            source = row.text("sourceType") ?: "RSS",
            aliases =
                row.raw.growSets["feedKeys"]
                    .orEmpty()
                    .sorted(),
            title = row.text("customTitle") ?: row.text("title") ?: "Untitled podcast",
            customTitle = row.text("customTitle"),
            subscribedAt = row.long("subscribedAt") ?: 0,
            includeInAll = row.bool("includeInAll") ?: true,
            episodeOrder = row.text("episodeOrder"),
            artworkUrl = row.text("artworkUrl"),
            link = row.text("link"),
            podcastGuid = row.text("podcastGuid"),
            youtubeChannelId = row.text("youtubeChannelId"),
            youtubeVariants = row.long("youtubeVariants")?.toInt(),
            credentialOrigin = row.text("credentialOrigin"),
            settings = overrides(row),
            syncId = row.key.id.rid,
        )
    }

    private fun overrides(row: StoredRecord): OverridesV1? {
        if (row.raw.fields.keys
                .none { it.startsWith("s.") }
        ) {
            return null
        }
        return OverridesV1(
            playbackSpeed = (row.raw.fields["s.playbackSpeed"]?.v as? JsonPrimitive)?.floatOrNull,
            skipSilence = row.bool("s.skipSilence"),
            boostDb = (row.raw.fields["s.boostDb"]?.v as? JsonPrimitive)?.floatOrNull,
            introSkipMs = row.long("s.introSkipMs"),
            outroSkipMs = row.long("s.outroSkipMs"),
        )
    }

    private companion object {
        val SYNCED_SETTINGS =
            mapOf(
                "playback.speed" to "Float32",
                "playback.skip_silence" to "Bool",
                "playback.speed_presets" to "Text",
                "playback.skip_back_ms" to "Int64",
                "playback.skip_forward_ms" to "Int64",
                "groups.show_ungrouped_tab" to "Bool",
                "groups.all_filter_flags" to "Int32",
                "groups.all_media_filter" to "Choice",
                "groups.all_hide_older_than_days" to "Int32",
                "groups.ungrouped_filter_flags" to "Int32",
                "groups.ungrouped_media_filter" to "Choice",
                "groups.ungrouped_hide_older_than_days" to "Int32",
                "feeds.backfill_paged_feeds" to "Bool",
                "feeds.show_notes_images" to "Choice",
            )
    }
}

internal fun StoredRecord.live(): Boolean = !RecordMerger.isDead(raw)

internal fun StoredRecord.text(name: String): String? =
    (raw.fields[name]?.v as? JsonPrimitive)
        ?.takeUnless {
            it ==
                JsonNull
        }?.contentOrNull

internal fun StoredRecord.long(name: String): Long? = (raw.fields[name]?.v as? JsonPrimitive)?.longOrNull

internal fun StoredRecord.bool(name: String): Boolean? = (raw.fields[name]?.v as? JsonPrimitive)?.booleanOrNull

private fun ByteArray.hex(): String = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
