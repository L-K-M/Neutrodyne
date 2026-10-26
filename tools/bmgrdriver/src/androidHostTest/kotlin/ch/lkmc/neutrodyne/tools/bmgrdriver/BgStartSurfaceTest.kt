// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Host-side coverage for the bgstart lane's pure dump parsers and fixture audio — the
 * same surfaces the device run and the strict aggregate checker assert on real evidence.
 */
class BgStartSurfaceTest {
    @Test
    fun parsesUidState() {
        assertEquals(4, BgStartSurface.parseUidState("4 (FOREGROUND_SERVICE)"))
        assertEquals(10, BgStartSurface.parseUidState("10 (SERVICE)"))
        assertEquals(20, BgStartSurface.parseUidState("20 (NONEXISTENT)"))
        assertNull(BgStartSurface.parseUidState(""))
        assertNull(BgStartSurface.parseUidState("garbage"))
    }

    @Test
    fun parsesAppUid() {
        assertEquals(
            10150,
            BgStartSurface.parseAppUid(
                "package:ch.lkmc.neutrodyne uid:10150",
                "ch.lkmc.neutrodyne",
            ),
        )
        assertNull(
            BgStartSurface.parseAppUid(
                "package:ch.lkmc.neutrodyne",
                "ch.lkmc.neutrodyne",
            ),
        )
    }

    @Test
    fun parsesAppUidAnchorsTheExactPackage() {
        // `pm list packages -U` substring-matches: the driver package also matches
        // `ch.lkmc.neutrodyne`, and either line can come first (R1 picked the driver).
        val listing =
            """
            package:ch.lkmc.neutrodyne.tools.bmgrdriver.test uid:10151
            package:ch.lkmc.neutrodyne uid:10150
            """.trimIndent()
        assertEquals(10150, BgStartSurface.parseAppUid(listing, "ch.lkmc.neutrodyne"))
        assertEquals(
            10151,
            BgStartSurface.parseAppUid(
                listing,
                "ch.lkmc.neutrodyne.tools.bmgrdriver.test",
            ),
        )
        val reversed = listing.lines().reversed().joinToString("\n")
        assertEquals(10150, BgStartSurface.parseAppUid(reversed, "ch.lkmc.neutrodyne"))
    }

    @Test
    fun distinguishesLiveFromPhantomServiceRecords() {
        val live =
            """
            ServiceRecord{u0 abc u0a150 ch.lkmc.neutrodyne/.NeutrodynePlaybackService}
              app=ProcessRecord{abc123 5123:ch.lkmc.neutrodyne/u0a150}
            """.trimIndent()
        assertTrue(BgStartSurface.serviceRecordPresent(live))
        assertTrue(BgStartSurface.serviceRunning(live))
        // A denied background start leaves a phantom record: app=null, no process.
        val phantom = live.replace("app=ProcessRecord{abc123 5123:ch.lkmc.neutrodyne/u0a150}", "app=null")
        assertTrue(BgStartSurface.serviceRecordPresent(phantom))
        assertFalse(BgStartSurface.serviceRunning(phantom))
    }

    @Test
    fun parsesBindingClients() {
        val dump =
            """
            ServiceRecord{u0 abc u0a150 ch.lkmc.neutrodyne/.NeutrodynePlaybackService}
              app=ProcessRecord{abc123 5123:ch.lkmc.neutrodyne/u0a150}
              * Client AppBindRecord{aaa111 ProcessRecord{bbb222 5123:ch.lkmc.neutrodyne.tools.bgactor/u0a151}}
              * Client AppBindRecord{ccc333 ProcessRecord{ddd444 6001:com.android.systemui/u0a124}}
            """.trimIndent()
        assertEquals(
            listOf(
                "ch.lkmc.neutrodyne.tools.bgactor/u0a151",
                "com.android.systemui/u0a124",
            ),
            BgStartSurface.bindingClients(dump),
        )
        assertEquals(emptyList<String>(), BgStartSurface.bindingClients("no bindings"))
    }

