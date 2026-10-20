// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntOffset
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real UI targets and apply/layout callbacks; none of these assertions substitutes a model ledger. */
@OptIn(ExperimentalTestApi::class)
class PaneFocusTransactionTest {
    @Test fun outsideBetweenCaptureAndInstall() = verify(Scenario.Outside)

    @Test fun clearBetweenCaptureAndInstall() = verify(Scenario.Clear)

    @Test fun alreadyEmptyClearBetweenCaptureAndInstall() = verify(Scenario.EmptyClear)

    @Test fun acquireThenClearBeforeInstall() = verify(Scenario.AcquireClear)

    @Test fun acquireThenClearAfterInstallBeforeGlobal() = verify(Scenario.AfterInstallClear)

    @Test fun retargetBeforeLossAndInstall() = verify(Scenario.Retarget)

    @Test fun roundTripRetargetBeforeLossAndInstall() = verify(Scenario.RoundTrip)

    @Test fun roundTripRetargetWithNoCoordinates() = verify(Scenario.UnplacedRoundTrip)

    @Test fun populatedFieldRoundTripWithNoCoordinates() = verify(Scenario.UnplacedFieldRoundTrip)

    @Test fun noProposalSupersedesEvenAnUninstalledCapture() = verify(Scenario.NoProposal)

    @Test fun newerInstallSupersedesAnUninstalledCapture() = verify(Scenario.Superseded)

    @Test fun consumedInstallationDoesNotRearmOnReattach() = verify(Scenario.Reattach)

    @Test fun abandonedCaptureCannotArm() = verify(Scenario.Abandoned)

    @Test fun noLossClosesBeforeAnUnrelatedLoss() = verify(Scenario.NoLoss)

    @Test fun tabLeaveReturnRevokesBeforeInstall() = verify(Scenario.TabRoundTrip)

    @Test fun stackLeaveReturnRevokesBeforeInstall() = verify(Scenario.StackRoundTrip)

    @Test fun openSameRouteStillPublishesDeparture() = verify(Scenario.OpenSameRoute)

    @Test fun replacingEqualDetailPublishesDeparture() = verify(Scenario.ReplaceSameRoute)

    @Test fun resetAndReturnPublishesDeparture() = verify(Scenario.ResetRoundTrip)

    @Test fun reselectAndReturnPublishesDeparture() = verify(Scenario.ReselectRoundTrip)

    @Test fun continuouslyAuthorizedListSurvivesOtherPaneMutation() = verify(Scenario.ContinuousList)

    @Test fun retainedOutgoingEntryCannotReclaim() = verify(Scenario.RetainedOutgoing)

    @Test fun changedEntryIdentityRejectsOldCapture() = verify(Scenario.WrongIdentity)

    @Test fun absentCaptureCannotBeFilledInLate() = verify(Scenario.MissingCapture)

    @Test fun firstUnreadyGlobalClosesWithoutRetry() = verify(Scenario.Unready)

    @Test fun reentrantRedirectIsTerminal() = verify(Scenario.Redirect)

