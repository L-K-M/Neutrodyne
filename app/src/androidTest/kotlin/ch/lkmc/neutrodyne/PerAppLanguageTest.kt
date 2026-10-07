// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.ComposeTimeoutException
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * S11's Android legs (01 S11): `AppCompatDelegate.setApplicationLocales` relabels the destination
 * items through the `core/ui` Compose resources without a test-side activity restart — AppCompat
 * (API < 33) or the framework `LocaleManager` recreates `MainActivity` itself. The generated
 * `locales_config.xml` is also read on device so the merged-resource contents are checked, not
 * just the build-time file. Runs on the API 26 and 36 GMDs; the relabel leg skips below API 33
 * while only `en-US` ships (the S11 finding in the test body).
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
        // S11 finding (2026-10-07): below API 33 the framework reorders the activity configuration
        // so a locale the APK's Android resources ship comes first, and AppCompat sets the process
        // default (what Compose resources read) from it. A requested locale the build does not ship
        // therefore stays English there; the picker only offers shipped locales (09), and every
        // shipped locale has `:app` Android resources. Until a second locale ships, the API < 33 leg
        // has nothing to switch to.
        val shipped = shippedLocales().map { it.substringBefore('-') }
        assumeTrue(
            "API ${Build.VERSION.SDK_INT} < 33 switches only to shipped locales; '$GERMAN' is not shipped yet",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || GERMAN in shipped,
        )

        // Baseline: the GMDs boot en-US.
        compose.onNodeWithTag("nav_library").assertIsDisplayed().assert(hasText("Library"))

        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(GERMAN))
        }

        // The activity recreates itself; wait for the German resources to surface. On a timeout the
        // failure reports the locale chain (AppCompat's app locales → the activity configuration →
        // the process default Compose resources read), so a device run shows where it broke.
        try {
            compose.waitUntil(RELABEL_TIMEOUT_MS) {
                compose.onAllNodesWithText("Bibliothek").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("German labels did not appear: ${localeChain()}", e)
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

    private fun localeChain(): String {
        var activityLocales = "no activity"
        compose.activityRule.scenario.onActivity {
            activityLocales =
                it.resources.configuration.locales
                    .toLanguageTags()
        }
        return "appLocales=${AppCompatDelegate.getApplicationLocales().toLanguageTags()}, " +
            "activity=$activityLocales, LocaleList.getDefault=${LocaleList.getDefault().toLanguageTags()}, " +
            "Locale.getDefault=${Locale.getDefault().toLanguageTag()}, sdk=${Build.VERSION.SDK_INT}"
    }

    private fun readLocalesConfig(context: Context): List<String> {
        val names = mutableListOf<String>()
        val parser = context.resources.getXml(localeConfigXmlId())
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    names += parser.getAttributeValue(ANDROID_NS, "name").orEmpty()
                }
                parser.next()
            }
        } finally {
            parser.close()
        }
        return names
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val RELABEL_TIMEOUT_MS = 10_000L
        const val GERMAN = "de"

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
