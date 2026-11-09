// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

/**
 * Instrumented tests that run only in nightly scopes (`notAnnotation` excludes them from `ci`, 09).
 * Quarantined `FlakyTest` cases also need this marker while AGP cannot carry compound exclusions.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Nightly

/** Instrumented tests that also run against the published `release` build (E0, E7; 09 Release-type test runs). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ReleaseSmoke
