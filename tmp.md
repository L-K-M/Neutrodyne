# Radio review (working document)

Review of the skeuomorphic radio home and its shared parts, as merged on
`origin/main` (`f9bd91021`, `41b9be857`, `9af7e7f27`, `3c528281e`, `e4af8904c`,
`ced908ce6`, `94282a3ae`, plus the control-sound commit `446afd6cd` that is
still only on `feat/control-sounds`). The existing backlog is
[ANALYSIS.md](ANALYSIS.md); this document is an independent pass and calls out
what is new, what is confirmed, and what I intend to change.

Method: read every file under
`core/designsystem/.../radio/` and `core/ui/.../radio/`, the production adapter
`feature/feeds/.../RadioViewModel.kt` and `RadioRoute.kt`, the navigation host
`NeutrodyneNavHost.kt`, the existing radio tests, and the PO-51–PO-56 sections
of `docs/design/08-ui-ux.md`. Ran `:core:ui:desktopTest` for the radio package
(green) and wrote throwaway desktop probes for keyboard focus and unknown
durations. No visual inspection was possible in this environment (images are
not readable here), so every visual claim below is derived from the drawing
code, not from a render.

## Confirmed bugs

### B1. The transport status lies while buffering (medium, user-visible)
`RadioPlayer` derives its status from `selected`, `state.playing`,
`state.hasPlayed` and `state.elapsedMs` only:

```
selected == null -> PLAYLIST CLEARED
state.playing    -> NOW PLAYING
hasPlayed/elapsed>0 -> PAUSED
else             -> READY TO PLAY
```

`NowPlaying` already carries `phase: PlayerPhase` (`BUFFERING`, `READY`,
`ENDED`, `ERROR`) and `issue: PlaybackIssue?`
(`playback/api/.../PlaybackStateSource.kt:40`). During buffering
`isPlaying` is false, so the radio reads **READY TO PLAY** (or PAUSED) while
the stream is actually loading; on an error it keeps reading whatever the last
transport state was. The mini player at least draws an amber lamp while
buffering and a red dot on error (`feature/player/.../MiniPlayer.kt:297,419`);
the radio has a status line and a lamp and uses neither for this. Nothing in
`RadioState` carries the phase, so the home cannot show it.

**Fix:** copy `phase`/`issue` into `RadioState` in the existing
`nowPlaying.collect`, and render `BUFFERING` / `ENDED` / `ERROR` with the
existing lamp (amber while buffering, red on error). Planned.

