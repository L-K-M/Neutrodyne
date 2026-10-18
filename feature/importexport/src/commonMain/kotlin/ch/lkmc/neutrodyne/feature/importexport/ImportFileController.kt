// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker

internal class ImportFileController(
    private val imports: ImportRepository,
) {
    suspend fun open(picker: FilePicker): FileImportResult {
        val uri =
            suspendRunCatching { picker.openFile() }
                .getOrElse { return FileImportResult.Failed(ImportError.Unreadable) }
                ?: return FileImportResult.Cancelled
        val name = if (uri.startsWith("file:", ignoreCase = true)) fileName(uri) else null
        return when (val result = imports.create(ImportSource(uri, name))) {
            is Outcome.Success -> FileImportResult.Opened(result.value)
            is Outcome.Failure -> FileImportResult.Failed(result.error)
        }
    }

    // URI escapes encode UTF-8 bytes. Decode only the leaf, keeping literal '+' intact.
    private fun fileName(uri: String): String? {
        val encoded =
            uri
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/')
                .encodeToByteArray()
        if (encoded.isEmpty()) return null

        val decoded = ByteArray(encoded.size)
        var read = 0
        var written = 0
        while (read < encoded.size) {
            val high =
                encoded
                    .getOrNull(read + 1)
                    ?.toInt()
                    ?.toChar()
                    ?.digitToIntOrNull(16)
            val low =
                encoded
                    .getOrNull(read + 2)
                    ?.toInt()
                    ?.toChar()
                    ?.digitToIntOrNull(16)
            if (encoded[read] == '%'.code.toByte() && high != null && low != null) {
                decoded[written++] = (high * 16 + low).toByte()
                read += 3
                continue
            }
            decoded[written++] = encoded[read++]
        }
        return decoded.decodeToString(endIndex = written)
    }
}

internal sealed interface FileImportResult {
    data object Cancelled : FileImportResult

    data class Opened(
        val sessionId: Long,
    ) : FileImportResult

    data class Failed(
        val error: ImportError,
    ) : FileImportResult
}
