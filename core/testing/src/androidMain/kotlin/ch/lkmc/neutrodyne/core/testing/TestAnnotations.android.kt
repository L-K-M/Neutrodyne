// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

actual typealias BeforeTest = org.junit.Before
actual typealias AfterTest = org.junit.After
actual typealias Test = org.junit.Test

actual fun <T> assertEquals(
    expected: T,
    actual: T,
    message: String?,
) {
    org.junit.Assert.assertEquals(message, expected, actual)
}
