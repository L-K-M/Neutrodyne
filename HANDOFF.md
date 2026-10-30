# Handoff — state of the Neutrodyne work

This file lets another person or agent take over the work at any point. **Last updated: 2026-10-30.** M0a is merged; M0b is held for Android publication; M1a, M1b and M2 are merged, with owner-run device and desktop checks open (see §0). M3's source and scripted API31/36 backup gate are merged through #218 and #223; other acceptance evidence remains listed below. M4's first five slices are merged through #222, #224, #225, #228 and #230; P6's Android service/lifecycle source is merged through [#231](https://github.com/L-K-M/Neutrodyne/pull/231) and [#232](https://github.com/L-K-M/Neutrodyne/pull/232). The test-clock repair [#240](https://github.com/L-K-M/Neutrodyne/pull/240) and release-linkage repair [#241](https://github.com/L-K-M/Neutrodyne/pull/241) are merged. Controller authentication [#244](https://github.com/L-K-M/Neutrodyne/pull/244), playback safety [#245](https://github.com/L-K-M/Neutrodyne/pull/245) and hosted recording [#246](https://github.com/L-K-M/Neutrodyne/pull/246) are merged. P7 queue UI [#243](https://github.com/L-K-M/Neutrodyne/pull/243) and root scaffolding [#250](https://github.com/L-K-M/Neutrodyne/pull/250) are merged; buffering/refused-advance safety [#251](https://github.com/L-K-M/Neutrodyne/pull/251) and dismissal fencing [#252](https://github.com/L-K-M/Neutrodyne/pull/252) are merged. Last verified main CI: `e20a440f`. P8 player UI is a local candidate; M4 acceptance remains open. Follow [`docs/IMPLEMENTATION-PROMPT.md`](docs/IMPLEMENTATION-PROMPT.md) through the v1.0 release.

**Helper policy (2026-10-20):** Prefer Devin `swe-2-max`, then prepaid Xiaomi
MiMo, and cheap Junie tiers for small tasks. Use Z.ai helpers only after
`helper-models zai-quota` reports spare quota. This supersedes older helper notes below.

## 0. Implementation status

