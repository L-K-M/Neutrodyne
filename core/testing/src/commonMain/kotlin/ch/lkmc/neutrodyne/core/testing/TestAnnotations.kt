// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

/**
 * JUnit-style lifecycle and assertion API for shared test bases (09 Shared helpers). `kotlin.test`'s
 * names are typealiases to JUnit 4's, which live in `kotlin-test-junit` — an artifact KMP test
 * compilations get automatically but `commonMain` never sees. Expect/actual typealiases inside
 * `:core:testing` produce the real JUnit annotations and asserts in the compiled classes, so
 * runners pick them up on both targets with no extra dependency.
 */
@Target(AnnotationTarget.FUNCTION)
expect annotation class BeforeTest()

/** See [BeforeTest]. */
@Target(AnnotationTarget.FUNCTION)
expect annotation class AfterTest()

/** See [BeforeTest]; JUnit's `@Test`, so contract-base tests run on both targets. */
@Target(AnnotationTarget.FUNCTION)
expect annotation class Test()

/** See [BeforeTest]; `org.junit.Assert.assertEquals`. */
expect fun <T> assertEquals(
    expected: T,
    actual: T,
    message: String? = null,
)
