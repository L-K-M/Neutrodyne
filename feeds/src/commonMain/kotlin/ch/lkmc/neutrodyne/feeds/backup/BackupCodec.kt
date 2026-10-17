// SPDX-License-Identifier: Unlicense
@file:OptIn(ExperimentalSerializationApi::class)

package ch.lkmc.neutrodyne.feeds.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import okio.BufferedSink
import okio.BufferedSource
import okio.Source
import okio.buffer

/**
 * The version-1 backup codec (05 Full backup and restore): the Json flags the format mandates
 * — sparse writes (`encodeDefaults = false`, `explicitNulls = false`), forward-compatible reads
 * (`ignoreUnknownKeys = true`), no polymorphism — plus the read caps of 05 Validation:
 * `manifest.json` ≤ [MANIFEST_MAX_BYTES], `library.json` ≤ [LIBRARY_MAX_BYTES] and
 * `episodes.jsonl` lines ≤ [EPISODE_LINE_MAX_BYTES], streamed so a large file never
 * materialises.
 *
 * [mode] decides what the staging-only `EpisodeLineV1.fc` does on encode: it belongs only in
 * 10's first-link staging files, so ARCHIVE strips it — the invariant "fc never reaches an
 * archive" holds regardless of what a producer hands over. Decoding always accepts it.
 */
