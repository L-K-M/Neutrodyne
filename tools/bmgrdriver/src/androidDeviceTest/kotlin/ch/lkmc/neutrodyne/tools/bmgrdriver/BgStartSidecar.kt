// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The bgseed -> bgstart handoff record in the driver's `files/bmgr/` vault: nonce and API
 * bind the leg, `port`/`enclosureUrl` name the loopback enclosure the restored session's
 * current episode must stream from the driver's own HTTP server. The host checker
 * validates the whole record so a stale or foreign sidecar fails closed.
 */
@Serializable
internal data class BgStartSidecar(
    val v: Int = 1,
    val nonce: String,
    val api: Int,
    val seedSha256: String,
    val seedBytes: Long,
    val port: Int,
    val enclosureUrl: String,
    val fixture: FixtureContent,
) {
    companion object {
        private val json = Json { prettyPrint = false }

        fun read(file: File): BgStartSidecar = json.decodeFromString(serializer(), file.readText())

        fun write(
            file: File,
            sidecar: BgStartSidecar,
        ) = file.writeText(json.encodeToString(serializer(), sidecar))
    }
}
