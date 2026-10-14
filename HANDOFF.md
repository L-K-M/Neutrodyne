# Handoff — state of the Neutrodyne work

This file lets another person or agent take over the work at any point. **Last updated: 2026-10-12.** M0a is merged; M0b and M1a are in progress (see §0). Follow [`docs/IMPLEMENTATION-PROMPT.md`](docs/IMPLEMENTATION-PROMPT.md) through the v1.0 release.

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
| M1a UI | U1–U3 and Feeds merged; Podcast in review; Episode draft | [#91](https://github.com/L-K-M/Neutrodyne/pull/91) U1 `3f54537` (replaces #53, closed) → [#93](https://github.com/L-K-M/Neutrodyne/pull/93) U2 `f101552` (replaces #54, closed) → [#94](https://github.com/L-K-M/Neutrodyne/pull/94) U3 `f8540f2` (replaces #57, closed) → [#97](https://github.com/L-K-M/Neutrodyne/pull/97) Feeds `44e96e2` → [#96](https://github.com/L-K-M/Neutrodyne/pull/96) Podcast → [#95](https://github.com/L-K-M/Neutrodyne/pull/95) Episode and Add-podcast (all three now target main) | #53/#54/#57's deltas and the remaining screens of the verified `wip/m1a-ui` package (`753bee3`) are replayed onto the reviewed stack. The package was never GLM-reviewed, so every slice had an independent pre-review with verified fixes: a Library crash (keys pushed before their screens exist; placeholder entries with graph tests now guard every pushed key), repeated artwork pins wiping stored metadata, locale-frozen collation and dates, inert snackbar actions, and the feed password kept in the saved-state bundle. Merge order #97 → #96 → #95; later slices replace earlier placeholders. #91 merged after two GLM rounds: r1's one confirmed important finding (desktop `file:` artwork URIs were not percent-encoded) and eleven minors were fixed; its Major and Critical claims on cache trimming, cache keys and a missing import were refuted with Coil bytecode and probes, and r2 only re-raised one of them. #93 merged after two rounds: three confirmed important findings fixed test-first (the hover overflow covered the play button, download menu labels contradicted the state, the declared image aspect overrode the loaded one); its two Criticals (a literal `%` in Compose resources) were refuted, and a queued row offers only Cancel until M6 adds `promote`. #94 merged after three rounds: no confirmed important GLM finding (accessibility of the sort menu, a named mark-all dialog and test fixes applied), but its new metrox edge had put lifecycle 2.10.0 on the desktop compile classpath against 2.11.0 at run time (`NoSuchMethodError`); the desktop application convention now uses `consistentResolution { useRuntimeClasspathVersions() }`, with a linkage test. #97 merged after two rounds with no confirmed important finding. Its round-1 change to show append errors only in the footer row was wrong: with placeholders the footer sits after every unloaded row, so a mid-list append failure became invisible; #96 restores the pinned banner for append on Feeds and removes the duplicate footer on both screens, and 08's two slice notes now agree. |
| M1a corpus device leg | merged | [#98](https://github.com/L-K-M/Neutrodyne/pull/98) `8b478e5` (replaces #60, closed) | All 70 goldens pass through the AOSP parser on the API 26 and 36 managed devices (`AndroidParserGoldenInstrumentedTest`, per-device XML on CI); leg (b) runs on the host since #83. |
| M1a GUID provenance | merged | [#88](https://github.com/L-K-M/Neutrodyne/pull/88) `f56a2b9` (replaces #70 and the held #64) | D98/PO-49: feed-scoped GUID history and additive V1→V2 with the frozen V1 schema and fixture unchanged. Two Codex rounds before GLM; GLM r1's ten "critical" items were `execSQL` false positives. The V1→V2 device run passed on the managed devices. |
| Navigation transitions | merged | [#73](https://github.com/L-K-M/Neutrodyne/pull/73) `ff6ad06` (code `08570c3`) | Instant tabs, cover on push, wipe on back and predictive back are as the owner asked; expanded two-pane in-pane swaps (Settings About → Licences → Back) now animate too. Three earlier in-pane designs animated inside one scene and each failed independent review (state loss, duplicate-provider crashes, over-pops, cross-tab mutation). A two-member committee (Sol, SWE-2) traced the root cause, and both candidate designs were built against a 26-test public-behavior gate suite (`PaneSwapGateTest`): the in-pane swap as a real Navigation3 scene change passed 26/26, the alternative pane animator stalled at 19/26. Later Codex closure rounds fixed an early predictive over-pop, completion-only Back over-pops, and cross-tab gesture completion; `0657504` closes with no important finding (86 host, 40 desktop, all gates), and the first GLM round was minor-only (its blocker was refuted against the `NavigationEvent` bytecode). Also fixed [#75](https://github.com/L-K-M/Neutrodyne/issues/75) (two-pane Back popped nothing in Feeds, over-popped in Library). Documented limitations: an expanded detail swap stops an in-progress list fling and ends an IME composition (focus restored); the post-decoration pane animator (`proto/nav73-e`) is the follow-up. Physical predictive gestures unverified. |

**Toolchain used locally:** Temurin 21 and 25 (Gradle toolchains via `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`), Android SDK with build tools 36/37 and platform 37, host CPython 3.14 for Chaquopy's `buildPython` (`neutrodyne.buildPython` in `~/.gradle/gradle.properties`; builds need `LANG=C.UTF-8`). No KVM on the development machine: instrumented tests and Gradle Managed Devices run only on CI. Compose UI tests on the desktop JVM need a GL library; locally a user-level Mesa is unpacked in `~/.local/gfx` (`source ~/.local/gfx/env.sh`).

**Owner execution instructions, retain through compaction:**

- Follow `docs/IMPLEMENTATION-PROMPT.md` milestone by milestone through published v1.0.
- Use available helpers, especially free models, Devin SWE-2 and direct Z.ai GLM-5.3 through pi or opencode. Check helper results before integration.
- Periodically have Codex Sol 6.1 at maximum reasoning review merged `main`.
- Keep PRs small and GLM reviews serial to protect the Z.ai quota. Avoid pushes that cancel an active review. The owner allows waiving GLM after repeated integration failures; record the gap and waiver, never claim approval.

**Release gate:** main `33b6cb5` adds the feed parser and corpus (#77, #78), data contracts and the fetcher (#79), ingest (#80), the sniff and challenge fixes (#89), the refresh engine (#81), add/subscribe (#82), the refresh runners (#83), the engine fixes (#84), the paged `FeedRepository` (#87), the start-up recovery fix (#99), GUID provenance with V1→V2 (#88), the show-notes format fix (#92), a runner test fix (#100), the device corpus leg (#98) and two test-race fixes (#101) to the feeds, schema, Room, startup and transitions work. Regular main CI passes at `33b6cb5` ([38447262960](https://github.com/L-K-M/Neutrodyne/actions/runs/38447262960)). The newest scheduled nightly, [38227948941](https://github.com/L-K-M/Neutrodyne/actions/runs/38227948941), passed on `72fe62f`, an ancestor; a scheduled green on the current head is still required before `v0.1.0`. Branch, GMD or older-head success never waives the schedule or owner gates, and `repro` stays report-only. The owner's device report asked for corrected transitions; #73 (`ff6ad06`) delivers them and needs device acceptance. Checklist [#20](https://github.com/L-K-M/Neutrodyne/issues/20), transition acceptance on a device, tagged-main and the remaining manual checks precede publication. Main now exports V2.

**Review queue:** one GLM review at a time. Merged since the last update: #82 (three rounds), #83 (two), #84 (one), #87 (one), #99 (two), #88 (two), #92 (one; both "Major" claims refuted on the PR), #100 (one; a `DesktopJobRunnerTest` race under load, the "blocker" refuted), #98 (two; stale leg (c) status fixed), #101 (one; two load-sensitive test failures: the Android start-up recovery bound and a `SubscribeFlowTest` announcement race Codex reproduced), #90 (two), #91 (two), #93 (two), #94 (three), #97 (two). #96 (Podcast) is in review. Next: #95. Main's head is `44e96e2`; #96 and #95 target main, and each merges main again before its review. Keep #34 draft until Android publishes. Prune swallowing, IOERR escalation, redundant GUID index and exec claims stay rejected. Avoid starting GLM reviews right after 00:00 and 01:00 UTC: the Paseo stack heartbeats use the same Z.ai plan (HTTP 429 seen on #83); rerun a 429-only failure after ~20 min. Push the UI branches only when one enters review: pushing all eight at once saturated the Actions runners for over two hours. GMD emulator boots fail occasionally (seen on the earlier main commit `ea86095`, `api26Setup`); rerun them.

**Data-hang cause and proof:** the 73-minute U1 paging hang produced 72,445,492,508 bytes of test events, then a daemon OOM. Oversized binaries were removed; source, daemon log and retry XMLs remain. Capped probes reproduce inherited `TimeoutCancellationException` retrying, about 695 MB counted stderr/eight seconds and a fresh writer pending after six seconds with Free/permits-0 state. Protected normal release refutes interrupted-release claims; test-clock divergence accelerates a real hazard. All probe groups are dead, 168 KB evidence retained. Caps remain 120 s plus 15 s kill, 64 MiB/file, 512 MiB total, fresh no-daemon, ≤2 workers. Stable 3.0.3 has no fail-fast switch; alpha retains the flaw. #56's reproducible Apache-2.0 repair is merged in `98dd9a7`, compiler/plugin/paging/testing remain upstream 3.0.3. Remove vendoring once upstream ships equivalent behavior. Older synthetic data/UI bases still need refresh; never replay full unpatched suites or treat green retries as resolution.

**Remaining UI sequence:** feature installers activate routes immediately. #57's real Metro/Coil and sourced-Mesa window paths pass; minimum public scheduler export is now proven with internal constructor/rebaser. Fresh composed CI/review remains. `FeedRepositoryImpl` precedes feeds/podcast; preserve splash/startup gates and the 30-source desktop shell. Feeds/data, podcast/settings, discover/episode and the small documented remainder follow.

**Periodic main review (2026-10-08, Codex Sol 6.1 Max, `9805dbf`):** no Android-only blocker found in the reviewed range. All three important desktop fixes merged: runtime host/archive handling [#36](https://github.com/L-K-M/Neutrodyne/pull/36) (`8cccf7c`), bounded/cancellable handshake reads [#35](https://github.com/L-K-M/Neutrodyne/pull/35) (`221feb1`), and returning smoke-window/first-frame validation [#39](https://github.com/L-K-M/Neutrodyne/pull/39) (`9a6e78e`). GLM's alleged `assertThrows` compile blocker was refuted by the shared test helper and forced convention tests. The Windows jpackage suffix claim was Linux-only. Malformed server CIDRs and the stale licence-repair note in design 01 remain deferred.

**Windows test portability:** [#46](https://github.com/L-K-M/Neutrodyne/pull/46) merged as `9a81df3`. CI and the native Windows unit step above pass with identical test sources. One GLM round found no applicable defect: the resolved runtime uses `kotlin-test-junit` and JUnit 4. Temp-directory cleanup and unrelated fixture consolidation are deferred.

**Periodic main reviews (Codex Sol 6.1 Max):** 2026-10-09 on `ce2ec9a`: no important finding across `6ed5d75..ce2ec9a` (#59, #56, #55). 2026-10-10 on `a7200f5`: no important finding (schema #38). 2026-10-11 on `52b2fd6`: one important finding (an overflowing error code stranded the open gate), fixed by #74; the healthy-launch quarantine-expiry minor is deferred as an owner trade-off. 2026-10-12 on `97a57cf` (#22, #74, #41): no important finding; merge trees match the reviewed heads, startup preserves #74's failure publication on both shells, module-graph and dependency policies pass; the IDNA numeric-host minor was fixed by #76. 2026-10-12 on `d89d290` (#73, #76, #72, #77–#79): no important finding; #89 fixed the prefixed-Atom sniff and the Basic-challenge stack overflow, and this update fixes the stale handoff. 2026-10-12 on `c77d211` (#80, #89): no important finding; merge trees match the reviewed heads. Its two code minors (the show-notes cache ignores a changed link base; Atom plain-text descriptions render as HTML) are being fixed on `fix/show-notes-format-base`; this update fixes its third, the README's "no code yet". 2026-10-12 on `327a4e4` (#81, #82): three important findings (a discarded final-flush failure, an unguarded failure write in the fetch-exception guard, decimal-IPv4 host spellings bypassing the per-host lane) and two minors; all fixed by #84. 2026-10-13 on `223aa5e` (#83, #84): all earlier findings fixed; one important finding (refresh initialization never resumed after a database-recovery retry), fixed by #99; the stale corpus note is fixed on #98. 2026-10-13 on `f56a2b9` (#87, #99, #88): no important finding; merge trees match the reviewed heads, the startup-retry fix holds on both shells, and the V1→V2 migration matches frozen `2.json`. Of its four doc minors, #98 fixes the corpus note; #90 fixes the README status, 03's D98 upgrade sentence and 02's builder example. 2026-10-13 on `df14937` (#92, #100, #98, #101, #90): no important or new minor finding; every earlier finding fixed, merge trees reconstruct exactly, release APKs carry no corpus assets, and exact-main CI passes with all 70 device goldens and both V1→V2 driver paths on API 26 and 36.

**Resume:** fetch first; cut new task branches from `origin/main`, or merge it into existing work before edits. Check PR heads, CI and review comments before acting. Worktrees are under `~/nd-wt/`, prompts and reports under `~/nd-wt/prompts/` and `~/nd-wt/reviews/`. Use `export JAVA_HOME=$HOME/.local/jdks/jdk21 ANDROID_HOME=$HOME/android-sdk LANG=C.UTF-8`; source `~/.local/gfx/env.sh` for Compose JVM tests. Watch free disk space; prune only verified merged worktrees and regenerable outputs, never active helper work.

**Coordination:** the parent agent owns serial CI, reviews and this handoff; child agents run no monitors or heartbeats. Idle and reusable: `f0c145ae` (Devin, built the PR #73 gate base `proto/nav73-base`), committee members `eee69e7d` (Sol, author of the accepted #73 design on `proto/nav73-b`) and `8e9d8f13` (SWE-2). Keep `proto/nav73-e`, its WIP pane animator, intact until the #73 continuity follow-up lands. GUID `ff56521` stays frozen in `guid-provenance`. Open-PR worktrees under `~/nd-wt/`: `ui-1-refresh` (#91), `ui-2-refresh` (#93), `ui-3-base`, `ui-3-refresh` (#94), `ui-4-base`, `ui-4-feeds` (#97), `ui-5-podcast` (#96), `ui-6-episode` (#95). Long helper-CLI jobs run detached through `~/nd-wt/bin/helper-job.sh` (start/wait), so workflow relays can poll past their 10-minute call limit. Review reports and probes live under `~/nd-wt/reviews/` and each review worktree's `build/review-*/`; prompts under `~/nd-wt/prompts/`. Legacy heartbeat `b6598800` expired 2026-10-10 15:32 UTC; delete it on takeover if it still exists and never extend it silently. Preserve the `~/nd-wt/review-*` worktrees' `build/review-*/` evidence: copy it to `~/nd-wt/reviews/evidence/` before removing a worktree. On 2026-10-13 the `review-main-52b2fd6`, `review-feeds2`, `review-nav-static` and `review-guid88` worktrees were removed before this rule existed: their `build/review-*/` evidence was deleted, not copied, and only their reports remain in `~/nd-wt/reviews/`. `main-review-3` and `rare-catfish` no longer exist; older proofs are listed in this file's git history.

**Deferred follow-ups:** integrate the verified query-component UTF-8 repair (#86, from #63) after its predecessors; make process-start probe reports atomic; guard `SettingStore.observe` against a key from the wrong file; optionally clamp sanitizer image dimensions. From this round:
- Owner trade-offs: healthy-launch quarantine expiry for recovery copies (2026-10-11 review); a retry policy for transient prune failures (#74 round 1).
- #41 minors: stale KDoc, logging discarded retry failures, an `ActivityNotFoundException` guard on the storage action (`StorageManager.ACTION_MANAGE_STORAGE` may be the better target), the duplicated fixture parser, a cascade assertion.
- #22 deferred minors (PR conversation).
- #73's pane animator follow-up for list fling/IME continuity.
- #80: split ingest preparation from the write transaction (the 2c caller); dateless items and the new-episode window (product question).
- #81 test nits: close the database in `NextRefreshRebaserTest`; add forced and blocked rows to the manual-only rebaser case (production already skips them).
- #83 deferred: chunk `downloadedEpisodeIds` at the bind limit; GLM r2 test nits (hard-coded retry boundary, the host fixture's ingestor dispatcher).
- #84, #87 deferred: test-hygiene nits (single-dispatcher assumption, closing local test databases); affected-row counts from the order setters when M2 needs them.
- UI pre-review deferrals: the tile-menu separator, the Library empty state's Import action, the three duplicate `*Filled` navigation icons (pre-existing on main), and the declined Podcast-screen nits.
- #94 deferred: the mark-all pending id when its tile vanishes; sort radios outside a selectable group; `NdDialogAction` parameter order; the disabled destructive tint; the red-pixel predicate also matching amber; the hand-kept pushed-key lists in the graph tests.
- #93 deferred: EmptyState's gap between stacked text buttons; a tile menu already open when selection starts; the zero-duration sentinel; `ShowNotesRendererTest`'s English click label.
- #91 deferred: `DefaultArtworkStoreTest` shares a fixed `build/tmp` directory (use a temp dir); declare the `:core:common` → `:core:model` edge as `api`.
- #90 deferred: 02's `encode` comment should say the header byte precedes both size paths.
- #98 deferred: a reverse (golden without fixture) pairing check, in corpus leg (a) rather than only on the device.
- #100 deferred: state `awaitIdle`'s started-run precondition in its KDoc.
- #92 deferred: exhaustive `when` in `DescriptionCodecTest`'s tag helpers; one shared JVM codec instead of identical Android/desktop copies.
- #88 deferred: #99's `refresh-periodic` literal in `AndroidStartupRecoveryTest`.
- #88 residual policy, owner-visible: a recycled `KNOWN_INDEPENDENT` GUID claims by authority; an episode returning without an enclosure to an unproven rotated slot duplicates.

These are not blanket release approvals or completed work.

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
