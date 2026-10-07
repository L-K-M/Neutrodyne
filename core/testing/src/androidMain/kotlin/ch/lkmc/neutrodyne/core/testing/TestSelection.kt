// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

/** Instrumented tests that run only in nightly scopes (`notAnnotation` filters them out of `ci`, 09). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Nightly

/** Instrumented tests that also run against the published `release` build (E0, E7; 09 Release-type test runs). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ReleaseSmoke
