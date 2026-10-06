// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import dev.zacsweers.metro.Qualifier

/**
 * Distinguishes the two dispatchers everything injects (01 Coroutines and threading). Binding them
 * by qualifier is what lets `Dispatchers.setMain` and test dispatchers work in unit tests.
 */
enum class NeutrodyneDispatchers { IO, Default }

/** Metro qualifier carrying a [NeutrodyneDispatchers] value: `@Dispatcher(IO)` / `@Dispatcher(Default)`. */
@Qualifier
annotation class Dispatcher(
    val dispatcher: NeutrodyneDispatchers,
)

/**
 * Process-wide Metro scope: `@ContributesTo(AppScope::class)`, `@SingleIn(AppScope::class)`,
 * `@DependencyGraph(AppScope::class)`.
 */
@Qualifier
annotation class ApplicationScope