    private fun verify(scenario: Scenario) =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = Fixture(scenario)
            setContent { fixture.Content() }
            if (scenario != Scenario.MissingCapture) {
                runOnIdle { assertTrue(fixture.a.requestFocus()) }
                onNodeWithTag("a").assertIsFocused()
            }
            var captured: PaneFocusInstallation? = null
            runOnIdle {
                captured = fixture.owner.capture()
                if (scenario != Scenario.MissingCapture) assertNotNull(captured?.proposal)
                fixture.ticket = captured
                fixture.moved = scenario !in listOf(Scenario.NoLoss, Scenario.Superseded, Scenario.RetainedOutgoing)
                fixture.before = {
                    when (scenario) {
                        Scenario.Outside -> {
                            fixture.acquireOutside()
                        }

                        Scenario.Clear -> {
                            fixture.manager.clearFocus()
                        }

                        Scenario.EmptyClear -> {
                            // Simulate structural clearing through the real manager captured outside the facade.
                            fixture.parentManager.clearFocus()
                            fixture.manager.clearFocus()
                        }

                        Scenario.AcquireClear -> {
                            fixture.acquireOutside()
                            fixture.manager.clearFocus()
                        }

                        Scenario.AfterInstallClear -> {
                            fixture.afterAttach = {
                                assertTrue(captured?.installed == true)
                                fixture.acquireOutside()
                                fixture.manager.clearFocus()
                            }
                        }

                        Scenario.Retarget -> {
                            fixture.retarget(roundTrip = false)
                        }

                        Scenario.RoundTrip, Scenario.UnplacedRoundTrip, Scenario.UnplacedFieldRoundTrip -> {
                            fixture.retarget(roundTrip = true)
                        }

                        Scenario.TabRoundTrip -> {
                            fixture.navigation.selectTab(LibraryKey)
                            fixture.navigation.selectTab(FeedsKey)
                        }

                        Scenario.StackRoundTrip -> {
                            fixture.navigation.pop()
                            fixture.navigation.push(PodcastKey(1))
                        }

                        Scenario.OpenSameRoute -> {
                            fixture.navigation.open(FeedsKey, listOf(PodcastKey(1)))
                        }

                        Scenario.ReplaceSameRoute -> {
                            fixture.navigation.panePartitions = 2
                            fixture.navigation.pushDetail(PodcastKey(1))
                        }

                        Scenario.ResetRoundTrip -> {
                            fixture.navigation.resetTab(FeedsKey)
                            fixture.navigation.push(PodcastKey(1))
                        }

                        Scenario.ReselectRoundTrip -> {
                            fixture.navigation.selectTab(FeedsKey)
                            fixture.navigation.push(PodcastKey(1))
                        }

                        Scenario.ContinuousList -> {
                            fixture.navigation.push(PodcastKey(2))
                        }

                        Scenario.RetainedOutgoing -> {
                            fixture.navigation.selectTab(LibraryKey)
                            fixture.parentManager.clearFocus()
                        }

                        Scenario.MissingCapture -> {
                            assertTrue(fixture.a.requestFocus())
                        }

                        Scenario.Unready -> {
                            fixture.parentManager.clearFocus()
                            fixture.placeEntry = false
                        }

                        else -> {
                            Unit
                        }
                    }
                }
                when (scenario) {
                    Scenario.Abandoned, Scenario.NoProposal -> {
                        fixture.parentManager.clearFocus()
                        fixture.ticket = fixture.owner.capture()
                        assertTrue(fixture.ticket?.proposal == null)
                    }

                    Scenario.Superseded -> {
                        fixture.ticket = fixture.owner.capture()
                    }

                    Scenario.WrongIdentity -> {
                        fixture.identity = "replacement"
                    }

                    Scenario.Redirect -> {
                        fixture.redirect = true
                    }

                    else -> {
                        Unit
                    }
                }
                fixture.serial++
            }
            waitForIdle()
            assertTrue(fixture.globals > 1, "A real host global-position boundary must execute")
            if (scenario in listOf(Scenario.Outside, Scenario.Redirect)) {
                onNodeWithTag("outside").assertIsFocused()
                runOnIdle { assertTrue(fixture.outsideAcquired) }
            } else if (
                scenario in
                listOf(
                    Scenario.ContinuousList,
                    Scenario.NoLoss,
                    Scenario.Reattach,
                    Scenario.Superseded,
                )
            ) {
                onNodeWithTag("a").assertIsFocused()
            } else {
                if (scenario == Scenario.Unready) {
                    runOnIdle { assertTrue(!fixture.owner.hasFocus) }
                } else {
                    onNodeWithTag("a").assertIsNotFocused()
                }
                onNodeWithTag("outside").assertIsNotFocused()
            }
            if (scenario != Scenario.Unready) onNodeWithTag("first").assertIsNotFocused()
            if (scenario in
                listOf(
                    Scenario.Retarget,
                    Scenario.RoundTrip,
                    Scenario.UnplacedRoundTrip,
                    Scenario.UnplacedFieldRoundTrip,
                )
            ) {
                runOnIdle {
                    assertTrue(fixture.bAcquired && fixture.bGains > 0, "The actual leaf B must acquire focus")
                    assertTrue(fixture.retargetInvalidated, "A real retarget must invalidate the old proposal")
                    assertTrue(fixture.retargetRejectedAtInstall, "Reject before the global boundary can mask it")
                    if (scenario in listOf(Scenario.UnplacedRoundTrip, Scenario.UnplacedFieldRoundTrip)) {
                        assertEquals(0, fixture.bGlobals, "B must never have supplied coordinates")
                    }
                }
            }
            runOnIdle {
                if (scenario == Scenario.Abandoned) {
                    assertTrue(captured?.installed == false)
                } else if (scenario !in listOf(Scenario.Superseded, Scenario.NoProposal)) {
                    assertTrue(captured?.closed == true)
                }
            }
            // Real later dispatch and same-key reattachment must not revive a consumed/stale permission.
            if (scenario in
                listOf(
                    Scenario.NoProposal,
                    Scenario.Superseded,
                    Scenario.Reattach,
                    Scenario.NoLoss,
                    Scenario.Unready,
                )
            ) {
                runOnIdle {
                    fixture.parentManager.clearFocus()
                    fixture.before = null
                    fixture.ticket = captured
                    if (scenario == Scenario.Reattach) fixture.hostKey++
                    if (scenario in listOf(Scenario.NoProposal, Scenario.Superseded)) {
                        fixture.serial++
                        fixture.moved = !fixture.moved
                    }
                    if (scenario == Scenario.Unready) fixture.placeEntry = true
                    fixture.shift = 7
                }
                waitForIdle()
                onNodeWithTag("a").assertIsNotFocused()
                onNodeWithTag("outside").assertIsNotFocused()
                onNodeWithTag("first").assertIsNotFocused()
            }
            if (scenario == Scenario.Redirect) {
                val gains = fixture.aGains
                runOnIdle { fixture.shift = 9 }
                waitForIdle()
                onNodeWithTag("outside").assertIsFocused()
                onNodeWithTag("a").assertIsNotFocused()
                runOnIdle { assertEquals(gains, fixture.aGains) }
            }
        }
}

