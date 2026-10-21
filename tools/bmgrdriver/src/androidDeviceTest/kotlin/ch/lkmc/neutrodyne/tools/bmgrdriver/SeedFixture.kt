// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import ch.lkmc.neutrodyne.feeds.backup.AppInfoV1
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
import ch.lkmc.neutrodyne.feeds.backup.PodcastV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SessionV1
import ch.lkmc.neutrodyne.feeds.backup.SettingValueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import ch.lkmc.neutrodyne.feeds.backup.UpNextRefV1
import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Builds the bmgr fixtures in the *driver's* process with the real `:feeds` codec — the seed
// library (two feeds, two groups, four episodes, Up next, play session, GROUPED settings), the
// settings-only FLAT flip archive, and the hand-crafted encrypted blob. SeedZipWriter is the
// bounded `java.util.zip` writer the corrected plan prescribes when `:feeds:jvm` is not on the
// Android device-test classpath; it is used for fixture input only and never in the target.

/** Everything the host checker needs, verbatim — recorded in `seed.expected.json`. */
@Serializable
internal data class FixturePodcast(
    val key: String,
    val feedUrl: String,
    val title: String,
    val link: String,
    val syncId: String,
    val subscribedAt: Long,
)

@Serializable
internal data class FixtureGroup(
    val uuid: String,
    val name: String,
    val sortOrder: Int,
    val members: List<String>,
)

@Serializable
internal data class FixtureEpisode(
    val p: String,
    val k: String,
    val g: String,
    val t: String,
    val d: Long,
    val u: String,
    val ty: String,
    val dur: Long,
    val l: String,
    val pl: Long? = null,
    val pc: Int = 0,
    val st: Long? = null,
    val lp: Long? = null,
    val pos: Long? = null,
    val posAt: Long? = null,
    val posSrc: String? = null,
    val fav: Boolean = false,
    val md: Long? = null,
    val ts: Long,
)

@Serializable
internal data class FixtureRef(
    val p: String,
    val k: String,
)

@Serializable
internal data class FixtureSession(
    val current: FixtureRef?,
    val contextType: String?,
    val contextGroupUuid: String?,
    val order: String,
)

@Serializable
internal data class FixtureContent(
    val podcasts: List<FixturePodcast>,
    val groups: List<FixtureGroup>,
    val episodes: List<FixtureEpisode>,
    val queue: List<FixtureRef>,
    val session: FixtureSession?,
    val seedSettings: Map<String, String>,
    val flipSettings: Map<String, String>,
)

@Serializable
internal data class SeedSidecar(
    val v: Int = 1,
    val nonce: String,
    val api: Int,
    val seedSha256: String,
    val seedBytes: Long,
    val fixture: FixtureContent,
)

internal object SeedFixture {
    /** `NeutrodyneDatabase.VERSION` — the driver must not depend on :app/:core:database. If the
     * schema bumps, this constant moves with it; a stale value fails the run loudly. */
    private const val DB_SCHEMA_VERSION = 2

    private const val INSTALLATION = "install-seed"
    private const val SETTING_OPML_LAYOUT = "backup.opml_layout"
    private const val SETTING_AUTO_SNAPSHOT = "backup.auto_snapshot_enabled"

    private val json = Json { prettyPrint = false }

