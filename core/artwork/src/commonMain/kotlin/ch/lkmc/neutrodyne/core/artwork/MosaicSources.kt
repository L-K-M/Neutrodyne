// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import androidx.room3.withReadTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import ch.lkmc.neutrodyne.core.model.MosaicSnapshot
import dev.zacsweers.metro.Inject
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okio.Buffer

/** Canonical, unambiguous UTF-8 fields; null differs from empty and member order matters. */
fun mosaicDescriptor(snapshot: MosaicSnapshot): String {
    val buffer = Buffer()

    fun field(value: Any?) {
        if (value == null) {
            buffer.writeInt(-1)
            return
        }
        val bytes = value.toString().encodeToByteArray()
        buffer.writeInt(bytes.size).write(bytes)
    }
    with(snapshot) {
        field(groupId)
        field(groupUuid)
        field(groupName)
        field(groupColorArgb)
        field(groupIconKey)
        field(members.size)
        for (member in members) {
            field(member.groupId)
            field(member.podcastId)
            field(member.displayTitle)
            field(member.artwork.key)
            field(member.artwork.url)
            field(member.artwork.version)
            field(member.artworkAvgArgb)
        }
    }
    return "nd:mosaic:v1:${buffer.readByteString().sha256().hex()}"
}

@Inject
internal class MosaicSources(
    private val db: NeutrodyneDatabase,
) {
    suspend fun snapshots(): List<MosaicSnapshot> =
        db.withReadTransaction {
            val members = db.groupDao().mosaicMembers().groupBy { it.groupId }
            db.groupDao().groupsInOrder().map { group ->
                MosaicSnapshot(
                    group.id,
                    group.uuid,
                    group.name,
                    group.colorArgb,
                    group.iconKey,
                    members[group.id]
                        .orEmpty()
                        .map { row ->
                            GroupMosaicMember(
                                row.groupId,
                                row.podcastId,
                                row.title,
                                ArtworkRef(row.artworkKey, row.artworkUrl, row.artworkVersion),
                                row.artworkAvgArgb,
                            )
                        }.toImmutableList(),
                )
            }
        }

    fun observe(): Flow<List<MosaicSnapshot>> =
        db.invalidationTracker
            .createFlow("podcast_group", "podcast_group_member", "podcast", "artwork")
            .map { snapshots() }
}

/** Own g- row writes invalidate Room but cannot move the debounce edge or schedule a loop. */
@OptIn(FlowPreview::class)
internal fun Flow<List<MosaicSnapshot>>.mosaicChanges(): Flow<List<String>> =
    map { snapshots -> snapshots.map(::mosaicDescriptor) }
        .distinctUntilChanged()
        .debounce(MOSAIC_DEBOUNCE_MS)

private const val MOSAIC_DEBOUNCE_MS = 10_000L
