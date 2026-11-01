// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

sealed class ValidatedChange {
    data class Write(
        val id: RecordId,
        val state: RecordState,
    ) : ValidatedChange()

    data class Rekey(
        val from: RecordId.Episode,
        val to: RecordId.Episode,
    ) : ValidatedChange()
}

/** Validation is shared, but per-key settings ranges and GroupNames normalization stay with their owners. */
object ProtocolValidation {
    fun change(
        dto: ChangeDto,
        serverTimeMs: Long? = null,
    ): ValidatedChange =
        checked {
            val coll = SyncCollection.fromWire(dto.coll)
            val id = RecordId.fromWire(coll, dto.id)
            if (dto.op == "rekey") {
                if (coll != SyncCollection.EPISODE || dto.fields.isNotEmpty() ||
                    dto.match != null || dto.matchClocks.isNotEmpty()
                ) {
                    invalid("invalid rekey")
                }
                val to = RecordId.fromWire(coll, dto.to ?: invalid("rekey needs target")) as RecordId.Episode
                val from = id as RecordId.Episode
                if (from == to || from.podcast != to.podcast) invalid("rekey must change key within podcast")
                return@checked ValidatedChange.Rekey(from, to)
            }
            if (dto.op != null || dto.to != null || dto.fields.isEmpty()) invalid("invalid write operation")
            val state = readState(coll, dto.fields, dto.match, dto.matchClocks, null, serverTimeMs)
            ValidatedChange.Write(id, state)
        }

    fun record(dto: RecordDto): RecordState =
        checked {
            val coll = SyncCollection.fromWire(dto.coll)
            val id = RecordId.fromWire(coll, dto.id)
            if (dto.seq < 0L) invalid("negative record sequence")
            dto.by?.let(::validateUuid)
            val redirect = dto.mergedInto?.let { RecordId.fromWire(coll, it) }
            if (redirect == id) invalid("self redirect")
            readState(coll, dto.fields, dto.match, dto.matchClocks, redirect, null)
        }

    fun state(record: RecordState) {
        if (record.fields.size + record.growSets.size > ProtocolLimits.MAX_FIELDS_PER_RECORD) invalid("too many fields")
        if (record.fields.keys.any { it in record.growSets }) invalid("duplicate field kind")
        record.fields.forEach { (name, field) ->
            field(record.coll, name, FieldValue(field.v, field.c?.toString(), field.reset.takeIf { it }))
        }
        record.growSets.forEach { (name, values) -> field(record.coll, name, FieldValue(add = values.toList())) }
        playedPair(record.coll, record.fields)
        hints(record.coll, record.match)
        if (record.matchClocks.keys.any { it !in record.match?.values().orEmpty() }) invalid("orphan hint clock")
        record.mergedInto?.let {
            if (RecordId.fromWire(record.coll, it.toWire()) != it) invalid("redirect collection mismatch")
        }
    }

    fun field(
        coll: SyncCollection,
        name: String,
        value: FieldValue,
    ) {
        if (name.isEmpty() || !fitsUtf8(name, ProtocolLimits.MAX_STRING)) invalid("invalid field name")
        val spec =
            coll.fields[name] ?: FieldSpec(
                name,
                FieldKind.LWW,
                ValueType.JSON,
                nullability = Nullability.NULLABLE,
            )
        if (spec.kind == FieldKind.GROW_SET) {
            if (value.v != null || value.c != null || value.reset != null || value.add == null ||
                value.add.size > ProtocolLimits.MAX_GROW_SET
            ) {
                invalid("invalid grow set")
            }
            value.add.forEach {
                text(it, spec.maxBytes)
            }
            return
        }
        if (value.add != null || value.v == null) invalid("field requires value")
        if (spec.kind != FieldKind.MIN_FIELD && spec.kind != FieldKind.MAX_FIELD &&
            value.c == null
        ) {
            invalid("field requires clock")
        }
        value.c?.let(::clock)
        if (value.reset != null && spec.kind != FieldKind.LWW_POSITION) invalid("reset only applies to positions")
        validateValue(spec, value.v)
    }

    fun admit(
        clock: Hlc,
        serverTimeMs: Long,
    ) {
        require(serverTimeMs in 0..Hlc.MAX_MILLIS) { "server time out of range" }
        if (clock.millis < ProtocolLimits.MIN_CLOCK_MS || clock.millis - serverTimeMs > ProtocolLimits.MAX_SKEW_MS) {
            throw ProtocolException(SyncErrorCode.CLOCK_SKEW, "clock outside admission bound")
        }
    }

    fun cursor(value: String) {
        val parts = value.split(':', limit = 4)
        if (parts.size != 3 || parts[0] != "c" || parts[1].length != 8 ||
            parts[1].any { it !in '0'..'9' && it !in 'a'..'f' } ||
            parts[2].isEmpty() || parts[2].any { it !in '0'..'9' } ||
            parts[2].toLongOrNull() == null
        ) {
            invalid("invalid cursor")
        }
    }

