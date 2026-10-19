# M10 I1 local integration report

Branch `impl/m10-p1`, worktree `/home/paseo/nd-wt/m10-p1`.
Local integration only. No push, PR, GitHub API operation, CI, external review
(0 rounds) or merge to main. M10 acceptance remains open.

## Frozen inputs and commits

Initial status was clean at `b89f8f85`; there were no uncommitted changes or
untracked scratch files to discard. Existing `.nd-context/` and build outputs
were preserved. No helper, nested agent, quota probe, monitor, heartbeat,
new worktree, sibling cleanup or global Gradle change was used.

| Input | Exact tip |
| --- | --- |
| Initial integration | `b89f8f85eed13d1266e973f989dc9a273101d9d7` |
| Fetched main | `95a769c11070e7727103a131d1020912b761e65f` |
| Current M3 integration | `8f34b2cb09adbaf12d0be2d486350b1f007f7532` |
| Frozen M8 W8 | `9ac0d7204e8cc8c175cacee3ce553cefde102ab9` |
| Already integrated W2 | `0e7763efbb77fa1a4b40d0ed02c32841521cc041` |
| Already integrated W3 | `c5ba68c9fe025a6edb1be568734ac01a9710bcd9` |
| W4 common publication patch | `24de1e683ec3466861fca4f55b6613632ae0a0ec` |
| Completed W4 | `40ae6955fe0d299b4c35f7a870522bb9eb4effd8` |

Merged in the requested order: `03d3e310` (main), `28930122` (M3),
`9003705e` (M8 W8), `b39125be` (W4). `c8d2179d` contains the demonstrated
integration repairs, formatting and owning-document qualifications.
The shared `origin/main` ref advanced elsewhere after the initial fetch;
this job did not chase it or the moving M8 W9 branch.
No commits were amended or rewritten, and no hooks were skipped.
The first merge body's command quoting retained newline escape sequences;
its subject is valid, and that commit was left intact under the no-rewrite constraint.

## Conflict reconciliation and preservation

- Main initially produced 84 conflicted paths because reviewed M3 layers
  were squashed over older ancestry. The actual pre-review L8 checkpoint
  `0703bd09` differs from fetched main in 25 files. Three-way hunk merging
  against that checkpoint reduced substantive overlaps to four files.
  Keep M8's NewPipe export preparation and expanded export test; retain
  main's XML-character validation and test beside later salvage cases.
  The final main merge changes 17 files. This used no blanket merge
  strategy or old-file checkout. Original conflicts and the reviewed delta
  are retained in `build/review-m10-i1/main-*.diff` and path lists.
- M3: seven conflicted paths. Union CI's first-launch selector test with
  playback/download/discovery/system assertions. Keep later HANDOFF state,
  import preview upgrade, download strings/graphs and graph tests.
  Retain M3's startup baseline/recovery, OPML/archive fixes, complete
  first-launch selectors, SDK 34 graph sandbox and asynchronous snackbar
  test repair. The later M3 candidate notes remain alongside M5–M8 history.
- M8 W8: seven conflicted episode/podcast/player source and fixture paths.
  Combine capability and external-open dependencies with artwork colours;
  retain both flow stages, tint tests and external fallback tests. Preserve
  mark-played/Undo, settings, lazy external/chapter/episode dependencies,
  and request/claim/runnable guards. Download controller/planner/DAO guard
  files are byte-identical to the frozen W8 versions.
- W4: one store conflict. Both entry points share W2's `applyBatchLocked`
  row/file mechanics. Lock order remains file mutex, writer transaction,
  then index refresh after commit. Mosaic source validation runs in that
  transaction; raster rendering and colour extraction run outside the
  publication lock. W2 colours, byte-version identity, retry metadata,
  stable local snapshots and row/digest backfill guards are retained.
  Existing provider fallback locking is unchanged.
- Preserve distinct source signatures before the 10 s debounce, excluding
  own g- output, stale/deleted-source rejection and continuation, sequential
  bounded Android rendering and Auto's versioned local g- URIs. Worker
  fixtures carry W2 sampler/dispatcher arguments and W4's mosaic provider.
  The real W2 `ArtworkRepositoryImpl` binding supplies W3 consumers.

Detailed conflicts: `m3-conflicts.diff`, `w8-conflicts.diff`,
`w4-conflicts.diff` and corresponding path lists under the evidence root.
No schema/entity/index, dependency version/set or parser mutation #211
implementation was changed by I1. Incoming parser review fixes were kept.

## Demonstrated integration defects

1. Eager player artwork injection resolved the real database before the
   startup gate. New `idlePlayerWithArtworkCanBeCreatedOnTheEventThread`
   failed first with `NeutrodyneDatabase required on the UI thread before
   the start-up gate`, through `ArtworkRepositoryImpl`. Retain a provider
   until there is current artwork. Both real shell graphs and the new case
   subsequently pass; tint-switch tests still pass.
