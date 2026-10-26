// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side coverage for the bgstart lane's pure dump parsers — the
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
        assertEquals("0", systemUi.startForegroundCount)
        assertNull(self.bindingGrant)
        assertFalse(self.granted)
        assertTrue("SVC" in BgStartSurface.UIDSTATE_BELOW_BFGS)
        assertTrue("PER" !in BgStartSurface.UIDSTATE_BELOW_BFGS)
    }

    @Test
    fun parsesTheTrustedBindGrantStamp() {
        // The api31 record is sparse (no uidBFSL/isBindService); api36 carries both.
        val records =
            listOf(
                "infoAllowStartForeground=[callingPackage: ch.lkmc.neutrodyne.tools.bgtrigger; " +
                    "callingUid: 10108; uidState: BFGS; intent: Intent { act=a }; " +
                    "code:PROC_STATE_BFGS; tempAllowListReason:<null>; targetSdkVersion:37; " +
                    "callerTargetSdkVersion:30; startForegroundCount:0; bindFromPackage:null]",
                "infoAllowStartForeground=[callingPackage: ch.lkmc.neutrodyne.tools.bgtrigger; " +
                    "callingUid: 10153; uidState: BFGS; uidBFSL: [BFSL]; intent: Intent { act=a }; " +
                    "code:PROC_STATE_BFGS; tempAllowListReason:<null>; allowWiu:-1; " +
                    "targetSdkVersion:37; callerTargetSdkVersion:30; startForegroundCount:0; " +
                    "bindFromPackage:null: isBindService:true]",
            )
        for (record in records) {
            val info = BgStartSurface.allowInfos(record).single()
            assertEquals("ch.lkmc.neutrodyne.tools.bgtrigger", info.callingPackage)
            assertEquals("BFGS", info.uidState)
            assertEquals("PROC_STATE_BFGS", info.code)
            assertEquals("0", info.startForegroundCount)
            assertTrue(info.granted)
        }
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
        val paused =
            dump.replace("state=PLAYING(3)", "state=PAUSED(2)").replace("speed=1.0", "speed=0.0")
        val pausedSample = BgStartSurface.mediaSessionPlayback(paused, "ch.lkmc.neutrodyne")!!
        assertTrue(pausedSample.paused)
        assertNull(BgStartSurface.mediaSessionPlayback("unrelated", "ch.lkmc.neutrodyne"))
    }

    @Test
    fun attributesPlaybackToTheExactSessionBlock() {
        // A foreign session's state above the target header is nearer than the
        // target's own state below it — distance must not win over block bounds.
        val dump =
            """
            Media sessions (2):
              MediaPlaybackService com.android.music/MediaPlaybackService (userId=0)
                package=com.android.music
                state=PlaybackState {state=PLAYING(3), position=111, buffered position=111, speed=1.0, updated=100}
              neutrodyne ch.lkmc.neutrodyne/ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService (userId=0)
                ownerPid=2851, ownerUid=10150, userId=0
                package=ch.lkmc.neutrodyne
                active=true
                flags=7
                rating type=0
                controllers: 1
                state=PlaybackState {state=PAUSED(2), position=750000, buffered position=750000, speed=0.0, updated=200}
            """.trimIndent()
        val sample = BgStartSurface.mediaSessionPlayback(dump, "ch.lkmc.neutrodyne")!!
        assertEquals(2, sample.state)
        assertEquals(750_000L, sample.position)
        assertEquals(200L, sample.updatedElapsed)
    }

    @Test
    fun ignoresSiblingPrefixSessionHeaders() {
        // `ch.lkmc.neutrodyne.tools.*` contains the app package as a prefix; its
        // session must not be attributed to `ch.lkmc.neutrodyne`.
        val dump =
            """
            Media sessions (2):
              bgmirror ch.lkmc.neutrodyne.tools.bgmirror/ch.lkmc.neutrodyne.tools.bgmirror.MirrorSession (userId=0)
                package=ch.lkmc.neutrodyne.tools.bgmirror
                state=PlaybackState {state=PLAYING(3), position=42, buffered position=42, speed=1.0, updated=50}
              neutrodyne ch.lkmc.neutrodyne/ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService (userId=0)
                package=ch.lkmc.neutrodyne
                state=PlaybackState {state=PAUSED(2), position=750000, buffered position=750000, speed=0.0, updated=60}
            """.trimIndent()
        val sample = BgStartSurface.mediaSessionPlayback(dump, "ch.lkmc.neutrodyne")!!
        assertEquals(2, sample.state)
        assertEquals(750_000L, sample.position)
    }

    @Test
    fun returnsNullWhenTheTargetSessionHasNoState() {
        // The target block ends at the next sibling header; a later session's
        // PlaybackState must not be attributed back to it.
        val dump =
            """
            Media sessions (2):
              neutrodyne ch.lkmc.neutrodyne/ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService (userId=0)
                package=ch.lkmc.neutrodyne
                active=false
                state=null
                queueTitle=null, size=0
              other com.other.app/com.other.app.MusicService (userId=0)
                package=com.other.app
                state=PlaybackState {state=PLAYING(3), position=9, buffered position=9, speed=1.0, updated=10}
            """.trimIndent()
        assertNull(BgStartSurface.mediaSessionPlayback(dump, "ch.lkmc.neutrodyne"))
    }

    @Test
    fun parsesTheSavedApi31AndApi36SessionDumps() {
        val api31 = BgStartSurface.mediaSessionPlayback(api31SessionDump, "ch.lkmc.neutrodyne")!!
        assertEquals(0, api31.state)
        assertEquals(750_000L, api31.position)
        assertEquals(117_008L, api31.updatedElapsed)
        // api36 nests the target under a foreign com.android.music session whose
        // state sits nearer the target header than the target's own state.
        val api36 = BgStartSurface.mediaSessionPlayback(api36SessionDump, "ch.lkmc.neutrodyne")!!
        assertEquals(0, api36.state)
        assertEquals(750_000L, api36.position)
        assertEquals(121_063L, api36.updatedElapsed)
    }

    // Saved api31 dump; long unrelated field tails are elided with `…`.
    private val api31SessionDump =
        """
        MEDIA SESSION SERVICE (dumpsys media_session)

        4 sessions listeners.
        Global priority session is com.android.server.telecom/HeadsetMediaButton (userId=0)
          HeadsetMediaButton com.android.server.telecom/HeadsetMediaButton (userId=0)
            ownerPid=538, ownerUid=1000, userId=0
            package=com.android.server.telecom
            launchIntent=null
            mediaButtonReceiver=null
            active=false
            flags=65537
            rating type=0
            controllers: 0
            state=null
            audioAttrs=AudioAttributes: usage=USAGE_VOICE_COMMUNICATION content=CONTENT_TYPE_SPEECH …
            volumeType=1, controlType=2, max=0, current=0
            metadata: null
            queueTitle=null, size=0
        User Records:
        Record for full_user=0
          Volume key long-press listener: null
          Volume key long-press listener package:
          Media key listener: null
          Media key listener package:
          OnMediaKeyEventDispatchedListener: added 0 listener(s)
          OnMediaKeyEventSessionChangedListener: added 1 listener(s)
            from com.android.bluetooth
          Last MediaButtonReceiver: null
          Media button session is null
          Sessions Stack - have 1 sessions:
            androidx.media3.session.id.neutrodyne ch.lkmc.neutrodyne/androidx.media3.session.id.neutrodyne (userId=0)
              ownerPid=2695, ownerUid=10105, userId=0
              package=ch.lkmc.neutrodyne
              launchIntent=PendingIntent{8f532e0: PendingIntentRecord{f271999 ch.lkmc.neutrodyne startActivity}}
              mediaButtonReceiver=Restored MBR {component=ComponentInfo{ch.lkmc.neutrodyne/androidx.media3.session…
              active=true
              flags=7
              rating type=0
              controllers: 1
              state=PlaybackState {state=0, position=750000, buffered position=750000, speed=0.0, updated=117008, …
              audioAttrs=AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_SPEECH flags=0x800 tags= bundle=null
              volumeType=1, controlType=2, max=0, current=0
              metadata: size=14, description=Alpha Episode Two run-1793047478-3988, null, null
              queueTitle=null, size=2
        Audio playback (lastly played comes first)
        Media session config:
          media_button_receiver_fgs_allowlist_duration_ms: [cur: 10000, def: 10000]
          media_session_calback_fgs_allowlist_duration_ms: [cur: 10000, def: 10000]
          media_session_callback_fgs_while_in_use_temp_allow_duration_ms: [cur: 10000, def: 10000]
        """.trimIndent()

    // Saved api36 dump; long unrelated field tails are elided with `…`.
    private val api36SessionDump =
        """
        MEDIA SESSION SERVICE (dumpsys media_session)

        7 sessions listeners.
        Global priority session is com.android.server.telecom/HeadsetMediaButton/1 (userId=0)
          HeadsetMediaButton com.android.server.telecom/HeadsetMediaButton/1 (userId=0)
            ownerPid=634, ownerUid=1000, userId=0
            package=com.android.server.telecom
            launchIntent=null
            mediaButtonReceiver=null
            active=false
            flags=65537
            rating type=0
            controllers: 0
            state=null
            audioAttrs=AudioAttributes: usage=USAGE_VOICE_COMMUNICATION content=CONTENT_TYPE_SPEECH …
            volumeType=LOCAL, controlType=ABSOLUTE, max=0, current=0, volumeControlId=null
            metadata: null
            queueTitle=null, size=0
        User Records:
        Record for full_user=0
          Volume key long-press listener: null
          Volume key long-press listener package:
          Media key listener: null
          Media key listener package:
          OnMediaKeyEventDispatchedListener: added 0 listener(s)
          OnMediaKeyEventSessionChangedListener: added 1 listener(s)
            from com.android.bluetooth
          Last MediaButtonReceiver: null
          Media button session is null
          Sessions Stack - have 2 sessions:
            MediaPlaybackService com.android.music/MediaPlaybackService/6 (userId=0)
              ownerPid=1670, ownerUid=10102, userId=0
              package=com.android.music
              launchIntent=PendingIntent{38d7436: PendingIntentRecord{4671f37 com.android.music startActivity}}
              mediaButtonReceiver=null
              active=false
              flags=3
              rating type=0
              controllers: 0
              state=PlaybackState {state=NONE(0), position=0, buffered position=0, speed=0.0, updated=0, …
              audioAttrs=AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_UNKNOWN flags=0x800 tags= bundle=null
              volumeType=LOCAL, controlType=ABSOLUTE, max=0, current=0, volumeControlId=null
              metadata: null
              queueTitle=null, size=0
            androidx.media3.session.id.neutrodyne ch.lkmc.neutrodyne/androidx.media3.session.id.neutrodyne/7 (userId=0)
              ownerPid=2851, ownerUid=10150, userId=0
              package=ch.lkmc.neutrodyne
              launchIntent=PendingIntent{6f53ca4: PendingIntentRecord{9bcfa0d ch.lkmc.neutrodyne startActivity}}
              mediaButtonReceiver=MBR {pi=null, componentName=ComponentInfo{ch.lkmc.neutrodyne/androidx.media3…
              active=true
              flags=7
              rating type=0
              controllers: 1
              state=PlaybackState {state=NONE(0), position=750000, buffered position=750000, speed=0.0, updated=121063}
              audioAttrs=AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_SPEECH flags=0x800 tags= bundle=null
              volumeType=LOCAL, controlType=ABSOLUTE, max=0, current=0, volumeControlId=null
              metadata: size=14, description=Alpha Episode Two run-1793047657-14049, null, null
              queueTitle=null, size=2
        Audio playback (lastly played comes first)
        Media session config:
          media_button_receiver_fgs_allowlist_duration_ms: [cur: 10000, def: 10000]
          media_session_calback_fgs_allowlist_duration_ms: [cur: 10000, def: 10000]
          media_session_callback_fgs_while_in_use_temp_allow_duration_ms: [cur: 10000, def: 10000]
          media_session_temp_user_engaged_duration_ms: [cur: 600000, def: 600000]
        """.trimIndent()
}
