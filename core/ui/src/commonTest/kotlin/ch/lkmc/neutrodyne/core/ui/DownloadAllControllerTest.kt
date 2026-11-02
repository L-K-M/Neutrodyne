// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.model.DownloadAllEstimate
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController
import ch.lkmc.neutrodyne.download.api.RejectReason
import ch.lkmc.neutrodyne.download.api.RequestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadAllControllerTest {
    private var notificationPrompts = 0
    private val estimate =
        DownloadAllEstimate(
            listOf(
                1,
                2,
            ),
            999,
            12_000_000,
            1,
            true,
        )

    @Test fun rejectionAndCancelledReadNeverCreateRows() =
        runTest {
            val downloads = FakeDownloadController()
            val gate = CompletableDeferred<DownloadAllEstimate>()
            val controller =
                DownloadAllController(
                    backgroundScope,
                    { gate.await() },
                    downloads,
                    { notificationPrompts++ },
                )
            controller.open(FeedSource.Group(7))
            runCurrent()
            controller.confirm()
            controller.dismiss()
            gate.complete(estimate)
            runCurrent()
            assertNull(controller.state.value)
            assertTrue(downloads.calls.isEmpty())
            controller.open(FeedSource.Podcast(3))
            runCurrent()
            assertEquals(
                estimate,
                controller.state.value?.estimate,
            )
            controller.dismiss()
            controller.confirm()
            assertTrue(downloads.calls.isEmpty())
        }

    @Test fun confirmationSubmitsExactlyTheOfferedIdsAsManualOnce() =
        runTest {
            val downloads =
                FakeDownloadController().apply {
                    requestResult =
                        RequestResult.Queued(
                            2,
                            0,
                            emptyMap(),
                            true,
                        )
                }
            val controller =
                DownloadAllController(
                    backgroundScope,
                    { estimate },
                    downloads,
                    { notificationPrompts++ },
                )
            controller.open(FeedSource.Group(7))
            runCurrent()
            controller.confirm()
            controller.confirm()
            runCurrent()
            assertEquals(
                1,
                downloads.calls.size,
            )
            assertEquals(
                estimate.episodeIds,
                downloads.calls.single().ids,
            )
            assertEquals(
                DownloadLane.MANUAL,
                downloads.calls.single().lane,
            )
            assertNull(downloads.calls.single().allowMetered)
            assertNull(controller.state.value)
            assertEquals(1, notificationPrompts)
        }

    @Test fun meteredDecisionRequiresAnotherExplicitConfirmation() =
        runTest {
            val downloads =
                FakeDownloadController().apply {
                    requestResult =
                        RequestResult.NeedsMeteredDecision(
                            2,
                            1000,
                            1,
                        )
                }
            val controller =
                DownloadAllController(
                    backgroundScope,
                    { estimate },
                    downloads,
                    { notificationPrompts++ },
                )
            controller.open(FeedSource.Podcast(3))
            runCurrent()
            controller.confirmMetered()
            assertTrue(downloads.calls.isEmpty())
            controller.confirm()
            runCurrent()
            assertNotNull(controller.state.value?.metered)
            controller.dismiss()
            assertEquals(
                1,
                downloads.calls.size,
            )
            controller.open(FeedSource.Podcast(3))
            runCurrent()
            controller.confirm()
            runCurrent()
            downloads.requestResult =
                RequestResult.Queued(
                    2,
                    0,
                    emptyMap(),
                    false,
                )
            controller.confirmMetered()
            runCurrent()
            assertEquals(
                true,
                downloads.calls.last().allowMetered,
            )
            assertEquals(
                DownloadLane.MANUAL,
                downloads.calls.last().lane,
            )
        }

    @Test fun failuresStayActionableAndPartialRejectionIsReported() =
        runTest {
            val downloads = FakeDownloadController().apply { failure = IllegalStateException("disk") }
            val controller =
                DownloadAllController(
                    backgroundScope,
                    { estimate },
                    downloads,
                    { notificationPrompts++ },
                )
            controller.open(FeedSource.Group(7))
            runCurrent()
            controller.confirm()
            runCurrent()
            assertNotNull(controller.state.value?.error)
            downloads.failure = null
            downloads.requestResult =
                RequestResult.Queued(
                    1,
                    0,
                    mapOf(2L to RejectReason.UNAVAILABLE),
                    false,
                )
            controller.confirm()
            runCurrent()
            assertNull(controller.state.value)
            assertTrue(
                controller.messages.flow.value
                    .isNotEmpty(),
            )
        }

    @Test fun emptyEstimateCannotQueue() =
        runTest {
            val downloads = FakeDownloadController()
            val controller =
                DownloadAllController(
                    backgroundScope,
                    { estimate.copy(episodeIds = emptyList()) },
                    downloads,
                    { notificationPrompts++ },
                )
            controller.open(FeedSource.Group(7))
            runCurrent()
            controller.confirm()
            runCurrent()
            assertTrue(downloads.calls.isEmpty())
        }
}
