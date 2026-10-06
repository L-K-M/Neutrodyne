// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import kotlinx.serialization.json.Json

/**
 * The shared `Json` for every Ktor client's `ContentNegotiation` (01 Serialization):
 * unknown fields are ignored, absent fields stay absent, defaults aren't emitted.
 */
internal val NeutrodyneJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
