// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.crossdevice

import ch.lkmc.neutrodyne.core.database.SyncHeldEntity
import ch.lkmc.neutrodyne.sync.impl.HeldState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A held-row contract failure. The message is a fixed, JSON-free reason, so it is safe to write
 * to `result.json` and log; no raw payload, summary or exception detail is attached.
 */
class HeldDumpException(
    message: String,
) : Exception(message)

/** One payload removal's canonical identity: the payload's rid spelling and its staged clock. */
internal data class HeldIdentity(
    val rid: String,
    val clock: String,
)

/** The validated payload (the `sync_held.batch` JSON): state plus the removal identities. */
internal data class HeldPayload(
    val state: String,
    val podcasts: List<HeldIdentity>,
    val groups: List<HeldIdentity>,
)

/** The validated prompt summary (the `sync_held.summary` JSON): display titles only. */
internal data class HeldSummary(
    val podcasts: List<String>,
    val groups: List<String>,
)

/**
 * The `dump` phase's held-row projection (10 Cross-device journey), shared by the Android driver
 * and its host regressions.
 *
 * The host proves exact podcast identities before approval, so the entry carries the payload's
 * `{rid, clock}` removals verbatim; the summary's display titles ride along separately for the
 * prompt. Both are validated strictly: a malformed payload or summary, a blank or duplicate rid,
 * a missing or non-string clock, a non-string title or a summary/payload count mismatch throws
 * [HeldDumpException] rather than reporting a partial, title-only or empty result.
 */
fun heldDumpEntry(row: SyncHeldEntity): JsonObject {
    val payload = parseHeldPayload(row.batch)
    val summary = parseHeldSummary(row.summary)
    if (summary.podcasts.size != payload.podcasts.size) {
        throw HeldDumpException("held summary podcast count does not match the payload")
    }
    if (summary.groups.size != payload.groups.size) {
        throw HeldDumpException("held summary group count does not match the payload")
    }
    return buildJsonObject {
        put("id", row.id)
        put("heldAt", row.heldAt)
        put("state", payload.state)
        put("podcasts", identitiesJson(payload.podcasts))
        put("groups", identitiesJson(payload.groups))
        put("podcastTitles", stringsJson(summary.podcasts))
        put("groupTitles", stringsJson(summary.groups))
    }
}

/** The payload's state and removal identities, or a [HeldDumpException] with a safe reason. */
internal fun parseHeldPayload(json: String): HeldPayload {
    val obj = parseObject(json, "held payload")
    val state = obj.stringField("state") ?: throw HeldDumpException("held payload state is missing or not a string")
    if (state.isBlank()) throw HeldDumpException("held payload state is blank")
    if (state !in heldStates()) throw HeldDumpException("held payload state is not recognized")
    return HeldPayload(
        state = state,
        podcasts = parseIdentities(obj["podcasts"], "podcast"),
        groups = parseIdentities(obj["groups"], "group"),
    )
}

/** The summary's display titles, or a [HeldDumpException] with a safe reason. */
internal fun parseHeldSummary(json: String): HeldSummary {
    val obj = parseObject(json, "held summary")
    return HeldSummary(
        podcasts = parseTitles(obj["podcasts"], "podcast"),
        groups = parseTitles(obj["groups"], "group"),
    )
}

/** The current payload states, read from the production enum rather than a copied list. */
private fun heldStates(): Set<String> = HeldState.entries.map { it.name }.toSet()

private fun parseObject(
    json: String,
    field: String,
): JsonObject =
    try {
        heldJson.parseToJsonElement(json).jsonObject
    } catch (_: Exception) {
        throw HeldDumpException("$field is not a JSON object")
    }

private fun parseIdentities(
    element: JsonElement?,
    kind: String,
): List<HeldIdentity> {
    val array =
        element as? JsonArray
            ?: throw HeldDumpException("held payload $kind removals are not an array")
    val identities =
        array.mapIndexed { index, removal ->
            val obj =
                removal as? JsonObject
                    ?: throw HeldDumpException("held payload $kind removal $index is not an object")
            val rid = obj.stringField("rid")
            if (rid == null || rid.isBlank()) {
                throw HeldDumpException("held payload $kind removal $index rid is missing or blank")
            }
            val clock = obj.stringField("clock")
            if (clock == null) {
                throw HeldDumpException("held payload $kind removal $index clock is missing or not a string")
            }
            HeldIdentity(rid, clock)
        }
    val rids = identities.map { it.rid }
    if (rids.toSet().size != rids.size) {
        throw HeldDumpException("held payload repeats a $kind rid")
    }
    return identities
}

private fun parseTitles(
    element: JsonElement?,
    kind: String,
): List<String> {
    val array =
        element as? JsonArray
            ?: throw HeldDumpException("held summary $kind titles are not an array")
    return array.mapIndexed { index, title ->
        val primitive = title as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw HeldDumpException("held summary $kind title $index is not a string")
        }
        primitive.content
    }
}

private fun JsonObject.stringField(field: String): String? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

private fun identitiesJson(identities: List<HeldIdentity>): JsonArray =
    buildJsonArray {
        identities.forEach { identity ->
            add(
                buildJsonObject {
                    put("rid", identity.rid)
                    put("clock", identity.clock)
                },
            )
        }
    }

private fun stringsJson(values: List<String>): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

private val heldJson: Json =
    Json {
        ignoreUnknownKeys = true
    }
