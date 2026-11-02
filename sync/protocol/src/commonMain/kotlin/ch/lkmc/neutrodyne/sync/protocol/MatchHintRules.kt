// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int

internal fun MatchHints.values(): Map<String, JsonPrimitive> =
    buildMap {
        guid?.let { put("guid", JsonPrimitive(it)) }
        enc?.let { put("enc", JsonPrimitive(it)) }
        title?.let { put("title", JsonPrimitive(it)) }
        date?.let { put("date", JsonPrimitive(it)) }
        link?.let { put("link", JsonPrimitive(it)) }
        ytId?.let { put("ytId", JsonPrimitive(it)) }
        kv?.let { put("kv", JsonPrimitive(it)) }
    }

internal fun hintsFrom(values: Map<String, JsonPrimitive>): MatchHints? =
    if (values.isEmpty()) {
        null
    } else {
        MatchHints(
            guid = values["guid"]?.content,
            enc = values["enc"]?.content,
            title = values["title"]?.content,
            date = values["date"]?.content,
            link = values["link"]?.content,
            ytId = values["ytId"]?.content,
            kv = values["kv"]?.int,
        )
    }
