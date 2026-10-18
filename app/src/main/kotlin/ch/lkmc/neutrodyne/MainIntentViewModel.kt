// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Intent
import androidx.lifecycle.ViewModel
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.UnsupportedSource
import ch.lkmc.neutrodyne.core.navigation.IntentRouter
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.RouteInput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

internal const val IMPORT_ERROR_EXTRA = "ch.lkmc.neutrodyne.IMPORT_ERROR"

/** The root drains this queue after startup. Rotation must not drop an intent waiting at the gate. */
internal class MainIntentViewModel : ViewModel() {
    private val pending = Channel<Route>(Channel.UNLIMITED)
    val routes = pending.receiveAsFlow()
    val error = MutableStateFlow<ImportAcquisitionError?>(null)

    fun receive(
        intent: Intent,
        packageName: String,
    ) {
        val internal = intent.component?.packageName == packageName
        if (internal) {
            val errorName = intent.getStringExtra(IMPORT_ERROR_EXTRA)
            error.value = ImportAcquisitionError.entries.firstOrNull { it.name == errorName }
        }
        val action =
            when (intent.action) {
                Intent.ACTION_VIEW -> RouteInput.Action.VIEW
                Intent.ACTION_SEND -> RouteInput.Action.SEND
                else -> RouteInput.Action.LAUNCH
            }
        val route = IntentRouter.route(RouteInput(action, uri = intent.dataString, internal = internal))
        if (route != Route.None) pending.trySend(route)
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
