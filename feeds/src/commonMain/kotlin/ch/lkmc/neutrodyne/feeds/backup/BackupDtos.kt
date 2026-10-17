// SPDX-License-Identifier: Unlicense
@file:OptIn(ExperimentalSerializationApi::class)

package ch.lkmc.neutrodyne.feeds.backup

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// The backup format v1 DTOs (05 Archive), written and read by [BackupCodec]. Every field of
// 05's Kotlin block is here, including the optional 2026-10-05/06/10 additions (D33, D98):
// optional-with-default fields never bump formatVersion, so readers ignore unknown keys and
// writers stay sparse (encodeDefaults = false, explicitNulls = false).

/** `manifest.json`: format, versions, counts, per-entry size + SHA-256 + records. */
@Serializable
public data class BackupManifest(
    /**
     * `format`, `formatVersion` and `minReaderVersion` are forced onto the wire: with this
     * format's sparse Json flags a defaulted field would never be written, and 05's own sniff
     * rule ("a `manifest.json` entry whose `format` is `neutrodyne-backup`") could then never
     * recognise our archives.
     */
    @EncodeDefault val format: String = BackupCodec.FORMAT,
    @EncodeDefault val formatVersion: Int = BackupCodec.FORMAT_VERSION,
    @EncodeDefault val minReaderVersion: Int = BackupCodec.MIN_READER_VERSION,
    val createdAt: String, // ISO-8601 UTC (for humans)
    val kind: BackupKind,
    val app: AppInfoV1,
    val dbSchemaVersion: Int,
    val episodeKeysVersion: Int,
    val urlNormalizerVersion: Int,
    val installationId: String,
    val snapshotLevel: Int = 0,
    val includesCredentials: Boolean = false,
    val counts: CountsV1,
    val entries: List<EntryInfo>,
)

@Serializable
public enum class BackupKind {
    MANUAL,
    AUTO_SNAPSHOT,
    SCHEDULED,
}

@Serializable
public data class AppInfoV1(
    val versionName: String,
    val versionCode: Long,
    /** The writer's ABI; informational only — a backup never depends on the writer's ABI. */
    val abi: String,
    /** "android", "windows", "macos", "linux", "server" (2026-10-05); informational. */
    val platform: String? = null,
)

@Serializable
public data class CountsV1(
    val podcasts: Int,
    val groups: Int,
    val episodeLines: Int,
    val played: Int,
    val inProgress: Int,
    val upNext: Int,
    val settings: Int,
)

@Serializable
public data class EntryInfo(
    val name: String,
    val bytes: Long,
    val sha256: String,
    val records: Int,
)

/** `library.json`: podcasts, groups and (opt-in, manual backups only) credentials. */
@Serializable
public data class LibraryV1(
    val podcasts: List<PodcastV1>,
    val groups: List<GroupV1>,
    val credentials: List<CredentialV1> = emptyList(),
)

@Serializable
public data class PodcastV1(
    val key: String, // feedKey
    val feedUrl: String,
    val source: String = "RSS", // SourceType name
    val aliases: List<String> = emptyList(),
    val podcastGuid: String? = null, // real GUIDs only
    val youtubeChannelId: String? = null,
    val youtubeVariants: Int? = null,
    val title: String,
    val customTitle: String? = null,
    val artworkUrl: String? = null,
    val link: String? = null,
    val subscribedAt: Long,
    val includeInAll: Boolean = true,
    val episodeOrder: String? = null,
    val settings: OverridesV1? = null,
    /** Set when the podcast had a credential; a restore can flag `needsCredentials`. */
    val credentialOrigin: String? = null,
    /** Optional (D33, 2026-10-05): adopted on restore when unused locally. */
    val syncId: String? = null,
    /** Optional (D98, 2026-10-10): the feed's KNOWN_AMBIGUOUS GUIDs, canonical. */
    val guidAmbiguous: List<String>? = null,
)

@Serializable
public data class GroupV1(
    val uuid: String,
    val name: String,
    /** Rank in group order; still written for readers that predate orderKey. */
    val sortOrder: Int,
    /** Optional (2026-10-05): the group's OrderKey. */
    val orderKey: String? = null,
    val colorArgb: Int? = null,
    val iconKey: String? = null,
    val kind: String = "MANUAL",
    val rule: JsonElement? = null,
    val feedOrder: String = "NEWEST_FIRST",
    val playOrder: String = "NEWEST_FIRST",
    val filterFlags: Int = 0,
    val mediaFilter: String = "ALL",
    val hideOlderThanDays: Int? = null,
    val showAsTab: Boolean = true,
    val createdAt: Long? = null,
    val settings: OverridesV1? = null,
    val members: List<MemberV1> = emptyList(),
)

