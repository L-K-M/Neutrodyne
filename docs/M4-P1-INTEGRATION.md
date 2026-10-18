# M4 P1 integration record

Integration fix-up on `impl/m4-p1`, based on `545c84f5`, 2026-10-18.
Local commits only; no push, PR, CI run or merge is part of this task.

## Contract corrections

| Contradiction or gap | Resolution | Evidence |
|---|---|---|
| W3 starts require `PlaybackMediaInfo.sortDate`, but W2 omitted it from both its SQL projection and mapper. | Select the existing episode column into `MediaLookupRow` and map it for both get and observe. No entity, index or schema change. | `PlaybackMediaLookupTest` checks the stored date and equality of one-shot and observed metadata. |
| W3 supplies `SessionStart.departure`, but W2's `commitStart` ignored it. | Call the existing `saveDeparture` before promotion, within the existing write transaction. Transition and start saves share the position and mark-played rules. | `SessionWriterTest` checks both context modes, an SQL trigger proving departure-before-arrival, zero and played-after-start guards, save/played flags, missing arrival and late-write rollback. |
| `PositionStore.reset` KDoc referenced a nonexistent `reset=true` parameter. | Describe the actual reset operation: explicit zero clears started status and preserves played status. | Existing `PositionWriterTest` reset coverage. |
| `QueueRepository` KDoc said queue edits never change generation, including its context-clearing operation. | Limit that statement to Up next edits and state the context-clearing exception. | Existing queue/session generation tests. |
| Graph tests resolved platform stubs but did not request W2's five durable ports. | Add deferred graph accessors and test production implementation names and singleton identity after database opening, while retaining the no-eager-open test. | Both `AndroidAppGraphTest` and `DesktopAppGraphTest`. |

## Additional audit dispositions

One read-only SWE-2 Max helper audit completed. Its additional confirmed
mismatch was `FakePositionStore` inventing a position row on reset,
mark-played or replay-clear when the real UPDATE-only store emits no position.
The fake now retains played-state guards separately when no position exists,
updates only existing position rows for those operations, and has a regression
covering absent rows and the subsequent save guard.

Three other suggestions did not justify behavior changes:

- Queue-add rejection precedence: 06 lists rejection conditions without
  specifying their order. Both conditions reject the entire batch; preserve
  the existing behavior. The separately specified start-gate order is already
  implemented by W3.
- Fake virtual tails containing current: these are explicitly recording seams
  with caller-published snapshots, rather than database emulators. Fixtures
  own the supplied snapshots. Adding a second queue-normalization algorithm
  would obscure the planner's tests of defensive input handling.
- Per-candidate media reads and per-podcast settings reads: this is a possible
  latency optimization, without measured regression evidence. Batch APIs and
  prefetch changes are deferred to keep this integration focused.

## Binding and rule ownership audit

Each graph includes one production binding for `QueueRepository`,
`PlayContextResolver`, `PlaySessionStore`, `PositionStore` and
`PlaybackMediaLookup`, contributed by W2. Each has a `core:testing` fake.
Metro graph compilation checks missing and duplicate bindings; the runtime
checks also reject an accidentally substituted implementation.

Existing graph tests cover `PlaybackController`, `PlaybackStateSource`,
`PlaybackMaintenance`, `LocalMediaIndex`, `YouTubeCapabilitiesSource` and
`YouTubeStreamResolver`. The first three share the platform's single truthful
unavailable implementation until Android W8 or desktop MD1. Local media is
empty until M6; YouTube resolution explicitly returns Unsupported.
`StartGate` is an optional adapter extension set, with a recording fake;
there is no production metered gate until W8. Player-owned rule objects are
constructor-injected, rather than contributed as competing port bindings.

