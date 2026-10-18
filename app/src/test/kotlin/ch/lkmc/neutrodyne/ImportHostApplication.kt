// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Application
import android.os.Looper
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.model.CreateImportOptions
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.di.AndroidAppGraph
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.Dispatchers
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy

/** The framework creates real activities; only their graph inputs are replaced. */
class ImportHostApplication :
    Application(),
    GraphHolder {
    val productionGraph by lazy { createGraphFactory<AndroidAppGraph.Factory>().create(this) }
    var imports: ImportRepository = stubImports { Outcome.Success(42L) }
    var importsProvider: Lazy<ImportRepository>? = null
    override val graph: AndroidAppGraph =
        Proxy.newProxyInstance(
            AndroidAppGraph::class.java.classLoader,
            arrayOf(AndroidAppGraph::class.java),
        ) { _, method, args ->
            if (method.name == "inject" && args?.firstOrNull() is ExternalImportActivity) {
                (args[0] as ExternalImportActivity).apply {
                    imports = importsProvider ?: lazy { this@ImportHostApplication.imports }
                    io = Dispatchers.IO
                }
                return@newProxyInstance null
            }
            method.invoke(productionGraph, *(args ?: emptyArray()))
        } as AndroidAppGraph
}

internal fun stubImports(create: suspend (ImportSource) -> Outcome<Long, ImportError>): ImportRepository {
    val unused =
        Proxy.newProxyInstance(
            ImportRepository::class.java.classLoader,
            arrayOf(ImportRepository::class.java),
        ) { _, method, _ -> error("Unexpected repository call: ${method.name}") } as ImportRepository
    return object : ImportRepository by unused {
        override suspend fun create(
            source: ImportSource,
            options: CreateImportOptions,
        ) = create(source)
    }
}

internal fun pumpUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + 20_000_000_000L
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Activity result did not arrive within 20 seconds" }
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }
    shadowOf(Looper.getMainLooper()).idle()
}
