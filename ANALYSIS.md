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

## Done since the last consolidation

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

### Make buffering and errors intelligible on the radio

`RadioPlayer` distinguishes cleared, ready, playing, and paused. The transport
can also be waiting for a stream, retrying, or blocked on an error. Extend the
presentation using the existing playback snapshot and event bridge. Add text
next to the lamp and a route to the existing recovery action where applicable.
Keep Cancel available while buffering. Test a delayed stream, metered consent,
engine refusal, replay, and a switch from a failing item to a healthy one. Avoid
inventing a new source of playback truth in the UI.

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
Undo, and a playing episode outside the selected group.

### Replace the last Material buttons on the radio home

`RadioProgramme` renders the paging Retry action and the discard Undo action
with Material `TextButton`, painted with `RadioText`. Their ripple, touch
metrics and font metrics are foreign to the paper tray, while every other
action on the radio is an ivory key or a bat switch. Restyle both as momentary
piano keys (or another radio part) without changing the test tags
(`radio_retry`, `radio_undo`), the focus hand-off after a discard, or the
single-line error/removal rows. Check the row height at compact sizes and at
1.3× text before choosing between `MomentaryKey` and `ActionKey`.

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

### Preserve veneer proportions during live resize

RadioMaterials stretches the cabinet wood image with FillBounds while the
plinth uses Crop. Capture one cabinet at portrait, ordinary Mac window and
ultrawide sizes at both display scales. Compare the existing veneer with an
aspect-preserving crop anchored at the same corner, keeping the bezel,
lighting and screws fixed. Select a treatment only if the grain holds its
proportions without an obvious seam or a distracting jump during live
resize.
[RadioMaterials.kt](core/designsystem/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/designsystem/radio/RadioMaterials.kt)

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

### Design an evening listening experience

Make an optional player display that dims secondary brass and highlights the
remaining sleep timer close to the Play switch. Use the existing PO-55 cabinet
and materials. Preserve contrast and focus indicators. Keep the timer
controls visible and check that the idle display causes no extra frame
activity. Review the product decision before
implementing a new user preference.

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
