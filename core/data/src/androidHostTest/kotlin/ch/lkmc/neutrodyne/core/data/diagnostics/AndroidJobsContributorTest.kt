// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 08's JOBS card is "every app's WorkManager work": a lane without the maintenance or
 * refresh tag must still be listed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AndroidJobsContributorTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            application,
            Configuration.Builder().build(),
        )
    }

    @After
    fun cleanup() {
        WorkManager.getInstance(application).cancelAllWork().result.get()
    }

    @Test
    fun theJobsCardListsWorkOutsideMaintenanceAndRefresh() =
        runTest {
            WorkManager
                .getInstance(application)
                .enqueue(OneTimeWorkRequestBuilder<ProbeWorker>().addTag("download-cleanup").build())
                .result
                .get()

            val lines = AndroidJobsContributor(application).collect()
            assertThat(lines.map { it.key }).contains("download-cleanup")
        }

    class ProbeWorker(
        appContext: Context,
        params: WorkerParameters,
    ) : Worker(appContext, params) {
        override fun doWork(): Result = Result.success()
    }
}
