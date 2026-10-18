// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.importing.PayloadSource.Opened
import ch.lkmc.neutrodyne.core.domain.ImportError
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.Source
import okio.source
import java.io.File
import java.io.FileNotFoundException

/**
 * `PayloadSource` over `ContentResolver` (05 OPML import §1 step 1, row C1): `content://` —
 * plus best-effort `file://` from old senders — opened while the caller's URI grant lives.
 * `open` queries `OpenableColumns` eagerly (the metadata dies with the grant) but defers the
 * stream itself: `PayloadStore` opens, counts and closes it inside the copy, so the declared
 * `SIZE` stays a hint and is never trusted as the cap.
 *
 * Metadata is optional: a provider returning no cursor yields an [Opened] with null fields and
 * the store falls back to the `ImportSource` name. A provider that denies the query with
 * `SecurityException` will deny the stream too, so it is [ImportError.Unreadable] at once; a
 * provider that fails later (revoked grant, null stream, mid-read cut) throws inside the
 * deferred [Opened.open], which the store maps to the same error. Nothing here touches the
 * provider off [io] — `PayloadStore.acquire` already calls on IO, the extra hop keeps that
 * true for every future caller.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class ContentPayloadSource(
    private val resolver: ContentResolver,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : PayloadSource {
    override suspend fun open(uri: String): Outcome<Opened, ImportError> =
        withContext(io) {
            val parsed = Uri.parse(uri)
            when (parsed.scheme) {
                ContentResolver.SCHEME_FILE -> openFileUri(parsed)

                ContentResolver.SCHEME_CONTENT -> openContentUri(parsed)

                // 05 §1 routes only content/file through this port; anything else is unreadable.
                else -> Outcome.Failure(ImportError.Unreadable)
            }
        }

    /** `file://` (05 §1 step 1): the same regular-file rule the desktop applies, best effort. */
    private fun openFileUri(uri: Uri): Outcome<Opened, ImportError> {
        val file = uri.path?.let(::File) ?: return Outcome.Failure(ImportError.Unreadable)
        if (!file.isFile) return Outcome.Failure(ImportError.Unreadable)
        return Outcome.Success(
            Opened(open = { openStream(uri) }, displayName = file.name, declaredSize = file.length()),
        )
    }

    /**
     * `content://`: [OpenableColumns.DISPLAY_NAME] and [OpenableColumns.SIZE] are read now
     * while the grant is certain; a missing cursor or column leaves the field null.
     */
    private fun openContentUri(uri: Uri): Outcome<Opened, ImportError> {
        var displayName: String? = null
        var declaredSize: Long? = null
        try {
            resolver.query(uri, OPENABLE_PROJECTION, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor
                        .getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { displayName = cursor.getString(it) }
                    cursor
                        .getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { declaredSize = cursor.getLong(it) }
                }
            }
        } catch (e: SecurityException) {
            // A grant that fails the query fails the stream too — fail as 05 §1 step 4 says.
            return Outcome.Failure(ImportError.Unreadable)
        } catch (e: RuntimeException) {
            // A provider that crashes on query may still stream; metadata stays absent.
        }
        return Outcome.Success(
            Opened(open = { openStream(uri) }, displayName = displayName, declaredSize = declaredSize),
        )
    }

    /**
     * The deferred stream of the [Opened] contract: `PayloadStore` invokes this inside the
     * counted copy and maps any throw to [ImportError.Unreadable]. A provider answering `null`
     * is the same "couldn't open" as a thrown `FileNotFoundException`.
     */
    private fun openStream(uri: Uri): Source =
        resolver.openInputStream(uri)?.source() ?: throw FileNotFoundException(uri.toString())

    private companion object {
        val OPENABLE_PROJECTION = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }
}
