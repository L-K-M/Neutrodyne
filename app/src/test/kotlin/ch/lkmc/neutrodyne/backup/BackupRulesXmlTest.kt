// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.backup

import android.app.Application
import ch.lkmc.neutrodyne.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** 05 Rules XML: inspect packaged resources, including API-qualified selection. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupRulesXmlTest {
    @Test
    @Config(sdk = [26])
    fun legacyAndroidIncludesOnlyTheAbsentPlaceholder() {
        assertEquals(
            listOf(
                Element("full-backup-content", 1),
                include("backup/.none", depth = 2),
            ),
            read(R.xml.backup_rules),
        )
    }

    @Test
    @Config(sdk = [28])
    fun androidNineHasSeparateEncryptionAndDeviceTransferIncludes() {
        assertEquals(
            listOf(
                Element("full-backup-content", 1),
                include(SNAPSHOT, depth = 2, flags = "clientSideEncryption"),
                include(SETTINGS, depth = 2, flags = "clientSideEncryption"),
                include(SNAPSHOT, depth = 2, flags = "deviceToDeviceTransfer"),
                include(SETTINGS, depth = 2, flags = "deviceToDeviceTransfer"),
            ),
            read(R.xml.backup_rules),
        )
    }

    @Test
    @Config(sdk = [31, 36])
    fun modernAndroidIncludesOnlySnapshotsAndPortableSettingsInBothModes() {
        assertEquals(
            listOf(
                Element("data-extraction-rules", 1),
                Element("cloud-backup", 2, mapOf("disableIfNoEncryptionCapabilities" to "true")),
                include(SNAPSHOT, depth = 3),
                include(SETTINGS, depth = 3),
                Element("device-transfer", 2),
                include(SNAPSHOT, depth = 3),
                include(SETTINGS, depth = 3),
            ),
            read(R.xml.data_extraction_rules),
        )
    }

    private fun read(resourceId: Int): List<Element> =
        RuntimeEnvironment.getApplication().resources.getXml(resourceId).use { parser ->
            buildList {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue

                    val attributes =
                        (0 until parser.attributeCount).associate { index ->
                            parser.getAttributeName(index) to parser.getAttributeValue(index)
                        }
                    add(Element(parser.name, parser.depth, attributes))
                }
            }
        }

    private fun include(
        path: String,
        depth: Int,
        flags: String? = null,
    ): Element =
        Element(
            "include",
            depth,
            buildMap {
                put("domain", "file")
                put("path", path)
                if (flags != null) put("requireFlags", flags)
            },
        )

    private data class Element(
        val name: String,
        val depth: Int,
        val attributes: Map<String, String> = emptyMap(),
    )

    private companion object {
        const val SNAPSHOT = "backup/auto-snapshot.zip"
        const val SETTINGS = "datastore/settings.preferences_pb"
    }
}
