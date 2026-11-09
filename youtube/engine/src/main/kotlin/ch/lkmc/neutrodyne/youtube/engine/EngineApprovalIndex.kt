// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Steps 2–4 of the immutable-approval design: the stable `engine-approvals` release is an index
 * whose body names exactly one immutable snapshot tag `engine-approvals-<run>-<attempt>` — never
 * URLs. The updater resolves the index release once per update round and then downloads all four
 * trust documents from that one snapshot's fixed release-asset names, so a moved index can never
 * mix documents across snapshots. The index is discovery metadata, not an approval: anything that
 * is not the exact expected prerelease in the configured repository fails closed before a single
 * document is fetched.
 */
object EngineApprovalIndex {
    /** The stable release tag that acts as the index; snapshots carry `engine-approvals-<r>-<a>`. */
    const val INDEX_TAG = "engine-approvals"

    /** Version of the JSON record stored in the index release body. */
    const val SCHEMA = 1

    /** The index body is a small record; anything bigger is foreign metadata and fails closed. */
    const val MAX_BODY_CHARS = 4 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val snapshotTagPattern = Regex("$INDEX_TAG-[0-9]+-[0-9]+")

    /**
     * The snapshot's download base (`<base>/download/<tag>`) when [document] parses as the exact
     * non-draft prerelease index in the repository [repoPrefix] names, or null on any defect.
     * [repoPrefix] is the `<api>/repos/<owner>/<repo>/releases/` prefix derived from the
     * configured index URL, so the check pins API host, owner and repository at once.
     */
    fun resolve(
        document: ByteArray,
        repoPrefix: String,
        approvalBaseUrl: String,
    ): String? {
        val index =
            try {
                json.decodeFromString<ReleaseIndex>(document.decodeToString())
            } catch (invalid: Exception) {
                return null
            }
        if (index.tagName != INDEX_TAG || index.draft || !index.prerelease) return null
        if (repoPrefix.isEmpty() || !index.url.startsWith(repoPrefix)) return null
        if (index.body.length > MAX_BODY_CHARS) return null
        val record =
            try {
                json.decodeFromString<ApprovalIndexRecord>(index.body)
            } catch (invalid: Exception) {
                return null
            }
        if (record.schema != SCHEMA || !snapshotTagPattern.matches(record.snapshotTag)) return null
        return "$approvalBaseUrl/download/${record.snapshotTag}"
    }
}

/** The subset of the GitHub release-by-tag response the index check is allowed to trust. */
@Serializable
data class ReleaseIndex(
    val url: String,
    @kotlinx.serialization.SerialName("tag_name") val tagName: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val body: String,
)

/** The versioned record inside the index release body: the snapshot tag and nothing else. */
@Serializable
private data class ApprovalIndexRecord(
    val schema: Int,
    val snapshotTag: String,
)
