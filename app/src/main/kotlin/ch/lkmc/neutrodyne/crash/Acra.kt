// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.crash

import android.app.Application
import ch.lkmc.neutrodyne.BuildConfig
import ch.lkmc.neutrodyne.ProcessStartProbe
import ch.lkmc.neutrodyne.R
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfigurationBuilder
import org.acra.config.dialog
import org.acra.config.mailSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra

/** ACRA's own preference file; its enable flag mirrors `privacy.crash_reports` (09 Settings). */
private const val ACRA_PREFERENCES = "acra"
private const val REPORT_FILE_NAME = "neutrodyne-crash.txt"

/**
 * The only report fields that leave the device (09 ACRA configuration): never LOGCAT, BUILD_CONFIG (it may hold
 * a Podcast Index key, PO-3), SHARED_PREFERENCES or device identifiers.
 */
private val REPORT_CONTENT =
    listOf(
        ReportField.REPORT_ID,
        ReportField.APP_VERSION_NAME,
        ReportField.APP_VERSION_CODE,
        ReportField.ANDROID_VERSION,
        ReportField.BRAND,
        ReportField.PHONE_MODEL,
        ReportField.STACK_TRACE,
        ReportField.CUSTOM_DATA,
        ReportField.USER_COMMENT,
        ReportField.USER_CRASH_DATE,
        ReportField.IS_SILENT,
    )

/**
 * Installs ACRA with mail + dialog: zero network traffic, the user sends each report from their own mail app
 * (D62). Called from `NeutrodyneApplication.attachBaseContext` only when the mailbox is configured; [mailTo]
 * exists for `YtxIsolationTest`, which installs it with a test address.
 */
internal fun installAcra(
    app: Application,
    mailTo: String = BuildConfig.ACRA_MAILTO,
) {
    ACRA.log = RedactingAcraLog // before init: ACRA logs the original exception before redaction runs
    app.initAcra(acraConfiguration(app, mailTo))
    ProcessStartProbe.record(ProcessStartProbe.Event.ACRA_INSTALLED)
}

private fun acraConfiguration(
    app: Application,
    mailTo: String,
): CoreConfigurationBuilder.() -> Unit =
    {
        buildConfigClass = BuildConfig::class.java
        sharedPreferencesName = ACRA_PREFERENCES
        reportFormat = StringFormat.KEY_VALUE_LIST
        reportContent = REPORT_CONTENT
        mailSender {
            this.mailTo = mailTo
            reportAsFile = true
            reportFileName = REPORT_FILE_NAME
            subject = app.getString(R.string.crash_mail_subject)
            body = app.getString(R.string.crash_mail_body)
        }
        dialog {
            title = app.getString(R.string.crash_dialog_title)
            text = app.getString(R.string.crash_dialog_text)
            commentPrompt = app.getString(R.string.crash_dialog_comment)
        }
    }