### B2. Unknown durations print five `00:00` labels and a fake end time (medium)
`RadioPlayer.Needle` always prints `radioTimeline(durationMs * index / 4)` for
`0..4` plus, on tall layouts, `radioTimeline(durationMs)` under the dial. When
`durationMs` is null (live streams, feeds without a duration, duration that has
not arrived yet) that is six `00:00` labels and a needle parked at zero. The
seek node still exposes `progressBarRangeInfo` and a `setProgress` action that
always returns false, which is a silent failure for switch access. The design
already flags this (`08-ui-ux.md`, radio home: "Show a quiet inactive dial when
the duration is unknown").

**Fix:** when the duration is unknown, draw the dial without graduations,
labels or needle, print one localized "duration unknown" legend, keep the
elapsed counter, and do not expose progress-bar semantics. Planned as a
follow-up.

### B3. `RadioState.episodeCount` is dead state (medium)
`RadioViewModel.onRows` maintains `episodeCount` (`RadioViewModel.kt:275,296`)
and `RadioRoute` keeps it fresh, but no composable reads it; the dial prints
`index+1 / episodes.size` only when `state.complete`
(`RadioPlayer.kt:160-166`). The design asks for the repository count while
paging. A related dead resource is `radio_meta` ("%1$d podcasts · %2$d
episodes"), which is defined in EN/DE and never referenced, so the radio never
shows how large the tuned playlist is.

**Fix:** show the total while paging. The loaded window index is not the
ordinal once `placeholdersBefore > 0`, so either carry the leading-placeholder
count in `RadioState` or print the count without a position. Needs a decision;
recorded in ANALYSIS.

### B4. Arrow keys do nothing in the programme (medium, desktop convenience)
Verified with a desktop probe: focusing a slip and pressing
`Key.DirectionDown` / `Key.DirectionUp` leaves `selectedId` unchanged. The
global shortcuts only bind ←/→ (skip), Ctrl/Cmd+↑/↓ (volume) and Space
(`NeutrodyneRoot.kt:599-631,664-665`), so plain ↑/↓ are free. Every other
focusable list in the app has a keyboard story (Delete on a slip, Menu/Shift+F10
on a row, Alt+↑/↓ in group management); the radio programme has none, so a
keyboard user must Tab through every slip. **Fix:** handle ↑/↓ on the list,
move the selection, scroll it into view and move focus with it.

### B5. The radio's Menu key is silent (small, spec deviation)
PO-56's table says `KEY_PRESS` is played by "the Menu keys". The radio home's
Menu key is `SmallRadioKey` (`RadioProgramme.kt`), which calls a plain
`clickable(onClick = onClick)` with no `rememberClickSound`. The EnamelSelector
above it does sound. The Menu dialog's `CloseKey` is also silent (it is a
brass plate rather than an ivory key, so this one is arguable). The chooser's
playlist rows (`RadioInsert.kt`) are silent too; they are not in the PO-56
table, but they are the radio's own controls and a key press is the natural
sound. **Fix:** at minimum the Menu key.

### B6. `RadioSwitch` has a 30 × 40 dp touch target (small, accessibility)
`RadioSwitch.kt` sizes itself `SWITCH_WIDTH = 30.dp`, `SWITCH_HEIGHT = 40.dp`.
The codebase asserts 48 dp targets elsewhere (`StepCounterTest`), and
`BatToggle`'s own doc says "the caller sizes it". In dense panels (Import,
Groups, Backup) a 30 × 40 dp target is below the platform minimum and smaller
than every other radio control. The existing backlog asks for measurement
first; a safe change is to keep the painted plate at its render proportion and
reserve a 48 dp target around it without changing row geometry.

### B7. The programme can show "All clear" before the first page lands (small)
`RadioProgramme` shows `All clear.` whenever `episodes.isEmpty()` and
`!loading && !loadError`. `loading` is only true while `refresh` is `Loading`
with an empty snapshot (`RadioRoute.kt:51`). If a group's first loaded window
is empty while later pages exist (or a fast empty refresh follows a slow
append), the radio briefly claims there is nothing left. Gate the empty state
on `state.complete` or on `episodeCount == 0`.

## General issues and questions

- **Paging/Undo reconciliation.** `onRows` defers a snapshot while a Mark
  played write is in flight and drops it on Undo (`RadioViewModel.kt:604-610`);
  the backlog has a tested fix in PR #564. Confirmed by reading, not re-derived.
- **Discard feedback.** `discard` returns early while `writing`, so a second
  swipe during the write is dropped with no feedback (the slip is still
  present, the first write pending). The backlog asks for explicit feedback.
- **`RadioSlip` swipe vs. play key.** The horizontal-drag detector is attached
  to the whole row, including the 48 dp play key; a swipe that starts on the
  play key can discard the episode. Small, but the play key is the most
  tapped target on the row.
- **`RadioSlip` role.** `selectable(selected, onClick = ...)` defaults to
  `Role.Tab`; a programme slip is not a tab. Harmless today, but it makes the
  accessibility tree read a list of tabs.
- **`RadioInsert` focus.** Focus is requested on the first playlist row (or the
  close key when empty). With a non-first group active, the open row is not
  announced as selected; the backlog links PR #568.
- **`RadioInsert` positioning** uses `boundsInWindow` of the faceplate and a
  hand-computed inset (`RadioInsert.kt:60-70`). It works at the tested sizes
  but is sensitive to dialog insets and density; worth a look when the pane
  row lands on wide windows.
