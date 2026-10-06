// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * S11's Android legs (01 S11): `AppCompatDelegate.setApplicationLocales` relabels the destination
 * items through the `core/ui` Compose resources without a test-side activity restart — AppCompat
 * (API < 33) or the framework `LocaleManager` recreates `MainActivity` itself. The generated
 * `locales_config.xml` is also read on device so the merged-resource contents are checked, not
 * just the build-time file. Runs on the API 26 (AppCompat path) and 36 (framework path) GMDs.
 */
@RunWith(AndroidJUnit4::class)
class PerAppLanguageTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun restoreLocales() {
        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    @Test
    fun perAppLanguageRelabelsTheDestinations() {
        // Baseline: the GMDs boot en-US.
        compose.onNodeWithTag("nav_library").assertIsDisplayed().assert(hasText("Library"))

        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("de"))
        }

        // The activity recreates itself; wait for the German resources to surface.
        compose.waitUntil(RELABEL_TIMEOUT_MS) {
            compose.onAllNodesWithText("Bibliothek").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("nav_library").assertIsDisplayed().assert(hasText("Bibliothek"))
        compose.onNodeWithTag("nav_up_next").assertIsDisplayed().assert(hasText("Als Nächstes"))
        compose.onNodeWithTag("nav_discover").assertIsDisplayed().assert(hasText("Entdecken"))
    }

    @Test
    fun generatedLocalesConfigListsExactlyTheShippedLocales() {
        // The file generateLocaleConfig merges into the app resources; the OS settings page
        // offers exactly these names through the android.localeConfig manifest property.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThat(readLocalesConfig(context)).containsExactlyElementsIn(expectedLocales())
    }

    private fun readLocalesConfig(context: Context): List<String> {
        val names = mutableListOf<String>()
        val parser = context.resources.getXml(localeConfigXmlId())
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                names += parser.getAttributeValue(ANDROID_NS, "name").orEmpty()
            }
            parser.next()
        }
        parser.close()
        return names
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val RELABEL_TIMEOUT_MS = 10_000L

        /** `app/policy/locales.txt`, mirrored through `BuildConfig.SHIPPED_LOCALES`. */
        fun shippedLocales(): List<String> = BuildConfig.SHIPPED_LOCALES.split(',').map { it.trim() }

        /** Debug adds the two generated pseudo-locale qualifiers to the config. */
        fun expectedLocales(): List<String> =
            shippedLocales() + if (BuildConfig.DEBUG) listOf("en-XA", "ar-XB") else emptyList()

        /**
         * AGP names the generated file (`_generated_res_locale_config` in 9.4); find it by suffix
         * instead of baking an implementation detail into the test.
         */
        fun localeConfigXmlId(): Int =
            R.xml::class.java.fields
                .single { it.name.endsWith("locale_config") }
                .getInt(null)
    }
}
