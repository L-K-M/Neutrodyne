// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.FileUris
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.navigation.IntentRouter
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.RouteInput
import ch.lkmc.neutrodyne.desktop.shell.HandoffRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** All external desktop inputs share this ordered queue; resolving the repository waits for UI readiness. */
internal class DesktopOpenHandler(
    scope: CoroutineScope,
    private val imports: () -> ImportRepository,
    private val dispatch: (Route) -> Unit,
    private val rejected: suspend () -> Unit,
) {
    private val ready = CompletableDeferred<Unit>()
    private val pending = Channel<Batch>(Channel.UNLIMITED)
    private val errors = Channel<ImportError>(Channel.UNLIMITED)
    val failures = errors.receiveAsFlow()

    init {
        scope
            .launch {
                ready.await()
                for (batch in pending) open(batch)
            }.invokeOnCompletion {
                pending.cancel()
                errors.close()
            }
    }

    fun submit(
        inputs: List<String>,
        cwd: Path = Path.of(System.getProperty("user.dir")),
    ) {
        pending.trySend(Batch(inputs.take(MAX_INPUTS), cwd))
    }

    /** Only the database-ready root calls this, after attaching navigation and drawing a frame. */
    fun afterFirstFrame() {
        ready.complete(Unit)
    }

    private suspend fun open(batch: Batch) {
        var navigated = false
        for (input in batch.inputs) {
            val route =
                try {
                    route(input, batch.cwd)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Log.w(TAG) { "input could not be opened" }
                    errors.send(ImportError.Unreadable)
                    Route.None
                }
            if (route == Route.None || navigated) continue

            dispatch(route)
            navigated = true
        }
    }

    private suspend fun route(
        input: String,
        cwd: Path,
    ): Route {
        if (input.isBlank() || input.length > MAX_INPUT_BYTES ||
            input.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES
        ) {
            return reject()
        }
        val link = IntentRouter.route(RouteInput(RouteInput.Action.VIEW, uri = input))
        if (link != Route.None) return link

        val uri = runCatching { URI(input) }.getOrNull()
        if (uri?.scheme?.lowercase() in setOf("http", "https")) {
            return IntentRouter.route(RouteInput(RouteInput.Action.SEND, text = input))
        }
        val path =
            runCatching {
                val fileUri =
                    if (uri?.scheme.equals(
                            "file",
                            ignoreCase = true,
                        )
                    ) {
                        input
                    } else {
                        cwd.resolve(Path.of(input)).toUri().toASCIIString()
                    }
                FileUris.parse(fileUri)
            }.getOrNull() ?: return reject()
        val file = path.toAbsolutePath().normalize()
        val extension =
            file.fileName
                ?.toString()
                ?.substringAfterLast('.', "")
                ?.lowercase()
        if (extension !in IMPORT_EXTENSIONS) return reject()

        // Unlike isRegularFile, stat preserves access failures for the Unreadable dialog.
        val attributes =
            try {
                Files.readAttributes(file, BasicFileAttributes::class.java)
            } catch (_: NoSuchFileException) {
                return reject()
            }
        if (!attributes.isRegularFile) return reject()
        if (!Files.isReadable(file)) {
            errors.send(ImportError.Unreadable)
            return Route.None
        }
        if (attributes.size() > MAX_OPEN_FILE_BYTES) return reject()

        // The repository copies the selected file before returning and sniffs ZIPs itself.
        return when (
            val result =
                imports().create(
                    ImportSource(file.toUri().toASCIIString(), file.fileName.toString()),
                )
        ) {
            is Outcome.Success -> {
                IntentRouter.route(
                    RouteInput(
                        RouteInput.Action.VIEW,
                        uri = "neutrodyne://open/import/${result.value}",
                        internal = true,
                    ),
                )
            }

            is Outcome.Failure -> {
                errors.send(result.error)
                Route.None
            }
        }
    }

    private suspend fun reject(): Route {
        Log.w(TAG) { "external input rejected" }
        rejected()
        return Route.None
    }

    private data class Batch(
        val inputs: List<String>,
        val cwd: Path,
    )

    companion object {
        const val MAX_INPUTS = HandoffRequest.MAX_ARGS
        private const val MAX_INPUT_BYTES = 4 * 1024
        private const val MAX_OPEN_FILE_BYTES = 64L * 1024 * 1024
        private const val TAG = "DesktopOpenHandler"
        private val IMPORT_EXTENSIONS = setOf("opml", "xml", "zip")
    }
}
