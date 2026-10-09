// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing.database

import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.MemberSource
import ch.lkmc.neutrodyne.core.model.OwnerType
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WaitReason

/**
 * The enum names SQL literals in the design docs and DAO queries refer to (02 Type converters):
 * `'COMPLETED'`, `'PENDING_FIRST_FETCH'`, `'YOUTUBE_CHANNEL'` and friends are spelled out inside
 * SQL, so a renammed or removed enum constant would silently change query semantics. `ConverterTest`
 * asserts every [Literal.name] still exists in its enum — the append-only rule made executable.
 */
object SqlEnumLiterals {
    /** One literal occurrence: [name] is expected to be a constant of the enum [constants] lists. */
    data class Literal(
        val enumName: String,
        val name: String,
        val constants: Set<String>,
    )

    val ALL: List<Literal> =
        buildList {
            literals<SourceType>("RSS", "YOUTUBE_CHANNEL", "YOUTUBE_PLAYLIST")
            literals<PodcastStatus>("PENDING_FIRST_FETCH", "ACTIVE")
            literals<DownloadState>("QUEUED", "COMPLETED", "FAILED", "MISSING")
            literals<WaitReason>("NONE")
            literals<OwnerType>("PODCAST", "EPISODE")
            literals<ChapterSource>("PSC", "PODCASTING20_JSON", "ID3", "MP4", "YOUTUBE_DESC")
            literals<ContextType>("PODCAST", "GROUP", "ALL", "UNGROUPED", "DOWNLOADS", "EXTERNAL")
            literals<Availability>("AVAILABLE", "UPCOMING", "LIVE", "MEMBERS_ONLY", "UNAVAILABLE")
            literals<AliasReason>("SUBSCRIBE_INPUT", "IMPORT", "RESTORE", "SYNC")
            literals<MemberSource>("MANUAL", "RULE")
            literals<ImportFormat>("OPML", "NEUTRODYNE_BACKUP")
            literals<ImportItemStatus>("PREVIEW", "SUBSCRIBED", "FETCH_FAILED")
            literals<FeedErrorKind>("IDENTITY_CONFLICT", "UNKNOWN")
        }

    private inline fun <reified E : Enum<E>> MutableList<Literal>.literals(vararg names: String) {
        val constants = enumValues<E>().mapTo(mutableSetOf()) { it.name }
        names.forEach { add(Literal(E::class.simpleName ?: "unknown", it, constants)) }
    }
}
