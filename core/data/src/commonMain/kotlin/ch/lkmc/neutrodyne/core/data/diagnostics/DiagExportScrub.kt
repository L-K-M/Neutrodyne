// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import androidx.sqlite.SQLiteConnection

/**
 * Scrubs a `VACUUM INTO` diagnostics copy in place on a raw driver connection (02
 * db-maintenance worker, N3). Credential and every `sync_*` payload table is emptied; then
 * every `TEXT` column outside [KEEP] has each non-null value rewritten, derived from the
 * copy's own `sqlite_master`/`PRAGMA table_info` so a column added later is scrubbed by
 * default. Callers `VACUUM` the copy afterwards so deleted bytes leave the file.
 *
 * Rewrite shapes (02): an absolute-URL value becomes `scheme://host/…#{rowid}`;
 * `episode.identityKey`/`episode.guid` keep their key prefix + `…#{rowid}` (`u:` keys embed
 * the enclosure URL, GUIDs can be URLs); anything else becomes `…#{rowid}`. The `#{rowid}`
 * suffix keeps unique indices and primary keys valid.
 */
internal object DiagExportScrub {
    private const val MASK = "…"

    /**
     * `sync_state.serverUrl` must not survive even as a host — 09's SYNC rule: the server's
     * address never leaves the app. Every other URL value keeps `scheme://host`.
     */
    private val FORCE_MASK = setOf("sync_state.serverUrl")

    /**
     * 02's allow-list: `table.column` for columns that never hold a URL, a token or
     * feed-supplied free text — enum and state columns, error codes, MIME types, language
     * tags, `uuid`, `nameKey`, opaque key columns, group names, and podcast and episode
     * titles and authors.
     */
    val KEEP: Set<String> =
        setOf(
            // podcast: enums, error code, MIME-free title/author/language and key columns.
            "podcast.sourceType",
            "podcast.title",
            "podcast.author",
            "podcast.language",
            "podcast.showType",
            "podcast.medium",
            "podcast.artworkKey",
            "podcast.customTitle",
            "podcast.episodeOrder",
            "podcast.status",
            "podcast.lastErrorKind",
            "podcast_url_alias.reason",
            "podcast_settings.o.autoDownloadNetwork",
            "podcast_settings.o.deleteAfterPlayed",
            "podcast_group_settings.o.autoDownloadNetwork",
            "podcast_group_settings.o.deleteAfterPlayed",
            // podcast_group: uuid/nameKey per 02, plus the opaque ordering/icon keys and enums.
            "podcast_group.uuid",
            "podcast_group.name",
            "podcast_group.nameKey",
            "podcast_group.orderKey",
            "podcast_group.iconKey",
            "podcast_group.kind",
            "podcast_group.feedOrder",
            "podcast_group.playOrder",
            "podcast_group.mediaFilter",
            "podcast_group_member.orderKey",
            "podcast_group_member.source",
            // episode: titles and enum/MIME columns; identityKey/guid have their own rule.
            "episode.title",
            "episode.enclosureType",
            "episode.episodeType",
            "episode.artworkKey",
            "episode.chaptersType",
            "episode.availability",
            "episode_transcript.type",
            "episode_transcript.language",
            "episode_transcript.rel",
            "episode_alt_enclosure.type",
            "episode_alt_enclosure.lang",
            "episode_alt_enclosure.title",
            "episode_alt_enclosure.rel",
            "episode_alt_enclosure.codecs",
            "episode_alt_enclosure.integrityType",
            "person.ownerType",
            "person.name",
            "person.role",
            "person.grp",
            "funding.ownerType",
            "chapter.source",
            "episode_guid_provenance.knowledge",
            "episode_position.positionSource",
            "queue_entry.orderKey",
            "play_session.contextType",
            "play_session.contextOrder",
            "play_session.contextMediaFilter",
            "download.lane",
            "download.state",
            "download.waitReason",
            "download.sourceKind",
            "download.formatPref",
            "download.mimeType",
            "artwork.key",
            "import_session.sourceFormat",
            "import_session.state",
            "import_item.title",
            "import_item.kind",
            "import_item.groupNamesJson",
            "import_item.status",
        )

    /** Columns whose values keep a key prefix instead of the URL mask (02's third shape). */
    private val PREFIXED = setOf("episode.identityKey", "episode.guid")

    /** Tables whose rows are emptied outright (02): credentials and every sync payload. */
    private val EMPTIED =
        listOf("credential", "sync_outbox", "sync_clock", "sync_parked", "sync_held")

    /** System tables never touched by the scrub. */
    private val SKIPPED_TABLES = setOf("room_master_table", "android_metadata")

