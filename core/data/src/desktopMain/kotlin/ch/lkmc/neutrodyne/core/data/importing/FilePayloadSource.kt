// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.FileUris
import ch.lkmc.neutrodyne.core.domain.ImportError
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Source

/**
 * The desktop [PayloadSource] (05 §1 step 7, row C3a): a `file:` URI naming a regular local
 * file. [open] runs entirely on [io] — URI decode, the regular-file stat and a probe open —
 * so a UI caller on the EDT never does filesystem work. The returned `Opened.open` defers the
 * real stream to `PayloadStore`'s counted copy; what the copy reads then is the payload. The
 * original is never moved, deleted or written through this port — the copy is the store's.
 *
 * Every rejection is [ImportError.Unreadable] on purpose: the URI is user/OS-supplied input,
 * and the report wording ("Couldn't open this file") is the same for a malformed URI, a
 * remote `file://host` share, a directory and a permission-denied file (incl. a refused macOS
 * folder-privacy prompt, 05 §1 step 7).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class FilePayloadSource(
    private val fileSystem: FileSystem,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : PayloadSource {
    override suspend fun open(uri: String): Outcome<PayloadSource.Opened, ImportError> =
        withContext(io) {
            val path =
                FileUris.parse(uri)?.toOkioPath()
                    ?: return@withContext Outcome.Failure(ImportError.Unreadable)
            val metadata =
                try {
                    fileSystem.metadataOrNull(path)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            // 05 §1 step 7: only a regular file — no directory, device or link to one.
            // `metadataOrNull` stats the link itself (NIO readAttributes), so a symlink —
            // even to a regular file — reports isRegularFile=false and is refused.
            if (metadata?.isRegularFile != true) {
                return@withContext Outcome.Failure(ImportError.Unreadable)
            }
            // Probe-open so an unreadable path fails here as Unreadable, not mid-copy later;
            // the counted copy still opens its own stream through `Opened.open`.
            try {
                fileSystem.source(path).close()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext Outcome.Failure(ImportError.Unreadable)
            }
            Outcome.Success(
                PayloadSource.Opened(
                    open = { fileSystem.source(path) },
                    displayName = path.name,
                    declaredSize = metadata.size,
                ),
            )
        }
}
