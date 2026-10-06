// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds

/**
 * Runs after `RunAppStartup` and before the UI opens (01 Application start-up). `order` is the
 * start-up band — in ms budget terms, from the start-up table: 25 / 50 / 80 / 150 / 300.
 * Implementations are contributed into the `Set<AppInitializer>` multibinding.
 */
interface AppInitializer {
    val order: Int

    suspend fun run()
}

/**
 * The shared initializer runner — a plain suspend function so both shells (Android `Application`
 * and desktop `main`) and tests call it identically (01 Testing). Runs sequentially on the caller's
 * context (the shells invoke it on `@Dispatcher(Default)`), ordered by [AppInitializer.order] then
 * fully-qualified class name; a failure is logged and the rest still run; cancellation propagates.
 */
suspend fun runInitializers(initializers: Set<AppInitializer>) {
    val ordered =
        initializers.sortedWith(
            compareBy({ it.order }, { it::class.qualifiedName ?: it::class.toString() }),
        )
    for (initializer in ordered) {
        val name = initializer::class.qualifiedName ?: initializer::class.toString()
        suspendRunCatching { initializer.run() }
            .onFailure { Log.e("AppInit", it) { "initializer $name failed" } }
    }
}

/** Empty `Set<AppInitializer>` so every graph compiles before any module contributes (01 DI). */
@ContributesTo(AppScope::class)
@BindingContainer
interface InitializerBindings {
    @Multibinds(allowEmpty = true)
    fun appInitializers(): Set<AppInitializer>
}
