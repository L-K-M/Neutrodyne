// SPDX-License-Identifier: Unlicense
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The E12 desktop selector (09): `:desktopApp`'s test tasks exclude the `CrossDevice` category
 * unless the `crossDevice` property is present. The predicate takes the project path explicitly —
 * the unqualified `path` inside `configureEach` is the Test task's path (`:desktopApp:test`),
 * which never matched and silently disabled the exclusion.
 */
class CrossDeviceSelectionTest {
    @Test
    fun `desktopApp excludes the category by default`() {
        assertTrue(crossDeviceCategoryExcluded(":desktopApp", crossDevicePresent = false))
    }

    @Test
    fun `desktopApp includes the category when crossDevice is present`() {
        assertFalse(crossDeviceCategoryExcluded(":desktopApp", crossDevicePresent = true))
    }

    @Test
    fun `other modules are never excluded`() {
        assertFalse(crossDeviceCategoryExcluded(":sync:impl", crossDevicePresent = false))
        assertFalse(crossDeviceCategoryExcluded(":", crossDevicePresent = false))
    }

    @Test
    fun `the task path is not the project path`() {
        // The old selector read the unqualified `path` inside `configureEach`, which is the task
        // path; it never matched and the exclusion silently did not apply.
        assertFalse(crossDeviceCategoryExcluded(":desktopApp:test", crossDevicePresent = false))
        assertTrue(crossDeviceCategoryExcluded(":desktopApp", crossDevicePresent = false))
    }
}
