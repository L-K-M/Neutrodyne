// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Maps outbox rows and the current source rows onto `ChangeDto`s (10 Push). A `*` row expands to
 * every field; played/position literals stay frozen; a grow-set row sends the whole current set.
 */
class ChangeBuilder(
    private val db: NeutrodyneDatabase,
    private val secrets: SecretStore,
    private val sharePasswords: () -> Boolean,
) {
    suspend fun build(rows: List<SyncOutboxEntity>): List<ChangeDto> {
        val source = SourceReader(db, secrets, sharePasswords)
        return rows
            .groupBy { it.coll to it.rid }
            .mapNotNull { (recordKey, recordRows) ->
                val (coll, rid) = recordKey
                val collection =
                    runCatching { SyncCollection.fromWire(coll) }.getOrNull() ?: return@mapNotNull null
                val id = runCatching { RecordId.fromRid(collection, rid) }.getOrNull() ?: return@mapNotNull null
                val rekey = recordRows.firstOrNull { it.field == REKEY_FIELD }
                if (rekey != null) {
                    return@mapNotNull rekeyChange(collection, id, rekey)
                }
                val fields = mutableMapOf<String, FieldValue>()
                var hints: MatchHints? = null
                for (row in recordRows.sortedBy { it.field }) {
                    val clock = outboxClock(row) ?: continue
                    if (row.field == "*") {
                        addAllFields(source, collection, rid, clock, fields)
                    } else {
                        addField(source, collection, rid, row, clock, fields)
                    }
                    if (collection in HINTED) {
                        hints = hintsFor(source, collection, rid) ?: hints
                    }
                }
                if (fields.isEmpty()) return@mapNotNull null
                ChangeDto(coll, id.toWire(), fields = fields, match = hints)
            }
    }

    private suspend fun addField(
        source: SourceReader,
        collection: SyncCollection,
        rid: String,
        row: SyncOutboxEntity,
        clock: Hlc,
        fields: MutableMap<String, FieldValue>,
    ) {
        when (row.field) {
            "played" -> {
                val literal = row.value?.let(::parseJsonObject)
                val played = (literal?.get("played") as? JsonPrimitive)?.booleanOrNull ?: return
                val playedAt = (literal?.get("playedAt") as? JsonPrimitive)?.longOrNull
                fields["played"] = FieldValue(JsonPrimitive(played), clock.toString())
                fields["playedAt"] = FieldValue(playedAt?.let(::JsonPrimitive) ?: JsonNull, clock.toString())
            }

            "pos" -> {
                val literal = row.value?.let(::parseJsonObject)
                val reset = (literal?.get("reset") as? JsonPrimitive)?.booleanOrNull == true
                val ms = (literal?.get("ms") as? JsonPrimitive)?.longOrNull ?: 0L
                fields["pos"] =
                    FieldValue(JsonObject(buildMap { put("ms", JsonPrimitive(ms)) }), clock.toString(), reset)
            }

            "feedKeys" -> {
                val keys = source.feedKeys(rid)
                if (keys.isNotEmpty()) fields["feedKeys"] = FieldValue(add = keys)
            }

            "auth" -> {
                if (sharePasswords()) {
                    source.authField(rid)?.let { fields["auth"] = it.withClock(clock) }
                }
            }

            "v" -> {
                val literal = row.value ?: return
                fields["v"] = FieldValue(parsed(literal) ?: return, clock.toString())
            }

            else -> {
                val value = row.value?.let(::parsed) ?: source.field(collection, rid, row.field) ?: return
                fields[row.field] = FieldValue(value, clock.toString())
            }
        }
    }

    private suspend fun addAllFields(
        source: SourceReader,
        collection: SyncCollection,
        rid: String,
        clock: Hlc,
        fields: MutableMap<String, FieldValue>,
    ) {
        val all = source.allFields(collection, rid)
        all.fields.forEach { (name, value) ->
            fields[name] = FieldValue(value.v, clock.toString())
        }
        all.growSets.forEach { (name, values) ->
            fields[name] = FieldValue(add = values.toList())
        }
    }

    private fun rekeyChange(
        collection: SyncCollection,
        id: RecordId,
        row: SyncOutboxEntity,
    ): ChangeDto? {
        if (collection != SyncCollection.EPISODE) return null
        val from = id as? RecordId.Episode ?: return null
        val to =
            row.value
                ?.let(::parsed)
                ?.let { (it as? JsonObject)?.get("to") }
                ?.let { runCatching { RecordId.fromWire(SyncCollection.EPISODE, it) }.getOrNull() }
                as? RecordId.Episode
                ?: return null
        return ChangeDto(coll = collection.wire, id = from.toWire(), op = "rekey", to = to.toWire())
    }

    private fun parseJsonObject(text: String): JsonObject? =
        runCatching {
            ch.lkmc.neutrodyne.sync.protocol.SyncJson
                .parseToJsonElement(text) as? JsonObject
        }.getOrNull()

    private fun parsed(text: String): JsonElement? =
        runCatching {
            ch.lkmc.neutrodyne.sync.protocol.SyncJson
                .parseToJsonElement(text)
        }.getOrNull()

    private suspend fun hintsFor(
        source: SourceReader,
        collection: SyncCollection,
        rid: String,
    ): MatchHints? =
        when (collection) {
            SyncCollection.EPISODE -> source.episode(rid)?.let(::episodeHints)
            SyncCollection.UPNEXT -> source.upnext(rid)?.let { episodeHints(it.episode) }
            SyncCollection.SESSION -> source.session()?.let { session -> episodeHintsOf(session) }
            else -> null
        }

    private fun episodeHintsOf(session: SessionSource): MatchHints? {
        val episode = session.episode ?: return null
        val podcast = session.podcast ?: return null
        return MatchHints(
            guid = episode.guid,
            enc = episode.enclosureUrl,
            title = episode.title,
            link = episode.link,
            ytId = episode.externalMediaId,
        )
    }

    private companion object {
        const val REKEY_FIELD = "~rekey"
        val HINTED = setOf(SyncCollection.EPISODE, SyncCollection.UPNEXT, SyncCollection.SESSION)
    }
}

private fun FieldValue.withClock(clock: Hlc): FieldValue = copy(c = clock.toString())
