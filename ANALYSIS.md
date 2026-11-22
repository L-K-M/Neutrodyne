# Neutrodyne design backlog

This is the open design and engineering backlog for the radio home, the shared
radio parts and the macOS experience. It is the document a future agent should
read before picking work. [Product plan](docs/PLAN.md) is the source of truth for
requirements and decisions; [radio design](docs/design/08-ui-ux.md#radio-home)
elaborates the cabinet. Keep the material and control vocabulary from PO-55: the
walnut cabinet, enamel signs, paper and tactile controls distinguish navigation,
programme and transport. Native window and menu behaviour matters alongside the
shared cabinet.

Review history: a first pass on `f9bd91021`, a polish pass on `a9b0e114f`, a
native-verification pass on 2026-11-18, a fourth source pass on `ad8dc5399`, and
an independent pass on `c542b073e` (2026-11-22) which is the current head. The
2026-11-22 pass re-read the radio, feeds adapter, desktop shell, sync server and
download data layer; its findings are folded in below and marked **new** where
earlier passes had not recorded them. A further independent pass on the same
head verified which earlier items were already fixed and added the feed-cache
batching, DNS-pinning, credential-clock and unaddressable-key items, also
marked **new** below. A second independent pass on the same head confirmed the
window-placement AWT values and the Compose desktop-density source, and opened
the five fixes listed under Done since the last consolidation; its review is
consolidated here without losing the earlier passes' detail.
A third pass on the same head verified the placement live (the first window was
clamped into the bottom-right of the usable area) and opened the window-placement
and window-bounds PRs listed under Done since the last consolidation.
An Opus 5.5 pass
on the same head (2026-11-22) ran the desktop app from source against an
isolated home with eight generated podcasts on loopback, at 600 x 480 to
1600 x 900 and at 1x and Retina scale, read the radio, window, playback and sync
code by hand, and verified helper audits of every subsystem against the code.
Its findings are folded in below with their working ids in brackets (L-, V-, M-,
Q-, A-, DP-, SY-, DA-, DL-, LU-, AS-, DI-, SE-, P-, SERVER-); it opened the fixes
listed under Done. **REPORTED** marks a helper's trace that nobody has re-read;
re-read or reproduce those first.

## Done since the last consolidation

- **The home's compact and short layouts follow the usable height (PR #578).**
  `radioLayout(width, usableHeight, fontScale)` is pure and unit-tested.
- **The home Menu key is announced as a button (PR #579).**
- **Transport status while buffering, ended or failed (PR #575).** The radio
  prints BUFFERING, ENDED or PLAYBACK ERROR from the loaded player's phase, with
  the pilot lamp amber while buffering and red on an issue.
- **Arrow-key tuning (PR #576).** Up and Down move the selection, scroll it into
  view and carry focus.
- **Global shortcuts stood down in sync fields (PR #577).**
- **An empty radio offers its filling routes (PR #580), with Import and the
  NOT TUNED dial (PR #583).**
- **The third pass's fixes are merged.** PRs #581-#597 and #599 cover the
  deferred desktop refresh, programme-name search, empty-radio tuning, headings
  and roles, thin dial windows, haptics, the forbidden-enclosure password prompt,
  parked seeks, valve warm-up, sync and update copy, spoken elapsed time, new-start
  played marks, retained Discover results, the unknown-duration dial and desktop
  Previous. The earlier "awaiting merge" note is stale.
- **Three small fixes from the 2026-11-22 pass are in review:**
  [#609](https://github.com/L-K-M/Neutrodyne/pull/609) arrow-key tuning no longer
  jumps the list for a slip that is already visible and reveals a half-visible
  slip at the nearest edge;
  [#610](https://github.com/L-K-M/Neutrodyne/pull/610) the Menu key label is
  capped at one line (the key-press sound itself landed on main);
  [#612](https://github.com/L-K-M/Neutrodyne/pull/612) the chooser saver keeps
  the focus hand-off and reads old state defensively.
- **The empty-programme spinner gate from the same pass landed on main** (a
  partial page keeps the spinner, a failed page keeps its retry row); the
  follow-up branch [#611](https://github.com/L-K-M/Neutrodyne/pull/611) was
  closed as superseded.
- **Three more small fixes from the independent pass are merged:**
  [#619](https://github.com/L-K-M/Neutrodyne/pull/619) binds the macOS Window
  menu's Minimise and Zoom to Cmd+M and Ctrl+Cmd+F;
  [#622](https://github.com/L-K-M/Neutrodyne/pull/622) chunks
  `DownloadDao.downloadedEpisodeIds` at the 500-row download bind size;
  [#627](https://github.com/L-K-M/Neutrodyne/pull/627) prefers the programme
  row's metadata over a stale player snapshot for the selected episode.
- **The chooser marks the tuned playlist and focuses it** (`cec78f017`), and
  **Settings slip values yield to the title's longest word** (#562). The older
  "Mark the tuned playlist in the chooser" and mid-word title sections are
  done.
- **Three small fixes from a further pass are in review:**
  [#623](https://github.com/L-K-M/Neutrodyne/pull/623) a VERIFYING download's
  row key cancels like RESOLVING instead of being a dead tap;
  [#624](https://github.com/L-K-M/Neutrodyne/pull/624) the podcast
  filtered-empty action reads "Clear filters" unless the sole active filter is
  the unplayed one, matching Feeds;
  [#625](https://github.com/L-K-M/Neutrodyne/pull/625) link-code polling stops
  at the code lifetime instead of polling forever when the server keeps
  answering `authorization_pending`.
- **Five more small fixes from a second independent pass are in review:**
  [#615](https://github.com/L-K-M/Neutrodyne/pull/615) Escape goes back from a
  focused text field instead of being consumed with no action;
  [#618](https://github.com/L-K-M/Neutrodyne/pull/618) applies a paging
  snapshot that arrives during Undo;
  [#628](https://github.com/L-K-M/Neutrodyne/pull/628) keeps a swipe that
  starts on a slip's play key from discarding it;
  [#629](https://github.com/L-K-M/Neutrodyne/pull/629) gives a programme slip a
  button role;
  [#630](https://github.com/L-K-M/Neutrodyne/pull/630) letters a sign whose name
  ends in a space at its full size.
- **The first desktop window is centred on the display (in review).**
  [#614](https://github.com/L-K-M/Neutrodyne/pull/614) converts
  `GraphicsConfiguration.bounds` from device space with the inverse default
  transform and drops the `96 / physical DPI` multiplication. Verified live on a
  1920 x 1080 Mac with 30/90 insets: the window is now at `(360, 140)` instead of
  the clamped `(720, 190)`. A noninvertible transform returns null (the default
  placement) and logs at warn.
- **The desktop window's last bounds are restored (in review).**
  [#633](https://github.com/L-K-M/Neutrodyne/pull/633) adds
  `desktop.window_bounds`, a validated codec, the 50% overlap restore rule, a
  one-second debounced writer and a clean-shutdown write. Verified live: a window
  moved to `(200, 120, 1000, 700)` reopened there.
- **The sign trailing-space fix is duplicated.** [#630](https://github.com/L-K-M/Neutrodyne/pull/630)
  already compares against the trimmed name; #634 was opened from the same pass
  and closed as a duplicate.
- **The independent 2026-11-22 pass's fixes are merged:** #613 gates the
  radio's All clear sign on finished paging (an empty first window with an
  append pending no longer claims the station is cleared), and #616 gives the
  home Menu key its `KEY_PRESS` sound per PO-56. In review from the same pass:
  #617 remembers the desktop's `failedDownloads` flow, #620 reserves a 48 x 48
  dp hit target around `RadioSwitch`'s unchanged plate, #621 letters a sign
  whose name ends in a space at full size (a duplicate of #630 from another
  pass), and #626 shows the localized unplayed count on the dial while paging
  and leaves the total unchanged when the count query fails.

- **Fixes from the Opus 5.5 pass are in review** (each test-first, red then
  green locally, reviewed by an independent model before the PR; links below).
  Main fails `spotlessKotlinCheck` since b2e7215c8, which dropped the blank line
  before `RadioState`'s selected-episode KDoc, so every PR's `static` job fails
  on that file. Each of these PRs carries the identical one-line fix as its own
  commit, which merges cleanly however many land.
  - [#632](https://github.com/L-K-M/Neutrodyne/pull/632) Show the saved position of a restored session.
  - [#635](https://github.com/L-K-M/Neutrodyne/pull/635) Seek a restored desktop session before Play.
  - [#636](https://github.com/L-K-M/Neutrodyne/pull/636) Make the Mac delete key remove list items.
  - [#637](https://github.com/L-K-M/Neutrodyne/pull/637) Stop feed refreshes waiting out the batch deadline.
  - [#638](https://github.com/L-K-M/Neutrodyne/pull/638) Count each shortened import group name once.
  - [#639](https://github.com/L-K-M/Neutrodyne/pull/639) Inset show notes to the episode page's margin.
  - [#640](https://github.com/L-K-M/Neutrodyne/pull/640) Keep the radio's playlist pagers across visits.
  - [#641](https://github.com/L-K-M/Neutrodyne/pull/641) Add Backup and restore to Library's More menu.
  - [#642](https://github.com/L-K-M/Neutrodyne/pull/642) Toggle playback with Space on slips and the bar.
  - [#643](https://github.com/L-K-M/Neutrodyne/pull/643) Keep the valves lit while the radio's switch is on.
  - [#644](https://github.com/L-K-M/Neutrodyne/pull/644) Show the keyboard shortcuts card instead of crashing.
  - [#645](https://github.com/L-K-M/Neutrodyne/pull/645) Quit on Ctrl/Cmd+Q instead of closing the window.
  - [#646](https://github.com/L-K-M/Neutrodyne/pull/646) Retry a desktop advance that committed nothing.
  - [#647](https://github.com/L-K-M/Neutrodyne/pull/647) Hide the mini player under sheets on the radio home.
  - [#648](https://github.com/L-K-M/Neutrodyne/pull/648) Screen NAT64 addresses by their embedded IPv4.
  - [#649](https://github.com/L-K-M/Neutrodyne/pull/649) Keep the desktop window's minimum at 600 x 480.
  - [#650](https://github.com/L-K-M/Neutrodyne/pull/650) Apply a remote member reorder to an existing member.
  - [#651](https://github.com/L-K-M/Neutrodyne/pull/651) Show the needle in the mini radio's dial window.
  - [#652](https://github.com/L-K-M/Neutrodyne/pull/652) Open YouTube settings from the breaker notification.
  - [#653](https://github.com/L-K-M/Neutrodyne/pull/653) Translate the Android playback actions into German.
  - [#654](https://github.com/L-K-M/Neutrodyne/pull/654) Take up a synced position on a headset resume.
  - [#655](https://github.com/L-K-M/Neutrodyne/pull/655) Push an undone group delete as the restored group.
  - [#656](https://github.com/L-K-M/Neutrodyne/pull/656) Stop at the end of the episode on the desktop.

## Playback and programme correctness

### Reconcile paging during Undo and clarify repeated discards

The discard race has a tested fix in (#564, merged). During Undo, `onRows` still
defers a paging snapshot;
[#618](https://github.com/L-K-M/Neutrodyne/pull/618) applies it after a
successful un-play write and re-inserts the restored row when the newer page does
not carry it. Complete the failure path too: a failed un-play write must keep the
removal and must not restore the row. Give rapid repeated discards explicit
feedback while the first write is pending: `discard` returns early while
`writing`, so a second swipe is dropped with no acknowledgement.

### Add a recovery route and Cancel while buffering

The status and lamp read BUFFERING, ENDED and PLAYBACK ERROR from the loaded
player's phase and issue. What is still missing is the way out: a route to the
existing recovery action where applicable, and Cancel staying available while a
stream buffers. Test a delayed stream, metered consent, engine refusal, replay,
and a switch from a failing item to a healthy one. Avoid inventing a new source
of playback truth in the UI.

### Keep all transport functions reachable at high text scales

`RadioPlayer` hides the skip keys below its combined width estimate. The narrow
layout relies on the needle, which requires a known duration and cannot apply
the listener's skip intervals. Provide a second layout for the keys with 48 dp
targets and the printed intervals. Use fewer scale labels where they collide.
Test both skip values, unknown durations, three-hour media and 2.0 text at
320 dp, in German and with keyboard access.

Window width also matters. At 390 x 844 both keys flank the counter; at
700 x 900 the right pane narrows enough to hide them. Retain the 600 dp
two-column transition. Compare a second compact bank with reallocating pane width
at 599, 600 and 700 dp and 1x, 1.3x and 2x text. Keep the four Nixie digits
legible. At the 600 dp minimum width the home already takes the two-column
layout: the speaker grille takes half the width, both skip keys disappear and
slip titles cut to two short lines. Consider a 720 dp threshold for the split or
a speaker that yields width before the keys do.

### Give long playlists a reliable count

`RadioViewModel.onRows` asks the repository for an unplayed count on every
incomplete paging snapshot. The result is stored as `RadioState.episodeCount`,
but the dial only prints its position when the loaded episode list is complete.
Show the total as a localised unplayed count for partial pages, and show a
position only when the episode's ordinal is known. The loaded window index is
not the ordinal once `placeholdersBefore > 0`, so either carry the leading
placeholder count in `RadioState` or print the count without a position.
The count is fetched per snapshot and can land after a discard, leaving a stale
value that nothing draws today: draw it and bump the state's revision when it
changes, or delete it [R-04, REPORTED].
`RadioPlaylist` already carries `podcastCount` and `radio_meta`
("%1$d podcasts - %2$d episodes") is defined in EN and DE, but neither is drawn.
A failed count currently reports a write error for a read; a failed count should
leave the total unknown. Test successive append, invalidation, count failure,
discard and Undo, and a playing episode outside the selected group. In review:
#626 prints the localized unplayed total (`group_unplayed_fmt`) on the dial
while paging is incomplete and makes a failed count a silent keep-previous
instead of a write error.

### Replace the last Material buttons on the radio home

`RadioProgramme` renders the paging Retry action and the discard Undo action with
Material `TextButton`, painted with `RadioText`. Their ripple, touch metrics and
font metrics are foreign to the paper tray, while every other action on the radio
is an ivory key or a bat switch. Restyle both as momentary piano keys without
changing the test tags (`radio_retry`, `radio_undo`), the focus hand-off after a
discard, or the single-line error/removal rows. Check the row height at compact
sizes and at 1.3x text.

### Let the radio play subscriptions without a group

PLAN R2.3 says two virtual feeds always exist, All and Ungrouped. The radio
offers only real groups, and `RadioViewModel.start()` returns early without one,
so a subscribed but ungrouped podcast cannot be played from the only full player.
All survives only behind `Route.SelectFeed(null)` from notifications, which opens
the legacy `FeedBrowserKey` screen. Owner decision: add an "All podcasts" station
first in the chooser, backed by `FeedSource.All` (it honours `includeInAll`), so
subscribing is enough to start listening while groups stay the curated inboxes.
Test a library with no groups, a podcast excluded from All, and a switch from All
to a group and back.

### Bring Android and desktop playback into parity

A review of `playback/*` found these differences. Two are already fixed: a
desktop enclosure answering 403 now asks for a password (#588), and Android marks
an outgoing episode in its near-end window played on a new start (#593). Verify
the rest against the code before fixing each with a regression test in
`playback/impl` (Robolectric harness in `LifecycleHarness.kt`) or
`playback/desktop` (`DesktopPlaybackTimingTest`).

- Android does not re-prepare an errored current item: partly refuted. A
  Robolectric test (`PrePlayControllerTest`: `player.stop()`, then
  `controller.play(UI)`) passes on main, because Media3's session play handling
  prepares an idle player before playing [A-02]. The variant with a real
  `playerError` set (an untyped ExoPlayer error, an exhausted audio retry or an
  unplayable last item) was not exercised; check it on a device before changing
  `AndroidServiceBridge.startLocked`'s AlreadyCurrent branch
  (`AndroidServiceBridge.kt:296-309`, `ErrorRecovery.kt:306-330`), where
  `QueueProjector`'s `if (playerError != null || playbackState == STATE_IDLE)
  prepare()` would go. The desktop re-prepares
  (`DesktopPlaybackController.startLocked`).
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
  latched, so playback stops and never advances
  (`DesktopPlaybackController.kt:846-906`) [DP-02]. In review: [#646](https://github.com/L-K-M/Neutrodyne/pull/646).
- At "End of episode" the desktop sleep timer never stops: the engine rolls into
  the successor without `Ended`, `engineItemTransition` re-arms the timer for the
  next item and `Effect.ArmEndOfItem` was a no-op, so a playlist plays all night
  [DP-01]. In review: [#656](https://github.com/L-K-M/Neutrodyne/pull/656) (an engine item-end mode, so arming never
  re-prepares, plus a stop at the arrival when the timer was armed late).
- After a relaunch the radio showed 00:00 and READY TO PLAY for a restored
  episode stored at 03:58, and skips counted from zero: `RadioViewModel`'s
  live-row collector skips the loaded item and `position()` ignored the engine's
  paused snapshot, so the first flow to arrive won [R-03]. In review:
  [#632](https://github.com/L-K-M/Neutrodyne/pull/632) (a parked seek, else the loaded item's own snapshot, else its
  stored row; `RadioViewModelTest.aRestoredSessionShowsItsSavedPosition`).
- After a relaunch the desktop session is restored but not prepared, and
  `seek` returned at once, so skip keys, the needle and the OS controls did
  nothing until Play, which then snapped back [L-16]. In review: [#635](https://github.com/L-K-M/Neutrodyne/pull/635).
- A headset button, the notification or Android Auto resumed through
  `beforePlay` without the paused-row pickup the app's Play applies, so a newer
  synced position was overwritten five seconds later [A-01]. In review:
  [#654](https://github.com/L-K-M/Neutrodyne/pull/654). Every start is also followed by a Play through `beforePlay`, so
  the change marks the position a start just chose (a timestamp, a restart, a
  smart-resume rewind) and keeps it; a first version without that mark dragged
  timestamp starts back to the stored row.
- An automatic arrival plays its first moments from 0:00, then jumps to the
  saved position: the engine window has no per-item start [DP-04]. Needs a start
  position in the window entries; same plumbing as smart resume.
- A failed successor prepare can override a Pause: a stale error event from the
  failed prepare races a Pause that already ran [DP-05, partly confirmed]. Drop
  error events whose item is no longer current and keep `wantPlaying`.
- A failed chapter or settings observation lights PLAYBACK ERROR on working
  audio [DP-06, REPORTED].
- The desktop opens output at 44.1 kHz, so 48 kHz audio is resampled twice
  [DP-08, REPORTED]; the buffered position always equals the position [DP-09,
  REPORTED].
- Android: a late item-change echo may save the outgoing position into the new
  episode. `PositionTracker.onPositionDiscontinuity` lacks the projector's echo
  guard (`QueueProjector.kt:594`) [A-04, PLAUSIBLE]; write a failing Media3
  adapter test first. The sleep ticker wakes the main looper twice a second
  even when Off [A-07, REPORTED].
- `EffectivePlaybackSettings.prepareWindow` issues one scope query and two
  global reads per podcast on every projection and clears its cache each time.
  Add a batch resolver like `notifications()`.
- `QueueProjector.onPositionDiscontinuity` mints a new `loadId` before its
  late-echo guard, so a "Marked played on ..." notice for the first occurrence
  can be dropped (`QueueProjector.kt:583-590`).

### Settle arrow-key tuning before loading while playing

Selection preserves play or pause, so holding Down while audio plays opens a
stream for every slip it passes [L-17]. Either settle for about 300 ms before
loading the newly selected episode, or separate keyboard focus from the tuned
selection while playing. Product-sensitive: decide which, then test a held key,
a single press and a press during buffering.

### Give the tuned playlist its own refresh and bulk actions

Refresh, Mark all played and Download all for a group exist only in the legacy
feed browser, which no in-app control opens [G-01]. The radio cannot refresh the
tuned playlist at all, and on Android the root refresh is a no-op. Put the three
on the radio's Menu (or the chooser row), reusing the feed browser's use cases,
and test a group refresh, an offline refresh and Undo of Mark all played.

### Draw the time-remaining preference

`appearance` PLAYER_TIME is stored, backed up and toggled in Settings, and drawn
nowhere [G-02]. Pressing the Nixie could flip between ELAPSED and REMAINING,
accounting for speed, with the caption saying which [Q-02]; the stored setting
then picks the default. No time remaining, speed or sleep state is visible on
the player today [M-06]; "Ends at 14:32" under the dial is a related idea. Test
the flip at 1x and 1.5x, with an unknown duration and with TalkBack.

## Cabinet, layout, and accessibility

### Make the cabinet fit the useful viewport

The transport and the slips now follow the usable height after the system bars.
In the narrow desktop fixture the transport and deck leave roughly one full slip.
The small layout permits nested vertical scrolling once the cabinet exceeds its
viewport. Keep the valves and selection visible, but measure the usable space
inside the insets and define a minimum for the slip list. Reduce the speaker
opening and panel padding first, then use a more compact arrangement of the dial
and transport. Choose an intentional scroll mode if the total still cannot fit.
Test 600 x 480, 800 x 500, 1200 x 800 and the phone and tabletop presets at 1.0,
1.3 and 2.0 font scales, including a two-line title, Retry alongside Undo, and
enough episodes to require scrolling. The deck now shows whenever the window is
tall enough (owner decision 2026-11-17); the 560/440/500 dp minimums are still
unmeasured.

### Give the playlist sign room and a legible fallback

`RadioProgramme` reserves an invisible key's worth of width to centre the sign
opposite Menu. The 320 x 568 and 1.3x text desktop presets ellipsize even
"Morning rotation." Keep the enamel treatment. Align the sign to the paper
tray's reading column or move Menu to a separate corner when the measured sign
cannot fit. Show the complete name in the chooser and expose it through desktop
hover or focus when clipped. Compare English and German, two long names with the
same prefix, and 320 dp plus 600 dp widths.

### Add a usable Mac programme scrollbar

The home list in `RadioProgramme` lacks the scrollbar drawn by `FeedsScreen` and
other scrolling destinations. In a short window a full row can fill the paper
tray with little indication of more material below, and nothing hints at the
position in a long programme [M-04]. The shared `NdScrollbar`
overlay draws a thumb without drag support and hides it when all rows are even
partly visible. PRs #569/#570 cover the draw exception and the minimum thumb
bounds. Address the partial-row case before wiring the overlay into the radio,
or provide a draggable desktop scrollbar in the paper-tray gutter. Compare the
treatments at 700 and 1200 dp, with a long list, one clipped row, a tray shorter
than the thumb minimum and a fully fitting list. Test wheel and trackpad
movement, and keep the play target unobstructed. The loaded window is the
scrollbar's extent until pagination supplies more rows.

### Establish a material and type hierarchy

The desktop radio draws several borders, screws, wood grain, glass highlights
and small mono labels into the same few hundred pixels. Use a quiet paper slip
under readable ink, crisp sans-serif metadata, and retain mono for the dial,
counters and short engraved terms. Keep a single direct light direction across
the faceplates, flatten seams where the two trays meet, and check the
reduced-contrast engraved labels on warm paper and charcoal. Start with the
radio home and one shared page. Compare normal and selected slips, hover,
keyboard focus, missing cover art, and Retina plus 1x rendering.

The home installs its own light scheme in `RadioScreen`. Many physical legends
draw fixed ink even when `NeutrodyneTheme` honors the system contrast request.
Sample the 11 sp metadata and 10 sp timeline against the lightest and darkest
grain under the letters. Measured contrast to start from: inactive Nixie digits
are 2.0:1 against the glass, the failed-downloads lamp 3.95:1, and the Library's
New group drawer label about 3.1:1. The selected slip is a `0x22E4BF71` tint
that is hard to tell from its neighbours; try a brass edge marker or a printed
pointer before a stronger tint.

### Replace the last Material surfaces on radio pages

These still use Material shapes on the radio's materials: the Library's "No
groups yet" icon and pill, Discover preview's Subscribe and + New group pills,
the podcast page's + Group chip and More link, the speed sheet's chips, slider
and segmented button, the sleep sheet's chips, outlined minutes field and text
button (`SleepTimerSheet.kt`; both sheets open full height and mostly empty,
and read poorly on the stage [V-04]), the Up next and Downloads text buttons,
the Menu plate's flat tan Close key, and the light drag-and-drop overlay on the
charcoal stage. The podcast page header has no enamel title, and Subscribed is a dot and text
where the mockup shows a toggle. In the group editor the member check badge
overlaps cover lettering. Replace each with the existing keys, switches and
signs, and compare goldens at 390 and 1200 dp.

### Close the remaining accessibility and localisation gaps

Radio slips expose no explicit role. `Modifier.selectable` does not default to
`Role.Tab` (its role parameter is null), so the earlier claim that the tree
reads a list of tabs is false;
[#629](https://github.com/L-K-M/Neutrodyne/pull/629) picks `Role.Button`
deliberately; verify with TalkBack. The chooser rows and the Sync link-method
selector are fixed. German lacked the crash-dialog and crash-mail strings (the
only five missing keys in a repo-wide diff)
(`app/src/main/res/values-de`), and the media notification and lock-screen
actions were English literals (`MediaButtons.kt`); [#653](https://github.com/L-K-M/Neutrodyne/pull/653) translates both
and adds a resource parity test [A-03, AX-03]. The first-link choices in sync
setup may not be a radio group (mirror `MethodOption`'s `selectableGroup()` and
`Role.RadioButton`) [AX-01, REPORTED]. `downloadSize` hard-codes the decimal point and
unit words, and the queue position "01 / 12" is assembled in code and spoken as
digits; give it a localized description [AX-02]. On Android,
`RadioCabinetPage` already ends a page at the keyboard with
`aboveKeyboard(..., WindowInsets.ime)`, so the earlier "no `imePadding`
anywhere" claim is stale; verify the Sync, credentials and group-editor fields
with the keyboard up rather than assuming no IME handling. Test each with
TalkBack, VoiceOver and the German locale.

### Measure switch hit areas in dense panels

`RadioSwitch` requests 30 x 40 dp for the painted plate and passes the same
modifier to the interactive `BatToggle`. A probe on this Compose version found
that `Modifier.minimumInteractiveComponentSize()` does not widen the pointer
region here, so meeting the target needs a real layout change. Measure actual
hit regions between adjacent controls in Import, Groups and Playback speed
before sizing the surrounding panel. For standalone switches, reserve a distinct
48 x 48 dp area around the current plate (a centred plate in a 48 dp box, not a
larger plate). Verify corner taps, lever flicks, disabled controls and vertical
scrolling at 2x text. For rows that own their switch action, retain one
accessible target. In review: #620 implements exactly that (`BatToggle`/
`BatToggleFace` take a `plateSize`; the modifier is the 48 x 48 dp hit box), with
the affected desktop goldens re-recorded through the record-screenshots workflow.

### Place the Mac window in the display's usable coordinates

A live AWT probe on this Mac reported primary bounds 1920 x 1080, an identity
default display transform, a normalizing transform of 0.9583, 69 DPI, and
top/bottom insets of 30/90. `WindowIcons.primaryScreen` passes the bounds and
physical DPI to `defaultWindowBounds`, which multiplies by 96/69. This computes
a first-window origin near (736, 351) for a 1200 x 800 cabinet, so the window
spans 736-1936 of a 1920 px screen and its right and bottom edges extend past the
display even before the Dock. The normalizing transform and the physical DPI are
two descriptions of the same scale; applying both is the suspected defect.

Verify the Compose-to-AWT position on a clean launch at 1x and Retina 2x, with
the Dock on two edges. Convert from the native usable coordinate space exactly
once. Verify the 600 x 480 minimum and a secondary display with a negative
origin. Check Windows fractional scaling and Linux scaling before sharing the
conversion across platforms. A second pass reproduced the same AWT values and
confirmed that Compose's desktop density is
`GraphicsConfiguration.getDefaultTransform().getScaleX()` and that Compose's own
`WindowLocationTracker` places windows directly in `getBounds()`'s space, so the
bounds are user-space units. [#614](https://github.com/L-K-M/Neutrodyne/pull/614)
(in review) converts the device-space bounds with the inverse default transform
and drops the `96 / physical DPI` multiplication; verified live at `(360, 140)`
instead of the clamped `(720, 190)`. A noninvertible transform returns null (the
default placement) and logs at warn. Still to verify: Retina 2x, the Dock on two
edges, a secondary display with a negative origin, the 600 x 480 minimum, and
Windows/Linux fractional scaling.

The same double conversion breaks the minimum size on every Retina Mac [L-22]:
`minimumWindowDimension` multiplied 600 x 480 dp by the density and handed the
result to `window.minimumSize`, which AWT reads in user-space points. At
`-Dsun.java2d.uiScale=2` the 1200 x 800 default opened as 1200 x 960 and
refused a resize to 700 x 560; a 13-inch MacBook has fewer than 960 usable
points of height. [#649](https://github.com/L-K-M/Neutrodyne/pull/649) returns 600 x 480 at every scale (verified live at
2x: 700 x 560 is accepted) and deliberately leaves the first window's placement
[L-04] to the placement change above. Windows fractional scaling and Linux
scaling are still unverified.

### Make the Mac window feel native

Use `apple.awt.fullWindowContent` and `apple.awt.transparentTitleBar` on the
root pane, a `WindowDraggableArea` for the top band and about 28 dp for the
traffic lights, so the charcoal stage reaches the window top. With
`apple.awt.application.appearance=system`, a light-mode Mac now shows a light
title bar over the charcoal stage. `java.awt.Taskbar` offers a Dock menu (Play
or Pause, skip back and forward, the current title) and an opt-in icon badge
(unplayed in the tuned playlist); the app uses neither. Guard each with
`Taskbar.isSupported`: the Dock menu and text badge are macOS features, and
Windows offers neither. Window > Minimise lacked Cmd+M and Zoom lacked
Ctrl+Cmd+F; #619 binds them and stands them down with the other accelerators
while a text field is focused. The window title drops the episode as soon as
playback pauses (`NeutrodyneWindow.kt:304`); macOS convention keeps the current
title while the episode is loaded, so record the decision. Window bounds are
persisted by [#633](https://github.com/L-K-M/Neutrodyne/pull/633) (in review):
`desktop.window_bounds` is written one second after the last move or resize and
at quit, and restored when the rectangle still overlaps its screen's usable
bounds by at least half. Verify a screen that disappeared, a display
re-arrangement and the maximised flag.
Navigation state is lost on relaunch; restoring the last playlist already works
through settings. Check each in full-screen Spaces and with VoiceOver. A second
launch with an unusable working directory loses every argument [MS-02,
REPORTED]. A grey title bar sits on the charcoal stage [V-13], which the unified
title bar would fix.

### Preserve veneer proportions during live resize

`RadioMaterials` stretches the cabinet wood image with FillBounds while the
plinth uses Crop. Capture one cabinet at portrait, ordinary Mac window and
ultrawide sizes at both display scales. Compare the existing veneer with an
aspect-preserving crop anchored at the same corner, keeping the bezel, lighting
and screws fixed. Select a treatment only if the grain holds its proportions
without an obvious seam or a distracting jump during live resize.

### Check the dial end labels and slip row geometry

A vision pass over the committed desktop goldens (which pass on HEAD) flagged
three things to confirm in a render: the rightmost waveform time label appears
clipped by the panel edge in the phone, small, landscape and tablet presets;
slip row heights vary when a title wraps to two lines while its neighbours stay
on one; and the group editor's dark cabinet reads inconsistently with the light
pages, although the code sets `dark = false` (confirm whether that is the radio
stage or a theme bug). Confirm before changing the dial's `SpaceBetween` labels,
the row minimum height or the editor theme.

### Fix the layout faults seen running the Mac app

Each was seen live on the desktop app [V-, L- ids]; compare goldens at 390,
700 and 1200 dp before and after.

- The status line is cut by the dial's arch: `DialOpening`'s lip is 10% of the
  height, so the lamp and "NOW PLAYING" sit under the bezel [L-10].
- Tall layouts print the episode total twice, as the scale's end label and as
  the `radioTimeline(duration)` line under it when not compact [L-19].
- The transport is off-centre: skip, counter and skip are centred in what the
  switch leaves, not under the dial [V-01].
- The speaker takes a third of the window and holds nothing; at 1200 and
  1600 dp it floats between two bands of bare wood [V-02]. See "Fill the speaker
  grille on wide windows".
- The playlist chooser is a blank full-pane sheet with text touching its edge,
  no padding, counts or covers [V-03].
- Single keys get a heavy machined housing taller than the faceplate (Cancel,
  More, Download settings), and the Import faceplate is 14 dp taller than its
  neighbour [V-05]. Key banks stretch across the whole window (two 560 dp keys
  for Podcasts and Groups) [V-06]; a short bank leaves a long black slot
  (podcast filters) or clips "IN PROGRESS" in a narrow pane [V-07].
- Import rows misalign Rename and the switch, "Done" is an outlined chip that
  reads as a button, and every row's subtitle is the feed host [V-08];
  "GROUPS" in the import preview is clipped at the pane edge [L-09].
- The empty Library shows the app icon, a second giant glyph and a navy
  Material pill [V-09]. Library covers have no names by default, so look-alike
  covers are unidentifiable, there is no tooltip, and badges cover a quarter of
  a small tile [V-10].
- Every pane in a row has its own Back key, three at once on the episode page
  [V-11]. A "Back" tooltip pops up without hover on every page that opens,
  over the valve deck, because the Back key takes focus and the tooltip follows
  focus [L-03; root cause unproven].
- Downloads tells a listener "0 B partial - 0 B untracked" [V-16]. Up next
  offers playlists as outlined chips under a blank glyph, below the fold
  [V-19]. The mini radio's label is a 900 dp empty paper strip with no times
  [V-20].
- The plinth feet are two tiny flat rectangles in the window's corners [V-14];
  see the `MaterialPlate(METAL)` item under Confirmed small defects, which
  covers the plain `Chassis` rectangles.
- Keep the two deck-stow paths in agreement: `DECK_MIN_HEIGHT` is compared
  against the home's and `RadioCabinetPage`'s own heights, which can disagree on
  the same window (with the duplicated-constants item below).

The mini radio's needle was invisible (a 14 dp window left a 5 dp face, and the
needle was drawn from y=2 to y=3 dp), so no page but the home showed progress
[L-01]; [#651](https://github.com/L-K-M/Neutrodyne/pull/651) scales the marks to the face and gives the mini player and
download rows 18 dp windows. The mini radio also drew on top of the home under
the Speed and Sleep sheets, because a pushed sheet counted as leaving home
[L-06]; [#647](https://github.com/L-K-M/Neutrodyne/pull/647) reads the entries' overlay metadata instead.

## Confirmed small defects

- **A swipe that starts on a slip's play key can discard it.** The horizontal
  drag detector is attached to the whole `MaterialPlate`, including the 48 dp
  play key, the most tapped target on the slip. Restrict the gesture to the
  cover and text, or ignore drags that begin on the key.
  [#628](https://github.com/L-K-M/Neutrodyne/pull/628) makes the play key
  consume horizontal drags; the swipe tests start on the text and a regression
  test swipes the key.
- **`RadioInsert` restores no focus target after process death** when the
  chooser was closed but the hand-off had not completed; #612 persists the flag.
- **`RadioInsert` positioning is inset-sensitive.** It places the paper from the
  faceplate's `boundsInWindow` and a hand-computed dialog inset; re-check it on
  a wide pane row and with different dialog insets.
- **The Menu key label can wrap and un-centre the sign**; #610 caps it at one
  line and plays the key press; #616 (merged) landed the same `KEY_PRESS`
  sound on `SmallRadioKey`. Its hand-drawn radial cap could become the shared
  `IvoryKeyCap`, but that is a visual change to the golden-covered radio
  home, so record the goldens first.
- **`ninePatch` could draw overlapping slices on a very narrow node**; the
  `ninePatchStops` clamp fixed it. `ValveDeck`'s tiling still has no unit test:
  extract the slice plan into a pure function and test the mirrored-tile, forced
  odd-count and narrow-node branches.
- **The deck constants are duplicated.** `DECK_MIN_HEIGHT`, `DECK_HEIGHT_SHARE`
  and `DECK_OVERHANG` exist in both `RadioScreen` and `RadioCabinetPage`; move
  them to `RadioMaterials` beside `VALVE_DECK_ASPECT`.
- **The player could show stale metadata for the selected episode.**
  `RadioState.selected` preferred `nowPlayingEpisode` over the row from the
  programme list, so a title or podcast changed by a refresh showed the old
  value on the player while the slip showed the new one. #627 prefers the row
  and keeps the player snapshot as the fallback; the measured duration still
  wins. Its fallback test asserts only the title; tighten it to full data-class
  equality so a dropped id or podcast cannot slip through.
- **The printed needle scale always uses `MM:SS`; above 99 minutes the
  rightmost quarter label ("900:00") is wider than the others and nudges the
  row.** Either accept it deliberately or use a compact hours form there too.
- **`Nixie` draws a ghost `8` behind every digit;** check on Retina whether it
  greys the tubes enough to cost legibility.
- **`SkipKey`'s painted cap fills its whole 52 dp target while `SlipPlayKey`
  keeps a 36 dp cap inside 48 dp.** A little air between ring and target would
  match.
- **`MaterialPlate(METAL)` draws two screws at the top corners only;** the
  rendered faceplates carry four. The home's plinth feet are plain `Chassis`
  rectangles with no shadow or recess, so on the dark stage they read as
  floating bars.
- **The dial needles are drawn on top of the glass sheen:** `dialWindow` is a
  `drawBehind` on the parent while `TuningDial`, `NeedleWindow` and
  `StepCounter` draw their needles afterwards, so the needle never sits under
  the reflection.
- **The speaker grille redraws on every draw invalidation** (new, independent
  pass 2026-11-22). `Speaker` (`RadioCabinet.kt`) draws `width/4 + height/4`
  lines plus ribs in a `Canvas`; on a wide window during a resize that is
  hundreds of `drawLine` calls per frame. Cache the grille in a `Picture` (or
  `drawWithCache`) keyed on `IntSize`.
- **`NdTopAppBar.FaceplateRow` falls back to the "between" position even when
  `betweenRoom < signWidth`**, so at very narrow widths the sign can run under a
  key.
- **The visible Nixie counter caps at `9H:59`** (`RadioState.kt:70-78`).
  `radioElapsed` coerces minutes to `9*60+59`, so a ten hour episode reads
  `9H:59` forever while the spoken description is unbounded. Accept the
  four-tube cap deliberately or print a compact two-digit-hours form.
- **`RadioText` sets a tight line height** (`RadioProgramme.kt:650`):
  `lineHeight = size * 1.2` sp can clip descenders at small sizes.
- **`SignLettering` mishandles a trailing space** (`RadioParts.kt:160-175`):
  it compares `getLineEnd(0, visibleEnd = true)`, which excludes trailing
  whitespace, with the full length, so a name ending in a space always letters
  at its minimum size. In review: [#630](https://github.com/L-K-M/Neutrodyne/pull/630)
  and #621 (same fix from the independent 2026-11-22 pass) compare against the
  trimmed name; the regression test fails before and passes after.
- **The desktop's failed-downloads badge blips to zero on settings changes**
  (new, independent pass 2026-11-22). `DesktopShell` built the `failedDownloads`
  flow inline in the `application {}` block, so a settings-driven
  recomposition produced an unequal `Flow` and restarted `NeutrodyneRoot`'s
  `collectAsStateWithLifecycle`, resetting the badge to its initial 0 until the
  database-gated re-subscribe emitted. `MainActivity` already wraps the
  identical flow in `remember(graph)`. In review: #617.
- **A multi-file drop persists an import session per file but opens only one**
  (new, needs a product decision). `DesktopOpenHandler.open(batch)` routes every
  input and `imports().create` persists a session for each, but only the first
  routeable input navigates; the rest surface later only through the
  import-resume banner, with no notice at drop time. Decide: queue additional
  sessions explicitly, or validate them without creating sessions and post one
  "skipped" notice.
- **The programme's empty and error states are footnotes.** When a playlist is
  empty or fails to load, the tray shows only a small strip at its foot; give
  that state the paper's full height.
- **Slips print "0 min" for an episode under a minute and "180 min" for three
  hours** (`RadioSlip` uses integer minutes) where other rows use hours and
  minutes [L-26]. Share the rows' duration formatter.
- **Show notes' nested lists indent twice, and long notes have no reading
  width** [L-25]: `NoteList` adds `24 * level` inside a parent column that is
  already past the marker gutter, and paragraphs run over 1,000 dp at 1600 wide.
  The notes also touched the pane's left edge [L-08]; [#639](https://github.com/L-K-M/Neutrodyne/pull/639) insets the
  text blocks to the page's 16 dp.
- **An engine-update 404 logs ERROR with two stack traces every few minutes**
  [L-15]. Log a missing manifest once at INFO and back off.
- **The playlist chooser does not refocus when playlists arrive after it
  opens** [R-05, REPORTED].
- **An import ends with no way to the radio** [L-21]: add a "Go to the radio"
  step to the finished import.
- **The podcast detail still has a settings gear**, against PO-55 [LU-08,
  REPORTED].

## Performance

### Measure the work done by playback ticks

`RadioViewModel` republishes the whole `RadioState` on position ticks, and
`RadioRoute` forwards it through the cabinet. `RadioPlayer` looks up selection
and the visible count with list scans. Profile a seeded 10,000-row station while
playing, inertially scrolling and resizing on a Retina screen. Attribute work
to composition, layout, asset rasterization and playback separately. If tick
invalidation is material, read positions in the dial and Nixie leaves, keep the
list inputs stable, and cache ID lookups by loaded snapshot. Compare p95 frame
time, input latency, composition counts and idle CPU against the baseline, with
and without artwork cached.

One cause is visible in code: `RadioRoute` rebuilds `RadioActions` on every tick
with a `loadEpisode` lambda that captures `state`, so the actions are never equal
and every visible `RadioSlip` recomposes each second while playing. A
composition-count test can show it before and after moving elapsed time into a
`State` read only by the needle and Nixie, remembering the actions with
`rememberUpdatedState` for captured values, and adding `contentType` to the slip
list. Desktop CPU while playing measured 2 to 7% on a Mac Studio, so the cost
matters most on low-end Android. The one-second desktop ticker also calls
`DesktopPlaybackController.publish()`, and `PlayerViewModel.position` switches
its tick subscription on each new `nowPlaying` value.

### Keep download progress and static drawing off the hot path

Download progress recomposes the whole Downloads screen; pass the live progress
`State` down and read it per row, as `PodcastScreen.kt:483` does. The Downloads
overview is recomputed by a 30 s polling pulse, and the engine's queue scan loads
every download row on each loop (`DownloadControllerImpl.kt:356`,
`DownloadEngine.kt:312`). The faceplate and paper card decode both the wide and
the tall artwork although only one is drawn (`RadioPanels.kt:44,61`). `recess`,
`radioGlass`, `cabinetEdges` and the needle allocate `Path` and `Brush` per
draw, and static tick scales redraw every frame (`TuningDial.kt:93`,
`RadioPlayer.kt:393`); cache them with `drawWithCache`. A control-sounds branch
rewrote every sound WAV to `cacheDir` on each cold Android start; if sounds
land, write them once per version.

### Profile rendering and paging while scrolling

Compare individual and split `RadioCabinetPage` trays for duplicate lid drawing,
texture scaling and repeated clips at Retina resolution. Trace
`RadioRoute.onRows` mapping the paging snapshot and the list's per-item
`radioPageIndex` lookup during rapid scrolling and station changes. The feed
pages by 40 with a maximum loaded size of 400; time `loadedIds.indexOf` against
that window before adding a lookup table. `RadioRoute` rebuilds the entire
`RadioEpisode` presentation list on every paging emission and copies it into
`RadioState`, so one append recomposes the whole cabinet even though
`LazyColumn` keys isolate unchanged rows. Map only the diff or keep the mapping
behind the snapshot identity. `Speaker` redraws its fixed grille pattern on
every invalidation; a cached draw is safe because only the size is an input.

Two more cheap wins to measure first:

- `feeds.countUnplayed` issues one count query per page while a long playlist
  streams in (see "Give long playlists a reliable count").
- Every nine-patch and three-patch slice draws with `FilterQuality.High`,
  including the small slices of every key on every slip. A fling composes
  hundreds of slips; try `FilterQuality.Low` for slices under a few pixels and
  measure the difference at Retina.
- `RadioProgramme` keys its paging load with `LaunchedEffect(index)`; a discard
  shifts indices and re-fires the load for a row whose episode key is stable.
  Key it by episode id if profiling shows it matters.

### Remove the remaining per-draw and per-tick allocations

Measured on an M4 Max: 1.5% CPU idle, 5.4% playing, 110 to 160 MB RSS, so these
matter for slower machines and low-end Android first. Measure each before
changing it.

- `MaterialPlate` costs a Canvas, an image shader and a fresh gradient brush
  per slip [P-02].
- `barlow()` and `courier()` create a `FontFamily` per call [P-03].
- `radioStage` allocates brushes and paths in every draw, beside the
  `cabinetEdges`, `radioGlass`, `dialPaper` and `recess` already listed [P-04].
- The live-state flow re-emits on each position tick for all visible ids [P-05].
- `FeedDao.page` is invalidated by any write to six observed tables, position
  saves included [P-06].
- `MonogramPainter` measures text in every draw [P-09, REPORTED]; `slipFocus`
  keeps one `FocusRequester` for every episode ever composed [P-11, REPORTED];
  date labels are formatted twice per row per composition [P-13, REPORTED];
  `FeedsScreen` flat-maps every loaded page for selection even with nothing
  selected [P-14, REPORTED].
- Every refresh ran at least five seconds, because `FetchStateBatcher`
  launched its 5 s deadline into the scope `FeedRefresher` waits on, so the run
  stayed `running` and held its lock until the timer fired [DA-02];
  [#637](https://github.com/L-K-M/Neutrodyne/pull/637) cancels the deadline when the run's work ends.

### Make desktop screenshot failures reproducible

On this Mac the focused Roborazzi verification of `RadioScreenshotTest.presets`
failed at `radio_phone.png` on clean main and on the accessibility change, with
identical SHA-256 digests for the two fresh renders. Compare the main render on
the reference CI OS against the committed PNG. Record its JDK, rasterizer,
density, fonts and renderer configuration, then decide whether the host should
use its own baseline or run visual acceptance on the reference OS. Verify all
six presets and prove a deliberate layout change still fails.

## Listening workflows

### Put useful episode actions beside the programme

`RadioSlip` currently offers selection and Play. Give a slip a compact context
menu for episode notes, Play next, download where supported, and Mark played at
the end. Reuse the episode navigation and action services behind `EpisodeRow`.
Bind every action to the slip's stable episode ID. Verify primary click, Play,
secondary click, Shift+F10 and a touch interaction. Check that Open notes
preserves the selected station, scroll anchor and active transport on return.
Chapter next and previous are unreachable from the radio, the only full player:
the handlers in `PlayerRoute.kt:134` are dead code. Offer them in the same menu
or on the keyboard when the episode has chapters. The same audit found Next
episode unreachable from the radio too [G-05, REPORTED], and no route from the
radio to show notes [G-03]. Secondary click on a slip does nothing [M-02].

### Preserve the station and scroll position when browsing

`RadioProgramme` resets scroll to the first item when the playlist ID changes.
Consider a bounded per-station anchor keyed by stable episode ID and offset.
Select the nearest surviving episode after refresh, discard or data removal.
Keep the playing episode independent of the browsed station. Test A -> B -> A,
paging behind the anchor, removals, restart and Back. Defer a migration until a
session-only prototype feels right. Returning to the radio also rebuilt the
pager and lost the place, because `RadioViewModel.pagingFor` returned a new
`Pager` flow with no `cachedIn`, against `FeedRepository.kt:73` [L-11];
[#640](https://github.com/L-K-M/Neutrodyne/pull/640) caches the last three tuned pagers.

### Make first tuning a short, recoverable journey

A new listener often needs to add a subscription and create their first group
before the radio has anything to tune. Keep group creation in Library. Provide
one route to the existing group workflow and a separate route to Discover when
there are no subscriptions. On macOS, exercise a clean launch, adding a feed by
paste, creating a playlist and returning to play the first episode. Repeat with
an OPML import, a present but empty playlist, and a feed whose refresh failed.
Keep separate empty and error explanations so a failed refresh never looks like
an empty schedule. Today the journey from the empty radio still takes seven or
more steps (Library, Groups, New group, name, members, save, back).

### Show how old an episode is and what is new

Slips print no date. For a news inbox, "today" against "three weeks ago"
decides what to keep: print the age like a rubber stamp beside the duration
("18 NOV"). The radio has no "new since last visit" marker; the legacy feed
browser had baselines (`FeedsViewModel.kt:451`, `markVisited`, `lastViewedAt`).
A red NEW stamp on episodes that arrived since the playlist was last tuned would
reuse them. Check both against German dates, 1.3x text and the compact row.
The rubber stamp could read TODAY, YESTERDAY or 18 NOV [M-05, Q-10]; a RESUME
stamp and a thin progress groove on a started slip would say where listening
stopped.

### Make editing and finding work naturally with a Mac keyboard

`DesktopMenuBar` disables Cut, Copy, Paste, Undo and Select All and drops app
menu accelerators while a Compose field has focus. Build a focused-field action
bridge only if the composition can reliably supply enabled states and route to
the actual field. Start with Discover search and verify Cmd+F, Cmd+W, Cmd+,
and ordinary text shortcuts against focus in a field, in a modal and in the
programme. Preserve the current key ownership for transport, and use the
platform clipboard rather than a new editing abstraction.

In the programme, each Up or Down press now changes the selection and reveals
the slip at the nearest edge (#609); verify a press inside the view, at its
bottom edge and at the end of the list. Escape was swallowed in text fields:
`isRootShortcut` marked it as a root shortcut and the text-field branch consumed
it with no action (`NeutrodyneRoot.kt:394,659-673`), so in Discover search
Escape neither cleared the field nor went back.
[#615](https://github.com/L-K-M/Neutrodyne/pull/615) removes it from
`isRootShortcut`, so the root's existing back path runs; verify it in Discover
search, Sync setup and a modal. The window's Cmd+Q fallback
routed through `onCloseRequest`, which hides to the tray, instead of quitting
(`NeutrodyneWindow.kt:555`); the fallback runs whenever the accelerators stand
down, as in a focused field [L-23]. [#645](https://github.com/L-K-M/Neutrodyne/pull/645) maps Q to `onQuitRequest` and
unit-tests the chord map. Cmd+, may be registered twice on macOS [MS-04,
PLAUSIBLE].

More Mac keyboard and pointer gaps found live:

- Space did not pause after clicking a slip: a focused slip's `selectable` and
  the mini player bar's `clickable` take Space as a click before the root's
  play/pause sees it, against 08's Space row [L-02]. [#642](https://github.com/L-K-M/Neutrodyne/pull/642) adds a shared
  `Modifier.spaceTogglesPlayback` after both; episode rows on other pages have
  not been checked.
- The Mac's Delete key discarded nothing: every list handler matched only
  `Key.Delete`, which is forward delete; the key labelled Delete arrives as
  `Key.Backspace` [L-24]. [#636](https://github.com/L-K-M/Neutrodyne/pull/636) adds one shared `isDeleteKey` for the
  programme, Up next, Library and Downloads; text fields keep Backspace.
- Help > Keyboard shortcuts crashed the app with "Unknown screen
  KeyboardShortcutsKey": no installer registered the key [L-27]. [#644](https://github.com/L-K-M/Neutrodyne/pull/644)
  registers the dialog with 11's chords in each platform's notation and adds a
  test that every menu and serialised key resolves. A paper service-manual
  card raised by "?" would make it a delight [Q-11].
- Cmd+V outside a field does nothing. Pasting a feed or OPML link anywhere in
  the window should open Add podcast with it filled in, as dropping one already
  does [M-09].
- No hover state on slips, keys or covers, so nothing says what is clickable
  [M-01]. The dial has no hover preview, scroll-wheel tuning or keyboard focus
  [M-03]; a wheel over the dial could scrub with detents and the tick sound
  [Q-09], but only if it does not steal the scroll from a cabinet that scrolls
  when short. A trackpad's horizontal swipe cannot discard a slip; only a
  click-drag can [M-07].
- There is no Library search, and Cmd+F works only in Discover while the menu
  item is always enabled [G-04, REPORTED].

### Centralize text-field focus reporting

`.ndTextFieldFocus()` is hand-applied at every call site. Moving it inside
`PaperTextField` would stop the next dialog or search field from silently
reintroducing the shortcut conflict, but the wrapper lives in
`:core:designsystem`, which cannot see the tracker in `:core:ui` without a
circular dependency; raw `OutlinedTextField`s elsewhere would still need
explicit treatment. Do this as its own pass over every text field rather than
halfway, or move the tracker down to the design system first.

### Explain short searches and episode counts

For a one- or two-character search, show a localised hint that three characters
start an automatic search. Keep Enter available for an explicit short search.
The Discover list can show Apple's episode count while the feed preview shows a
different number. Compare both for the same podcast. Label the source of each
count, or use the feed count when it is available.

### Recover when a directory search cannot read the local library

Discover and Directory use `SearchRepositoryImpl.annotate` to mark incoming
hits as subscribed. A desktop probe closed the test database before a recorded
search. The flow emitted Pending, then threw `IllegalStateException` on the
subscription lookup before it could emit a final result. Exercise both screens
with a failing local lookup. Give the listener a way to retry when the library
is available, preserve the distinction between local failure and provider
failure, and verify that a subscribed podcast still opens its library page after
recovery.

### Diagnose the Linux YouTube preview handoff

In the Linux unit suite, `ExternalYouTubeModeTest.directoryChannelPreviewHandsOffToAdd`
timed out waiting for the channel title after opening a YouTube handle through
`PodcastPreviewKey`, on main and twice on the Discover proposal. At the timeout,
capture every Compose root, the active navigation entries and the Add sheet
state. Compare the preview route with the passing direct `AddPodcastKey`
journey in the same Linux suite. Determine whether the handoff lost the route
or the title assertion missed the active sheet. Verify that one Subscribe action
creates one channel in the selected group.

## Sync server and client

### Batch the feed-cache reads and eviction (new)

`FeedCacheService.batch` loops the (up to 1,000) requested URLs and calls
`feedCacheHead(url)` then `feedCache(listOf(url))` for each, inside one read
transaction: about 2,000 point queries per full batch. A single `IN` query for
the heads plus one for the selected bodies would be cheaper. `MAX_FEED_BATCH`
is 1,000, above the old SQLite default of 999 variables, so chunk at 500 like
the download planner's `BIND_CHUNK`. Separately, `JdbcWrite.putFeedCache`
calls `evictFeedCacheOverflow()`, which runs `SUM(body_bytes)` for the account
after every insert; maintain a running total or check the cap only when the
just-written entry crosses it.

### Stop decoding the whole library on every push and GC (new, REPORTED)

`JdbcRead.records()` loads and JSON-decodes every record of the account.
`RecordChanges.records()` calls it, and `PodcastDeduper.lifecycle`/`merge` and
`GroupNameMerger` call that inside per-change loops, on the single writer
shared by all accounts; `StoredRecordWriter.push` also reloads `redirects()`
for each change [SERVER-01]. A hundred unsubscribes on a 50,000-record account
is five million decodes and can exceed the documented `-Xmx96m`, stalling every
account's sync meanwhile. Design 10's write path expects a prefix scan
(`substr(rid, 37)`). Fetch children by `rid` prefix in SQL and load redirects
once per push; test with a counting `SyncRead` proxy that rows read scale with
the touched podcasts, not the library. `GcJob` has the same shape
(`GcJob.kt:33,71-103`): it loads each account, rescans all rows per candidate
parent and runs every account in one transaction, so one crash rolls back every
account's purge [SERVER-02]. Find candidates with the `record_gc` index, use the
same prefix lookups, purge per account and chunk deletes. Effort M each; share
the prefix helper.

### Smaller server fixes (new, REPORTED)

- `/library` pages every record and filters podcast and group titles in
  memory, so on a listening-heavy account most pages are empty [SERVER-03].
  Query `coll IN ('podcast','group')` with keyset paging on `seq`.
- Setting only `NEUTRODYNE_SERVER_PUBLIC_URL` keeps `--insecure-lan`, because
  the compose default drops the flag only when the transport variable is set
  and empty: cookies lose `Secure` and the red banner shows behind an HTTPS
  proxy [SERVER-05]. Warn at startup and key banner and cookie on
  `!publicUrlIsHttps && insecureLan`.
- The CLI has `user disable` but no `user enable`, and disabling the only
  admin locks out web administration; `admin create` is an undocumented way
  out [SERVER-06].
- The feed cache is bounded per account (512 MiB) but not globally
  [SERVER-07]; add a global budget to `evictFeedCacheOverflow`.
- `sse.max_per_account` above 10 is silently clamped (`EventBus.kt:40`)
  [SERVER-08]. Decide whether 10 is a ceiling (then reject larger values at
  load) or only a default (then drop the `minOf`).
- NAT64-wrapped addresses (`64:ff9b::/96`) passed the poller's local-destination
  check, so on a DNS64 host a feed could reach a local IPv4 service
  [SERVER-09]; [#648](https://github.com/L-K-M/Neutrodyne/pull/648) classifies them by their embedded IPv4, in the
  server's `LocalDestination` and in the app's pre-confirmation peer guard
  (`LocalNetworkGuard.isLocalPeerV6`), which had the same gap.
- The admin web is unbranded, answers a signed-out page visit with problem
  JSON, and always shows "Set up the first administrator" [SERVER-10, S-01]:
  redirect web routes to sign-in, hide the setup link once an admin exists, and
  give `admin.css` the navy and amber palette with CSS alone.
- A feed pass (two permits, 30 s per feed, 15 min interval) can outrun the
  30 min TTL on a large library [S-02, PLAUSIBLE].

### Harden the login buckets and body budget (new)

`LoginAttempts` refuses new usernames once 10,000 failure buckets exist, so
every unseen username is throttled for up to 24 h [SERVER-04] and a distributed
caller could in principle exhaust the map; the 10-per-15-minutes login limit
makes it impractical. Evicting the oldest bucket would keep innocent logins
working, but a spray could then evict a victim's lockout and resume guessing;
pick the trade-off (or key the cap per source network) before changing it.
`RequestGuards` reserves `MAX_REQUEST_BYTES` (2 MiB) for a body with no
`Content-Length` until its read finishes (`RequestGuards.kt:79-85`), so about a
dozen concurrent undeclared or chunked requests hit the 24 MiB pre-auth budget
and draw 429 even though each body is small [S-03]; reserve a smaller amount
and charge the actual read.


### Reconsider the credential clock check in the server poll (new)

`FeedCacheService.basicAuthorization` sends the stored Basic header only when
`auth.c == credentialOrigin.c`. The client stamps the pair together, but the
server merges fields independently: a device with password sharing off can push
a newer `credentialOrigin` without `auth` (`SourceReader` writes the origin
whenever a credential exists, and `auth` only while sharing is on). After that
merge the stored auth is older than the origin, the check fails and the server
polls the private feed without its stored credential until something rewrites
`auth`. The final origin comparison already protects against sending the
credential to the wrong host; decide whether to relax the clock equality or
re-stamp the pair on the server when the origin changes.

### Fix the cause of unaddressable episode keys (new)

`SyncEngine.retireUnaddressable` deletes outbox rows whose `rid` cannot be
parsed by `RecordId.fromRid`. That happens when a feed GUID or enclosure URL
makes `EpisodeKeys` exceed `MAX_IDENTITY_KEY` (2,048 bytes) or contain a
control character. The episode keeps working locally, but its played state
and position silently stop syncing, and a log line is the only trace. A
content-hash fallback for over-long keys, or a bounded key at ingest, would fix
the cause rather than retiring the row.

### Wire up "Check again" (new, missing feature)

`EpisodeRow` offers `EpisodeAction.CheckAvailability` for greyed YouTube rows
when `RowCaps.recheck` is set, and `YouTubeChannelRepository.recheckAvailability`
is implemented (`YouTubeChannelRepositoryImpl.kt:92`). Every view model's
`onRowAction` ends in `else -> Unit` and none handles it
(`PodcastViewModel.kt:320`, `FeedsViewModel.kt:564`, `EpisodeViewModel.kt:312`),
so the
action is a no-op. `PodcastViewModel` already injects
`YouTubeChannelRepository`; add it to the other two, call
`recheckAvailability`, and post a localized "Couldn't check — try again later"
message when it returns null (design 04). Add the string EN and DE, bind the
row's availability update through the existing episode flow, and test success,
a new reason and a transient failure.

### Sync client defects (new)

- A group created on two devices with the same name stalls sync on the
  receiver for good [SY-01, critical, traced]: `SyncApplier.applyGroup` inserts
  the remote uuid without looking up `nameKey`, which is unique
  (`PodcastEntities.kt:215`), so the page's transaction fails on every retry.
  Design 10:333 and :1010 require `SurvivorRule.group` there. Needs a designed
  merge and a two-device test.
- Undo of a group delete was pushed as the delete: the delete left a
  `deleted=true` outbox row, the Undo's insert a newer `*` row beside it
  (triggers coalesce per field only), and
  `ChangeBuilder` and `SyncApplier.pendingState` applied `*` first and named
  rows over it without comparing clocks [SY-02]. [#655](https://github.com/L-K-M/Neutrodyne/pull/655) applies a record's
  rows in clock order, `*` first on a tie, also in
  `SessionAdopter.pendingSession`.
- A reordered group never reordered on the other device: `applyMember` wrote
  the new order through `insertMember`, which is `OnConflictStrategy.IGNORE`
  [SY-07]. [#650](https://github.com/L-K-M/Neutrodyne/pull/650) adds a member update that also lands a lower merged
  `addedAt` when the order key is unchanged.
- The client never follows `mergedInto`, redirects or aliases [SY-03,
  REPORTED]. A first link with Merge seeds only a few fields, so local settings
  are lost (`FirstLinkMerger.seedLocalRows`) [SY-04, REPORTED]. Keep mine
  restores the podcast and group but not their memberships and Up next
  removals from the same batch (`MassChangeGuard.keepMine`) [SY-05, traced in
  part]. Each needs a designed merge and multi-device tests, one PR each.
- A position pushed by `ChangeBuilder` drops `dur` and `src`, which the
  trigger stores (`SyncTriggers.kt:244`) [SY-12, traced].
- Responses skip `ProtocolCodec`'s caps and validation in `KtorSyncClient`
  [SY-06]; `invalid` and `quota` rejections are never acted on [SY-09]; an
  auth outbox row stays stuck while password sharing is off [SY-10]; the
  desktop installation fingerprint is never written, so a cloned profile is not
  noticed [SY-11]; `matchClocks` is ignored on HLC receive and a first-link
  pull is held in memory [SY-13] (all REPORTED). An episode played elsewhere
  may re-enter Up next [SY-08, PLAUSIBLE].

## Library, downloads, and sync defects

### Make listening-page actions do what they show

- "Check again" is offered on unavailable YouTube rows and dropped by every
  view model [LU-04]; see the section 'Wire up "Check again"'.
- A VERIFYING download key said Cancel but did nothing [LU-05]; #623 cancels
  it like RESOLVING.
- The podcast page's filtered-empty action said "Show played episodes" but
  cleared every filter; #624 labels it accurately. The page still has no
  episode search, and its filter bank scrolls away.
- Downloads rows document swipe to delete; there is none.
- The group editor's "Show as a tab in Feeds" switch reads `showAsTab`, which
  only the legacy feed browser uses now that the home has no tabs. Retire it or
  give it a meaning on the radio.
- Library's More lacked Backup and restore [LU-03]; [#641](https://github.com/L-K-M/Neutrodyne/pull/641) adds it.
- Up next has no selection mode: its rows emit Select and nothing handles it
  [LU-01]; selection has no Download or Delete download [LU-02]; the episode
  page's More lacks Check again, Show in folder and Delete download [LU-07]
  (all REPORTED).
- Editing a group saves in five transactions, so a failure leaves it half
  changed; use one repository transaction [LB-01, REPORTED]. "Watch on YouTube"
  does nothing when the link cannot be resolved, on five screens; reuse the
  player route's failure message through one helper [LB-02, REPORTED].
- An OPML import with one over-long folder said "2 group names were
  shortened", because the classifier's per-entry count was added to the
  adapter's per-group one [L-14]; [#638](https://github.com/L-K-M/Neutrodyne/pull/638) counts distinct names.

### Fix download storage limits and notices

`DownloadDao.downloadedEpisodeIds` was not chunked at SQLite's bind limit, so a
large library could throw (`DownloadDao.kt:29`); #622 chunks it at the existing
500-row download bind size. The storage cap applies only to automatic
downloads; `DownloadQuota`'s own contract says admission is AUTO-only, so the
manual case is an owner decision rather than a clear defect. Android's "SD card
is not available" notice has no action, so the setting that fixes it is
unreachable (`DownloadNotices.kt`). Test with 1,500 downloaded episodes, a cap
reached by a manual download, and an ejected card.

Also in downloads: engine removals bypass `claimMutex`; `Attempt.finish` drains
deferred deletes without the mutex the contract names [DL-01, PLAUSIBLE; write a
race test first]. A changed enclosure URL never revives a failed download,
because `requeueChangedEnclosures` has no production caller although design 07
wires it to refresh [DL-02, traced]. The YOUTUBE_PAUSED banner is never emitted
[DL-03, REPORTED].

In the data layer: a restore while linked calls `UnsubscribeUseCase`, which
pauses and flushes the position, from inside the write transaction, so it can
wait on its own writer (`RestoreMerger.kt:182-190,506-511`) [DA-01, PLAUSIBLE;
needs an Android test with a playing episode]. One IOException ends settings
observation until the next collection, which on the desktop means until
relaunch (`SettingStore.kt:109-114`) [DA-03, traced]. Files are deleted inside
write transactions, against design 02 [DA-04, REPORTED].

### Keep sync and settings drafts and copy intact

Sync setup, Settings > Sync, the "Link another device" sheet and Settings >
Discover keep drafts in `remember`, so rotation loses a typed invite code,
username, server address or country; use `rememberSaveable`. The first-link
choice cards print their counts without plurals ("1 groups"). Sync screens have
no Back key, unlike every other settings page.

### Fill the Android platform surfaces

Android has no app shortcuts and no Quick Settings tile;
`settings.gradle.kts:121` reserves `:feature:widgets` for a v1.x widget. The
media notification is the unbranded Media3 default, and new-episode
notifications carry no artwork. Start with a Play or Pause tile and a shortcut
to the last playlist.

Android shell defects: tapping the "YouTube is broken" notification went
nowhere, because it targeted `MainActivity` and `IntentRouter` dropped the route
as external [AS-01]; [#652](https://github.com/L-K-M/Neutrodyne/pull/652) routes it through the internal alias. An
import that finishes in the background posts no notification, because
"visible" follows the ViewModel's life, not the screen's [AS-02]; the startup
failure screen has no Send report [AS-03]; the keyboard covers low fields in
sync setup [AS-04]; "Ready to review" stays in the shade after the import is
done [AS-05] (all REPORTED). A `SecurityException` from `notify` may kill the
breaker notifier's collector [AS-06, PLAUSIBLE]. R8 may drop the ACRA
redactor, which is found only through `META-INF/services`; check the release
APK, since a redactor lost in release would matter [AS-07, PLAUSIBLE].

Discover, import, settings and sync screens (all REPORTED, none re-read):
sharing several files opens the first only [DI-01]; shared text over 4 KB is
ignored without a word [DI-02]; a dropped `.txt` gives the wrong error [DI-03];
YouTube search clears its hits when the capability flag flips [DI-04]; a
failed restore check of the server library has no Retry [DI-05]; Import from
URL is absent [DI-06]. Forget server is several writes, so a failure
half-forgets it [SE-01]; "What syncs" is static text whatever is switched on
[SE-02]; the home sync summary drops the last-sync time on an error [SE-03];
the exported diagnostics file always suggests the same name [SE-04]; the
YouTube engine's diagnostics section is thin [SE-05].

## More ideas from the polish pass

### Offer an index for long programmes

The programme is a lazy pager with no way to jump. A radio-appropriate band of
A-Z or date keys along the tray edge, or a tuning-dial style jump strip that
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
Keep it quiet when off and honour the existing timer controls. A running sleep
timer is invisible everywhere else too [G-06].

### More small ideas from the product audit

Each is a short prototype; keep the ones that read as this radio and no other.
An ON AIR slip for an episode playing from outside the tuned playlist; a
PLEASE STAND BY card while a stream buffers; a paper clip that files a slip
under Up next; a speed key on the faceplate; a "Wireless bulletin" refresh card
that lists what arrived; an inbox-pressure gauge for unplayed hours. The
now-playing episode has no artwork on the only full player [G-07]: the tuned
episode's cover could sit behind the grille cloth, barely visible through the
louvres, and show fully on hover [Q-03].

### Put unplayed counts on the station chooser rows

`RadioInsert` rows print only the playlist name and the active check, while
`RadioPlaylist.podcastCount` and the `radio_meta` string ("%1$d podcasts ·
%2$d episodes") are defined but never drawn. `FeedRepository.observeGroupCounts`
already streams per-group counts and `BrassCountBadge` exists; a badge per
chooser row (and/or `radio_meta` under the enamel sign) would make the tuned
station's size visible at a glance. Needs the same "new since" window the
library tabs use.

## Prototypes

### Make the Mac useful without the main cabinet open

Prototype a small always-on-top receiver with current title, play, skip, elapsed
time and an Open Radio action. Drive it from the existing desktop playback source
and the mini-player action semantics. It should never start a second audio
session. Design close and regular-window restoration against the existing tray
and quit behavior. Exercise VoiceOver, keyboard-only use, full-screen Spaces,
and unplugging a display before considering cross-platform support.

### Offer practical station presets

A few optional enamel preset keys could bring favourite groups within one click
or keypress. Use actual groups and retain the existing chooser for the full
list. Show active state and unplayed count only when useful. Reordering should
be deliberate, with no accidental membership changes. Prototype three presets
on wide screens and evaluate whether they save time. Leave keybindings unset
until they can coexist with desktop menu accelerators; Cmd+Option+1 to 6, like
car-radio presets, is a candidate [Q-05].

### Use the valves for small, useful moments

The lamp or valve can acknowledge a station change or a finished episode with a
brief warm change. Use the same accessible status text and honor reduced motion.
Keep normal playback static and never introduce button sounds into the audio.
The warm-up is merged (#592): the filaments brighten over 1.8 s when the radio is
switched on and cool over 0.9 s when it is switched off, static while playing
and instant with reduced motion. The dial paper's backlight (`dialPaper`
gradient) could warm with it for one gradient parameter. In the sleep timer's
last minute the tubes could cool with the volume fade, so the radio visibly
winds down [Q-07]. The valves followed `playing`, so they went out on every
stall and stayed dark while starting, against 08's "warm while the switch is
ON" [R-01]; [#643](https://github.com/L-K-M/Neutrodyne/pull/643) lights them from the switch.

### Give the signal meter a buffering job

The status lamp now says BUFFERING, but a needle is a better fit for an
uncertain stream: rest it while ready, let it hunt while buffering, and park it
on an issue. Reuse `NeedleGauge` beside the status lamp, drive it from the same
phase, and honor reduced motion. Measure idle CPU; if the needle animates, it
must stop the moment the stream settles. The alternative is a magic-eye tuning
indicator (a 6E5-style green eye, open while playing, wavering while buffering,
closed on error). Pick one of the two, not both.

### Recall the last tuned station

A small ivory key that returns to the previous playlist is the radio equivalent
of a station preset and costs nothing when unused. Keep it explicit (a long
press or a dedicated key) so it cannot fire by accident, and remember only
within the session until a persistence decision is made.

### Design an evening listening experience

Make an optional player display that dims secondary brass and highlights the
remaining sleep timer close to the Play switch. Use the existing PO-55 cabinet
and materials. Preserve contrast and focus indicators. Keep the timer controls
visible and check that the idle display causes no extra frame activity. Review
the product decision before implementing a new user preference. A mechanical
sleep timer, a wind-up knob on the faceplate that visibly unwinds as the timer
runs, could replace the sleep dialog there.

### Prototype chapter marks on the tuning scale

Try small index marks along the existing ruler for an episode with chapters.
Show the current chapter name on deliberate pointer or keyboard inspection, and
make it reachable by assistive technology. Use existing chapter times and seek
handling. Compare no chapters, dense short chapters, a very long title and
unknown duration. Keep the plain unchaptered dial, preserve arbitrary seeking
and avoid extra permanent controls. Decide from a full-size and a compact render
whether this earns space. Prototype slightly longer brass ticks, cluster marks
too close for a pointer to resolve, and test late-arriving chapter data with four
and eighty chapters.

### Fill the speaker grille on wide windows

At 1200 x 800 a grille the size of the slip tray holds nothing. Tuck a paper
"station log" card into its frame: the next three Up next slips, or the tuned
episode's show-notes summary. Compare against the plain grille at 700 and
1200 dp, and keep the card out of the layout when the grille is too small.

### Keep a broadcast log

A paper ticker-tape in the speaker grille on wide windows (see "Fill the speaker
grille on wide windows") that prints what played, with a share action. Listening
history is a v1.x non-goal, so make it an opt-in artifact and do not sync it.

### Show the time when idle

When no episode is loaded, the Nixie tubes can show the time; when an episode
loads they become the counter. Quiet, and it gives the receiver a reason to sit
on a desk. Honour reduced motion and do not animate the tubes when idle.

### Try small mechanical responses

Each must respect reduced motion and add no per-frame work while idle.

- The needle travels to a newly selected episode's position with a short
  spring instead of jumping.
- A flick of the tuning knob sweeps the needle with a short ease and settles on
  the nearest chapter or minute mark.
- Haptics beyond the switches and the discard threshold (#586): a `SegmentTick`
  on the ivory keys, and light ticks when scrubbing across chapter marks or
  whole minutes.
- A short burst of tuning static while the station changes, once control sounds
  exist, with the needle sweeping the scale.
- A station-identification chime when a group is first tuned, off by default,
  honouring control sounds and reduced motion.
- A sign-off when the last slip of a playlist is discarded: the Nixie reads
  `--:--`, the valves cool, and the paper says the station has closed for the
  night, with keys to another station or Discover; a hand-lettered "Off the
  air" card gives the time and the next thing to do, another station with
  unplayed episodes [Q-08].
- A paper spike in the tray corner that stacks discarded slips; tapping it
  recalls the last one, as Undo does.

## Owner decisions: documentation the code contradicts

Each needs a decision: build it, or correct the document.

- `privacy.crash_reports` and its Privacy-page switch are documented (09 and 08)
  but not registered, read or rendered.
- The group media filter and "hide older than N days" are documented, but no UI
  sets them (`FeedRepository.setHideOlderThanDays` has no production caller).
- The Android in-app language picker exists only in tests
  (`BuildInfo.shippedLocales`); the desktop Language row and the Windows Access
  Bridge row are missing.
- Podcast and group settings document a Playback section (speed, skip silence)
  that is not rendered.
- Settings > Feeds documents "Show Ungrouped tab" and "Manage groups". The
  Library overflow lacks "Backup and restore" (in review: [#641](https://github.com/L-K-M/Neutrodyne/pull/641)).
  About and Privacy lack a "Privacy policy" row. 05's "Import from URL" is missing.
- `CHANGELOG.md` still reads `[Unreleased]` with only M0a.1 at version 1.0.0.
- Transcripts are parsed and stored but never shown; the documents defer them.
- Not documented anywhere, an idea: OPML auto-sync from a remote list.
- The radio's `fontScale` rule contradicts 08's platform table. The Android
  table says layouts switch on `fontScale >= 1.5` and never compute sizes from
  it; the radio home scales `minimumHeight`, key widths, cap sizes and the Nixie
  glass from `fontScale`. Record a dated amendment or align the table; the pure
  `radioLayout` now makes the absolute-threshold/scaled-minimum split explicit
  and tested.
- The desktop window title rule says "while playing" but macOS convention keeps
  the current title while an episode is loaded; decide before changing
  `NeutrodyneWindow.kt:304` [MS-01].
- The tubes go dark on every cabinet page while audio plays [V-17]; 08 documents
  that the pages' decks stay unlit. Lighting them while audio plays ("it is one
  radio", [Q-06]) would reverse that, so it is the owner's call.
- External play under ASK grants one item, while design 06 describes a standing
  grant; the code's comment says the single grant is deliberate [A-08]. Correct
  06 or the code.
- Arrow keys during playback load every slip they pass [L-17]; see "Settle
  arrow-key tuning before loading while playing" for the two options.
- The `LoginAttempts` cap trade-off [SERVER-04]: see "Harden the login buckets
  and body budget".

## Checked and cleared

- **Elapsed time after a seek.** A suspected freeze does not exist: `seek()`
  populates `seeks` only in the not-loaded branch, so a loaded needle seek or
  skip keeps receiving ticks.
- **Update-dot announcement.** The radio Menu key's badge description merges
  with its label (`mergeDescendants=true`), so it does not double-announce.
- **Focused-field disposal.** `TextFieldFocusTest` proves a focused field
  leaving composition releases the shortcut stand-down.
- **Stock touch-target expansion.** `Modifier.minimumInteractiveComponentSize()`
  did not widen the pointer region in a desktop probe, so do not assume it
  satisfies the 48 dp rule here.
- **The storage cap and manual downloads.** `DownloadQuota` is AUTO-only by
  design; a manual download ignoring the cap is intended, not a defect.
- **The radio slip role.** `Modifier.selectable` does not default to
  `Role.Tab`; the earlier claim that the tree reads a list of tabs is false.
- **Android IME.** `RadioCabinetPage` applies `aboveKeyboard(...,
  WindowInsets.ime)` to every cabinet page, so the earlier "no `imePadding`
  anywhere" claim was stale.
- **`rememberClickSound` staleness.** The helper's `remember(sounds, sound,
  onClick)` keys include `onClick`, so a new callback is tracked; the reviewer's
  stale-lambda concern does not apply.
- **Claims refuted in earlier passes.** `onRows` re-emits identical state
  (StateFlow conflates it). The Nixie caption uppercases a Turkish i wrongly. A
  deep link is lost on process death (the stack restores it). The RTL swipe is
  broken (the offset is RTL-aware). Up next headings are spoken uppercase. A
  podcast description collapses on re-emission. The faceplate header ignores
  RTL (`placeRelative` mirrors it). Go menu items push top-level keys onto the
  current stack (`navigateFromPlayer` selects tabs for `TopLevelKey`). The macOS
  tray never appears. Completed downloads with a null size are marked missing. A
  zero-byte temp file skips adoption. The host-permit map grows without bound. A
  manual refresh that ignores the interval is a bug (the bypass is intended). An
  arrow-key focus request on a slip removed mid-scroll crashes (Compose UI
  1.12.1's `requestFocus()` on a detached requester prints a warning and returns
  false).
- **Window placement and restore.** A live launch on a 1920 x 1080 Mac with
  30/90 insets showed the window clamped to `(720, 190)`; after the
  inverse-transform conversion it is centred at `(360, 140)`, and a window moved
  to `(200, 120, 1000, 700)` reopened there.

- **Feed fetch DNS pinning.** The earlier item asked to pin the server's feed
  fetch to its checked DNS answers. `HttpServerFeedFetcher.kt:29-30` sets
  `Proxy.NO_PROXY` and `FeedDns`, and OkHttp connects only to the addresses
  `Dns.lookup` returned, which `FeedDns` has checked; every redirect hop resolves
  through it again. Rebinding between check and connect is not possible; the
  remaining edge was the NAT64 form (see "Smaller server fixes").
- **Android Play on an idle player after a stop.** Media3's session play
  handling prepares it; see the parity list for the untested error variant.
- **Claims refuted in the Opus 5.5 pass.** Paging "never appends past 80"
  (`LazyPagingItems.itemSnapshotList` is replaced on each presenter event in
  paging-compose 3.5, so `remember(snapshot)` follows it). Discover runs an
  IMMEDIATE search against undisclosed providers (`restartSearch` returns while
  the disclosed and current configurations differ). Both settings sentinels
  belong in one file (they live in different files by design, and a test pins
  that). The diagnostics cursor prefix leaks (eight characters of an opaque
  cursor, documented). `AC/DC` searching instead of opening a link (a
  documented trade-off with a test). Sync diagnostics leak account and device
  names (design 10:1257 specifies both). The import fix-up accepts an empty URL
  (the repository validates and the screen shows its typed error). The verbose
  log switch sticks on after a failed write (it is bound to the stored value).
  Turning off password sharing needs a confirmation (turning a disclosure off is
  the safe side). Show-note, banner and chapter images decode at full size
  (Coil 3's `AsyncImage` installs a constraints size resolver when the request
  has no size). The sleep sheet preselects 30 min while Off (08:2148 preselects
  the last choice; its readability belongs to the Material-surfaces item [V-04]).
  A short query plus Enter does nothing [DI-06]: `DiscoverRoute.submit` opens
  the directory search for any non-empty query that is not a link.
  `downloadedEpisodeIds` fails above 999 ids (the app ships
  the bundled SQLite driver, whose variable limit is 32,766; #622 chunks it
  anyway). The paging and Coil items are argued from how those libraries
  behave, without opening their sources.

## Native verification on proposed fixes

Each fix below still needs the named device or VoiceOver/TalkBack check. Do not
claim it from a host test alone.

- With VoiceOver on macOS and TalkBack on Android, change a populated Discover
  search while a slow provider responds; check the label names the search that
  produced the visible rows and that rapid typing avoids repeated announcements.
  [Discover retained results](https://github.com/L-K-M/Neutrodyne/pull/594)
- Play a stream with no announced duration; check one duration legend, the
  advancing elapsed counter and a screen reader skipping the inactive dial.
  [Unknown-duration dial](https://github.com/L-K-M/Neutrodyne/pull/595)
- On macOS, leave a podcast with a saved position in the middle; press Previous
  within three seconds of the next episode starting and after three seconds, and
  check pause preservation. [Desktop Previous](https://github.com/L-K-M/Neutrodyne/pull/596)
- Scrub a streaming and a downloaded episode on macOS; check pointer
  cancellation, resumption and the number of engine seeks and writes.
  [Seek on release](https://github.com/L-K-M/Neutrodyne/pull/565),
  [receiver drag tests](https://github.com/L-K-M/Neutrodyne/pull/571)
- Invoke Mark played from VoiceOver and TalkBack, then check Undo and its
  return focus; verify the desktop Delete path.
  [Programme slip action](https://github.com/L-K-M/Neutrodyne/pull/566),
  [accessible discard](https://github.com/L-K-M/Neutrodyne/pull/563)
- Verify the printed quarter times against needle position and seeking on an
  RTL device. [RTL time scale](https://github.com/L-K-M/Neutrodyne/pull/567)
- Open the paper playlist insert with a non-first group active; check VoiceOver
  announces it as selected and keyboard focus starts on its checked row.
  [Tuned playlist indicator](https://github.com/L-K-M/Neutrodyne/pull/568)
- Launch the desktop app ten times from a fresh data directory on a slow disk
  and confirm it never exits before the window.
  [Deferred refresh controller](https://github.com/L-K-M/Neutrodyne/pull/581)
- In Discover, search "Star Wars: The Clone Wars", "AC/DC" and "Dr. Death", then
  paste an address with a no-break space or curly quotes and confirm it never
  reaches the directory. [Programme names search](https://github.com/L-K-M/Neutrodyne/pull/582)
- On a fresh install, follow the empty radio's Discover, Import and Library
  keys; check the sign gives way once a group exists and that all three keys
  show on a small phone. [Empty radio tuning](https://github.com/L-K-M/Neutrodyne/pull/583)
- With VoiceOver and TalkBack, move by headings through Library, Settings and
  the Menu plate, open a Diagnostics section and retune the Library dial with
  reduced motion on. [Headings and toggles](https://github.com/L-K-M/Neutrodyne/pull/584)
- Compare the mini radio's needle window and a downloading row at 1x and 2x.
  [Thin dial windows](https://github.com/L-K-M/Neutrodyne/pull/585)
- On a phone with touch feedback on and off, throw the radio switch and a
  settings switch and swipe a slip past the discard threshold; confirm nothing
  buzzes. [Haptics](https://github.com/L-K-M/Neutrodyne/pull/586)
- Fill a device's storage until the library cannot open and use Manage storage
  on a Pixel and an OEM build. [Manage storage guard](https://github.com/L-K-M/Neutrodyne/pull/587)
- Play a password-protected feed whose enclosure answers 403 on the desktop and
  confirm the Enter password action appears.
  [Forbidden enclosure](https://github.com/L-K-M/Neutrodyne/pull/588)
- Drag the needle on a slip that is not loaded, start that episode from Up next,
  and confirm the Nixie follows playback and the radio's ON resumes rather than
  restarts. [Parked seek](https://github.com/L-K-M/Neutrodyne/pull/589)
- Switch the radio on and off on a phone and a Retina Mac and watch the valves
  warm and cool; repeat with reduced motion and check idle CPU while playing.
  [Valves warm up](https://github.com/L-K-M/Neutrodyne/pull/592)
- Link a second device against a server and read the Merge card, the address
  field's label and an update card's size in English and German.
  [Sync and update copy](https://github.com/L-K-M/Neutrodyne/pull/590)
- With TalkBack and VoiceOver in English and German, focus the radio's counter
  at 1:01:01 and after 12 hours and listen for plural forms without zero parts.
  [Spoken elapsed time](https://github.com/L-K-M/Neutrodyne/pull/591)
- On a phone, play an episode to its last half minute, tap another episode, and
  confirm the first one shows as played on Android as on the desktop.
  [New start marks played](https://github.com/L-K-M/Neutrodyne/pull/593)
- Arrow-key tuning: press Down from a slip at the bottom edge of the viewport
  and confirm the list reveals it without snapping it to the top; repeat with a
  slip already fully visible. [PR #609](https://github.com/L-K-M/Neutrodyne/pull/609)
- With Control sounds on, press the radio Menu key and confirm it sounds like
  the other ivory keys; check the label at 200% text.
  [PR #610](https://github.com/L-K-M/Neutrodyne/pull/610)
- With a group whose first page is empty and later pages exist, confirm the
  programme keeps the spinner instead of flashing All clear; fail the load and
  confirm the retry row remains. This landed on main; the closed
  [PR #611](https://github.com/L-K-M/Neutrodyne/pull/611) has the test notes.
- Tap a download in VERIFYING and confirm it cancels like RESOLVING; check the
  row at 1x and 2x. [PR #623](https://github.com/L-K-M/Neutrodyne/pull/623)
- With a downloaded or in-progress filter active and no episodes, confirm the
  podcast page offers "Clear filters" and clears every filter; with only the
  unplayed filter, confirm it still reads "Show played episodes".
  [PR #624](https://github.com/L-K-M/Neutrodyne/pull/624)
- With a server that keeps answering `authorization_pending` past the code
  lifetime, confirm the link flow fails with InvalidCode instead of polling
  forever. [PR #625](https://github.com/L-K-M/Neutrodyne/pull/625)
- On a phone, pause an episode, let another device sync a newer position, then
  resume from a headset button, the media notification and Android Auto;
  confirm each resumes at the synced position. [#654](https://github.com/L-K-M/Neutrodyne/pull/654)
- On a German phone, read the media notification, lock screen and Android Auto
  actions ("30 Sekunden zurück", "Tempo 1,5×") and force a crash to read the
  crash dialog. [#653](https://github.com/L-K-M/Neutrodyne/pull/653)
- With the YouTube engine broken, tap its notification and confirm it opens
  Settings > YouTube. [#652](https://github.com/L-K-M/Neutrodyne/pull/652)
- On Windows with 125% and 150% scaling and on a Linux HiDPI desktop, launch
  from a clean home and confirm the window can shrink to 600 x 480. [#649](https://github.com/L-K-M/Neutrodyne/pull/649)
- On the desktop, play a two-episode playlist with the sleep timer at End of
  episode, armed before and after the first episode's last minute; confirm
  audio stops at the boundary both times. [#656](https://github.com/L-K-M/Neutrodyne/pull/656)
- On a Mac compact keyboard, press delete on a focused slip, Up next row,
  Library tile and finished download; with VoiceOver on, confirm VO+Space on a
  slip still selects it while plain Space toggles playback. [#636](https://github.com/L-K-M/Neutrodyne/pull/636) [#642](https://github.com/L-K-M/Neutrodyne/pull/642)
- Open Help > Keyboard shortcuts on macOS and Windows and compare each chord
  with the menus. [#644](https://github.com/L-K-M/Neutrodyne/pull/644)
- Launch the desktop app on a 1920 x 1080 Mac and confirm the first window is
  centred; move it, quit, relaunch and confirm it returns to the same rectangle.
  Repeat on a second display, after a display re-arrangement, and with the window
  maximised. [PR #614](https://github.com/L-K-M/Neutrodyne/pull/614),
  [PR #633](https://github.com/L-K-M/Neutrodyne/pull/633)
