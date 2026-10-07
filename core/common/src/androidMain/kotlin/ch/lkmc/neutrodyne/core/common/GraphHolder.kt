// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * The bridge from framework-created components (Service, Activity, BroadcastReceiver,
 * ContentProvider, WorkManager workers) into the Metro graph (01 Dependency injection):
 * `NeutrodyneApplication` implements it, services reach it via
 * `(applicationContext as GraphHolder).graph as <Component>Injector`.
 */
interface GraphHolder {
    /** The `AndroidAppGraph` — cast to the component's injector interface at each call site. */
    val graph: Any
}
