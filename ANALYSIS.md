# Neutrodyne design backlog

This backlog records open design and engineering work for the radio home and
macOS experience. The first review began on `f9bd91021` with code inspection,
fresh desktop fixture renders, and desktop journeys for discovery, group
management, and the mini player; a second polish pass ran on `a9b0e114f` over
the shared radio parts, the feeds adapter and every text field. Playback
latency and frame pacing still require measurements on the Mac.
[Product plan](docs/PLAN.md)
[Radio design](docs/design/08-ui-ux.md#radio-home)

Keep the material and control vocabulary from PO-55. The walnut cabinet,
enamel signs, paper, and tactile controls should help users distinguish
navigation, programme, and transport. Native window and menu behavior matters
alongside the shared cabinet.

A third pass on 2026-11-18 ran the desktop app from source, read the macOS
accessibility tree, and checked ten helper-model subsystem reviews against the
code (94 of 108 claims confirmed). Its fixes are listed under
[proposed fixes](#native-verification-on-proposed-fixes); what it found and did
not fix is folded into the sections below. Claims that verification refuted
are listed under [Checked and cleared](#checked-and-cleared) so nobody
re-reports them.

## Done since the last consolidation

- **Transport status while buffering, ended or failed (PR #575, merged).** The radio prints BUFFERING, ENDED or PLAYBACK ERROR from the
  loaded player's phase, with the pilot lamp amber while buffering and red on
  an issue. The phase and issue come from the existing `PlaybackStateSource`
  snapshot; no new source of playback truth.
- **Arrow-key tuning (PR #576, merged).** With a slip focused, Up and
  Down move the selection, scroll it into view and carry focus, stopping at
  the ends. Chords pass through to the root and the handler bubbles, so a
  descendant that learns arrows later wins.
- **Global shortcuts stood down in sync fields (PR #577, awaiting merge).** The linking
  flow's address, invite, username and password fields, the approve sheet's
  code field, the Settings › Sync server-address field, and the device-rename
  and delete-confirmation strips now report through
  `Modifier.ndTextFieldFocus()` like every other field. Typing in them no
  longer toggles playback or skips, and the desktop menu accelerators clear
  again. Regression cover: `SyncApproveSheetTest`'s focus-tracker tests.
- **An empty radio offers its two filling routes (PR #580, awaiting merge).** With no groups,
  the programme said "All clear" as if a station had been emptied. It now
  says "No playlists yet." and offers momentary keys to Discover and Library;
  a genuinely cleared playlist keeps its All clear sign without route keys.
- **Thirteen fixes from the third pass (PRs #581 to #593, awaiting merge).**
  Each is listed with the device check it still needs under
  [Native verification on proposed fixes](#native-verification-on-proposed-fixes).

## Playback and programme correctness

### Reconcile paging during Undo and clarify repeated discards

The discard race has a tested fix awaiting merge. It preserves a newer
selection and seek while a Mark played write is pending. Verify pausing or
receiving an engine position update during a discard on a device. Preserve
the adjacent-episode behavior when discarding the selected slip.
[Discard fix](https://github.com/L-K-M/Neutrodyne/pull/564)

During Undo, `onRows` defers a paging snapshot and the Undo path drops it.
Suspend a fake repository write, deliver a newer same-playlist page, then
complete the write with success and failure. Reconcile the page in both
cases. Give rapid repeated discards explicit feedback while the first write
is pending.

### Add a recovery route and Cancel while buffering

The status and lamp now read BUFFERING, ENDED and PLAYBACK ERROR from the
loaded player's phase and issue ([done](#done-since-the-last-consolidation)). What is still missing is
the way out: a route to the existing recovery action where applicable, and
Cancel staying available while a stream buffers. Test a delayed stream,
metered consent, engine refusal, replay, and a switch from a failing item to
a healthy one, on top of the status tests. Avoid inventing a new source of
playback truth in the UI.

### Keep all transport functions reachable at high text scales

`RadioPlayer` hides the skip keys below its combined width estimate. The
narrow layout relies on the needle, which requires a known duration and
cannot apply the listener's skip intervals. Provide a second layout for the
keys with 48 dp targets and the printed intervals. Use fewer scale labels
where they would collide. Show a quiet inactive dial when the duration is
unknown. Test both skip values, unknown durations, three-hour media,
and 2.0 text at 320 dp. Check German and keyboard access.

Window width also matters. At 390 × 844 both keys flank the counter. At
700 × 900 the right pane narrows enough to hide them. Retain the 600 dp
two-column transition. Compare a second compact bank with reallocating pane
width at 599, 600 and 700 dp and 1×, 1.3× and 2× text. Keep the four Nixie
digits legible. [Phone](core/ui/screenshots/desktop/radio_phone.png) ·
[Tablet](core/ui/screenshots/desktop/radio_tablet.png)

For unknown duration, `RadioPlayer` prints five `00:00` labels, repeats the end time on tall
screens, and exposes a SetProgress action that returns false. Compare blank graduations with a single
localized duration-unavailable legend. Let the inactive dial pass vertical
scrolling through and retain elapsed time. Verify an empty playlist, a live
stream, a downloaded recording, and duration arriving after playback starts.

At the 600 × 800 desktop minimum the home already takes the two-column layout:
the speaker grille takes half the width, both skip keys disappear, and slip
titles cut to two short lines. Consider a 720 dp threshold for the split or a
speaker that yields width before the keys do. Skip forward clamped at the end
of an episode gives no feedback and does not advance; decide between a brief
refusal on the key and moving to the next slip.

### Give long playlists a reliable count

`RadioViewModel.onRows` asks the repository for an unplayed count on every
incomplete paging snapshot. The result is stored as `RadioState.episodeCount`,
but the current dial only prints its position when the loaded episode list is
complete. The design calls for repository counts while paging. Show the total
as a localized unplayed count for partial pages, and show a position only when
the episode's ordinal is known. Check whether the count can come from the same
paging generation or can be shared across append events. A failed count should
leave the total unknown. The existing failure path reports a write error for
this read. Test successive append, invalidation, count failure, discard and
Undo, and a playing episode outside the selected group. `RadioPlaylist` already
carries `podcastCount` into the radio state and `radio_meta` ("%1$d podcasts ·
%2$d episodes") is defined in EN and DE, but neither is drawn, so the tuned
playlist's size is invisible; use both when the count is shown.

### Do not call an empty first page All clear

`RadioProgramme` shows `All clear.` whenever `episodes.isEmpty()` and
`!loading && !loadError`, but `loading` is only true while `refresh` is
`Loading` with an empty snapshot. A fast empty refresh after a slow append, or
a group whose first loaded window is empty while later pages exist, briefly
claims there is nothing left. Gate the empty state on `complete` or on
`episodeCount == 0`, and keep the spinner for a first page that is still on
its way. PR #580 already gives the no-playlist case its own sign and routes;
this is the remaining paging race.

### Replace the last Material buttons on the radio home

`RadioProgramme` renders the paging Retry action and the discard Undo action
with Material `TextButton`, painted with `RadioText`. Their ripple, touch
metrics and font metrics are foreign to the paper tray, while every other
action on the radio is an ivory key or a bat switch. Restyle both as momentary
piano keys (or another radio part) without changing the test tags
(`radio_retry`, `radio_undo`), the focus hand-off after a discard, or the
single-line error/removal rows. Check the row height at compact sizes and at
1.3× text before choosing between `MomentaryKey` and `ActionKey`.

### Let the radio play subscriptions without a group

PLAN R2.3 says two virtual feeds always exist, All and Ungrouped. The radio
offers only real groups, and `RadioViewModel.start()` returns early without
one, so a subscribed but ungrouped podcast cannot be played from the only full
player. All survives only behind `Route.SelectFeed(null)` from notifications,
which opens the legacy `FeedBrowserKey` screen. Owner decision: add an "All
podcasts" station first in the chooser, backed by `FeedSource.All` (it honours
`includeInAll`), so subscribing is enough to start listening while groups stay
the curated inboxes. If accepted, test a library with no groups, a podcast
excluded from All, and a switch from All to a group and back.

### Bring Android and desktop playback into parity

A LongCat review of `playback/*` found these differences between the two
players. Two were verified against the code and design 06 and have fixes in
review: a desktop enclosure answering 403 now asks for a password
([#588](https://github.com/L-K-M/Neutrodyne/pull/588)), and Android marks an
outgoing episode in its near-end window played on a new start, as the desktop
does ([#593](https://github.com/L-K-M/Neutrodyne/pull/593)). Verify the rest
against the code before fixing each with a regression
test in `playback/impl` (Robolectric harness in `LifecycleHarness.kt`) or
`playback/desktop` (`DesktopPlaybackTimingTest`).

- Android does not re-prepare an errored current item. After an untyped
  ExoPlayer error, an exhausted audio retry or an unplayable last item, Play
  reaches `StartOutcome.AlreadyCurrent` and calls `play()` in `STATE_IDLE`, so
  nothing resumes. The desktop re-prepares (`DesktopPlaybackController.startLocked`).
  See `AndroidServiceBridge.kt:296-309` and `ErrorRecovery.kt:306-330`.
- The desktop's Previous within three seconds (`PreviousAction.GoBack`) passes
  0 to `projectStart`, so the previous episode restarts instead of resuming at
  its stored position as on Android (`DesktopPlaybackController.kt:811`).
- The desktop persists a user seek before the engine confirms it
  (`DesktopPlaybackController.kt:745`). A seek during a network drop stores a
  position that was never heard.
- The desktop never applies smart resume: `PlayStarter.start` gets the default
  `SmartResumePolicy.DISABLED` and no `StartPositionRule` call passes
  `smartResume`, although the synced setting defaults on.
- Per-podcast volume boost is a no-op: `BoostLimiterProcessor.onConfigure`
  returns `NOT_SET`, so Media3 skips it, and `boostDb` never reaches
  `EffectivePlayback` or the desktop `GainStage` (`PlayerFactory.kt:178`).
- Per-podcast intro and outro trim (`introSkipMs`, `outroSkipMs`) are stored,
  merged and backed up but never applied.
- Android's explicit start does not record the outgoing item in
  `PlaybackHistory`, so Previous after tapping another episode skips it
  (`QueueProjector.kt:629`; the desktop records it).
- A failed commit in the desktop's `advance` leaves `engineEndedHandled`
  latched, so playback stops and never advances (`DesktopPlaybackController.kt:846-906`).
- `EffectivePlaybackSettings.prepareWindow` issues one scope query and two
  global reads per podcast on every projection and clears its cache each time.
  Add a batch resolver like `notifications()`.
- `QueueProjector.onPositionDiscontinuity` mints a new `loadId` before its
  late-echo guard, so a "Marked played on ..." notice for the first occurrence
  can be dropped (`QueueProjector.kt:583-590`).

## Cabinet, layout, and accessibility

### Make the cabinet fit the useful viewport

`RadioScreen.RadioCabinet` sizes compact and short variants from window height
while sizing the deck from local constraints. In the narrow desktop fixture
the transport and deck leave roughly one full slip. The small layout permits
nested vertical scrolling once the cabinet exceeds its viewport. Keep the
valves and selection visible, but measure the usable space inside the insets
and define a minimum for the slip list. Reduce the speaker opening and panel
padding first, then use a more compact arrangement of the dial and transport.
Choose an intentional scroll mode if the total still cannot fit. Test 600 ×
480, 800 × 500, 1200 × 800, and the phone and tabletop presets at 1.0, 1.3, and
2.0 font scales. Include a two-line title, retry alongside Undo, and enough
episodes to require scrolling. Count whole usable rows and exercise focus at
the bottom of the cabinet.

Exercise both the 660 and 800 dp thresholds. An 844 dp window with 72 dp of
system insets has 772 dp of content but still selects the tall player. Compare
gesture and three-button navigation and a root banner. Verify the deck,
focused slip and play switch remain reachable during each transition.

### Give the playlist sign room and a legible fallback

`RadioProgramme` reserves an invisible key's worth of width to center the
sign opposite Menu. The 320 × 568 and 1.3× text desktop presets ellipsize even
“Morning rotation.” Keep the enamel treatment. Align the sign to the paper
tray's reading column or move Menu to a separate corner when the measured sign
cannot fit. Show the complete name in the chooser and expose it through desktop
hover or focus when clipped. Compare English and German, two long names with
the same prefix, and 320 dp plus 600 dp widths.

### Add a usable Mac programme scrollbar

The home list in `RadioProgramme` lacks the scrollbar drawn by `FeedsScreen`
and other scrolling destinations. In a short window, a full row can fill the
paper tray with little indication of more material below. The current shared
`NdScrollbar` overlay draws a thumb without drag support. Its lazy-list branch
also hides the thumb when all rows are even partly visible. Two open fixes cover
the draw exception below the minimum thumb height. Exercise the 12 dp
case with a list, grid and ordinary scroll region before choosing one.
[Short-track fix](https://github.com/L-K-M/Neutrodyne/pull/570),
[shared thumb bounds](https://github.com/L-K-M/Neutrodyne/pull/569)

Address the partial-row
case before wiring the overlay into the radio, or provide a draggable
desktop scrollbar in the existing paper-tray gutter. Compare the treatments
at 700 and 1200 dp. Check a long list, one
clipped row, a tray shorter than the thumb minimum, and a fully fitting list.
Test wheel and trackpad movement, and keep the play target unobstructed.
The loaded window is the scrollbar's extent until pagination supplies more
rows.

### Establish a material and type hierarchy

The desktop radio renders draw several borders, screws, wood grain, glass
highlights, and small mono labels into the same few hundred pixels. Use a quiet
paper slip under readable ink, crisp sans-serif metadata, and retain mono for
the dial, counters, and short engraved terms. Keep a single direct light
direction across the faceplates, flatten seams where the two trays meet, and
check the reduced-contrast engraved labels on warm paper and charcoal. Start
with the radio home and one shared page. Compare normal and selected slips,
hover, keyboard focus, missing cover art, and Retina plus 1× rendering before
changing the shared components.

The home installs its own light scheme in `RadioScreen`. Many physical legends
draw fixed ink even when `NeutrodyneTheme` honors the system contrast request.
Sample the 11 sp metadata and 10 sp timeline against the lightest and darkest
grain under the letters. Try darker ink and less grain under small type in
increased-contrast mode. Compare German labels and transport status as well as
selection and focus.

Measured contrast to start from: inactive Nixie digits are 2.0:1 against the
glass, the failed-downloads lamp 3.95:1, and the Library's New group drawer
label (Cream on wood) about 3.1:1. The selected slip is a `0x22E4BF71` tint
that is hard to tell from its neighbours at a glance; try a brass edge marker
or a printed pointer before a stronger tint. When a playlist is empty or fails
to load, the tray shows only a small strip at its foot
(`RadioProgramme.kt:238-250`); give that state the paper's full height.

### Replace the last Material surfaces on radio pages

These still use Material shapes on the radio's materials: the Library's "No
groups yet" icon and pill, Discover preview's Subscribe and + New group pills,
the podcast page's + Group chip and More link, the speed sheet's chips, slider
and segmented button, the Up next and Downloads text buttons, the Menu plate's
flat tan Close key, and the light drag-and-drop overlay on the charcoal stage.
The podcast page header has no enamel title, and Subscribed is a dot and text
where the mockup shows a toggle. In the group editor the member check badge
overlaps cover lettering. Settings slip titles break mid-word at about 290 px
("Playb/ack") because the trailing value is unconstrained (`SettingsSlips`);
give the title priority and ellipsize the value. Replace each with the existing
keys, switches and signs, and compare goldens at 390 and 1200 dp.

### Close the remaining accessibility and localisation gaps

Radio slips and chooser rows expose no role. The Sync link-method selector
has no selected or radio-button semantics. German lacks the crash-dialog strings
(`app/src/main/res/values-de`). `downloadSize` hard-codes the decimal point
and unit words, and the queue position "01 / 12" is assembled in code. On
Android, edge-to-edge with `adjustResize` leaves `WindowInsets.ime` unconsumed
(no `imePadding` anywhere), so the keyboard covers paper fields low on the
page (sync setup, credentials, group editor) although design 08 asks for
`imePadding()`. Test each with TalkBack, VoiceOver and the German locale.

### Measure switch hit areas in dense panels

`RadioSwitch` requests 30 × 40 dp for the painted plate and passes the same
modifier to the interactive `BatToggle`. A probe on this Compose version found
that `Modifier.minimumInteractiveComponentSize()` does not widen the pointer
region here (a click at (46, 46) inside a 30 dp node did not fire), so meeting
the target needs a real layout change rather than the stock modifier.

Measure actual hit regions between adjacent controls in Import, Groups and
Playback speed before sizing the surrounding panel. For standalone switches,
reserve a distinct 48 × 48 dp area around the current plate — a centred plate
in a 48 dp box, not a larger plate, unless the mockups say otherwise. Verify
corner taps, lever flicks, disabled controls and vertical scrolling at 2× text.
For rows that own their switch action, retain one accessible target.

### Place the Mac window in the display’s usable coordinates

A live AWT probe on this Mac reported primary bounds 1920 × 1080, an identity
display transform, 69 DPI, and top/bottom insets of 30/90.
`WindowIcons.primaryScreen` passes the bounds and physical DPI to
`defaultWindowBounds`, which multiplies by 96/69. This computes a first-window
origin near (736, 351) for a 1200 × 800 cabinet. Its bottom extends beyond the
screen even before accounting for the Dock. The calculation was reproduced
independently of a real window launch.

Verify the Compose-to-AWT position on a clean launch at 1× and Retina 2×, with
the Dock on two edges. Convert from the native usable coordinate space exactly
once. Verify the 600 × 480 minimum and a secondary display with a negative
origin. Check Windows fractional scaling and Linux scaling before sharing the
conversion across platforms.

Design 11's window state is also undelivered: `desktop.window_bounds` should be
written one second after the last move or resize and at quit, and restored
with the 50 % overlap rule (`NeutrodyneWindow.kt:127, 466`). Every launch
returns to 1200 × 800 on the primary display. Build it on the corrected
coordinate conversion, and test a display that is gone at the next launch.

### Make the Mac window feel native

Use `apple.awt.fullWindowContent` and `apple.awt.transparentTitleBar` on the
root pane, a `WindowDraggableArea` for the top band and about 28 dp for the
traffic lights, so the charcoal stage reaches the window top. With
`apple.awt.application.appearance=system`, a light-mode Mac now shows a light
title bar over the charcoal stage. `java.awt.Taskbar` offers a Dock menu (Play
or Pause, skip back and forward, the current title) and an opt-in icon badge
(unplayed in the tuned playlist); the app uses neither. Window > Minimise lacks
Cmd+M and Zoom lacks Ctrl+Cmd+F. The window title drops the episode as soon as
playback pauses (`NeutrodyneWindow.kt:305`). Navigation state is lost on
relaunch; restoring the last playlist already works through settings. Check
each in full-screen Spaces and with VoiceOver.

### Preserve veneer proportions during live resize

RadioMaterials stretches the cabinet wood image with FillBounds while the
plinth uses Crop. Capture one cabinet at portrait, ordinary Mac window and
ultrawide sizes at both display scales. Compare the existing veneer with an
aspect-preserving crop anchored at the same corner, keeping the bezel,
lighting and screws fixed. Select a treatment only if the grain holds its
proportions without an obvious seam or a distracting jump during live
resize.
[RadioMaterials.kt](core/designsystem/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/designsystem/radio/RadioMaterials.kt)

## Confirmed small defects

- **A swipe that starts on a slip's play key can discard it.** The horizontal
  drag detector is attached to the whole `MaterialPlate`, including the 48 dp
  play key, which is the most tapped target on the slip. A horizontal drag
  that begins on the key consumes it and commits a discard. Restrict the
  gesture to the cover and text, or ignore drags that begin on the key.
- **`RadioInsert` restores no focus target after process death.** The saver
  keeps `chooserOpen` and `playlistId` but not `restoreFocus`, so a restored
  chooser opens with no focused row. Cheap to save.
- **`RadioInsert` positioning is inset-sensitive.** It places the paper from
  the faceplate's `boundsInWindow` and a hand-computed dialog inset; re-check
  it on a wide pane row and with different dialog insets.
- **The Menu key label can wrap and un-centre the sign.** `SmallRadioKey`'s
  label has no `maxLines`, and `RadioProgramme` reserves a fixed
  `radioKeyWidth()` spacer to centre the enamel sign; a long translation that
  wraps grows the column and shifts the sign.
- **The home's Menu key has no click feedback.** `SmallRadioKey` uses a plain
  `clickable` while the EnamelSelector beside it sounds; when the control
  sounds land, give the key the same press.

## Performance measurements

### Measure the work done by playback ticks

`RadioViewModel` republishes the whole `RadioState` on position ticks, and
`RadioRoute` forwards it through the cabinet. Separately,
`RadioPlayer` looks up selection and the visible count with list scans.
Profile a seeded 10,000-row station while playing, inertially scrolling,
and resizing on a Retina screen. Attribute work to composition, layout, asset
rasterization, and playback separately. If tick invalidation is material,
read positions in the dial and Nixie leaves, keep the list inputs stable,
and cache ID lookups by loaded snapshot. Compare p95 frame time, input latency,
composition counts, and idle CPU against the baseline. Measure with and without
artwork cached. Separate programme recomposition from needle and digit draws.
Hold window size, episode and playback interval constant.
[Radio route](feature/feeds/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/feeds/RadioRoute.kt)

The one-second desktop ticker also calls `DesktopPlaybackController.publish()`,
and `PlayerViewModel.position` switches its tick subscription on each new
`nowPlaying` value. Count subscriptions and transport-state emissions. Run the
same seeded fling paused and playing on an Android reference device as well as
the Mac. The existing opening journey misses this workload. Preserve terminal
state reconciliation, position saving and paused browse seeks.

One cause is visible in code: `RadioRoute` rebuilds `RadioActions` on every
tick with a `loadEpisode` lambda that captures `state`, so the actions are
never equal and every visible `RadioSlip` (a `BoxWithConstraints`
subcomposition, a `pointerInput` and a `CoverArt`) recomposes each second while
playing. A composition-count test can show it before and after moving elapsed
time into a `State` read only by the needle and Nixie, remembering the actions
with `rememberUpdatedState` for captured values, and adding `contentType` to
the slip list. Desktop CPU while playing measured 2 to 7 % on a Mac Studio, so
the cost matters most on low-end Android.

### Keep download progress and static drawing off the hot path

Download progress recomposes the whole Downloads screen; pass the live
progress `State` down and read it per row, as `PodcastScreen.kt:483` does. The
Downloads overview is recomputed by a 30 s polling pulse, and the engine's
queue scan loads every download row on each loop (`DownloadControllerImpl.kt:356`,
`DownloadEngine.kt:312`). The faceplate and paper card decode both the wide
and the tall artwork although only one is drawn (`RadioPanels.kt:44, 61`).
`recess`, `radioGlass`, `cabinetEdges` and the needle allocate `Path` and
`Brush` per draw, and static tick scales redraw every frame (`TuningDial.kt:93`,
`RadioPlayer.kt:393`); cache them with `drawWithCache`. A control-sounds branch
rewrote every sound WAV to `cacheDir` on each cold Android start; if sounds
land, write them once per version. Measure before and after each change.

### Profile rendering and paging while scrolling

Compare individual and split `RadioCabinetPage` trays for duplicate lid
drawing, texture scaling, and repeated clips at Retina resolution. Trace
`RadioRoute.onRows` mapping the paging snapshot and the list's per-item
`radioPageIndex` lookup during rapid scrolling and station changes. Measure
frame time, GC, RSS, and appended pages with a cold and warm artwork cache.
Cache static drawing or index snapshots only where profiling shows work.
Keep episode keys and the identity guard between the rendered rows and the
paging snapshot. Verify output and pagination after rapid station switches.

The feed pages by 40 with a maximum loaded size of 400. Time
`loadedIds.indexOf` against that window before adding a lookup table. Retain
leading placeholder offsets and ignore IDs missing from the latest snapshot.

`RadioRoute` rebuilds the entire `RadioEpisode` presentation list on every
paging emission and copies it into `RadioState`, so one append recomposes the
whole cabinet even though `LazyColumn` keys isolate unchanged rows. Map only
the diff or keep the mapping behind the snapshot identity. `Speaker` redraws
its fixed grille pattern on every invalidation; a cached draw is safe because
only the size is an input.

Two more cheap wins to measure first:

- `feeds.countUnplayed` is launched for every incomplete paging snapshot,
  guarded by `rowsRevision`. While a long playlist streams pages in, that is
  one count query per page; count once per generation, or only when the
  visible window nears the end.
- Every nine-patch and three-patch slice draws with `FilterQuality.High`,
  including the small slices of every key on every slip. A fling composes
  hundreds of slips; try `FilterQuality.Low` for slices under a few pixels and
  measure the difference at Retina.

### Make desktop screenshot failures reproducible

On this Mac the focused Roborazzi verification of `RadioScreenshotTest.presets`
failed at `radio_phone.png` on clean main and on the accessibility change. The
two freshly rendered phone PNGs had identical SHA-256 digests.

Compare the main render on the reference CI OS against the committed PNG. Record
its JDK, rasterizer, density, fonts and renderer configuration, then decide
whether the host should use its own baseline or run visual acceptance on the
reference OS. Verify all six presets and prove a deliberate layout change still
fails. Preserve the reference until the source of the mismatch is known.

## Listening workflows

### Put useful episode actions beside the programme

`RadioSlip` currently offers selection and Play. The menu keys go to app
destinations. Give a slip a compact context menu for episode notes, Play next,
download where supported, and Mark played at the end. Reuse the episode
navigation and action services behind `EpisodeRow`. Bind every action to the
slip's stable episode ID. Use the shared secondary-click and keyboard gesture
patterns. Verify primary click, Play, secondary click, Shift+F10, and a
touch interaction. Check that Open notes preserves the selected station,
scroll anchor, and active transport on return. Validate the new route and action
catalogue before exposing the menu.

Chapter next and previous are unreachable from the radio, the only full player:
the handlers in `PlayerRoute.kt:134` are dead code. Offer them in the same menu
or on the keyboard when the episode has chapters.

### Preserve the station and scroll position when browsing

Treat switching among stations and visiting a detail page as different from
clearing the receiver. `RadioProgramme` resets scroll to the first item when
the playlist ID changes. Consider a bounded per-station anchor keyed by stable
episode ID and offset. Select the nearest surviving episode after refresh,
discard, or data removal. Keep the playing episode independent of the
browsed station. Test A → B → A, paging behind the anchor, removals, restart,
and Back. Defer a migration until a session-only prototype feels right.

### Make first tuning a short, recoverable journey

A new listener often needs to add a subscription and create their first
group before the radio has anything to tune. Keep group creation in Library.
Provide one route to the existing group workflow and a separate route to
Discover when there are no subscriptions. On macOS, exercise a clean launch,
adding a feed by paste, creating a playlist, and returning to play the first
episode. Repeat with an OPML import, a present but empty playlist, and a
feed whose refresh failed. Keep separate empty and error explanations so a
failed refresh never looks like an empty schedule. The empty-state route keys
from PR #580 cover the no-subscription case; the remaining journey work is the
clean-launch exercise, OPML import and the failed-refresh wording.

A first-tuning card is in review: with no playlist the paper offers keys to
Discover, the import dialog and the Library, and the dial reads NOT TUNED
([first-tuning card](https://github.com/L-K-M/Neutrodyne/pull/583)). Today
the journey from there still takes eight or more steps (Library, Groups, New
group, name, members, save, back). The journey checks above, and the All
station decision under
[Let the radio play subscriptions without a group](#let-the-radio-play-subscriptions-without-a-group),
remain.

### Show how old an episode is and what is new

Slips print no date. For a news inbox, "today" against "three weeks ago"
decides what to keep: print the age like a rubber stamp beside the duration
("18 NOV"). The radio has no "new since last visit" marker; the legacy feed
browser had baselines (`FeedsViewModel.kt:451`, `markVisited`, `lastViewedAt`).
A red NEW stamp on episodes that arrived since the playlist was last tuned
would reuse them. Check both against German dates, 1.3× text and the compact
row.

### Make editing and finding work naturally with a Mac keyboard

`DesktopMenuBar` disables its Cut, Copy, Paste, Undo, and Select All items and
drops app menu accelerators while a Compose field has focus. The context menu
serves text editing. Build a focused-field action bridge only if the
composition can reliably supply enabled states and route to the actual field.
Start with Discover search and verify Cmd+F, Cmd+W, Cmd+, and ordinary text
shortcuts against focus in a field, in a modal, and in the programme. Preserve
the current key ownership for transport, and use the platform clipboard
rather than a new editing abstraction. Avoid dropping keyboard equivalents for
unrelated window commands once the focus bridge is proven.

Exercise read-only selections and password fields as well as normal editing.
Verify the menu's enabled states after focus leaves the window.

Escape is swallowed in text fields: `isRootShortcut` marks it as a root
shortcut and the text-field branch consumes it with no action
(`NeutrodyneRoot.kt:394`, `:659-673`), so in Discover search Escape neither
clears the field nor goes back. The window's Cmd+Q fallback routes through
`onCloseRequest`, which hides to the tray, instead of quitting
(`NeutrodyneWindow.kt:555`); AWT accelerators usually shadow it, but the paths
diverge. Cmd+, may be registered twice on macOS (the app menu's Preferences
handler and Go > Settings); unverified.

### Centralize text-field focus reporting

`.ndTextFieldFocus()` is hand-applied at every call site. Moving it inside
`PaperTextField` would stop the next dialog or search field from silently
reintroducing the shortcut conflict, but the wrapper lives in
`:core:designsystem`, which cannot see the tracker in `:core:ui` without a
circular dependency; raw `OutlinedTextField`s elsewhere would still need
explicit treatment. Do this as its own pass over every text field rather than
halfway, or move the tracker down to the design system first.

### Mark the tuned playlist in the chooser

`RadioInsertDialog` lists groups as plain clickable text rows: nothing marks
the playlist the radio is tuned to, and focus starts on row 0 even when the
active group is elsewhere. Give the active row the selected treatment (a lit
key or lamp beside its name), start keyboard focus on it, and let the screen
reader announce it as selected. This is also the acceptance check recorded
under "Native verification" below ([Tuned playlist indicator](#native-verification-on-proposed-fixes)).
Compare a long group name, a non-first active group, Escape, and returning to
the chooser.

### Identify retained results during a new search

DiscoverViewModel deliberately retains previous hits during the 600 ms
debounce and while the next provider response is pending. DiscoverRoute
leaves those rows actionable. Prototype a compact, accessible pending
indicator alongside the old results, retaining the existing anti-flicker
behavior. The current signal is a Material `LinearProgressIndicator` at the
top of the results and carries no accessible live-region announcement, so a
screen reader user cannot tell that the old hits are stale. With a fake slow
provider, enter two unrelated queries and try to open a result with both
keyboard and pointer. Check whether listeners can correctly identify which
query produced the visible list and whether the final list retains a sensible
focus target.
[DiscoverViewModel.kt](feature/discover/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/discover/DiscoverViewModel.kt)

Discover shows nothing for queries under three characters; add a hint. Its
list shows Apple's episode count (2784) while the preview shows the feed's
(71); label which one each is or show the feed's.

## Library, downloads, and sync defects

### Make listening-page actions do what they show

- "Check again" is offered on unavailable YouTube rows, but every view model
  drops `EpisodeAction.CheckAvailability` (`PodcastViewModel.kt:320`,
  `FeedsViewModel.kt:564`, `EpisodeViewModel.kt:312`).
- A VERIFYING download key looks tappable but does nothing, while RESOLVING
  cancels (`DownloadRequestHandler.toggle`).
- The podcast page's filtered-empty action says "Show played episodes" but
  clears every filter. The page has no episode search, and its filter bank
  scrolls away.
- Downloads rows document swipe to delete; there is none.
- The group editor's "Show as a tab in Feeds" switch reads `showAsTab`, which
  only the legacy feed browser uses now that the home has no tabs. Retire it or
  give it a meaning on the radio.

### Fix download storage limits and notices

`DownloadDao.downloadedEpisodeIds` is not chunked at SQLite's bind limit, so a
large library can throw (`DownloadDao.kt:29`). The storage cap applies only to
automatic downloads; manual downloads ignore it (`DownloadQuota.kt:24-33`).
Android's "SD card is not available" notice has no action, so the setting that
fixes it is unreachable (`DownloadNotices.kt`). Test with 1,500 downloaded
episodes, a cap reached by a manual download, and an ejected card.

### Keep sync and settings drafts and copy intact

Sync setup, Settings > Sync, the "Link another device" sheet and Settings >
Discover keep drafts in `remember`, so rotation loses a typed invite code,
username, server address or country; use `rememberSaveable`. The first-link
choice cards print their counts without plurals ("1 groups"). Sync screens have
no Back key, unlike every other settings page.

### Fill the Android platform surfaces

Android has no app shortcuts and no Quick Settings tile; `settings.gradle.kts:121`
reserves `:feature:widgets` for a v1.x widget. The media notification is the
unbranded Media3 default, and new-episode notifications carry no artwork.
Start with a Play or Pause tile and a shortcut to the last playlist.

## More ideas from the polish pass

### Offer an index for long programmes

The programme is a lazy pager with no way to jump. A radio-appropriate band of
A–Z or date keys along the tray edge, or a tuning-dial style jump strip that
scrolls the `LazyColumn` to the nearest matching sort date, would make
500-episode groups usable. Prototype first: it needs stable item keys and an
ordinal lookup that survives paging windows and station switches.

### Print the queue position as a counter window

`RadioPlayer` prints `NN / NN` as plain mono text beside the status. A small
brass-framed counter window like `StepCounter`'s glass, without the step keys,
would match the dial/Nixie assembly. Purely presentational; keep the complete
count semantics from "Give long playlists a reliable count".

### Show the running sleep timer on its Menu key

The Menu dialog's Sleep key never reflects state. When a timer runs, a small
counter on or beside the key (remaining minutes) would save opening the sheet.
Keep it quiet when off and honour the existing timer controls.

### Small finish notes for the radio face

- The printed needle scale always uses `MM:SS`; above 99 minutes the rightmost
  quarter label ("900:00") is wider than the others and nudges the row. Either
  accept it deliberately or use a compact hours form there too.
- `Nixie` draws a ghost `8` behind every digit; check on Retina whether it greys
  the tubes enough to cost legibility.
- `SkipKey`'s painted cap fills its whole 52 dp target while `SlipPlayKey` keeps
  a 36 dp cap inside 48 dp. A little air between ring and target would match.
- The programme header reserves a full key's width to centre the enamel sign;
  at large font scales that spacer can be what pushes the sign into ellipsis
  (see "Give the playlist sign room").
- The dial needles are drawn on top of the glass sheen: `dialWindow` is a
  `drawBehind` on the parent while `TuningDial`, `NeedleWindow` and
  `StepCounter` draw their needles afterwards, so the needle never sits under
  the reflection. Cosmetic, but it is the difference between glass and a
  printed line.
- `MaterialPlate(METAL)` draws two screws at the top corners only; the rendered
  faceplates carry four. The home's plinth feet are plain `Chassis` rectangles
  with no shadow or recess, so on the dark stage they read as floating bars.

## Prototypes

### Make the Mac useful without the main cabinet open

Prototype a small always-on-top receiver with current title, play, skip,
elapsed time, and an Open Radio action. Drive it from the existing desktop
playback source and the mini-player action semantics. It should never start a
second audio session. Design close and regular-window restoration against the
existing tray and quit behavior. Exercise VoiceOver, keyboard-only use,
full-screen Spaces, and unplugging a display before considering cross-platform
support.

### Offer practical station presets

A few optional enamel preset keys could bring favourite groups within one click
or keypress. Use actual groups and retain the existing chooser for the full
list. Show active state and unplayed count only when useful. Reordering should
be deliberate, with no accidental membership changes. Prototype three presets
on wide screens and evaluate whether they save time during a normal listening
session. Leave keybindings unset until they can coexist with desktop menu
accelerators.

### Use the valves for small, useful moments

The lamp or valve can acknowledge a station change or a finished episode with
a brief warm change. Use the same accessible status text and honor reduced
motion. Keep normal playback static and never introduce button sounds into the
audio. Prototype a single transition and compare idle CPU and distraction over
a listening session.

A warm-up is in review: the filaments brighten over 1.8 s when the radio is
switched on and cool over 0.9 s when it is switched off, static while playing
and instant with reduced motion ([valves warm up](https://github.com/L-K-M/Neutrodyne/pull/592)).
The dial paper's backlight (`dialPaper` gradient) could warm with it for one
gradient parameter.

### Give the signal meter a buffering job

The status lamp now says BUFFERING, but a needle is a better fit for an
uncertain stream: rest it while ready, let it hunt while buffering, and park
it on an issue. Reuse `NeedleGauge` beside the status lamp, drive it from the
same phase, and honour reduced motion. Measure idle CPU; if the needle
animates, it must stop the moment the stream settles. The alternative is a
magic-eye tuning indicator (a 6E5-style green eye, open while playing,
wavering while buffering, closed on error); it needs a rendered part. Pick one
of the two, not both.

### Recall the last tuned station

A small ivory key that returns to the previous playlist is the radio
equivalent of a station preset and costs nothing when unused. Keep it
explicit (a long press or a dedicated key) so it cannot fire by accident, and
remember only within the session until a persistence decision is made.

### Design an evening listening experience

Make an optional player display that dims secondary brass and highlights the
remaining sleep timer close to the Play switch. Use the existing PO-55 cabinet
and materials. Preserve contrast and focus indicators. Keep the timer
controls visible and check that the idle display causes no extra frame
activity. Review the product decision before
implementing a new user preference.

A mechanical sleep timer, a wind-up knob on the faceplate that visibly unwinds
as the timer runs, could replace the sleep dialog there.

### Prototype chapter marks on the tuning scale

Try small index marks along the existing ruler for an episode with chapters.
Show the current chapter name on deliberate pointer or keyboard inspection, and
make it reachable by assistive technology. Use existing chapter times and seek
handling. Compare no chapters, dense short chapters, a very long title and
unknown duration. Keep the plain unchaptered dial, preserve arbitrary seeking
and avoid extra permanent controls. Decide from a full-size and a compact render
whether this earns space. Prototype slightly longer brass ticks, cluster marks
too close for a pointer to resolve, and test late-arriving chapter data with
four and eighty chapters. [PlayerViewModel.kt](feature/player/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/player/PlayerViewModel.kt)

### Fill the speaker grille on wide windows

At 1200 × 800 a grille the size of the slip tray holds nothing. Tuck a paper
"station log" card into its frame: the next three Up next slips, or the tuned
episode's show-notes summary. Compare against the plain grille at 700 and
1200 dp, and keep the card out of the layout when the grille is too small.

### Try small mechanical responses

Each must respect reduced motion and add no per-frame work while idle.

- The needle travels to a newly selected episode's position with a short
  spring instead of jumping.
- Haptics beyond the switches and the discard threshold
  ([haptics](https://github.com/L-K-M/Neutrodyne/pull/586)): a `SegmentTick` on
  the ivory keys, and light ticks when scrubbing across chapter marks or whole
  minutes.
- A short burst of tuning static while the station changes, once control
  sounds exist, with the needle sweeping the scale.
- A sign-off when the last slip of a playlist is discarded: the Nixie reads
  `--:--`, the valves cool, and the paper says the station has closed for the
  night, with keys to another station or Discover.
- A paper spike in the tray corner that stacks discarded slips; tapping it
  recalls the last one, as Undo does.

## Owner decisions: documentation the code contradicts

Each needs a decision: build it, or correct the document.

- `privacy.crash_reports` and its Privacy-page switch are documented (09 and
  08) but not registered, read or rendered.
- The group media filter and "hide older than N days" are documented, but no
  UI sets them (`FeedRepository.setHideOlderThanDays` has no production
  caller).
- The Android in-app language picker exists only in tests
  (`BuildInfo.shippedLocales`); the desktop Language row and the Windows Access
  Bridge row are missing.
- Podcast and group settings document a Playback section (speed, skip silence)
  that is not rendered.
- Settings > Feeds documents "Show Ungrouped tab" and "Manage groups". The
  Library overflow lacks "Backup and restore". About and Privacy lack a
  "Privacy policy" row. 05's "Import from URL" is missing.
- `CHANGELOG.md` still reads `[Unreleased]` with only M0a.1 at version 1.0.0.
- Transcripts are parsed and stored but never shown; the documents defer them.
- Not documented anywhere, an idea: OPML auto-sync from a remote list.

## Checked and cleared

- **Elapsed time after a seek.** A suspected freeze (position ticks suppressed
  for an episode present in `RadioViewModel.seeks`) does not exist: `seek()`
  populates `seeks` only in the not-loaded branch, so a loaded needle seek or
  skip keeps receiving ticks. Two exploratory regression tests (tick after
  seek; resume after a paused seek) passed on clean `origin/main`.
- **Update-dot announcement.** The radio Menu key's badge description merges
  with its label (`mergeDescendants=true`), so it does not double-announce.
- **Focused-field disposal.** `TextFieldFocusTest` already proves a focused
  field leaving composition releases the shortcut stand-down.
- **Stock touch-target expansion.** `Modifier.minimumInteractiveComponentSize()`
  did not widen the pointer region in a desktop probe (see "Measure switch hit
  areas"), so do not assume it satisfies the 48 dp rule here.
- **Claims refuted in the third pass.** Each of these was reported and is
  false. `onRows` re-emits identical state (StateFlow conflates it). The Nixie
  caption uppercases a Turkish i wrongly. A deep link is lost on process death
  (the navigation stack restores it). The RTL swipe is broken (the offset is
  RTL-aware). Up next headings are spoken uppercase. A podcast description
  collapses on re-emission. The faceplate header ignores RTL (`placeRelative`
  mirrors it). Go menu items push top-level keys onto the current stack
  (`navigateFromPlayer` selects tabs for `TopLevelKey`). The macOS tray never
  appears. Completed downloads with a null size are marked missing. A zero-byte
  temp file skips adoption. The host-permit map grows without bound. Manual
  refresh ignores the interval (intended).

## Native verification on proposed fixes

- Scrub a streaming and a downloaded episode on macOS. Check pointer
  cancellation, playback resumption, and the number of engine seeks and
  writes. Include a duration correction while dragging. Compare the two proposals
  before choosing one. [Seek on release](https://github.com/L-K-M/Neutrodyne/pull/565),
  [receiver drag and accessibility tests](https://github.com/L-K-M/Neutrodyne/pull/571)
- Invoke Mark played from VoiceOver and TalkBack, then check Undo and its
  return focus. Verify the desktop keyboard path with Delete, including on a
  compact Mac keyboard. [Programme slip action](https://github.com/L-K-M/Neutrodyne/pull/566)
  Compare the two proposed implementations before integrating and retain one
  localized action per slip. [Accessible episode discard](https://github.com/L-K-M/Neutrodyne/pull/563)
- Verify the printed quarter times against needle position and seeking on an RTL
  device, including a left-edge tap. [RTL time scale](https://github.com/L-K-M/Neutrodyne/pull/567)
- Open the paper playlist insert with a non-first group active. Check that
  VoiceOver announces the group as selected and that keyboard focus starts
  on its checked row. Try a long group name and return with Escape.
  [Tuned playlist indicator](https://github.com/L-K-M/Neutrodyne/pull/568)
- Launch the desktop app ten times from a fresh data directory on a slow disk
  and confirm it never exits before the window; Refresh and Cmd+R do nothing
  until the library opens, then refresh once.
  [Deferred refresh controller](https://github.com/L-K-M/Neutrodyne/pull/581)
- In Discover, search "Star Wars: The Clone Wars", "AC/DC" and "Dr. Death",
  then paste an address from a web page with a no-break space or curly quotes
  around it and confirm it never reaches the directory.
  [Programme names search](https://github.com/L-K-M/Neutrodyne/pull/582)
- On a fresh install, follow the first-tuning card's Discover, Import and
  Library keys, return, and confirm the card gives way once a group exists.
  Start an episode from a notification with no playlists and check that the
  dial shows its status, not NOT TUNED.
  [First-tuning card](https://github.com/L-K-M/Neutrodyne/pull/583)
- With VoiceOver and TalkBack, move by headings through Library, Settings and
  the Menu plate, open a Diagnostics section from its header, and retune the
  Library dial with reduced motion on.
  [Headings and toggles](https://github.com/L-K-M/Neutrodyne/pull/584)
- Compare the mini radio's needle window and a downloading row at 1× and 2×
  against the bezel art.
  [Thin dial windows](https://github.com/L-K-M/Neutrodyne/pull/585)
- On a phone with touch feedback on and then off, throw the radio switch and a
  settings switch, and swipe a slip past the discard threshold; let playback
  stop on its own and confirm nothing buzzes.
  [Haptics](https://github.com/L-K-M/Neutrodyne/pull/586)
- Fill a device's storage until the library cannot open and use Manage storage
  on a Pixel and an OEM build.
  [Manage storage guard](https://github.com/L-K-M/Neutrodyne/pull/587)
- Play a password-protected feed whose enclosure answers 403 on the desktop
  and confirm the Enter password action appears.
  [Forbidden enclosure](https://github.com/L-K-M/Neutrodyne/pull/588)
- Drag the needle on a slip that is not loaded, start that episode from Up
  next, and confirm the Nixie follows playback and the radio's ON resumes
  rather than restarting.
  [Parked seek](https://github.com/L-K-M/Neutrodyne/pull/589)
- Switch the radio on and off on a phone and a Retina Mac and watch the valves
  warm and cool; repeat with reduced motion and check idle CPU while playing.
  [Valves warm up](https://github.com/L-K-M/Neutrodyne/pull/592)
- Link a second device against a server and read the Merge card, the address
  field's label and an update card's size in English and German.
  [Sync and update copy](https://github.com/L-K-M/Neutrodyne/pull/590)
- With TalkBack and VoiceOver in English and German, focus the radio's counter
  at 1:01:01 and after 12 hours and listen for plural forms without zero parts.
  [Spoken elapsed time](https://github.com/L-K-M/Neutrodyne/pull/591)
- On a phone, play an episode to its last half minute, tap another episode,
  and confirm the first one shows as played on Android as on the desktop.
  [New start marks played](https://github.com/L-K-M/Neutrodyne/pull/593)
