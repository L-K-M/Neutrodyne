// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

// 05 OPML export — the types of ExportRepository's prepare/saveTo contract.

enum class ExportFormat {
    OPML,

    /** M8, full export only. */
    NEWPIPE_JSON,
}

/** Also the type of the setting `backup.opml_layout`. */
enum class ExportLayout {
    GROUPED,
    FLAT,
}

data class ExportRequest(
    /** Null = the whole library; a group id shares that group. */
    val groupId: Long? = null,
    val format: ExportFormat = ExportFormat.OPML,
    val layout: ExportLayout = ExportLayout.GROUPED,
    val includeYouTube: Boolean = true,
    val includePasswords: Boolean = false,
)

/**
 * A file built under `<cache>/export/{fileName}` (05 Destinations). [privateLinks] and
 * [passwords] drive the R1.9 warning before the file leaves the app.
 */
data class PreparedExport(
    val fileName: String,
    val mimeType: String,
    /** Android `content://{applicationId}.fileprovider/export/…`; null on the desktop (no share sheet). */
    val shareUri: String?,
    val feeds: Int,
    val privateLinks: Int,
    val passwords: Int,
)
