// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.opml

/**
 * The writer-level snapshot of one OPML export (05 OPML export). It is built by `:core:data`'s
 * `OpmlExporter` from the database and knows no `:core:model` types, so the sync server can write the
 * same OPML into its per-account backups. Every list is already in its document order; the writer
 * never re-reads the database and never normalises names.
 *
 * [createdAt] is the export instant in epoch milliseconds, formatted as an English RFC 822 `dateCreated`
 * in UTC. [groups] is in group order (`orderKey`, id). [feeds] holds every exported feed exactly once
 * per its [ExportFeed.groupKeys].
 */
public data class ExportDocument(
    val title: String,
    val createdAt: Long,
    val groups: List<ExportGroup>,
    val feeds: List<ExportFeed>,
)

/**
 * One group as the writer needs it. [key] links an [ExportFeed.groupKeys] entry to this folder and is
 * never written out (05 Attributes: no Neutrodyne UUIDs in the file); it is the group's uuid for the
 * real exporter. [colorArgb] and [iconKey] are the `nd:groupColor`/`nd:groupIcon` values and are
 * omitted when null.
 */
public data class ExportGroup(
    val key: String,
    val name: String,
    val colorArgb: Int?,
    val iconKey: String?,
)

/**
 * One feed as the writer needs it. [displayTitle] is `COALESCE(customTitle, title)` and drives the
 * sort; [feedTitle] is the show's own title. [groupKeys] lists the member groups in group order, so its
 * first entry that names an exported group is the primary group the feed nests under.
 *
 * [xmlUrl] carries userinfo only when the caller opted into passwords. [youtube] selects
 * `nd:source="youtube"`; [ytVariants] is the `podcast.youtubeVariants` bitmask and becomes the
 * `nd:ytVariants` name list.
 */
public data class ExportFeed(
    val displayTitle: String,
    val feedTitle: String,
    val xmlUrl: String,
    val htmlUrl: String?,
    val language: String?,
    val groupKeys: List<String>,
    val youtube: Boolean,
    val ytVariants: Int?,
)

/**
 * The OPML shape (05 Structure). [GROUPED] is the default hybrid layout: each feed nested once under
 * its primary group, with empty folders kept so names and order round-trip. [FLAT] writes every feed at
 * top level and keeps groups only as tags, so empty groups, group order, colours and icons are lost.
 */
public enum class OpmlLayout {
    GROUPED,
    FLAT,
}