public class BackupCodec(
    private val mode: Mode = Mode.ARCHIVE,
) {
    public enum class Mode {
        /** Backup archives (`episodes.jsonl` inside a backup ZIP): staging fields stripped. */
        ARCHIVE,

        /** 10's first-link staging files: `fc` written, everything else identical. */
        STAGING,
    }

    /** The format's Json flags (05 Archive): sparse writes, forward-compatible reads. */
    private val json =
        Json {
            encodeDefaults = false
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    /** Encodes [manifest] to the `manifest.json` payload. */
    public fun encodeManifest(manifest: BackupManifest): String = json.encodeToString(manifest)

    /**
     * Decodes `manifest.json` after the format's checks: `format` must be [FORMAT]
     * ([BackupCodecException.NotABackup]) and `minReaderVersion` may not exceed
     * [FORMAT_VERSION] ([BackupCodecException.NewerFormat]) — a newer `formatVersion` is fine,
     * old readers safely ignore what it adds.
     */
    public fun decodeManifest(source: Source): BackupManifest {
        val text = source.buffer().readBounded(MANIFEST_MAX_BYTES)
        val element = parse(text)
        val format = (element as? JsonObject)?.get("format") as? JsonPrimitive
        if (format?.isString != true || format.content != FORMAT) {
            throw BackupCodecException.NotABackup
        }
        // The gate reads the raw field before the v1 decode: a newer manifest can change
        // the v1 shape, and "update the app" must still win over "corrupt" (05
        // Versioning). An absent or malformed field claims nothing — the full decode
        // then supplies the default or reports Corrupt.
        val minReaderVersion =
            ((element as? JsonObject)?.get("minReaderVersion") as? JsonPrimitive)?.intOrNull
                ?: MIN_READER_VERSION
        if (minReaderVersion > FORMAT_VERSION) {
            throw BackupCodecException.NewerFormat(minReaderVersion)
        }
        return decode(element)
    }

    /**
     * The `format` value of a `manifest.json` payload, or null when it is unreadable, oversized
     * or absent. The sniffer needs only this field, so a manifest whose other parts are damaged
     * or written by a newer app still routes into the backup pipeline, which then reports the
     * precise failure.
     */
    public fun manifestFormat(source: Source): String? {
        val buffered = source.buffer()
        if (buffered.request(MANIFEST_MAX_BYTES + 1)) return null
        val element =
            try {
                json.parseToJsonElement(buffered.readUtf8())
            } catch (e: SerializationException) {
                return null
            }
        val format = (element as? JsonObject)?.get("format") as? JsonPrimitive
        return format?.takeIf { it.isString }?.content
    }

    public fun encodeLibrary(library: LibraryV1): String = json.encodeToString(library)

    public fun decodeLibrary(source: Source): LibraryV1 = decode(parse(source.buffer().readBounded(LIBRARY_MAX_BYTES)))

    public fun encodeQueue(queue: QueueV1): String = json.encodeToString(queue)

    public fun decodeQueue(source: Source): QueueV1 = decode(parse(source.buffer().readUtf8()))

    public fun encodeSettings(settings: SettingsV1): String = json.encodeToString(settings)

    public fun decodeSettings(source: Source): SettingsV1 = decode(parse(source.buffer().readUtf8()))

    /**
     * One `episodes.jsonl` line, without the line terminator. In [Mode.ARCHIVE] the
     * staging-only `fc` is dropped here rather than trusted to every producer.
     */
    public fun encodeEpisodeLine(line: EpisodeLineV1): String =
        json.encodeToString(if (mode == Mode.STAGING) line else line.copy(fc = null))

    /** Writes [line] to [sink] followed by the `\n` terminator. */
    public fun writeEpisodeLine(
        sink: BufferedSink,
        line: EpisodeLineV1,
    ) {
        sink.writeUtf8(encodeEpisodeLine(line)).writeByte('\n'.code)
    }

    /**
     * Streams `episodes.jsonl` line by line without materialising the file: lines longer than
     * [EPISODE_LINE_MAX_BYTES] or failing to decode are skipped and counted in
     * [EpisodeLines.skippedLines] — a warning, never fatal (05 Validation and preview).
     */
    public fun episodeLines(source: Source): EpisodeLines = EpisodeLines(source, json)

    private fun parse(text: String): JsonElement =
        try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw BackupCodecException.Corrupt(e)
        }

    private inline fun <reified T> decode(element: JsonElement): T =
        try {
            json.decodeFromJsonElement<T>(element)
        } catch (e: SerializationException) {
            throw BackupCodecException.Corrupt(e)
        } catch (e: IllegalArgumentException) {
            // Enum names and other value failures surface as plain IllegalArgumentException.
            throw BackupCodecException.Corrupt(e)
        }

    /**
     * Reads all remaining bytes as UTF-8, throwing [BackupCodecException.TooLarge] when the
     * source supplies more than [maxBytes]. `request` pulls until the buffer holds cap+1 bytes
     * or the source is exhausted, so a false return means the buffer is the whole payload.
     */
    private fun BufferedSource.readBounded(maxBytes: Long): String {
        if (request(maxBytes + 1)) throw BackupCodecException.TooLarge
        return readUtf8()
    }

    public companion object {
        public const val FORMAT: String = "neutrodyne-backup"
        public const val FORMAT_VERSION: Int = 1

        /**
         * The oldest reader version that restores a written file correctly (05
         * Versioning): it stays 1 across compatible `formatVersion` bumps, so it is its
         * own constant — never an alias of [FORMAT_VERSION].
         */
        public const val MIN_READER_VERSION: Int = 1

        /** `manifest.json` cap (05 Validation and preview). */
        public const val MANIFEST_MAX_BYTES: Long = 1024L * 1024

        /** `library.json` cap (05 Validation and preview). */
        public const val LIBRARY_MAX_BYTES: Long = 32L * 1024 * 1024

        /** One `episodes.jsonl` line's cap (05 Validation and preview). */
        public const val EPISODE_LINE_MAX_BYTES: Long = 64L * 1024

        public const val ENTRY_MANIFEST: String = "manifest.json"
        public const val ENTRY_LIBRARY: String = "library.json"
        public const val ENTRY_EPISODES: String = "episodes.jsonl"
        public const val ENTRY_QUEUE: String = "queue.json"
        public const val ENTRY_SETTINGS: String = "settings.json"
        public const val ENTRY_OPML: String = "subscriptions.opml"

        /** The only entry names a backup reader ever opens (05 Validation and preview). */
        public val ENTRY_NAMES: Set<String> =
            setOf(ENTRY_MANIFEST, ENTRY_LIBRARY, ENTRY_EPISODES, ENTRY_QUEUE, ENTRY_SETTINGS, ENTRY_OPML)
    }
}

/** The one failure type of [BackupCodec] reads; callers map each case to `BackupError`. */
public sealed class BackupCodecException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** `manifest.json`'s `format` is missing or not `neutrodyne-backup`. */
    public data object NotABackup : BackupCodecException("not_a_backup")

    /** The file needs a reader newer than [BackupCodec.FORMAT_VERSION]. */
    public data class NewerFormat(
        val minReaderVersion: Int,
    ) : BackupCodecException("newer_format")

    /** The entry exceeds its documented size cap. */
    public data object TooLarge : BackupCodecException("too_large")

    /** Malformed JSON or a shape the DTOs reject. */
    public class Corrupt(
        cause: Throwable? = null,
    ) : BackupCodecException("corrupt", cause)
}

