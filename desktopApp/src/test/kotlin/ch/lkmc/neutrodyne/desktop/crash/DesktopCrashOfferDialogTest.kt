// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.crash

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.testing.FakeCrashReporter
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.RevealInFolder
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.Locale

/**
 * The next-start crash offer (11 Crash files and the email dialog): each button goes through
 * the injected OS seams and marks the file exactly once — "Send by email" opens the bounded
 * `mailto:` and marks `.sent`, "Show report" reveals the file and leaves it pending, "Don't
 * send" marks `.dismissed`, and the next unsent file follows only after the current one is
 * handled.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopCrashOfferDialogTest {
    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        // The shared test-task config sets the JVM to de_DE; these assertions name English labels.
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `send by email opens the mailto seam and marks the file sent`() =
        runComposeUiTest {
            val dirs = crashDirs("nd-crash-offer-send")
            val crash = dirs.writeCrashFile("crash-20261106-120000.txt", "report body")
            val opened = mutableListOf<String>()
            val offer =
                DesktopCrashOffer(
                    dirs = dirs,
                    buildInfo = crashDialogBuildInfo(),
                    clock = DesktopClock,
                    crashReporter = FakeCrashReporter(true),
                )
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    DesktopCrashOfferDialog(
                        offer = offer,
                        urls =
                            ExternalUrlOpener {
                                opened += it
                                OpenResult.OPENED
                            },
                        reveal = null,
                    )
                }
            }
            waitUntil("the crash dialog shows", TIMEOUT_MS) { sendButton().isNotEmpty() }
            onNodeWithText("Send by email").performClick()
            waitUntil("the mailto seam is called", TIMEOUT_MS) { opened.isNotEmpty() }
            assertThat(opened.single()).startsWith("mailto:neutrodyne@example.com")
            assertThat(Files.exists(dirs.state.resolve("crash-20261106-120000.sent"))).isTrue()
            assertThat(Files.exists(crash)).isFalse()
            waitUntil("the handled crash closes the dialog", TIMEOUT_MS) { sendButton().isEmpty() }
        }

    @Test
    fun `show report reveals the file without marking it`() =
        runComposeUiTest {
            val dirs = crashDirs("nd-crash-offer-show")
            val crash = dirs.writeCrashFile("crash-20261106-120000.txt", "report body")
            val revealed = mutableListOf<String>()
            val offer =
                DesktopCrashOffer(
                    dirs = dirs,
                    buildInfo = crashDialogBuildInfo(),
                    clock = DesktopClock,
                    crashReporter = FakeCrashReporter(true),
                )
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    DesktopCrashOfferDialog(
                        offer = offer,
                        urls = ExternalUrlOpener { OpenResult.OPENED },
                        reveal =
                            RevealInFolder {
                                revealed += it
                                true
                            },
                    )
                }
            }
            waitUntil("the crash dialog shows", TIMEOUT_MS) { sendButton().isNotEmpty() }
            onNodeWithText("Show report").performClick()
            waitUntil("the reveal seam is called", TIMEOUT_MS) { revealed.isNotEmpty() }
            assertThat(revealed.single()).isEqualTo(crash.toString())
            // The file stays pending: the dialog still offers every action and nothing is renamed.
            assertThat(Files.exists(crash)).isTrue()
            onNodeWithText("Send by email").assertExists()
            onNodeWithText("Don't send").assertExists()
        }

    @Test
    fun `dont send marks the file dismissed`() =
        runComposeUiTest {
            val dirs = crashDirs("nd-crash-offer-dismiss")
            dirs.writeCrashFile("crash-20261106-120000.txt", "report body")
            val offer =
                DesktopCrashOffer(
                    dirs = dirs,
                    buildInfo = crashDialogBuildInfo(),
                    clock = DesktopClock,
                    crashReporter = FakeCrashReporter(true),
                )
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    DesktopCrashOfferDialog(
                        offer = offer,
                        urls = ExternalUrlOpener { OpenResult.OPENED },
                        reveal = null,
                    )
                }
            }
            waitUntil("the crash dialog shows", TIMEOUT_MS) { sendButton().isNotEmpty() }
            onNodeWithText("Don't send").performClick()
            waitUntil("the file is marked dismissed", TIMEOUT_MS) {
                Files.exists(dirs.state.resolve("crash-20261106-120000.dismissed"))
            }
            waitUntil("the handled crash closes the dialog", TIMEOUT_MS) { sendButton().isEmpty() }
        }

    @Test
    fun `the next unsent file follows only after the current one is handled`() =
        runComposeUiTest {
            val dirs = crashDirs("nd-crash-offer-order")
            val newest = dirs.writeCrashFile("crash-20261107-120000.txt", "newest")
            val older = dirs.writeCrashFile("crash-20261106-120000.txt", "older")
            Files.setLastModifiedTime(newest, FileTime.fromMillis(DesktopClock.now()))
            Files.setLastModifiedTime(older, FileTime.fromMillis(DesktopClock.now() - 60_000))
            val opened = mutableListOf<String>()
            val offer =
                DesktopCrashOffer(
                    dirs = dirs,
                    buildInfo = crashDialogBuildInfo(),
                    clock = DesktopClock,
                    crashReporter = FakeCrashReporter(true),
                )
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    DesktopCrashOfferDialog(
                        offer = offer,
                        urls =
                            ExternalUrlOpener {
                                opened += it
                                OpenResult.OPENED
                            },
                        reveal = null,
                    )
                }
            }
            waitUntil("the crash dialog shows", TIMEOUT_MS) { sendButton().isNotEmpty() }
            // Newest first: "Don't send" dismisses the newer file and the older one takes over.
            onNodeWithText("Don't send").performClick()
            waitUntil("the newer file is dismissed", TIMEOUT_MS) {
                Files.exists(dirs.state.resolve("crash-20261107-120000.dismissed"))
            }
            assertThat(Files.exists(older)).isTrue()
            waitUntil("the dialog stays for the next file", TIMEOUT_MS) { sendButton().isNotEmpty() }
            onNodeWithText("Send by email").performClick()
            waitUntil("the mailto seam is called", TIMEOUT_MS) { opened.isNotEmpty() }
            assertThat(Files.exists(dirs.state.resolve("crash-20261106-120000.sent"))).isTrue()
            waitUntil("all crashes handled closes the dialog", TIMEOUT_MS) { sendButton().isEmpty() }
        }

    @Test
    fun `an unavailable reporter shows nothing`() =
        runComposeUiTest {
            val dirs = crashDirs("nd-crash-offer-none")
            dirs.writeCrashFile("crash-20261106-120000.txt", "report body")
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    DesktopCrashOfferDialog(
                        offer =
                            DesktopCrashOffer(
                                dirs = dirs,
                                buildInfo = crashDialogBuildInfo(),
                                clock = DesktopClock,
                                crashReporter = FakeCrashReporter(false),
                            ),
                        urls = ExternalUrlOpener { OpenResult.OPENED },
                        reveal = null,
                    )
                }
            }
            waitForIdle()
            // `pendingCrashes` is empty for an unavailable reporter, so the dialog can never appear.
            onNodeWithText("Send by email").assertDoesNotExist()
        }

    @Test
    fun `a DEV build shows nothing`() =
        runComposeUiTest {
            val dirs = crashDirs("nd-crash-offer-dev")
            dirs.writeCrashFile("crash-20261106-120000.txt", "report body")
            val info = crashDialogBuildInfo(installKind = InstallKind.DEV)
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    DesktopCrashOfferDialog(
                        offer =
                            DesktopCrashOffer(
                                dirs = dirs,
                                buildInfo = info,
                                clock = DesktopClock,
                                crashReporter = DesktopCrashReporter(dirs, info, DesktopClock, RecentLogBuffer()),
                            ),
                        urls = ExternalUrlOpener { OpenResult.OPENED },
                        reveal = null,
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Send by email").assertDoesNotExist()
        }

    @Test
    fun `an empty mailbox shows nothing`() =
        runComposeUiTest {
            val dirs = crashDirs("nd-crash-offer-nomail")
            dirs.writeCrashFile("crash-20261106-120000.txt", "report body")
            val info = crashDialogBuildInfo(mailto = "")
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    DesktopCrashOfferDialog(
                        offer =
                            DesktopCrashOffer(
                                dirs = dirs,
                                buildInfo = info,
                                clock = DesktopClock,
                                crashReporter = DesktopCrashReporter(dirs, info, DesktopClock, RecentLogBuffer()),
                            ),
                        urls = ExternalUrlOpener { OpenResult.OPENED },
                        reveal = null,
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Send by email").assertDoesNotExist()
        }

    private fun ComposeUiTest.sendButton() = onAllNodesWithText("Send by email").fetchSemanticsNodes()

    private fun crashDirs(prefix: String) = tempAppDirs(prefix).also { Files.createDirectories(it.state) }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