- **`RadioProgrammeState.Saver`** persists `chooserOpen` and `playlistId` but
  not `restoreFocus`; a process death while the chooser is open restores the
  chooser with no focus target. Edge case.
- **Dead code:** `RadioProgrammeState.playlistId` is written and read only by
  the scroll-reset effect; fine, but `radioKeyWidth()`'s `Spacer` assumes the
  Menu key's width, which breaks if the label wraps to two lines (the key's
  `Text` has no `maxLines`, so a long translation can grow the column and
  un-centre the sign).

## Performance

- **Every position tick republishes the whole `RadioState`.**
  `RadioViewModel` collects `positionTicks` and copies `RadioState`
  (`RadioViewModel.kt:196-200`), and `RadioRoute` hands that state to
  `RadioScreen`, so the cabinet, programme, dial and player all see a new
  state object once a second while playing. The list keys are stable, so
  `LazyColumn` should skip, but `RadioPlayer`'s `state.episodes.indexOfFirst`
  and the `Needle`/`Nixie` recompute every tick. The backlog's profiling item
  is the right first step; a cheap win is to move `elapsedMs` into a
  `StateFlow<Long>` collected only by the dial and counter leaves.
- **`RadioRoute` recomposes on every paging emission.** `snapshot.items` is
  mapped to `RadioEpisode` twice (once in `LaunchedEffect`, once in
  `RadioScreen` via `state`), and `key(group) { remember(group) ... }` throws
  away the paging items on every group change. Expected; measure before
  changing.
- **`feeds.countUnplayed` is launched for every incomplete snapshot**
  (`RadioViewModel.kt:292-299`), guarded by `rowsRevision`. While a long
  playlist streams pages in, that is one count query per page. Consider
  counting once per generation, or only when the visible window nears the end.
- **`MaterialPlate` rebuilds a `ShaderBrush` per bitmap** with `remember`,
  fine, but `ValveDeck` and `Speaker` redraw full-canvas every frame while the
  window resizes; `Speaker` draws `width/4 + height/4 + 7` lines. Acceptable at
  rest; the backlog asks for frame-time evidence.
- **Nine-patch/three-patch drawing** uses `FilterQuality.High` on every slice
  of every key on every slip. With hundreds of slips composed during a fling,
  that is the most likely jank source. A `FilterQuality.Low`/`None` for the
  small slices, or a cached `Picture`/`PictureLayer`, is worth measuring.

## Missing features

- **No scrollbar on the radio programme.** The shared `NdScrollbar` exists and
  the other scrolling destinations use it; the home list does not. Backlog
  item with PRs #569/#570.
- **No buffering/error/ended status** (B1).
- **No count while paging** (B3).
- **No episode actions on a slip.** Selection and Play only; notes, Play next,
  download and Mark played need the row menu. Backlog item with PRs
  #563/#566.
- **No per-station scroll memory.** Switching playlists resets the list to the
  top (`RadioProgramme.kt:120-127`); returning to a station loses the place.
  Backlog item; needs a product decision on persistence.
- **No first-run journey.** An empty library lands on a cleared radio with no
  route to Discover. Backlog item.
- **No chapter marks on the dial, no station presets, no evening/sleep
  display, no always-on-top receiver.** All in the backlog's prototype list.

## Visual and layout

- **Cabinet fit on short windows.** `RadioScreen` mixes `LocalWindowInfo`
  container height with `BoxWithConstraints` max height, so a window with
  system insets can pick the tall layout while its content area is short
  (backlog). The whole assembly scrolls once `contentHeight > height`, which
  is the intended fallback, but the minimum heights (`500/440/280 dp`) are
  guesses and were not measured.
- **Deck stow threshold.** `DECK_MIN_HEIGHT = 560.dp` is compared against
  `maxHeight` after insets on the home but against `maxHeight` inside
  `RadioCabinetPage` too; the two paths can disagree on the same window.
- **Wood veneer distortion.** `MaterialPlate` draws `WOOD` with
  `ContentScale.FillBounds` and `WOOD_EDGE` with `Crop`
  (`RadioMaterials.kt:84-100`), so the cabinet grain stretches with the
  window. The backlog suggests an aspect-preserving crop.
