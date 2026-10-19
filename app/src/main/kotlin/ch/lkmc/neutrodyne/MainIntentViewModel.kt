// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.ComponentName
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.UnsupportedSource
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.IntentRouter
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.RouteInput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow

internal const val INTERNAL_MAIN_ACTIVITY = "ch.lkmc.neutrodyne.InternalMainActivity"
internal const val IMPORT_ERROR_EXTRA = "ch.lkmc.neutrodyne.IMPORT_ERROR"

/** The root drains this queue after startup. Rotation must not drop an intent waiting at the gate. */
internal class MainIntentViewModel(
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val pending = Channel<Route>(Channel.UNLIMITED)
    val routes =
        pending.receiveAsFlow().onEach { route ->
            val sessionId = (route as? Route.Navigate)?.stack?.singleOrNull() as? ImportKey
            if (sessionId?.sessionId?.toLongOrNull() == savedState.get<Long>(PENDING_SESSION)) {
                savedState.remove<Long>(PENDING_SESSION)
            }
        }
    private val pendingError = MutableStateFlow(errorCode(savedState[PENDING_ERROR]))
    val error = pendingError.asStateFlow()

    init {
        savedState.get<Long>(PENDING_SESSION)?.takeIf { it > 0 }?.let { id ->
            pending.trySend(Route.Navigate(LibraryKey, listOf(ImportKey(id.toString()))))
        }
    }

    fun dismissError() {
        pendingError.value = null
        savedState.remove<String>(PENDING_ERROR)
    }

    fun receive(
        intent: Intent,
        packageName: String,
    ) {
        // The OS denies foreign callers access to this alias. A destination package alone
        // never identifies the sender of an intent to the exported launcher.
        val internal = intent.component == ComponentName(packageName, INTERNAL_MAIN_ACTIVITY)
        if (internal) {
            errorCode(intent.getStringExtra(IMPORT_ERROR_EXTRA))?.let { receivedError ->
                pendingError.value = receivedError
                savedState[PENDING_ERROR] = receivedError.name
            }
        }
        val action =
            when (intent.action) {
                Intent.ACTION_VIEW -> RouteInput.Action.VIEW
                else -> RouteInput.Action.LAUNCH
            }
        val route = IntentRouter.route(RouteInput(action, uri = intent.dataString, internal = internal))
        if (route == Route.None) return

        val importKey = (route as? Route.Navigate)?.stack?.singleOrNull() as? ImportKey
        importKey?.sessionId?.toLongOrNull()?.let { savedState[PENDING_SESSION] = it }
        pending.trySend(route)
    }

    private fun errorCode(name: String?): ImportAcquisitionError? =
        ImportAcquisitionError.entries.firstOrNull { it.name == name }

    private companion object {
        const val PENDING_SESSION = "pendingImportSession"
        const val PENDING_ERROR = "pendingImportError"
    }
}

/** Only a bounded error code crosses activities, never a provider URI or exception text. */
internal enum class ImportAcquisitionError(
    val message: Int,
) {
    UNREADABLE(R.string.import_unreadable),
    TOO_LARGE(R.string.import_too_large),
    UNSUPPORTED(R.string.import_unsupported),
    SINGLE_FEED(R.string.import_single_feed),
    HOSTILE(R.string.import_hostile),
    EMPTY(R.string.import_empty),
    STORAGE(R.string.import_storage),
    GONE(R.string.import_gone),
    NETWORK(R.string.import_network),
    ;

    companion object {
        fun from(error: ImportError): ImportAcquisitionError =
            when (error) {
                ImportError.Unreadable -> {
                    UNREADABLE
                }

                ImportError.TooLarge -> {
                    TOO_LARGE
                }

                is ImportError.Unsupported -> {
                    if (error.kind == UnsupportedSource.SINGLE_FEED) SINGLE_FEED else UNSUPPORTED
                }

                is ImportError.Hostile -> {
                    HOSTILE
                }

                ImportError.Empty -> {
                    EMPTY
                }

                ImportError.Storage -> {
                    STORAGE
                }

                ImportError.SessionGone -> {
                    GONE
                }

                is ImportError.Network -> {
                    NETWORK
                }
            }
    }
}