    /** Seed content with every id nonce-bound so cross-run reuse is impossible. */
    fun content(nonce: String): FixtureContent {
        val day = 86_400_000L
        val base = 1_700_000_000_000L
        val feedUrlA = "https://alpha.example.test/$nonce/feed.xml"
        val feedUrlB = "https://beta.example.test/$nonce/feed.xml"
        val podcastA =
            FixturePodcast(
                key = UrlNormalizer.forIdentity(feedUrlA)!!,
                feedUrl = feedUrlA,
                title = "Alpha Cast $nonce",
                link = "https://alpha.example.test/$nonce",
                syncId = uuid("sync-$nonce-a"),
                subscribedAt = base,
            )
        val podcastB =
            FixturePodcast(
                key = UrlNormalizer.forIdentity(feedUrlB)!!,
                feedUrl = feedUrlB,
                title = "Beta Show $nonce",
                link = "https://beta.example.test/$nonce",
                syncId = uuid("sync-$nonce-b"),
                subscribedAt = base + 1_000,
            )
        val groupA =
            FixtureGroup(
                uuid = UUID.randomUUID().toString(),
                name = "Aviation $nonce",
                sortOrder = 0,
                members = listOf(podcastA.key),
            )
        val groupB =
            FixtureGroup(
                uuid = UUID.randomUUID().toString(),
                name = "Boredom $nonce",
                sortOrder = 1,
                members = listOf(podcastA.key, podcastB.key),
            )
        val episodes =
            listOf(
                // played + favourite + measured duration
                FixtureEpisode(
                    p = podcastA.key,
                    k = "epk-$nonce-a1",
                    g = "guid-$nonce-a1",
                    t = "Alpha Episode One $nonce",
                    d = base - 4 * day,
                    u = "https://alpha.example.test/$nonce/ep1.mp3",
                    ty = "audio/mpeg",
                    dur = 3_600_000,
                    l = "https://alpha.example.test/$nonce/ep1",
                    pl = base - day,
                    pc = 1,
                    lp = base - day + 60_000,
                    fav = true,
                    md = 3_580_000,
                    ts = base - day + 60_000,
                ),
                // in progress (position + started + last played)
                FixtureEpisode(
                    p = podcastA.key,
                    k = "epk-$nonce-a2",
                    g = "guid-$nonce-a2",
                    t = "Alpha Episode Two $nonce",
                    d = base - 3 * day,
                    u = "https://alpha.example.test/$nonce/ep2.mp3",
                    ty = "audio/mpeg",
                    dur = 3_600_000,
                    l = "https://alpha.example.test/$nonce/ep2",
                    st = base - 20_000,
                    lp = base - 10_000,
                    pos = 750_000,
                    posAt = base - 10_000,
                    posSrc = "STREAM",
                    ts = base - 10_000,
                ),
                // queued, otherwise clean
                FixtureEpisode(
                    p = podcastB.key,
                    k = "epk-$nonce-b1",
                    g = "guid-$nonce-b1",
                    t = "Beta Episode One $nonce",
                    d = base - 2 * day,
                    u = "https://beta.example.test/$nonce/ep1.mp3",
                    ty = "audio/mpeg",
                    dur = 1_800_000,
                    l = "https://beta.example.test/$nonce/ep1",
                    ts = 0,
                ),
                // played twice
                FixtureEpisode(
                    p = podcastB.key,
                    k = "epk-$nonce-b2",
                    g = "guid-$nonce-b2",
                    t = "Beta Episode Two $nonce",
                    d = base - day,
                    u = "https://beta.example.test/$nonce/ep2.mp3",
                    ty = "audio/mpeg",
                    dur = 1_800_000,
                    l = "https://beta.example.test/$nonce/ep2",
                    pl = base - 5_000,
                    pc = 2,
                    lp = base - 4_000,
                    ts = base - 4_000,
                ),
            )
        return FixtureContent(
            podcasts = listOf(podcastA, podcastB),
            groups = listOf(groupA, groupB),
            episodes = episodes,
            queue = listOf(FixtureRef(episodes[2].p, episodes[2].k), FixtureRef(episodes[3].p, episodes[3].k)),
            session =
                FixtureSession(
                    current = FixtureRef(episodes[1].p, episodes[1].k),
                    contextType = "GROUP",
                    contextGroupUuid = groupB.uuid,
                    order = "NEWEST_FIRST",
                ),
            seedSettings =
                mapOf(
                    SETTING_OPML_LAYOUT to "GROUPED",
                    SETTING_AUTO_SNAPSHOT to "true",
                ),
            flipSettings = mapOf(SETTING_OPML_LAYOUT to "FLAT"),
        )
    }

