// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI

/**
 * The replay-response adaptation half of MD3 AC2's media slice: copies the recorded
 * `resolve` scenario into a caller-owned temp directory with only the recorded response
 * *bodies* rewritten — `https://media.example.invalid/audio` → the bound fixture URL and
 * `clen`/`"contentLength"` `1000` → the fixture's real length. This is the Android
 * `YouTubeReleaseSmokeTest` move: `ReplayRH` matches requests (`method` + scrubbed URL +
 * body), so rewritten responses keep strict replay working while the resolved media URL
 * points at loopback.
 *
 * The contract is strict so the proof can never replay a lie: [mediaUrl] must be plain
 * `http`, host `127.0.0.1`, the fixture's media path, a real bound port, and no
 * query/fragment/credentials — an external or non-loopback URL is refused; every recorded
 * `request` triple is preserved byte-exact (no wildcarding, no matcher changes); the known
 * placeholders and recorded length must be present before anything is rewritten;
 * itag/mime/duration/expiry/signature stay untouched because only the URL *prefix* and the
 * two length markers are replaced; the source scenario is never mutated (the target must
 * be a different directory); and no system properties are touched.
 *
 * This is internal proof machinery: the caller owns both directories and the eventual
 * fixture wiring (the armed `smoke` + `engineReplay` double gate) is a later slice — until
 * then nothing in production reaches this object.
 */
internal object RecordedMediaScenario {
    /** `http.json`'s file name inside a recorded scenario directory. */
    private const val HTTP_JSON = "http.json"

    /** The recorded media URL prefix the synthetic scenarios all carry. */
    private const val RECORDED_MEDIA_URL = "https://media.example.invalid/audio"

    /** The recorded stream's declared length (`clen`/`contentLength`). */
    private const val RECORDED_LENGTH = "1000"

    /**
     * Copies [sourceDir]'s recorded scenario into [targetDir] with the media URL and
     * declared length rewritten in every response body; returns the written `http.json`.
     * Fails closed on a missing scenario, a target that would overwrite the source, a
     * non-loopback [mediaUrl], or absent recorded placeholders.
     */
    fun rewriteResolve(
        sourceDir: File,
        targetDir: File,
        mediaUrl: String,
        mediaLength: Long,
    ): File {
        requireLoopbackMediaUrl(mediaUrl)
        require(mediaLength > 0) { "the fixture media length must be positive" }
        val source = File(sourceDir, HTTP_JSON)
        require(source.isFile) { "the recorded scenario has no $HTTP_JSON: $sourceDir" }
        require(sourceDir.canonicalFile != targetDir.canonicalFile) {
            "the rewritten scenario must not overwrite the recording"
        }

        val fixture = Json.parseToJsonElement(source.readText()).jsonObject
        require(!(fixture["exchanges"] as? JsonArray).isNullOrEmpty()) {
            "the recorded scenario carries no exchanges"
        }
        val bodies =
            fixture["bodies"] as? JsonArray
                ?: error("the recorded scenario carries no bodies")
        require(bodies.isNotEmpty() && bodies.all { it is JsonPrimitive && it.isString }) {
            "the recorded scenario bodies are not the expected string table"
        }
        val recorded = bodies.joinToString("\n") { it.jsonPrimitive.content }
        require(RECORDED_MEDIA_URL in recorded) { "the recorded media URL placeholder is missing" }
        require("clen=$RECORDED_LENGTH" in recorded) { "the recorded clen marker is missing" }
        require("\"contentLength\": \"$RECORDED_LENGTH\"" in recorded) {
            "the recorded contentLength marker is missing"
        }

        val rewritten =
            bodies.map { body ->
                JsonPrimitive(
                    body.jsonPrimitive.content
                        .replace(RECORDED_MEDIA_URL, mediaUrl)
                        .replace("clen=$RECORDED_LENGTH", "clen=$mediaLength")
                        .replace(
                            "\"contentLength\": \"$RECORDED_LENGTH\"",
                            "\"contentLength\": \"$mediaLength\"",
                        ),
                )
            }
        // Only `bodies` may change: every exchange keeps its recorded request triple (the
        // ReplayRH matcher key) and its status/headers/bodyRef projection untouched, and
        // the provenance string stays. Re-encoding the object preserves member order.
        val adapted = JsonObject(fixture + ("bodies" to JsonArray(rewritten)))
        check(adapted["exchanges"] == fixture["exchanges"] && adapted["provenance"] == fixture["provenance"]) {
            "the rewrite must keep every recorded request and the provenance exact"
        }

        targetDir.mkdirs()
        val target = File(targetDir, HTTP_JSON)
        target.writeText(Json.encodeToString(JsonElement.serializer(), adapted) + "\n")
        for (sibling in sourceDir.listFiles().orEmpty()) {
            if (sibling.isFile && sibling.name != HTTP_JSON) {
                sibling.copyTo(File(targetDir, sibling.name), overwrite = true)
            }
        }
        return target
    }

    /**
     * The fixture URL contract for the rewrite: plain `http` (the fixture serves no TLS),
     * the literal `127.0.0.1` host it binds, its media path, an explicit port, and no
     * query, fragment or credentials — anything else is a live-network URL in disguise and
     * is refused.
     */
    internal fun requireLoopbackMediaUrl(url: String) {
        val uri =
            try {
                URI(url)
            } catch (failure: Exception) {
                throw IllegalArgumentException("the fixture media URL does not parse", failure)
            }
        require(uri.scheme == "http") { "the fixture media URL must be plain http" }
        require(uri.host == "127.0.0.1") { "the fixture media URL must target the bound loopback host" }
        require(uri.port in 1..65535) { "the fixture media URL must carry the bound port" }
        require(uri.path == LoopbackMediaFixture.MEDIA_PATH) {
            "the fixture media URL must target ${LoopbackMediaFixture.MEDIA_PATH}"
        }
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) {
            "the fixture media URL carries no query, fragment or credentials"
        }
    }
}