@Serializable
public data class MemberV1(
    val podcastKey: String,
    val addedAt: Long? = null,
    /** Optional (2026-10-05): the membership's OrderKey. */
    val orderKey: String? = null,
)

@Serializable
public data class CredentialV1(
    val origin: String,
    val username: String,
    val password: String,
)

/**
 * The 13 per-scope override fields (05 OverridesV1), all nullable, enum fields carried by
 * name so `:feeds` never sees `:core:model`'s `NetworkPolicy`/`DeleteAfter`.
 */
@Serializable
public data class OverridesV1(
    val playbackSpeed: Float? = null,
    val skipSilence: Boolean? = null,
    val boostDb: Float? = null,
    val introSkipMs: Long? = null,
    val outroSkipMs: Long? = null,
    val autoDownload: Boolean? = null,
    val autoDownloadKeepLatest: Int? = null,
    val autoDownloadNetwork: String? = null,
    val autoDownloadRequireCharging: Boolean? = null,
    val deleteAfterPlayed: String? = null,
    val includeVideoInAutoDownload: Boolean? = null,
    val notifyNewEpisodes: Boolean? = null,
    val refreshIntervalMinutes: Int? = null,
)

/** One line of `episodes.jsonl`. Short keys are the wire names — do not rename. */
@Serializable
public data class EpisodeLineV1(
    val p: String, // podcast key
    val k: String, // identityKey
    val kv: Int = 1, // EpisodeKeys version
    val g: String? = null, // guid
    val t: String? = null, // title
    val d: Long? = null, // pubDate
    val u: String? = null, // enclosure URL
    val ty: String? = null, // enclosure type
    val dur: Long? = null, // duration hint
    val yt: String? = null, // YouTube video ID
    val l: String? = null, // link
    val pl: Long? = null, // playedAt
    val pc: Int = 0, // playCount
    val st: Long? = null, // startedAt
    val lp: Long? = null, // lastPlayedAt, optional (2026-10-05)
    val pos: Long? = null,
    val posAt: Long? = null,
    val posSrc: String? = null,
    val fav: Boolean = false,
    val dis: Long? = null, // downloadDismissedAt
    val dl: Boolean = false, // was downloaded
    val md: Long? = null, // measuredDurationMs
    val ts: Long, // last user-state change; 0 = the "no state" sentinel (05 Line timestamps)
    // Staging only (2026-10-06): field-clock milliseconds per state field the record has —
    // written into 10's first-link staging files, never into an archive (BackupCodec's
    // ARCHIVE mode strips it on encode).
    val fc: Map<String, Long>? = null,
)

@Serializable
public data class EpisodeRefV1(
    val p: String,
    val k: String,
    val kv: Int = 1,
)

/** Up next ref: JSON-compatible with [EpisodeRefV1], plus the optional OrderKey (2026-10-05). */
@Serializable
public data class UpNextRefV1(
    val p: String,
    val k: String,
    val kv: Int = 1,
    val ok: String? = null,
)

/** `queue.json`: Up next refs and the play session. */
@Serializable
public data class QueueV1(
    val upNext: List<UpNextRefV1>,
    val session: SessionV1? = null,
)

@Serializable
public data class SessionV1(
    val current: EpisodeRefV1? = null,
    val contextType: String? = null,
    val contextGroupUuid: String? = null,
    val contextPodcastKey: String? = null,
    val order: String = "NEWEST_FIRST",
    val filterFlags: Int = 0,
    val mediaFilter: String = "ALL",
    val minSortDate: Long? = null,
    val anchor: EpisodeRefV1? = null,
    val anchorSortDate: Long? = null,
)

/** `settings.json`: whitelisted portable settings with types. */
@Serializable
public data class SettingsV1(
    val values: List<SettingValueV1>,
)

@Serializable
public data class SettingValueV1(
    val key: String,
    /** The setting key's subclass name, for type checks on read. */
    val type: String,
    val value: JsonPrimitive? = null,
    val values: List<String>? = null,
)
