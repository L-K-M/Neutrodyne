// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.backup

import okio.Path
import okio.Sink
import okio.Source

/**
 * The read caps one archive may cost (05 Archive guard). The reader enforces them while
 * streaming — central-directory sizes are hints, never trusted: [maxEntryBytes] and
 * [maxTotalBytes] count inflated bytes as they are actually read, and the [maxRatio] check is
 * armed only once an entry's inflated bytes pass [ratioFloorBytes].
 */
public data class ArchiveCaps(
    /** Central-directory entries tolerated; more rejects the archive at open (`zip_entries`). */
    val maxEntries: Int,
    /** Inflated bytes tolerated from a single entry (`zip_bomb`). */
    val maxEntryBytes: Long,
    /** Inflated bytes tolerated across every entry read from one archive (`zip_bomb`). */
    val maxTotalBytes: Long,
    /** An entry inflating past `maxRatio` times its compressed size is hostile (`zip_bomb`). */
    val maxRatio: Int = DEFAULT_MAX_RATIO,
    /** The ratio check arms only once an entry has inflated past this many bytes. */
    val ratioFloorBytes: Long = DEFAULT_RATIO_FLOOR_BYTES,
) {
    public companion object {
        public const val DEFAULT_MAX_RATIO: Int = 100
        public const val DEFAULT_RATIO_FLOOR_BYTES: Long = 16L * 1024 * 1024

        /** Full backups (05 Archive guard): a fixed small entry set with generous size room. */
        public val BACKUP: ArchiveCaps =
            ArchiveCaps(
                maxEntries = 16,
                maxEntryBytes = 128L * 1024 * 1024,
                maxTotalBytes = 256L * 1024 * 1024,
            )

        /**
         * Google Takeout ZIPs (05 Archive guard): many entries, each small. The spec table's
         * Takeout column gives decimal MB, unlike the Backup column's binary MiB.
         */
        public val TAKEOUT: ArchiveCaps =
            ArchiveCaps(
                maxEntries = 10_000,
                maxEntryBytes = 5_000_000,
                maxTotalBytes = 250_000_000,
            )
    }
}

/**
 * Opens an archive file for capped reads (05 Archive guard). Implementations are JVM code in
 * `:feeds:jvm` (`ZipGuard`), bound from `:core:data`'s `androidMain` and `desktopMain`.
 */
public fun interface ArchiveReaderFactory {
    /**
     * Validates [path]'s central directory and returns the reader. An unreadable or missing file
     * surfaces as the platform I/O error (callers map it to `Unreadable`/`Io`), never as
     * [ArchiveException].
     * @throws ArchiveException a hostile archive reports [ArchiveException.Kind.HOSTILE] with its
     *   reason (`zip_path`, `zip_entries`); a malformed one [ArchiveException.Kind.CORRUPT] (`zip`)
     */
    public fun open(
        path: Path,
        caps: ArchiveCaps,
    ): ArchiveReader
}

/** One validated archive open for capped reads (05 Archive guard). */
public interface ArchiveReader : AutoCloseable {
    /** Central-directory entry names, in directory order; name-validated and duplicate-free. */
    public val entryNames: List<String>

    /**
     * Streams [name]'s inflated bytes through a counting source enforcing this archive's caps.
     * @throws ArchiveException a tripped cap reports [ArchiveException.Kind.HOSTILE] (`zip_bomb`);
     *   an unknown name or damaged entry data reports [ArchiveException.Kind.CORRUPT] (`zip`)
     */
    public fun open(name: String): Source
}

/**
 * Creates a writer producing one archive file at [path] (05 Writing). Implementations are JVM code
 * in `:feeds:jvm` (`ZipArchiveWriter`); staged atomic moves onto the final name are the caller's
 * job. [deflateLevel] defaults to the backup format's DEFLATE level 6 — the `create(path)` overload
 * carries it because a `fun interface`'s abstract method cannot take a default.
 */
public fun interface ArchiveWriterFactory {
    public fun create(
        path: Path,
        deflateLevel: Int,
    ): ArchiveWriter

    public fun create(path: Path): ArchiveWriter = create(path, DEFAULT_DEFLATE_LEVEL)

    public companion object {
        public const val DEFAULT_DEFLATE_LEVEL: Int = 6
    }
}

/** One archive being written (05 Writing). */
public interface ArchiveWriter : AutoCloseable {
    /**
     * Opens the next entry's sink. Exactly one entry sink is open at a time — closing a sink ends
     * its entry, never the archive; [close] ends the archive itself.
     * Entry names must be unique within the archive; implementations reject a duplicate.
     */
    public fun entry(name: String): Sink
}

/**
 * The one failure type of archive validation and capped reads (05 Archive guard): hostile archives
 * and damaged data both surface through this with a stable [reason] tag the callers map to
 * `ImportError`/`BackupError`.
 */
public class ArchiveException(
    public val kind: Kind,
    public val reason: String,
) : Exception(reason) {
    public enum class Kind {
        HOSTILE,
        CORRUPT,
    }
}