    fun scrub(connection: SQLiteConnection) {
        // The rewrites must not fire capture triggers: MS0's SyncTriggers.dropAll is this
        // until it lands — every trigger is dropped on the copy.
        val triggers = names(connection, "SELECT name FROM sqlite_master WHERE type = 'trigger'")
        for (trigger in triggers) {
            exec(connection, "DROP TRIGGER IF EXISTS \"${trigger.escaped()}\"")
        }
        exec(connection, "UPDATE sync_state SET enabled = 0")
        for (table in EMPTIED) {
            exec(connection, "DELETE FROM \"$table\"")
        }

        val tables =
            names(connection, "SELECT name FROM sqlite_master WHERE type = 'table'")
                .filter { it !in SKIPPED_TABLES && !it.startsWith("sqlite_") && it !in EMPTIED }
        for (table in tables) {
            scrubTable(connection, table)
        }
    }

    private fun scrubTable(
        connection: SQLiteConnection,
        table: String,
    ) {
        val columns = textColumns(connection, table)
        if (columns.isEmpty()) return
        val scrubbed =
            columns.filter { "$table.$it" !in KEEP }
        if (scrubbed.isEmpty()) return

        val select =
            "SELECT rowid, ${scrubbed.joinToString { "\"${it.escaped()}\"" }} " +
                "FROM \"${table.escaped()}\""
        val update =
            "UPDATE \"${table.escaped()}\" SET ${scrubbed.joinToString { "\"${it.escaped()}\" = ?" }} " +
                "WHERE rowid = ?"
        connection.prepare(select).use { read ->
            connection.prepare(update).use { write ->
                while (read.step()) {
                    val rowid = read.getLong(0)
                    var dirty = false
                    for ((index, column) in scrubbed.withIndex()) {
                        if (read.isNull(index + 1)) continue
                        val qualified = "$table.$column"
                        val replacement =
                            when {
                                qualified in FORCE_MASK -> masked(rowid)
                                qualified in PREFIXED -> prefixMask(read.getText(index + 1), rowid)
                                else -> valueMask(read.getText(index + 1), rowid)
                            }
                        write.bindText(index + 1, replacement)
                        dirty = true
                    }
                    if (!dirty) continue
                    write.bindLong(scrubbed.size + 1, rowid)
                    write.step()
                    write.reset()
                    write.clearBindings()
                }
            }
        }
    }

    /** `TEXT`-affinity column names of [table] from `PRAGMA table_info` (cid, name, type, …). */
    private fun textColumns(
        connection: SQLiteConnection,
        table: String,
    ): List<String> =
        buildList {
            connection.prepare("PRAGMA table_info(\"${table.escaped()}\")").use { stmt ->
                while (stmt.step()) {
                    if (stmt.getText(2).uppercase().contains("TEXT")) add(stmt.getText(1))
                }
            }
        }

    private fun names(
        connection: SQLiteConnection,
        sql: String,
    ): List<String> =
        buildList {
            connection.prepare(sql).use { stmt ->
                while (stmt.step()) add(stmt.getText(0))
            }
        }

    private fun exec(
        connection: SQLiteConnection,
        sql: String,
    ) {
        connection.prepare(sql).use { it.step() }
    }

    /** `scheme://host/…#{rowid}` for absolute URLs, else `…#{rowid}` (02's shapes). */
    private fun valueMask(
        value: String,
        rowid: Long,
    ): String {
        val schemeEnd = value.indexOf("://")
        if (schemeEnd <= 0 || !value.substring(0, schemeEnd).all { it.isSchemeChar() }) {
            return masked(rowid)
        }
        val authority = value.substring(schemeEnd + 3).substringBefore('/').substringBefore('?')
        val host = authority.substringAfter('@').substringBefore(':')
        if (host.isEmpty()) return masked(rowid)
        return "${value.substring(0, schemeEnd)}://$host/$MASK#$rowid"
    }

    /** `episode.identityKey`/`episode.guid`: `u:`-style prefix + `…#{rowid}`. */
    private fun prefixMask(
        value: String,
        rowid: Long,
    ): String {
        val colon = value.indexOf(':')
        val prefix = if (colon in 1..3) value.substring(0, colon + 1) else ""
        return "$prefix$MASK#$rowid"
    }

    private fun masked(rowid: Long): String = "$MASK#$rowid"

    private fun String.escaped(): String = replace("\"", "\"\"")

    private fun Char.isSchemeChar(): Boolean = isLetterOrDigit() || this == '+' || this == '-' || this == '.'
}
