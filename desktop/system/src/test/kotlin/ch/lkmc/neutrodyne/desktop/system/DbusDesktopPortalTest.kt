// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.DirectoryChoice
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * `DbusDesktopPortal` against fake services on the private session bus of
 * `dbus-run-session` (CI wraps the Linux Gradle steps): the portal FileChooser's
 * directory round trip through the `Response` signal on the predictable request path
 * (chosen, dismissed, no portal), the `handle_token`/`directory` options we send, the
 * `Request.Close` a cancellation owes the portal, and `FileManager1.ShowItems` with the
 * file URI. The fake honours the spec's `handle_token` path rule — a portal that does
 * not would defeat the race-free match and reads as `Unavailable`.
 */
class DbusDesktopPortalTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun aChosenFolderArrivesThroughTheResponseSignal() {
        val address = busAddress()
        runBlocking {
            FakePortal(address).use { portal ->
                DbusDesktopPortal(address).use { underTest ->
                    val pending = async(Dispatchers.IO) { underTest.chooseDirectory("Choose a folder", null) }

                    await("OpenFile call") { portal.openCalls.isNotEmpty() }
                    val call = portal.openCalls.single()
                    assertThat(call.title).isEqualTo("Choose a folder")
                    assertThat(call.options["directory"]?.value).isEqualTo(true)
                    assertThat(call.options["handle_token"]?.value as? String).isNotEmpty()

                    portal.respond(call.handleToken, RESPONSE_OK, listOf(folderUri))
                    val outcome = withTimeout(AWAIT_MS) { pending.await() }

                    assertThat(outcome).isEqualTo(DirectoryChoice.Chosen(folderPath))
                }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aCompletedPickRemovesTheResponseMatch() {
        val address = busAddress()
        runBlocking {
            // The portal's own connection is held by the test, so the match rules it
            // installs are inspectable through AbstractConnectionBase.handledSignals
            // (dbus-java keeps no public match count).
            val bus = DBusConnectionBuilder.forAddress(address).build()
            try {
                FakePortal(address).use { portal ->
                    DbusDesktopPortal({ bus }).use { underTest ->
                        val pending = async(Dispatchers.IO) { underTest.chooseDirectory(TITLE, null) }
                        await("OpenFile call") { portal.openCalls.size == 1 }
                        assertThat(bus.responseMatches()).isEqualTo(1)

                        portal.respond(portal.openCalls.single().handleToken, RESPONSE_OK, listOf(folderUri))
                        assertThat(withTimeout(AWAIT_MS) { pending.await() })
                            .isEqualTo(DirectoryChoice.Chosen(folderPath))

                        // A pick that answered is finished: its match must not linger.
                        assertThat(bus.responseMatches()).isEqualTo(0)
                    }
                }
            } finally {
                runCatching { bus.close() }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aDismissedDialogIsCancelledNotAFallback() {
        val address = busAddress()
        runBlocking {
            FakePortal(address).use { portal ->
                DbusDesktopPortal(address).use { underTest ->
                    val pending = async(Dispatchers.IO) { underTest.chooseDirectory(TITLE, null) }
                    await("OpenFile call") { portal.openCalls.size == 1 }

                    portal.respond(portal.openCalls.single().handleToken, RESPONSE_CANCELLED, emptyList())

                    assertThat(withTimeout(AWAIT_MS) { pending.await() })
                        .isEqualTo(DirectoryChoice.Cancelled)
                }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aWrongHandleAnswerClosesTheResponseMatch() {
        val address = busAddress()
        runBlocking {
            val bus = DBusConnectionBuilder.forAddress(address).build()
            try {
                FakePortal(address).use { portal ->
                    portal.misdirect = true
                    DbusDesktopPortal({ bus }).use { underTest ->
                        assertThat(withTimeout(AWAIT_MS) { underTest.chooseDirectory(TITLE, null) })
                            .isEqualTo(DirectoryChoice.Unavailable)

                        // The error path is an outcome too: its match must not linger.
                        assertThat(bus.responseMatches()).isEqualTo(0)
                    }
                }
            } finally {
                runCatching { bus.close() }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aResponseSignalledWhileTheHandleIsWrongResumesExactlyOnce() {
        val address = busAddress()
        runBlocking {
            // The fake emits the Response for the predictable path *and* answers the
            // call with a path the match rule does not cover, so the signal thread and
            // the error path both reach the outcome gate.
            val bus = DBusConnectionBuilder.forAddress(address).build()
            try {
                FakePortal(address).use { portal ->
                    portal.misdirect = true
                    portal.misdirectUris = listOf(folderUri)
                    DbusDesktopPortal({ bus }).use { underTest ->
                        val outcome = withTimeout(AWAIT_MS) { underTest.chooseDirectory(TITLE, null) }

                        // Exactly one of the two outcomes won the gate; the match is gone.
                        assertThat(outcome).isAnyOf(DirectoryChoice.Chosen(folderPath), DirectoryChoice.Unavailable)
                        assertThat(bus.responseMatches()).isEqualTo(0)
                    }
                }
            } finally {
                runCatching { bus.close() }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aBusWithoutThePortalAnswersUnavailable() {
        val address = busAddress()
        runBlocking {
            // No fake portal exported: the service does not exist on this bus.
            DbusDesktopPortal(address).use { underTest ->
                assertThat(withTimeout(AWAIT_MS) { underTest.chooseDirectory(TITLE, null) })
                    .isEqualTo(DirectoryChoice.Unavailable)
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancellingTheChooserClosesThePortalRequest() {
        val address = busAddress()
        runBlocking {
            FakePortal(address).use { portal ->
                DbusDesktopPortal(address).use { underTest ->
                    val pending = async(Dispatchers.IO) { underTest.chooseDirectory(TITLE, null) }
                    await("OpenFile call") { portal.openCalls.size == 1 }

                    pending.cancelAndJoin()

                    await("Request.Close") {
                        portal.closedTokens.contains(portal.openCalls.single().handleToken)
                    }
                }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun showItemsSendsTheFileUriAndReportsDelivery() {
        val address = busAddress()
        runBlocking {
            FakeFileManager(address).use { files ->
                DbusDesktopPortal(address).use { underTest ->
                    val file = Path.of(files.root.toString(), "episode file.mp3").toString()

                    assertThat(underTest.showItems(file)).isTrue()

                    await("ShowItems call") { files.calls.isNotEmpty() }
                    assertThat(files.calls.single())
                        .isEqualTo(Path.of(file).toUri().toASCIIString())
                }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun showItemsWithoutAFileManagerAnswersFalse() {
        val address = busAddress()
        runBlocking {
            DbusDesktopPortal(address).use { underTest ->
                assertThat(underTest.showItems("/tmp/whatever.mp3")).isFalse()
            }
        }
    }

    private val folderPath = Path.of(System.getProperty("java.io.tmpdir")).toString()
    private val folderUri = Path.of(folderPath).toUri().toASCIIString()

    /** The `member='Response'` match rules still installed on this connection. */
    private fun DBusConnection.responseMatches(): Int {
        val field =
            org.freedesktop.dbus.connections.base.AbstractConnectionBase::class.java
                .getDeclaredField("handledSignals")
        field.isAccessible = true
        val rules = (field.get(this) as Map<*, *>).keys
        return rules.count { "member='Response'" in it.toString() }
    }

    private fun busAddress(): String {
        val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")
        assumeTrue(
            "no session bus: run under dbus-run-session (CI wraps the Linux Gradle steps)",
            address?.startsWith("unix:") == true,
        )
        return address
    }

    private fun await(
        what: String,
        until: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + AWAIT_MS * 1_000_000
        while (!until() && System.nanoTime() < deadline) Thread.sleep(AWAIT_POLL_MS)
        assertThat(until()).isTrue()
    }

    private class OpenCall(
        val title: String,
        val options: Map<String, Variant<*>>,
        val handleToken: String,
    )

    /**
     * The fake portal honours the `handle_token` rule: each call returns the predictable
     * request path `/org/freedesktop/portal/desktop/request/{caller}/{token}` and answers
     * `Response` from exactly that path when [respond] runs.
     */
    private class FakePortal(
        address: String,
    ) : AutoCloseable {
        private val bus: DBusConnection = DBusConnectionBuilder.forAddress(address).build()
        private val requests = HashMap<String, String>()
        val openCalls = ConcurrentLinkedQueue<OpenCall>()
        val closedTokens = ConcurrentLinkedQueue<String>()

        /** When set, [OpenFile] answers with a path the match rule does not cover. */
        var misdirect: Boolean = false

        /** When non-null, the wrong handle is answered with a Response for these uris. */
        var misdirectUris: List<String>? = null

        private val fileChooser =
            object : PortalFileChooser {
                override fun getObjectPath() = PortalNames.PATH

                override fun OpenFile(
                    parentWindow: String,
                    title: String,
                    options: Map<String, Variant<*>>,
                ): DBusPath {
                    val token = options["handle_token"]?.value as? String ?: "anonymous"
                    val sender = bus.uniqueName.removePrefix(":").replace('.', '_')
                    val path = "${PortalNames.PATH}/request/$sender/$token"
                    requests[token] = path
                    bus.exportObject(path, FakeRequest(token, closedTokens))
                    openCalls += OpenCall(title, options, token)
                    if (misdirect) {
                        misdirectUris?.let { uris ->
                            bus.sendMessage(
                                PortalRequest.Response(
                                    path,
                                    UInt32(RESPONSE_OK),
                                    mapOf("uris" to Variant(uris, "as")),
                                ),
                            )
                        }
                        return DBusPath("${PortalNames.PATH}/request/wrong/path")
                    }
                    return DBusPath(path)
                }
            }

        init {
            bus.exportObject(PortalNames.PATH, fileChooser)
            bus.requestBusName(PortalNames.BUS)
        }

        fun respond(
            token: String,
            code: Long,
            uris: List<String>,
        ) {
            val path = requests.getValue(token)
            bus.sendMessage(
                PortalRequest.Response(
                    path,
                    UInt32(code),
                    // Collections inside a Variant need the explicit signature.
                    mapOf("uris" to Variant(uris, "as")),
                ),
            )
        }

        override fun close() {
            // Synchronous teardown: dbus-java shares one connection per address, and a
            // lingering export or name breaks the next test's setup. The client shares the
            // same connection, so the fake's unique name IS the caller's — exactly what the
            // predictable request path is derived from.
            for (path in requests.values) runCatching { bus.unExportObject(path) }
            requests.clear()
            runCatching { bus.unExportObject(PortalNames.PATH) }
            runCatching { bus.releaseBusName(PortalNames.BUS) }
            bus.close()
        }
    }

    private class FakeRequest(
        private val token: String,
        private val closedTokens: ConcurrentLinkedQueue<String>,
    ) : PortalRequest {
        override fun getObjectPath() = "/ignored"

        override fun Close() {
            closedTokens += token
        }
    }

    private class FakeFileManager(
        address: String,
    ) : AutoCloseable {
        private val bus: DBusConnection = DBusConnectionBuilder.forAddress(address).build()
        val calls = ConcurrentLinkedQueue<String>()
        val root: Path =
            java.nio.file.Files
                .createTempDirectory("nd-reveal")

        private val manager =
            object : FileManager1 {
                override fun getObjectPath() = FileManagerNames.PATH

                override fun ShowItems(
                    uris: List<String>,
                    startupId: String,
                ) {
                    calls += uris
                }
            }

        init {
            bus.exportObject(FileManagerNames.PATH, manager)
            bus.requestBusName(FileManagerNames.BUS)
        }

        override fun close() {
            runCatching { root.toFile().deleteRecursively() }
            runCatching { bus.unExportObject(FileManagerNames.PATH) }
            runCatching { bus.releaseBusName(FileManagerNames.BUS) }
            bus.close()
        }
    }

    private companion object {
        const val TITLE = "Choose a folder"
        const val RESPONSE_OK = 0L
        const val RESPONSE_CANCELLED = 1L
        const val TEST_TIMEOUT_MS = 60_000L
        const val AWAIT_MS = 5_000L
        const val AWAIT_POLL_MS = 10L
    }
}
