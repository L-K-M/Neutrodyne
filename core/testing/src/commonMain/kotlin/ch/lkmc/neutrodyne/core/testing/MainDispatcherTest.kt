// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Common-test base class for code that uses `Dispatchers.Main` (ViewModels) (09 Shared helpers):
 * [dispatcher] is installed as Main before each test; `runTest` reuses a Main-installed
 * `TestDispatcher`'s scheduler (coroutines 1.11), so tests write plain `runTest {}` without
 * passing dispatchers twice. Lifecycle hooks are our [BeforeTest]/[AfterTest] — `kotlin.test`'s
 * own names live in `kotlin-test-junit`, which main source sets never see.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class MainDispatcherTest(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) {
    @BeforeTest
    fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()
}
