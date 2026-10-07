// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.l10n

import android.content.Context
import ch.lkmc.neutrodyne.BuildConfig
import ch.lkmc.neutrodyne.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * S11's generated-locale check (01 S11): the `locales_config.xml` that `generateLocaleConfig`
 * merges into the app resources must list exactly `app/policy/locales.txt` — no more, no fewer.
 * Robolectric reads the merged unit-test resources, and the policy file is read via the
 * `neutrodyne.moduleDir`/`neutrodyne.rootDir` properties the test conventions set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocaleConfigTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun localesConfigMatchesThePolicyFile() {
        assertThat(readLocalesConfig(context)).containsExactlyElementsIn(expectedLocales())
    }

    @Test
    fun buildConfigMirrorsThePolicyFile() {
        // The two places the policy file is consumed stay in step by construction; pin it.
        assertThat(BuildConfig.SHIPPED_LOCALES.split(',').map { it.trim() })
            .containsExactlyElementsIn(shippedLocales())
    }

    private fun shippedLocales(): List<String> =
        File(moduleDir(), "policy/locales.txt")
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Debug adds the two generated pseudo-locale qualifiers to the config. */
    private fun expectedLocales(): List<String> =
        shippedLocales() + if (BuildConfig.DEBUG) listOf("en-XA", "ar-XB") else emptyList()

    private fun moduleDir(): String =
        System.getProperty("neutrodyne.moduleDir")
            ?: error("neutrodyne.moduleDir not set by the test conventions")

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