    /** seed.zip — a full backup archive the restore preview applies under MERGE. */
    fun seedZip(
        nonce: String,
        dest: File,
    ): Pair<FixtureContent, File> {
        val content = content(nonce)
        val codec = BackupCodec()
        val library =
            LibraryV1(
                podcasts =
                    content.podcasts.map {
                        PodcastV1(
                            key = it.key,
                            feedUrl = it.feedUrl,
                            title = it.title,
                            link = it.link,
                            subscribedAt = it.subscribedAt,
                            syncId = it.syncId,
                        )
                    },
                groups =
                    content.groups.mapIndexed { index, group ->
                        GroupV1(
                            uuid = group.uuid,
                            name = group.name,
                            sortOrder = index,
                            orderKey = "0,${index.toString().padStart(6, '0')}",
                            members = group.members.map { MemberV1(podcastKey = it) },
                        )
                    },
            )
        val episodesText =
            content.episodes.joinToString("") { spec ->
                codec.encodeEpisodeLine(
                    EpisodeLineV1(
                        p = spec.p,
                        k = spec.k,
                        g = spec.g,
                        t = spec.t,
                        d = spec.d,
                        u = spec.u,
                        ty = spec.ty,
                        dur = spec.dur,
                        l = spec.l,
                        pl = spec.pl,
                        pc = spec.pc,
                        st = spec.st,
                        lp = spec.lp,
                        pos = spec.pos,
                        posAt = spec.posAt,
                        posSrc = spec.posSrc,
                        fav = spec.fav,
                        md = spec.md,
                        ts = spec.ts,
                    ),
                ) + "\n"
            }
        val queue =
            QueueV1(
                upNext = content.queue.map { UpNextRefV1(p = it.p, k = it.k, kv = EpisodeKeys.VERSION) },
                session =
                    content.session?.let {
                        SessionV1(
                            current =
                                it.current?.let { ref ->
                                    EpisodeRefV1(p = ref.p, k = ref.k, kv = EpisodeKeys.VERSION)
                                },
                            contextType = it.contextType,
                            contextGroupUuid = it.contextGroupUuid,
                            order = it.order,
                        )
                    },
            )
        val settings =
            SettingsV1(
                values =
                    listOf(
                        SettingValueV1(SETTING_OPML_LAYOUT, "Choice", JsonPrimitive("GROUPED")),
                        SettingValueV1(SETTING_AUTO_SNAPSHOT, "Bool", JsonPrimitive(true)),
                    ),
            )
        return content to archive(nonce, library, episodesText, queue, settings, codec, dest)
    }

    /** flip.zip — an empty library whose settings.json flips opml_layout to FLAT (MERGE). */
    fun flipZip(
        nonce: String,
        dest: File,
    ): File {
        val codec = BackupCodec()
        val settings =
            SettingsV1(
                values = listOf(SettingValueV1(SETTING_OPML_LAYOUT, "Choice", JsonPrimitive("FLAT"))),
            )
        return archive(nonce, LibraryV1(emptyList(), emptyList()), "", QueueV1(emptyList()), settings, codec, dest)
    }

    private fun archive(
        nonce: String,
        library: LibraryV1,
        episodesText: String,
        queue: QueueV1,
        settings: SettingsV1,
        codec: BackupCodec,
        dest: File,
    ): File {
        val payloads =
            linkedMapOf(
                BackupCodec.ENTRY_LIBRARY to codec.encodeLibrary(library).toByteArray(),
                BackupCodec.ENTRY_EPISODES to episodesText.toByteArray(),
                BackupCodec.ENTRY_QUEUE to codec.encodeQueue(queue).toByteArray(),
                BackupCodec.ENTRY_SETTINGS to codec.encodeSettings(settings).toByteArray(),
            )
        val counts =
            CountsV1(
                podcasts = library.podcasts.size,
                groups = library.groups.size,
                episodeLines = episodesText.lines().count { it.isNotBlank() },
                played = episodesText.lines().count { it.contains("\"pl\"") },
                inProgress = episodesText.lines().count { it.contains("\"pos\"") },
                upNext = queue.upNext.size,
                settings = settings.values.size,
            )
        val manifest =
            BackupManifest(
                createdAt = Instant.ofEpochMilli(1_700_000_000_000L).toString(),
                kind = BackupKind.MANUAL,
                app = AppInfoV1(versionName = "bmgrdriver", versionCode = 1L, abi = "fixture"),
                dbSchemaVersion = DB_SCHEMA_VERSION,
                episodeKeysVersion = EpisodeKeys.VERSION,
                urlNormalizerVersion = UrlNormalizer.VERSION,
                installationId = "$INSTALLATION-$nonce",
                counts = counts,
                entries =
                    payloads.map { (name, bytes) ->
                        EntryInfo(
                            name = name,
                            bytes = bytes.size.toLong(),
                            sha256 = sha256(bytes),
                            records =
                                when (name) {
                                    BackupCodec.ENTRY_LIBRARY -> library.podcasts.size + library.groups.size
                                    BackupCodec.ENTRY_EPISODES -> counts.episodeLines
                                    BackupCodec.ENTRY_QUEUE -> queue.upNext.size + if (queue.session != null) 1 else 0
                                    else -> settings.values.size
                                },
                        )
                    },
            )
        val entries = linkedMapOf(BackupCodec.ENTRY_MANIFEST to codec.encodeManifest(manifest).toByteArray())
        entries.putAll(payloads)
        SeedZipWriter.write(dest, entries)
        return dest
    }

