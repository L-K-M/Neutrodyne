// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.feeds.discovery.AddInputNormalizer
import ch.lkmc.neutrodyne.feeds.discovery.NormalizedInput
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.importing.ImportEntry
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import ch.lkmc.neutrodyne.youtube.api.YtRef
import dev.zacsweers.metro.Inject

/**
 * 05 §3 Classify: turns the parser-agnostic `ImportDocument` entries into `import_item`-shaped
 * rows. Pure where possible — the only lookups are the already-subscribed checks against
 * `podcast.feedKey`/`podcast_url_alias` (identity, so feedKey and URL-alias rows compare
 * directly).
 *
 * YouTube is routed through [YouTubeUrlClassifier] first (04 OPML): any ref makes the item
 * `kind = YOUTUBE`. Channels and resolvable inputs are selectable; only a `Channel` carries a
 * canonical `normalizedUrl`. Playlists remain unsupported with `errorDetail = "playlist"`.
 */
@Inject
internal class ImportClassifier(
    private val db: NeutrodyneDatabase,
    private val youTube: YouTubeUrlClassifier,
) {
    /** One classified entry — the shape `import_item` persists. */
    data class ClassifiedItem(
        val ordinal: Int,
        /** Display title — `title ?: customTitle ?: host`; null only when no host parses either. */
        val title: String?,
        /**
         * The parsed user rename of a Neutrodyne file (05 §3.5) — never the publisher title.
         * `import_item` has no column for it; [ImportSessions] carries it in optionsJson as
         * `itemCustomTitles[ordinal]` so commit can land it on the pending row.
         */
        val customTitle: String?,
        /** The file's URL verbatim — userinfo stays here until commit (05 §3 step 2). */
        val originalUrl: String,
        /** The normalized http(s) URL — never carries userinfo; null for unresolved YouTube refs. */
        val normalizedUrl: String?,
        val kind: ImportItemKind,
        /** `GroupNames`-normalised names in first-seen order, duplicates' unions included. */
        val groupNames: List<String>,
        val selected: Boolean,
        val status: ImportItemStatus,
        val podcastId: Long?,
        val errorDetail: String?,
        val youTubeInput: String? = null,
        val youTubeVariants: Int? = null,
    )

    class Result
        internal constructor(
            val items: List<ClassifiedItem>,
            /** Group names cut to `GroupNames.MAX_CODE_POINTS` — folds into TRUNCATED_NAMES. */
            val truncatedNames: Int,
        )

    enum class InputMode { MIXED, YOUTUBE_ONLY }

    suspend fun classify(
        entries: List<ImportEntry>,
        mode: InputMode = InputMode.MIXED,
    ): Result {
        val items = ArrayList<ClassifiedItem>(entries.size)
        // nameKey → name per item, so a duplicate's groups can union into its winner (05 §3.3).
        val groupsByItem = ArrayList<LinkedHashMap<String, String>>()
        // identity → index of the first entry carrying it; only a first occurrence is a winner.
        val winnerByIdentity = HashMap<String, Int>()
        var truncated = 0

        for (entry in entries) {
            val input = if (youTube.classify(entry.url) != null) entry.url else entry.htmlUrl
            val ref = input?.let(youTube::classify)
            val target = classificationOf(entry, ref, mode)
            val names = groupNamesOf(entry.groupNames)
            truncated += names.truncated

            var status = target.status
            var podcastId: Long? = null
            var selected = false

            val winner = target.identity?.let(winnerByIdentity::get)
            when {
                winner != null -> {
                    status = ImportItemStatus.DUPLICATE_IN_FILE
                    names.byKey.forEach { (key, name) -> groupsByItem[winner].putIfAbsent(key, name) }
                }

                target.identity != null -> {
                    winnerByIdentity[target.identity] = items.size
                }
            }

            if (status == ImportItemStatus.PREVIEW) {
                val owner = target.identity?.let { existingPodcast(it) }
                if (owner != null) {
                    status = ImportItemStatus.ALREADY_SUBSCRIBED
                    podcastId = owner
                } else {
                    // 05 §3 step 6: PREVIEW rows start selected unless the file opted out.
                    selected = entry.preselect
                }
            }

            items +=
                ClassifiedItem(
                    ordinal = entry.ordinal,
                    title = displayTitle(entry, ref, target.normalizedUrl),
                    customTitle = entry.customTitle,
                    originalUrl = entry.url,
                    normalizedUrl = target.normalizedUrl,
                    kind = target.kind,
                    groupNames = emptyList(),
                    selected = selected,
                    status = status,
                    podcastId = podcastId,
                    errorDetail = target.errorDetail,
                    youTubeInput =
                        input
                            ?.let {
                                (AddInputNormalizer.normalize(it) as? NormalizedInput.Url)?.url ?: it
                            }?.takeIf { ref != null && ref !is YtRef.Playlist && entry.errorDetail == null },
                    youTubeVariants =
                        if (ref != null) {
                            entry.youtubeVariantsHint?.takeIf(YouTubeVariantBits::isValid)
                                ?: (ref as? YtRef.Channel)?.variantsHint ?: YouTubeVariantBits.LONG_FORM
                        } else {
                            null
                        },
                )
            groupsByItem += names.byKey
        }

        return Result(
            items =
                items.mapIndexed { index, item ->
                    item.copy(groupNames = groupsByItem[index].values.toList())
                },
            truncatedNames = truncated,
        )
    }

    /**
     * Kind/normalizedUrl/identity/status before the in-file passes. `identity` is what dedupe
     * and the feedKey/alias lookups compare on — `UrlNormalizer.forIdentity` for real URLs; an
     * unresolved YouTube ref's lowercased handle or path (05 §3 step 3) under a `yt:` namespace
     * so it can never collide with a feed identity.
     */
    private fun classificationOf(
        entry: ImportEntry,
        ref: YtRef?,
        mode: InputMode,
    ): Shape {
        if (entry.errorDetail != null) {
            return Shape(ImportItemKind.YOUTUBE, null, null, ImportItemStatus.INVALID_URL, entry.errorDetail)
        }
        if (ref != null) {
            val canonical =
                (ref as? YtRef.Channel)?.let { "$YOUTUBE_CHANNEL_FEED${it.id}" }
            val identity =
                when (ref) {
                    is YtRef.Channel -> {
                        canonical?.let(UrlNormalizer::forIdentity) ?: "$YT_IDENTITY:c:${ref.id}"
                    }

                    // 05 §3.3: an unresolved ref's lowercased handle or path is its identity.
                    is YtRef.Handle -> {
                        "$YT_IDENTITY:h:${ref.handle.lowercase()}"
                    }

                    is YtRef.LegacyPath -> {
                        "$YT_IDENTITY:p:${ref.path.lowercase()}"
                    }

                    // Video and playlist IDs are case-sensitive — verbatim, still dedupe-able.
                    is YtRef.Video -> {
                        "$YT_IDENTITY:v:${ref.id}"
                    }

                    is YtRef.Playlist -> {
                        "$YT_IDENTITY:l:${ref.id}"
                    }

                    is YtRef.Query -> {
                        "$YT_IDENTITY:q:${ref.text.lowercase()}"
                    }
                }
            return Shape(
                kind = ImportItemKind.YOUTUBE,
                normalizedUrl = canonical,
                identity = identity,
                // Playlists retain the later-build report until M14.
                status =
                    if (ref is YtRef.Playlist) {
                        ImportItemStatus.YOUTUBE_UNSUPPORTED_YET
                    } else {
                        ImportItemStatus.PREVIEW
                    },
                errorDetail = if (ref is YtRef.Playlist) PLAYLIST_DETAIL else null,
            )
        }

        if (mode == InputMode.YOUTUBE_ONLY) {
            return Shape(ImportItemKind.YOUTUBE, null, null, ImportItemStatus.INVALID_URL)
        }

        return when (val normalized = AddInputNormalizer.normalize(entry.url)) {
            is NormalizedInput.Url -> {
                val identity = UrlNormalizer.forIdentity(normalized.url)
                if (identity == null) {
                    // A syntactically http(s) URL whose host never normalises is not fetchable.
                    Shape(ImportItemKind.RSS, null, null, ImportItemStatus.INVALID_URL)
                } else {
                    Shape(ImportItemKind.RSS, normalized.url, identity, ImportItemStatus.PREVIEW)
                }
            }

            NormalizedInput.Invalid, is NormalizedInput.NotAUrl -> {
                Shape(ImportItemKind.RSS, null, null, ImportItemStatus.INVALID_URL)
            }
        }
    }

    /**
     * 05 §4: `category` tokens ∪ the parent folder, each `GroupNames`-normalised — empty names
     * drop, over-long ones truncate and count toward the TRUNCATED_NAMES warning. Duplicates by
     * `nameKey` keep the first spelling.
     */
    private fun groupNamesOf(raw: List<String>): NormalizedNames {
        val byKey = LinkedHashMap<String, String>()
        var truncated = 0
        for (name in raw) {
            val valid =
                when (val validation = GroupNames.validate(name)) {
                    is GroupNames.Validation.Valid -> {
                        validation
                    }

                    GroupNames.Validation.Empty -> {
                        continue
                    }

                    is GroupNames.Validation.TooLong -> {
                        truncated++
                        GroupNames.truncate(name) ?: continue
                    }
                }
            byKey.putIfAbsent(valid.nameKey, valid.name)
        }
        return NormalizedNames(byKey, truncated)
    }

    private suspend fun existingPodcast(identity: String): Long? {
        db.podcastDao().byFeedKey(identity)?.let { return it.id }
        return db.podcastDao().aliasOwner(identity)
    }

    /**
     * 05 §3 step 5's display title — the adapter already folded `text` into `title` for foreign
     * files and into `customTitle` for ours, so the fallback chain only adds a host. An
     * unresolved YouTube ref falls back to its handle/path rather than `youtube.com`.
     */
    private fun displayTitle(
        entry: ImportEntry,
        ref: YtRef?,
        normalizedUrl: String?,
    ): String? {
        val named = entry.title ?: entry.customTitle
        if (named != null) return named
        when (ref) {
            is YtRef.Handle -> return ref.handle
            is YtRef.LegacyPath -> return ref.path
            else -> Unit
        }
        return hostOf(normalizedUrl ?: entry.url)
    }

    /** `://host/…` slice of a maybe-URL; userinfo and port are display noise, so they drop. */
    private fun hostOf(url: String?): String? =
        url
            ?.substringAfter("://", url)
            ?.substringBefore('/')
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.substringAfterLast('@')
            ?.substringBefore(':')
            ?.takeIf(String::isNotEmpty)

    private class Shape(
        val kind: ImportItemKind,
        val normalizedUrl: String?,
        val identity: String?,
        val status: ImportItemStatus,
        val errorDetail: String? = null,
    )

    private class NormalizedNames(
        val byKey: LinkedHashMap<String, String>,
        val truncated: Int,
    )

    private companion object {
        const val YOUTUBE_CHANNEL_FEED = "https://www.youtube.com/feeds/videos.xml?channel_id="
        const val YT_IDENTITY = "yt"
        const val PLAYLIST_DETAIL = "playlist"
    }
}
