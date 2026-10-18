// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.importing

import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.opml.FolderRef
import ch.lkmc.neutrodyne.feeds.opml.OpmlDocument
import ch.lkmc.neutrodyne.feeds.opml.ParseMode
import kotlinx.serialization.Serializable

/**
 * One parser-agnostic import row (05 Adapters): [title]/[customTitle] hold the document's spellings;
 * the classifier resolves display fallbacks (`host`) and normalises [groupNames] with
 * [GroupNames]. [youtubeVariantsHint] carries `nd:ytVariants` as the 04 bit set (UULF = 1, UUSH = 2,
 * UULV = 4) so classification need not re-tokenise it.
 */
@Serializable
public data class ImportEntry(
    val ordinal: Int,
    val url: String,
    val title: String? = null,
    val customTitle: String? = null,
    val htmlUrl: String? = null,
    val groupNames: List<String> = emptyList(),
    val preselect: Boolean = true,
    val youtubeVariantsHint: Int? = null,
)

/**
 * One proposed group, raw name in document order (05 Adapters). [excludedReason] pre-fills the
 * preview's switch-off reasons: [ImportGroup.EXCLUDED_WRAPPER] for a container folder and
 * [ImportGroup.EXCLUDED_GPODDER_SECTION] for gPodder's content-type sections (05 Group mapping).
 */
@Serializable
public data class ImportGroup(
    val name: String,
    val colorArgb: Int? = null,
    val iconKey: String? = null,
    val excludedReason: String? = null,
) {
    public companion object {
        public const val EXCLUDED_WRAPPER: String = "wrapper"
        public const val EXCLUDED_GPODDER_SECTION: String = "gpodder_section"
    }
}

/**
 * The import-preview input every source format adapts into (05 Adapters). [format] matches the
 * `:core:model` `ImportFormat` name (`OPML` here). [warnings] are the read's `OpmlWarningCode`
 * names plus counts, and [ignoredOutlines] the read's ignored-subtree count, so the preview's
 * "12 entries were ignored" line needs no re-walk.
 */
@Serializable
public data class ImportDocument(
    val format: String,
    val entries: List<ImportEntry>,
    val groups: List<ImportGroup>,
    val salvaged: Boolean = false,
    val warnings: List<ImportDocumentWarning> = emptyList(),
    val ignoredOutlines: Int = 0,
)

/**
 * One carried-over read warning: the `OpmlWarningCode` name and its subtree count. The adapter
 * adds its own [TRUNCATED_NAMES] when a proposed group name was cut to the code-point cap.
 */
@Serializable
public data class ImportDocumentWarning(
    val code: String,
    val count: Int = 0,
) {
    public companion object {
        /** Preview warning (05 Group mapping) for names truncated at [GroupNames.MAX_CODE_POINTS]. */
        public const val TRUNCATED_NAMES: String = "TRUNCATED_NAMES"
    }
}

/** The OPML adapter (05 Adapters): applies the Group-mapping folder rules to an [OpmlDocument]. */
public object ImportDocuments {
    public const val FORMAT_OPML: String = "OPML"

