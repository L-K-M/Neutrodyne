// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.benchmark

import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * The nightly `benchmark` scope's two gated Macrobenchmarks (PB1 and PB3) in
 * one instrumentation run.
 *
 * AGP 9.4.1 forwards instrumentation arguments to its managed-device engine
 * through a single comma-joined string that the engine splits again without
 * escaping (`android-test.instrumentation-args`), so a comma-separated
 * `class` argument loses everything after the first comma - every hosted run
 * through 42530327038 executed only `ColdStartBenchmark` while reporting
 * green. Running the pair through this suite keeps the class selection a
 * single comma-free name and both results in one cumulative
 * `benchmarkData.json` (rewritten after each test; see
 * `benchmark-live-capture.py`).
 *
 * `ColdStartBenchmark` and `CoverGridBenchmark` remain individually runnable
 * for narrower invocations.
 */
@Suite.SuiteClasses(ColdStartBenchmark::class, CoverGridBenchmark::class)
@RunWith(Suite::class)
class GatedBenchmarks