    @Test
    fun parsesServiceRecordVerdicts() {
        val denied =
            """
            ServiceRecord{u0 abc u0a150 ch.lkmc.neutrodyne/.NeutrodynePlaybackService}
              isForeground=false foregroundId=0 foregroundNoti=null
              mAllowStart_noBinding=DENIED
              mAllowStart_inBindService=DENIED
              mAllowStart_byBindings=DENIED
            """.trimIndent()
        assertTrue(BgStartSurface.serviceRecordPresent(denied))
        assertEquals(false, BgStartSurface.isForegroundValue(denied))
        assertEquals("DENIED", BgStartSurface.noBindingVerdict(denied))
        assertEquals("DENIED", BgStartSurface.byBindingsVerdict(denied))
        val foreground = denied.replace("isForeground=false", "isForeground=true")
        assertEquals(true, BgStartSurface.isForegroundValue(foreground))
    }

    @Test
    fun parsesAllowStartForegroundRecords() {
        val dump =
            """
            infoAllowStartForeground=[callingPackage: ch.lkmc.neutrodyne; callingUid: 10150; uidState: SVC ; uidBFSL: []; intent: Intent { cmp=a/b }; code:DENIED; tempAllowListReason:<null>; allowWiu:0; targetSdkVersion:36; callerTargetSdkVersion:0; startForegroundCount:0; bindFromPackage:null: isBindService:false]
            infoAllowStartForeground=[callingPackage: com.android.systemui; callingUid: 10124; uidState: PER ; uidBFSL: [BFSL]; intent: Intent { act=MediaBrowserService }; code:PROC_STATE_PERSISTENT; tempAllowListReason:<null>; allowWiu:52; targetSdkVersion:36; callerTargetSdkVersion:36; startForegroundCount:0; bindFromPackage:com.android.systemui: isBindService:true]
            """.trimIndent()
        val infos = BgStartSurface.allowInfos(dump)
        assertEquals(2, infos.size)
        val self = infos[0]
        assertEquals("ch.lkmc.neutrodyne", self.callingPackage)
        assertEquals(10150, self.callingUid)
        assertEquals("SVC", self.uidState)
        assertFalse(self.hasBfslCapability)
        assertFalse(self.tempAllowListed)
        assertEquals(false, self.isBindService)
        assertTrue(self.selfAttempt(10150))
        val systemUi = infos[1]
        assertTrue(systemUi.hasBfslCapability)
        assertFalse(systemUi.selfAttempt(10150))
        assertEquals("com.android.systemui", systemUi.bindingGrant)
        assertTrue(systemUi.granted) // PROC_STATE_PERSISTENT is an allow code
        assertNull(self.bindingGrant)
        assertFalse(self.granted)
        assertTrue("SVC" in BgStartSurface.UIDSTATE_BELOW_BFGS)
        assertTrue("PER" !in BgStartSurface.UIDSTATE_BELOW_BFGS)
    }

    @Test
    fun selfAttemptRespectsIsBindServiceOverUid() {
        val dump =
            """
            infoAllowStartForeground=[callingPackage: ch.lkmc.neutrodyne; callingUid: 10150; code:DENIED; bindFromPackage:null: isBindService:true]
            infoAllowStartForeground=[callingPackage: ch.lkmc.neutrodyne; callingUid: 10150; code:DENIED]
            """.trimIndent()
        val infos = BgStartSurface.allowInfos(dump)
        // A bind record from the target's own uid is not a startForeground attempt.
        assertFalse(infos[0].selfAttempt(10150))
        // Sparse records without isBindService fall back to the calling uid.
        assertTrue(infos[1].selfAttempt(10150))
    }

