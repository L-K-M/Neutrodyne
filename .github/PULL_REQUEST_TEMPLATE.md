<!-- SPDX-License-Identifier: Unlicense -->
<!-- The checklist of docs/design/09-quality-and-release.md "PR template". Tick what
     applies; delete what does not. -->

## Checklist

- [ ] Tests per 09's test obligations; bug fixes include a failing-first regression test; shared code is tested in `commonTest` and passes on the desktop JVM and on Android (PLAN DoD).
- [ ] UI changed → screenshots re-recorded for the Android and desktop sets with `record-screenshots.yml`; accessibility checks pass; from MD4, every new action has a keyboard shortcut or context-menu entry on the desktop.
- [ ] Strings externalised as Compose resources (Android-only labels in `res/`), plurals used, no concatenation.
- [ ] Schema change → version bump, migration and test; a synced column also updates the capture triggers and `SyncCaptureTest` (from MS0).
- [ ] New setting → classified `settings`/`device_settings` and registered (01); a portable key declares whether it syncs (`SettingKey.synced`, D93).
- [ ] YouTube UI checked in both capability modes (engine present and external mode) against 04's capability matrix (08's capability differences).
- [ ] No MockK in `commonTest` or `androidTest`; `commonMain` free of `java.*` and `android.*` (`checkBannedApis`).
- [ ] Developer tooling only in the `debug` build type (`app/src/debug/`, `debugImplementation`, branches on `BuildInfo.debug`); no `BuildConfig.DEBUG` outside `:app`; any test hook in `main` inert until an instrumentation test sets it (except a shell-only, DUMP-protected trigger that runs only production code, 05's `SnapshotNowReceiver`; PLAN 7.2, D2); benchmark-only code only in `app/src/benchmarkRelease/`.
- [ ] Licences (D3): a new dependency or bundled component is permissive, LGPL dynamically linked with its source attached, MPL-2.0 only as unmodified files or data (plus only the pinned PBS build patches with their source attached, PO-48 proposed default), MS-RL only as the unmodified WiX components jpackage embeds in the MSI with the WiX source attached (PO-48 proposed default), or part of the unmodified OpenJDK runtime under the runtime exception; its Licensee allow-list or lockfile entry (`python-components.lock`, `native-components.lock`, `runtime.lock`), the Licences screens and `THIRD_PARTY_NOTICES.md` are updated in the same PR; no GPL or AGPL anywhere.
- [ ] Copied code: 01's rule — copied code keeps its licence headers, its origin and licence are named in the commit message and the PR, and its licence is Unlicense-compatible (never GPL/AGPL).
- [ ] Design document updated if behaviour deviates (PLAN DoD).
- [ ] Shim PRs (`youtube/engine/python/neutrodyne_ytx/`): `shimTest` and `shimTestStdio` green against the bundled yt-dlp (CI) and against the latest approved version (locally: `scripts/engine/bump-ytdlp.sh <approved version>` without committing, then both tasks); recordings re-recorded when the requests changed; `SHIM_API_VERSION` bumped for incompatible changes (04).
- [ ] Bundled-engine bumps: only a version the engine canary approved; `bump-ytdlp.sh` output committed unchanged (04).
- [ ] Native code or a bundled runtime component (`playback/native/`, `runtime.lock`, the desktop Python lock): `nightly.yml` dispatched with `scope: desktop` on the branch and green on all four targets.
- [ ] Sync protocol or server change: a conformance vector for every merge-behaviour change; the protocol version window respected (10).
