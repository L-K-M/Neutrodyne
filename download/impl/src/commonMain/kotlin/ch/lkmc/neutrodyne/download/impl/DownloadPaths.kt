// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.Nfc

internal enum class PathPlatform { PORTABLE, WINDOWS }

internal object DownloadPaths {
    const val COMPONENT_BYTES = 100
    private const val WINDOWS_PATH_UNITS = 259
    private const val MIN_TITLE_BYTES = 20
    private val forbidden = "/\\:*?\"<>|"
    private val reserved = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])")

    fun part(episodeId: Long): String {
        require(episodeId > 0)
        return ".partial/$episodeId.part"
    }

    fun component(
        text: String,
        suffix: String,
        budgetBytes: Int = COMPONENT_BYTES,
    ): String {
        require(suffix.isNotEmpty() && suffix.none { it in forbidden || it.isWhitespace() || it.code < 32 })
        val budget = budgetBytes - (" $suffix").encodeToByteArray().size
        require(budget >= 1) { "No room for a title" }
        var clean =
            Nfc
                .normalize(text)
                .map {
                    if (it in forbidden || it.isWhitespace() || it.code < 32 || it == '\u007f') ' ' else it
                }.joinToString("")
                .replace(Regex("\\s+"), " ")
                .trim(' ', '.')
        if (clean.isEmpty()) clean = "Untitled"
        if (reserved.matches(clean.substringBefore('.').trim())) clean = "_$clean"
        clean = truncate(clean, budget).trim(' ', '.')
        require(clean.isNotEmpty()) { "Title budget cannot hold one code point" }
        return "$clean $suffix"
    }

    /** Existing suffixes survive podcast/title changes. Full path includes the absolute root. */
    suspend fun finalPath(
        files: DownloadFileSystem,
        root: ResolvedRoot,
        podcastId: Long,
        podcastTitle: String,
        episodeId: Long,
        title: String,
        date: String,
        extension: String,
        platform: PathPlatform = PathPlatform.PORTABLE,
    ): String {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date))
        require(extension.matches(Regex("[a-z0-9]+")))
        val folderSuffix = "[p$podcastId]"
        val fileSuffix = "[e$episodeId].$extension"
        val existing =
            files
                .list(
                    root.rootId,
                    "",
                ).firstOrNull { it.directory && it.path.endsWith(" $folderSuffix") }
                ?.path
        var folderBudget = COMPONENT_BYTES
        var fileBudget = COMPONENT_BYTES
        if (platform == PathPlatform.WINDOWS) {
            val available = WINDOWS_PATH_UNITS - root.absolutePath.length - 2
            val minFolder = (" $folderSuffix").length + MIN_TITLE_BYTES
            val minFile = ("$date  $fileSuffix").length + MIN_TITLE_BYTES
            require(available >= (existing?.length ?: minFolder) + minFile) { "Download root path is too long" }
            folderBudget =
                if (existing == null) minOf(COMPONENT_BYTES, maxOf(minFolder, available / 2)) else existing.length
            fileBudget = minOf(COMPONENT_BYTES, available - folderBudget)
            if (fileBudget < minFile) {
                folderBudget -= minFile - fileBudget
                fileBudget = minFile
            }
        }
        val folder = existing ?: component(podcastTitle, folderSuffix, folderBudget)
        val file = component("$date $title", fileSuffix, fileBudget)
        return "$folder/$file"
    }

    private fun truncate(
        text: String,
        budget: Int,
    ): String {
        var end = 0
        var bytes = 0
        while (end < text.length) {
            val count = if (text[end].isHighSurrogate() && text.getOrNull(end + 1)?.isLowSurrogate() == true) 2 else 1
            val size = text.substring(end, end + count).encodeToByteArray().size
            if (bytes + size > budget) break
            bytes += size
            end += count
        }
        if (end == text.length) return text
        return dropTrailingTitleMarks(text.substring(0, end))
    }
}

/** Unicode scalar categories, including supplementary combining marks and variation selectors. */
internal expect fun dropTrailingTitleMarks(text: String): String