    /** A ZIP with `manifest.json`'s encrypted flag set — the sniffer/restore must refuse it. */
    fun encryptedBlob(dest: File): File = EncryptedBlob.write(dest)

    /** Deterministic nonce-bound canonical UUID — the restore stager only accepts that form. */
    private fun uuid(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun writeSidecar(
        file: File,
        sidecar: SeedSidecar,
    ) {
        file.writeText(json.encodeToString(SeedSidecar.serializer(), sidecar))
    }
}

/** Deterministic bounded ZIP writer — `java.util.zip` only, fixed timestamps, manifest first. */
internal object SeedZipWriter {
    private const val STAMP = 1_700_000_000_000L

    fun write(
        file: File,
        entries: LinkedHashMap<String, ByteArray>,
    ) {
        FileOutputStream(file).use { stream ->
            ZipOutputStream(stream).use { zip ->
                for ((name, bytes) in entries) {
                    val entry = ZipEntry(name)
                    entry.time = STAMP
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }
}

/**
 * A minimal ZIP whose `manifest.json` entry carries the encrypted flag (general purpose bit
 * 0x0001). Platform readers reject its directory or entry; the production sniffer must
 * report it as unreadable/unsupported. This is the negative leg's input.
 */
internal object EncryptedBlob {
    fun write(out: File): File {
        val name = "manifest.json".toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val bytes = java.io.ByteArrayOutputStream()

        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

        fun le32(v: Long) =
            byteArrayOf(
                (v and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v shr 24) and 0xFF).toByte(),
            )

        // Local file header — bit 0 of the flags word marks the entry encrypted.
        bytes.write(le32(0x04034B50))
        bytes.write(le16(20)) // version needed
        bytes.write(le16(0x0001)) // flags: encrypted
        bytes.write(le16(0)) // method: stored
        bytes.write(le16(0))
        bytes.write(le16(0))
        bytes.write(le32(0)) // crc (unused — entry never inflates)
        bytes.write(le32(payload.size.toLong()))
        bytes.write(le32(payload.size.toLong()))
        bytes.write(le16(name.size))
        bytes.write(le16(0))
        bytes.write(name)
        bytes.write(payload)

        val centralOffset = bytes.size()
        bytes.write(le32(0x02014B50))
        bytes.write(le16(20)) // version made by
        bytes.write(le16(20)) // version needed
        bytes.write(le16(0x0001))
        bytes.write(le16(0))
        bytes.write(le16(0))
        bytes.write(le16(0))
        bytes.write(le32(0))
        bytes.write(le32(payload.size.toLong()))
        bytes.write(le32(payload.size.toLong()))
        bytes.write(le16(name.size))
        bytes.write(le16(0))
        bytes.write(le16(0))
        bytes.write(le16(0))
        bytes.write(le16(0))
        bytes.write(le32(0))
        bytes.write(le32(0)) // local header offset
        bytes.write(name)

        bytes.write(le32(0x06054B50))
        bytes.write(le16(0))
        bytes.write(le16(0))
        bytes.write(le16(1))
        bytes.write(le16(1))
        bytes.write(le32((bytes.size() - centralOffset).toLong()))
        bytes.write(le32(centralOffset.toLong()))
        bytes.write(le16(0))

        FileOutputStream(out).use { it.write(bytes.toByteArray()) }
        return out
    }
}