private enum class Scenario {
    Outside,
    Clear,
    EmptyClear,
    AcquireClear,
    AfterInstallClear,
    Retarget,
    RoundTrip,
    UnplacedRoundTrip,
    UnplacedFieldRoundTrip,
    NoProposal,
    Superseded,
    Reattach,
    Abandoned,
    NoLoss,
    TabRoundTrip,
    StackRoundTrip,
    OpenSameRoute,
    ReplaceSameRoute,
    ResetRoundTrip,
    ReselectRoundTrip,
    ContinuousList,
    RetainedOutgoing,
    WrongIdentity,
    MissingCapture,
    Unready,
    Redirect,
}

private class Fixture(
    private val scenario: Scenario,
) {
    val owner = PaneFocusOwner()
    val navigation = NavigationState.create()
    private val route =
        when (scenario) {
            Scenario.StackRoundTrip, Scenario.OpenSameRoute, Scenario.ReplaceSameRoute,
            Scenario.ResetRoundTrip, Scenario.ReselectRoundTrip,
            -> PodcastKey(1)

            else -> FeedsKey
        }

    init {
        if (route != FeedsKey) navigation.push(route)
    }

    val a = FocusRequester()
    private val b = FocusRequester()
    private val outside = FocusRequester()
    lateinit var manager: FocusManager
    lateinit var parentManager: FocusManager
    var ticket by mutableStateOf<PaneFocusInstallation?>(null)
    var serial by mutableIntStateOf(0)
    var hostKey by mutableIntStateOf(0)
    var moved by mutableStateOf(false)
    var identity by mutableStateOf<Any>(route)
    var placeEntry by mutableStateOf(true)
    var shift by mutableIntStateOf(0)
    var before: (() -> Unit)? = null
    var afterAttach: (() -> Unit)? = null
    var outsideAcquired = false
    var bAcquired = false
    var retargetInvalidated = false
    var retargetRejectedAtInstall = false
    var bGains = 0
    var aGains = 0
    var bGlobals = 0
    var globals = 0
    var redirect = false

    fun acquireOutside() {
        outsideAcquired = outside.requestFocus()
    }

    fun retarget(roundTrip: Boolean) {
        val prior = ticket?.proposal
        bAcquired = b.requestFocus()
        if (roundTrip) {
            assertTrue(a.requestFocus())
        }
        val after = owner.capture().proposal
        retargetInvalidated = after == null || after.targetRevision != prior?.targetRevision
        afterAttach = { retargetRejectedAtInstall = ticket?.closed == true }
    }

    @Composable
    fun Content(modifier: Modifier = Modifier) {
        parentManager = LocalFocusManager.current
        val entry =
            remember {
                movableContentOf {
                    PaneFocusContent(owner, navigation, identity, navigation, {
                        navigation.selectedTab == FeedsKey &&
                            (scenario == Scenario.ContinuousList || navigation.stack(FeedsKey).last() == route)
                    }, { it.tab == FeedsKey && it.key == route }) {
                        Column(
                            Modifier.then(
                                AttachedElement {
                                    val action = afterAttach
                                    afterAttach = null
                                    action?.invoke()
                                },
                            ),
                        ) {
                            Text("First", Modifier.testTag("first").focusable())
                            Leaf("a", a, true)
                            Leaf(
                                "b",
                                b,
                                scenario !in listOf(Scenario.UnplacedRoundTrip, Scenario.UnplacedFieldRoundTrip),
                            )
                        }
                    }
                }
            }
        NavigationFocusRoot(modifier.fillMaxSize(), owner) {
            manager = LocalFocusManager.current
            Column {
                Text("Outside", Modifier.testTag("outside").focusRequester(outside).focusable())
                key(hostKey) {
                    PaneFocusTransactionHost(
                        owner,
                        Modifier
                            .then(
                                BeforeBindingElement(serial) {
                                    val action = before
                                    before = null
                                    action?.invoke()
                                },
                            ).offset { IntOffset(shift, 0) }
                            .onGloballyPositioned { globals++ },
                        ticket ?: owner.capture(),
                    ) {
                        val placement =
                            Modifier.layout { measurable, constraints ->
                                val child = measurable.measure(constraints)
                                layout(child.width, child.height) { if (placeEntry) child.place(0, 0) }
                            }
                        if (moved) {
                            key("moved") { Row(placement) { entry() } }
                        } else {
                            key("original") { Column(placement) { entry() } }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Leaf(
        tag: String,
        requester: FocusRequester,
        placed: Boolean,
        modifier: Modifier = Modifier,
    ) {
        val leafModifier =
            modifier
                .testTag(tag)
                .focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused) {
                        if (tag == "a") {
                            aGains++
                            if (redirect) {
                                redirect = false
                                acquireOutside()
                            }
                        } else {
                            bGains++
                        }
                    }
                }.onGloballyPositioned { if (tag == "b") bGlobals++ }
                .layout { measurable, constraints ->
                    val child = measurable.measure(constraints)
                    layout(child.width, child.height) { if (placed) child.place(0, 0) }
                }
        if (scenario == Scenario.UnplacedFieldRoundTrip) {
            var value by remember { mutableStateOf("populated $tag") }
            TextField(value, { value = it }, leafModifier)
        } else {
            Text(tag, leafModifier.focusable())
        }
    }
}

private data class BeforeBindingElement(
    val serial: Int,
    val action: () -> Unit,
) : ModifierNodeElement<BeforeBindingNode>() {
    override fun create() = BeforeBindingNode(serial)

    override fun update(node: BeforeBindingNode) {
        if (node.serial == serial) return
        node.serial = serial
        action()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "beforeBinding"
    }
}

private class BeforeBindingNode(
    var serial: Int,
) : Modifier.Node()

private data class AttachedElement(
    val action: () -> Unit,
) : ModifierNodeElement<AttachedNode>() {
    override fun create() = AttachedNode(action)

    override fun update(node: AttachedNode) {
        node.action = action
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "attachAction"
    }
}

private class AttachedNode(
    var action: () -> Unit,
) : Modifier.Node() {
    override fun onAttach() {
        action()
    }
}