2. The existing desktop licence journey clicked a clipped Settings row.
   It failed in the focused run and in isolation. A temporary semantics
   trace showed Licences at y=812..836 in a 768 px viewport and the Settings
   list still open. Added Settings rows moved it offscreen. Scroll before
   clicking; keep the 5 s timeout. Diagnostic edits were removed. The full
   desktop shell suite then passed 126 tests with two existing skips.
3. The broad dependency gate failed Licensee on MCU's common/Android/JVM
   5.0.1 POM licence URL, despite their explicit MIT declaration. Apply
   three exact-coordinate/version allowances in the one shared policy,
   following the existing scoped metadata pattern. No global URL or new
   licence class is allowed. Cached published POMs are preserved under
   `mcu-license-metadata/`. Final gate result is recorded below.

## Validation

Evidence root: `build/review-m10-i1/`. All Gradle commands use JDK 21 at
`/home/paseo/.local/jdks/jdk21`, `ANDROID_HOME=/home/paseo/android-sdk`,
`LANG=LC_ALL=C.UTF-8`, source `/home/paseo/.local/gfx/env.sh`, and:

```sh
./gradlew --no-daemon --max-workers=2 -Pkotlin.incremental=false   -Pkotlin.compiler.execution.strategy=in-process
```

Test commands additionally use `-Proborazzi.test.verify=true`; multi-task
checks use `--continue`. Only one Gradle invocation ran at a time in this
worktree. Cache/up-to-date results are distinguished by the retained logs.
`previous-test-xml.zip` preserves 52 pre-run XML reports. Further XML and
screenshot archives retain failures before subsequent commands replaced
standard Gradle outputs. Android/desktop group captures share output
filenames, so the final image directory alone cannot preserve both legs.

- Standalone Spotless apply/check runs passed. The final code check is
  `13-spotless-check.log`; formatting only touched integrated hunks.
- `02-player-startup-red.log`: 1 test failed before the provider repair.
- `05-focused.log`: task-selection failure before tests, because
  `:core:designsystem:testAndroidHostTest` does not exist. Corrected the
  selection; no test success is attributed to that command.
- `06-focused.log`: complete focused command, 2,971 passed / 29 failed /
  3 skipped, with both shell compilations successful. Of its failures,
  28 are screenshot references and one was the now-repaired licence test.
- `07-licences-isolated.log`: unchanged licence test failed again;
  `08-licences-diagnostic.log` captured the offscreen-row mechanism.
- `11-gates.log`: shell retest passed 126 / 0 / 2; release lint passed
  with zero issues for app and playback. Licensee's three failures exposed
  the MCU metadata recognition gap. They are not counted as approval.
- First-launch selector fixtures: 10 passed (`first-launch-selectors.log`).

Initial focused per-task results (pass / failure / skip):

| Task | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| `:core:artwork:desktopTest` | 39 | 0 | 0 |
| `:core:artwork:testAndroidHostTest` | 73 | 0 | 0 |
| `:core:database:desktopTest` | 174 | 0 | 0 |
| `:core:data:desktopTest` | 1404 | 0 | 1 |
| `:core:data:testAndroidHostTest` | 179 | 0 | 0 |
| `:core:designsystem:desktopTest` | 21 | 0 | 0 |
| `:core:ui:desktopTest` | 186 | 6 | 0 |
| `:core:ui:testAndroidHostTest` | 141 | 19 | 0 |
| `:feature:player:desktopTest` | 69 | 0 | 0 |
| `:feature:episode:desktopTest` | 52 | 0 | 0 |
| `:feature:podcast:desktopTest` | 125 | 3 | 0 |
| `:feature:settings:desktopTest` | 59 | 0 | 0 |
| `:playback:impl:testDebugUnitTest` | 161 | 0 | 0 |
| `:app:testDebugUnitTest` | 163 | 0 | 0 |
| `:desktopApp:test` | 125 | 1 | 2 |

The focused skips are Windows DPAPI and the two native desktop file-menu
picker cases. All 161 playback adapter tests passed with max-workers=2;
earlier W4 convergence failures remain unexplained, not disproved by this
pass. The incoming parent snackbar race repair was preserved and its
surrounding core UI tests passed apart from screenshot references.

Final code checks at `c8d2179dca23a44c1598976839ed890c0f825103`:

- `13-spotless-check.log`: standalone Spotless check passed.
- `14-gates.log`: **passed**, 8m 22s, 820 tasks executed / 20 from cache /
  19 up to date. All 49 dependency policies, five module graphs, banned API
  scan and MCU Licensee checks pass. App and playback release lint each
  report zero issues; XML copies are retained beside the log. Lint emits
  its existing baseline-variant note, with no errors or warnings.
