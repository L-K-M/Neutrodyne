// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.model.update.UpdateApk
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckError
import ch.lkmc.neutrodyne.core.model.update.UpdateDesktopAsset
import ch.lkmc.neutrodyne.core.model.update.UpdateManifest
import ch.lkmc.neutrodyne.core.model.update.UpdateServerInfo
import dev.zacsweers.metro.Inject
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Inject
internal class UpdateManifestParser(
    private val build: BuildInfo,
) : UpdateParser {
    private val repo = build.repoUrl.removeSuffix("/")
    private val repoSegments = Url(repo).encodedPath.removePrefix("/").split("/")

    override fun parse(bytes: ByteArray): Outcome<UpdateManifest, UpdateCheckError> {
        if (bytes.size > MAX_UPDATE_MANIFEST_BYTES) return invalid()

        return try {
            val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            require(root.number("schema") == 1L)
            val name = root.text("versionName")
            val code = root.number("versionCode")
            require(code == VersionScheme.versionCode(name))
            val minSdk = root.positiveInt("minSdk")
            val published = root.text("published")
            // Parse the calendar as well as the UTC syntax: February 30 is not a release date.
            require(UTC_DATE.matches(published))
            LocalDateTime.parse(published.removeSuffix("Z"))
            val notes = root.text("notes")
            require(notes.isNotBlank() && notes.codePointsCount() <= MAX_NOTES_CHARACTERS)
            val release = link(root.text("releaseUrl"), listOf("releases", "tag", "v$name"))
            val apks =
                root.array("apks").mapNotNull { element ->
                    val entry = element.jsonObject
                    val abi = entry.text("abi")
                    if (abi !in KNOWN_ABIS) return@mapNotNull null

                    val asset = asset(entry, name)
                    UpdateApk(abi, asset.file, asset.url, asset.size, asset.hash)
                }
            require(apks.distinctBy { it.abi }.size == apks.size)
            val desktop =
                root.optionalArray("desktop").mapNotNull { element ->
                    val entry = element.jsonObject
                    val os = DesktopOs.entries.find { it.wire == entry.text("os") } ?: return@mapNotNull null
                    val arch = DesktopArch.entries.find { it.wire == entry.text("arch") } ?: return@mapNotNull null
                    val kind = InstallKind.entries.find { it != InstallKind.DEV && it.wire == entry.text("kind") }
                    if (kind == null) return@mapNotNull null

                    val asset = asset(entry, name)
                    val minOs = entry["minOs"]?.let { entry.text("minOs") }
                    require(minOs == null || numericOsVersion(minOs) != null)
                    UpdateDesktopAsset(os, arch, kind, asset.file, asset.url, asset.size, asset.hash, minOs)
                }
            require(desktop.distinctBy { Triple(it.os, it.arch, it.kind) }.size == desktop.size)
            val server =
                root["server"]?.let { element ->
                    val entry = element.jsonObject
                    val jar = asset(entry.getValue("jar").jsonObject, name)
                    val image = entry.text("image")
                    val owner = repoSegments.first().lowercase()
                    require(image.startsWith("ghcr.io/$owner/neutrodyne-server@sha256:"))
                    require(HASH.matches(image.substringAfter("@sha256:")))
                    UpdateServerInfo(jar.file, jar.url, jar.size, jar.hash, image, entry.positiveInt("minJava"))
                }
            Outcome.Success(
                UpdateManifest(
                    name,
                    code,
                    minSdk,
                    published,
                    notes,
                    release,
                    apks.toImmutableList(),
                    desktop.toImmutableList(),
                    server,
                ),
            )
        } catch (_: CharacterCodingException) {
            invalid()
        } catch (_: IllegalArgumentException) {
            invalid()
        } catch (_: IllegalStateException) {
            invalid()
        } catch (_: NoSuchElementException) {
            invalid()
        }
    }

    private fun asset(
        entry: JsonObject,
        version: String,
    ): Asset {
        val file = entry.text("file")
        require(FILE.matches(file) && file != "." && file != "..")
        val url = link(entry.text("url"), listOf("releases", "download", "v$version", file))
        val size = entry.number("size")
        require(size > 0)
        val hash = entry.text("sha256")
        require(HASH.matches(hash))
        return Asset(file, url, size, hash.lowercase())
    }

    private fun link(
        raw: String,
        suffix: List<String>,
    ): String {
        // Check the spelling before Ktor can normalize traversal, empty segments or backslashes.
        val match = RAW_URL.matchEntire(raw) ?: error("Invalid release URL")
        val host = match.groupValues[1]
        require(host.all { it <= '\u007f' } && host.lowercase() == "github.com")
        val segments =
            match.groupValues[2].removePrefix("/").split("/").map { segment ->
                require(segment.isNotEmpty() && VALID_ESCAPES.matches(segment))
                val decoded = segment.decodeURLPart()
                require(decoded != "." && decoded != ".." && '/' !in decoded && '\\' !in decoded)
                decoded
            }
        require(segments == repoSegments + suffix)
        val parsed = Url(raw)
        require(
            parsed.protocol.name == "https" &&
                parsed.host.equals(
                    "github.com",
                    ignoreCase = true,
                ) && parsed.port == 443,
        )
        return "$repo/${suffix.joinToString("/")}"
    }

    private data class Asset(
        val file: String,
        val url: String,
        val size: Long,
        val hash: String,
    )

    private companion object {
        const val MAX_NOTES_CHARACTERS = 500
        val UTC_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z")
        val HASH = Regex("[0-9a-fA-F]{64}")
        val FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        val RAW_URL = Regex("https://([^/:@?#]+)(?::443)?(/[^?#\\\\\\s]*)")
        val VALID_ESCAPES = Regex("(?:[^%]|%[0-9a-fA-F]{2})*")
        val KNOWN_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

        fun invalid() = Outcome.Failure(UpdateCheckError.MANIFEST_INVALID)
    }
}

internal fun JsonObject.text(key: String): String {
    val value = getValue(key) as? JsonPrimitive ?: error("Expected text")
    require(value.isString)
    return value.content
}

internal fun JsonObject.number(key: String): Long {
    val value = getValue(key) as? JsonPrimitive ?: error("Expected integer")
    require(!value.isString && Regex("0|[1-9][0-9]*").matches(value.content))
    return value.content.toLong()
}

internal fun JsonObject.positiveInt(key: String): Int {
    val value = number(key)
    require(value in 1..Int.MAX_VALUE.toLong())
    return value.toInt()
}

private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

private fun JsonObject.optionalArray(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())

// Kotlin String.length counts UTF-16 units; the producer's Python len counts Unicode code points.
private fun String.codePointsCount(): Int {
    var index = 0
    var count = 0
    while (index < length) {
        val character = this[index++]
        if (character in '\uD800'..'\uDBFF') {
            require(index < length && this[index] in '\uDC00'..'\uDFFF')
            index++
        } else {
            require(character !in '\uDC00'..'\uDFFF')
        }
        count++
    }
    return count
}
