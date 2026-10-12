// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import android.os.Parcel
import androidx.activity.compose.setContent
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Acceptance gates for the expanded in-pane swap (08 Transitions): every assertion observes
 * public behavior only — `NavigationState` calls, rendered nodes and pixels, and the probe
 * pages' `viewModel()`/`rememberSaveable` triples. Nothing here names a production
 * implementation seam, so the same gates judge any mechanism.
 *
 * Gates marked MOTION GATE in their KDoc (G1, G4, G5's preview, and the RTL mirror in G10)
 * require real transition frames and fail against an instant-swap host; state and history
 * gates must hold under every implementation, animated or not.
 *
 * On the machinery-free base (plain `NavDisplay` over the real stacks) the motion gates and
 * the two-pane system-back gate (G6) fail by design; every state/history gate passes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // gate suite: one method per assertion group
internal class PaneSwapGateTest : NdNavHostHarness() {
    // region G1 — MOTION GATE: the wipe moves the outgoing pane only

    /**
     * MOTION GATE. Settings Home → About → Licences → pop: while the wipe runs the list pane
     * never moves a pixel, Licences' pane content slides toward the layout end over several
     * frames, and About is revealed underneath it. An instant pop cannot produce transit
     * frames — that is the failure this gate exists to see on the bare base.
     */
    @Test
    fun g1PanePopWipesTheOutgoingTowardTheEnd() {
        setFixedHost(directive = TwoPaneDirective)
        pushSettingsChain()
        settle()
        val detailRest = leftEdgeOf(rule.onRoot().captureToImage().toPixelMap(), LICENCES_COLOR)
        assertTrue(detailRest > 0, "expected a two-pane layout")

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }

        var transitFrames = 0
        var revealed = false
        var listMoved = false
        repeat(WIPE_FRAME_BOUND) {
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            if (leftEdgeOf(pixels, SETTINGS_COLOR) != 0) listMoved = true
            val outgoing = leftEdgeOf(pixels, LICENCES_COLOR)
            if (outgoing > detailRest) transitFrames++
            if (outgoing > detailRest && leftEdgeOf(pixels, ABOUT_COLOR) == detailRest) {
                revealed = true
            }
        }
        assertTrue(!listMoved, "the list pane moved during an in-pane wipe")
        assertTrue(transitFrames >= 2, "the outgoing pane snapped out instead of wiping")
        assertTrue(revealed, "no frame showed About revealed under the wiping pane")

        settle()
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(-1, leftEdgeOf(pixels, LICENCES_COLOR), "popped detail still painted")
        assertEquals(detailRest, leftEdgeOf(pixels, ABOUT_COLOR))
        assertEquals(0, leftEdgeOf(pixels, SETTINGS_COLOR))
    }

    // endregion

    // region G2 — outgoing owner continuity through the wipe

    /**
     * While the wipe still draws the outgoing pane, its ViewModel, `ViewModelStoreOwner` and
     * `rememberSaveable` value must stay identical — no disappear/re-enter frame — and the
     * popped entry's ViewModel clears exactly once after it exits.
     */
    @Test
    fun g2OutgoingOwnerStaysContinuousThroughItsExit() {
        OwnerProbe.reset()
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        pushSettingsChain()
        settle()
        rule.onNodeWithTag("$LICENCES_TAG-inc").performClick()
        settle()
        val original = assertNotNull(OwnerProbe.snapshots[LICENCES_TAG], "licences never composed")
        assertEquals(1, original.saved)

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }

        // Conditional continuity: any frame that still draws the outgoing pane must draw the
        // same emission (G1 is the gate that proves the wipe itself exists).
        advanceFramesUntil(GHOST_WATCH_FRAME_BOUND) {
            val present =
                rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty()
            if (present) {
                val now = OwnerProbe.snapshots[LICENCES_TAG]
                assertEquals(original.vm, now?.vm, "outgoing ViewModel was recreated")
                assertEquals(original.owner, now?.owner, "outgoing ViewModelStoreOwner changed")
                assertEquals(1, now?.saved, "outgoing rememberSaveable state was reset")
                assertTrue(
                    original.vm !in OwnerProbe.cleared,
                    "outgoing ViewModel cleared while still drawn",
                )
                false
            } else {
                true
            }
        }

        settle()
        assertTrue(rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isEmpty())
        assertEquals(
            1,
            OwnerProbe.cleared.count { it == original.vm },
            "the popped ViewModel must clear exactly once",
        )
        assertEquals(SettingsKey(SettingsPage.ABOUT), navigation.stack(FeedsKey).last())
        assertTrue(rule.onAllNodesWithTag(SETTINGS_TAG).fetchSemanticsNodes().isNotEmpty())
    }

    // endregion

    // region G3 — input to the revealed incoming pane survives the swap

    /**
     * About's `rememberSaveable` reads 1; Licences covers it, then pops. While the wipe still
     * reveals About (or immediately, on an instant swap), two real touches on its revealed
     * increment control must land and survive the settle — the settled entry reads 3.
     */
    @Test
    fun g3IncomingEditsDuringTheRevealSurvive() {
        OwnerProbe.reset()
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
        }
        settle()
        rule.onNodeWithTag("$ABOUT_PAGE_TAG-inc").performClick()
        settle()
        assertEquals(1, OwnerProbe.snapshots[ABOUT_PAGE_TAG]?.saved)

        rule.runOnUiThread { navigation.push(LicencesKey) }
        settle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)

        assertTrue(
            rule.onAllNodesWithTag(ABOUT_PAGE_TAG).fetchSemanticsNodes().isNotEmpty(),
            "About was not revealed within four frames of the pop",
        )
        // The wipe uncovers the incoming pane from the start edge — tap the control's start
        // corner so the touch lands on the revealed strip even mid-wipe.
        repeat(2) {
            rule
                .onNodeWithTag("$ABOUT_PAGE_TAG-inc")
                .performTouchInput { click(Offset(8f, center.y)) }
            rule.mainClock.advanceTimeByFrame()
        }

        settle()
        assertEquals(
            3,
            OwnerProbe.snapshots[ABOUT_PAGE_TAG]?.saved,
            "input to the revealed incoming pane was lost when the swap settled",
        )
        assertEquals(SettingsKey(SettingsPage.ABOUT), navigation.stack(FeedsKey).last())
    }

    // endregion

    // region G4 — MOTION GATE: the push covers from the end

    /**
     * MOTION GATE. Settings Home → About, then push Licences: Licences' pane content enters
     * from the layout end over several frames while About stays drawn underneath at its rest
     * position and the list pane never moves. Licences' owner identity is continuous from the
     * first drawn frame through settle.
     */
    @Test
    fun g4PanePushCoversTheNewDetailFromTheEnd() {
        OwnerProbe.reset()
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
        }
        settle()
        val detailRest = leftEdgeOf(rule.onRoot().captureToImage().toPixelMap(), ABOUT_COLOR)
        assertTrue(detailRest > 0, "expected a two-pane layout")

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.push(LicencesKey) }

        var firstOwner: OwnerProbe.Snapshot? = null
        var transitFrames = 0
        var underneath = false
        var listMoved = false
        repeat(WIPE_FRAME_BOUND) {
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            if (rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty()) {
                val now = OwnerProbe.snapshots[LICENCES_TAG]
                if (firstOwner == null) firstOwner = now
                assertEquals(firstOwner?.vm, now?.vm, "incoming ViewModel was recreated mid-cover")
                assertEquals(firstOwner?.owner, now?.owner, "incoming owner changed mid-cover")
            }
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            if (leftEdgeOf(pixels, SETTINGS_COLOR) != 0) listMoved = true
            val entering = leftEdgeOf(pixels, LICENCES_COLOR)
            if (entering > detailRest) transitFrames++
            if (entering > detailRest && leftEdgeOf(pixels, ABOUT_COLOR) == detailRest) {
                underneath = true
            }
        }
        assertTrue(!listMoved, "the list pane moved during an in-pane cover")
        assertTrue(transitFrames >= 2, "the pushed pane snapped in instead of covering")
        assertTrue(underneath, "the covered detail vanished under the cover")

        settle()
        assertEquals(
            detailRest,
            leftEdgeOf(rule.onRoot().captureToImage().toPixelMap(), LICENCES_COLOR),
        )
        assertEquals(LicencesKey, navigation.stack(FeedsKey).last())
    }

    // endregion

    // region G5 — MOTION GATE: predictive back previews the pane wipe

    /**
     * MOTION GATE. A predictive-back gesture inside a two-pane scene seeks the same wipe the
     * committed pop will run: progress reveals the committed target (About) under the
     * still-cancellable Licences, cancel restores the scene exactly, and a second gesture's
     * commit lands on About exactly once.
     */
    @Test
    fun g5PredictiveBackSeeksThePaneWipe() {
        setFixedHost(directive = TwoPaneDirective)
        pushSettingsChain()
        settle()
        val detailRest = leftEdgeOf(rule.onRoot().captureToImage().toPixelMap(), LICENCES_COLOR)
        val input = DirectNavigationEventInput()
        val eventDispatcher =
            assertNotNull(dispatcher, "no NavigationEventDispatcher owner in the host")
        rule.runOnUiThread { eventDispatcher.addInput(input) }
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread {
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.45f, 300f, 400f))
        }

        // The preview must show the pop's committed target — the same landing a real pop
        // produces — under the still-drawn outgoing pane.
        val previewed =
            advanceFramesUntil(GESTURE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                leftEdgeOf(pixels, LICENCES_COLOR) > detailRest &&
                    leftEdgeOf(pixels, ABOUT_COLOR) == detailRest
            }
        assertTrue(previewed, "predictive progress did not preview the committed pop target")
        assertTrue(
            rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty(),
            "the outgoing entry left composition while the gesture was still cancellable",
        )

        rule.runOnUiThread { input.backCancelled() }
        val restored =
            advanceFramesUntil(GESTURE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                leftEdgeOf(pixels, LICENCES_COLOR) == detailRest &&
                    leftEdgeOf(pixels, ABOUT_COLOR) == -1
            }
        assertTrue(restored, "cancel did not restore the covered scene exactly")

        rule.runOnUiThread {
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.45f, 300f, 400f))
            input.backCompleted()
        }
        settle()
        assertEquals(
            SettingsKey(SettingsPage.ABOUT),
            navigation.stack(FeedsKey).last(),
            "the committed gesture did not land on its previewed target",
        )
        assertTrue(LicencesKey !in navigation.stack(FeedsKey))
    }

    // endregion

    // region G6 — system back in a two-pane scene pops exactly one entry (issue #75)

    /**
     * On a settled two-pane list+detail stack a completed back must remove exactly the detail —
     * the scaffold's boundary handler reports no previousEntries for this shape, so without a
     * fix it pops nothing. Feeds-tab variant; Library must behave identically.
     */
    @Test
    fun g6TwoPaneSystemBackPopsExactlyOneInFeeds() {
        setFixedHost(directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
        }
        settle()

        instantBack()

        settle()
        assertEquals(
            SettingsHomeKey,
            navigation.stack(FeedsKey).last(),
            "system back left the two-pane detail on the stack",
        )
    }

    @Test
    fun g6TwoPaneSystemBackPopsExactlyOneInLibrary() {
        setFixedHost(directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.open(
                LibraryKey,
                listOf(SettingsHomeKey, SettingsKey(SettingsPage.ABOUT)),
            )
        }
        settle()

        instantBack()

        settle()
        assertEquals(
            SettingsHomeKey,
            navigation.stack(LibraryKey).last(),
            "system back left the two-pane detail on the Library stack",
        )
        assertEquals(LibraryKey, navigation.selectedTab, "back should not switch tabs")
    }

    /**
     * Extra-pane boundary semantics are scaffold-owned and preserved: from
     * [F, Home, About, Ep1, Ep2] an instant back lands on About (both extras remove), whether
     * or not an in-pane swap was still in flight when the gesture began — Codex's
     * pinned/settled boundary expectations.
     */
    @Test
    fun g6ExtraPaneBoundaryCommitIsPreserved() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
            navigation.push(EpisodeKey(1))
            navigation.push(EpisodeKey(2))
        }
        settle()

        instantBack()
        settle()
        assertEquals(
            SettingsKey(SettingsPage.ABOUT),
            navigation.stack(FeedsKey).last(),
            "the boundary back must still pop the extra-pane entries to About",
        )
    }

    @Test
    fun g6ExtraPaneBoundaryCommitAfterAPopIsPreserved() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
            navigation.push(EpisodeKey(1))
            navigation.push(EpisodeKey(2))
        }
        settle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)

        instantBack()
        settle()
        assertEquals(
            SettingsKey(SettingsPage.ABOUT),
            navigation.stack(FeedsKey).last(),
            "a back during the extra-entry's exit must still land on the scaffold boundary",
        )
    }

    @Test
    fun g6ExtraPaneBoundaryCancelKeepsTheTop() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
            navigation.push(EpisodeKey(1))
            navigation.push(EpisodeKey(2))
        }
        settle()
        val input = DirectNavigationEventInput()
        val eventDispatcher =
            assertNotNull(dispatcher, "no NavigationEventDispatcher owner in the host")
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            eventDispatcher.addInput(input)
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.4f, 300f, 400f))
            input.backCancelled()
        }
        settle()
        assertEquals(
            EpisodeKey(2),
            navigation.stack(FeedsKey).last(),
            "a cancelled boundary gesture must leave the top entry in place",
        )
    }

    /** The tab-root fallback is unchanged: back at a non-Feeds root never removes the root. */
    @Test
    fun g6TabRootBackKeepsTheRootEntry() {
        setFixedHost(directive = TwoPaneDirective)
        rule.runOnUiThread { navigation.selectTab(LibraryKey) }
        settle()

        instantBack()
        settle()
        assertEquals(
            listOf(LibraryKey),
            navigation.stack(LibraryKey).toList(),
            "back at the tab root must not pop the root entry",
        )
    }

    // endregion

    // region G7 — a second back during the wipe pops the next live entry

    /**
     * Pop Licences, then pop again while any wipe is still running: the second back removes
     * About, not the already-popped entry — the stack lands on Settings Home and the popped
     * ViewModel still clears exactly once.
     */
    @Test
    fun g7SecondBackDuringTheWipePopsTheNextEntry() {
        OwnerProbe.reset()
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        pushSettingsChain()
        settle()
        val licencesVm =
            assertNotNull(OwnerProbe.snapshots[LICENCES_TAG], "licences never composed").vm

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)
        rule.runOnUiThread { navigation.pop() }
        settle()

        assertEquals(
            SettingsHomeKey,
            navigation.stack(FeedsKey).last(),
            "the second back was consumed by the already-popped entry",
        )
        assertEquals(
            1,
            OwnerProbe.cleared.count { it == licencesVm },
            "the popped ViewModel must still clear exactly once",
        )
    }

    // endregion

    // region G8 — pushDetail and reopen during a swap

    /**
     * `pushDetail` targets the logical current detail, not an entry that is still animating
     * out: popping Podcast back to About and then `pushDetail(Appearance)` — same SettingsKey
     * class as About — replaces About and leaves exactly the appearance page in history.
     */
    @Test
    fun g8PushDetailDuringAWipeReplacesTheLogicalDetail() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.panePartitions = 2
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
            navigation.push(PodcastKey(1))
        }
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(3)
        rule.runOnUiThread { navigation.pushDetail(SettingsKey(SettingsPage.APPEARANCE)) }
        settle()

        assertEquals(
            listOf(FeedsKey, SettingsHomeKey, SettingsKey(SettingsPage.APPEARANCE)),
            navigation.stack(FeedsKey).toList(),
            "pushDetail matched the exiting entry instead of the logical detail",
        )
    }

    /** Repeated same-class replacement keeps exactly the newest detail in history. */
    @Test
    fun g8RepeatedPushDetailKeepsOneDetail() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.panePartitions = 2
            navigation.open(FeedsKey, listOf(SettingsHomeKey, PodcastKey(1)))
        }
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pushDetail(PodcastKey(2)) }
        advanceFrames(3)
        rule.runOnUiThread { navigation.pushDetail(PodcastKey(3)) }
        settle()

        assertEquals(
            listOf(FeedsKey, SettingsHomeKey, PodcastKey(3)),
            navigation.stack(FeedsKey).toList(),
            "rapid same-class replacement left the obsolete detail in history",
        )
    }

    /**
     * Reopening the very key that is still exiting — `open` with a stack containing it, or
     * `resetTab` + `push` in the same action — must not crash and must leave exactly the
     * requested history.
     */
    @Test
    fun g8OpenToTheOutgoingKeyMidWipeKeepsTheNewEntry() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        pushSettingsChain()
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)
        rule.runOnUiThread {
            navigation.open(FeedsKey, listOf(SettingsHomeKey, LicencesKey))
        }
        settle()

        assertEquals(
            listOf(FeedsKey, SettingsHomeKey, LicencesKey),
            navigation.stack(FeedsKey).toList(),
            "open discarded the newly requested live detail",
        )
        assertTrue(rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun g8ResetThenRepushTheOutgoingKeyKeepsIt() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        pushSettingsChain()
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)
        rule.runOnUiThread {
            navigation.resetTab(FeedsKey)
            navigation.push(LicencesKey)
        }
        settle()

        assertEquals(
            listOf(FeedsKey, LicencesKey),
            navigation.stack(FeedsKey).toList(),
            "an exiting entry consumed a new key pushed after reset",
        )
        assertTrue(rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty())
    }

    // endregion

    // region G9 — per-tab independence and process-level restore

    /**
     * Equal keys in two tabs are independent members: popping Library's Licences while Feeds
     * still holds its own live Licences must leave the Feeds stack untouched.
     */
    @Test
    fun g9DepartureInOneTabNeverMutatesAnother() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        pushSettingsChain()
        rule.runOnUiThread {
            navigation.open(
                LibraryKey,
                listOf(SettingsHomeKey, SettingsKey(SettingsPage.ABOUT), LicencesKey),
            )
        }
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)
        settle()

        assertTrue(
            LicencesKey in navigation.stack(FeedsKey),
            "a Library pop removed the live Feeds entry with the same key",
        )
        assertEquals(
            SettingsKey(SettingsPage.ABOUT),
            navigation.stack(LibraryKey).last(),
            "the Library pop did not land on its own previous detail",
        )
    }

    /**
     * Fresh-process restore (the saveable registry marshalled through a real Parcel): the
     * imported history contains only committed entries — a still-exiting key in Library is not
     * resurrected, and the equal live key in Feeds survives untouched.
     */
    @Test
    fun g9ParcelRestoredRegistryKeepsCommittedHistoryOnly() {
        setFixedHost(
            installers = ProbedInstallers,
            directive = TwoPaneDirective,
            standaloneRegistry = true,
        )
        pushSettingsChain()
        rule.runOnUiThread {
            navigation.open(
                LibraryKey,
                listOf(SettingsHomeKey, SettingsKey(SettingsPage.ABOUT), LicencesKey),
            )
        }
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)

        // Export the real registry through Parcel bytes, like a fresh process importing it.
        val parcel = Parcel.obtain()
        rule.runOnUiThread {
            parcel.writeMap(assertNotNull(saveableRegistry).performSave())
        }
        val bytes = parcel.marshall()
        parcel.recycle()

        val restored =
            Parcel.obtain().run {
                unmarshall(bytes, 0, bytes.size)
                setDataPosition(0)
                @Suppress("DEPRECATION", "UNCHECKED_CAST")
                val map =
                    readHashMap(javaClass.classLoader) as Map<String, List<Any?>>
                recycle()
                map
            }

        buildFixedHost(
            installers = ProbedInstallers,
            directive = TwoPaneDirective,
            standaloneRegistry = true,
            restoredValues = restored,
        )
        rule.activityRule.scenario.recreate()
        rule.setContent(requireNotNull(hostContent))
        settle()

        assertEquals(
            listOf(FeedsKey, SettingsHomeKey, SettingsKey(SettingsPage.ABOUT), LicencesKey),
            navigation.stack(FeedsKey).toList(),
            "the restore lost or rewrote the live Feeds history",
        )
        assertEquals(
            listOf(LibraryKey, SettingsHomeKey, SettingsKey(SettingsPage.ABOUT)),
            navigation.stack(LibraryKey).toList(),
            "the restore resurrected or rewrote the committed Library history",
        )
    }

    // endregion

    // region G10 — lifecycle, layout and platform invariants

    /**
     * Activity recreation while a pop is still animating: the restored stack contains the
     * committed history, the popped ViewModel cleared exactly once, and reopening the key
     * composes a fresh owner.
     */
    @Test
    fun g10ActivityRecreationMidWipeClearsThePoppedOwnerOnce() {
        OwnerProbe.reset()
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        pushSettingsChain()
        settle()
        val licencesVm =
            assertNotNull(OwnerProbe.snapshots[LICENCES_TAG], "licences never composed").vm

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)

        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.onActivity {
            it.setContent(content = requireNotNull(hostContent))
        }
        settle()

        assertEquals(SettingsKey(SettingsPage.ABOUT), navigation.stack(FeedsKey).last())
        assertTrue(LicencesKey !in navigation.stack(FeedsKey))
        advanceFrames(64)
        rule.runOnUiThread { navigation.push(LicencesKey) }
        settle()

        assertEquals(
            1,
            OwnerProbe.cleared.count { it == licencesVm },
            "the popped ViewModel leaked across the configuration change",
        )
        assertTrue(
            licencesVm != OwnerProbe.snapshots[LICENCES_TAG]?.vm,
            "the reopened entry reused the popped ViewModel",
        )
    }

    /**
     * Collapsing to one pane while a pop is animating commits the logical stack immediately;
     * the outgoing pane's visual exit may finish as ordinary scene motion, but it can never
     * stay on the real stack or interactive.
     */
    @Test
    fun g10CollapseToOnePaneMidWipeCommitsTheHistory() {
        val directive = setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        pushSettingsChain()
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(3)
        rule.runOnUiThread { directive.value = PaneScaffoldDirective.Default }

        advanceFrames(4)
        assertTrue(
            LicencesKey !in navigation.stack(FeedsKey),
            "the popped entry stayed on the real stack after the collapse",
        )
        val framesToGone = framesUntilAbsent(LICENCES_TAG, COLLAPSE_EXIT_FRAME_BOUND)
        assertTrue(
            framesToGone < COLLAPSE_EXIT_FRAME_BOUND,
            "the popped entry stayed visible ${framesToGone}f after the collapse",
        )
        settle()
        assertEquals(SettingsKey(SettingsPage.ABOUT), navigation.stack(FeedsKey).last())
    }

    /** The reverse direction: expanding mid-pop also lands on the committed history. */
    @Test
    fun g10ExpandToTwoPanesMidWipeCommitsTheHistory() {
        val directive =
            setFixedHost(installers = ProbedInstallers, directive = PaneScaffoldDirective.Default)
        pushSettingsChain()
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)
        rule.runOnUiThread { directive.value = TwoPaneDirective }
        settle()

        assertEquals(SettingsKey(SettingsPage.ABOUT), navigation.stack(FeedsKey).last())
        assertTrue(LicencesKey !in navigation.stack(FeedsKey))
        assertTrue(rule.onAllNodesWithTag(ABOUT_PAGE_TAG).fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * Compact mode keeps the outer-scene pair: the single-pane push covers from the end and the
     * pop wipes toward it — the fixture proves animation is observable here.
     */
    @Test
    fun g10CompactCoverAndWipeStillAnimate() {
        setFixedHost(installers = ProbedInstallers, directive = PaneScaffoldDirective.Default)
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.push(PodcastKey(1)) }
        val covered =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                leftEdgeOf(pixels, PODCAST_COLOR) in 1..<pixels.width
            }
        assertTrue(covered, "the compact push produced no cover motion")
        settle()

        rule.runOnUiThread { navigation.pop() }
        val wiped =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                leftEdgeOf(pixels, PODCAST_COLOR) > 0 && leftEdgeOf(pixels, FEEDS_COLOR) == 0
            }
        assertTrue(wiped, "the compact pop produced no wipe motion")
        settle()
        assertEquals(listOf(FeedsKey), navigation.stack(FeedsKey).toList())
    }

    /** The instant tab switch is unchanged under a one-pane layout. */
    @Test
    fun g10CompactTabSwitchStaysInstant() {
        setFixedHost(installers = ProbedInstallers, directive = PaneScaffoldDirective.Default)
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.selectTab(LibraryKey) }
        var blendSeen = false
        val swapped =
            advanceFramesUntil(INSTANT_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                if (hasBlendedPixels(pixels, FEEDS_COLOR, LIBRARY_COLOR)) blendSeen = true
                leftEdgeOf(pixels, FEEDS_COLOR) == -1 && leftEdgeOf(pixels, LIBRARY_COLOR) == 0
            }
        assertTrue(swapped, "the compact tab switch still animates")
        assertTrue(!blendSeen, "the compact tab switch cross-faded")
    }

    /**
     * Overlay scenes keep the platform default: pushing a sheet never translates the entries
     * underneath — no wipe, no cover — while the sheet content appears.
     */
    @Test
    fun g10OverlayKeepsThePlatformDefault() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        pushSettingsChain()
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.push(AddPodcastKey(input = null)) }

        var underlyingMoved = false
        val sheetShown =
            advanceFramesUntil(FADE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                if (leftEdgeOf(pixels, SETTINGS_COLOR) != 0) underlyingMoved = true
                rule.onAllNodesWithTag(ADD_PODCAST_TAG).fetchSemanticsNodes().isNotEmpty()
            }
        assertTrue(sheetShown, "the pushed sheet never appeared")
        assertTrue(!underlyingMoved, "the overlay entry translated the underlying scene")
    }

    /**
     * Reduced motion snaps every transition: any in-pane swap that does run must reach rest
     * without intermediate offsets — a frame showing the outgoing detail right of its rest
     * edge is an animated wipe and fails.
     */
    @Test
    fun g10ReducedMotionSnapsThePaneSwap() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective, reducedMotion = true)
        pushSettingsChain()
        settle()
        val detailRest = leftEdgeOf(rule.onRoot().captureToImage().toPixelMap(), LICENCES_COLOR)
        assertTrue(detailRest > 0, "expected a two-pane layout")

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }

        var transitSeen = false
        repeat(WIPE_FRAME_BOUND) {
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            if (leftEdgeOf(pixels, LICENCES_COLOR) > detailRest) transitSeen = true
        }
        assertTrue(!transitSeen, "the in-pane wipe kept animating under reduced motion")
        settle()
        assertEquals(SettingsKey(SettingsPage.ABOUT), navigation.stack(FeedsKey).last())
    }

    /**
     * MOTION GATE (G10, RTL): the same wipe mirrored — under RTL the outgoing detail recedes
     * through the pane's start side, i.e. its right edge pulls left, revealing About.
     */
    @Test
    fun g10RtlPaneWipeRecedesTowardTheLayoutEnd() {
        setFixedHost(
            installers = ProbedInstallers,
            directive = TwoPaneDirective,
            layoutDirection = LayoutDirection.Rtl,
        )
        pushSettingsChain()
        settle()
        val restRight = rightEdgeOf(rule.onRoot().captureToImage().toPixelMap(), LICENCES_COLOR)
        assertTrue(restRight > 0, "expected licences painted")

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }

        var transitFrames = 0
        var revealed = false
        repeat(WIPE_FRAME_BOUND) {
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            val receding = rightEdgeOf(pixels, LICENCES_COLOR)
            if (receding in 1 until restRight) {
                transitFrames++
                if (leftEdgeOf(pixels, ABOUT_COLOR) != -1) revealed = true
            }
        }
        assertTrue(transitFrames >= 2, "the RTL pop produced no pane wipe")
        assertTrue(revealed, "the RTL wipe never revealed the previous detail")
    }

    // endregion

    private fun pushSettingsChain() {
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
            navigation.push(LicencesKey)
        }
    }

    /** An instant back through the real dispatcher — the `backStarted` + `backCompleted` path. */
    private fun instantBack() {
        val input = DirectNavigationEventInput()
        val eventDispatcher =
            assertNotNull(dispatcher, "no NavigationEventDispatcher owner in the host")
        rule.runOnUiThread {
            eventDispatcher.addInput(input)
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backCompleted()
        }
    }
}
