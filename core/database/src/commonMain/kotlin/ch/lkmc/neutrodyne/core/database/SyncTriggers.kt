// SPDX-License-Identifier: Unlicense

// SQL is kept readable as complete clauses and compared verbatim with the golden.
@file:Suppress("ktlint:standard:max-line-length")

package ch.lkmc.neutrodyne.core.database

import androidx.sqlite.SQLiteConnection
import ch.lkmc.neutrodyne.core.common.Log

/** The 24 capture triggers of 02. No JSON extensions or post-3.18 SQLite syntax. */
object SyncTriggers {
    internal data class Field(
        val name: String,
        val predicate: String = "1",
        val value: String = "NULL",
    )

    internal data class Spec(
        val name: String,
        val table: String,
        val event: String,
        val columns: List<String>,
        val collection: String,
        val rid: String,
        val fields: List<Field>,
        val guard: String = "1",
    )

    private const val GUARD = "(SELECT enabled AND NOT applying FROM sync_state WHERE id = 0)"
    private const val CAPTURE_QUERY = "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync\\_cap\\_%' ESCAPE '\\' ORDER BY name"
    private val overrides = listOf("playbackSpeed", "skipSilence", "boostDb", "introSkipMs", "outroSkipMs")

    internal val specs: List<Spec> =
        buildList {
            add(spec("podcast", "ins", "podcast", "NEW.syncId", listOf(Field("*"))))
            val podcastFields =
                listOf(
                    changed("feedUrl"),
                    changed("feedKey", "feedKeys"),
                    Field(
                        "podcastGuid",
                        "NEW.podcastGuidDerived = 0 AND (NEW.podcastGuid IS NOT OLD.podcastGuid OR NEW.podcastGuidDerived IS NOT OLD.podcastGuidDerived)",
                    ),
                    changed("title"),
                    changed("artworkUrl"),
                    changed("link"),
                    changed("customTitle"),
                    changed("includeInAll"),
                    changed("episodeOrder"),
                    changed("youtubeVariants"),
                    changed("credentialId", "credentialOrigin"),
                    changed("credentialId", "auth"),
                    Field(
                        "needsCredentials",
                        "NEW.needsCredentials IS NOT OLD.needsCredentials OR NEW.credentialId IS NOT OLD.credentialId",
                    ),
                )
            add(
                spec(
                    "podcast",
                    "upd",
                    "podcast",
                    "NEW.syncId",
                    podcastFields,
                    listOf(
                        "feedUrl",
                        "feedKey",
                        "podcastGuid",
                        "podcastGuidDerived",
                        "title",
                        "artworkUrl",
                        "link",
                        "customTitle",
                        "includeInAll",
                        "episodeOrder",
                        "youtubeVariants",
                        "credentialId",
                        "needsCredentials",
                    ),
                ),
            )
            add(spec("podcast", "del", "podcast", "OLD.syncId", listOf(Field("subscribed", value = "'false'"))))

            for ((table, coll, parent, parentId, key) in listOf(
                listOf("podcast_settings", "podcast", "podcast", "podcastId", "syncId"),
                listOf("podcast_group_settings", "group", "podcast_group", "groupId", "uuid"),
            )) {
                for (event in listOf("ins", "upd", "del")) {
                    val row = if (event == "del") "OLD" else "NEW"
                    val rid = "(SELECT $key FROM $parent WHERE id = $row.$parentId)"
                    val fields =
                        overrides.map { column ->
                            when (event) {
                                "ins" -> Field("s.$column", "NEW.$column IS NOT NULL")
                                "del" -> Field("s.$column", "OLD.$column IS NOT NULL", "'null'")
                                else -> changed(column, "s.$column")
                            }
                        }
                    add(
                        spec(
                            table,
                            event,
                            coll,
                            rid,
                            fields,
                            if (event ==
                                "upd"
                            ) {
                                overrides
                            } else {
                                emptyList()
                            },
                            "$rid IS NOT NULL",
                        ),
                    )
                }
            }

            add(spec("podcast_group", "ins", "group", "NEW.uuid", listOf(Field("*"))))
            val groupColumns =
                listOf(
                    "name",
                    "orderKey",
                    "colorArgb",
                    "iconKey",
                    "feedOrder",
                    "playOrder",
                    "filterFlags",
                    "mediaFilter",
                    "hideOlderThanDays",
                    "showAsTab",
                )
            add(
                spec(
                    "podcast_group",
                    "upd",
                    "group",
                    "NEW.uuid",
                    groupColumns.map {
                        changed(
                            it,
                            if (it ==
                                "orderKey"
                            ) {
                                "ok"
                            } else {
                                it
                            },
                        )
                    },
                    groupColumns,
                ),
            )
            add(spec("podcast_group", "del", "group", "OLD.uuid", listOf(Field("deleted", value = "'true'"))))

            for ((table, coll) in listOf("podcast_group_member" to "member", "queue_entry" to "upnext")) {
                for (event in listOf("ins", "upd", "del")) {
                    val row = if (event == "del") "OLD" else "NEW"
                    val rid =
                        if (coll == "member") {
                            "(SELECT g.uuid || p.syncId FROM podcast_group g, podcast p WHERE g.id = $row.groupId AND p.id = $row.podcastId)"
                        } else {
                            episodeRid(row)
                        }
                    val fields =
                        when (event) {
                            "ins" -> {
                                listOf(Field("in"), Field("ok")) +
                                    if (coll == "member") listOf(Field("addedAt")) else emptyList()
                            }

                            "upd" -> {
                                listOf(changed("orderKey", "ok"))
                            }

                            else -> {
                                listOf(Field("in", value = "'false'"))
                            }
                        }
                    add(
                        spec(
                            table,
                            event,
                            coll,
                            rid,
                            fields,
                            if (event ==
                                "upd"
                            ) {
                                listOf("orderKey")
                            } else {
                                emptyList()
                            },
                            "$rid IS NOT NULL",
                        ),
                    )
                }
            }

            val stateColumns = listOf("playedAt", "isFavorite", "playCount", "lastPlayedAt", "measuredDurationMs")
            val stateFields = listOf("played", "fav", "playCount", "lastPlayedAt", "measuredDurationMs")
            for (event in listOf("ins", "upd")) {
                val fields =
                    stateColumns.zip(stateFields).map { (column, field) ->
                        val predicate =
                            if (event == "upd") {
                                "NEW.$column IS NOT OLD.$column"
                            } else {
                                when (column) {
                                    "isFavorite", "playCount" -> "NEW.$column <> 0"
                                    else -> "NEW.$column IS NOT NULL"
                                }
                            }
                        val value =
                            if (field == "played") {
                                "'{\"played\":' || CASE WHEN NEW.playedAt IS NULL THEN 'false' ELSE 'true' END || ',\"playedAt\":' || COALESCE(NEW.playedAt, 'null') || '}'"
                            } else {
                                "NULL"
                            }
                        Field(field, predicate, value)
                    }
                add(
                    spec(
                        "episode_state",
                        event,
                        "episode",
                        episodeRid("NEW"),
                        fields,
                        if (event ==
                            "upd"
                        ) {
                            stateColumns
                        } else {
                            emptyList()
                        },
                    ),
                )
            }
            for (event in listOf("ins", "upd")) {
                val predicate = if (event == "ins") "NEW.positionMs > 0" else "NEW.positionMs IS NOT OLD.positionMs"
                val literal =
                    "CASE WHEN NEW.positionMs = 0 THEN '{\"ms\":0,\"reset\":true}' ELSE " +
                        "'{\"ms\":' || NEW.positionMs || ',\"dur\":' || COALESCE(NEW.durationMs, 'null') || ',\"src\":' || ${jsonString(
                            "NEW.positionSource",
                        )} || '}' END"
                add(
                    spec(
                        "episode_position",
                        event,
                        "episode",
                        episodeRid("NEW"),
                        listOf(Field("pos", predicate, literal)),
                        if (event ==
                            "upd"
                        ) {
                            listOf("positionMs")
                        } else {
                            emptyList()
                        },
                    ),
                )
            }
            val contextColumns =
                listOf(
                    "contextType",
                    "contextId",
                    "contextOrder",
                    "contextFilterFlags",
                    "contextMediaFilter",
                    "contextMinSortDate",
                    "contextAnchorEpisodeId",
                    "contextAnchorSortDate",
                )
            add(
                spec(
                    "play_session",
                    "upd",
                    "session",
                    "'current'",
                    listOf(
                        changed("currentEpisodeId", "episode"),
                        Field("context", contextColumns.joinToString(" OR ") { "NEW.$it IS NOT OLD.$it" }),
                    ),
                    listOf("currentEpisodeId") + contextColumns,
                ),
            )
            // Rekey has a specialised body but still declares its UPDATE OF and guard here.
            val oldRid = episodeRowRid("OLD")
            add(
                Spec(
                    "sync_cap_episode_rekey",
                    "episode",
                    "UPDATE",
                    listOf("identityKey"),
                    "episode",
                    oldRid,
                    emptyList(),
                    "NEW.identityKey IS NOT OLD.identityKey AND NEW.podcastId = OLD.podcastId AND " +
                        "(EXISTS (SELECT 1 FROM sync_clock WHERE coll IN ('episode', 'upnext') AND rid = $oldRid) OR " +
                        "EXISTS (SELECT 1 FROM sync_outbox WHERE coll IN ('episode', 'upnext') AND rid = $oldRid))",
                ),
            )
        }

    /** Individual statements, also used verbatim as the committed golden. */
    fun sql(): List<String> = specs.map { render(it) }

    suspend fun create(connection: SQLiteConnection) {
        sql().forEach { execute(connection, it) }
    }

    suspend fun dropAll(connection: SQLiteConnection) {
        installed(
            connection,
        ).keys.forEach { execute(connection, "DROP TRIGGER IF EXISTS \"${it.replace("\"", "\"\"")}\"") }
    }

    /** Call inside the migration transaction; [ensure] owns atomic repair at open. */
    suspend fun recreate(connection: SQLiteConnection) {
        dropAll(connection)
        create(connection)
    }

    suspend fun ensure(connection: SQLiteConnection) {
        // SAVEPOINT works both during an existing transaction and on a callback's raw connection.
        execute(connection, "SAVEPOINT sync_trigger_install")
        try {
            val expected = specs.zip(sql()).associate { (spec, sql) -> spec.name to sql.removeSuffix(";") }
            if (installed(connection) != expected) {
                recreate(connection)
                Log.i("SyncTriggers") { "Repaired sync capture trigger set" }
            }
            execute(connection, "RELEASE sync_trigger_install")
        } catch (failure: Throwable) {
            execute(connection, "ROLLBACK TO sync_trigger_install")
            execute(connection, "RELEASE sync_trigger_install")
            throw failure
        }
    }

    /** XML identity keys admit tab, LF and CR, but no other control characters below U+0020. */
    internal fun jsonString(expression: String): String =
        "'\"' || replace(replace(replace(replace(replace($expression, '\\', '\\\\'), '\"', '\\\"'), char(10), '\\n'), char(13), '\\r'), char(9), '\\t') || '\"'"

    private fun spec(
        table: String,
        suffix: String,
        coll: String,
        rid: String,
        fields: List<Field>,
        columns: List<String> = emptyList(),
        guard: String = "1",
    ): Spec =
        Spec(
            "sync_cap_${table}_$suffix",
            table,
            when (suffix) {
                "ins" -> "INSERT"
                "upd" -> "UPDATE"
                else -> "DELETE"
            },
            columns,
            coll,
            rid,
            fields,
            guard,
        )

    private fun changed(
        column: String,
        field: String = column,
    ): Field = Field(field, "NEW.$column IS NOT OLD.$column")

    private fun episodeRid(row: String): String =
        "(SELECT p.syncId || e.identityKey FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id = $row.episodeId)"

    private fun episodeRowRid(row: String): String =
        "(SELECT p.syncId || $row.identityKey FROM podcast p WHERE p.id = $row.podcastId)"

    private fun render(spec: Spec): String {
        val event = spec.event + if (spec.columns.isEmpty()) "" else " OF " + spec.columns.joinToString(", ")
        val predicate = if (spec.fields.isEmpty()) "1" else spec.fields.joinToString(" OR ") { "(${it.predicate})" }
        val body = if (spec.name == "sync_cap_episode_rekey") rekeyBody() else captureBody(spec)
        return "CREATE TRIGGER ${spec.name}\nAFTER $event ON ${spec.table}\nWHEN $GUARD AND (${spec.guard}) AND ($predicate)\nBEGIN\n  $SYNC_TICK;\n$body\nEND;"
    }

    private fun captureBody(spec: Spec): String {
        val fields = spec.fields.joinToString(" UNION ALL ") { "SELECT '${it.name}' AS field WHERE (${it.predicate})" }
        val values =
            spec.fields.joinToString(" UNION ALL ") {
                "SELECT '${it.name}' AS field, ${it.value} AS value WHERE (${it.predicate})"
            }
        return "  DELETE FROM sync_outbox WHERE coll = '${spec.collection}' AND rid = ${spec.rid} AND field IN ($fields);\n" +
            "  INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)\n" +
            "    SELECT '${spec.collection}', ${spec.rid}, f.field, s.hlc, s.nodeId, f.value FROM sync_state s, ($values) f WHERE s.id = 0;"
    }

    private fun rekeyBody(): String {
        val old = episodeRowRid("OLD")
        val new = episodeRowRid("NEW")
        val newJson = jsonString(new)
        return """
            SELECT RAISE(ABORT, 'Conflicting rekey payloads at the same complete clock') WHERE EXISTS (
              SELECT 1 FROM sync_outbox a JOIN sync_outbox b ON a.coll = b.coll AND a.field = b.field
              WHERE a.coll IN ('episode', 'upnext') AND a.rid = $old AND b.rid = $new
                AND a.hlc = b.hlc AND a.nodeId = b.nodeId AND a.value IS NOT b.value);
            DELETE FROM sync_outbox WHERE coll IN ('episode', 'upnext') AND rid = $new AND EXISTS (
              SELECT 1 FROM sync_outbox o WHERE o.coll = sync_outbox.coll AND o.rid = $old AND o.field = sync_outbox.field
                AND (o.hlc > sync_outbox.hlc OR (o.hlc = sync_outbox.hlc AND o.nodeId >= sync_outbox.nodeId)));
            DELETE FROM sync_outbox WHERE coll IN ('episode', 'upnext') AND rid = $old AND EXISTS (
              SELECT 1 FROM sync_outbox o WHERE o.coll = sync_outbox.coll AND o.rid = $new AND o.field = sync_outbox.field);
            UPDATE sync_outbox SET rid = $new WHERE coll IN ('episode', 'upnext') AND rid = $old;
            UPDATE sync_clock SET rid = $new WHERE coll IN ('episode', 'upnext') AND rid = $old
              AND NOT EXISTS (SELECT 1 FROM sync_clock c WHERE c.coll = sync_clock.coll AND c.rid = $new);
            INSERT INTO sync_outbox(coll, rid, field, hlc, nodeId, value)
              SELECT 'episode', $old, '~rekey', hlc, nodeId, '{"to":' || $newJson || '}' FROM sync_state WHERE id = 0;
            """.trimIndent().prependIndent("  ")
    }

    private suspend fun installed(connection: SQLiteConnection): Map<String, String> =
        connection.prepare(CAPTURE_QUERY).use { statement ->
            buildMap { while (statement.step()) put(statement.getText(0), statement.getText(1)) }
        }

    private suspend fun execute(
        connection: SQLiteConnection,
        sql: String,
    ) {
        connection.prepare(sql).use { it.step() }
    }
}
