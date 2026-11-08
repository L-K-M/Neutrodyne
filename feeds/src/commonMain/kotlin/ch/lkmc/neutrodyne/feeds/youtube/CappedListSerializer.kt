// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.youtube

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure

/** Enforce row limits during decoding, before a compact hostile array allocates millions of DTOs. */
internal open class CappedListSerializer<T>(
    private val element: KSerializer<T>,
) : KSerializer<List<T>> {
    private val delegate = ListSerializer(element)
    override val descriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<T> =
        decoder.decodeStructure(descriptor) {
            val items = ArrayList<T>()
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                checkItemCount(items.size + 1)
                items += decodeSerializableElement(descriptor, index, element)
            }
            items
        }

    override fun serialize(
        encoder: Encoder,
        value: List<T>,
    ) = delegate.serialize(encoder, value)
}
