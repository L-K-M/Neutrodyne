// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.DirectoryChoice
import ch.lkmc.neutrodyne.core.common.LinuxDesktopPortal
import ch.lkmc.neutrodyne.core.common.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * 11's [LinuxDesktopPortal] over the session bus (dbus-java):
 * `org.freedesktop.FileManager1.ShowItems` for "Show in Files" and the portal's
 * `org.freedesktop.portal.FileChooser.OpenFile` with `directory: true` for "Change
 * folder…". Both are best effort — a bus without the service answers `false` or
 * [DirectoryChoice.Unavailable] so the caller runs its documented `JFileChooser` /
 * `Desktop.open` fallback, never an error (11 OS-integration failure modes).
 *
 * The FileChooser answer arrives as the `Response` signal on the request handle. The
 * handle's path is made predictable with the spec's `handle_token` option
 * (`…/request/{sender}/{token}`), so the signal match rule is installed *before* the
 * call — no window between call and subscription in which the answer could be lost.
 * Cancelling the caller removes the match and closes the request, which the portal
 * turns into dismissing its dialog.
 */
class DbusDesktopPortal internal constructor(
    private val connect: () -> DBusConnection,
) : LinuxDesktopPortal,
    AutoCloseable {
    constructor(busAddress: String? = null) : this({ LinuxDbusNotifications.openBus(busAddress) })

    private val lock = Any()
    private var connection: DBusConnection? = null

    /** Connected lazily: a desktop without a portal never opens one on the "Show in folder" path. */
    private fun bus(): DBusConnection =
        synchronized(lock) {
            connection ?: connect().also { connection = it }
        }

    override suspend fun showItems(file: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                    val uri = Path.of(file).toUri().toASCIIString()
                    bus()
                        .getRemoteObject(FileManagerNames.BUS, FileManagerNames.PATH, FileManager1::class.java)
                        .ShowItems(listOf(uri), "")
                }
                .fold(
                    onSuccess = { true },
                    onFailure = {
                        Log.w(TAG, it) { "FileManager1.ShowItems unavailable; caller falls back" }
                        false
                    },
                )
        }

    override suspend fun chooseDirectory(
        title: String,
        start: String?,
    ): DirectoryChoice =
        withContext(Dispatchers.IO) {
            val bus = bus()
            val token = "neutrodyne${tokens.incrementAndGet()}"
            val sender = bus.uniqueName.removePrefix(":").replace('.', '_')
            val requestPath = "${PortalNames.PATH}/request/$sender/$token"
            val options =
                buildMap<String, Variant<*>> {
                    put(OPTION_HANDLE_TOKEN, Variant(token))
                    put(OPTION_DIRECTORY, Variant(true))
                    if (start != null) put(OPTION_CURRENT_FOLDER, Variant(start.toByteArray()))
                }

            suspendCancellableCoroutine { continuation ->
                var match: AutoCloseable? = null
                val handler =
                    object : DBusSigHandler<PortalRequest.Response> {
                        override fun handle(signal: PortalRequest.Response) {
                            if (continuation.isActive) continuation.resume(parseResponse(signal))
                        }
                    }
                match =
                    runCatching {
                        bus.addSigHandler(
                            DBusMatchRuleBuilder
                                .create()
                                .withType(PortalRequest.Response::class.java)
                                .withPath(requestPath)
                                .build(),
                            handler,
                        )
                    }.getOrNull()
                if (match == null) {
                    continuation.resume(DirectoryChoice.Unavailable)
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    runCatching { match?.close() }
                    closeRequest(requestPath)
                }
                try {
                    val chooser =
                        bus.getRemoteObject(PortalNames.BUS, PortalNames.PATH, PortalFileChooser::class.java)
                    val handle = chooser.OpenFile(parentWindow = "", title = title, options = options)
                    // The spec pins the returned handle to the predictable path when
                    // `handle_token` was honoured; a portal answering something else cannot
                    // be matched safely — the timeout-free wait stays on our rule, and the
                    // caller's cancellation cleans it up.
                    if (handle.path != requestPath) {
                        runCatching { match.close() }
                        continuation.resume(DirectoryChoice.Unavailable)
                    }
                } catch (e: Exception) {
                    runCatching { match.close() }
                    Log.w(TAG, e) { "portal FileChooser unavailable; caller falls back" }
                    continuation.resume(DirectoryChoice.Unavailable)
                }
            }
        }

    override fun close() {
        val bus =
            synchronized(lock) {
                val current = connection ?: return
                connection = null
                current
            }
        runCatching { bus.close() }
            .onFailure { Log.w(TAG, it) { "portal connection close failed" } }
    }

    private fun parseResponse(response: PortalRequest.Response): DirectoryChoice {
        val params = response.parameters ?: return DirectoryChoice.Unavailable
        val code = (params.getOrNull(RESPONSE_CODE) as? UInt32)?.toLong() ?: return DirectoryChoice.Unavailable
        if (code != RESPONSE_OK) return DirectoryChoice.Cancelled
        val results = params.getOrNull(RESPONSE_RESULTS) as? Map<*, *> ?: return DirectoryChoice.Cancelled
        val uris = results[RESULT_URIS] as? Variant<*> ?: return DirectoryChoice.Cancelled
        val uri = (uris.value as? List<*>)?.firstOrNull() as? String ?: return DirectoryChoice.Cancelled
        return try {
            DirectoryChoice.Chosen(Path.of(URI(uri)).toAbsolutePath().toString())
        } catch (e: Exception) {
            Log.w(TAG, e) { "portal returned an unusable uri" }
            DirectoryChoice.Cancelled
        }
    }

    /** Best-effort `Request.Close` on cancel, so the portal's dialog does not linger. */
    private fun closeRequest(requestPath: String) {
        runCatching {
            bus()
                .getRemoteObject(PortalNames.BUS, requestPath, PortalRequest::class.java)
                .Close()
        }.onFailure { Log.w(TAG, it) { "portal request close failed" } }
    }

    private companion object {
        const val TAG = "DbusPortal"

        /** Process-wide: two portal instances must never collide on a request path. */
        val tokens = AtomicLong()

        const val RESPONSE_CODE = 0
        const val RESPONSE_RESULTS = 1
        const val RESPONSE_OK = 0L
        const val RESULT_URIS = "uris"

        /** The FileChooser options the spec defines (v1 plus `current_folder`). */
        const val OPTION_HANDLE_TOKEN = "handle_token"
        const val OPTION_DIRECTORY = "directory"
        const val OPTION_CURRENT_FOLDER = "current_folder"
    }
}

internal object FileManagerNames {
    const val BUS = "org.freedesktop.FileManager1"
    const val PATH = "/org/freedesktop/FileManager1"
}

/** The file-manager interface's one call we need, member name verbatim. */
@Suppress("ktlint:standard:function-naming", "FunctionName")
@DBusInterfaceName(FileManagerNames.BUS)
internal interface FileManager1 : DBusInterface {
    fun ShowItems(
        uris: List<String>,
        startupId: String,
    )
}

@Suppress("ktlint:standard:function-naming", "FunctionName")
@DBusInterfaceName("org.freedesktop.portal.FileChooser")
internal interface PortalFileChooser : DBusInterface {
    /** v1 signature: `OpenFile(parent_window s, title s, options a{sv}) → handle o`. */
    fun OpenFile(
        parentWindow: String,
        title: String,
        options: Map<String, Variant<*>>,
    ): DBusPath
}
