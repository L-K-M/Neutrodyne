# M8 external YouTube acceptance

Local integration, 2026-10-19. M8 remains open. No publication, CI, external
review, device execution or main merge is claimed. The parent owns publication
and PR review. Current shells bind external-only implementations regardless of
packaged self-test scaffolding, with no engine start or stream resolution.

## Acceptance ledger

| AC | Implemented behavior and deterministic tests | Outstanding evidence |
|---|---|---|
| 1 | `YouTubeUrlClassifierTest`, `YouTubeIdsTest` and classifier mutation tests cover canonical URLs, variant prefixes, handles, Unicode and invalid identities | Latest-candidate CI/review and real Unicode YouTube shares |
| 2 | `youtube:impl` resolver replay covers reconstructed handle/oEmbed HTML, headers, redirect/consent failure, byte caps and timeout/cancellation; graph journeys use the same application clients | Fresh successful handle/oEmbed recordings; actual channel-head median and metered-avatar budget. Prior consent capture is not a successful channel capture |
| 3 | `YouTubeSourceAdapterTest`, `YouTubeIngestRegressionTest`, both platform date-provenance suites and controlled journeys cover author title, video GUID, published-only, Shorts and INITIAL/no-new/no-download | Android device execution of the graph journey |
| 4 | `YouTubeHealthTest`, `YouTubeOutageIntegrationTest` and E8 UI banner/retry assertions cover controls, cached checks, bounded probes, backoff and badge suppression | Actual application-client control/automatic-refresh captures on both platforms; successful scheduled canary. The independent parser recovery is on main (#213; #211 closed); this run makes no new scheduled-nightly claim |
| 5 | Android `ExternalYouTubeModeTest` uses the application graph and test-only HTTP/browser seams, semantics, failed/successful open, Undo, no queue/download, all-external and mixed-group selection, settings reason and one banner | API26/33/34/36/37, optimized release, ARMv7/arm64/x86_64; physical browser/YouTube app/no-handler, warm/cold shares and TalkBack |
| 6 | Import parser and durable import tests cover LibreTube memberships, Takeout RFC4180/ZIP, NewPipe services and limits. Graph Add/import journeys replay a synthetic LibreTube document through the application client and repository | Owner's current localized Takeout ZIP and grouped LibreTube export; Neutrodyne NewPipe export imported into both NewPipe and LibreTube |
| 7 | `YouTubeSourceAdapterTest` and ingest regressions preserve scrolled-out state across ten windows, raw variant absence floors, sticky Shorts and unchanged metadata | Latest-candidate CI/review |
| 8 | Recorded shared-handle Add waits for confirmation, writes canonical identity and chosen group, performs INITIAL refresh and rejects queue/download. Suggestion searches use enabled directories and channel title, never the handle | Android device execution; real share targets and title-query network captures |
| 9 | Desktop `ExternalYouTubeModeTest` uses the real shell graph, captured opener, routes, settings reason, durable import lane and mixed-group selection | Packaged Windows x64, macOS arm64, Linux x64/arm64, actual browser policies and screen-reader traversal. Desktop audio remains MD1 |

## Verification receipts

Implementation commit: `7f6e65e3`, over merges `3c1c1731`, `872c1666` and
`2d150787`. The last merge includes main `ef5fec26`; requested M3 `8f34b2cb`
and all later local M8 behavior are retained. The parser correction is incoming
reviewed code, with its five-second limit unchanged. The owner reports hosted
1,000-iteration mutation recovery (#213; #211 closed); that hosted run was not
performed here and is not a full-nightly claim.

All commands used this environment and Gradle invocation, one build at a time:

```sh
export JAVA_HOME=/home/paseo/.local/jdks/jdk21
export ANDROID_HOME=/home/paseo/android-sdk
export LANG=C.UTF-8 LC_ALL=C.UTF-8
source /home/paseo/.local/gfx/env.sh
g() {
  ./gradlew --no-daemon --max-workers=2 -Pkotlin.incremental=false \
    -Pkotlin.compiler.execution.strategy=in-process "$@"
}
g allTests test mutationTest :app:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin :desktopApp:compileKotlin assertModuleGraph \
  checkBannedApis checkSpdxHeaders verifyDependencyPolicy \
  :app:verifyManifestPermissions :app:lintRelease \
  -Proborazzi.test.verify=true --continue
```

That broad repair gate passed before the final imported-channel HTML fixture
correction and current-main merge: 1,435 tasks, 134 executed / 1,301 up-to-date.
JUnit counts across 59 report-bearing tasks were:

| Gate | Passed | Existing skips | Failed |
|---|---:|---:|---:|
| allTests | 6144 | 1 | 0 |
| test | 1788 | 2 | 0 |
| mutationTest | 43 | 0 | 0 |
| Total | 7975 | 3 | 0 |

The skips are `DesktopSecretStoreTest.dpapi protection round-trips the secrets
file[desktop]` (Windows-only) and both English/German
`DesktopImportMenuTest.*FileMenuRegistersTheAcceleratorAndOpensTheSharedPicker`
cases (headless/macOS guard; this host is headless Linux). The protocol mutation
task has no cases. Compilation is not Android execution.

The initial broad gate failed two tests (7,973 passed / 3 skipped / 2 failed).
The new outage journey had split failures across refresh runs; triggering the
actual Feeds Refresh action tests one forced scope request. The existing
Licences test clicked a settings row below the viewport; it now scrolls to the
row. Diagnostics reproduced both causes; product code and five-second waits
were unchanged. Failure XML and complete successful XML were preserved before
any affected rerun. Initial test compilation and formatting failures were fixed
before the successful broad receipt; they are not test passes.

Only affected checks were repeated after the final fixture edit and main merge:

```sh
g spotlessCheck
g :desktopApp:test :feeds:jvm:test :app:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin :desktopApp:compileKotlin \
  -Proborazzi.test.verify=true --continue
g -PtestBuildType=release :app:compileReleaseAndroidTestKotlin \
  :app:lintRelease --continue
python3 scripts/ci/tests/assert-discovery-tests.test.py
python3 scripts/ci/tests/youtube-feed-canary.test.py
python3 scripts/ci/assert-discovery-tests.py . --external-youtube
python3 scripts/ci/assert-discovery-tests.py .
```

Formatting passed (8 tasks, 2 executed / 6 up-to-date). Affected Gradle checks
passed (579 tasks, 56 executed / 523 up-to-date): desktop 126 passed / 2 existing
skips, parser 279 passed / 0 skipped, both app compilations and debug Android-test
compilation passed. Both exact E8 methods and the existing M7 host selectors
passed. Selector fixtures passed 7 tests; canary fixtures passed 4 tests.
Earlier all eight Python fixture suites passed 53 tests, and SDK shell fixtures
passed six cases. Release recheck passed in 1m27s (839 tasks, 51 executed / 788 up-to-date).
Release Android-test compilation and release lint passed; no device ran.

No Android instrumented case, real browser, other OS/ABI, physical device or
live canary was executed locally. Static/API/module/manifest and release lint
gates passed in the broad receipt; their task reuse is not fresh execution.
Final documentation checks: `python3 tools/doccheck/checkdocs.py .` reported
0 problems across 14 files; `node tools/doccheck/mmdcheck.mjs .` reported
0 invalid diagrams across 78 diagrams. The same link/fence checker also
checked Privacy, HANDOFF and this ledger as supplemental inputs.

## Review boundaries

Measured patches include context, tests and documentation; overlapping hunks in
selected historical commits are counted again. These are proposed serial review
groups, requiring reconstruction against the parent's published prerequisites
and verification at each boundary, rather than ready cherry-pick PRs.

| Proposed group | Commit inputs | Patch bytes | Unique files |
|---|---|---:|---:|
| Contracts and Layer A resolver | 04b32ea8, 55225bff | 170771 | 50 |
| Feed health and Atom ingestion | 4fb96683, b004dc39 | 197808 | 37 |
| External actions and safety before activation | 9ac0d720 | 182053 | 83 |
| Channel imports/exports | 4b4b1248 | 192010 | 56 |
| Add, channel screens and artwork | 44f3637d, 7168e359 | 209769 | 66 |
| Acceptance/privacy/workflow implementation | 2d150787..7f6e65e3 | 153666 | 23 |

The final receipt update adds only owning documentation. No new binary fixture
is introduced. Combining external actions with acceptance would exceed the
roughly 250k review ceiling; the original three-PR estimate is superseded by
these six smaller groups. Safety must precede Add/import activation. The whole
prerequisite-inclusive snapshot is not a proposed single review.

## Provenance and network evidence

`youtube/impl/src/test/resources/recorded/PROVENANCE.md` identifies reconstructed
success HTML/oEmbed shapes and the unsuccessful 2026-10-19 consent capture.
`feeds/src/test/resources/youtube/m8-atom.xml` is synthetic. The acceptance
LibreTube document and empty directory replies are synthetic. None is a live
capture. The same fixtures enter the desktop test resources and Android test
APK assets, never ordinary app source sets.

The replay interceptor captures requests after the real client's User-Agent
interceptor, asserting fixed `SOCS=CAE=`, locale and page/feed requests. It
rejects unplanned requests and opens no external socket. Image loading is faked
for deterministic UI, so it provides no image-host or visual-quality capture.
Live captures of automatic refresh, controls, title-based RSS suggestions and
all avatar/banner hosts remain missing on both platforms. Known destinations
are disclosed in [Privacy](../../PRIVACY.md) and the existing
[network inventory](../design/09-quality-and-release.md#network-inventory).
The stdlib feed canary is explicitly not an application-client capture.

## Adopted behavior

Both shells remain external. Primary variant 404/410 tries canonical first;
failed fallback keeps its real diagnostic. Gap pull-in spans the triggering
success and two subsequent successes while preserving failure/deferred floors.
Generic Atom fallback is unchanged; YouTube uses published provenance, sticky
Shorts and a normalized channel-author digest. Global outage suppresses badges
without deleting subscriptions. Past-live toggles affect future polls; old live
rows remain visible because external mode has no per-row live provenance.
Imports persist ordinal input/variant hints, use canonical identity and lazily
upgrade unfinished sessions. Layer A fixtures stay outside the engine tree.
Only one feed-only report canary exists in this milestone.
