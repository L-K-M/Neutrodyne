// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.archive

import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReader
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReaderFactory
import okio.Buffer
import okio.ForwardingSource
import okio.Path
import okio.Source
import okio.source
import java.io.EOFException
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * The guarded archive reader (05 Archive guard): `java.util.zip.ZipFile` for random access over the
 * local payload copy — entries are never extracted to disk, so entry names are never paths and
 * zip-slip cannot happen through this reader. The guard instead validates names itself and counts
 * every inflated byte its sources hand out.
 *
 * Name validation runs at [open], not per entry: a name containing `..`, starting with `/` or
 * containing `\` rejects the whole archive, as does one name claimed by two central-directory
 * records (the name→entry lookup would be ambiguous). On Android an app targeting 34+ sees the
 * platform reject `..`/leading-`/` names inside the `ZipFile` constructor itself — that
 * `ZipException` is recognised by message and mapped to the same `zip_path` so the desktop, JVM
 * tests and devices agree.
 */
public class ZipGuard : ArchiveReaderFactory {
    override fun open(
        path: Path,
        caps: ArchiveCaps,
    ): ArchiveReader {
        val zip =
            try {
                ZipFile(path.toFile())
            } catch (e: ZipException) {
                throw mapOpenFailure(e)
            }
        try {
            return Reader(zip, caps, validateNames(zip, caps))
        } catch (e: Throwable) {
            zip.close()
            throw e
        }
    }

    /**
     * Collects the central-directory names after applying the name and count rules; bails at the
     * first offending record so a huge directory cannot hold the scan open.
     */
    private fun validateNames(
        zip: ZipFile,
        caps: ArchiveCaps,
    ): List<String> {
        val names = ArrayList<String>(minOf(caps.maxEntries, 64))
        val seen = HashSet<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name
            if (names.size >= caps.maxEntries) throw hostile("zip_entries")
            if (!isSafeEntryName(name)) throw hostile("zip_path")
            if (!seen.add(name)) throw hostile("zip_entries")
            names += name
        }
        return names
    }

    /**
     * The constructor-time `ZipException` on Android ("Invalid zip entry path: …", thrown by the
     * platform's path validator for `..` or leading `/` when the app targets 34+) maps to the same
     * `zip_path` our own name check produces on the JVM; every other `ZipException` is a malformed
     * archive. (Only reachable on devices; the JVM constructor does no name validation.)
     */
    private fun mapOpenFailure(e: ZipException): ArchiveException =
        if (e.message?.startsWith(ANDROID_PATH_MESSAGE) == true) {
            hostile("zip_path")
        } else {
            corrupt("zip")
        }

    private class Reader(
        private val zip: ZipFile,
        private val caps: ArchiveCaps,
        override val entryNames: List<String>,
    ) : ArchiveReader {
        /** Inflated bytes handed out by every open source, checked against `caps.maxTotalBytes`. */
        private val totalInflated = AtomicLong()

        override fun open(name: String): Source {
            val entry = zip.getEntry(name) ?: throw corrupt("zip")
            val stream =
                try {
                    zip.getInputStream(entry)
                } catch (e: ZipException) {
                    throw corrupt("zip")
                }
            return CountingSource(stream.source(), entry.compressedSize, caps, totalInflated)
        }

        override fun close() {
            zip.close()
        }
    }

    /**
     * Wraps one entry's inflater stream and counts inflated bytes itself. The declared
     * uncompressed size is never consulted: the per-entry and total caps trip on the counted
     * bytes, and the ratio check divides by the declared *compressed* size — `ZipFile` bounds the
     * inflater's input to that size, so a bomb can only lie the ratio downward (it then still hits
     * the byte caps).
     */
    private class CountingSource(
        delegate: Source,
        private val compressedSize: Long,
        private val caps: ArchiveCaps,
        private val totalInflated: AtomicLong,
    ) : ForwardingSource(delegate) {
        private var inflated = 0L

        override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            val n =
                try {
                    super.read(sink, byteCount)
                } catch (e: ZipException) {
                    // Bad CRC, damaged deflate data, inconsistent local header.
                    throw corrupt("zip")
                } catch (e: EOFException) {
                    // The entry's deflate stream runs out before its declared end: damage inside
                    // a file that was readable, not an I/O failure.
                    throw corrupt("zip")
                }
            if (n < 0) return n
            inflated += n
            if (inflated > caps.maxEntryBytes || ratioTripped(inflated)) throw hostile("zip_bomb")
            if (totalInflated.addAndGet(n) > caps.maxTotalBytes) throw hostile("zip_bomb")
            return n
        }

        private fun ratioTripped(inflated: Long): Boolean {
            if (inflated <= caps.ratioFloorBytes || compressedSize <= 0) return false
            // An absurd declared size would overflow the multiply; such a declaration dwarfs any
            // possible inflation, so the check cannot legitimately trip for it.
            if (compressedSize > Long.MAX_VALUE / caps.maxRatio) return false
            return inflated > compressedSize * caps.maxRatio
        }
    }

    private companion object {
        /**
         * Android's zip path validator throws `ZipException("Invalid zip entry path: <name>")` from
         * the `ZipFile` constructor (SafeZipPathValidatorCallback, apps targeting 34+).
         */
        const val ANDROID_PATH_MESSAGE = "Invalid zip entry path"
    }
}

/** 05 Archive guard's name rule: no `..` anywhere, no leading `/`, no `\` at all. */
internal fun isSafeEntryName(name: String): Boolean =
    !name.contains("..") && !name.startsWith("/") && !name.contains('\\')

private fun hostile(reason: String): ArchiveException = ArchiveException(ArchiveException.Kind.HOSTILE, reason)

private fun corrupt(reason: String): ArchiveException = ArchiveException(ArchiveException.Kind.CORRUPT, reason)
