// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.di.AndroidAppGraph
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds the incoming grant until create has copied it; no worker or outgoing intent gets the URI. */
class ExternalImportActivity : AppCompatActivity() {
    @Inject
    internal lateinit var imports: Lazy<ImportRepository>

    @Inject
    @Dispatcher(NeutrodyneDispatchers.IO)
    internal lateinit var io: CoroutineDispatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = incomingUri(intent)
        if (uri == null) {
            Toast.makeText(this, R.string.nothing_to_import, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        ((application as GraphHolder).graph as AndroidAppGraph).inject(this)
        val repository = imports
        val copyDispatcher = io
        val model =
            ViewModelProvider(
                this,
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return ExternalImportViewModel(repository, copyDispatcher) as T
                    }
                },
            )[ExternalImportViewModel::class.java]
        model.receive(uri.toString())

        lifecycleScope.launch {
            model.result.collect { result ->
                if (result == null) return@collect
                val handoff =
                    Intent(this@ExternalImportActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                when (result) {
                    is Outcome.Success -> {
                        handoff.action = Intent.ACTION_VIEW
                        handoff.data = "neutrodyne://open/import/${result.value}".toUri()
                    }

                    is Outcome.Failure -> {
                        handoff.putExtra(IMPORT_ERROR_EXTRA, result.error.name)
                    }
                }
                startActivity(handoff)
                finish()
            }
        }
        lifecycleScope.launch {
            delay(PROGRESS_DELAY_MS)
            if (model.result.value != null) return@launch
            val overlay = FrameLayout(this@ExternalImportActivity)
            overlay.addView(
                ProgressBar(this@ExternalImportActivity),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            setContentView(overlay)
        }
    }

    private fun incomingUri(intent: Intent): Uri? {
        val uri =
            try {
                when (intent.action) {
                    Intent.ACTION_VIEW -> {
                        intent.data
                    }

                    Intent.ACTION_SEND -> {
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                            ?: intent.clipData
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.uri
                    }

                    else -> {
                        null
                    }
                }
            } catch (_: RuntimeException) {
                // Malformed parcelables are external input, not a reason to crash the host.
                null
            }
        return uri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }
    }

    private companion object {
        const val PROGRESS_DELAY_MS = 300L
    }
}

/** Rotation retains the caller's copy; the repository owns the subsequent application-scope job. */
internal class ExternalImportViewModel(
    private val imports: Lazy<ImportRepository>,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    val result = MutableStateFlow<Outcome<Long, ImportAcquisitionError>?>(null)
    private var started = false

    fun receive(uri: String) {
        if (started) return
        started = true
        viewModelScope.launch {
            result.value =
                when (
                    val created =
                        suspendRunCatching {
                            // Resolving the database-backed lazy may wait for startup; never block main.
                            withContext(io) { imports.value.create(ImportSource(uri, displayName = null)) }
                        }.getOrElse { Outcome.Failure(ImportError.Storage) }
                ) {
                    is Outcome.Success -> created
                    is Outcome.Failure -> Outcome.Failure(ImportAcquisitionError.from(created.error))
                }
        }
    }
}
