// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.crash

import android.content.Context
import ch.lkmc.neutrodyne.core.common.CrashKey
import ch.lkmc.neutrodyne.core.common.Redactor
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.config.ReportingAdministrator
import org.acra.data.CrashReportData
import org.json.JSONObject

/**
 * Redacts every report before ACRA stores it (09 ACRA configuration): the stack trace and each custom value pass
 * [Redactor.text], and custom keys outside [CrashKey] are dropped. ACRA 5.14.2 calls `shouldSendReport` before
 * `saveCrashReportFile`, so the stored file that the dialog and mail sender use is already redacted. Loaded
 * through `META-INF/services/org.acra.config.ReportingAdministrator`.
 */
class CrashReportRedactor : ReportingAdministrator {
    override fun shouldSendReport(
        context: Context,
        config: CoreConfiguration,
        crashReportData: CrashReportData,
    ): Boolean {
        redact(crashReportData)
        return true
    }

    internal companion object {
        private val ALLOWED_CUSTOM_KEYS = CrashKey.entries.map { it.name }.toSet()

        fun redact(data: CrashReportData) {
            data.getString(ReportField.STACK_TRACE)?.let { data.put(ReportField.STACK_TRACE, Redactor.text(it)) }

            val custom = data.get(ReportField.CUSTOM_DATA.name) ?: return
            data.put(ReportField.CUSTOM_DATA, redactedCustomData(custom))
        }

        /** ACRA keeps custom data as a JSON object; anything else is treated as opaque text. */
        private fun redactedCustomData(custom: Any): JSONObject {
            val result = JSONObject()
            val source = custom as? JSONObject ?: return result

            for (key in source.keys()) {
                if (key !in ALLOWED_CUSTOM_KEYS) continue
                result.put(key, Redactor.text(source.optString(key)))
            }
            return result
        }
    }
}
