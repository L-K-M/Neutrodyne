// SPDX-License-Identifier: Unlicense
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `verifyManifestPermissions`' permission set: `uses-permission-sdk-23` declarations apply on
 * every supported device (minSdk 26), so they join `uses-permission` in the closed comparison.
 */
class ManifestPermissionsTest {
    private fun parse(xml: String) =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(xml.byteInputStream())

    private fun manifest(body: String) =
        "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">$body</manifest>"

    @Test
    fun `uses-permission-sdk-23 declarations count as permissions`() {
        val doc =
            parse(
                manifest(
                    "<uses-permission android:name=\"android.permission.INTERNET\"/>" +
                        "<uses-permission-sdk-23 android:name=\"android.permission.READ_CONTACTS\"/>",
                ),
            )
        assertEquals(
            sortedSetOf("android.permission.INTERNET", "android.permission.READ_CONTACTS"),
            doc.permissionNames(),
        )
    }

    @Test
    fun `unrelated elements do not count`() {
        val doc =
            parse(
                manifest(
                    "<uses-permission android:name=\"android.permission.INTERNET\"/>" +
                        "<permission android:name=\"ch.lkmc.neutrodyne.OWN\"/>" +
                        "<uses-feature android:name=\"android.hardware.audio.output\"/>",
                ),
            )
        assertEquals(sortedSetOf("android.permission.INTERNET"), doc.permissionNames())
    }
}