- **Material hierarchy.** Every surface carries screws, brass, grain and
  glass; the home adds a speaker grill, dial paper, Nixie ghost digits and a
  valve deck in a few hundred pixels. The backlog's "one quiet paper slip,
  crisp sans metadata, mono only for dials" is the right direction.
- **`TuningDial` needle is not under glass.** `dialWindow` is a `drawBehind`
  on the parent `Box`, and the needle is drawn by the scrolling `Row` inside
  it, so the needle sits on top of the glass sheen. Same in `NeedleWindow`
  and `StepCounter`. Cosmetic.
- **`MaterialPlate(METAL)` draws two screws** at the top corners only; the
  rendered faceplates have four. Cosmetic.
- **`RadioScreen` feet/plinth** are plain `Chassis` rectangles with no
 _shadow or recess; on a dark stage they read as floating bars.
- **High-contrast ink.** Many legends are fixed `Ink`/`Cream` regardless of
  `NeutrodyneTheme`'s contrast state (backlog).
- **Sign fallback.** `RadioProgramme` reserves an invisible key's width to
  centre the enamel sign; at 320 dp and 1.3× text the sign ellipsizes
  ("Morning rotation"), and the Menu key label can wrap and break the
  centring. Another branch is on this; recorded.

## Aesthetics: what would make it read as a high-value macOS app

- Consistent light direction (already mostly true: top-left key light, top
  cabinet edge) and one accent (amber) used for state, not decoration.
- A single material per surface: paper slips should be the quietest thing on
  the screen; chrome, brass and wood should be reserved for the cabinet and
  the transport.
- Alignment: the skip keys align to the Nixie glass, the sign is centred
  against an invisible key, the plinth overlaps the cabinet by a fixed 14 dp.
  These are good; the same discipline should extend to the pane rows, where
  each pane currently draws its own shadow and walls.
- Motion: the lever flip, needle sweep and key lamps are the app's character.
  They should be short, eased, and disabled under reduced motion (mostly done:
  `LocalReducedMotion` in `BatToggle`). Check `Crossfade`/`animateTo` calls
  honour it too.
- Native window behaviour (traffic-light space, tray, menuBar) matters as much
  as the cabinet; the backlog's window-placement item is the top native risk.

## Novel / delightful ideas

- **Valves that breathe with playback.** The deck's tubes could warm while
  playing and cool when paused, with a slow ease and reduced-motion off. The
  backlog prototypes this; keep it to one transition and measure idle CPU.
- **A "signal" meter for buffering.** Reuse `NeedleGauge` beside the status
  lamp: while buffering the needle hunts, while playing it rests. Gives the
  skeuomorphic metaphor something to do during the most uncertain moment.
- **Station presets.** Three optional enamel preset keys above the slips for
  favourite groups, with unplayed counts. Backlog prototype.
- **Chapter marks on the dial.** Small brass ticks for chapters, with the
  chapter name on hover/focus and full accessibility. Backlog prototype.
- **Evening mode.** A dimmed display that highlights the sleep-timer remainder
  near the Play switch. Backlog prototype.
- **"Last tuned" recall.** A small ivory key that returns to the previous
  playlist, the radio equivalent of a station preset. Cheap and characterful.
- **A subtle power-on sequence.** On first launch the tubes could warm up over
  a second. Delightful, but it must not delay interaction or run under reduced
  motion; probably not worth it.

## Implementation plan (ranked)

1. **B1 transport status** — self-contained, correct-by-construction, fixes a
   false statement. Branch `fix/radio-transport-status`.
2. **B4 arrow-key selection** — desktop convenience, verified gap, no visual
   change. Branch `feat/radio-arrow-keys`.
3. **B5 Menu key sound** — one-line spec fix, bundle with either of the above
   if the diff stays clean.
4. **B2 unknown duration** — needs a small product decision on the legend;
   record and defer if time is short.
5. Everything else goes to ANALYSIS.md.
