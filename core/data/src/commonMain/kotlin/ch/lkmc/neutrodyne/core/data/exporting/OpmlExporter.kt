// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.exporting

import androidx.room3.withReadTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.database.MembershipPair
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.model.ExportRequest
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.feeds.identity.PrivateFeedUrls
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.opml.ExportDocument
import ch.lkmc.neutrodyne.feeds.opml.ExportFeed
import ch.lkmc.neutrodyne.feeds.opml.ExportGroup
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The [ExportDocument] builder of 05 OPML export ("Model and writer"): podcasts, groups and
 * memberships in one read transaction — a point-in-time snapshot, WAL isolates readers from
 * concurrent writes — mapped to writer types on `@Dispatcher(Default)`. The pure `OpmlWriter`
 * consumes the result and never sees the database or `:core:model` types. `BackupWriter` reuses
 * [build] for the archive's `subscriptions.opml` (grouped, no passwords).
 */
@Inject
internal class OpmlExporter(
    private val db: NeutrodyneDatabase,
    private val secrets: SecretStore,
    private val clock: Clock,
    @Dispatcher(NeutrodyneDispatchers.Default) private val defaultDispatcher: CoroutineDispatcher,
) {
    /**
     * The document, file name and warning counts of [request], or `null` when
     * [ExportRequest.groupId] names a group that no longer exists (a delete raced the dialog).
     */
    suspend fun build(request: ExportRequest): BuiltExport? {
        val snapshot =
            db.withReadTransaction {
                Snapshot(
                    groups = db.groupDao().groupsInOrder(),
                    podcasts = db.podcastDao().allForExport(),
                    pairs = db.groupDao().membershipPairs(),
                )
            }
        return withContext(defaultDispatcher) { document(request, snapshot) }
    }

    private suspend fun document(
        request: ExportRequest,
        snapshot: Snapshot,
    ): BuiltExport? {
        val groups: List<PodcastGroupEntity>
        val pairs: List<MembershipPair>
        val title: String
        // The group the file name and title are built from; null on a whole-library export.
        val fileGroup: PodcastGroupEntity?
        if (request.groupId == null) {
            groups = snapshot.groups
            pairs = snapshot.pairs
            fileGroup = null
            title = LIBRARY_TITLE
        } else {
            val group = snapshot.groups.firstOrNull { it.id == request.groupId } ?: return null
            groups = listOf(group)
            // 05 Single-group export: `category` names that group only — a member's other
            // memberships stay out of the file.
            pairs = snapshot.pairs.filter { it.groupId == request.groupId }
            fileGroup = group
            title = "Neutrodyne: ${group.name}"
        }

        val uuidById = groups.associate { it.id to it.uuid }
        val rankById = groups.withIndex().associate { (rank, group) -> group.id to rank }
        val memberGroups = HashMap<Long, MutableList<Long>>()
        for (pair in pairs) memberGroups.getOrPut(pair.podcastId) { mutableListOf() } += pair.groupId

        var privateLinks = 0
        var passwords = 0
        val feeds = ArrayList<ExportFeed>(snapshot.podcasts.size)
        for (podcast in snapshot.podcasts) {
            val youtube = podcast.sourceType != SourceType.RSS
            if (youtube && !request.includeYouTube) continue
            // A full export writes every subscription; a group export only the group's members.
            val membership = memberGroups[podcast.id]
            if (request.groupId != null && membership == null) continue

            var xmlUrl = podcast.feedUrl
            // Passwords only on opt-in (R1.9); a credential the store cannot read leaves the
            // URL bare (05 Options and warnings).
            if (request.includePasswords) {
                val withUserInfo =
                    secrets
                        .forPodcast(
                            podcast.id,
                        )?.let { insertUserInfo(xmlUrl, it.username, it.password) }
                if (withUserInfo != null) {
                    xmlUrl = withUserInfo
                    passwords++
                }
            }
            // R1.9's private-link count runs on the written URL: userinfo always qualifies.
            if (PrivateFeedUrls.looksPrivate(xmlUrl)) privateLinks++

            feeds +=
                ExportFeed(
                    displayTitle = podcast.customTitle ?: podcast.title,
                    feedTitle = podcast.title,
                    xmlUrl = xmlUrl,
                    htmlUrl = podcast.link ?: youtubeChannelLink(podcast),
                    language = podcast.language,
                    groupKeys =
                        membership
                            .orEmpty()
                            .sortedBy { rankById[it] ?: Int.MAX_VALUE }
                            .mapNotNull { uuidById[it] },
                    youtube = youtube,
                    ytVariants = if (youtube) podcast.youtubeVariants else null,
                )
        }

        val document =
            ExportDocument(
                title = title,
                createdAt = clock.now(),
                groups = groups.map { ExportGroup(it.uuid, it.name, it.colorArgb, it.iconKey) },
                feeds = feeds,
            )
        return BuiltExport(document, fileNameOf(fileGroup, document.createdAt), privateLinks, passwords)
    }

    /**
     * `user:pass@` ahead of the authority (05 Attributes: `https://user:pass@host/…`), both
     * percent-encoded per RFC 3986 userinfo rules. `null` when [feedUrl] has no authority.
     * Stored URLs arrive credential-free (03 strips userinfo on subscribe); splitting first
     * means a stray one would still be replaced by the stored credential, never doubled.
     */
    private fun insertUserInfo(
        feedUrl: String,
        username: String,
        password: String,
    ): String? {
        val (clean, _) = UrlNormalizer.splitUserInfo(feedUrl)
        val schemeEnd = clean.indexOf("://")
        if (schemeEnd < 0) return null
        val authority = schemeEnd + AUTHORITY_PREFIX_LENGTH
        return buildString(clean.length + USERINFO_PADDING) {
            append(clean, 0, authority)
            append(encodeUserInfo(username))
            append(':')
            append(encodeUserInfo(password))
            append('@')
            append(clean, authority, clean.length)
        }
    }

    /**
     * Only `unreserved` stays raw — stricter than the userinfo grammar, which also allows
     * sub-delims and `:`: a `:` or `@` inside a credential can then never re-shape the authority.
     */
    private fun encodeUserInfo(value: String): String {
        if (value.all { it in UNRESERVED }) return value
        val out = StringBuilder(value.length + USERINFO_PADDING)
        // UTF-8 bytes: multi-byte sequences are all >= 0x80, so every byte outside the ASCII
        // unreserved set percent-encodes and a non-BMP credential can never produce lone '?'.
        for (byte in value.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val char = b.toChar()
            if (char in UNRESERVED) {
                out.append(char)
            } else {
                out.append('%').append(HEX_DIGITS[b shr 4]).append(HEX_DIGITS[b and 0xF])
            }
        }
        return out.toString()
    }

    /** 05 Destinations: `neutrodyne-subscriptions-{yyyy-MM-dd}.opml`, `neutrodyne-{slug}-…` for a group. */
    private fun fileNameOf(
        group: PodcastGroupEntity?,
        createdAt: Long,
    ): String {
        val date = dateStamp(createdAt)
        return when (group) {
            null -> "neutrodyne-subscriptions-$date$OPML_SUFFIX"
            else -> "neutrodyne-${slugOf(group.nameKey)}-$date$OPML_SUFFIX"
        }
    }

    /**
     * The file-name slug: `nameKey` with non-`[a-z0-9]` runs collapsed to `-`, ends trimmed,
     * at most [MAX_SLUG_CHARS] chars, `group` when nothing survives (05 Destinations). Pending
     * dashes emit only before a kept char, so neither this nor the length cap leaves a tail `-`.
     */
    private fun slugOf(nameKey: String): String {
        val slug =
            buildString {
                var dashPending = false
                for (char in nameKey) {
                    if (char in 'a'..'z' || char in '0'..'9') {
                        if (dashPending) {
                            append('-')
                            dashPending = false
                        }
                        append(char)
                    } else {
                        dashPending = isNotEmpty()
                    }
                }
            }
        return slug.take(MAX_SLUG_CHARS).ifEmpty { FALLBACK_SLUG }
    }

    /** `{yyyy-MM-dd}` in UTC — deterministic across devices, matching the document's `dateCreated`. */
    private fun dateStamp(epochMs: Long): String {
        val date =
            kotlin.time.Instant
                .fromEpochMilliseconds(epochMs)
                .toLocalDateTime(TimeZone.UTC)
                .date
        return buildString(ISO_DATE_LENGTH) {
            append(date.year)
            append('-')
            appendPadded(date.month.ordinal + 1)
            append('-')
            appendPadded(date.day)
        }
    }

    private fun StringBuilder.appendPadded(value: Int) {
        val digits = value.toString()
        if (digits.length == 1) append('0')
        append(digits)
    }

    /** The 05 Destinations YouTube `htmlUrl` fallback when the feed carried no link. */
    private fun youtubeChannelLink(podcast: PodcastEntity): String? =
        podcast.youtubeChannelId?.let { "$YOUTUBE_CHANNEL_PREFIX$it" }

    private class Snapshot(
        val groups: List<PodcastGroupEntity>,
        val podcasts: List<PodcastEntity>,
        val pairs: List<MembershipPair>,
    )

    private companion object {
        const val LIBRARY_TITLE = "Neutrodyne subscriptions"
        const val OPML_SUFFIX = ".opml"
        const val MAX_SLUG_CHARS = 40
        const val FALLBACK_SLUG = "group"
        const val ISO_DATE_LENGTH = 10
        const val AUTHORITY_PREFIX_LENGTH = 3 // "://".length
        const val USERINFO_PADDING = 8
        const val YOUTUBE_CHANNEL_PREFIX = "https://www.youtube.com/channel/"
        const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        const val HEX_DIGITS = "0123456789ABCDEF"
    }
}

/**
 * [OpmlExporter]'s result: the document for `OpmlWriter`, the destination's file name and the
 * counts [ch.lkmc.neutrodyne.core.model.PreparedExport] carries into the R1.9 warning
 * ([feeds] is `document.feeds.size`).
 */
internal class BuiltExport(
    val document: ExportDocument,
    val fileName: String,
    val privateLinks: Int,
    val passwords: Int,
)