/**
 * One `episodes.jsonl` stream, iterated lazily (never the whole file in memory). Reads the
 * next line's bytes only, capped at [BackupCodec.EPISODE_LINE_MAX_BYTES]; oversized and
 * undecodable lines increment [skippedLines] and are passed over. Closing the iterator closes
 * the source; iterating to exhaustion is the normal end. Not re-iterable.
 */
public class EpisodeLines internal constructor(
    source: Source,
    private val json: Json,
) : Iterator<EpisodeLineV1>,
    AutoCloseable {
    private val input: BufferedSource = source.buffer()

    /** Lines skipped so far for exceeding the line cap or being undecodable (UTF-8 or JSON). */
    public var skippedLines: Int = 0
        private set

    private var pending: EpisodeLineV1? = null
    private var pendingReady = false
    private var closed = false

    override fun hasNext(): Boolean {
        if (!pendingReady && !closed) {
            pending = readDecoded()
            pendingReady = true
        }
        return pendingReady && pending != null
    }

    override fun next(): EpisodeLineV1 {
        if (!hasNext()) throw NoSuchElementException()
        pendingReady = false
        return pending!!
    }

    override fun close() {
        if (closed) return
        closed = true
        // Drop any held line: a consumed or prefetched item must not outlive close.
        pending = null
        pendingReady = false
        input.close()
    }

    private fun readDecoded(): EpisodeLineV1? {
        while (true) {
            when (val line = readRawLine()) {
                is RawLine.End -> {
                    return null
                }

                is RawLine.Oversized -> {
                    skippedLines++
                }

                is RawLine.Text -> {
                    // Okio's UTF-8 decode replaces bad bytes with U+FFFD, so a line
                    // containing it was undecodable: skip and count (05 Validation). A
                    // literal U+FFFD in genuine content is sacrificed — the spec trades
                    // that for "warning, not fatal".
                    if ('\uFFFD' in line.text) {
                        skippedLines++
                    } else {
                        try {
                            return json.decodeFromString<EpisodeLineV1>(line.text)
                        } catch (e: SerializationException) {
                            skippedLines++
                        } catch (e: IllegalArgumentException) {
                            skippedLines++
                        }
                    }
                }
            }
        }
    }

    /**
     * One line, bounded: the buffer is scanned for `\n` within the first cap+1 bytes (a `\n`
     * found there ends a ≤ cap-byte line), refilling from the source only until that is
     * provable. An overlong line is discarded in bounded chunks, so a hostile file can still
     * never grow memory past roughly one cap plus a segment.
     */
    private fun readRawLine(): RawLine {
        while (true) {
            val newline = input.buffer.indexOf(LF)
            if (newline >= 0) {
                if (newline > BackupCodec.EPISODE_LINE_MAX_BYTES) {
                    input.skip(newline + 1)
                    return RawLine.Oversized
                }
                val text = input.readUtf8(newline)
                input.skip(1)
                return RawLine.Text(text.removeSuffix("\r"))
            }
            if (input.buffer.size > BackupCodec.EPISODE_LINE_MAX_BYTES) {
                discardLine()
                return RawLine.Oversized
            }
            if (!input.request(BackupCodec.EPISODE_LINE_MAX_BYTES + 1)) {
                // Source exhausted, the buffer holds every byte left. A '\n' inside is still
                // caught by the next loop pass, so only a newline-free tail is the last line.
                if (input.buffer.size == 0L) return RawLine.End
                if (input.buffer.indexOf(LF) < 0) {
                    return RawLine.Text(input.readUtf8(input.buffer.size).removeSuffix("\r"))
                }
            }
        }
    }

    /** Skips bytes through the next `\n`, discarding in bounded chunks; stops at exhaustion. */
    private fun discardLine() {
        while (true) {
            val newline = input.buffer.indexOf(LF)
            if (newline >= 0) {
                input.skip(newline + 1)
                return
            }
            if (input.buffer.size > 0) {
                input.skip(input.buffer.size)
            } else if (!input.request(1)) {
                return
            }
        }
    }

    private sealed interface RawLine {
        data object End : RawLine

        data object Oversized : RawLine

        data class Text(
            val text: String,
        ) : RawLine
    }

    private companion object {
        const val LF: Byte = 0x0A
    }
}
