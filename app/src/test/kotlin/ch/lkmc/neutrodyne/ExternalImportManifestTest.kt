// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ExternalImportManifestTest {
    @Test
    fun externalReceiverHasExactlyTheDocumentedFilters() {
        val manifest =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(File("src/main/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        val receiver =
            (0 until activities.length)
                .map { activities.item(it) as Element }
                .singleOrNull { it.attr("name") == ".ExternalImportActivity" }
        assertThat(receiver).isNotNull()
        receiver!!
        assertThat(receiver.attr("exported")).isEqualTo("true")
        assertThat(receiver.attr("excludeFromRecents")).isEqualTo("true")
        assertThat(receiver.attr("noHistory")).isEqualTo("true")
        assertThat(receiver.attr("theme")).isEqualTo("@style/Theme.Neutrodyne.Translucent")
        val filters = receiver.getElementsByTagName("intent-filter")
        assertThat(filters.length).isEqualTo(2)
        assertThat((filters.item(0) as Element).values("action", "name"))
            .containsExactly("android.intent.action.VIEW", "android.intent.action.SEND")
        assertThat((filters.item(0) as Element).values("data", "mimeType"))
            .containsExactly("text/x-opml", "text/xml", "application/xml", "application/zip")
        assertThat((filters.item(1) as Element).values("action", "name"))
            .containsExactly("android.intent.action.SEND")
        assertThat((filters.item(1) as Element).values("data", "mimeType"))
            .containsExactly("application/octet-stream")
        for (index in 0 until filters.length) {
            val filter = filters.item(index) as Element
            assertThat(filter.values("category", "name")).containsExactly("android.intent.category.DEFAULT")
            val dataNodes = filter.getElementsByTagName("data")
            for (dataIndex in 0 until dataNodes.length) {
                assertThat(dataNodes.item(dataIndex).attributes.length).isEqualTo(1)
            }
            assertThat(filter.values("data", "scheme").all { it.isEmpty() }).isTrue()
        }
        val aliases = manifest.getElementsByTagName("activity-alias")
        val alias =
            (0 until aliases.length)
                .map { aliases.item(it) as Element }
                .single { it.attr("name") == ".OpmlByExtensionAlias" }
        assertThat(alias.attr("name")).isEqualTo(".OpmlByExtensionAlias")
        assertThat(alias.attr("targetActivity")).isEqualTo(".ExternalImportActivity")
        assertThat(alias.attr("enabled")).isEqualTo("@bool/neutrodyne_api31_or_newer")
        assertThat(alias.attr("exported")).isEqualTo("true")
        assertThat(alias.getElementsByTagName("intent-filter").length).isEqualTo(1)
        assertThat(alias.values("action", "name")).containsExactly("android.intent.action.VIEW")
        assertThat(alias.values("category", "name")).containsExactly("android.intent.category.DEFAULT")
        val data = alias.getElementsByTagName("data").item(0) as Element
        assertThat(data.attributes.length).isEqualTo(4)
        assertThat(data.attr("scheme")).isEqualTo("content")
        assertThat(data.attr("host")).isEqualTo("*")
        assertThat(data.attr("mimeType")).isEqualTo("*/*")
        assertThat(data.attr("pathSuffix")).isEqualTo(".opml")
    }

    private fun Element.attr(name: String) = getAttribute("android:$name")

    private fun Element.values(
        tag: String,
        attr: String,
    ): List<String> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { (nodes.item(it) as Element).attr(attr) }
    }
}