    fun device(dto: DeviceInfoDto) =
        checked {
            validateUuid(dto.id)
            deviceName(dto.name)
            if (dto.platform !in setOf("android", "windows", "macos", "linux")) invalid("invalid device platform")
            text(dto.appVersion, ProtocolLimits.MAX_STRING)
            if (dto.appVersion.isBlank()) invalid("empty app version")
            NodeId(dto.nodeId)
            Unit
        }

    fun deviceName(name: String) {
        if (name.trim().length !in 1..ProtocolLimits.MAX_DEVICE_NAME) invalid("invalid device name")
    }

    fun discovery(dto: DiscoveryDocument) {
        text(dto.name, ProtocolLimits.MAX_STRING)
        text(dto.serverVersion, ProtocolLimits.MAX_STRING)
        if (dto.name.isBlank() || dto.serverVersion.isBlank() || dto.protocol.min < 1 ||
            dto.protocol.max < dto.protocol.min ||
            dto.maxBatch !in 1..ProtocolLimits.MAX_CHANGES || dto.maxSkewMs < 0
        ) {
            invalid("invalid discovery document")
        }
        dto.features.forEach { text(it, ProtocolLimits.MAX_STRING) }
        timestamp(dto.serverTime)
    }

    internal fun json(
        value: JsonElement,
        depth: Int = 0,
    ) {
        if (depth > ProtocolLimits.MAX_JSON_DEPTH) invalid("JSON too deep")
        when (value) {
            is JsonObject -> {
                value.forEach { (key, child) ->
                    text(key, ProtocolLimits.MAX_STRING)
                    json(
                        child,
                        depth + 1,
                    )
                }
            }

            is JsonArray -> {
                value.forEach { json(it, depth + 1) }
            }

            is JsonPrimitive -> {
                text(value.content, ProtocolLimits.MAX_STRING)
                if (value != JsonNull && !value.isString && value.booleanOrNull == null &&
                    value.doubleOrNull?.isFinite() != true
                ) {
                    invalid("invalid JSON scalar")
                }
            }
        }
    }

    private fun readState(
        coll: SyncCollection,
        fields: Map<String, FieldValue>,
        match: MatchHints?,
        hintClocks: Map<String, String>,
        redirect: RecordId?,
        serverTimeMs: Long?,
    ): RecordState {
        if (fields.size > ProtocolLimits.MAX_FIELDS_PER_RECORD) invalid("too many fields")
        val states = mutableMapOf<String, FieldState>()
        val sets = mutableMapOf<String, Set<String>>()
        fields.forEach { (name, value) ->
            field(coll, name, value)
            val clock = value.c?.let(::clock)
            if (clock != null && serverTimeMs != null) admit(clock, serverTimeMs)
            if (value.add != null) {
                sets[name] = value.add.toSet()
            } else {
                states[name] = FieldState(requireNotNull(value.v), clock, value.reset == true)
            }
        }
        val anchor = states.values.mapNotNull { it.c }.maxOrNull()
        val hints = match?.values().orEmpty()
        if (hintClocks.keys.any { it !in hints }) invalid("orphan hint clock")
        val clocks =
            hints.mapValues { (name, _) ->
                hintClocks[name]?.let(::clock) ?: anchor
                    ?: invalid("hint needs clock anchor")
            }
        if (serverTimeMs != null) clocks.values.forEach { admit(it, serverTimeMs) }
        val record = RecordState(coll, states.toMap(), sets.toMap(), match, redirect, clocks)
        state(record)
        return record
    }

    private fun playedPair(
        coll: SyncCollection,
        fields: Map<String, FieldState>,
    ) {
        if (coll != SyncCollection.EPISODE || ("played" !in fields && "playedAt" !in fields)) return
        val played = fields["played"] ?: invalid("played pair incomplete")
        val at = fields["playedAt"] ?: invalid("played pair incomplete")
        if (played.c != at.c || bool(played.v) != (at.v != JsonNull)) invalid("inconsistent played pair")
    }

    private fun validateValue(
        spec: FieldSpec,
        value: JsonElement,
    ) {
        json(value)
        if (value == JsonNull) {
            if (spec.nullability != Nullability.NULLABLE) invalid("null not allowed")
            return
        }
        when (spec.type) {
            ValueType.JSON -> {
                return
            }

            ValueType.BOOLEAN -> {
                bool(value)
            }

            ValueType.INTEGER -> {
                val number = integer(value)
                range(spec, number.toDouble())
            }

            ValueType.NUMBER -> {
                val number = primitive(value).takeUnless { it.isString }?.doubleOrNull ?: invalid("number required")
                if (!number.isFinite()) invalid("finite number required")
                range(spec, number)
            }

            ValueType.TEXT, ValueType.URL -> {
                val text = string(value)
                text(text, spec.maxBytes)
                if (spec.choices.isNotEmpty() && text !in spec.choices) invalid("unsupported field value")
                if (spec.type == ValueType.URL) url(text)
                if (spec.name == "ok" && !OrderKey.isValid(text)) invalid("invalid order key")
            }

            ValueType.POSITION -> {
                val obj = value as? JsonObject ?: invalid("position must be object")
                if (integer(obj["ms"]) < 0) invalid("negative position")
                obj["dur"]?.takeUnless { it == JsonNull }?.let { if (integer(it) < 0) invalid("negative duration") }
                obj["src"]?.let { if (string(it) !in setOf("STREAM", "DOWNLOAD")) invalid("invalid position source") }
            }

            ValueType.AUTH -> {
                val obj = value as? JsonObject ?: invalid("auth must be object")
                string(obj["u"])
                string(obj["p"])
            }

            ValueType.EPISODE_REF -> {
                RecordId.fromWire(SyncCollection.EPISODE, value)
            }

            ValueType.CONTEXT -> {
                context(value)
            }

            ValueType.STRING_SET -> {
                invalid("grow set requires add")
            }
        }
    }

