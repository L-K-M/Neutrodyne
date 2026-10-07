// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

/**
 * JUnit categories for test selection (09 `:core:testing` inventory). Declared in `commonMain` (not only
 * `desktopMain`) because the shared test configuration excludes [CrossDevice] in every JVM test task, Android host
 * tests included, and the category class must load there too.
 */
interface CrossDevice

/** Tests that flake by nature (live network, timing); excluded from PR runs. */
interface Flaky
