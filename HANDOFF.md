# Handoff — state of the Neutrodyne work

This file lets another person or agent take over the work at any point. **Last updated: 2026-10-10.** M0a is merged; M0b and M1a are in progress (see §0). Follow [`docs/IMPLEMENTATION-PROMPT.md`](docs/IMPLEMENTATION-PROMPT.md) through the v1.0 release.

## 0. Implementation status

| Milestone | State | Branch / PR | Notes |
|---|---|---|---|
| M0a.1 | scheduled gate green; owner/tagged release checks pending | [PR #10](https://github.com/L-K-M/Neutrodyne/pull/10), merge `e0c3722` | Scaffold, Android shell, policy gates and release pipeline. Seven Codex Sol rounds; GLM waived after repeated timeouts. Scheduled-main [38037939442](https://github.com/L-K-M/Neutrodyne/actions/runs/38037939442) on `bb0d13e` passes all four jobs, including API26/33/34/36 debug, API37 16 KB and release smoke; report-only repro also passes. Issue #58 closed. Next: checklist [#20](https://github.com/L-K-M/Neutrodyne/issues/20), owner-device acceptance and tagged-main checks before `v0.1.0`. |
| M0a.2 | merged | [PR #13](https://github.com/L-K-M/Neutrodyne/pull/13), merge `49bc849` | Library spikes and Android UI/resource tests passed on API 26 and 36. Below API 33 the language-switch test skips while only `en-US` ships as Android resources; recorded in `docs/design/01-foundation.md` (S11) and `docs/design/09-quality-and-release.md` (Shipped locales and per-app language). No separate tester build. |
| M0b | native repairs green; independently closed | [#19](https://github.com/L-K-M/Neutrodyne/pull/19), [#25](https://github.com/L-K-M/Neutrodyne/pull/25); draft [#34](https://github.com/L-K-M/Neutrodyne/pull/34) `dbf475a` | [Regular CI 37973990857](https://github.com/L-K-M/Neutrodyne/actions/runs/37973990857) and [full matrix 37973995959](https://github.com/L-K-M/Neutrodyne/actions/runs/37973995959) pass, eight jobs including four desktop targets. Codex reports no important finding: 12 fixtures/six real ELF cases, whole checker accepts five valid variants and rejects four loader mutants/eight malformed cases. Program headers/loaded bytes remain bound; section metadata gives no alternate authority. MSI requires the actual INSTALLDIR; 37 image/15 smoke fixtures pass. Hold #34 until Android publishes; physical/minimum-OS/upgrade/uninstall/DMG/S13 gates remain. |
| M1a database | schema/runtime/recovery merged; startup CI green, final review pending | merged [#37](https://github.com/L-K-M/Neutrodyne/pull/37) `6ed5d75`, [#38](https://github.com/L-K-M/Neutrodyne/pull/38) `616fefa`, [#40](https://github.com/L-K-M/Neutrodyne/pull/40) `52b2fd6` (code `72b8890`); draft [#41](https://github.com/L-K-M/Neutrodyne/pull/41) `8c25e94` | Recovery closes after three GLM rounds; independent 33 opener/three caller probes and full 96 DB/nine host pass. Startup passes eight graph/smoke cases/two actual graph/Room/UI probes: permission failure keeps routes/player absent, preserves SHA/state/scope and retries. `8c25e94` fixes the verified API37 preboot KMP-APK omission and adds artifact guards. [Latest CI 38056459671](https://github.com/L-K-M/Neutrodyne/actions/runs/38056459671) passes all six; downloaded XML confirms five native/two V1→V1 helper/three DB methods per API. Prior [38051811605](https://github.com/L-K-M/Neutrodyne/actions/runs/38051811605) timed out during API36 locale recreation: seven completed methods, 17 discovery-only logs, no final XML/core result. Exact stall mechanism remains unproven; the current pass does not explain it. [Branch nightly 38057314395](https://github.com/L-K-M/Neutrodyne/actions/runs/38057314395) exercises the preboot fix; final workflow/GLM closure remains. No physical/crash/Android-failure-retry/actual-upgrade proof claimed. |
| M1a feeds | refreshed; common final serial review pending | ready [#22](https://github.com/L-K-M/Neutrodyne/pull/22) `913ba53`; draft [#32](https://github.com/L-K-M/Neutrodyne/pull/32) `669904e` | Four completed common GLM rounds; six R4 regressions/121 tests pass. Both heads are merge-only refreshes on main `52b2fd6`; branch logic is unchanged. Persistent-history replacement #70 now closes the real DATA consumer defect on its protected composition, allowing #22's final scoped review. Hash/KDoc contract preserved. Parser `1cf1d9c`/`b2ce1b6` retain 32 MiB+1 bounds/one-segment read-ahead; forced 122 JVM/121 common and all 70 unchanged goldens pass. Earlier host/API26/API36 corpus proof remains scoped to #60 `cba79e3`. Resolved URL/srcset claims stay refuted; byte bounds provide no blocked-read timeout. Predecessors still require their own ordered merge. |
| Room runtime repair | merged | [#56](https://github.com/L-K-M/Neutrodyne/pull/56), merge `98dd9a7`, code `9497e5a` | Two GLM rounds plus independent source/rebuild/API triage close important findings. R1 holder-entry/probe bounds are fixed; R2 cancellation claim is false because pinned recycle is ordinary synchronous fun. Effective AAR timestamp/order settings are reproducible; six artifact hashes and 64 checksum sidecars match, Android 156/156 and JVM 126/126 class/API parity holds. All six exact-head/main jobs pass, [main CI 37980960606](https://github.com/L-K-M/Neutrodyne/actions/runs/37980960606). Version drift fails loudly. Deterministic handoff, Proxy and physical proof remain separate gaps. |
| Room native ownership | merged; startup-compatible fixture checks merged | [#67](https://github.com/L-K-M/Neutrodyne/pull/67) merge `0526a46`, code `efca8a5`; [#71](https://github.com/L-K-M/Neutrodyne/pull/71) merge `0684257`, code `0f9b53b` | Patch0002 closes unpublished native connections across cancellation boundaries; independent six-artifact/API/Binder/resource proof and 59 desktop/9 host pass. The wrapper-routing defect is fixed. Actual startup composition exposed a second test defect: a global app-directory sentinel mistook legitimate initializer writes for misrouting. #71 records every test-owned open, retains rejection before native opening and adds a negative control. [Composed CI 38038627021](https://github.com/L-K-M/Neutrodyne/actions/runs/38038627021) executes all five methods on API26/API36 without method failures/skips (0.377/0.248 s). Each PR closes after one GLM round with no verified important remainder. FD visibility stays conditional; physical proof remains separate. |
| Room migration index inspection | merged | [#69](https://github.com/L-K-M/Neutrodyne/pull/69), merge `bb0d13e`, code `440b90f` | Actual V1→V2 validation exposed upstream counting of WITHOUT ROWID auxiliary PK fields as declared index columns. Patch0003 filters `index_xinfo.key = 0`; raw native controls fail before/pass after. Independent clean six-artifact reproduction, 64 sidecars, eight variant records and 126 JVM/156 Android descriptors pass; 63 desktop/9 host and all-six CI pass. One GLM round closes: remote-Maven cache allegation does not match the exclusive file repository or observed offline warm-worktree 2→3 behavior. Corrected source comments were republished; production binaries remain byte-identical to independently reviewed `e66a735`. |
| M1a data/UI | contracts independently closed; predecessor integration pending | data [#50](https://github.com/L-K-M/Neutrodyne/pull/50) `da57236` → [#51](https://github.com/L-K-M/Neutrodyne/pull/51) `26398ba` → [#52](https://github.com/L-K-M/Neutrodyne/pull/52) `d720ec2`; UI [#53](https://github.com/L-K-M/Neutrodyne/pull/53) `fd3ff56` → [#54](https://github.com/L-K-M/Neutrodyne/pull/54) `1e57019` → [#57](https://github.com/L-K-M/Neutrodyne/pull/57) `9ad540a` | #50 targets immutable `impl/m1a-data-reviewed-refresh-base` `a628d57` (main52/common913/parser669/startupd711), never a wholesale-main merge. Independent 30-file review finds no important defect; 34 real MockWebServer fetch cases pass, with scoped auth/redirects/bounded bodies/cancellation/temp cleanup. Contracts lack later production consumers; this is boundary proof. Author 43 fetcher/33 network/122 parser/121 common, both graphs and [regular CI 38053504470](https://github.com/L-K-M/Neutrodyne/actions/runs/38053504470) pass; instrumented skipped. Higher DATA/UI remain unrefreshed. Library's earlier 15 screen/four real-window proof remains scoped to `9ad540a`. Old disk-exhaustion outcome stays unknown. Refresh after reviewed predecessors; preserve the shell and hold real routes until identity integration. |
| M1a corpus parity | all three refreshed legs proven; integration pending | draft [#60](https://github.com/L-K-M/Neutrodyne/pull/60) `cba79e3`, base `impl/m1a-corpus-refresh-base` `65b7688` | Current #41/#32/#62/#63 composition preserves four device-gate files. Forced 122 JVM cases/all 70 goldens and actual AOSP host loop/all 70 pass. [CI 37992749849](https://github.com/L-K-M/Neutrodyne/actions/runs/37992749849) passes all six; downloaded API26/API36 XML confirms the corpus method executes/passes (3.259/2.758 s), with no corpus skips. API26's two aggregate assumption entries are known locale/seccomp restrictions. Both Room device cases pass per API too. Evidence: `rare-catfish/build/review-corpus-cba79e3/`. GUID #64 remains excluded/held; corpus green is not matching approval. Own bootstrap link removed, shared cache preserved. Physical execution/predecessor review/merge remain. |
| M1a refresh finalization | fixed and independently reviewed; integration pending | draft [#61](https://github.com/L-K-M/Neutrodyne/pull/61) `bf360f7`, base `ee8f0cf` | Failed final scheduling writes propagate after running-state cleanup, retain prior success diagnostics and preserve primary body/cancellation errors with secondary flush failures suppressed. Four of five real SQLite-abort cases fail before; all five pass after. Forced 183 desktop/48 host cases and [CI 37968748861](https://github.com/L-K-M/Neutrodyne/actions/runs/37968748861) pass. Codex reports no important finding; worker/lane fault injection/device proof remain gaps. |
| M1a producer atomicity | proven and independently reviewed; integration pending | draft [#62](https://github.com/L-K-M/Neutrodyne/pull/62) `75f2827`, base `b12c126` | Real parser accepts custom 768 Ki CJK input (2,359,296 UTF-8 bytes); subscribe returns Storage and rolls back the inserted podcast, leaving seeded state/rows unchanged. Exact 2,097,152-byte control commits/decodes. 185 desktop/48 host, fresh independent 21 SubscribeFlowTest cases and [CI 37984609864](https://github.com/L-K-M/Neutrodyne/actions/runs/37984609864) pass. No production fix/fail-before claim: preparation rejects before episode writes; fixture inserts no alias and never reaches membership. Codex reports no important finding; source stack/device integration pending. |
| M1a UTF-8 wrappers | fixed and independently reviewed; integration pending | draft [#63](https://github.com/L-K-M/Neutrodyne/pull/63) `d72e03d`, base `75f2827` | Byte-wise percent decoding corrupted Unicode URLs. Three regressions fail before/pass after; real HTTP target, raw supplementary characters, plus/malformed escapes and once-only decoding verified. 189 desktop/48 host, fresh 30 resolver cases, 16 edge vectors/two credential controls and docs/policies pass. Codex reports no important finding. [CI 37988543012](https://github.com/L-K-M/Neutrodyne/actions/runs/37988543012) passes regular jobs, instrumentation skipped. Dedicated Android wrapper execution and predecessor review/merge remain. |
| M1a GUID matching | review/device proof closed; frozen pending ordered integration | draft [#70](https://github.com/L-K-M/Neutrodyne/pull/70) `ff56521`, base `impl/m1a-guid-device-proof-base` `e75c970`; held unsafe [#64](https://github.com/L-K-M/Neutrodyne/pull/64) `54aafe5` | D98 adds local feed-scoped history/nullable coverage and additive V1→V2, preserving frozen V1. Independent `5a98460` proves durable OBSERVED, boundary completeness, five consumer probes plus NULL/rekey/reopen/deletion, subscribe rollback and distinct-history ownership. Parent independently closes `7b23dd0`'s same-ingest authority/enclosure index/archive delta with 21 selected tests. Three GLM rounds close with two consecutive rounds without confirmed important findings: OBSERVED bypass is refuted by all-record exclusion and a real SQLite mirror; paging is link-driven; composite PK indexing needs no extra DDL. `ff56521` adds refutation/row-ID/cleanup proof, with production logic unchanged; 16 provenance/205 DATA/89 DB pass. Downloaded [CI 38056001052](https://github.com/L-K-M/Neutrodyne/actions/runs/38056001052) confirms both actual framework/bundled 1→V2 methods and five native methods pass on each API. Optional nit pushes stopped; Windows cleanup/backup/sync/physical proof remain gaps. Protected bases never approve unmerged predecessors. |
| M1a paged feed prerequisite | independently/GLM closed; predecessor integration pending | draft [#65](https://github.com/L-K-M/Neutrodyne/pull/65) `09b7efa`, base `360d065` | Eight real Room cases/197 desktop, Android/both graph compiles, policies and [regular CI 37994217608](https://github.com/L-K-M/Neutrodyne/actions/runs/37994217608) pass. Codex and one [GLM round 37997853530](https://github.com/L-K-M/Neutrodyne/actions/runs/37997853530) have no verified important finding. Same-package newDb compile claim refuted; unused import deferred. Exception-type suggestion conflicts with canonical IllegalArgumentException; declined. Enum-whitelisted SQL is not injection. Virtual-read guards/Group seams remain later caller/M2 coverage. Append/drop/other-table invalidation, Android feature/VM lifecycle and Group timestamp gaps stay explicit. Activate Feeds only after DATA identity closure, without synthetic bypass. |
| Navigation transitions | phone-frame proof green; expanded-pane fix active | draft [#73](https://github.com/L-K-M/Neutrodyne/pull/73) `eea73f3` → main | Owner requires instant tabs/back wipe. Explicit NavDisplay specs replace Android's fades; 34 desktop and 20 Android-host tests pass. Native Robolectric uses real AndroidX 1.2.0/frame pixels: old defaults fail instant-tab/wipe assertions, fixed phone scenes pass, predictive seek/cancel is exercised. [CI 38054102568](https://github.com/L-K-M/Neutrodyne/actions/runs/38054102568) passes all six; host frame tests are not GMD/system-gesture proof. Independent review found one important expanded Settings defect: About/Licences share a scene, so back removes detail immediately; the two-pane midpoint fails while a distinct-scene control exits over 239 ms. Pane-content repair/lifecycle proof is active. No GLM or merge approval; physical/RTL/system gestures remain separate. |

**Toolchain used locally:** Temurin 21 and 25 (Gradle toolchains via `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`), Android SDK with build tools 36/37 and platform 37, host CPython 3.14 for Chaquopy's `buildPython` (`neutrodyne.buildPython` in `~/.gradle/gradle.properties`; builds need `LANG=C.UTF-8`). No KVM on the development machine: instrumented tests and Gradle Managed Devices run only on CI. Compose UI tests on the desktop JVM need a GL library; locally a user-level Mesa is unpacked in `~/.local/gfx` (`source ~/.local/gfx/env.sh`).

**Owner execution instructions, retain through compaction:**

- Follow `docs/IMPLEMENTATION-PROMPT.md` milestone by milestone through published v1.0.
- Use available helpers, especially free models, Devin SWE-2 and direct Z.ai GLM-5.3 through pi or opencode. Check helper results before integration.
- Periodically have Codex Sol 6.1 at maximum reasoning review merged `main`.
- Keep PRs small and GLM reviews serial to protect the Z.ai quota. Avoid pushes that cancel an active review. The owner allows waiving GLM after repeated integration failures; record the gap and waiver, never claim approval.

**Release gate:** main `52b2fd6` includes schema #38, handoffs #55/#66/#68, Room #56/#67/#69, fixture #71 and Recovery #40. Exact-head [main CI 38049711044](https://github.com/L-K-M/Neutrodyne/actions/runs/38049711044) passes all six, including instrumented. Scheduled-main [38037939442](https://github.com/L-K-M/Neutrodyne/actions/runs/38037939442) on `bb0d13e` passes all four jobs, including report-only repro; [#58](https://github.com/L-K-M/Neutrodyne/issues/58) closed automatically. Historical failure XML/logcat remain in `rare-catfish/build/nightly-37908392429/`. The owner's general device success report also requests corrected transitions; device model/Android version, exact build and local-release-versus-CI install-over evidence are not recorded. Checklist [#20](https://github.com/L-K-M/Neutrodyne/issues/20), new-transition acceptance, tagged-main and remaining manual checks precede publication. Main still exports V1; actual V1→V2 proof is on unmerged #70, not main integration.

**Review queue:** #67/#69/#71 closed in one GLM round each, Recovery #40 in three. GUID #70 closes after three ([R3 38056001055](https://github.com/L-K-M/Neutrodyne/actions/runs/38056001055)); stop optional nit pushes and freeze its protected proof pending ordered integration. Common #22 occupies the sole final scoped review ([38056925492](https://github.com/L-K-M/Neutrodyne/actions/runs/38056925492)). Navigation #73 needs its confirmed expanded-pane fix and independent/GLM closure before release. Startup #41's flow has no important finding/current CI passes; its preboot fix awaits the exact branch nightly and final GLM. Preserve the original locale timeout's diagnostic gap. #50's independent boundary review is closed, GLM/predecessors pending. Prune swallowing, IOERR escalation, redundant GUID index and exec claims stay rejected. No wholesale proof-base integration; keep #34 draft until Android publishes.

**Data-hang cause and proof:** the 73-minute U1 paging hang produced 72,445,492,508 bytes of test events, then a daemon OOM. Oversized binaries were removed; source, daemon log and retry XMLs remain. Capped probes reproduce inherited `TimeoutCancellationException` retrying, about 695 MB counted stderr/eight seconds and a fresh writer pending after six seconds with Free/permits-0 state. Protected normal release refutes interrupted-release claims; test-clock divergence accelerates a real hazard. All probe groups are dead, 168 KB evidence retained. Caps remain 120 s plus 15 s kill, 64 MiB/file, 512 MiB total, fresh no-daemon, ≤2 workers. Stable 3.0.3 has no fail-fast switch; alpha retains the flaw. #56's reproducible Apache-2.0 repair is merged in `98dd9a7`, compiler/plugin/paging/testing remain upstream 3.0.3. Remove vendoring once upstream ships equivalent behavior. Older synthetic data/UI bases still need refresh; never replay full unpatched suites or treat green retries as resolution.

**Remaining UI sequence:** feature installers activate routes immediately. #57's real Metro/Coil and sourced-Mesa window paths pass; minimum public scheduler export is now proven with internal constructor/rebaser. Fresh composed CI/review remains. `FeedRepositoryImpl` precedes feeds/podcast; preserve splash/startup gates and the 30-source desktop shell. Feeds/data, podcast/settings, discover/episode and the small documented remainder follow.

**Periodic main review (2026-10-08, Codex Sol 6.1 Max, `9805dbf`):** no Android-only blocker found in the reviewed range. All three important desktop fixes merged: runtime host/archive handling [#36](https://github.com/L-K-M/Neutrodyne/pull/36) (`8cccf7c`), bounded/cancellable handshake reads [#35](https://github.com/L-K-M/Neutrodyne/pull/35) (`221feb1`), and returning smoke-window/first-frame validation [#39](https://github.com/L-K-M/Neutrodyne/pull/39) (`9a6e78e`). GLM's alleged `assertThrows` compile blocker was refuted by the shared test helper and forced convention tests. The Windows jpackage suffix claim was Linux-only. Malformed server CIDRs and the stale licence-repair note in design 01 remain deferred.

**Windows test portability:** [#46](https://github.com/L-K-M/Neutrodyne/pull/46) merged as `9a81df3`. CI and the native Windows unit step above pass with identical test sources. One GLM round found no applicable defect: the resolved runtime uses `kotlin-test-junit` and JUnit 4. Temp-directory cleanup and unrelated fixture consolidation are deferred.

**Latest periodic main review (2026-10-10, Codex Sol 6.1 Max, `a7200f5`):** no important finding across `ce2ec9a..a7200f5` (two merges/57 files). Code matches accepted schema `468605f`; only HANDOFF differs, and exact-head CI passes all six. Runtime #67/#69 and fixture #71 have separate immutable source/rebuild/device closure and await the next periodic merged delta check. Unmerged DATA/feeds/library remain outside main; corpus or GUID proof does not substitute for integration. Scheduled gate is now green; physical/tagged/publication gates remain.

**Resume:** fetch first; cut new task branches from `origin/main`, or merge it into existing work before edits. Check PR heads, CI and review comments before acting. Worktrees are under `~/nd-wt/`, prompts and reports under `~/nd-wt/prompts/` and `~/nd-wt/reviews/`. Use `export JAVA_HOME=$HOME/.local/jdks/jdk21 ANDROID_HOME=$HOME/android-sdk LANG=C.UTF-8`; source `~/.local/gfx/env.sh` for Compose JVM tests. Watch free disk space; prune only verified merged worktrees and regenerable outputs, never active helper work.

**Coordination:** `ca2b8527` stays idle. `0d1ed070` published #41 `8c25e94`, #22 `913ba53`, #32 `669904e` and #50 `da57236`, now idle; `e1aeb1e9` closed #50's independent boundary review and is idle; GUID `ff56521` is frozen in `guid-provenance` (`wks_91556f22ddc95651`). `f0c145ae` owns navigation's pane fix in `navigation-transitions` (`wks_82c6e8bd801b28dd`). Codex `5dcdbde0` found the navigation/preboot defects, verified startup retry and localized the original timeout to API36 locale recreation; it is idle. DeepSeek's second opinion remains incomplete. Preserve `main-review-3/build/review-navigation-3188d3e/`, `review-startup-d711ad4/`, `review-startup-d711ad4-timeout/` and prior proofs, plus `rare-catfish/build/review-guid-ff56521-device/`, `review-startup-d711ad4-device/`, `review-startup-8c25e94-device/`. #50's immutable baseline `a628d57` (`wks_7039f8e289af6633`) includes pre-fix startup; refresh/reslice after predecessor approval. Parent owns serial CI/reviews/handoff; no child monitors/bootstrap. Wait for Devin completion before follow-ups. Heartbeat `b6598800`: every 10 min, 144 runs/until 2026-10-10 15:32 UTC; delete on takeover/stop/completion, never extend silently. Create-agent rejects `background`; expiry accepts `24h`, not `1d`.

**Deferred follow-ups:** integrate verified query-component UTF-8 repair #63 after predecessors; make process-start probe reports atomic; guard `SettingStore.observe` against a key from the wrong file; optionally clamp sanitizer image dimensions (renderer bounds them). These are not blanket release approvals or completed work.

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