The owner-adopted playable-first policy is already recorded in
[05 Start rules](design/05-groups-opml-backup.md#start-rules) and
[06 Starting playback](design/06-playback.md#starting-playback).
`PlayStarter` owns selection/gates, the resolver supplies SQL-eligible context,
and the planner filters projection without deleting greyed queue entries.
No new policy implementation is added here.

The mark-played chain is shared by playback, episode/bulk actions and import;
start/transition departures and ordinary saves share the guarded position
helper. Queue insertion planning reuses the existing order-key planner;
restore-specific ordering remains separate because it has different rules.
W4 retains the podcast → member context group → global resolution chain,
without borrowing the M2 multi-group merge. No competing production helper
or additional production W2/W3/W4 behavior contradiction was confirmed.

## Verification

Regression-first execution reproduced the missing sort date and departure:
the date was null and the arrival trigger raised `departure missing`.
The final full gate run passed in 9m 45s (1,302 actionable Gradle tasks; 200 executed, 1,102 up-to-date).
The command used JDK 21, the supplied Android SDK and graphics environment,
`--no-daemon`, `-Pkotlin.incremental=false` and
`-Pkotlin.compiler.execution.strategy=in-process`:

```sh
./gradlew --no-daemon -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  allTests test mutationTest :app:compileDebugAndroidTestKotlin spotlessCheck \
  :app:lintRelease assertModuleGraph checkBannedApis \
  :app:compileDebugKotlin :desktopApp:compileKotlin --continue
```

`spotlessApply` ran successfully on its own before this command. Release lint
reported no errors or warnings. All four module-graph assertions passed.
Both application targets and Android instrumentation-test sources compiled.
No device tests or CI were run.

The JUnit XML reports contain **6,358 tests: 6,355 passed, 3 skipped,
0 failures and 0 errors**. The skips are the Windows-only DPAPI round trip
and two AWT file-menu checks on this headless host. The final task outputs
are counted below, with execution/cache status. Compilation and aggregate
tasks do not add test cases.

| Test task | Tests | Skipped | Status |
|---|---:|---:|---|
| `:app:testDebugUnitTest` | 140 | 0 | executed |
| `:core:artwork:desktopTest` | 25 | 0 | executed |
| `:core:artwork:testAndroidHostTest` | 43 | 0 | executed |
| `:core:common:desktopTest` | 100 | 0 | UP-TO-DATE |
| `:core:data:desktopTest` | 1,182 | 1 | executed |
| `:core:data:mutationTest` | 1 | 0 | executed |
| `:core:data:testAndroidHostTest` | 139 | 0 | executed |
| `:core:database:desktopTest` | 150 | 0 | executed |
| `:core:database:testAndroidHostTest` | 9 | 0 | executed |
| `:core:datastore:desktopTest` | 18 | 0 | executed |
| `:core:designsystem:desktopTest` | 14 | 0 | executed |
| `:core:domain:desktopTest` | 3 | 0 | UP-TO-DATE |
| `:core:model:desktopTest` | 2,544 | 0 | UP-TO-DATE |
| `:core:navigation:desktopTest` | 25 | 0 | UP-TO-DATE |
| `:core:network:desktopTest` | 33 | 0 | executed |
| `:core:network:okhttp:test` | 45 | 0 | executed |
| `:core:network:testAndroidHostTest` | 6 | 0 | executed |
| `:core:testing:desktopTest` | 65 | 0 | executed |
| `:core:ui:desktopTest` | 141 | 0 | executed |
| `:core:ui:testAndroidHostTest` | 134 | 0 | executed |
| `:desktop:system:test` | 1 | 0 | executed |
| `:desktopApp:test` | 113 | 2 | executed |
| `:download:api:desktopTest` | 1 | 0 | UP-TO-DATE |
| `:download:impl:desktopTest` | 1 | 0 | executed |
| `:feature:discover:desktopTest` | 40 | 0 | executed |
| `:feature:episode:desktopTest` | 37 | 0 | executed |
| `:feature:feeds:desktopTest` | 106 | 0 | executed |
| `:feature:feeds:testAndroidHostTest` | 59 | 0 | executed |
| `:feature:groups:desktopTest` | 82 | 0 | executed |
| `:feature:importexport:desktopTest` | 190 | 0 | executed |
| `:feature:library:desktopTest` | 57 | 0 | executed |
| `:feature:player:desktopTest` | 1 | 0 | executed |
| `:feature:podcast:desktopTest` | 105 | 0 | executed |
| `:feature:settings:desktopTest` | 25 | 0 | executed |
| `:feature:sync:desktopTest` | 1 | 0 | executed |
| `:feeds:desktopTest` | 215 | 0 | UP-TO-DATE |
| `:feeds:jvm:mutationTest` | 38 | 0 | executed |
| `:feeds:jvm:test` | 252 | 0 | executed |
| `:playback:api:desktopTest` | 1 | 0 | UP-TO-DATE |
| `:playback:core:desktopTest` | 100 | 0 | executed |
| `:playback:desktop:test` | 1 | 0 | executed |
| `:playback:engine:test` | 1 | 0 | executed |
| `:playback:impl:testDebugUnitTest` | 1 | 0 | executed |
| `:playback:native:test` | 1 | 0 | executed |
| `:sync:api:desktopTest` | 1 | 0 | UP-TO-DATE |
| `:sync:impl:desktopTest` | 1 | 0 | executed |
| `:sync:protocol:desktopTest` | 27 | 0 | UP-TO-DATE |
| `:sync:server:test` | 58 | 0 | UP-TO-DATE |
| `:youtube:api:desktopTest` | 22 | 0 | UP-TO-DATE |
| `:youtube:api:mutationTest` | 1 | 0 | UP-TO-DATE |
| `:youtube:engine:test` | 1 | 0 | executed |
| `:youtube:impl:desktopTest` | 1 | 0 | executed |

`:sync:protocol:mutationTest` completed with 0 matching tests; the convention
reserves it for future robustness cases. `:feature:downloads:desktopTest`,
`:feature:queue:desktopTest` and `:youtube:ytdlp:testDebugUnitTest` were
NO-SOURCE. `:app:test`, `:playback:impl:test` and `:youtube:ytdlp:test` are
aggregate tasks; their cases are counted under concrete test tasks above.

Within these totals, `SessionWriterTest` passed 9 cases,
`PlaybackMediaLookupTest` passed 2, and both app graph test classes passed
13 each. The shared playback suite passed 100 cases. The date and departure
regressions passed after the fix, including rollback and the SQL ordering
assertion. The fake contract suite passed 9 cases, including the new absent-row
regression, which first failed against the old fake. Schema files remained
unchanged.

Documentation validators: `python3 tools/doccheck/checkdocs.py .` reported
0 problems; `node tools/doccheck/mmdcheck.mjs .` reported 0 invalid of
78 diagrams.

## Files changed

- [app/src/main/kotlin/ch/lkmc/neutrodyne/di/AndroidAppGraph.kt](../app/src/main/kotlin/ch/lkmc/neutrodyne/di/AndroidAppGraph.kt)
- [app/src/test/kotlin/ch/lkmc/neutrodyne/di/AndroidAppGraphTest.kt](../app/src/test/kotlin/ch/lkmc/neutrodyne/di/AndroidAppGraphTest.kt)
- [core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/playback/PlaybackMediaLookupImpl.kt](../core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/playback/PlaybackMediaLookupImpl.kt)
- [core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/playback/SessionWriter.kt](../core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/playback/SessionWriter.kt)
- [core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/PlaybackMediaLookupTest.kt](../core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/PlaybackMediaLookupTest.kt)
- [core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/SessionWriterTest.kt](../core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/SessionWriterTest.kt)
- [core/database/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/database/EpisodeDao.kt](../core/database/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/database/EpisodeDao.kt)
- [core/database/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/database/PlaybackRows.kt](../core/database/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/database/PlaybackRows.kt)
- [core/domain/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/domain/PlaybackStores.kt](../core/domain/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/domain/PlaybackStores.kt)
- [core/domain/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/domain/QueueRepository.kt](../core/domain/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/domain/QueueRepository.kt)
- [core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakePositionStore.kt](../core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakePositionStore.kt)
- [core/testing/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/testing/FakePlaybackContractsTest.kt](../core/testing/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/testing/FakePlaybackContractsTest.kt)
- [desktopApp/src/main/kotlin/ch/lkmc/neutrodyne/desktop/di/DesktopAppGraph.kt](../desktopApp/src/main/kotlin/ch/lkmc/neutrodyne/desktop/di/DesktopAppGraph.kt)
- [desktopApp/src/test/kotlin/ch/lkmc/neutrodyne/desktop/di/DesktopAppGraphTest.kt](../desktopApp/src/test/kotlin/ch/lkmc/neutrodyne/desktop/di/DesktopAppGraphTest.kt)
- [docs/M4-P1-INTEGRATION.md](../docs/M4-P1-INTEGRATION.md)
- [docs/design/06-playback.md](../docs/design/06-playback.md)
