// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Validate the central directory before writing anything. ZipEntry alone hides Unix symlink bits. */
internal object BundledZip {
    const val MAX_ZIP_BYTES = 10L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 40L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    private const val MAX_ENTRIES = 4096
    private const val MAX_RATIO = 200
    private const val EOCD_SIZE = 22
    private const val CENTRAL_SIZE = 46
    private const val UNIX_TYPE_MASK = 0xf000
    private const val REGULAR = 0x8000
    private const val DIRECTORY = 0x4000

    fun extract(
        archive: File,
        destination: File,
        version: String,
        ejsVersion: String,
    ) {
        require(archive.length() in 1..MAX_ZIP_BYTES) { "Bundle size limit" }
        val names = validateDirectory(archive.readBytes())
        require(!destination.exists())
        Files.createDirectory(destination.toPath())
        ZipFile(archive).use { zip ->
            require(zip.size() == names.size)
            var total = 0L
            for (entry in zip.entries()) {
                require(entry.name in names)
                val output = File(destination, entry.name)
                if (entry.isDirectory) {
                    require(entry.size == 0L)
                    Files.createDirectories(output.toPath())
                    continue
                }
                require(entry.size in 0..MAX_ENTRY_BYTES && entry.compressedSize >= 0)
                require(entry.size <= maxOf(1L, entry.compressedSize) * MAX_RATIO) { "ZIP expansion ratio" }
                Files.createDirectories(output.parentFile.toPath())
                val crc = CRC32()
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(output.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW).use { sink ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            total += count
                            require(
                                size <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES && size <= entry.size,
                            ) { "ZIP expansion limit" }
                            crc.update(buffer, 0, count)
                            sink.write(buffer, 0, count)
                        }
                    }
                }
                require(size == entry.size && crc.value == entry.crc) { "ZIP size/CRC mismatch" }
                require(output.setReadOnly()) { "Cannot protect bundled file" }
            }
        }
        val info = File(destination, "yt_dlp/version.py").readText()
        require(info.lineSequence().any { it == "ORIGIN = 'yt-dlp/yt-dlp'" })
        require(info.lineSequence().any { it == "CHANNEL = 'stable'" })
        require(info.lineSequence().any { it == "__version__ = '$version'" })
        require(
            File(destination, "yt_dlp_ejs/_version.py").readText().lineSequence().any {
                it ==
                    "__version__ = version = '$ejsVersion'"
            },
        )
    }

    private fun validateDirectory(bytes: ByteArray): Set<String> {
        fun u16(at: Int): Int {
            require(at >= 0 && at + 2 <= bytes.size)
            return (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
        }

        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        val end =
            (bytes.size - EOCD_SIZE downTo maxOf(0, bytes.size - EOCD_SIZE - 65535)).firstOrNull {
                u32(it) == 0x06054b50L && it + EOCD_SIZE + u16(it + 20) == bytes.size
            } ?: error("Missing ZIP end record")
        require(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == u16(end + 10)) { "Multipart ZIP" }
        val count = u16(end + 10)
        require(count in 1..MAX_ENTRIES)
        // Offsets in Python's zipapp exclude the shebang prefix. Derive the actual start from size.
        val start = end.toLong() - u32(end + 12)
        val prefix = start - u32(end + 16)
        require(start >= 0 && prefix in 0..256)
        var at = start.toInt()
        var total = 0L
        val names = linkedSetOf<String>()
        repeat(count) {
            require(u32(at) == 0x02014b50L)
            val flags = u16(at + 8)
            require(flags and 1 == 0 && u16(at + 10) in setOf(0, 8)) { "Encrypted or unsupported ZIP" }
            val compressed = u32(at + 20)
            val size = u32(at + 24)
            val length = u16(at + 28)
            val next = at.toLong() + CENTRAL_SIZE + length + u16(at + 30) + u16(at + 32)
            require(length in 1..512 && next <= end)
            val name = bytes.copyOfRange(at + CENTRAL_SIZE, at + CENTRAL_SIZE + length).toString(Charsets.UTF_8)
            require(
                Regex("[A-Za-z0-9_./-]+").matches(name) &&
                    name.removeSuffix("/").split('/').all { it.isNotEmpty() && it != "." && it != ".." },
            ) { "Unsafe ZIP path" }
            require(name == "__main__.py" || name.startsWith("yt_dlp/") || name.startsWith("yt_dlp_ejs/")) {
                "Unapproved ZIP entry"
            }
            require(
                !name.endsWith(".pyc") && !name.endsWith(".pyo") &&
                    !Regex("(?i)\\.(so|dll|exe|dex|class|jar|dylib)$").containsMatchIn(name),
            ) { "Non-source ZIP code" }
            require(
                name !in names && (name.endsWith('/') || "$name/" !in names) &&
                    (!name.endsWith('/') || name.removeSuffix("/") !in names),
            ) { "Duplicate ZIP path" }
            names.add(name)
            val mode = (u32(at + 38) shr 16).toInt() and UNIX_TYPE_MASK
            require(
                mode == 0 || mode == if (name.endsWith('/')) DIRECTORY else REGULAR,
            ) { "ZIP symlink or special file" }
            require(
                u16(at + 34) == 0 && size <= MAX_ENTRY_BYTES && size <= maxOf(1L, compressed) * MAX_RATIO,
            ) { "ZIP entry limit" }
            total += size
            require(total <= MAX_TOTAL_BYTES) { "ZIP total limit" }
            val local = u32(at + 42) + prefix
            require(local >= 0 && local + 30 < start && u32(local.toInt()) == 0x04034b50L)
            val localAt = local.toInt()
            val dataAt = local + 30 + u16(localAt + 26) + u16(localAt + 28)
            require(u16(localAt + 6) == flags && u16(localAt + 8) == u16(at + 10) && dataAt + compressed <= start)
            require(
                u16(localAt + 26) == length &&
                    bytes.copyOfRange(localAt + 30, localAt + 30 + length).contentEquals(
                        bytes.copyOfRange(at + CENTRAL_SIZE, at + CENTRAL_SIZE + length),
                    ),
            ) { "ZIP path mismatch" }
            at = next.toInt()
        }
        require(at == end)
        for (name in names) {
            var parent = name.removeSuffix("/").substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent !in names) { "ZIP file/directory collision" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        require(
            setOf(
                "__main__.py",
                "yt_dlp/__init__.py",
                "yt_dlp/version.py",
                "yt_dlp_ejs/__init__.py",
                "yt_dlp_ejs/_version.py",
            ).all {
                it in
                    names
            },
        )
        return names
    }
}