| Milestone | State | Branch / PR | Notes |
|---|---|---|---|
| M0a.1 | scheduled gate green through `52b2fd6`; current-head and tagged checks pending | [PR #10](https://github.com/L-K-M/Neutrodyne/pull/10), merge `e0c3722` | Scaffold, Android shell, policy gates and release pipeline. Seven Codex Sol rounds; GLM waived after repeated timeouts. Scheduled-main [38037939442](https://github.com/L-K-M/Neutrodyne/actions/runs/38037939442) on `bb0d13e` passes all four jobs, including API26/33/34/36 debug, API37 16 KB and release smoke; report-only repro also passes. The newer scheduled nightly [38125978109](https://github.com/L-K-M/Neutrodyne/actions/runs/38125978109) passes on `52b2fd6`. Issue #58 closed for those heads. Next: a scheduled green on the current main head, checklist [#20](https://github.com/L-K-M/Neutrodyne/issues/20), owner-device acceptance and tagged-main checks before `v0.1.0`. |
| M0a.2 | merged | [PR #13](https://github.com/L-K-M/Neutrodyne/pull/13), merge `49bc849` | Library spikes and Android UI/resource tests passed on API 26 and 36. Below API 33 the language-switch test skips while only `en-US` ships as Android resources; recorded in `docs/design/01-foundation.md` (S11) and `docs/design/09-quality-and-release.md` (Shipped locales and per-app language). No separate tester build. |
| M0b | native repairs green; independently closed | [#19](https://github.com/L-K-M/Neutrodyne/pull/19), [#25](https://github.com/L-K-M/Neutrodyne/pull/25); draft [#34](https://github.com/L-K-M/Neutrodyne/pull/34) `dbf475a` | [Regular CI 37973990857](https://github.com/L-K-M/Neutrodyne/actions/runs/37973990857) and [full matrix 37973995959](https://github.com/L-K-M/Neutrodyne/actions/runs/37973995959) pass, eight jobs including four desktop targets. Codex reports no important finding: 12 fixtures/six real ELF cases, whole checker accepts five valid variants and rejects four loader mutants/eight malformed cases. Program headers/loaded bytes remain bound; section metadata gives no alternate authority. MSI requires the actual INSTALLDIR; 37 image/15 smoke fixtures pass. Hold #34 until Android publishes; physical/minimum-OS/upgrade/uninstall/DMG/S13 gates remain. |
| M1a database | merged | [#37](https://github.com/L-K-M/Neutrodyne/pull/37) `6ed5d75`, [#38](https://github.com/L-K-M/Neutrodyne/pull/38) `616fefa`, [#40](https://github.com/L-K-M/Neutrodyne/pull/40) `52b2fd6`, [#74](https://github.com/L-K-M/Neutrodyne/pull/74) `d759351` (code `85b5342`), [#41](https://github.com/L-K-M/Neutrodyne/pull/41) `97a57cf` (code `3c0df9f`) | Schema, Room runtime, recovery and startup are on main. #74 (from the 2026-10-11 periodic review) stops an exception during failure classification from stranding the open gate, and makes a denied prune fail the open instead of reporting success; regressions fail before/pass after, mutation-checked. #41 opens the database at startup on both shells and runs the KMP device tests on API 26/36 in CI; its first GLM round was minor-only (exec/APK-guard/intent/snackbar claims refuted with evidence), and exact-head CI including instrumented passed. Still open: actual V1→V2 upgrade on main (proof lives on #70), physical-device and crash/retry proof. |
| M1a feeds | merged | [#22](https://github.com/L-K-M/Neutrodyne/pull/22) `5602303` (code `7a4e825`), [#76](https://github.com/L-K-M/Neutrodyne/pull/76) `195a04a`, [#77](https://github.com/L-K-M/Neutrodyne/pull/77) `d8a7d7c` (code `f6975f0`), [#78](https://github.com/L-K-M/Neutrodyne/pull/78) `e8ea0cd` (code `3a7b54f`), [#89](https://github.com/L-K-M/Neutrodyne/pull/89) `c77d211` (code `1d7606a`) | #22 merged after 15 GLM rounds (stabilization exit; deferred minors in its conversation); #76 runs the numeric-host rule on the IDNA result. #32 was split by review size into the parser and sanitizer (#77; its second GLM round had no confirmed important defect) and the corpus and goldens (#78; one round, all applied; the corpus now fails on a missing or empty directory). #89 fixes two minors from the 2026-10-12 periodic review: prefixed Atom roots were sniffed as `OTHER`, and a long Basic challenge overflowed the regex stack (now an iterative, fuzzed parser). The corpus device leg merged as #98, replacing #60. |
| Room runtime repair | merged | [#56](https://github.com/L-K-M/Neutrodyne/pull/56), merge `98dd9a7`, code `9497e5a` | Two GLM rounds plus independent source/rebuild/API triage close important findings. R1 holder-entry/probe bounds are fixed; R2 cancellation claim is false because pinned recycle is ordinary synchronous fun. Effective AAR timestamp/order settings are reproducible; six artifact hashes and 64 checksum sidecars match, Android 156/156 and JVM 126/126 class/API parity holds. All six exact-head/main jobs pass, [main CI 37980960606](https://github.com/L-K-M/Neutrodyne/actions/runs/37980960606). Version drift fails loudly. Deterministic handoff, Proxy and physical proof remain separate gaps. |
| Room native ownership | merged; startup-compatible fixture checks merged | [#67](https://github.com/L-K-M/Neutrodyne/pull/67) merge `0526a46`, code `efca8a5`; [#71](https://github.com/L-K-M/Neutrodyne/pull/71) merge `0684257`, code `0f9b53b` | Patch0002 closes unpublished native connections across cancellation boundaries; independent six-artifact/API/Binder/resource proof and 59 desktop/9 host pass. The wrapper-routing defect is fixed. Actual startup composition exposed a second test defect: a global app-directory sentinel mistook legitimate initializer writes for misrouting. #71 records every test-owned open, retains rejection before native opening and adds a negative control. [Composed CI 38038627021](https://github.com/L-K-M/Neutrodyne/actions/runs/38038627021) executes all five methods on API26/API36 without method failures/skips (0.377/0.248 s). Each PR closes after one GLM round with no verified important remainder. FD visibility stays conditional; physical proof remains separate. |
| Room migration index inspection | merged | [#69](https://github.com/L-K-M/Neutrodyne/pull/69), merge `bb0d13e`, code `440b90f` | Actual V1→V2 validation exposed upstream counting of WITHOUT ROWID auxiliary PK fields as declared index columns. Patch0003 filters `index_xinfo.key = 0`; raw native controls fail before/pass after. Independent clean six-artifact reproduction, 64 sidecars, eight variant records and 126 JVM/156 Android descriptors pass; 63 desktop/9 host and all-six CI pass. One GLM round closes: remote-Maven cache allegation does not match the exclusive file repository or observed offline warm-worktree 2→3 behavior. Corrected source comments were republished; production binaries remain byte-identical to independently reviewed `e66a735`. |
| M1a data | merged | [#79](https://github.com/L-K-M/Neutrodyne/pull/79) `d89d290`, [#80](https://github.com/L-K-M/Neutrodyne/pull/80) `4d1876e`, [#81](https://github.com/L-K-M/Neutrodyne/pull/81) `04d15c7`, [#82](https://github.com/L-K-M/Neutrodyne/pull/82) `327a4e4`, [#83](https://github.com/L-K-M/Neutrodyne/pull/83) `c74b40c`, [#84](https://github.com/L-K-M/Neutrodyne/pull/84) `223aa5e`, [#87](https://github.com/L-K-M/Neutrodyne/pull/87) `c1d5f16`, [#99](https://github.com/L-K-M/Neutrodyne/pull/99) `03a65e3`, [#88](https://github.com/L-K-M/Neutrodyne/pull/88) `f56a2b9` | The closed DATA PRs #50–#52 and their follow-ups #61–#63, #65 and #70 were replayed onto main's merged parser and split by review size. Every GLM finding is first verified independently (helper CLIs, executed probes); verified majors are fixed test-first. #81: four rounds (per-feed fault isolation; a paging host-key regression). #82: three (Basic credentials leaked over the http fallback, chained wrappers, scheduler failures failing a committed subscribe; #86 folded in). #83: a four-reviewer pre-review (no `DesktopJobRunnerTest` although M1 AC10 names it; stranded paging under "Manual only"), then two GLM rounds with no verified important finding; one round needed a rerun after HTTP 429s. #84: the 2026-10-12 review of `327a4e4`'s three important findings plus #61 and #85. #87: the paged `FeedRepository`. #99: the 2026-10-13 review of `223aa5e`'s important finding, refresh start-up never resuming after a database-recovery retry. #88 (D98, V1→V2): r1's one verified important finding (a contested, unchanged re-offer dropping out of the feed) is fixed. r2 covered three of four chunks with no important finding; the last chunk and a full rerun failed on HTTP 429, so Devin reviewed the r1 fix delta (no defect). The V1→V2 device migration passed. |
| M1a UI | merged | [#91](https://github.com/L-K-M/Neutrodyne/pull/91) U1 `3f54537` (replaces #53, closed) → [#93](https://github.com/L-K-M/Neutrodyne/pull/93) U2 `f101552` (replaces #54, closed) → [#94](https://github.com/L-K-M/Neutrodyne/pull/94) U3 `f8540f2` (replaces #57, closed) → [#97](https://github.com/L-K-M/Neutrodyne/pull/97) Feeds `44e96e2` → [#96](https://github.com/L-K-M/Neutrodyne/pull/96) Podcast `0ae5485` → [#95](https://github.com/L-K-M/Neutrodyne/pull/95) Episode and Add-podcast `9436e04` (all three now target main) | #53/#54/#57's deltas and the remaining screens of the verified `wip/m1a-ui` package (`753bee3`) are replayed onto the reviewed stack. The package was never GLM-reviewed, so every slice had an independent pre-review with verified fixes: a Library crash (keys pushed before their screens exist; placeholder entries with graph tests now guard every pushed key), repeated artwork pins wiping stored metadata, locale-frozen collation and dates, inert snackbar actions, and the feed password kept in the saved-state bundle. Merge order #97 → #96 → #95; later slices replace earlier placeholders. #91 merged after two GLM rounds: r1's one confirmed important finding (desktop `file:` artwork URIs were not percent-encoded) and eleven minors were fixed; its Major and Critical claims on cache trimming, cache keys and a missing import were refuted with Coil bytecode and probes, and r2 only re-raised one of them. #93 merged after two rounds: three confirmed important findings fixed test-first (the hover overflow covered the play button, download menu labels contradicted the state, the declared image aspect overrode the loaded one); its two Criticals (a literal `%` in Compose resources) were refuted, and a queued row offers only Cancel until M6 adds `promote`. #94 merged after three rounds: no confirmed important GLM finding (accessibility of the sort menu, a named mark-all dialog and test fixes applied), but its new metrox edge had put lifecycle 2.10.0 on the desktop compile classpath against 2.11.0 at run time (`NoSuchMethodError`); the desktop application convention now uses `consistentResolution { useRuntimeClasspathVersions() }`, with a linkage test. #97 merged after two rounds with no confirmed important finding. Its round-1 change to show append errors only in the footer row was wrong: with placeholders the footer sits after every unloaded row, so a mid-list append failure became invisible; #96 restores the pinned banner for append on Feeds and removes the duplicate footer on both screens, and 08's two slice notes now agree. #96 merged after two rounds: its confirmed important findings were latent credential defects behind `FEED_ACCOUNT_CONTROLS_ENABLED` (unmasked passwords, the password in saved state), fixed test-first so M1b can enable the controls. #95 merged after two rounds: its one confirmed important finding (an action snackbar defaulting to `Indefinite`, which holds the single root snackbar queue) was fixed, and the same default elsewhere on main is fixed by #103. |
| M1a corpus device leg | merged | [#98](https://github.com/L-K-M/Neutrodyne/pull/98) `8b478e5` (replaces #60, closed) | All 70 goldens pass through the AOSP parser on the API 26 and 36 managed devices (`AndroidParserGoldenInstrumentedTest`, per-device XML on CI); leg (b) runs on the host since #83. |
| M1a acceptance | merged | [#105](https://github.com/L-K-M/Neutrodyne/pull/105) `173fd04`, [#106](https://github.com/L-K-M/Neutrodyne/pull/106) `26de636`, [#111](https://github.com/L-K-M/Neutrodyne/pull/111) `854d546` | The audit in `~/nd-wt/reviews/m1a-audit.md` listed automated gaps. #105 (two GLM rounds, no confirmed important finding; its "won't compile" claim refuted on the PR) proves the rewrite and catalogue journeys, the subscribe `syncId`, and the supplementary-reference corpus fixture (71 fixtures). It also narrows M1 AC11 to subscribe in a dated note, with import and restore moving to M3 acceptance 12. #111 proves AC2's M1a clauses (`FeedRefreshContractTest`), AC4 (`RefreshWorkManagerTest`, WorkManager `TestDriver`, mid-ingest cancellation rollback) and AC10's runner half. Its CI failure was a real test race: after a retry, WorkManager's reschedule pass cancels the scheduler's tokens asynchronously, so an early re-delivery was stopped. The test waits for `scheduleRequestedAt`; 10 of 10 runs pass under CPU load, against 5 of 10 failing before the fix. #106 runs the parser mutation gate in the PR unit job and adds the nightly `mutation-full`. Owner-run checks remain: AC5 on the reference device and AC10's manual desktop half (procedure in the audit). |
| M1b | merged; device and Windows checks open | [#112](https://github.com/L-K-M/Neutrodyne/pull/112) `6efe483`, [#115](https://github.com/L-K-M/Neutrodyne/pull/115) `7707ed2`, [#117](https://github.com/L-K-M/Neutrodyne/pull/117) `77a1ee1`, [#120](https://github.com/L-K-M/Neutrodyne/pull/120) `03ff793`, [#113](https://github.com/L-K-M/Neutrodyne/pull/113) `51d4c18`, [#114](https://github.com/L-K-M/Neutrodyne/pull/114) `002f1a9`, [#116](https://github.com/L-K-M/Neutrodyne/pull/116) `03e257a`, [#118](https://github.com/L-K-M/Neutrodyne/pull/118) `b684817`, [#122](https://github.com/L-K-M/Neutrodyne/pull/122) `b909b52`, [#121](https://github.com/L-K-M/Neutrodyne/pull/121) `cce2a41` | Ten PRs from Codex's plan (`~/nd-wt/reviews/m1b-plan.md`), no schema change: the `SecretStore` and `CredentialCommitCoordinator` contracts, the shared store core with Android Keystore AES-GCM and the desktop secrets file (DPAPI on Windows, `0600` on macOS and Linux), credential persistence and wiring, lossless merges, permanent redirects and renormalisation, the `itunes:new-feed-url` probe, `editFeedUrl`, the enabled account controls, and RFC 5005 paging acceptance (AC8). Settled in dated notes: 02's provenance union runs as two statements on SQLite 3.18; a 301 ending in 304 gets one confirmation fetch, and only a body replaces the 304; AC8's 5,000-item stop applies to automatic sessions. Every PR had one or two GLM rounds; confirmed defects were fixed test-first (a desktop secret lost to a reconcile race, an unreadable secrets file overwritten, cleanup masking a commit failure, a failed redirect confirmation failing a healthy feed, failed probes cached as rejections, UI-scope exceptions). Open: AC9's real-Keystore device run (`:core:data` has no device-test source set) and AC12's DPAPI round-trip on a Windows host. |
| M2 | merged; device checks open | all 28 of [#125](https://github.com/L-K-M/Neutrodyne/pull/125)–[#152](https://github.com/L-K-M/Neutrodyne/pull/152) merged (not in number order); the last was [#151](https://github.com/L-K-M/Neutrodyne/pull/151) `a712bef` | From the synthesized plan (`~/nd-wt/reviews/m2-plan.md`): groups, membership ordering, persisted tabs and visits, effective settings, mosaics, counts, the batched new-episode notifier and Android poster and channels, effective refresh intervals, delete with undo, the Feeds pager, group editor and manage screen, chips and add-to-groups, Library multi-select, counted mark-played, gestures and live rows, query-plan and invalidation-hygiene tests, and the desktop group journey. No schema change. Every PR had one to three GLM rounds; findings, refutations and deferrals per PR are in `~/nd-wt/reviews/m2-deferred.md`. In-progress and Downloaded filter chips stay hidden until M4/M6 (`FeedFilterCapabilities`). Owner checks open: AC2 timing on the reference device, AC8 TalkBack, AC6 real process death, the notification-permission flows. |
| M3 | merged; acceptance evidence open | [#203](https://github.com/L-K-M/Neutrodyne/pull/203) through [#210](https://github.com/L-K-M/Neutrodyne/pull/210), [#212](https://github.com/L-K-M/Neutrodyne/pull/212), [#213](https://github.com/L-K-M/Neutrodyne/pull/213), [#215](https://github.com/L-K-M/Neutrodyne/pull/215), [#216](https://github.com/L-K-M/Neutrodyne/pull/216), [#217](https://github.com/L-K-M/Neutrodyne/pull/217) and [#218](https://github.com/L-K-M/Neutrodyne/pull/218) merged | Formats, durable services, Android/desktop hosts, prepared-backup consent, preview, Android receiving, reports/fix-ups/resume, scale/device tests and desktop inputs/backup UI are on main. #218 merged after two completed GLM review rounds and all six current CI jobs, including actual API 26/API 36 XML. No M3 schema change. Local checks and branch device results do not close current-main, native or transport acceptance. |
| M1a GUID provenance | merged | [#88](https://github.com/L-K-M/Neutrodyne/pull/88) `f56a2b9` (replaces #70 and the held #64) | D98/PO-49: feed-scoped GUID history and additive V1→V2 with the frozen V1 schema and fixture unchanged. Two Codex rounds before GLM; GLM r1's ten "critical" items were `execSQL` false positives. The V1→V2 device run passed on the managed devices. |
| Navigation transitions | merged | [#73](https://github.com/L-K-M/Neutrodyne/pull/73) `ff6ad06` (code `08570c3`) | Instant tabs, cover on push, wipe on back and predictive back are as the owner asked; expanded two-pane in-pane swaps (Settings About → Licences → Back) now animate too. Three earlier in-pane designs animated inside one scene and each failed independent review (state loss, duplicate-provider crashes, over-pops, cross-tab mutation). A two-member committee (Sol, SWE-2) traced the root cause, and both candidate designs were built against a 26-test public-behavior gate suite (`PaneSwapGateTest`): the in-pane swap as a real Navigation3 scene change passed 26/26, the alternative pane animator stalled at 19/26. Later Codex closure rounds fixed an early predictive over-pop, completion-only Back over-pops, and cross-tab gesture completion; `0657504` closes with no important finding (86 host, 40 desktop, all gates), and the first GLM round was minor-only (its blocker was refuted against the `NavigationEvent` bytecode). Also fixed [#75](https://github.com/L-K-M/Neutrodyne/issues/75) (two-pane Back popped nothing in Feeds, over-popped in Library). Documented limitations: an expanded detail swap stops an in-progress list fling and ends an IME composition (focus restored); the post-decoration pane animator (`proto/nav73-e`) is the follow-up. Physical predictive gestures unverified. |

### M3 candidate and remaining acceptance evidence

At the 2026-10-20 checkpoint, `origin/main` is `e9d8ba47` (#217). The preceding
L9 reviewed head `66af0a04` passed two GLM rounds, all required CI including server, and
the fresh seeded [39257853895](https://github.com/L-K-M/Neutrodyne/actions/runs/39257853895)
device gate on both APIs. Preview [#215](https://github.com/L-K-M/Neutrodyne/pull/215)
merged after three completed reviews and required CI. Android receiving
[#216](https://github.com/L-K-M/Neutrodyne/pull/216) merged after one completed
minor-only review, required CI including instrumentation/server, and a full
branch nightly. Reports/fix-ups/resume [#217](https://github.com/L-K-M/Neutrodyne/pull/217)
merged after two completed reviews and all six exact-head CI jobs, including
instrumentation and server. Parent verified the five named smoke cases on both
API26/API36, with no failures/errors/skips or host-only failure fixtures. The
first review's unpublished informational tail remains a recorded delivery gap;
the second review was fully delivered, with no confirmed important remainder.
Final desktop-input/backup UI remains a local candidate. The repeated-cancellation
handshake regression passes; two native-menu cases remain headless skips.
The candidate uses the existing schema V2, preserves the unlinked sync state
and keeps YouTube imports external until M8.

| M3 acceptance | Candidate coverage | Evidence still required |
|---|---|---|
| 1 | OPML reader fixtures and preview assertions | Latest-candidate CI/review |
| 2, 7 | `RoundTripPropertyTest`, import commit and membership tests | Latest-candidate CI/review |
| 3 | Hostile-input, bounded JSON, archive and mutation tests; real 400k-row snapshot test in a 128 MiB JVM. [#213](https://github.com/L-K-M/Neutrodyne/pull/213) fixes the signed UTF-32 word stall with failing-first regressions, full local 1,000-iteration proof and `mutation-full` recovery within [39265477995](https://github.com/L-K-M/Neutrodyne/actions/runs/39265477995) (that run's `instrumented-full` job failed under #214); [#211](https://github.com/L-K-M/Neutrodyne/issues/211) is closed. | Latest-candidate CI/review and a green scheduled release gate |
| 4 | `ImportPipelineTest`, no-new-notification and inert-download assertions | Confirm the 300-feed/two-second acceptance measurement on the candidate |
| 5, 11 | Restore merger/runner tests and `BackupCrossPlatformTest` | Actual Android-to-desktop and desktop-to-Android file round trip; native desktop drop/dialog checks |
| 6, 8 | Real first-launch logical restore on API26/API36 in [39257853895](https://github.com/L-K-M/Neutrodyne/actions/runs/39257853895), including the named non-skipped-result gate; host foreign-snapshot and startup-race regressions | Local-transport `bmgr` uninstall/reinstall on API29/API36 and manual API26/28/31 checks. The logical device test is not transport evidence. |
| 9, 10 | OPML writer, export warning/share and prepared-backup consent tests; route tests for backup/restore | Real share-sheet/SAF/provider journeys and latest-candidate UI checks |
| 12 | `SyncInertTest` and round-trip tests cover unique/colliding `syncId` values and empty unlinked sync tables | Latest-candidate CI/review |

The earlier main nightly [39265477995](https://github.com/L-K-M/Neutrodyne/actions/runs/39265477995)
failed `instrumented-full` ([#214](https://github.com/L-K-M/Neutrodyne/issues/214)):
unsupported assumptions were misreported, and two API33 Settings journeys raced
startup. #216 repairs selection before discovery and waits for actual readiness.
Exact-head [CI](https://github.com/L-K-M/Neutrodyne/actions/runs/39346042216) and
[full branch nightly](https://github.com/L-K-M/Neutrodyne/actions/runs/39346043911)
pass. Downloaded XML proves the five named smoke cases on CI API26/36 and nightly
API26/33/34/36, with no failures/errors/skips or host-only failure fixtures.
Actual-main [full recovery](https://github.com/L-K-M/Neutrodyne/actions/runs/39363070031)
and [seeded restore](https://github.com/L-K-M/Neutrodyne/actions/runs/39363072325)
both pass on `7615ae2f`, the main ancestor before #217; #214 closed after actual-main recovery. Parent checked
the named smoke XML and strict non-skipped API26/API36 seeded case. This does not
approve a scheduled/tagged release, native dialogs/providers, physical devices
or `bmgr` transport. Old failures and new proof remain under `~/nd-wt/reviews/evidence/`.

**Toolchain used locally:** Temurin 21 and 25 (Gradle toolchains via `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`), Android SDK with build tools 36/37 and platform 37. The configured Chaquopy `buildPython` path for CPython 3.14 is currently unavailable; local Kotlin compilation does not prove Python bytecode compilation. Available host CPython is 3.11.2; record any fallback explicitly. Builds need `LANG=C.UTF-8` and `LC_ALL=C.UTF-8`. Local software-emulator attempts have not supplied usable device evidence; run instrumented tests and Gradle Managed Devices in CI. Compose UI tests on the desktop JVM need a GL library; locally a user-level Mesa is unpacked in `~/.local/gfx` (`source ~/.local/gfx/env.sh`).

**Owner execution instructions, retain through compaction:**

- Follow `docs/IMPLEMENTATION-PROMPT.md` milestone by milestone through published v1.0.
- Use available helpers, especially free models, Devin SWE-2 and Codex Sol. Since 2026-10-14 the owner has paused Z.ai models for helpers ("Don't use z.ai for subagents right now"); the CI GLM review continues. Check helper results before integration.
- Periodically have Codex Sol 6.1 at maximum reasoning review merged `main`.
- Continue across milestone boundaries without waiting for acknowledgements. Use `opencode/deepseek-v4.1-flash-free` where appropriate; Codex is the fallback when the shared free tier is rate-limited. Keep shared implementation ownership singular and never duplicate a running helper job.
- Keep PRs small and GLM reviews serial to protect the Z.ai quota. Avoid pushes that cancel an active review. The owner allows waiving GLM after repeated integration failures; record the gap and waiver, never claim approval.
- Helper CLIs are the default for independent work (owner's global instructions, 2026-10-16); Anthropic-backed workers only when they fit better. On 2026-10-17 `venice/*` ran out of credits and the free opencode models were rate-limited or overloaded; Devin SWE-2 carried implementation and pre-reviews. Opencode helpers cannot read files outside their worktree.

**Release gate:** (baseline below is stale: main has since taken #103–#154; re-baseline at the next scheduled green) main `33b6cb5` adds the feed parser and corpus (#77, #78), data contracts and the fetcher (#79), ingest (#80), the sniff and challenge fixes (#89), the refresh engine (#81), add/subscribe (#82), the refresh runners (#83), the engine fixes (#84), the paged `FeedRepository` (#87), the start-up recovery fix (#99), GUID provenance with V1→V2 (#88), the show-notes format fix (#92), a runner test fix (#100), the device corpus leg (#98) and two test-race fixes (#101) to the feeds, schema, Room, startup and transitions work. Regular main CI passes at `33b6cb5` ([38447262960](https://github.com/L-K-M/Neutrodyne/actions/runs/38447262960)). The newest scheduled nightly, [38227948941](https://github.com/L-K-M/Neutrodyne/actions/runs/38227948941), passed on `72fe62f`, an ancestor; a scheduled green on the current head is still required before `v0.1.0`. Branch, GMD or older-head success never waives the schedule or owner gates, and `repro` stays report-only. The owner's device report asked for corrected transitions; #73 (`ff6ad06`) delivers them and needs device acceptance. Checklist [#20](https://github.com/L-K-M/Neutrodyne/issues/20), transition acceptance on a device, tagged-main and the remaining manual checks precede publication. Main now exports V2.

**Review queue:** one GLM review at a time. Merged since the last update: #82 (three rounds), #83 (two), #84 (one), #87 (one), #99 (two), #88 (two), #92 (one; both "Major" claims refuted on the PR), #100 (one; a `DesktopJobRunnerTest` race under load, the "blocker" refuted), #98 (two; stale leg (c) status fixed), #101 (one; two load-sensitive test failures: the Android start-up recovery bound and a `SubscribeFlowTest` announcement race Codex reproduced), #90 (two), #91 (two), #93 (two), #94 (three), #97 (two), #96 (two), #95 (two); the M1a UI stack is complete. #103 (one; action snackbars `Long` instead of `Indefinite`, per 08). #104 (one; the 9436e04 review's minors). #105 (two). Dependabot #107–#110 (action bumps, SHAs checked against upstream tags): GLM reviewed all four at once because Dependabot runs receive `ZAI_API_KEY`, so treat Dependabot PRs as quota consumers. #106 (two; both "blocking" claims, a missing task contract and a corrupted pin, refuted on the PR). #111 (two; the CI failure was a real WorkManager test race, root-caused and fixed; r2's stale-stamp claim refuted from the 2.12 bytecode). #119 (one; minor-only). #112 (one; minor-only, `6efe483`). #115 (two, `7707ed2`): r1's confirmed major, a reconcile dropping a desktop secret that a racing `put` had just re-persisted, was fixed test-first by running `rowDropped` under the store's lock. #117 (two plus a 429 rerun, `77a1ee1`): an unreadable secrets file is set aside as `.unreadable` instead of overwritten (test-first), and the temp file is forced to disk before the atomic rename. #120 (two plus a 429 rerun, `03ff793`): the credential cleanup no longer masks a commit failure when the database itself failed (test-first, closed database). The credential half of M1b is on main. #113 (one, `51d4c18`): no confirmed important finding; the "resurrected loser" major is refuted (podcast rows are inserted only by subscribe and import; an ingest into a deleted podcast hits the episode FK or returns `vanished`). Mark a PR ready only after GitHub shows the pushed head; otherwise the review covers the old head. #114 (two, `002f1a9`): r1's confirmed major, a failed redirect confirmation turning a healthy 304 into a failure, was fixed test-first (only a confirmation body replaces the 304; 03's note follows); its other three majors were refuted, and r2 only re-raised one of them. #116 (two plus a 429 rerun, `03e257a`): failed probe fetches no longer enter the 24-hour rejection LRU (03 reserves it for implausible targets; test-first), and a probe fault after a committed ingest no longer records a STORAGE failure. #118 (one, `b684817`): no confirmed important finding (userinfo in fetch metadata refuted: ingest persists neither URL; edit-URL credentials land with #122). #122 (two, `b909b52`): the account controls are live; r1's confirmed finding (a repository fault in edit-URL or set-password escaping the UI scope) was fixed test-first in both view models. GitHub retargets a stacked PR to main when its base merges (merged branches are deleted). Every M1b PR and both review-fix PRs are merged; this handoff (#102) is the last open item, and M2 is next. Main's head is `b50a8b1`. Keep #34 draft until Android publishes. Prune swallowing, IOERR escalation, redundant GUID index and exec claims stay rejected. Avoid starting GLM reviews right after 00:00 and 01:00 UTC: the Paseo stack heartbeats use the same Z.ai plan (HTTP 429 seen on #83); rerun a 429-only failure after ~20 min. Push the UI branches only when one enters review: pushing all eight at once saturated the Actions runners for over two hours. GMD emulator boots fail occasionally (seen on the earlier main commit `ea86095`, `api26Setup`); rerun them.

**Data-hang cause and proof:** the 73-minute U1 paging hang produced 72,445,492,508 bytes of test events, then a daemon OOM. Oversized binaries were removed; source, daemon log and retry XMLs remain. Capped probes reproduce inherited `TimeoutCancellationException` retrying, about 695 MB counted stderr/eight seconds and a fresh writer pending after six seconds with Free/permits-0 state. Protected normal release refutes interrupted-release claims; test-clock divergence accelerates a real hazard. All probe groups are dead, 168 KB evidence retained. Caps remain 120 s plus 15 s kill, 64 MiB/file, 512 MiB total, fresh no-daemon, ≤2 workers. Stable 3.0.3 has no fail-fast switch; alpha retains the flaw. #56's reproducible Apache-2.0 repair is merged in `98dd9a7`, compiler/plugin/paging/testing remain upstream 3.0.3. Remove vendoring once upstream ships equivalent behavior. Older synthetic data/UI bases still need refresh; never replay full unpatched suites or treat green retries as resolution.

**Remaining UI sequence:** feature installers activate routes immediately. #57's real Metro/Coil and sourced-Mesa window paths pass; minimum public scheduler export is now proven with internal constructor/rebaser. Fresh composed CI/review remains. `FeedRepositoryImpl` precedes feeds/podcast; preserve splash/startup gates and the 30-source desktop shell. Feeds/data, podcast/settings, discover/episode and the small documented remainder follow.

**Periodic main review (2026-10-08, Codex Sol 6.1 Max, `9805dbf`):** no Android-only blocker found in the reviewed range. All three important desktop fixes merged: runtime host/archive handling [#36](https://github.com/L-K-M/Neutrodyne/pull/36) (`8cccf7c`), bounded/cancellable handshake reads [#35](https://github.com/L-K-M/Neutrodyne/pull/35) (`221feb1`), and returning smoke-window/first-frame validation [#39](https://github.com/L-K-M/Neutrodyne/pull/39) (`9a6e78e`). GLM's alleged `assertThrows` compile blocker was refuted by the shared test helper and forced convention tests. The Windows jpackage suffix claim was Linux-only. Malformed server CIDRs and the stale licence-repair note in design 01 remain deferred.

**Windows test portability:** [#46](https://github.com/L-K-M/Neutrodyne/pull/46) merged as `9a81df3`. CI and the native Windows unit step above pass with identical test sources. One GLM round found no applicable defect: the resolved runtime uses `kotlin-test-junit` and JUnit 4. Temp-directory cleanup and unrelated fixture consolidation are deferred.

**Periodic main reviews (Codex Sol 6.1 Max):** 2026-10-09 on `ce2ec9a`: no important finding across `6ed5d75..ce2ec9a` (#59, #56, #55). 2026-10-10 on `a7200f5`: no important finding (schema #38). 2026-10-11 on `52b2fd6`: one important finding (an overflowing error code stranded the open gate), fixed by #74; the healthy-launch quarantine-expiry minor is deferred as an owner trade-off. 2026-10-12 on `97a57cf` (#22, #74, #41): no important finding; merge trees match the reviewed heads, startup preserves #74's failure publication on both shells, module-graph and dependency policies pass; the IDNA numeric-host minor was fixed by #76. 2026-10-12 on `d89d290` (#73, #76, #72, #77–#79): no important finding; #89 fixed the prefixed-Atom sniff and the Basic-challenge stack overflow, and this update fixes the stale handoff. 2026-10-12 on `c77d211` (#80, #89): no important finding; merge trees match the reviewed heads. Its two code minors (the show-notes cache ignores a changed link base; Atom plain-text descriptions render as HTML) were fixed by #92 (`ea86095`); this update fixes its third, the README's "no code yet". 2026-10-12 on `327a4e4` (#81, #82): three important findings (a discarded final-flush failure, an unguarded failure write in the fetch-exception guard, decimal-IPv4 host spellings bypassing the per-host lane) and two minors; all fixed by #84. 2026-10-13 on `223aa5e` (#83, #84): all earlier findings fixed; one important finding (refresh initialization never resumed after a database-recovery retry), fixed by #99; the stale corpus note is fixed on #98. 2026-10-13 on `f56a2b9` (#87, #99, #88): no important finding; merge trees match the reviewed heads, the startup-retry fix holds on both shells, and the V1→V2 migration matches frozen `2.json`. Of its four doc minors, #98 fixes the corpus note; #90 fixes the README status, 03's D98 upgrade sentence and 02's builder example. 2026-10-13 on `df14937` (#92, #100, #98, #101, #90): no important or new minor finding; every earlier finding fixed, merge trees reconstruct exactly, release APKs carry no corpus assets, and exact-main CI passes with all 70 device goldens and both V1→V2 driver paths on API 26 and 36. 2026-10-14 on `9436e04` (the M1a UI stack, #91–#97): one important finding (ignored action snackbars held the root queue), fixed by #103; two minors (the row's missing playback custom action, stale 08 placeholder notes), fixed by #104. Merge trees match the reviewed heads, 3,060 tests pass, desktop compile and runtime classpaths agree, and every pushed nav key renders its real screen. 2026-10-14 on `26de636` (#103–#110): no important finding; merge trees match, the 1,000-iteration mutation run passes locally in 16 min under the 128 MB heap, and all four action pins match upstream tags. Two minors (a step timeout for the nightly mutation job; R7.1, M1 AC11, M3 AC12 and 10 claiming every sync table stays empty although `sync_state` keeps its default row) are fixed by #119 (`8b8dc22`, one round). 2026-10-15 on `cce2a41` (#111, #119 and M1b #112–#122): six important findings, each reproduced by a scratch probe (`~/nd-wt/reviews/main-cce2a41.md`, evidence under `~/nd-wt/reviews/evidence/review-main-cce2a41/`). Secrets: a malformed desktop secrets file logs password excerpts; publisher userinfo in a 301 or `new-feed-url` target is stored in `feedUrl`; the Edit-URL draft keeps a typed password in saved state; stored IPv6 origins keep brackets and never match OkHttp's host. Engine: unbounded Levenshtein on publisher titles can stall a run; an in-flight 401 can re-block freshly saved credentials. The four secret findings are fixed test-first by #123 (`9b681ba`, one round; its "discards working credentials" major refuted on the PR: the fetcher never sends URL userinfo). The two engine findings (a 256-character title-compare bound; `setCredentials` under the refresher's run mutex, lock order run mutex → `commitMutex` → coordinator → Room writer) are fixed by #124 (`b50a8b1`, two rounds; its CI failure was a 200-ms test budget, now 2 s). 2026-10-17 on `4919b3a7` (Codex Sol max for data, domain and notifications; Devin SWE-2 for UI; every finding checked by an independent skeptic): five important or minor cancellation and race findings and one missing translation, fixed test-first in [#153](https://github.com/L-K-M/Neutrodyne/pull/153) `5fedf13` (writes and their owed effects now finish as one `NonCancellable` unit; the rebase write skips force marks; the channel sweep rereads groups after undo tokens; poster binder calls off Main; the two missing German strings) and [#154](https://github.com/L-K-M/Neutrodyne/pull/154) `97aaf40` (ingests that commit as a run is cancelled still notify). Evidence: `~/nd-wt/reviews/evidence/review-main-4919b3a7-{a,b}`.

**Resume:** fetch first; cut new task branches from `origin/main`, or merge it into existing work before edits. Check PR heads, CI and review comments before acting. Worktrees are under `~/nd-wt/`, prompts and reports under `~/nd-wt/prompts/` and `~/nd-wt/reviews/`. Use `export JAVA_HOME=$HOME/.local/jdks/jdk21 ANDROID_HOME=$HOME/android-sdk LANG=C.UTF-8`; source `~/.local/gfx/env.sh` for Compose JVM tests. Watch free disk space; prune only verified merged worktrees and regenerable outputs, never active helper work.

**Coordination:** the parent agent owns serial CI, reviews and this handoff; child agents run no monitors or heartbeats. Idle and reusable: `f0c145ae` (Devin, built the PR #73 gate base `proto/nav73-base`), committee members `eee69e7d` (Sol, author of the accepted #73 design on `proto/nav73-b`) and `8e9d8f13` (SWE-2). Keep `proto/nav73-e`, its WIP pane animator, intact until the #73 continuity follow-up lands. GUID `ff56521` stays frozen in `guid-provenance`. Open-PR and M3 worktrees under `~/nd-wt/`: `m3-a1` … `m3-b8` (one per M3 row branch `impl/m3-<row>`; drafts #155–#157, #159–#162 and later), `docs-handoff-m2` for this file; check `git worktree list` before removing any. The local branches `test/m1a-refresh-g1`, `-g3` and `-g5` were cherry-picked, modified, into #111 (merged); `git branch -d` refuses them and no PR covers them, so they stay; their content is on main through #111, so `git branch -D test/m1a-refresh-g1 test/m1a-refresh-g3 test/m1a-refresh-g5` is safe once the owner agrees. The Paseo worktrees `data-reviewed-refresh-base`, `db-shell-device-slice` and `navigation-transitions` predate this session; check their owners before removing them. Long helper-CLI jobs run detached through `~/nd-wt/bin/helper-job.sh` (start/wait), so workflow relays can poll past their 10-minute call limit. Review reports and probes live under `~/nd-wt/reviews/` and each review worktree's `build/review-*/`; prompts under `~/nd-wt/prompts/`. Legacy heartbeat `b6598800` expired 2026-10-10 15:32 UTC; delete it on takeover if it still exists and never extend it silently. Preserve the `~/nd-wt/review-*` worktrees' `build/review-*/` evidence: copy it to `~/nd-wt/reviews/evidence/` before removing a worktree. On 2026-10-13 the `review-main-52b2fd6`, `review-feeds2`, `review-nav-static` and `review-guid88` worktrees were removed before this rule existed: their `build/review-*/` evidence was deleted, not copied, and only their reports remain in `~/nd-wt/reviews/`. `main-review-3` and `rare-catfish` no longer exist; older proofs are listed in this file's git history.

**Deferred follow-ups:** integrate the verified query-component UTF-8 repair (#86, from #63) after its predecessors; make process-start probe reports atomic; guard `SettingStore.observe` against a key from the wrong file; optionally clamp sanitizer image dimensions. From this round:
- M2 and the 2026-10-17 review: every deferred item with its reason is in `~/nd-wt/reviews/m2-deferred.md`, among them D1's last `markVisited` on teardown, the podcast chip remove target (M10), the Library tile-menu counted dialog, D9's UTC month cutoffs, the sync-era merge of hidden filter flags on write (D11), the `FeedIngestor` caller-contract comment (#154), and `SubscribeUseCaseImpl`'s cancellable post-commit scheduling (same class as #153).
- Owner trade-offs: healthy-launch quarantine expiry for recovery copies (2026-10-11 review); a retry policy for transient prune failures (#74 round 1).
- #41 minors: stale KDoc, logging discarded retry failures, an `ActivityNotFoundException` guard on the storage action (`StorageManager.ACTION_MANAGE_STORAGE` may be the better target), the duplicated fixture parser, a cascade assertion.
- #22 deferred minors (PR conversation).
- #73's pane animator follow-up for list fling/IME continuity.
- #80: split ingest preparation from the write transaction (the 2c caller); dateless items and the new-episode window (product question).
- #81 test nits: close the database in `NextRefreshRebaserTest`; add forced and blocked rows to the manual-only rebaser case (production already skips them).
- #83 deferred: chunk `downloadedEpisodeIds` at the bind limit; GLM r2 test nits (hard-coded retry boundary, the host fixture's ingestor dispatcher).
- #84, #87 deferred: test-hygiene nits (single-dispatcher assumption, closing local test databases); affected-row counts from the order setters when M2 needs them.
- UI pre-review deferrals: the tile-menu separator, the Library empty state's Import action, the three duplicate `*Filled` navigation icons (pre-existing on main), and the declined Podcast-screen nits.
- #103 deferred: the About page's copy-link snackbar has no dismiss action; the action→`Long` rule repeats in `UrlOpeners` and `AboutPage` (a public core:ui helper would unify it).
- #95 deferred: an unused import in `AddPodcastRouteTest`.
- #112 deferred: document `SecretStore.put`'s secret-array ownership; `FakeSecretStore.get` returns its internal array and keys raw `put` origins differently from `storedKey`; test `putByOrigin`'s lowest-id branch for duplicate origins; `StoredSecret` has reference equality.
- #115 deferred: reload the cached Keystore key after an in-session wipe (a safe reload must never delete a key on a transient error); zero plaintext bytes (Strings carry them anyway); tell transient resolve failures apart from absent material; batch the absent-row check; harden the reconcile-pass test probe.
- #117 deferred: a second unreadable file replaces the first `.unreadable` copy; sweep crash-orphaned `secrets*.tmp` files; test teardown joins and timeout context.
- #120 deferred: a stale batched `needsCredentials` write can land after `setCredentials`'s clear (needs a concurrent 401 for that podcast; re-entering recovers); log a preview credential dropped when origin parsing fails; "podcast missing" maps to `InvalidUrl`; unsubscribe and subscribe test scopes, joins and probes. Declined: `AuthInterceptor` degrading to no-auth before the start-up gate.
- #114 deferred: `moveFeed` keeps `pendingNewFeedUrl`/`pagingNextUrl` (the same transaction's ingest rewrites the pending target); FeedMovesTest temp-dir teardown, unbounded awaits, raw table names, request-count pin.
- #116 deferred: an adoption fault still LRU-rejects a plausible target; the duplicated deadline margin; self-target and nil-GUID probe tests.
- #118 deferred: EditFeedUrlTest's active-subscription case compares the database with itself; seed episodes and assert their ids survive the move.
- #122 deferred: a credential displaced by an edit to another origin stays until 02's `db-maintenance` orphan sweep exists (not built yet); the edit dialog's in-flight indicator and blank-URL message.
- #123 deferred: artwork and enclosure URLs still persist publisher userinfo; the IPv6 origin parse assumes a leading bracket; CredentialCleanupTest reads the global LogSink.
- #124 deferred: `setCredentials` waits for a whole running refresh, and a cancelled save is dropped (the trade-off `editFeedUrl` makes).
- #119 deferred: the nightly mutation step's comment says it leaves the job's "last 15 minutes", but setup steps run first, leaving about 11 to 13.
- #96 deferred: autofill content types on `CredentialFields` (before M1b enables them); the settings pop waiting for the removed-feed snackbar (needs a root-owned snackbar scope); the header/banner/chips duplication; two test notes.
- #94 deferred: the mark-all pending id when its tile vanishes; sort radios outside a selectable group; `NdDialogAction` parameter order; the disabled destructive tint; the red-pixel predicate also matching amber; the hand-kept pushed-key lists in the graph tests.
- M3 B10 review (`impl/m3-b10`, 2026-10-17): resolved on `impl/m3-b11` — `mergeLibrary` returns a `LibraryMergeResult` carrying its settled key/alias → local-id and staged `uuid` → survivor maps, and the episode phase attaches only through them. Still on B12's checklist: nothing in `mergeLibrary` wraps or blocks linked-device capture triggers; the B12 runner owns that wrapping.
- #93 deferred: EmptyState's gap between stacked text buttons; a tile menu already open when selection starts; the zero-duration sentinel; `ShowNotesRendererTest`'s English click label.
- #91 deferred: `DefaultArtworkStoreTest` shares a fixed `build/tmp` directory (use a temp dir); declare the `:core:common` → `:core:model` edge as `api`.
- #90 deferred: 02's `encode` comment should say the header byte precedes both size paths.
- #98 deferred: a reverse (golden without fixture) pairing check, in corpus leg (a) rather than only on the device.
- #100 deferred: state `awaitIdle`'s started-run precondition in its KDoc.
- #92 deferred: exhaustive `when` in `DescriptionCodecTest`'s tag helpers; one shared JVM codec instead of identical Android/desktop copies.
- #88 deferred: #99's `refresh-periodic` literal in `AndroidStartupRecoveryTest`.
- #88 residual policy, owner-visible: a recycled `KNOWN_INDEPENDENT` GUID claims by authority; an episode returning without an enclosure to an unproven rotated slot duplicates.

These are not blanket release approvals or completed work.

### M4 playback, P1–P7 merged, P8 in progress

**2026-10-30 checkpoint.** P7 #243 merged as `a6a2f555` after two review
rounds. [Main CI 40882142965](https://github.com/L-K-M/Neutrodyne/actions/runs/40882142965)
passes all six jobs after an unchanged runner retry. Downloaded XML confirms
96 device cases without failures, errors or skips. Final-head full-debug
nightly evidence remains unavailable after runner interruptions. That is a
release-evidence gap, not a PR merge requirement.

Root scaffolding [#250](https://github.com/L-K-M/Neutrodyne/pull/250) merged
as `ceb3e487` after two minor-only review rounds.
[Main CI 40960233783](https://github.com/L-K-M/Neutrodyne/actions/runs/40960233783)
passes all six jobs. Reviewed-head XML confirms 96 device cases, no failures
or skips.

The player candidate on `impl/m4-p8-player-ui` supplies mini/full transport,
Up next/Notes panels, Speed, persistent time display and shared policy/error
handling. Failing-first fixes cover stale model/panel state, covered-content
semantics, snackbar access, transient Back ownership, cancelled predictive
Back and large-font geometry. After #255 integration, split gates cover
1,707 cases: 1,706 passes and one Windows-only DPAPI skip. UI/native gates
cover 435 cases, including 283 freshly rerun core/adapter cases; unchanged
domain/testing/data/API gates cover 1,272. Scoped 45-file lint, platform
compilation and 18 presence fixtures pass.
Earlier interrupted reports are not proof.

Hosted stress captures exposed Android-only nonlinear font clipping despite
desktop checks. A real SDK36 Robolectric regression failed `22 < 32` for the
context line. Measuring actual text instead of converting line-height sp
fixes it; Android-host and desktop regressions pass. Stress references from
[40975400402](https://github.com/L-K-M/Neutrodyne/actions/runs/40975400402)
and [40990941348](https://github.com/L-K-M/Neutrodyne/actions/runs/40990941348)
were rejected. Corrected references from
[41008967659](https://github.com/L-K-M/Neutrodyne/actions/runs/41008967659),
source `b370185d`, are inspected and applied: 24 player PNGs cover both
platforms, light/dark, large-font RTL, panels and Speed. Verification-only
checks pass all 24 images in 13 tests; recording stays disabled.

[Player #253](https://github.com/L-K-M/Neutrodyne/pull/253) has four completed
formal rounds. R2–R4 found no verified important defect; optional nits stop. On `47110070`, the real journey failed API26 idleness and
API36 navigation-target occlusion. The policy-aware frame loop and retained
dock measurement fix both: [CI 41024179011](https://github.com/L-K-M/Neutrodyne/actions/runs/41024179011)
at `f5af9950` proves 98 device passes, including the real Play journey once
on API26/36. Its unchanged first-arrival unit failure prompted #255; the
precise original timing remains unproven. Non-release profiles require the
journey; the seven-case release smoke ABI is unchanged.

[CI 41135770086](https://github.com/L-K-M/Neutrodyne/actions/runs/41135770086)
at integrated `d9076331` failed unit and device gates. An unowned test lookup
barrier could hold a refresh instead of the explicit start, allowing an
epoch-fenced event to disappear before the expected storage callback.
A failing-first helper regression now passes with an owned barrier;
181 adapter cases pass without changing product code, assertions or budgets.
The exact CI interleaving was not recorded.

API26 also had two independent process deaths: Compose's nested-layout
invariant in Licences, and StrictMode's Activity instance counter during
service-test teardown. Orchestrator isolates the tests. The service test
now resumes its existing host after killing the service, avoiding unrelated
Activity recreation without relaxing StrictMode. The Licences cause remains
unproven; a test-runtime traversal race is a hypothesis, not a fix. Both
real journeys passed, but only 67 app cases ran and the full 98-case gate
did not finish.

[CI 41141671060](https://github.com/L-K-M/Neutrodyne/actions/runs/41141671060)
at `cd205e15` passes unit and the other four non-device jobs. Licences and
both real journeys pass. Service recreation fails on both APIs: moving the
Activity lifecycle to RESUMED does not bring its task back from Home.
The fixture now foregrounds the existing task through Activity Manager,
preserving the launcher intent that ActivityScenario matches. It checks
the captured host's lifecycle on Main, foreground package and original
Activity identity. No Activity recreation, policy relaxation or larger
budget is used. Compilation passes; fresh exact-head device proof remains
required.
The earlier Licences cause is still unproven.

Buffering/refused-advance safety [#251](https://github.com/L-K-M/Neutrodyne/pull/251)
merged as `e20a440f` after two review rounds. Local core/adapter suites pass
274 cases; reviewed-head XML proves 96 device cases without failures,
errors or skips. [Main CI 40983471054](https://github.com/L-K-M/Neutrodyne/actions/runs/40983471054)
passes all six jobs.

Stale durable-dismiss fencing [#252](https://github.com/L-K-M/Neutrodyne/pull/252)
merged as `6079ec10` after two review rounds without a verified important
finding. [PR CI 40988017111](https://github.com/L-K-M/Neutrodyne/actions/runs/40988017111)
passes all six jobs and proves 96 device passes. Four failing-first
client/native variants pass; integrated local suites pass 1,548 cases with
one Windows-only DPAPI skip. Connected-command ordering and navigation
intents remain outside that fix.

Late-snapshot fencing [#255](https://github.com/L-K-M/Neutrodyne/pull/255)
merged as `40a5086d` after one formal round without a verified important
finding. A coherent same-generation mid-start read changed native
`[1,2,3]` to `[1]`. Emissions now trigger fenced durable reconciliation;
matching metadata is reused and collection stays conflated during IO.
A temporary async code mutation failed with 101 pending reads instead of
one; it was reverted. Current code passes the one-read burst assertion,
marker-backed queue check and persisted native Next. Local core/adapter
suites pass 281 cases. [CI 41035737384](https://github.com/L-K-M/Neutrodyne/actions/runs/41035737384)
at reviewed head `3f521bda` passes all six jobs after one unchanged unit-only
retry of an unrelated feed timing guard; 179 adapter and 96 actual device
cases pass. No timing threshold changed. The original first-arrival case
passes too, but the earlier CI timing was not reproduced directly.
[Main CI 41132537848](https://github.com/L-K-M/Neutrodyne/actions/runs/41132537848)
passes all six jobs.

Synchronous KVM preparation is now on main. A controlled
[probe](https://github.com/L-K-M/Neutrodyne/actions/runs/41009762790)
showed udev returning before permissions changed. P8 and #255 pass preflight
and device execution; the original failed VM's cause remains unconfirmed.

No desktop audio, chapters, sleep timer or artwork tint is added.
Physical/OEM, audible, TalkBack, desktop modal Escape and genuine
platform-denial recovery checks remain unverified.

**2026-10-28 checkpoint.** Main `5ddfe29a` passes all six jobs in
[CI40622726292](https://github.com/L-K-M/Neutrodyne/actions/runs/40622726292).
#244 authenticates Auto/AAOS identities; #245 fences stale events/retries and
late play replies, preserves streaming policy and retains storage recovery;
#246 supplies source-bound hosted screenshot recording. Their completed
reviews found no confirmed current blocker. Real Auto/AAOS behavior remains
unverified; v1 has no AAOS-specific device-test requirement.

P7 [#243](https://github.com/L-K-M/Neutrodyne/pull/243) contains the queue UI,
episode playback actions and in-progress filter. Its 18 queue references
were recorded on Linux from source `1771507d` in
[40623702421](https://github.com/L-K-M/Neutrodyne/actions/runs/40623702421).
The real API36 journey exposed snackbar occlusion of navigation targets;
a failing-first geometry test and all 23 root tests pass after moving notices
inside navigation content. Source `1771507d` passes the API26/API36 queue
journey and API36 ATF negative control in
[CI40623707135](https://github.com/L-K-M/Neutrodyne/actions/runs/40623707135);
only missing references failed its unit job. No ATF result was suppressed.
Final-head CI, review and nightly receipts belong to #243; P8 remains separate.

**2026-10-27 checkpoint.** Main `babd24b0` passes
[CI40569423211](https://github.com/L-K-M/Neutrodyne/actions/runs/40569423211),
all six jobs. #240 fixes #236's test-clock race without changing production
playback. #241 restores the ordinary release APK's instrumentation ABI and
requires all seven release cases. Its reviewed head `a6e915ef` passes
[full nightly40561577857](https://github.com/L-K-M/Neutrodyne/actions/runs/40561577857):
API36/API37 release cases, API37 debug, API26/33/34/36 debug playback,
API34's 1,821.826-second screen-off case, bmgr, bgstart, mutation and APK
comparison. The merged tree matches the reviewed head. One full review round
found no confirmed current blocker; ABI-upgrade and fixture maintenance is
tracked in [#242](https://github.com/L-K-M/Neutrodyne/issues/242).

#232's API31/36 lane measures only its external exemption matrix, not a
genuine platform denial or denial-to-resume recovery. No retained test drives
that callback end to end. YTX Binder/PID/name checks prove process wiring,
not graph construction or initializer absence. Back passed unchanged on
API36/API37; the earlier focus failure's platform cause remains unproved.
Evidence and completion report are under `~/nd-wt/reviews/`.
P7 queue UI is merged; its worktree and branch were removed after archiving
the evidence. P8 player/root composition follows on current main. Physical/OEM,
audible and genuine-denial acceptance remains open.

M4 plan row P1 is merged through [#222](https://github.com/L-K-M/Neutrodyne/pull/222)
(widened contracts, fakes, `PlayedRule` / `StartPositionRule` / `PositionSaver`,
the durable `SessionWriter` / `PositionWriter` / `PlayedWrites` / `QueueKeyWrites`
stores, `LocalMediaIndex` / external-only YouTube contracts, and one truthful
unavailable playback binding per shell).

M4 plan row P2 is merged through [#224](https://github.com/L-K-M/Neutrodyne/pull/224)
as `ff415871` (2026-10-21 00:30:15 UTC), reviewed at exact full-tree equality
with all six CI jobs and one completed formal round; independent triage found
nothing important. It adds the durable `QueueRepositoryImpl` (fractional `orderKey`
Up next plus `undoRemoval` capturing ordered `QueueRemoval`s in the removal
transaction), `PlayContextResolverImpl` (strict-keyset context tails,
identity-filtered current), `PlaybackMediaLookupImpl` and the shared
`EpisodeRows` projection; mark-played reuse through `PlayedWrites` in the
episode/feed repositories and `ImportFetchRunner`; `EpisodeLiveStateSourceImpl`
now merging persisted rows with `PlaybackStateSource`'s `NowPlaying`/position
ticks; and the remaining `:playback:core` rules — `PlaybackContracts`
(`VirtualQueue`, `WindowOp`, `StartOutcome`), `PlayStarter`,
`PlaybackEligibility`, `PlaybackHistory`, `QueueWindowPlanner`,
`RecoveryBudget`, `WindowDiff` — with `PlaybackFixtures` and their `commonTest`
suites. All five durable playback ports are Metro `Provider<T>` roots in both
shell graphs, resolved and identity-checked only after `awaitOpen()`; exactly
one unavailable controller/state/maintenance binding remains per shell. No
schema change. Playback still is unavailable on both platforms — nothing
plays; the Media3 service adapters, artwork and player UI remain in
their own M4 slices. Physical-device, native, transport (`bmgr`), audible and
release evidence is not produced by this work and remains open with the rest
of M4's acceptance.

M4 plan row P3 is merged through [#225](https://github.com/L-K-M/Neutrodyne/pull/225);
it was assembled on `impl/m4-p3-scoped-settings`, net-hunked from the
same frozen source record `453ea570` (`599b393b` scoped settings,
`aed7bc5e` settings UI) onto reviewed main `ff415871` (P2's merge). It adds the
eight `playback.*` portable keys and the `PlaybackSettingKeys` registry
(speed/preset/skip-interval/cache validation), `AllSettingKeys.acceptsValue`,
scoped `playbackSpeed`/`skipSilence` on podcast/group rows with attributed
`EffectiveSettingsResolver.playback`/`observePlayback` resolution, membership
checks and per-field more-specific clears in
`ScopeSettingsRepositoryImpl.writePlayback`, centralized DataStore/restore
registry validation (invalid legacy speeds read as inherit), updated fake
contracts, `EffectivePlaybackSettings` in `:playback:core`, and the Settings ›
Playback page: `PlaybackPage`, `PlaybackSettingsViewModel` (on
`SettingsRepository` + `PlaybackMaintenance`, no DB/file access), enabled-state
support in `SettingsChrome`, EN/DE strings and `MINI_PLAYER_SKIP` in
`AppearanceSettingKeys` (the Appearance row itself is P8). `SettingsHomeRoute`
activation stays P6; this slice tests the page components directly. `:feature:settings` gains
the permitted `:playback:api` edge. No schema change; M2 refresh/notification
merge and default rules are untouched.

Independent review of source `74225d28` and its connected callers found no
confirmed important issue. Parent verification ran one complete aggregate across
model/datastore/database/data/testing/playback-core/settings/groups/podcast,
`:core:{data,database}` Android host tests, and both shell graph suites —
4,709 test records, zero failures/errors, three pre-existing environment skips
(DPAPI Windows-only, two headless native-menu). The database desktop task executed
152 records; 12 tasks reused 4,557 same-input records. The earlier 4,557 count
omitted that database suite and is not added again. Source hashes, selected XML
snapshots and task states are retained. The initial pre-split home-route test's
failed XML was not retained; no bug-regression claim depends on it. Doc validators
report 0 problems and 0/78 invalid diagrams. Formal review, current-head CI,
labelled instrumentation and native/device legs remain pending; receipts are
under `build/review-m4-p3-{assembly,parent}/` and the ignored assembly/review notes.

M4 plan row P4 is merged through [#228](https://github.com/L-K-M/Neutrodyne/pull/228)
as `c9ecfe11`; it was assembled on `impl/m4-p4-artwork-cache`, net-hunked from the
same frozen source record `453ea570` (`dd84b337` core artwork/provider,
`27a317ad` source index/resolver/cache/prefs subset, `85c93bb0` final prefs
context, `f418ee50` resolver/index URI corrections) onto reviewed main
`f1c6e9ff` (P3's merge). It adds the common `ArtworkSyncRunner` /
`ArtworkSyncScheduler` / `ArtworkCodec` / `TinyImageInterceptor`, the Android
codec, monogram renderer, WorkManager worker/scheduler and the exported
read-only `ArtworkProvider` (`${applicationId}.artwork`) with a generated
neutral fallback and no network path; the desktop work scheduler; safe-key
`ArtworkRefMapper`/`DefaultArtworkStore` updates; the `ArtworkDao` additions
(`syncCandidates`, `fallbackFor`, `observe`, transactional `applyBatch`,
`garbage`, `delete`, `recountPins`, `ARTWORK_REFERENCE_COUNT`); and in
`:playback:impl` `EnclosureFingerprint`, `EpisodeSourceIndex` (bounded
awaited-open fallback), `EpisodeResolver` (canonical
`neutrodyne://episode/<id>` URIs, RSS-only remote pins, YouTube/other sources
unsupported), the lazy bounded `StreamingCache` (no index or directory until
first need; refcounted trimming, resize and clear preserving active pins),
and `PlaybackPrefs` gated on the first durable settings snapshot with its
order-300 `PlaybackPrefsInitializer`. `StreamingCache` implements
`PlaybackMaintenance` but is not bound yet — the unavailable stubs stay the
single truthful maintenance binding per shell until P6. Required Media3
catalog aliases and `playback:impl` dependencies travel with it; the Android graph tests assert the
provider's deferred access and the initializer band, and the manifest test
pins the provider's exported read-only contract. No schema change; playback
still is unavailable on both platforms, and the service/receiver/player,
routes, Appearance row and P5+ factories/adapters remain in their own M4
slices. Local verification and limitations are recorded in
`.nd-context/m4-p4-assembly-report.md` with receipts under
`build/review-m4-p4-assembly/`; labelled instrumentation and native/device legs
remain open with the rest of M4's acceptance.

Independent source review found no important issue. A parent regression exposed
neutral PNG bytes reported as JPEG; MIME and display-name metadata now match the
image. The unused playback `lifecycle-process` dependency is omitted.

M4 plan row P5 merged through [#230](https://github.com/L-K-M/Neutrodyne/pull/230), net-hunked from
the same frozen source record `453ea570` (`27a317ad` sources/factories,
`563cf609` Media3 adapters, `f418ee50` MediaItemFactory constants/URI
corrections) onto merged main `c9ecfe11` (P4's merge). It adds
`GuardedHttpDataSource` (auth/range/content-change guards, stale signed-final
retry, requested-URI reporting so no signed URL reaches cache metadata),
`MediaItemFactory` (canonical `neutrodyne://episode/<id>` items, enclosure
fingerprint cache key, local hint, completion extras, pinned-artwork URI),
`NdMediaSourceFactory` + `NdLoadErrorHandlingPolicy` (local/remote extractors,
fatal RSS error classes, one original-URL retry), `PlayerFactory` +
`BoostLimiterProcessor` (speech audio chain, Sonic + silence skipping, no
offload, local/network wake modes, live seek/skip prefs), and the four
player-thread adapters: `QueueProjector` (window diff apply, outgoing-pin
release, foreign-generation load-paused, external set/add write brackets with
2 s timeout, bracket-close refresh re-reading the durable queue rather than
replaying a stashed snapshot, the pre-read `revision` re-checked inside the
serialized apply so a bracket spanning the read, input or mutex wait discards
and re-arms, with collected-emission identity also fencing unbracketed writers),
`PositionTracker` (outgoing capture before arrival, 5 s tick,
teardown `NonCancellable`), `EffectivePlaybackApplier` (effective speed/skip
silence before prepare, temporary-speed retention on metadata updates), and
`ErrorRecovery` (classified skips vs bounded reprepare/reconnect inside the
foreground window). The adapter harness runs Media3 test players against the
real Room stores via the test-only Java bridge, plus MockWebServer HTTP tests
and three synthetic MP3 fixtures with their generator script. P5 introduced no
service, receiver, manifest activation or production graph binding: that boundary
kept the truthful unavailable controller/state/maintenance binding per shell.
It made no schema change and omitted `lifecycle-process`, `coroutines-guava` and
`coroutines-android`; no P5 consumer imports them. Its affected aggregate had
1,977 records, zero failures/errors and three existing environment skips.
The test-readiness correction executes 80 playback records and reuses 1,897
same-input records; its 25-test projector class also passes on one CPU. Room
metadata echo is required before arming refresh barriers, and all four semantic
counterexamples still fail against the pre-fence baseline. The `5fd58109` CI
and named API26/36 app/driver cases passed. The later `5bd60eee` integration
passed all six CI40174233128 jobs and the original named API26/36 cases. Full
formal40175399577, independent source triage and parent checks left no important
finding in delivered feedback; optional minors were deferred under the first
minor-only stopping rule, with the truncated INFO tail recorded as unknown.
The squash merge `9cf58b7b` exactly matches the reviewed complete tree. P5 does
not inherit service/UI/device/native acceptance from the prototype.

M4 plan row P6's source merged through [#231](https://github.com/L-K-M/Neutrodyne/pull/231),
with the external measurement companion [#232](https://github.com/L-K-M/Neutrodyne/pull/232).
Its historical branch `impl/m4-p6-android-service` was net-hunked
from the same frozen source record `453ea570` (`85c93bb0` Android
service/session/controller/lifecycle, `f418ee50` service/session fixes,
`c29dabc3` device-evidence gates, and only the `aed7bc5e` Playback
settings-route activation) onto fresh main `9cf58b7b` (post-P5 merge). It
replaces the truthful-unavailable `AndroidPlaybackStub` with the real
`PlaybackControllerImpl`/`PlaybackStateHub`/`StreamingCache` bindings and
`AndroidLocalMediaBindings` (the approved empty `LocalMediaIndex`), adds
`NeutrodynePlaybackService` (MediaLibraryService + one ExoPlayer +
MediaLibrarySession), `PlaybackServiceAccess` (deferred `Provider` ports behind
`DatabaseOpener.awaitOpen()`), `SessionPlayer` (deferred listener ordering
after tracker/projector/applier), `SessionCallback`, `MediaLibraryTree`,
`MediaButtons`, `CustomCommands`, `MeteredStreamingGate`,
`EndOfItemPauseArbiter`, `ResumptionProvider`, `TapToResumeNotifier`,
`PlaybackChannels`, `PlayerConnection` (order-300 process observer), the
`PlaybackBeforeUnsubscribe` seam + fake + `UnsubscribeUseCase` pause hook, the
manifest/permission additions, and the app-hosted `PlaybackServiceTest` with
`assert-playback-tests.py` selection/enforcement gates on the CI `ci`, nightly
`nightly`/`api37` and `release` legs. The Playback settings row/route now
activates against the real maintenance binding; P7 queue UI, P8 full
player/root composition and the Appearance mini-player control stay out; the
desktop binding stays truthfully unavailable. Independent review at `8590b615`
found that notification/session-activity content taps targeted the exported
launcher, so the internal player deep link was discarded. The shared
PendingIntent now targets the protected `InternalMainActivity` alias. The
existing notifier regression failed on the old target and passes after the fix
(two focused cases). Initial parent verification at `69ac9f6d` covered 2,088 records with zero
failures/errors and three existing environment skips: 422 executed (120
playback, 151 app, 142 data Android-host and nine database Android-host), 1,666
same-input records reused. Debug/test/release and standalone-driver assemblies,
required release lint, module/dependency/licence/manifest/brand/banned-API guards,
direct schema/input equality and all three published-APK checks pass. Receipts
are under `build/review-m4-p6-parent-f1/` and
`build/review-m4-p6-parent-final/`; original author receipts/report are retained
separately. Doc validators report 0/14 and 0/78. No device/native/runtime
evidence is produced by these host gates: exact-SHA
labelled API26/36 CI, the nightly API26/33/34/36 legs including the real
>=1800 s screen-off case, API37/16 KiB hardening, optimized release smoke and
the physical headset/call/focus/silence/artwork/Auto/TalkBack checks remain
parent gates with the rest of M4's acceptance.

The first hosted P6 run, CI40190590760 at `69ac9f6d`, failed the unchanged OPML
dense-input ratio guard and five framework cases. Original logs/XML are retained.
Follow-up reproduced the artwork worker's construction failure through the real
app graph: its internal binding container was not discovered across modules.
The contribution is now public, and the new creation regression passes while
database opening remains pending. The framework suite now uses stock full AOSP
images for SystemUI and Home; its cache key includes the device convention.
Notification clicks reacquire stale nodes within the original overall budget.
Phase, hierarchy, UID/service and checked shell-exit diagnostics retain the
remaining noisy-event and background-start questions for the next hosted run.
The expanded local aggregate has 2,196 records, zero failures/errors and three
existing environment skips (2,082 executed, 114 reused). The OPML guard also
passed focused ordinary and one-CPU probes with its original inputs, 128 MiB
heap, ratio threshold and timeout; only diagnostic output was added. Current-head
CI and native/release acceptance are still required.

CI40210591443 at `c0f5b826` passed five jobs and both images' notification controls;
the artwork worker now succeeds on-device. Its remaining failures exposed a
protected-broadcast rejection of shell UID 2000, a stopped activity needing an
explicit foreground launch, and an unproven background-denial premise. The next
fixture uses a system-authorized sender, target-owned diagnostic cache and a
cold background service binding; current-head native proof is still required.

P2's local verification had 4,674 test records with zero failures/errors and three
existing environment skips. In the final aggregate, seven test tasks executed
2,919 records; six reused 1,755 same-input focused results. The original report's
4,679 total was a prose arithmetic error. Independent source and XML review
found no important issue. The initial desktop run lacked libGL: its raw failure
log remains, but the failed XML was overwritten by the green rerun. P2's
current-head CI, labelled API26/API36 instrumentation and formal review are
closed by the #224 merge record above.

Main `96224e00` evidence update: the full and seeded device runs on that head
passed — the full run's first attempt failed before any test for an unavailable
runner accelerator, and the failed-job-only retry recovered
([#219](https://github.com/L-K-M/Neutrodyne/issues/219) closed). Receipts are in
`~/nd-wt/reviews/evidence/main-96224-{full,seeded}/EVIDENCE.md`. These are
actual-main device results, not scheduled/tagged, native, physical, provider or
`bmgr` acceptance.

**2026-10-21 M3 transport follow-up ([#223](https://github.com/L-K-M/Neutrodyne/pull/223)).**
The positive API31/API36 candidate now uses a separate self-instrumenting driver
APK and host-side evidence checks; no test classes enter the published app's
process. The original debug logical-restore gate is restored verbatim. Five
harness defects found in independent review have production-grounded regression
tests and a completed fix review; local native runs are not claimed. Current
source builds, exact-SHA hosted transport evidence, CI and formal review remain
parent gates. The published app's code, R8, signing and backup rules are intact.

The first black-box run on `eb79f8f9` failed both API31/36 legs before seeding:
the host expanded an unset phase variable. Its ordinary CI also exposed a driver
self-test decoding ZIP bytes as JSON on API26/36. Both are corrected locally;
the encrypted-input assertion now also accepts refusal while opening the ZIP,
as API31's platform source requires. Original failures remain retained. The
47-case host suite passes; final-head CI and actual restore proof remain required.

The next run on `22266282` reached the restore surface but its fixture had
noncanonical feed keys and invalid sync UUIDs. Those identities and a duplicated
instrumentation-status prefix are corrected. Unarmed driver tests now check the
encoded library and status value; actual final-head device proof remains open.

The retained API31/36 hierarchies show that checkbox/radio state belongs to the
checkable parent, not its text child. The driver now targets that parent; the
next real run must verify the complete journey without relaxing its checks.

### M3 bmgr driver tooling (2026-10-24)

PR #229 extracts `:tools:bmgrdriver` byte-identically from the #223 black-box
source `f89643fc`, based on main `f1c6e9ff`. This keeps #223 within
its 225k hard cap while leaving room for real-image fixes and regressions. It is a
self-instrumenting device-test APK in its own package/process that depends only
on `:feeds`, has no `:app` dependency and changes no production manifest, rules, R8,
signing, ID or schema input. Unarmed, its runner excludes the armed class before
discovery, so the labelled `ci`/`nightly` GMD groups run only `DriverSelfTest`'s
three codec/identity/wire cases on API 26/36. Armed with the host-pinned
`bmgrPhase` + exact `class#method` triple on API 31/36, its seed/flip/encrypted
phases drive the published release `ExternalImportActivity` over UiAutomator.
Run8's armed UI phases passed on API 31/36, but overall run 40128601596 FAILED
(API 31 host `pull_tree` stdin capture bug; an API 36 initialization-result
discrepancy explained by an AOSP `bmgr` observer-result race)
with retained evidence, not a pass and not
current-head native proof. The host transport, uninstall/reinstall and final
quiescent validation (`scripts/ci/bmgr-check.sh`, the checker, the nightly
`bmgr` job, the positive-lifecycle docs) stay in #223 and are NOT accepted here.
M3 acceptance 6's `bmgr` leg and P4's review state are unchanged.

Driver review R1 found that the original encrypted-flag blob also had malformed
ZIP bookkeeping, so its rejection did not isolate encryption handling. The
negative now starts from the real codec's valid backup and changes only entry
encryption flags. The unarmed pipeline test clears those flags, decodes the
manifest and streams every entry with CRC checks. The UI category helper clicks
once and waits for the requested checked state rather than re-toggling on a late
accessibility update. These test-only corrections change the initial extraction;
fresh CI/review and #223's actual armed journeys remain required.

As of 2026-10-25, #229 is merged as `8cb9a602` after two completed reviews and all six
current CI jobs, including the stronger paired-control test on API26/36. #223
normally merges this reviewed component. Run8's two host corrections are now in
this head: `pull_tree` reads the (now nonhidden `<dir>-listing.txt`) listing on
fd3 so `adb` children can no longer drain the cursor on fd0 — the run8 API 31
defect that pulled only the first canary — and `bmgr_init` retries only the
observed rc0/`Initialization result: -1000` shape (the AOSP `InitObserver`
publish-before-store race, `.nd-context/m3-bmgr-api36-init-rca.md`) at most 3
bounded attempts with every attempt receipt kept; anything else still fails
closed. The old encrypted UI refusal
does not isolate encryption handling; complete new-head positive31/36 lifecycle
proof remains open and required — this correction is not native acceptance.
No failed original capture is repaired or relabelled.

The next exact-head run on `5f826e24` reached TAR verification, install-time
delivery, cold launch and adopted-session completion on both API31/36. Final
validation stopped at a verifier path assumption: WorkManager's ledger is in
`no_backup/androidx.work.workdb`, not the app database directory. Both captures
contain it and copied diagnostic queries show the adopted tagged WorkSpec is
`SUCCEEDED`. The checker and fixture now use the pinned runtime's real path,
with a nonterminal-row negative test. Empty-dataset control never ran in that
failed attempt; a complete fresh lifecycle remains required.

That next run exposed an internal-only canary inventory, omitting the planted
external canary. Both listing call sites now capture both domains and reject
command failures. Copied downstream diagnostics also caught a false oracle:
the fixture requested a played item in Up next, which the documented restore
rule correctly skips. The checker now requires the exact unplayed order and
rejects both played entries and missing unplayed entries. Original failed
captures stay unchanged; a complete new lifecycle/empty control is still open.

The following run reached the end of positive validation on both images but
could not start the empty control: its earlier pull check incorrectly required
that future empty database. Positive and empty checks are now phase-specific;
both are mandatory in the final aggregate. Regression coverage preserves
rejection of missing or populated empty-control evidence. A fresh full run
remains required.

Both images then completed the positive and empty phases, but the final
aggregate rejected driver metadata because its Kotlin serializer omits the
declared `v = 1` default. The decoder now honors that wire contract while
rejecting explicit unknown/malformed versions and retaining identity/content
bindings. Original captures remain failed; complete copied-capture replay
and a fresh exact-head native aggregate must verify the correction.

**Current M3 transport acceptance (2026-10-25):** #223 is merged as `1501048a`.
The corrected `ab2347c1` head passed all six CI jobs, named API26/36 app/driver
cases, and the full API31/36 local-transport workflow with strict aggregate,
empty controls and cleanup. Two formal rounds resolved the parser admission
blocker; R1 minor diagnostics and R2's test-message precision nit are deferred.
Earlier failed attempts above stay failed. This closes the PO-50 stock-emulator gate,
not physical/device-to-device, scheduled/tagged release or other open acceptance.

## 1. What this repository is

Neutrodyne is an open-source podcast player in development. The sources of truth are:

- [CLAUDE.md](CLAUDE.md): the owner's standing conventions. Read this first; they are binding.
- [docs/PLAN.md](docs/PLAN.md): the master plan, holding requirements (R/N-ids), decisions (D-ids), owner decisions (PO-ids), the roadmap (M-ids), risks and the glossary.
- [docs/design/](docs/design/): the design docs `01-foundation` … `09-quality-and-release`, plus `10-sync` and `11-desktop` once the replan has written them.
- [docs/research/](docs/research/): raw research notes and the change briefs used for each revision. These are non-normative.
- [tools/doccheck/](tools/doccheck/): link/anchor and Mermaid checkers. Both must report 0 problems before any push.

## 2. Owner decisions so far (chronological)

| When | Decision |
|---|---|
| 2026-10-04 | Initial ask: Android podcast player with subscription import/export, user-defined groups each viewable as its own episode feed, YouTube channels as podcasts, streaming and downloading, a nice cover-art UI. Plan pushed to `main`. |
| 2026-10-05 | **PO-1** Avoid GPL: use **yt-dlp** (Unlicense) embedded via an embedded CPython instead of NewPipe Extractor. |
| 2026-10-05 | **PO-2** Distribution: **GitHub Releases only** (no Play, F-Droid or other stores). |
| 2026-10-05 | **PO-5** **No Google developer verification** registration. |
| 2026-10-05 | **PO-8** Package/ID prefix is always **`ch.lkmc`** (`ch.lkmc.neutrodyne`). |
| 2026-10-05 | **PO-31** Update checks are **notify-only**, linking to GitHub; the user downloads and installs manually. |
| 2026-10-05 | **PO-32** YouTube engine updates are automatic, limited to versions approved by our canary. |
| 2026-10-05 | **PO-33** No beta channel. **PO-34** No mirror. |
| 2026-10-05 | **PO-35** First answered "just build debug builds", then clarified as **"no key management"**: publish optimised release builds signed with the keystore committed to the repo. |
| 2026-10-05 | **Scope expansion:** add a **self-hosted sync server** (subscriptions, listening state and similar; never audio) and **desktop targets**. |
| 2026-10-05 | **LGPL allowed.** **Everything ships in v1.0** (Android + desktop + sync server). Desktop on **Windows, macOS and Linux, unsigned**. |
| 2026-10-05 | **Stack: Kotlin Multiplatform + Compose Multiplatform** (not Flutter), with a Kotlin/Ktor server. **Desktop bundles the Java runtime** (OpenJDK, narrow licence exception, its source attached to each release). |
| 2026-10-05 | The owner added the app icon `media-sources/icon.png` (vacuum-tube "N", amber on navy) and IntelliJ project files (`.idea/`). |
| 2026-10-06 | The owner enabled **immutable releases** in the repository settings (needed for 09's `publish` job and `gh release verify`). |
| 2026-10-10 | **GUID identity history:** retain persistent feed-scoped knowledge so erased duplicate-GUID history cannot silently redirect listening state. Conservative legacy UNKNOWN behavior and actual v1→v2 migration/restart/preservation proof are required; replacement implementation is in progress. |
| 2026-10-10 | **Navigation transitions:** switching tabs is immediate; back wipes to reveal the preceding screen. The owner reported general device success but rejected the default fades; corrected-build acceptance remains pending. |

## 3. Current state

- **`main`** holds the complete plan for **Android + desktop (Windows, macOS, Linux) + self-hosted sync server, all in v1.0**, built on Kotlin Multiplatform:
  - `docs/PLAN.md`: requirements R1–R8 and N1–N13, decisions D1–D97, owner decisions PO-1–PO-48, and milestones M0a…M11b, MD0–MD5 and MS0–MS3;
  - eleven design docs, including the new `10-sync.md` and `11-desktop.md`;
  - the README.

  Link and anchor checks report 0 problems, and all 78 Mermaid diagrams parse.
- The replan ([PR #4](https://github.com/L-K-M/Neutrodyne/pull/4)) and planning-review follow-ups are merged.
- `main` now includes the KMP scaffold, Android shell, library spikes, sync-server skeleton and desktop shell. §0 tracks unmerged implementation and outstanding release checks.

### Progress log

- 2026-10-04: first Android-only plan (PR #1).
- 2026-10-05: owner decisions PO-1/2/5/8: yt-dlp, GitHub only, no verification, `ch.lkmc` (PR #2).
- 2026-10-05: owner decisions PO-31–35: notify-only updates, no beta, no mirror, debug builds (PR #3).
- 2026-10-05 16:2x–22:3x UTC: KMP + desktop + sync replan (PR #4). Steps: the lead pass (brief saved as [`docs/research/briefs/change-brief-3-kmp.md`](docs/research/briefs/change-brief-3-kmp.md)); new docs `10-sync.md` and `11-desktop.md`, each written and then reviewed; revision of `01`–`09`; two critics (42 findings); a fixer (40 fixed, 1 handed to the orchestrator for `CLAUDE.md`, 1 rejected because it targeted the scratch brief). `CLAUDE.md` is updated to match.

### Planning review — 2026-10-06

A contributor (Sol, fork `BigBoyDevBox/Neutrodyne`) opened four documentation PRs that fix review findings. The owner asked us to review them and merge as appropriate.

| PR | Topic |
|---|---|
| [#5](https://github.com/L-K-M/Neutrodyne/pull/5) | Backup timestamps and history, OPML round trips, group undo, credential-commit coordination |
| [#6](https://github.com/L-K-M/Neutrodyne/pull/6) | Safe RSS resume, download redirects, small-library YouTube outages, resolve deadlines |
| [#7](https://github.com/L-K-M/Neutrodyne/pull/7) | Archive launcher paths and the scoped PBS licence-check contract (PO-48 stays pending) |
| [#8](https://github.com/L-K-M/Neutrodyne/pull/8) | Exact sync replay, raw state, resets, rekey collisions, crash-recoverable effects |

- **Review:** each PR had its own adversarial reviewer, and a sixth agent checked how they interact once combined. The verdict for every PR was "merge with follow-ups": no blockers or majors.
- **Merged:** all four on 2026-10-06, in order 5, 6, 7, 8.
- **Follow-ups:** the reviews left 46 follow-up items: about 35 minor fixes and a few notes on changes outside each PR's stated scope. All 46 were handled in [PR #9](https://github.com/L-K-M/Neutrodyne/pull/9), on `main` since 2026-10-06; a verifier pass confirmed them and both doc checks pass. Notable additions:
  - **Control-feed check:** before a 1–2-channel library declares a YouTube outage, the app fetches two pinned control channel feeds (R3.3 amended).
  - **Credential coordinator:** extended to sync password installs and every credential delete; arrives in M1b.
  - **Staging-only `EpisodeLineV1.fc` field:** per-field clocks for first-link staging.
  - **`SyncOutboxDao.captureIntent`:** captures mark-unplayed and position resets.
  - **`LinuxDesktopEntryWriter`:** writes both the menu and autostart entries with spec-correct `Exec` quoting.
  - **PBS patches and WiX:** both stay PO-48 proposed defaults awaiting the owner.

### Open owner questions (ask these next; each has a default in `docs/PLAN.md` §4)

Most important first:
- **PO-48 Licence classes:** the final review found two cases beyond the agreed rules.
  - **WiX code (MS-RL)** is embedded in every Windows MSI. If declined, Windows ships as a ZIP only.
  - **python-build-standalone carries MPL-2.0 patches** in the desktop Python. If declined, CPython must be built without them.
- **PO-46** Desktop recovery snapshot. Default: none in v1.0; the user restores a manual backup or reconnects sync.
- **PO-47** Sync timing promises and the mass-change threshold for small libraries.
- **PO-37 / PO-38** Sync scope (what syncs; feed passwords) and server accounts. Default: admin-created accounts, devices linked by code.
- **PO-39** macOS tester builds before 1.0.0. **PO-40** Windows on Arm (default: x64 under emulation). **PO-42** Ship the JDK AOT cache for faster desktop start.
- **PO-43** Reference hardware. **PO-44** Desktop secret storage. **PO-45** Server operations defaults. **PO-41** gpodder/Open Podcast API layers (default: v1.1).
- **Older questions still on their defaults:** PO-3, 4, 6, 7, 9–21, 24–28, 30, 36.

## 4. How to resume if this session stops

1. **Refresh:** fetch, inspect worktree status and read §0 plus `docs/IMPLEMENTATION-PROMPT.md`. Preserve active helper changes.
2. **Reconcile:** inspect open PRs, latest-commit CI and completed review comments. Resume the serial GLM queue; do not duplicate running helper tasks.
3. **Implement:** follow `docs/IMPLEMENTATION-PROMPT.md`; use PLAN §7 for dependencies and acceptance criteria. Resolve important review findings test-first and record design deviations.
4. **Run the checks:** `python3 tools/doccheck/checkdocs.py .` and the Mermaid checker. Both must report 0 problems.
5. **Merge and release:** commit and push focused branches, open PRs, finish CI/review and merge through GitHub. Publish milestone tester builds only after their release gates pass.
6. **Hand back:** update this file with exact heads, completed checks, remaining gaps and owner instructions. Report unmerged work as work in progress.

## 5. Binding decisions for the replan (S0–S13)

These are the instructions the replan agents received. They hold for anyone continuing the work.

- **S0** v1.0 = Android + desktop (Windows, macOS, Linux) + self-hosted sync server, all together. The server syncs state only, never audio or feed contents; each client polls feeds itself.
- **S1** Kotlin Multiplatform + Compose Multiplatform; targets `android` and `jvm("desktop")`; no iOS (would need Apple registration) but keep `commonMain` clean. Sync server in Kotlin + Ktor, sharing protocol and merge code.
- **S2** Android stays native where it matters, with designs unchanged: Media3 MediaLibraryService, Android Auto, WorkManager + user-initiated jobs, Android 15–17 rules, Chaquopy-hosted yt-dlp in `:ytx`, Auto Backup.
- **S3** Libraries: Compose Multiplatform, Material 3 multiplatform, Navigation 3 (Google runtime + JetBrains UI), lifecycle/ViewModel KMP, Room 3 KMP with `BundledSQLiteDriver`, Paging, DataStore, Coil 3, kotlinx-serialization/datetime, Ktor client on OkHttp (or OkHttp in JVM-shared modules), DI Hilt → Metro (Koin fallback). AGP 9 `com.android.kotlin.multiplatform.library`; `:app` (Android) and `:desktopApp`; JVM-only code in plain-JVM modules.
- **S4** Desktop (unsigned, GitHub only):
  - **Platforms:** Windows 10+ x64/arm64, macOS 13+ Apple Silicon only (ad-hoc signed), Linux x64/arm64 (glibc ≥ 2.31).
  - **Formats:** MSI + ZIP, DMG, DEB + RPM + tar.gz.
  - **Build:** one CI job per OS/arch. jpackage rejects 0.x versions on macOS.
  - **Updates:** notify-only checks.
- **S5** **Bundle** a jlink-trimmed, unmodified OpenJDK. It is a narrow licence exception: attach the runtime's corresponding source to every release. No ProGuard (GPL).
- **S6** LGPL allowed. Desktop playback is our own engine:
  - minimal LGPL FFmpeg build;
  - miniaudio output;
  - a Kotlin Sonic port plus silence skipping;
  - an OkHttp streaming cache.

  libmpv is the fallback. OS integration uses SMTC (Windows), Now Playing (macOS) and MPRIS (Linux). Audio-only on desktop in v1.0.
- **S7** Desktop YouTube engine: python-build-standalone CPython (trimmed to permissive parts) running yt-dlp as a child process over JSON/stdio, with the same trust chain and update policy as Android.
- **S8** Desktop behaviour:
  - an in-process scheduler and a single-instance lock;
  - closing the window keeps the app running (tray) only while playing or downloading;
  - start at login off;
  - downloads in the app data directory;
  - the Linux screen-reader gap is accepted.
- **S9** Sync uses our own protocol v1:
  - **Merging:** per-field last-writer-wins ordered by hybrid logical clocks; changes pulled since a server cursor; tombstones.
  - **Identity and order:** stable podcast UUIDs; per-item order keys for Up next and groups.
  - **Safety rules:** a position of 0 never wins without an explicit reset or mark-played; a mass unsubscribe asks first.
  - **First link** uses the backup Merge rules.
  - **Server:** Ktor with SQLite, SSE push, admin-created accounts, devices linked with short codes, no end-to-end encryption in v1. Request Android 17's `ACCESS_LOCAL_NETWORK` only for a LAN server.
  - **Data:** synced: subscriptions, groups and their order and settings, per-episode state, Up next, podcast settings, portable globals. Basic-auth passwords sync only when the user opts in. Device-local: auto-download, storage, notifications, refresh, appearance.
- **S10** Server distribution: a JAR (admin installs Java 21) plus a multi-arch GHCR image (counts as GitHub) and a reference docker-compose. Same release tags as the apps.
- **S11** Android publishes optimised release builds (R8, non-debuggable) signed with the committed keystore. The debug build type is for development only. Retire the debuggable-build risks; the public-key spoofing risk remains.
- **S12** Brand: `media-sources/icon.png`, seed colours ≈ amber `#FF8C1A` and navy `#0B1B2E`. Adaptive and monochrome Android icons and desktop ICO/ICNS/PNG are derived from it.
- **S13** Licensing: own code Unlicense; no GPL/AGPL except the S5 runtime exception; LGPL allowed (dynamically linked, notices and source offered); MPL-2.0 for unmodified files and data. Every component is listed with its licence, and CI licence checks cover Gradle, Python and native desktop libraries.

## 6. After the replan

- **Handing implementation to another agent:** use the ready-made prompt in [`docs/IMPLEMENTATION-PROMPT.md`](docs/IMPLEMENTATION-PROMPT.md).

- **Owner questions:** collect the open PO questions from `docs/PLAN.md` §4 and ask the owner. Each already has a default.
- **Implementation:** start with **M0** (scaffold and CI) per `docs/PLAN.md` §7 and `docs/design/01-foundation.md` "M0 scaffold checklist". Build Kotlin-Multiplatform-structured from the first commit.
- **Effort:** about 58–97 engineer-weeks for everything in v1.0, roughly 13–22 months solo. This is a planning estimate from `docs/research/2026-10-05-stack-choice/kmp-fit.md`.
