// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal actual fun dropTrailingTitleMarks(text: String): String {
    var end = text.length
    val marks =
        setOf(
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
        )
    while (end > 0) {
        val point = Character.codePointBefore(text, end)
        if (point != 0x200d && point !in 0xfe00..0xfe0f && point !in 0xe0100..0xe01ef &&
            Character.getType(point) !in marks
        ) {
            break
        }
        end -= Character.charCount(point)
    }
    return text.substring(0, end)
}

internal actual fun localDate(epochMillis: Long): String =
    Instant
        .ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toString()

internal actual fun httpDateMillis(value: String): Long? =
    try {
        ZonedDateTime
            .parse(
                value,
                DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US),
            ).toInstant()
            .toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

internal actual class PlatformSriHasher actual constructor(
    private val roots: StorageRoots,
    private val files: FileSystem,
) : SriHasher {
    actual override suspend fun matches(
        rootId: String,
        path: String,
        integrity: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val tokens =
                integrity.split(Regex("\\s+")).mapNotNull { token ->
                    val algorithm =
                        when (token.substringBefore('-')) {
                            "sha256" -> "SHA-256"
                            "sha384" -> "SHA-384"
                            "sha512" -> "SHA-512"
                            else -> return@mapNotNull null
                        }
                    val digest =
                        okio.ByteString.Companion.run { token.substringAfter('-').substringBefore('?').decodeBase64() }
                            ?: return@mapNotNull null
                    algorithm to digest.toByteArray()
                }
            if (tokens.isEmpty()) return@withContext false
            // SRI chooses the strongest supported algorithm, so a matching weak token cannot mask a bad strong token.
            val strongest = tokens.maxOf { it.first }
            val candidates = tokens.filter { it.first == strongest }
            val root = roots.resolve(rootId) ?: throw okio.IOException("Download root unavailable")
            require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path)
            var absolute = root.absolutePath.toPath()
            for (segment in path.split('/')) {
                require(segment.isNotEmpty() && segment != "." && segment != "..")
                absolute /= segment
                require(files.metadataOrNull(absolute)?.symlinkTarget == null)
            }
            val digest = MessageDigest.getInstance(strongest)
            files.source(absolute).use { source ->
                val buffer = okio.Buffer()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (source.read(buffer, TRANSFER_BUFFER_BYTES.toLong()) == -1L) break
                    digest.update(buffer.readByteArray())
                }
            }
            val actual = digest.digest()
            candidates.any { MessageDigest.isEqual(it.second, actual) }
        }
}
