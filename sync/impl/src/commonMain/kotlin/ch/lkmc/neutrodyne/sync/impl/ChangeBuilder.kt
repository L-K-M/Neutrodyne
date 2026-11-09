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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
    private val clockStore = SyncClockStore(db)

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
                    rekeyChange(collection, id, rekey)?.let { return@mapNotNull it }
                }
                val fields = mutableMapOf<String, FieldValue>()
                var hints: MatchHints? = null
                // 10 Passwords / origin association: the `auth` emitted into this change is
                // provable only with its companion `credentialOrigin` at the same complete
                // clock. The companion is stamped after the whole group so row ordering —
                // a newer standalone origin intent in the same batch — cannot overwrite it
                // or lend a different origin to the snapshot; that newer intent stays
                // pending in the outbox under normal sent-clock acknowledgement instead.
                var authCompanion: String? = null
                for (row in recordRows.sortedBy { it.field }) {
                    if (row.field == REKEY_FIELD) continue
                    val clock = outboxClock(row) ?: continue
                    if (row.field == "*") {
                        authCompanion = addAllFields(source, collection, rid, clock, fields)
                    } else {
                        addField(source, collection, rid, row, clock, fields)?.let {
                            authCompanion = it
                        }
                    }
                    if (collection in HINTED) {
                        hints = hintsFor(source, collection, rid) ?: hints
                    }
                }
                if (fields.isEmpty()) return@mapNotNull null
                fields["auth"]?.let { auth ->
                    if (authCompanion != null && auth.c != null) {
                        fields["credentialOrigin"] =
                            FieldValue(JsonPrimitive(authCompanion), auth.c)
                    }
                }
                ChangeDto(coll, id.toWire(), fields = fields, match = hints)
            }
    }

    /**
     * Emits one outbox row's field; returns the proven `credentialOrigin` companion when
     * this row emitted an `auth`, for the post-group pairing stamp.
     */
    private suspend fun addField(
        source: SourceReader,
        collection: SyncCollection,
        rid: String,
        row: SyncOutboxEntity,
        clock: Hlc,
        fields: MutableMap<String, FieldValue>,
    ): String? {
        when (row.field) {
            "played" -> {
                val element = row.value?.let(::parsed)
                val objectLiteral = element as? JsonObject
                if (objectLiteral != null) {
                    val played = (objectLiteral.get("played") as? JsonPrimitive)?.booleanOrNull ?: return null
                    val playedAt = (objectLiteral.get("playedAt") as? JsonPrimitive)?.longOrNull
                    fields["played"] = FieldValue(JsonPrimitive(played), clock.toString())
                    fields["playedAt"] = FieldValue(playedAt?.let(::JsonPrimitive) ?: JsonNull, clock.toString())
                } else if (element is JsonPrimitive) {
                    // A replay row carries the boolean alone; `playedAt` is a separate row.
                    fields["played"] = FieldValue(element, clock.toString())
                }
            }

            "pos" -> {
                val literal = row.value?.let(::parseJsonObject)
                val reset = (literal?.get("reset") as? JsonPrimitive)?.booleanOrNull == true
                val ms = (literal?.get("ms") as? JsonPrimitive)?.longOrNull ?: 0L
                fields["pos"] =
                    FieldValue(JsonObject(buildMap { put("ms", JsonPrimitive(ms)) }), clock.toString(), reset)
            }

            "feedKeys" -> {
                // A replay row carries the known set as its literal; a local row reads the source.
                val replay =
                    row.captureKind == ch.lkmc.neutrodyne.core.database.SyncCaptureKind.REPLAY &&
                        row.value != null
                if (replay) {
                    val parsed =
                        runCatching {
                            ch.lkmc.neutrodyne.sync.protocol.SyncJson
                                .parseToJsonElement(row.value!!)
                                as? kotlinx.serialization.json.JsonArray
                        }.getOrNull()
                    val keys = parsed?.map { it.jsonPrimitive.content } ?: emptyList()
                    if (keys.isNotEmpty()) fields["feedKeys"] = FieldValue(add = keys)
                } else {
                    val keys = source.feedKeys(rid)
                    if (keys.isNotEmpty()) fields["feedKeys"] = FieldValue(add = keys)
                }
            }

            "auth" -> {
                if (!sharePasswords()) return null
                val reference = clockStore.get(collection.wire, rid)?.authReference
                if (row.captureKind == ch.lkmc.neutrodyne.core.database.SyncCaptureKind.REPLAY) {
                    // 10 Passwords: a replay reasserts only the exact immutable snapshot the
                    // bound reference points at, and only at the reference's own complete
                    // clock — never later live bytes or a mismatched reference clock. Its
                    // companion is the reference's proven origin.
                    if (reference != null && reference.clock == clock.toString()) {
                        secrets.get(reference.storeKey)?.let { stored ->
                            fields["auth"] = FieldValue(authValue(stored), clock.toString())
                            return reference.credentialOrigin
                        }
                    }
                } else {
                    // A local intent IS the current credential: emit the coherent pair —
                    // credentialId resolves the credential row's exact origin and the secret
                    // is read under that origin — so the pushed snapshot can never bind to
                    // an origin it did not write with.
                    source.authSnapshot(rid)?.let { (origin, value) ->
                        fields["auth"] = FieldValue(value, clock.toString())
                        return origin
                    }
                }
            }

            "v" -> {
                val literal = row.value ?: return null
                fields["v"] = FieldValue(parsed(literal) ?: return null, clock.toString())
            }

            else -> {
                val value = row.value?.let(::parsed) ?: source.field(collection, rid, row.field) ?: return null
                fields[row.field] = FieldValue(value, clock.toString())
            }
        }
        return null
    }

    /**
     * A `*` row expands to every field at its own clock; returns the `credentialOrigin`
     * companion when the expansion carried an `auth`, for the post-group pairing stamp.
     */
    private suspend fun addAllFields(
        source: SourceReader,
        collection: SyncCollection,
        rid: String,
        clock: Hlc,
        fields: MutableMap<String, FieldValue>,
    ): String? {
        val all = source.allFields(collection, rid)
        all.fields.forEach { (name, value) ->
            fields[name] = FieldValue(value.v, clock.toString())
        }
        all.growSets.forEach { (name, values) ->
            fields[name] = FieldValue(add = values.toList())
        }
        if (!fields.containsKey("auth")) return null
        return (all.fields["credentialOrigin"]?.v as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
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

    /** The `{u,p}` wire value of a protected-store entry. */
    private fun authValue(stored: ch.lkmc.neutrodyne.core.domain.StoredSecret): JsonObject =
        JsonObject(
            buildMap {
                put("u", JsonPrimitive(stored.username))
                put("p", JsonPrimitive(stored.secret.concatToString()))
            },
        )

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
