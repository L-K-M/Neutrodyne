// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol.dto

import ch.lkmc.neutrodyne.sync.protocol.invalid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Nullable JsonElement's default serializer collapses explicit null into absence; field v cannot. */
object FieldValueSerializer : KSerializer<FieldValue> {
    override val descriptor = buildClassSerialDescriptor("FieldValue")

    override fun serialize(
        encoder: Encoder,
        value: FieldValue,
    ) {
        val json = encoder as? JsonEncoder ?: invalid("field encoding requires JSON")
        json.encodeJsonElement(
            buildJsonObject {
                value.v?.let { put("v", it) }
                value.c?.let { put("c", it) }
                value.reset?.let { put("reset", it) }
                value.add?.let { put("add", JsonArray(it.map(::JsonPrimitive))) }
            },
        )
    }

    override fun deserialize(decoder: Decoder): FieldValue {
        val obj = ((decoder as? JsonDecoder)?.decodeJsonElement() as? JsonObject) ?: invalid("field must be an object")
        val clock = obj["c"]?.takeUnless { it == JsonNull }?.let(::string)
        val reset =
            obj["reset"]?.takeUnless { it == JsonNull }?.let {
                (it as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid("reset must be boolean")
            }
        val add =
            obj["add"]?.takeUnless { it == JsonNull }?.let {
                (it as? JsonArray)?.map(::string) ?: invalid("add must be an array")
            }
        return FieldValue(obj["v"], clock, reset, add)
    }

    private fun string(value: JsonElement): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("field member must be a string")
}