    /**
     * Folders become group proposals in document order — for Neutrodyne files every folder (empty
     * groups and order round-trip); for foreign files folders directly holding feeds plus
     * the excluded wrapper (a FreshRSS-style container maps feeds to their innermost folder).
     * Proposals are deduplicated on the [GroupNames] name key, first spelling wins; category-only
     * names follow in order of
     * first appearance. The wrapper and gPodder-section exclusions of Group mapping are computed
     * here because the preview cannot see the folder tree once `import_item` rows exist.
     */
    public fun fromOpml(doc: OpmlDocument): ImportDocument {
        val foldersById = doc.folders.associateBy { it.id }
        val feedParents = doc.entries.mapNotNullTo(HashSet()) { it.folderId }
        val childrenByParent = doc.folders.groupBy { it.parentId }

        val topFolderId = wrapperFolderId(doc, feedParents, childrenByParent)
        val gpodder = doc.headTitle?.startsWith(GPODDER_HEAD, ignoreCase = true) == true

        val groups = ArrayList<ImportGroup>()
        val seen = HashSet<String>()
        var truncatedNames = 0

        fun propose(
            name: String,
            colorArgb: Int?,
            iconKey: String?,
            reason: String?,
        ) {
            var valid = GroupNames.validate(name) as? GroupNames.Validation.Valid
            var proposal = name
            val truncated = valid == null
            if (valid == null) {
                // The classifier truncates entry group names over the cap (05 Group mapping),
                // so the proposal takes the same prefix: a member's truncated nameKey then
                // resolves to it instead of silently losing the folder's grouping.
                valid = GroupNames.truncate(name) ?: return
                proposal = valid.name
            }
            if (!seen.add(valid.nameKey)) return
            if (truncated) truncatedNames++
            groups += ImportGroup(proposal, colorArgb, iconKey, reason)
        }

        for (folder in doc.folders) {
            if (!doc.neutrodyne && folder.id !in feedParents && folder.id != topFolderId) continue
            val reason =
                when {
                    folder.id == topFolderId -> {
                        ImportGroup.EXCLUDED_WRAPPER
                    }

                    gpodder && nameKey(folder.name) in GPODDER_SECTIONS -> {
                        ImportGroup.EXCLUDED_GPODDER_SECTION
                    }

                    else -> {
                        null
                    }
                }
            propose(folder.name, folder.colorArgb, folder.iconKey, reason)
        }
        for (entry in doc.entries) {
            for (category in entry.categories) {
                propose(category, colorArgb = null, iconKey = null, reason = null)
            }
        }

        val entries =
            doc.entries.map { entry ->
                // Neutrodyne files keep the writer's columns (05 Classify step 5): `text` is
                // the custom title when it differs, never the title fallback, so a pending
                // podcast exported with only a custom name round-trips title=null.
                val title = if (doc.neutrodyne) entry.title else entry.title ?: entry.text
                val customTitle =
                    if (doc.neutrodyne && entry.text != null && entry.text != entry.title) {
                        entry.text
                    } else {
                        null
                    }
                val groupNames = LinkedHashSet<String>()
                groupNames.addAll(entry.categories)
                entry.folderId?.let { foldersById[it] }?.name?.takeIf(String::isNotBlank)?.let {
                    groupNames.add(it)
                }
                ImportEntry(
                    ordinal = entry.ordinal,
                    url = entry.url,
                    title = title,
                    customTitle = customTitle,
                    htmlUrl = entry.htmlUrl,
                    groupNames = groupNames.toList(),
                    preselect = entry.preselect,
                    youtubeVariantsHint = ytVariantsHint(entry.ndYtVariants),
                )
            }

        return ImportDocument(
            format = FORMAT_OPML,
            entries = entries,
            groups = groups,
            salvaged = doc.mode == ParseMode.SALVAGE,
            warnings =
                doc.warnings.map { ImportDocumentWarning(it.code.name, it.count) } +
                    listOfNotNull(
                        if (truncatedNames > 0) {
                            ImportDocumentWarning(ImportDocumentWarning.TRUNCATED_NAMES, truncatedNames)
                        } else {
                            null
                        },
                    ),
            ignoredOutlines = doc.ignoredOutlines,
        )
    }

    /**
     * The single top-level folder that containers everything with a feed (05 Group mapping): T is
     * the top-level outlines that are feeds or folders whose subtree contains one; the sole T
     * element being a folder makes it a wrapper candidate when the file is not ours and its name
     * is a container word (`feeds`, `subscriptions`, …) or it holds a sub-folder with feeds.
     */
    private fun wrapperFolderId(
        doc: OpmlDocument,
        feedParents: Set<Int>,
        childrenByParent: Map<Int?, List<FolderRef>>,
    ): Int? {
        if (doc.neutrodyne) return null

        // The sole T element must be this folder: a lone top-level feed is no wrapper.
        val topFolders =
            doc.folders.filter {
                it.parentId == null && subtreeHasFeed(it.id, feedParents, childrenByParent)
            }
        if (topFolders.size != 1 || doc.entries.any { it.folderId == null }) return null

        val folder = topFolders.single()
        if (nameKey(folder.name) in WRAPPER_NAMES) return folder.id
        return folder.id.takeIf {
            descendants(folder.id, childrenByParent).any { it.id in feedParents }
        }
    }

    private fun subtreeHasFeed(
        folderId: Int,
        feedParents: Set<Int>,
        childrenByParent: Map<Int?, List<FolderRef>>,
    ): Boolean =
        folderId in feedParents ||
            descendants(folderId, childrenByParent).any { it.id in feedParents }

    private fun descendants(
        folderId: Int,
        childrenByParent: Map<Int?, List<FolderRef>>,
    ): List<FolderRef> {
        val out = ArrayList<FolderRef>()
        val pending = ArrayDeque(childrenByParent[folderId].orEmpty())
        while (pending.isNotEmpty()) {
            val folder = pending.removeFirst()
            out += folder
            childrenByParent[folder.id]?.let(pending::addAll)
        }
        return out
    }

    private fun nameKey(name: String): String? = (GroupNames.validate(name) as? GroupNames.Validation.Valid)?.nameKey

    /** `nd:ytVariants` tokens (UULF = 1, UUSH = 2, UULV = 4, UU = all) OR'd; unknown ignored. */
    private fun ytVariantsHint(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        var bits = 0
        for (token in raw.split(',')) {
            bits =
                bits or
                when (token.trim().uppercase()) {
                    "UU" -> 7
                    "UULF" -> 1
                    "UUSH" -> 2
                    "UULV" -> 4
                    else -> 0
                }
        }
        return bits.takeIf { it != 0 }
    }

    private const val GPODDER_HEAD = "gPodder"

    private val GPODDER_SECTIONS = setOf("audio", "video", "other")

    private val WRAPPER_NAMES = setOf("feeds", "subscriptions", "podcasts", "podcast feeds")
}
