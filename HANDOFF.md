# Handoff — state of the Neutrodyne work

This file lets another person or agent take over the work at any point. **Last updated: 2026-10-09.** M0a is merged; M0b and M1a are in progress (see §0). Follow [`docs/IMPLEMENTATION-PROMPT.md`](docs/IMPLEMENTATION-PROMPT.md) through the v1.0 release.

## 0. Implementation status

| Milestone | State | Branch / PR | Notes |
|---|---|---|---|
| M0a.1 | code merged; tester release pending | [PR #10](https://github.com/L-K-M/Neutrodyne/pull/10), merge `e0c3722` | Scaffold, Android shell, policy gates and release pipeline. Seven Codex Sol review rounds; GLM waived after repeated timeouts. Release fixes and verification gaps landed in PRs #15, #16, #17, #18, #23 and #24. Next: green scheduled nightly, then `v0.1.0`; checklist [#20](https://github.com/L-K-M/Neutrodyne/issues/20). |
| M0a.2 | merged | [PR #13](https://github.com/L-K-M/Neutrodyne/pull/13), merge `49bc849` | Library spikes and Android UI/resource tests passed on API 26 and 36. Below API 33 the language-switch test skips while only `en-US` ships as Android resources; recorded in `docs/design/01-foundation.md` (S11) and `docs/design/09-quality-and-release.md` (Shipped locales and per-app language). No separate tester build. |
| M0b | server/desktop merged; native CI/release pending | [#19](https://github.com/L-K-M/Neutrodyne/pull/19), [#25](https://github.com/L-K-M/Neutrodyne/pull/25); draft [#34](https://github.com/L-K-M/Neutrodyne/pull/34), `impl/m0b-3-ci` `75cc476` | [Matrix 37881222222](https://github.com/L-K-M/Neutrodyne/actions/runs/37881222222) passes all Android jobs, repro and both Linux targets; Windows packaging and macOS image checks fail. Skiko self-authored native/sidecar attacks were reproduced and fixed with trusted classpath-entry hashes; 31 checker and 15 packaging tests pass. Mach-O layout-field tamper checks and native fixes are pending. Both SDK fixture sets are preserved. Publish Android `v0.1.0` before merging desktop release jobs; branch dispatches do not replace the scheduled-main gate. S13 reference hardware remains. |
| M1a database | refreshed small slices; review in progress | [#37](https://github.com/L-K-M/Neutrodyne/pull/37) `2c9dd23`; drafts [#38](https://github.com/L-K-M/Neutrodyne/pull/38) `6276f59`, [#40](https://github.com/L-K-M/Neutrodyne/pull/40) `b8586cb`, [#41](https://github.com/L-K-M/Neutrodyne/pull/41) `d657df3` | #37's two completed rounds exposed malformed-input acceptance and reversed-bound jitter stack overflow. Six regressions fail before their fixes; all 24 protocol tests and compile/static/doc gates pass. Boxed-number and upstream head-length claims were refuted by bytecode and pinned upstream source. Round 3 is the sole active GLM review; fresh CI is running. #38/#40 have green regular CI. Moving `DatabaseOpenInitializer` into #41 beside its bindings fixes the recovery-slice build boundary. All six fresh #41 CI/device jobs pass ([37878644665](https://github.com/L-K-M/Neutrodyne/actions/runs/37878644665)); database/Android-production/desktop-DI code is unchanged. #26 is superseded, branch retained. Merge in order and retarget successors. |
| M1a feeds | verified fixes published; next review queued | draft [#22](https://github.com/L-K-M/Neutrodyne/pull/22) `2bd11ab`; stacked draft [#32](https://github.com/L-K-M/Neutrodyne/pull/32) `6b9d821` | Five Codex package rounds; two completed GLM rounds. Full-scope [37866559182](https://github.com/L-K-M/Neutrodyne/actions/runs/37866559182) completed all nine chunks and updated the consolidated review plus 12 inline suggestions. Malformed URLs, typographic dates and reachable hash-field boundary collisions are fixed with failing-before/passing-after regressions. Initial separator doubling was refuted by a trailing/leading-run counterexample; reversible backslash/control escaping replaces it, including content-hash leaves. All 103 feed tests and compile/static/doc gates pass. Current main is merged; regular CI is running. Preserve full credential redaction and conservative UUID/base64url detection; slug narrowing remains declined. No version/migration is invented for unshipped keys. |
| M1a data/UI | data and two UI slices published; base defect blocks integration | data [#50](https://github.com/L-K-M/Neutrodyne/pull/50) `777d8f3` → [#51](https://github.com/L-K-M/Neutrodyne/pull/51) `26398ba` → [#52](https://github.com/L-K-M/Neutrodyne/pull/52) `d720ec2`; UI [#53](https://github.com/L-K-M/Neutrodyne/pull/53) `fd3ff56` → [#54](https://github.com/L-K-M/Neutrodyne/pull/54) `1e57019` | Data slices match verified integration `8a3b62d`, base `a064b15`. U1's refreshed base `e3dae60` contains current main plus #52; its 53-path patch is unchanged and CI passes. U2's 30 paths match UI source `753bee3`; 43 UI tests, NdIcons, both-target compilation and static gates pass. CI [37879743701](https://github.com/L-K-M/Neutrodyne/actions/runs/37879743701) attempt 1 failed the data paging test, then heap/trust-store checks; attempt 2 passes but does not clear that defect. Free MiMo is checking runnable feature/wiring slices. Preserve originals `d21dc1b`/`753bee3`; port only `05b84a6..753bee3`, never replace the current desktop with old stubs. Desktop ViewModel factory/Coil provisioning remains required. |

**Toolchain used locally:** Temurin 21 and 25 (Gradle toolchains via `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`), Android SDK with build tools 36/37 and platform 37, host CPython 3.14 for Chaquopy's `buildPython` (`neutrodyne.buildPython` in `~/.gradle/gradle.properties`; builds need `LANG=C.UTF-8`). No KVM on the development machine: instrumented tests and Gradle Managed Devices run only on CI. Compose UI tests on the desktop JVM need a GL library; locally a user-level Mesa is unpacked in `~/.local/gfx` (`source ~/.local/gfx/env.sh`).

**Owner execution instructions, retain through compaction:**

- Follow `docs/IMPLEMENTATION-PROMPT.md` milestone by milestone through published v1.0.
- Use available helpers, especially free models, Devin SWE-2 and direct Z.ai GLM-5.3 through pi or opencode. Check helper results before integration.
- Periodically have Codex Sol 6.1 at maximum reasoning review merged `main`.
- Keep PRs small and GLM reviews serial to protect the Z.ai quota. Avoid pushes that cancel an active review. The owner allows waiving GLM after repeated integration failures; record the gap and waiver, never claim approval.

**Release gate:** the latest scheduled nightly ([run 37753068764](https://github.com/L-K-M/Neutrodyne/actions/runs/37753068764)) failed `api37-16k`. [#47](https://github.com/L-K-M/Neutrodyne/pull/47) merged as `8b2069c`: pinned tools 23.0 fix avdmanager 12.0's `target=android-0` output for API `37.0`; fresh AVD/boot/log gates and main-only issue reporting are tested. All four native jobs pass on `950a99f` ([run 37861532147](https://github.com/L-K-M/Neutrodyne/actions/runs/37861532147)); merged head `8feed54` adds only CI fixture wiring/mode and has green CI. [#49](https://github.com/L-K-M/Neutrodyne/pull/49) merged as `2b7ace9` after one GLM round without an applicable important finding: root-optional locale polling and a destroyed-activity probe pass the API26/33/34/36 nightly group ([run 37863196374](https://github.com/L-K-M/Neutrodyne/actions/runs/37863196374)). The next scheduled main nightly must pass before tagging; `repro` stays report-only. No release is published. Owner device checks remain in [#20](https://github.com/L-K-M/Neutrodyne/issues/20).

**Review queue:** #37 round 3 ([37885082928](https://github.com/L-K-M/Neutrodyne/actions/runs/37885082928)) is the sole active GLM review. #22's verified fixes have green regular CI as draft; ready it only when the slot is free. Then advance database #38 → #40 → #41 and parser #32, merging and retargeting in dependency order. Preserve the newest main handoff; this checkpoint is draft #55. Data #50 → #51 → #52 must be refreshed after reviewed upstream merges; do not merge the synthetic base as a bypass. Its reproduced Room defect needs a fix before data/UI integration. Keep #34 draft until Android publishes. #47 settled after four rounds; #49 after one. Timeouts are missing review, not approval or completed rounds.

**Data-hang cause and proof:** the 73-minute U1 paging hang produced 72,445,492,508 bytes of test events, then a daemon OOM while draining them. Oversized binaries were removed; the daemon log, source and retry XMLs remain. Codex identified Room 3.0.3's acquisition loop retrying inherited `TimeoutCancellationException`; protected normal release refutes the earlier interrupted-release claim. Capped probes in `m1a-data-integration/build/probes/` reproduce a timed-out waiter spinning after holder release/cancel, about 695 MB of counted stderr in eight seconds, and a fresh writer still pending after six seconds with the same Free/permits-0 pool state. Real-vs-virtual query-context controls confirm the test's timing accelerator; the original full incident was not replayed. All probe groups are dead, tracked tree unchanged, 168 KB evidence retained. Caps: 120 s plus 15 s kill, 64 MiB/file, 512 MiB total, fresh no-daemon process, workers ≤ 2. Stable 3.0.3 has no supported fail-fast policy; alpha 3.1.0 retains the flaw. Codex's accepted plan patches the acquisition routine to distinguish its own timeout and recycle untransferred resources; an ordinary-cancellation engine helper cannot protect enclosing foreign timeouts. SWE-2 Max is implementing a reproducible, provenance-pinned runtime patch on `fix/room-acquisition-timeout` from current main, preserving compiler/API/schema and deadlines. #54's first CI attempt independently repeats the paging timeout followed by heap/trust-store failures; green retries are not resolution.

**Remaining UI sequence:** the free review confirmed that feature entry installers activate routes immediately. Host ViewModel/Coil wiring must accompany the first Library replacement on both clients; `FeedRepositoryImpl` must precede feeds/podcast. Free MiMo is implementing `impl/m1a-ui-3-library` from #54, preserving current splash/startup gates and the 30-source desktop shell. Desktop factory and artwork-loader tests are required. Feeds/data, podcast/settings, discover/episode and the small documented remainder follow. Default-Coil behavior in old stubs is not proof for newly activated artwork models.

**Periodic main review (2026-10-08, Codex Sol 6.1 Max, `9805dbf`):** no Android-only blocker found in the reviewed range. All three important desktop fixes merged: runtime host/archive handling [#36](https://github.com/L-K-M/Neutrodyne/pull/36) (`8cccf7c`), bounded/cancellable handshake reads [#35](https://github.com/L-K-M/Neutrodyne/pull/35) (`221feb1`), and returning smoke-window/first-frame validation [#39](https://github.com/L-K-M/Neutrodyne/pull/39) (`9a6e78e`). GLM's alleged `assertThrows` compile blocker was refuted by the shared test helper and forced convention tests. The Windows jpackage suffix claim was Linux-only. Malformed server CIDRs and the stale licence-repair note in design 01 remain deferred.

**Windows test portability:** [#46](https://github.com/L-K-M/Neutrodyne/pull/46) merged as `9a81df3`. CI and the native Windows unit step above pass with identical test sources. One GLM round found no applicable defect: the resolved runtime uses `kotlin-test-junit` and JUnit 4. Temp-directory cleanup and unrelated fixture consolidation are deferred.

**Latest main review (2026-10-08, Codex Sol 6.1 Max, `9a81df3`):** no new important findings in the desktop fixes and surrounding startup, packaging, server and Android-release boundaries. All 244 focused tests passed; local Linux packaging and headless smoke passed. [Main CI](https://github.com/L-K-M/Neutrodyne/actions/runs/37859102685) passed all six jobs. Headed/native cross-platform compliance and reference-hardware checks remain separate gates; unmerged #34/#47 were excluded.

**Resume:** fetch first; cut new task branches from `origin/main`, or merge it into existing work before edits. Check PR heads, CI and review comments before acting. Worktrees are under `~/nd-wt/`, prompts and reports under `~/nd-wt/prompts/` and `~/nd-wt/reviews/`. Use `export JAVA_HOME=$HOME/.local/jdks/jdk21 ANDROID_HOME=$HOME/android-sdk LANG=C.UTF-8`; source `~/.local/gfx/env.sh` for Compose JVM tests. Watch free disk space; prune only verified merged worktrees and regenerable outputs, never active helper work.

**Coordination:** source `ca2b8527`, U1 `cc3fff95`, U2 `57910c83`, feed `0d1ed070`, probe `bb3b9969`, reviewer `5dcdbde0` and planner `7aa58e37` are idle. Native `75454da9` owns `~/nd-wt/m0b-c`; an ACP foreground-turn error required resuming the same task, retaining clean pushed `75cc476`. SWE-2 Max `f0c145ae` owns `room-acquisition-fix`; free MiMo `d22726a0` owns `m1a-ui-library`. Parent owns database review fixes and the serial queue. Do not duplicate active work or settled reviews. Child heartbeat `746f6596` was deleted. Parent `8e40cada` remains; delete it on coordinator takeover or owner stop.

**Deferred follow-ups:** make process-start probe reports atomic; guard `SettingStore.observe` against a key from the wrong file; optionally clamp image dimensions in the sanitiser (the UI renderer already bounds them). These are not blanket release approvals or completed work.

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