    @Test
    fun detectsTempAllowListOnlyWhenGranted() {
        val clean = "code:DENIED; tempAllowListReason:<null>; x=1\ntempAllowListReason=null\n"
        assertTrue(BgStartSurface.tempAllowListObserved(clean))
        assertFalse(BgStartSurface.tempAllowListViolation(clean))
        val granted = "tempAllowListReason=EXEMPTED\n"
        assertTrue(BgStartSurface.tempAllowListObserved(granted))
        assertTrue(BgStartSurface.tempAllowListViolation(granted))
        assertFalse(BgStartSurface.tempAllowListObserved("no field here"))
    }

    @Test
    fun matchesNotificationRecords() {
        val dump =
            """
            NotificationRecord(0x01: pkg=com.android.systemui user=UserHandle{0} id=33 tag=x key=s: Notification(channel=DEVELOPER pri=0))
            NotificationRecord(0x02: pkg=ch.lkmc.neutrodyne user=UserHandle{0} id=4001 tag=null key=t: Notification(channel=alerts contentView=headsUp pri=2))
            """.trimIndent()
        assertTrue(BgStartSurface.hasNotification(dump, "ch.lkmc.neutrodyne", 4001, "alerts"))
        assertTrue(BgStartSurface.hasNotificationId(dump, "ch.lkmc.neutrodyne", 4001))
        assertFalse(BgStartSurface.hasNotification(dump, "ch.lkmc.neutrodyne", 4000, "playback"))
        assertFalse(BgStartSurface.hasNotificationId(dump, "ch.lkmc.neutrodyne", 4000))
    }

    @Test
    fun matchesAudioPlaybackConfigurations() {
        val dump =
            """
            AudioPlaybackConfiguration items:
              - piid:391 uid:10150 state:started attr:{Content type: CONTENT_TYPE_SPEECH Usage: USAGE_MEDIA}
              - piid:392 uid:1000 state:stopped attr:{Usage: USAGE_MEDIA}
            """.trimIndent()
        assertTrue(BgStartSurface.apcStarted(dump, 10150))
        assertFalse(BgStartSurface.apcStarted(dump, 1000))
        assertFalse(BgStartSurface.apcStarted(dump, 10151))
    }

    @Test
    fun projectsLivePlaybackPosition() {
        val dump =
            """
            Media sessions (1):
              ch.lkmc.neutrodyne/ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService (userId=0)
                PlaybackState {state=PLAYING(3), position=750000, buffered position=760000, speed=1.0, updated=9000, actions=12345}
            """.trimIndent()
        val sample = BgStartSurface.mediaSessionPlayback(dump, "ch.lkmc.neutrodyne")!!
        assertEquals(3, sample.state)
        assertEquals(750_000L, sample.position)
        assertEquals(1.0f, sample.speed!!, 0.001f)
        assertEquals(9_000L, sample.updatedElapsed)
        assertTrue(sample.playing)
        assertFalse(sample.paused)
        assertEquals(751_500L, sample.livePosition(10_500))
        val paused =
            dump.replace("state=PLAYING(3)", "state=PAUSED(2)").replace("speed=1.0", "speed=0.0")
        val pausedSample = BgStartSurface.mediaSessionPlayback(paused, "ch.lkmc.neutrodyne")!!
        assertTrue(pausedSample.paused)
        assertEquals(750_000L, pausedSample.livePosition(11_000))
        assertNull(BgStartSurface.mediaSessionPlayback("unrelated", "ch.lkmc.neutrodyne"))
    }

    @Test
    fun fixtureAudioIsWellFormedWav() {
        val wav = FixtureAudio.pcm(3)
        assertEquals(44 + 3 * FixtureAudio.BYTES_PER_SECOND, wav.size)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals("WAVE", wav.decodeToString(8, 12))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 3 * FixtureAudio.BYTES_PER_SECOND, header.getInt(4))
        assertEquals(FixtureAudio.SAMPLE_RATE, header.getInt(24))
        assertEquals(3 * FixtureAudio.BYTES_PER_SECOND, header.getInt(40))
        assertTrue(wav.sliceArray(44 until wav.size).any { it != 0.toByte() })
    }
}
