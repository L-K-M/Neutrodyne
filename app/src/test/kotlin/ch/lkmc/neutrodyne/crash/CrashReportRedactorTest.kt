// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.crash

import ch.lkmc.neutrodyne.core.common.CrashKey
import com.google.common.truth.Truth.assertThat
import org.acra.ReportField
import org.acra.data.CrashReportData
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 09 ACRA configuration: secrets never reach a stored report, unknown custom keys are dropped. */
@RunWith(RobolectricTestRunner::class)
class CrashReportRedactorTest {
    @Test
    fun stackTraceLosesCredentialsAndTokens() {
        val data = CrashReportData()
        data.put(ReportField.STACK_TRACE, "IOException fetching $FEED_WITH_SECRETS\n\tat x.y(Z.kt:1)")

        CrashReportRedactor.redact(data)

        val stack = data.getString(ReportField.STACK_TRACE)!!
        assertThat(stack).doesNotContain(PASSWORD)
        assertThat(stack).doesNotContain(TOKEN)
        assertThat(stack).contains("feeds.example.invalid")
    }

    @Test
    fun customDataKeepsOnlyAllowListedKeysRedacted() {
        val custom =
            JSONObject()
                .put(CrashKey.SCREEN.name, "FeedsKey")
                .put(CrashKey.SYNC.name, "failed for $FEED_WITH_SECRETS")
                .put("DEVICE_ID", "abc")
        val data = CrashReportData()
        data.put(ReportField.CUSTOM_DATA, custom)

        CrashReportRedactor.redact(data)

        val result = data.get(ReportField.CUSTOM_DATA.name) as JSONObject
        assertThat(result.has("DEVICE_ID")).isFalse()
        assertThat(result.getString(CrashKey.SCREEN.name)).isEqualTo("FeedsKey")
        assertThat(result.getString(CrashKey.SYNC.name)).doesNotContain(TOKEN)
    }

    @Test
    fun stackTraceLosesSecretsBehindIpv6Hosts() {
        val data = CrashReportData()
        data.put(ReportField.STACK_TRACE, "IOException: https://[2001:db8::1]/rss?token=$TOKEN")

        CrashReportRedactor.redact(data)

        assertThat(data.getString(ReportField.STACK_TRACE)).doesNotContain(TOKEN)
    }

    private companion object {
        const val PASSWORD = "s3cret"
        const val TOKEN = "SECRETTOKEN"
        const val FEED_WITH_SECRETS = "https://alice:$PASSWORD@feeds.example.invalid/rss?token=$TOKEN"
    }
}