- `15-full-gate.log`: attempted the exact complete selectors
  `allTests test mutationTest --continue` with the common flags and
  screenshot verification. **Interrupted, exit 130**, not a completed gate.
  Initially free disk was 9.8 GiB and fell near 6 GiB; heavy work was withheld.
  Space later rose near 20 GiB without this job deleting anything. During
  the full attempt it fell to 9.6 GiB, so the explicit low-disk instruction
  required stopping it. Only this worktree's wrapper received SIGINT;
  its recorded daemon/test descendants exited too. No other process was
  signalled. See `full-gate-interruption.json` for the receipt.
- No complete allTests/test/mutationTest totals or mutation success are
  claimed. The full attempt had not reached mutation execution. Its
  partial XML inventory includes prior results for unreached tasks and
  must not be summed as a complete run. Resume the full gate when space
  permits; existing screenshot-reference failures still require W9 review.

The first complete focused command remains red for 28 screenshot cases;
the additional licence-test failure was diagnosed and repaired. The
corrected full desktop shell retest is separately archived (126 pass,
0 failure, 2 skips), rather than relabelling the earlier command as green.

## Owning documents and remaining contracts

I1 adds the scoped MCU metadata qualification to 01, records Import and
Downloads average-colour plumbing gaps in 08, and combines integration
status in HANDOFF. W4's 06/08 raster and Auto notes are retained. No D-id
change or PLAN requirement waiver was needed. Both documentation checkers
pass after the final report/HANDOFF edit (0 problems across 14 files and
0 invalid diagrams out of 78). The fixed-list checkers exclude `.nd-context`.

| Owner | Remaining contract |
| --- | --- |
| W5 | SharedKeys/scopes/modifier, two-tier frame proof and status-bar ownership/contrast/restoration |
| W6 | Single player presentation, persistent panel state and both shells, posture geometry, tabletop/landscape and accessible controls |
| W7 | Density and empty-state completion; carry persisted averages through Import report projections and Downloads CoverArt calls, preserving W2/W3 ownership |
| W8 | Accessibility/action inventory across all landed destinations, row stacking and real ATF/device evidence |
| W9 | Review/update existing theme references; PR/FULL matrix, isolated platform outputs, reference art, pseudo-locales, image budgets and reviewed comparisons |
| W10 | Safe benchmark-only seed with valid keys/real pinned covers, readiness, journeys and physical PB3 evidence |
| W11 | Serial prerequisite integration, latest-SHA CI/review, acceptance ledger and required device/release execution |

The 28 focused image failures are six desktop group cases, 19 Android
group cases and three desktop podcast preview cases. The app theme changed
from the placeholder scheme to W3's amber/navy MCU roles; reference and
actual backgrounds differ. No references were rerecorded or auto-accepted.
This is an open W9 review/reference gap, not a matrix pass. The interrupted full gate does not replace that focused evidence.
Production desktop pixels/artwork scheduling stay MD1; native desktop OS
UX is MD4. Physical Auto/artwork, device accessibility, hardware-bitmap
transitions, owner brand/golden review and reference-device performance
remain pending. Local host success does not close M10.

## Measured diff sizes

Sizes below count added/deleted text characters, excluding patch metadata and
context; they include tests, documentation and this staged report. They are
local diff projections, not published PRs. Exact bases and raw unified-diff
sizes are in `build/review-m10-i1/sizes-final.json`.

| Comparison | Files | Added lines | Deleted lines | Changed text characters | Binary files | PNG bytes at tip |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Fetched main | 1055 | 118661 | 2017 | 5300761 | 22 | 88080 |
| Tracked main at measurement | 1029 | 113947 | 1931 | 5073322 | 22 | 88080 |
| Frozen M8 W8 | 108 | 4572 | 293 | 238891 | 0 | 0 |
| Initial integration | 131 | 3625 | 318 | 194602 | 0 | 0 |
| W1–W3 component | 64 | 2862 | 197 | 143194 | 0 | 0 |
| W4 component | 16 | 872 | 39 | 43322 | 0 | 0 |

Tracked main at measurement is `ef5fec26a3fbebec8f1eac0b81ee7dca63eec6fc`; it was measured,
not merged. The fetched-main source remains the frozen tip listed above.
The large main comparisons include unmerged prerequisite milestones;
serialize those prerequisites before assembling P1. The frozen-W8 comparison
also includes later M3 work and exceeds the approximately 225k character PR
cap. It is not the eventual clean P1 base.

The W1–W3 and W4 component budget totals 186,516 changed characters before
I1 repairs/evidence. Components overlap shared files, so this sum is not a
consolidated PR measurement. M10 adds no committed PNG or binary fixture;
the binaries in the main comparisons are prerequisite work. Measure the
assembled P1 diff again against its actual serial base, including W9's
needed references, and regroup around 210k before the 225k cap.
P2/P3 and W5–W11 implementation are absent here; no implemented sizes are
claimed for them.