    private fun context(value: JsonElement) {
        val obj = value as? JsonObject ?: invalid("context must be object")
        val type = string(obj["type"])
        if (type !in
            setOf(
                "GROUP",
                "PODCAST",
                "ALL",
                "UNGROUPED",
                "DOWNLOADS",
                "EXTERNAL",
            )
        ) {
            invalid("invalid context type")
        }
        obj["group"]?.takeUnless { it == JsonNull }?.let { GroupId(string(it)) }
        obj["podcast"]?.takeUnless { it == JsonNull }?.let { PodcastId(string(it)) }
        if (type == "GROUP" && obj["group"].let { it == null || it == JsonNull }) invalid("group context needs group")
        if (type == "PODCAST" &&
            obj["podcast"].let {
                it == null ||
                    it == JsonNull
            }
        ) {
            invalid("podcast context needs podcast")
        }
        obj["order"]?.let { if (string(it) !in setOf("NEWEST_FIRST", "OLDEST_FIRST")) invalid("invalid context order") }
        obj["filters"]?.let {
            val filters = it as? JsonObject ?: invalid("filters must be object")
            filters["flags"]?.let { if (integer(it) !in 0L..7L) invalid("invalid context flags") }
            filters["media"]?.let {
                if (string(it) !in
                    setOf(
                        "ALL",
                        "AUDIO",
                        "VIDEO",
                    )
                ) {
                    invalid("invalid context media")
                }
            }
        }
        obj["anchor"]?.takeUnless { it == JsonNull }?.let { RecordId.fromWire(SyncCollection.EPISODE, it) }
        listOf("minSortDate", "anchorSortDate").forEach { name ->
            obj[name]?.takeUnless { it == JsonNull }?.let(::integer)
        }
    }

    private fun hints(
        coll: SyncCollection,
        match: MatchHints?,
    ) {
        if (match == null) return
        if (coll !in
            setOf(
                SyncCollection.EPISODE,
                SyncCollection.UPNEXT,
                SyncCollection.SESSION,
            )
        ) {
            invalid("unexpected match hints")
        }
        match.values().forEach { (name, value) ->
            text(value.content, if (name == "title") ProtocolLimits.MAX_TITLE else ProtocolLimits.MAX_STRING)
            if (name == "enc" || name == "link") url(value.content)
        }
        match.date?.let(::timestamp)
        if (match.kv != null && match.kv < 1) invalid("invalid key version")
    }

    private fun timestamp(value: String) {
        if (!value.endsWith('Z')) invalid("timestamp must be UTC")
        try {
            kotlin.time.Instant.parse(value)
        } catch (_: IllegalArgumentException) {
            invalid("invalid timestamp")
        }
    }

    private fun range(
        spec: FieldSpec,
        number: Double,
    ) {
        if ((spec.min != null && number < spec.min) || (
                spec.max != null &&
                    number > spec.max
            )
        ) {
            invalid("field out of range")
        }
    }

    private fun url(value: String) {
        val scheme =
            when {
                value.startsWith("https://", ignoreCase = true) -> "https://".length
                value.startsWith("http://", ignoreCase = true) -> "http://".length
                else -> invalid("URL must be http or https")
            }
        val authority = value.drop(scheme).takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isBlank() || '@' in authority ||
            value.any {
                it.isWhitespace() || it.code < 0x20 ||
                    it == '\\'
            }
        ) {
            invalid("invalid URL authority")
        }
    }

    private fun text(
        value: String,
        cap: Int,
    ) {
        if (!fitsUtf8(value, cap)) invalid("string exceeds byte cap")
    }

    private fun primitive(value: JsonElement?): JsonPrimitive = value as? JsonPrimitive ?: invalid("primitive required")

    private fun string(value: JsonElement?): String =
        primitive(value).takeIf { it.isString }?.content
            ?: invalid("string required")

    private fun bool(value: JsonElement?): Boolean =
        primitive(value).takeUnless { it.isString }?.booleanOrNull
            ?: invalid("boolean required")

    private fun integer(value: JsonElement?): Long =
        primitive(value).takeUnless { it.isString }?.longOrNull
            ?: invalid("integer required")

    private fun clock(value: String): Hlc = checked { Hlc.parse(value) }

    private inline fun <T> checked(block: () -> T): T =
        try {
            block()
        } catch (failure: ProtocolException) {
            throw failure
        } catch (_: IllegalArgumentException) {
            invalid("invalid protocol value")
        }
}
