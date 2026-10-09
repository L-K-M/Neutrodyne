# 09 — Quality and release

> Status: Draft v1, 2026-10-04; revised 2026-10-05 for the product owner's decisions (GitHub Releases only, no developer-verification registration, the embedded yt-dlp engine); revised 2026-10-05 for PO-31–PO-35 (notify-only update check in `:core:domain`/`:core:model`/`:core:data` with no in-app download or install, no beta channel, no mirror, a keystore committed to the repository); **scope revision 2026-10-05 (S0–S13):** quality and release now cover three products shipped together — the Android app, the desktop app (Windows, macOS, Linux) and the self-hosted sync server — with `commonTest` on the desktop JVM, desktop UI, native and packaged-app tests, the server suite and the sync conformance, convergence and cross-device tests (E11, E12), per-target desktop CI on four runners, a `release.yml` that publishes APKs, desktop installers, the runtime and FFmpeg sources, the server JAR and a GHCR image in one immutable release, published APKs as optimised non-debuggable release builds signed with `signing/neutrodyne-public.keystore` (the debuggable-build trade-offs, PB22/PB23 and the dev-tools build retired), additive `desktop[]` and `server` fields in the update manifest, budgets per platform (PB24–PB31) and licence checks for native desktop libraries, the runtime and the server image; **final cross-document review 2026-10-05:** runtime checks without `IMPLEMENTOR`, the WiX and python-build-standalone source assets, `check-embedded-natives.sh`, server-image sources on GHCR, two more public-key trade-offs · Implements: N1 / N3 / N4 / N5 / N8 / N9 / N10 / N11 / N12 / N13 (quality and release parts), R2.9 (measurement), R3.9 (engine canary for both hosts), R6.1–R6.7 (release assets, update-check behaviour, Android install guidance and the server's README text; screens in 08, desktop guidance in 11, server deployment in 10), R7.4 (propagation budget), R8.1 (shared tests on the desktop JVM) · Milestones: M0a, M0b, M1–M11 (M1a, M1b, M6a, M6b, M9a, M9b, M11a, M11b), MD0–MD5, MS0–MS3, M12–M17 · Honours: D2, D3, D13, D59, D60, D61, D62, D63, D76, D77, D78, D79, D80, D81, D83, D89, D95, D96; PO-1, PO-2 and PO-35 (re-resolved 2026-10-05), PO-5, PO-8, PO-31–PO-34 (resolved); PO-3, PO-10, PO-14, PO-18, PO-28, PO-36, PO-39, PO-40, PO-42, PO-43 defaults · Owns: test strategy and infrastructure for the shared code, both apps and the server; CI workflows (including the desktop matrix, the server image and the engine canary for both engine hosts); static-analysis gates and build-output checks (APKs, desktop images, runtime sources, server image, update manifest); dependency updates; versioning; the committed keystore and its public-key trade-offs; GitHub-only distribution and release assets for every product (GHCR for the server image); the update-check design for both apps and the manifest the server's notice reads; the report-only reproducibility check; Android developer-verification guidance (including the README "Install and update" content) and the README "Run the server" text; privacy policy and network inventory; crash reporting and diagnostics content; localisation workflow; performance budgets; release checklists

Contents: [Scope](#scope) · [Test strategy](#test-strategy) · [Test infrastructure](#test-infrastructure) · [CI pipelines](#ci-pipelines) · [Static analysis](#static-analysis) · [Dependency updates](#dependency-updates) · [Versioning and signing](#versioning-and-signing) · [Distribution channels](#distribution-channels) · [Update check](#update-check) · [Reproducible builds](#reproducible-builds) · [Developer verification](#developer-verification) · [Privacy](#privacy) · [Crash reporting and diagnostics](#crash-reporting-and-diagnostics) · [Localisation](#localisation) · [Performance budgets](#performance-budgets) · [Release checklist](#release-checklist) · [Settings](#settings) · [Delivery by milestone](#delivery-by-milestone) · [New names introduced here](#new-names-introduced-here) · [Open questions](#open-questions) · [Sources](#sources)

---

## Scope

This document is the engineering-process contract: how every other document's code — the shared Kotlin Multiplatform modules, the Android app, the desktop app and the sync server — is tested, gated, built, signed, shipped and measured. An implementer (human or AI session) reads it at M0a to set up the machinery, at M0b for the desktop and server pipelines, and again whenever a milestone adds a test type, a workflow job or a release asset. The three products share one repository, one version line and one GitHub release per tag ([D63](../PLAN.md#3-key-decisions), [D79](../PLAN.md#3-key-decisions)).

**Owned here** (other documents link, never restate):

| Topic | Section |
|---|---|
| Test pyramid for `commonTest` (on the desktop JVM), `desktopTest`, Android host and device tests, the server suite, the sync conformance and convergence tests, desktop native and packaged tests; per-change test obligations; the E2E journey catalogue (E0–E12); flakiness policy | [Test strategy](#test-strategy) |
| `configureNeutrodyneTestTasks()` and `neutrodyne.android.testing` content, shared test helpers, the `:core:testing` (KMP) inventory, fakes and contract tests, Robolectric/Room/MockWebServer/Media3/WorkManager/Compose/Roborazzi/GMD configuration, desktop UI tests, Ktor's test host, recorded-response and fixture policy | [Test infrastructure](#test-infrastructure) |
| GitHub Actions workflows `ci.yml`, `nightly.yml` (with the four-target `desktop-matrix`), `release.yml` (Android, desktop, sources, server JAR and GHCR image, publish), `engine-canary.yml` (the workflow for both engine hosts; its test content is 04's), helper workflows, CI scripts, runners, caching, hardening, secrets and environments, required checks | [CI pipelines](#ci-pipelines) |
| Lint, Spotless/ktlint/compose-rules, detekt, `.editorconfig`, translation checks, build-output checks (APKs, desktop images, runtime sources, server image), PR template | [Static analysis](#static-analysis) |
| Renovate configuration (including the JDK vendor, python-build-standalone, FFmpeg, miniaudio, WiX and base images) and update review rules | [Dependency updates](#dependency-updates) |
| Version scheme procedure for every product, `scripts/release.sh`, changelogs, the committed keystore, signing schemes, public-key trade-offs | [Versioning and signing](#versioning-and-signing) |
| GitHub Releases (and GitHub Container Registry for the server image) as the only channel: release assets of every product, immutable releases, provenance attestations, release body, Obtainium, tester builds, the README "Run the server" text, GitHub takedown | [Distribution channels](#distribution-channels) |
| Update-check behaviour of both apps (in `:core:domain`, `:core:model` and `:core:data`): update manifest with its `desktop[]` and `server` fields, checks, update card and links, notices | [Update check](#update-check) |
| Reproducibility hygiene, nightly report-only reproducibility check (APKs, desktop JARs, server JAR) | [Reproducible builds](#reproducible-builds) |
| Android unregistered distribution: phases, affected devices, the advanced flow, fallbacks, notice timing, watch cadence, README "Install and update" content (the desktop OS gates are 11's) | [Developer verification](#developer-verification) |
| `PRIVACY.md` (with its Sync and Desktop sections), the network inventory of both apps and the server, redaction surfaces, `SECURITY.md` | [Privacy](#privacy) |
| ACRA configuration, `CrashReporter`/`CrashContext`, the crash-report field set both apps use, diagnostics API and contents, copy/report/export | [Crash reporting and diagnostics](#crash-reporting-and-diagnostics) |
| Weblate, string conventions for Compose resources, shipped locales, per-app language and the desktop language, pseudo-locales | [Localisation](#localisation) |
| Reference devices and hardware (PO-28, PO-43), budgets PB1–PB31 (Android release builds, desktop, server, sync propagation), where each is measured, Macrobenchmark and baseline-profile journeys | [Performance budgets](#performance-budgets) |
| Checklists per release type and the v1.0 gate for all three products | [Release checklist](#release-checklist) |

**Not covered here:** the version catalog and convention-plugin skeletons ([01 Toolchain and versions](01-foundation.md#toolchain-and-versions), [01 Convention plugins](01-foundation.md#convention-plugins)); KMP targets, source sets and JVM islands ([01 Source sets and JVM islands](01-foundation.md#source-sets-and-jvm-islands)); Android build types (published `release`, local `debug`, `benchmarkRelease`, `nonMinifiedRelease`), R8 and keep rules, the baseline-profile plugin wiring, the `neutrodynePublic` signing config, ABI splits and the no-engine switch ([01 Build variants and ABIs](01-foundation.md#build-variants-and-abis), [01 Release build and baseline profiles](01-foundation.md#release-build-and-baseline-profiles), [01 Signing config](01-foundation.md#signing-config), [01 Debug build type](01-foundation.md#debug-build-type), [01 Emergency build without the engine](01-foundation.md#emergency-build-without-the-engine)); Gradle-side policy tasks `assertModuleGraph`, `verifyDependencyPolicy`, `verifyManifestPermissions`, `checkSpdxHeaders`, `checkBannedApis`, `checkBrandAssets`, the Licensee allow-list ([01 Licensing and dependency policy](01-foundation.md#licensing-and-dependency-policy)) and `checkPythonLicences`, `checkNativeLicences`, `verifyBundledYtDlp`, `shimTest`, `shimTestStdio` ([01 Python and native components](01-foundation.md#python-and-native-components)) — 09 only decides when CI runs them; what one desktop packaging job builds and checks (formats, jlink modules, the AOT cache, ad-hoc signing, the macOS `0.x` ZIP, image scan rules, the runtime-exception and FFmpeg duties, lockfile formats) ([11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception)); desktop install guidance and the desktop asset-selection rules ([11 Install and update](11-desktop.md#install-and-update)); desktop crash files, the crash dialog and log rotation ([11 Desktop diagnostics and crash files](11-desktop.md#desktop-diagnostics-and-crash-files)); the server's design, deployment files, image content and image-source bundle ([10 Deployment](10-sync.md#deployment)); the logging `Redactor` algorithm ([01 Logging and redaction](01-foundation.md#logging-and-redaction)); what each feature area tests (the `## Testing` sections of [02](02-data-model.md#testing), [03](03-feeds-and-discovery.md#testing), [04](04-youtube.md#testing), [05](05-groups-opml-backup.md#testing), [06](06-playback.md#testing), [07](07-downloads.md#testing), [08](08-ui-ux.md#testing), [10](10-sync.md#testing), [11](11-desktop.md#testing)); the diagnostics screen's visuals ([08 Diagnostics](08-ui-ux.md#diagnostics)); the update check's screens, notices and wording ([08 Updates settings](08-ui-ux.md#updates-settings), [08 Install and updates help](08-ui-ux.md#install-and-updates-help)); YouTube legal texts, the engine-update trust chain, what the engine canary tests and the hotfix runbook ([04 Licensing and legal](04-youtube.md#licensing-and-legal), [04 Engine updates](04-youtube.md#engine-updates), [04 Engine canary](04-youtube.md#engine-canary), [04 Maintenance and hotfix process](04-youtube.md#maintenance-and-hotfix-process)); what a manually installed app update means for playback and downloads ([06 App updates and playback](06-playback.md#app-updates-and-playback), [07 App update](07-downloads.md#app-update)); Auto Backup rules ([05 Auto Backup](05-groups-opml-backup.md#auto-backup)).

**Repository files owned by this document** (created in the milestone shown in [Delivery by milestone](#delivery-by-milestone)): `.editorconfig`, `.clang-format`, `.github/workflows/{ci,nightly,release,engine-canary,record-screenshots,keepalive}.yml`, `.github/PULL_REQUEST_TEMPLATE.md`, `.github/ISSUE_TEMPLATE/{bug.yml,feature.yml,release.md}`, `renovate.json`, `config/detekt/detekt.yml`, `app/lint-baseline.xml`, `app/policy/locales.txt`, `app/proguard-test.pro`, `changelogs/<versionCode>.txt` (one per release), `release-assets.json` (the asset set each release must carry, [release.yml](#releaseyml) step 1), `scripts/release.sh`, `scripts/ci/*.sh` (including `make-update-json.sh`, `check-update-json.sh`, `public-cert-sha256.sh`, `check-apk.sh`, `check-desktop-image.sh`, `check-runtime-sources.sh`, `check-server-image.sh` and `network-capture.sh`), `scripts/l10n/update-shipped-locales.sh`, `PRIVACY.md`, `SECURITY.md`; the keystore `signing/neutrodyne-public.keystore` (generated once with the command in [Committed keystore](#committed-keystore)) and the text of `signing/README.md` (01 lists both files in its M0 scaffold); the content of the README's "Install and update" section ([Developer verification](#developer-verification)) and "Run the server" section ([README "Run the server"](#readme-run-the-server)); the README itself is maintained with the PLAN, and its "Install on Windows, macOS or Linux" text is 11's ([11 README source text](11-desktop.md#readme-source-text)). The image-scan rules of `check-desktop-image.sh` are 11's ([11 Image scan rules](11-desktop.md#image-scan-rules)) and the content rules of `check-server-image.sh` 10's ([10 Image sources and the runtime exception](10-sync.md#image-sources-and-the-runtime-exception)); 09 owns the scripts, their inputs and where they run. `scripts/engine/*.sh` and `scripts/youtube/record-responses.sh` are 04's (the trim list of `trim-python.sh` is 11's), `scripts/desktop/mac-zip.sh` and `playback/native/ffmpeg/build.sh` are 11's; the workflows here call them. The lockfiles `youtube/ytdlp/python-components.lock`, `youtube/ytdlp-desktop/python-components.lock`, `playback/native/native-components.lock` and `desktopApp/runtime.lock` are defined by 01 and 11; 09's jobs read them. `CONTRIBUTING.md` content is 01's ([Copied code and contributions](01-foundation.md#copied-code-and-contributions)); 09 adds the testing and release sections.

---

## Test strategy

Serves N1, N9, N11, N12, N13, R8.1 and every milestone's acceptance criteria. Delivered from M0a; M0b adds the desktop and server layers, MS0 the sync layers; each milestone adds the tests its documents list. Honours [D59](../PLAN.md#3-key-decisions), [D81](../PLAN.md#3-key-decisions).

### Principles

1. **Fakes over mocks.** Every `:core:domain`, `:*:api` and `:sync:api` interface has a hand-written fake in `:core:testing` (`commonMain`); MockK is allowed only in JVM-only test source sets (`desktopTest`, `androidHostTest`, a JVM island's or the server's `test`) for final third-party classes — never in `commonTest` and never on devices ([`:core:testing` inventory](#coretesting-inventory)).
2. **Common first, on the desktop JVM.** Shared logic is tested once, in `commonTest`, which runs on the desktop JVM through each module's `desktopTest` task (common tests cannot run as Android local tests, [01 Source sets and JVM islands](01-foundation.md#source-sets-and-jvm-islands)). Tests that need JVM-only libraries or the bundled SQLite driver live in `desktopTest`; Android-only code (WorkManager, Media3, Keystore, notifications, the Android shell) runs under Robolectric in `androidHostTest` or `:app`'s `test`; only behaviour neither can reproduce — the Android SQLite driver on API 26, Keystore, FGS/UIDT, audio hardening, 16 KB pages, process death, the release APK's R8 output, real decoders at scale — runs on emulators. Desktop native and packaged behaviour (FFmpeg, miniaudio, OS media sessions, installers) runs on the four desktop runners nightly and at release.
3. **Goldens make format code reviewable.** Parsers, writers, codecs and sync conformance vectors compare against committed golden JSON, XML or vector files; screenshots are committed PNGs in an Android and a desktop set. All are rewritten only by an explicit switch.
4. **Nothing in a blocking job touches the internet.** Feeds, directories, YouTube, GitHub's release endpoints and sync servers are served by MockWebServer, Ktor's test host, an in-process `InMemorySyncServer` or a server started from the built JAR on loopback, or replayed from recorded responses; live canaries run nightly and never block. The engine canary downloads yt-dlp's release files (verified like the apps do) but tests them only against recorded responses.
5. **Deterministic time and locale.** No production code reads the wall clock directly (01 `Clock`; hybrid logical clocks take it injected, 10); tests use `TestClock`, and every JVM test task — `desktopTest` (with `commonTest`), Android host tests, JVM islands, desktop modules and the server — runs in `de_DE` / `America/St_Johns` to flush out locale and offset bugs.
6. **Every bug fix starts with a failing test** reproducing it, in the lowest layer that can show it; a fix in merge behaviour also adds a conformance vector ([10 Conformance vectors](10-sync.md#conformance-vectors)).
7. **One rule set, three runtimes.** Code that the apps and the server share (`:sync:protocol`, common `:feeds`) is tested once in `commonTest`, and its conformance vectors are replayed against the server's own write path (`ServerConformanceTest`), so the clients and the server cannot drift ([D94](../PLAN.md#3-key-decisions)).

### Test pyramid

| Layer | What it covers | Modules | Runner | Trigger |
|---|---|---|---|---|
| Common (`commonTest`) | common feed models and writers with goldens, identity keys, URL rules, classifiers, selectors (`DesktopAssetSelector` included), `Redactor`, rule tables, ViewModels with fakes and Turbine, `:playback:core` (queue window, positions, played rule, sleep timer, chapters), download planner and state machine, the update-check logic, `:sync:protocol` (`ConformanceVectorTest`, `HlcTest`, `OrderKeyTest`), the sync engine against `InMemorySyncServer` (`SyncConvergenceTest`, 1,000 seeds), shared Compose UI behaviour with `runComposeUiTest` | every KMP module | desktop JVM (`desktopTest` task): `kotlin-test`, coroutines-test, Turbine, `org.jetbrains.compose.ui:ui-test` | every PR |
| Desktop JVM (`desktopTest`, JVM islands' and desktop modules' `test`) | Room DAOs, migrations, capture triggers and invalidation hygiene with `BundledSQLiteDriver` (02); MockWebServer suites for the Ktor fetch pipeline, the transfer core and the sync client; the feed and OPML corpus through kxml2, the jsoup sanitiser and the OkHttp interceptors (`:feeds:jvm`, `:core:network:okhttp`); `:youtube:engine` (from M9a) and the stdio host with `ReplayRH` on a host CPython (MD3); desktop goldens (`roborazzi-compose-desktop`, Linux only); `:desktopApp`, `:playback:engine`, `:playback:desktop` and `:desktop:system` with miniaudio's null back-end and fake shims (11); the E11 journey | KMP modules' `desktopTest`, the JVM islands, the desktop-only modules, `:desktopApp` | JVM (Linux x64 in PRs; the four desktop targets nightly) | every PR; nightly matrix |
| Android host (`androidHostTest`, `test` of the Android modules) | Android-only code: workers with the WorkManager test driver, Media3 player logic with `media3-test-utils`, `KeystoreCredentialStore` with a software cipher, notifications, the Android `IntentRouter` adapter, the `AndroidAppGraph` and no-engine graph tests, the Android golden set (Roborazzi) and ATF accessibility checks of shared composables, `LocalNetworkPermissionGateTest` | `:app`, `:playback:impl`, `:youtube:ytdlp`, and the `androidMain` code of KMP modules that opt in to host tests (01) | JVM + Robolectric 4.17, `sdk=36` | every PR |
| Server (`:sync:server:test`) | the black-box HTTP suite on Ktor's `testApplication` (authentication and linking, `/sync` merge statuses, caps, rate limits, SSE, jobs, migrations, `LogRedactionTest`), `ServerConformanceTest` (10) | `:sync:server` | JVM 21 | every PR |
| Instrumented (GMD `ci` group) | migrations and capture triggers on both Android drivers, the platform XmlPullParser corpus, Keystore, `MediaController` ↔ service, the E0–E9 journeys, external-mode YouTube UI (E8), the `:ytx` process (`YtxProcessStartTest`, `YtxIsolationTest`) | `:app` (`src/androidTest`), `:core:database` (`androidDeviceTest`), `:playback:impl` | Gradle Managed Devices API 26 + 36, `debug` build | push to `main`, PRs labelled `run-instrumented`, nightly |
| Instrumented nightly and release smoke | long-running and API-specific behaviour (30-min background auto-advance, 20-min UIDT beside playback, API 33 `dataSync`, API 37 hardening and 16 KB, `selftest` in `:ytx` on the 16 KB image); the published `release` build: E0 and E7 in-process ([Release-type test runs](#gradle-managed-devices)) and `:benchmark`'s release smoke journeys out of process | same, `:benchmark` | GMD `nightly` group + emulator-runner API 37 | nightly, release candidates |
| Out-of-process system | process death (E10) and the release smoke journeys driven by UI Automator from a separate test APK; Macrobenchmark dry runs | `:benchmark` | GMD (`aosp` and ATD images) | nightly (from M6b / M10) |
| Desktop native and packaged | the FFmpeg corpus and clock tests with the null back-end on every target, DPAPI and URL-scheme tests on Windows, MPRIS on Linux, the packaged-app smoke start of every format, `check-desktop-image.sh`, `check-runtime-sources.sh`, `codesign --verify --deep --strict` on macOS ([11 Testing](11-desktop.md#testing)) | `:playback:native`, `:playback:engine`, `:desktop:system`, `:youtube:ytdlp-desktop`, `:desktopApp` | `desktop-matrix` on `windows-2025`, `macos-15`, `ubuntu-24.04`, `ubuntu-24.04-arm`; the Linux x64 smoke start on every PR | every PR (Linux x64 smoke), nightly, every release |
| Cross-device | E12: an Android emulator, a desktop JVM and a server started from the built JAR converge ([10 Cross-device journey](10-sync.md#cross-device-journey)) | `:app`, `:desktopApp`, `:sync:server` | GMD-class emulator + JVM on one runner | nightly from MS2, release candidates |
| Python (shim) | `neutrodyne_ytx` against the bundled yt-dlp through `ReplayRH` for both host adapters: Chaquopy's (`shimTest`) and stdio (`shimTestStdio`, from MD3): recorded scenarios, error-code mapping, option names, `selftest` API probe (04) | `:youtube:ytdlp`, `:youtube:ytdlp-desktop` | pytest on host CPython 3.14 | every PR |
| Device and manual | Bluetooth, AVRCP, Android Auto DHU, OEM restrictions, budgets on the reference phone, laptops and Raspberry Pi, accessibility passes (TalkBack and Switch Access; VoiceOver and NVDA), `bmgr` spot checks, 11's OS-integration checklists, the update check's links with a manual install over the running app ([Device checklist (M11a)](#device-checklist-m11a)), the cross-device checklist (M11 AC14) | — | reference hardware + checklists | per milestone and [release](#release-checklist) |
| Engine canary | each new yt-dlp stable release, verified like the apps verify it, against both host adapters' recorded responses and API probe (04) | `:youtube:ytdlp`, `:youtube:ytdlp-desktop`, `:youtube:engine` | [`engine-canary.yml`](#engine-canaryyml), host CPython | every 6 h; green approves the release |
| Live canaries | ~30 public feeds (03), YouTube smoke through the shim (04), the shim against yt-dlp's nightly build (informational) | `:feeds:jvm`, `:youtube:ytdlp`, `:youtube:ytdlp-desktop` | JVM / host CPython with network | nightly, non-blocking |

**Placement rule:** device tests exist only in `:app` (`src/androidTest`: the journeys and everything that needs the whole app, a real process or a real platform service, including the former device tests of `:core:data` and `:download:impl` — Keystore, UIDT, `dataSync`, offline playback), `:core:database` (`androidDeviceTest`: 02's migration, driver and trigger tests, which need the schema assets and both Android drivers) and `:playback:impl` (06's `PlaybackServiceTest`), plus `:benchmark`, which is a test module. Other KMP modules have no device tests ([01 Convention plugins](01-foundation.md#convention-plugins); [Open questions](#open-questions) 26 records the `:core:database` exception). Shared Compose UI is tested for behaviour in `commonTest` and captured for goldens and ATF checks in `androidHostTest` and `desktopTest`. This keeps emulator boots per CI run bounded: each module's managed-device task boots its own emulator, and modules without device tests have their device-test component disabled ([Gradle Managed Devices](#gradle-managed-devices)).

### Test obligations per change

A PR is not mergeable without the tests in this table for the kind of code it touches (reviewers check; the PR template lists it).

| Change | Required in the same PR |
|---|---|
| Parser, writer or codec in `:feeds` or `:feeds:jvm` (feed, OPML, backup, import formats) | one golden fixture per new quirk; the format's [mutation robustness](#untrusted-input-robustness) providers updated; a hostile-input case if a new input format or limit is added |
| SQL, DAO, index | `desktopTest` DAO test on `TestDb` with the bundled driver; a `QueryPlanTest` row for key queries; an `InvalidationHygieneTest` row for anything a paged query observes ([02 Hygiene tests](02-data-model.md#hygiene-tests)); a device case when Android's framework driver may behave differently (SQLite 3.18 dialect, 02) |
| Schema change | version bump, exported JSON, migration and `MigrationNToMTest` with invariants on the desktop JVM and in the device suite ([02 Tests](02-data-model.md#tests)); a synced column also updates the capture triggers, their golden file and `SyncCaptureTest` (from MS0, PLAN 7.2) |
| Repository or use case | test against fakes of its collaborators in `commonTest`; if it implements an interface whose fake has behaviour, run the [contract test](#fake-contract-tests) against the real implementation in `desktopTest` |
| New `:core:domain` / `:*:api` / `:sync:api` interface | `Fake<Name>` in `:core:testing` `commonMain` (+ contract test if the fake has rules) |
| ViewModel | `commonTest` Turbine test of `uiState`: initial, content, empty, error, each user action, message acknowledgement |
| Android worker or job | WorkManager `TestDriver` / `TestListenableWorkerBuilder` test (`androidHostTest`): success, retry, stop at soft deadline (continuation enqueued), idempotent re-run |
| Desktop lane | `desktopTest` with `TestClock` and the runner's fake lanes: due selection, backoff, catch-up after a simulated sleep ([11 Background work](11-desktop.md#background-work)) |
| Composable screen or component | `runComposeUiTest` behaviour test in `commonTest` (labels, roles, keyboard reachability of every action); Roborazzi capture of every state 08 lists for it in the Android set (Robolectric, ATF checks on) and the desktop set |
| Network code | MockWebServer test (`desktopTest`) including every response code its owning document's table names |
| Sync merge rule, field kind or episode-state rule (`:sync:protocol`) | a conformance vector (replayed by `ConformanceVectorTest` and `ServerConformanceTest`) and, for a new operation type, an operation in the convergence generator ([10 Testing](10-sync.md#testing)) |
| Server route, store or job | a black-box case on Ktor's test host for every status, error code and cap it implements; `LogRedactionTest` stays green |
| Desktop native code (`ndmedia`, FFM bindings, OS shims) | a `desktopTest` case with the null back-end or a fake shim; a row in 11's OS-integration checklists when real hardware is needed; the PR dispatches the nightly `desktop-matrix` on its branch |
| Python shim (`neutrodyne_ytx`) | a `shimTest` and a `shimTestStdio` case through `ReplayRH` against the bundled yt-dlp; requests that changed are re-recorded ([04 Recorded responses](04-youtube.md#recorded-responses)), never patched into the recording |
| Code that verifies or activates code, validates release data or publishes releases (04's engine updates, `UpdateManifestParser`, `DesktopAssetSelector`, `release.yml` and its scripts) | a negative test for every rejection reason it implements (each must leave the active engine, or the last known update state, untouched); for `release.yml` and its scripts, `check-update-json.sh` run on the changed output in the PR |
| Playback | Android: `media3-test-utils` test (Robolectric) and an instrumented session test if it touches the service lifecycle; desktop: an engine or controller test with the null back-end (11); shared rules: `commonTest` in `:playback:core` |
| Platform-dependent behaviour (FGS, UIDT, audio focus/hardening, backup, notification permission, OS media sessions, power, tray, login items) | nightly instrumented test, nightly `desktop-matrix` test **or** a row in the owning document's manual checklist, named in the PR |
| New user journey in the [E2E catalogue](#end-to-end-journeys) | the journey test |
| New user-visible string | a Compose resource in the module's `composeResources/values/strings.xml` (Android-only labels in `res/`, [D83](../PLAN.md#3-key-decisions)); plurals as `<plurals>`; no concatenation ([Localisation](#string-conventions)) |
| Bug fix | regression test that fails before the fix |

### End-to-end journeys

E0–E9 run on GMD (`:app/src/androidTest`) against the **real** `NeutrodyneApplication` and its Metro `AndroidAppGraph` (no test graph) on the `debug` build (`ch.lkmc.neutrodyne.debug`, the tested build type, [01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)); E0 and E7 also run in-process against the published `release` build type ([Release-type test runs](#gradle-managed-devices)); E10 runs out of process in `:benchmark` ([Out-of-process system tests](#out-of-process-system-tests)); E11 runs on the desktop JVM; E12 spans an emulator, a desktop JVM and a server. On Android, network comes from an on-device MockWebServer bound to `127.0.0.1` (cleartext is allowed by [D28](../PLAN.md#3-key-decisions)'s network security config; `LocalNetworkGuardDns` passes loopback). Data enters through the UI, public intents or `TestSeeder`. Android journeys use `createAndroidComposeRule<MainActivity>()` with `enableAccessibilityChecks()`, so every screen a journey visits is also checked on a device ([N4](../PLAN.md#22-non-functional-requirements)), and follow the [ATD rule](#gradle-managed-devices): no notification shade, lock screen, launcher or back gesture.

| ID | Test class | Journey | Asserts | From | Devices |
|---|---|---|---|---|---|
| E0 | `SmokeTest` | launch | five labelled destinations, rotation, dark-mode switch, back from Settings with `pressBack()`, About shows version and ABI, Licences non-empty (PLAN M0 AC4–5). The predictive-back animation of M0 AC4 is checked by hand on a gesture-navigation device (ATD images have no SystemUI, so no back gesture) and recorded in the M0 release issue | M0a | `ci`, API 37; the `release` build in `release-build-smoke` and `api37-16k` |
| E1 | `SubscribeJourneyTest` | M1: Library empty state → "Add by URL" → type `http://127.0.0.1:{port}/feeds/rss2-minimal.xml` → Subscribe → Library → Podcast → Episode. From M7 a second case enters through `neutrodyne://subscribe?url=…` (the VIEW filters ship in M7, [03 Deep links and share targets](03-feeds-and-discovery.md#deep-links-and-share-targets)) | tile with cover or monogram; paged episodes; show notes rendered; refresh via pull-to-refresh hits the server with `If-None-Match` | M1 (deep link M7) | `ci` |
| E2 | `GroupFeedJourneyTest` | create group "tech" → add podcast → Feeds tab "tech" | episodes newest first; podcast in "tech" and "news" appears in both and once in All (M2 AC4); `ActivityScenario.recreate()` keeps the selected tab | M2 | `ci` |
| E3 | `PlayJourneyTest` | play from a group feed → mini player → background the app by starting the test APK's `BackgroundStandInActivity` (ATD has no launcher) → pause through the media notification: find it with `NotificationManager.getActiveNotifications()` and fire the pause action's `PendingIntent` → reopen | media notification on channel `playback`; `MediaController.mediaMetadata.artworkUri` is `content://…artwork/…`; position persisted (`episode_position` > 0 after pause); "Play group" order (M4 AC6) | M4 | `ci` |
| E4 | `DownloadJourneyTest` | download a throttled 5 MB enclosure → progress → complete → stop the server → play | file under `Android/data/…/Podcasts/`; plays with the server down (M6 AC6) | M6 | `ci` |
| E5 | `ImportJourneyTest` | VIEW a `content://` OPML (provider in the test APK) via `ExternalImportActivity` → preview → confirm | 20 pending tiles within 2 s; all fetched; report shows one `NOT_A_FEED`; zero `download` rows | M3 | `ci` |
| E6 | `BackupRestoreJourneyTest` | seed state A → create backup → mutate (delete group, mark played) → Replace restore | state A restored: groups, memberships, played, positions, Up next (M3 AC5) | M3 | `ci` |
| E7 | `YouTubeReleaseSmokeTest` | seeded YouTube podcast (`TestSeeder`) → play → download | on the debug APK and on the published `release` APK (R8, as published), where R8 must have kept the classes Python reaches through Chaquopy (`PyHttp`) and the test hook `YtxTestHooks`: one resolve and one chunked download through `:ytx` with `ReplayRH` + a MockWebServer googlevideo stand-in ([04 Testing](04-youtube.md#testing), PLAN M9 AC9) | M9 (M9a) | nightly `instrumented-full` (debug), `release-build-smoke` (release, API 36) and `api37-16k` (both); `youtube-smoke` before hotfixes |
| E8 | `ExternalYouTubeModeTest` (04's) | seeded YouTube rows in external mode (every build until M9a; from M9a the test first sets `youtube.engine_enabled = false`) | "Watch on YouTube" fires `ACTION_VIEW` (captured with `Instrumentation.ActivityMonitor`); no queue/download actions; "Play group" skips them; Settings › YouTube shows the external reason (PLAN M8 AC5, M9 AC8) | M8 | `ci` |
| E9 | `FirstLaunchRestoreTest` | snapshot file + empty DB | 05's assertions ([05 Testing](05-groups-opml-backup.md#testing)) | M3 | `ci` |
| E10 | `ProcessDeathResumeTest` | kill the app mid-download from `:benchmark` | 07's assertions ([07 Instrumented and device tests](07-downloads.md#instrumented-and-device-tests)) | M6b | nightly `system-tests` |
| E11 | `DesktopSmokeJourneyTest` | desktop: start the headless app graph under `runComposeUiTest` → open the five destinations → subscribe to a recorded feed through the add sheet (MockWebServer) → open the podcast → play a local file with miniaudio's null back-end for 2 s → pause → quit ([11 E11 desktop journey](11-desktop.md#e11-desktop-journey)) | five labelled destinations; podcast and episodes stored; `episode_position` > 0 after pause; clean shutdown with no crash file | M0b (destinations), M1a (subscribe), MD1a (play) | `:desktopApp`'s JVM tests on Linux x64 in `unit`; every desktop target in `desktop-matrix` |
| E12 | `CrossDeviceSyncTest` | an Android emulator (API 36, `debug` build), a desktop JVM (headless `DesktopAppGraph`) and a server started from the built fat JAR on the runner, reached by the emulator through `adb reverse tcp:8787 tcp:8787`; seeded random offline edits on both devices, then sync ([10 Cross-device journey](10-sync.md#cross-device-journey)) | both devices and the server hold identical synced state (canonical JSON of the synced collections, compared by the host test); a desktop pause resumes on Android within 1 s through "Continue on this device" (R8.5); unsubscribing 11 of 50 podcasts is held (MS2 AC4); propagation with both apps in the foreground ≤ 10 s (PB31, recorded as a trend) | MS2 (handoff and PB31 from MS3) | nightly `sync-convergence`; release candidates (M11 AC14) |

E7 guards itself with `assumeTrue(BuildInfo.youTubeEngineBundled)`, so it skips on the no-engine build and on 32-bit images; E8 runs everywhere (its desktop variant runs in `:desktopApp`'s JVM tests, 04). `PlaybackServiceTest` (06), `UidtDownloadTest` and `DataSyncWorkerTest` (07) and the migration and trigger device tests (02) are not journeys but run in the same instrumented jobs; their devices are listed in [Gradle Managed Devices](#gradle-managed-devices). E12's host test (`:desktopApp`'s `test` source set, excluded from `unit` by its `CrossDevice` tag) starts the server JAR, creates an account and an invite through the CLI, runs the Android half (`CrossDeviceSyncAndroidTest` in `:app/src/androidTest`) phase by phase with `adb shell am instrument -e seed … -e phase …`, drives the desktop half in its own JVM and compares the three states; Unverified: that one runner holds the emulator, the server and the desktop JVM within the job's time budget (MS2 check; fallback: the Android half and the desktop half run as two independent suites against one server and the comparison reads the server's records).

**E7 seam:** the engine's HTTP goes through `NeutrodyneOkHttpRH` inside `:ytx`, which an androidTest APK cannot reach with a graph override — and a test graph would not exist in the R8-minified `release` APK anyway. When the instrumentation argument `ytxReplay` is set (`-Pandroid.testInstrumentationRunnerArguments.ytxReplay=true` in `instrumented-full`, `release-build-smoke`, `api37-16k` and `youtube-smoke`), E7 copies 04's `ReplayRH` and one recorded scenario from its androidTest assets into `noBackupFilesDir/ytx-test/` and sets `YtxTestHooks.replayDir` (a `@VisibleForTesting` field of `:youtube:ytdlp`, kept in the release build by its consumer rule, [01 Release build and baseline profiles](01-foundation.md#release-build-and-baseline-profiles); [04 Process and lifecycle](04-youtube.md#process-and-lifecycle) owns the class) before the first bind; `YtDlpClient` passes the directory to `YtxService` in the bind `Intent`, and `YtxPython` registers the replay handler above `NeutrodyneOkHttpRH`'s preference. The recorded player response is rewritten so stream URLs point at the test's MockWebServer. Only code running as the app's UID can set the hook (`YtxService` is not exported): an instrumentation inside the app's process, which needs a test APK signed with the app's certificate. That certificate's key is public ([Committed keystore](#committed-keystore)), so the hook adds no exposure beyond risk P10, which already lets anyone sign a replacement app; production code never sets it, so it stays inert in the published APK (PLAN 7.2). Unverified: that R8 keeps the field with the consumer rule alone (S19 and M9a check; fallback: the same hook read from a marker file in `noBackupFilesDir/ytx-test/` that only the instrumentation writes).

### Untrusted-input robustness

Serves N9 and the client side of N13. Two complementary layers:

1. **Hostile inputs with caps** — owned by the format documents: [03 Golden corpus](03-feeds-and-discovery.md#golden-corpus-feedssrctestresourcesfeeds) (entity DOCTYPE, deep nesting, oversized text), 05's `HostileInputTest` (billion laughs, 10k nesting, 100k outlines, 10 MB attribute, zip bomb, zip-slip; PLAN M3 AC3) and 10's caps on both sides of the sync protocol (oversize pages and records, gzip bombs, JSON depth, string and URL lengths, URL schemes; [10 Input caps on both sides](10-sync.md#input-caps-on-both-sides)), whose server half runs in the black-box suite.
2. **`MutationRobustnessTest`** (09, TestParameterInjector): in `:feeds:jvm` (JVM `test`) for every committed fixture of `FeedParser`, `OpmlReader`, `BackupCodec`, `NewPipeSubscriptions`, `LibreTubeBackupParser` and `TakeoutSubscriptionsParser`; in `:youtube:api` (`desktopTest`) for 200 seeded random strings of `YouTubeUrlClassifier`; in `:sync:protocol` (`desktopTest`, from MS0) for the committed `SyncResponse`, `ChangesPage` and `RecordDto` fixtures through the client-side decoder (`SyncJson` with `ProtocolLimits`). Apply seeded mutations — truncate at 10 offsets, flip 1–16 random bytes, duplicate a random 1 KB slice, insert `<!DOCTYPE x [<!ENTITY e "...">]>` (XML formats), replace the declared encoding, insert NUL and lone surrogates, nest a JSON value 100 levels deep (JSON formats). Assert: the call returns its declared result type (`ParseResult`/`Outcome` failure is fine), throws nothing except `CancellationException`, finishes in < 2 s, and stays within a 128 MB heap (enough for the largest committed fixture of ≤ 1 MB plus the test worker; an entity, nesting or decompression blow-up exceeds it at once). The class runs only in a dedicated `Test` task `mutationTest` (registered by `configureNeutrodyneTestTasks()` in the modules that own the class, from the `test` or `desktopTest` task's `testClassesDirs` and `classpath`, `includeTestsMatching` for `*MutationRobustnessTest` and `*HostileInputTest` — 05's `HostileInputTest` needs the same heap cap — `maxHeapSize = "128m"`, wired into `check`); the regular test task excludes both. PR runs use 20 mutations per fixture (seed = fixture name hash); nightly runs 1,000 per fixture (`-PmutationIterations=1000`) and prints the failing seed.

### Flakiness policy

- Common, desktop JVM, host and server tests must be deterministic; a flaky JVM test is a bug fixed or reverted within 24 h. Seeded generators (mutations, convergence) print their seed, and a failing seed becomes a regression test.
- An instrumented test that fails intermittently is annotated `@androidx.test.filters.FlakyTest` with a comment linking its issue within 24 h; blocking runs exclude `FlakyTest`, the nightly run includes it. A desktop-matrix test that fails intermittently on one target gets the JUnit category `Flaky` (`:core:testing`), excluded from `desktop-matrix`'s blocking step and run in a non-blocking step. Either must be fixed or deleted within 14 days.
- No automatic test retries in CI (retries hide real races such as position-loss bugs). Managed-device infrastructure failures (emulator boot timeout) may be re-run manually once. The one automatic retry is infrastructure, not tests: `release.yml` runs each desktop and image job's build steps a second time when the first attempt fails ([release.yml](#releaseyml), risk P15).

### Naming and style rules

- Test classes `<Subject>Test`; contract bases `<Interface>Contract`; journeys `<Name>JourneyTest`.
- Back-ticked sentence names only in JVM-only test source sets (`desktopTest`, `androidHostTest`, a JVM island's, a desktop module's or the server's `test`). `commonTest` uses camelCase, so a later iOS target stays possible ([D81](../PLAN.md#3-key-decisions); Unverified which characters Kotlin/Native accepts in back-ticked names), and so do `src/androidTest` and `:benchmark`: D8 rejects spaces in identifiers below DEX 040 (API 30), and minSdk is 26.
- Assertions: `kotlin.test` in `commonTest` (Truth is JVM-only); Truth in every JVM-only and device source set; no Hamcrest.
- Fixtures under `src/commonTest/resources/<area>/` (common and desktop tests; `Goldens.fixture` resolves them, [Shared helpers](#shared-helpers)), `src/desktopTest/resources/<area>/`, `src/test/resources/<area>/` (JVM islands, desktop modules, the server, Android host tests of the Android modules) or `src/androidTest/assets/<area>/` (instrumented); never read from `build/`.

---

## Test infrastructure

Serves N1, N11, N13. Delivered in M0a (configuration, base helpers), M0b (desktop UI tests, the server's test host), grown per milestone. Versions: [01 Toolchain and versions](01-foundation.md#toolchain-and-versions).

### Gradle test configuration

Every convention plugin whose modules have tests — `neutrodyne.kmp.library` (and so `kmp.compose` and `kmp.feature`), `neutrodyne.jvm.island`, `neutrodyne.desktop.library`, `neutrodyne.desktop.application`, `neutrodyne.server.application`, and `neutrodyne.android.testing` for the Android modules — calls the shared `configureNeutrodyneTestTasks()` ([01 Convention plugins](01-foundation.md#convention-plugins)); `neutrodyne.android.testing` adds the Android-only parts.

```kotlin
// build-logic: shared by every module with tests (KMP desktopTest and Android host tests, JVM islands, desktop, server)
internal fun Project.configureNeutrodyneTestTasks() {
    tasks.withType<Test>().configureEach {
        systemProperty("user.timezone", "America/St_Johns")          // UTC-3:30, DST, non-integral offset
        jvmArgs("-Duser.language=de", "-Duser.country=DE", "-Xshare:off", "-XX:+EnableDynamicAgentLoading")
        if (pluginManager.hasPlugin("neutrodyne.desktop.library") || path == ":desktopApp")
            jvmArgs("--enable-native-access=ALL-UNNAMED")                 // FFM in :playback:native and :desktop:system (01)
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        maxHeapSize = if (name == "mutationTest") "128m" else "2g"   // set here, not in register {}: no ordering doubt
        val update = providers.gradleProperty("updateGoldens").isPresent
        systemProperty("neutrodyne.updateGoldens", update)
        systemProperty("neutrodyne.moduleDir", layout.projectDirectory.asFile.absolutePath)
        systemProperty("neutrodyne.rootDir", rootProject.layout.projectDirectory.asFile.absolutePath)
        systemProperty("neutrodyne.screenshotTier", providers.gradleProperty("screenshotTier").getOrElse("pr"))
        systemProperty("neutrodyne.mutationIterations", providers.gradleProperty("mutationIterations").getOrElse("20"))
        systemProperty("neutrodyne.syncSeeds", providers.gradleProperty("syncSeeds").getOrElse("1000"))
        if (update) outputs.upToDateWhen { false }
        if (!providers.gradleProperty("crossDevice").isPresent) (options as? JUnitOptions)?.excludeCategories("ch.lkmc.neutrodyne.core.testing.CrossDevice")
        testLogging { events("failed"); exceptionFormat = TestExceptionFormat.FULL }
    }
    val owner = mapOf(":feeds:jvm" to "test", ":youtube:api" to "desktopTest", ":sync:protocol" to "desktopTest")[path] ?: return
    val base = tasks.named<Test>(owner)                               // modules that own MutationRobustnessTest
    val capped = listOf("*MutationRobustnessTest", "*HostileInputTest")   // HostileInputTest: 05's caps test
    base.configure { capped.forEach(filter::excludeTestsMatching) }
    val mutation = tasks.register<Test>("mutationTest") {
        testClassesDirs = base.get().testClassesDirs; classpath = base.get().classpath
        useJUnit(); capped.forEach(filter::includeTestsMatching)
    }
    tasks.named("check") { dependsOn(mutation) }
}
```

2026-10-06, S12: the checked-in `configureNeutrodyneTestTasks` also passes Robolectric's required JDK-21 module flags on every `Test` task — `--add-opens` for `java.base`'s internals plus `--add-exports java.base/jdk.internal.access=ALL-UNNAMED`; without them every host test dies with `IllegalAccessException` (Robolectric's instrumented classes reach into `SharedSecrets`). Harmless for non-Robolectric tasks and cheaper than a per-module opt-in.

```kotlin
// build-logic: neutrodyne.android.testing (:app, :playback:impl, :youtube:ytdlp). Catalog access per 01 catalog rule 5 (no
// type-safe accessors in plugin classes); okhttp-bom is already on test/androidTest configurations (01 catalog rule 4).
internal fun Project.configureAndroidTesting(ext: CommonExtension) {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    ext.defaultConfig.testInstrumentationRunner =
        if (path == ":app") "ch.lkmc.neutrodyne.NeutrodyneTestRunner"   // keeps ACRA out of release-type runs (below)
        else "androidx.test.runner.AndroidJUnitRunner"
    ext.defaultConfig.testInstrumentationRunnerArguments["clearPackageData"] = "true"
    if (providers.gradleProperty("neutrodyne.testScope").getOrElse("ci") == "ci")   // nightly/release scopes run everything
        ext.defaultConfig.testInstrumentationRunnerArguments["notAnnotation"] =
            "ch.lkmc.neutrodyne.core.testing.Nightly,androidx.test.filters.FlakyTest"
    ext.testOptions.unitTests.isIncludeAndroidResources = true   // Robolectric + Roborazzi need merged resources
    ext.testOptions.unitTests.isReturnDefaultValues = false      // unmocked android.* calls fail loudly
    ext.testOptions.animationsDisabled = true
    ext.testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"
    configureManagedDevices(ext.testOptions.managedDevices)      // see Gradle Managed Devices
    disableEmptyDeviceTests()                                    // see Gradle Managed Devices
    configureNeutrodyneTestTasks()
    dependencies {
        "testImplementation"(libs.findBundle("jvm-test").get())   // junit4, truth, turbine, coroutines-test, TPI
        "testImplementation"(libs.findLibrary("robolectric").get())
        "testImplementation"(project(":core:testing"))
        "androidTestImplementation"(libs.findLibrary("androidx-test-runner").get())
        "androidTestImplementation"(libs.findLibrary("androidx-test-ext-junit").get())
        "androidTestImplementation"(libs.findLibrary("truth").get())
        "androidTestImplementation"(project(":core:testing"))
        "androidTestUtil"(libs.findLibrary("androidx-test-orchestrator").get())
    }
    val robolectric = libs.findVersion("robolectric").get().requiredVersion
    configurations.configureEach { resolutionStrategy.eachDependency {   // media3-test-utils pulls Robolectric 4.16
        if (requested.group == "org.robolectric" && !requested.name.startsWith("android-all")) useVersion(robolectric)
    } }
}
```

- **Implementation notes (M0a.1, 2026-10-06).** The `CrossDevice` category is excluded only in `:desktopApp`'s test tasks, the one place E12's host test lives: excluding a category whose class is not on a module's test classpath fails the test executor. `CrossDevice` and `Flaky` live in `:core:testing`'s `commonMain` (not only `desktopMain`), so Android host tests can load them too. The Robolectric version pin exempts `nativeruntime-dist-compat` as well as `android-all*`, because both have their own version lines.
- **KMP modules.** `neutrodyne.kmp.library` gives `commonTest` the `common-test` bundle (`kotlin-test`, coroutines-test, Turbine) and `:core:testing`, and `desktopTest` the `jvm-test` bundle (01). Android host tests exist only in modules that opt in (`withHostTest { }` on the android target; Robolectric with the same `isIncludeAndroidResources`/`isReturnDefaultValues` settings; source set `androidHostTest`; the host-test task is **`testAndroidHostTest`** — S4 confirmed 2026-10-06, `:core:database` and `:core:network` opt in); device tests only in `:core:database` (`withDeviceTestBuilder {}` — DSL verified in AGP 9.4.1's `gradle-api` jar alongside `withDeviceTest {}`, both on `KotlinMultiplatformAndroidLibraryExtension`; the compilation exposes `instrumentationRunner`, `managedDevices` and `execution`, so runner `AndroidJUnitRunner` and the orchestrator sit there, 2026-10-07; source set `androidDeviceTest`; the managed devices below). Still Unverified: the exact Gradle task name that runs a KMP module's device test (the GMD task) — recorded in 01's verification log when `:core:database`'s device tests land in M1a.
- **Task selection in CI:** `allTests` runs every KMP module's `desktopTest` (which includes `commonTest`) and opted-in Android host tests; `test` runs the JVM islands, the desktop-only modules, `:desktopApp`, `:sync:server` and the Android modules' unit tests (AGP 9 creates unit tests only for the tested build type, `debug`, so `test` = `testDebugUnitTest` there, [01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)). The nightly `dev-tools-build` and its `-Pneutrodyne.devTools` unit-test run are retired (2026-10-05): developer tooling lives in the `debug` build type again ([01 Debug build type](01-foundation.md#debug-build-type)).
- `src/androidHostTest/resources/robolectric.properties` (KMP) and `src/test/resources/robolectric.properties` (Android modules): `sdk=36` (one `android-all-instrumented` jar, ~100 MB, cached in CI). Bump to 37 only together with a full Roborazzi re-record. Robolectric 4.16+ needs JDK 21 for SDK 36; CI runs Gradle on Temurin 21 and provides the JDK 25 (desktop) and 21 (server) toolchains with `actions/setup-java` ([ci.yml](#ciyml)).
- **Desktop UI tests need no display:** `runComposeUiTest` and `runDesktopComposeUiTest` render offscreen through Skiko; on `ubuntu-24.04-arm` they still need `libegl1` installed (skiko-linux-arm64's `NEEDED` list includes `libEGL.so.1`, which that image lacks — nightly 37831500506; its other entries already resolve); otherwise Unverified on the remaining runner images (M0b check; fallback: `xvfb-run` around the Linux `unit` and `desktop-matrix` test steps, as the packaged smoke start already uses).
- `:youtube:ytdlp:shimTest` (Chaquopy host adapter) and, from MD3, `:youtube:ytdlp-desktop:shimTestStdio` (stdio host adapter) are 01's tasks with 04's content and not JVM `Test` tasks: they run pytest on a host CPython 3.14 (the minor version both hosts package, fallback 3.13 per S7 for Android), with `ReplayRH` and the bundled yt-dlp on `sys.path`. CI provides the interpreter with `actions/setup-python` ([ci.yml](#ciyml)); locally a missing interpreter fails the task with a message, never silently skips.
- Unverified: Robolectric replaces the JVM default locale with the qualifier locale (`en-rUS`), which would neutralise `-Duser.language=de` in Robolectric tests; tests that guard wire formats against locale bugs therefore live in `commonTest`/`desktopTest` or use `@Config(qualifiers = "de-rDE")` explicitly (M0a check).

### Shared helpers

`:core:testing` is a KMP module (`neutrodyne.kmp.library`; [01 Module layout](01-foundation.md#module-layout)), so `commonTest`, `desktopTest`, Android host and device tests, the JVM islands' tests (its JVM variant, 01) and the server's tests import the same helpers from package `ch.lkmc.neutrodyne.core.testing`. Common helpers live in its `commonMain`; JUnit 4 helpers exist twice, in `desktopMain` and `androidMain`, because the default hierarchy has no shared JVM-and-Android source set ([01 Source sets and JVM islands](01-foundation.md#source-sets-and-jvm-islands)). The former `java-test-fixtures` of `:core:common`, `:core:database` and `:core:navigation` are gone: Gradle test fixtures do not apply to KMP modules, so their helpers moved into `:core:testing` ([Open questions](#open-questions) 27).

```kotlin
package ch.lkmc.neutrodyne.core.testing

/** Deterministic Clock (01). Optionally tied to a coroutine test scheduler's virtual time. commonMain. */
class TestClock(var nowMs: Long = DEFAULT_NOW, var elapsedMs: Long = 0L) : Clock {
    override fun now(): Long = nowMs
    override fun elapsedRealtime(): Long = elapsedMs
    fun advanceBy(d: Duration) { nowMs += d.inWholeMilliseconds; elapsedMs += d.inWholeMilliseconds }
    companion object {
        const val DEFAULT_NOW = 1_791_072_000_000L                         // 2026-10-04T00:00:00Z
        fun from(s: TestCoroutineScheduler, start: Long = DEFAULT_NOW) = object : Clock {
            override fun now() = start + s.currentTime
            override fun elapsedRealtime() = s.currentTime
        }
    }
}

/** commonMain: base class for tests of code that uses Dispatchers.Main (ViewModels); kotlin-test hooks, no JUnit rule. */
abstract class MainDispatcherTest(val dispatcher: TestDispatcher = StandardTestDispatcher()) {
    @BeforeTest fun setMainDispatcher() = Dispatchers.setMain(dispatcher)
    @AfterTest fun resetMainDispatcher() = Dispatchers.resetMain()
}
// 2026-10-06: @BeforeTest/@AfterTest are ch.lkmc.neutrodyne.core.testing's own expect annotations,
// actual typealiases to org.junit.Before/After — kotlin.test's names live in kotlin-test-junit,
// which KMP puts on test compilations only, never on a module's commonMain.

/** desktopMain and androidMain (identical copies): the JUnit 4 form for JVM-only and device tests. */
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

object Goldens {                                // commonMain, on Okio's FileSystem.SYSTEM (both targets are JVMs)
    /** Compares [actual] with <module>/src/<sourceSet>/resources/[path]; rewrites it when -PupdateGoldens is set (refused when CI=true). */
    fun assertMatches(actual: String, path: String, sourceSet: String = "commonTest")
    /** Pretty-printed JSON with object keys sorted recursively and explicit nulls dropped. */
    fun canonicalJson(element: JsonElement): String
    fun fixture(path: String, sourceSet: String = "commonTest"): Path   // resolves against neutrodyne.moduleDir
}
```

- `Goldens` reads the module directory through `testModuleDir()`, an `expect` function whose two actuals read the `neutrodyne.moduleDir` system property ([Gradle test configuration](#gradle-test-configuration)); device tests never use `Goldens` (they read `androidTest` assets). Unverified: that Okio's `FileSystem.SYSTEM` is visible in a `commonMain` shared only by `android` and `jvm("desktop")` (M0a check; fallback: an `expect` accessor next to `testModuleDir()`).
- Golden file naming follows the owning document (`<name>.golden.json` in 03, `*.expected.json` in 05, `sync/protocol/vectors/*.json` in 10); `Goldens` does not impose a suffix.
- The switch: `./gradlew :feeds:jvm:test -PupdateGoldens` locally, then review the diff like code. `Goldens` throws if `CI=true` and the switch is on, so CI can never "fix" a failure by rewriting.
- `runTest` reuses the scheduler of a `TestDispatcher` installed as Main (coroutines 1.11), so ViewModel tests extend `MainDispatcherTest` and use `runTest {}` without passing dispatchers twice.

### `:core:testing` inventory

Package `ch.lkmc.neutrodyne.core.testing`; dependencies per rule 9 (`:core:{domain, model, common}`, `:*:api` including `:sync:api`, `:sync:protocol`) plus the test-support edges to `:core:database` and `:core:navigation` this document adds ([Open questions](#open-questions) 27); `api` exports kotlin-test, coroutines-test, Turbine and coil-test in `commonMain`, and JUnit 4 and Truth in `desktopMain`/`androidMain` ([01 Module layout](01-foundation.md#module-layout)).

| Item | Fakes / provides | Interface owner | From |
|---|---|---|---|
| `TestClock`, `MainDispatcherTest`, `Goldens` (common); `MainDispatcherRule` (desktop and Android) | — | 09 | M0a |
| `FakeNetworkMonitor` | `NetworkMonitor` (`setStatus(...)`) | 01 | M0a |
| `FakeSettingsRepository` | `SettingsRepository` (in-memory map per file, `synced` flags honoured) | 01 | M0a |
| `FakeSecretStore` | `SecretStore` (in-memory, per origin) | 01, 03 | M1b |
| `FakeCrashReporter`, `FakeCrashContext` | [`CrashReporter`, `CrashContext`](#crashreporter-and-crashcontext) | 09 | M0a |
| `FakePodcastRepository`, `FakeEpisodeRepository`, `FakeRefreshController`, `FakeAddPodcastResolver`, `FakeSubscribeUseCase`, `FakeIngestionEvents` | 03 interfaces | 03 | M1a |
| `FakeSearchRepository` | `SearchRepository` | 03 | M7 |
| `FakeFeedRepository`, `FakeGroupRepository`, `FakeEffectiveSettingsResolver`, `FakeScopeSettingsRepository`, `FakePlayContextResolver` | 05 interfaces | 05 | M2 (resolver M2/M4) |
| `FakeImportRepository`, `FakeBackupRepository`, `FakeExportRepository` | 05 interfaces | 05 | M3 |
| `FakeEpisodeLiveStateSource`, `FakeArtworkRepository` | 08 interfaces | 08 | M2, M4 |
| `FakePlaybackController`, `FakePlaybackStateSource`, `FakeQueueRepository`, `FakeChapterRepository`, `FakePlaybackMaintenance` | 06 interfaces (the common Player API of `:playback:api` included) | 06 | M4 (chapters M5) |
| `FakeDownloadController`, `FakeLocalMediaIndex`, `FakeDownloadProgressSource` | 07 interfaces | 07 | M6a |
| `FakeYouTubeChannelResolver`, `FakeYouTubeStreamResolver`, `FakeYouTubeEnricher`, `FakeYouTubeChannelRepository`, `FakeYouTubeHealth`, `FakeYouTubeChannelSearch`, `FakeExtractorChannelLookup`, `FakeYouTubeAvailabilityRecorder`, `FakeYouTubeCapabilitiesSource`, `FakeYouTubeEngine`, `testCapabilities(external: ExternalReason? = null)` (all five capabilities true when null) | 04 interfaces | 04 | M2 (capabilities), M8, M9a |
| `FakeSyncController`, `FakePlaybackSyncPort`, `FakePrePlaySync`, `FakeSyncIngestHook`, `InMemorySyncServer` (the protocol's server side in memory, on `:sync:protocol`'s `RecordMerger`) | 10 interfaces | 10 | MS0 (server), MS2 |
| `FakeAppUpdateChecker`, `FakeUpdateNotices` | [`AppUpdateChecker`, `UpdateNotices`](#modules-and-api) (`:core:domain`) | 09 | M11a |
| `FakeDiagnosticsRepository` | [`DiagnosticsRepository`](#diagnostics-api) | 09 | M11b |
| `TestDb` (`inMemory`, `file`, `linked`; platform builders in `desktopMain` and `androidMain`), `SeedDatabase`, `FeedFixture`, `MigrationInvariants`, `SqlEnumLiterals` (package `…core.testing.database`) | database helpers ([02 Testing](02-data-model.md#testing)) | 02 | M1a (`linked` MS0) |
| `TestSqliteDriverBindings` (`androidMain`; `AndroidSQLiteDriver()`, replaces `:core:database`'s `SqliteDriverBindings` in Robolectric graphs; from S4, 2026-10-06) | the test-side SQLite driver binding ([01 Test overrides](01-foundation.md#test-overrides)) | 01/02 | M0a.2 |
| `RecordingAppNavigator` (records `push`/`selectTab`/`pop`; package `…core.testing.navigation`) | `AppNavigator` | 01 | M3 |
| Data builders `podcast(…)`, `episode(…)`, `episodeRow(…)`, `group(…)`, `rowLive(…)` | `:core:model` instances with readable defaults | 09 | M1a |
| `fakeImageLoader(context)` | Coil `ImageLoader` with `FakeImageLoaderEngine` returning deterministic `ColorImage`s keyed by URL hash | 09 | M1a |
| `Nightly`, `ReleaseSmoke` (Android annotations, `androidMain`); `Flaky`, `CrossDevice` (JUnit categories, `desktopMain`); `ScreenshotTier`, `assumeTier` (common) | test selection | 09 | M0a, M2, MS2 |
| `*Contract` bases | [fake contract tests](#fake-contract-tests) | 09 | with each fake |

Rule: an interface added to `:core:domain`, `:*:api` or `:sync:api` by any document gets its `Fake<Name>` in the same PR. Fakes are backed by `MutableStateFlow`, expose test-only mutators (`emit…`, `failNext: <ErrorType>?`) and a call log (`calls: List<String>`), and never sleep.

**Not in `:core:testing`** (rule 9 forbids the dependencies, or the helper is one platform's):

| Helper | Location | Consumers |
|---|---|---|
| `RecordingRH`, `ReplayRH` (04's names; Python, never packaged), `FakeYtxTransport` (Kotlin; replaces `FakeYtDlpClient`, 04); recordings in `youtube/engine/src/test/resources/recorded/{scenario}/` | `:youtube:engine` test sources (04) | `shimTest`, `shimTestStdio`, `scripts/youtube/record-responses.sh`, the engine canary, `:youtube:engine`, `:youtube:ytdlp` and `:youtube:ytdlp-desktop` tests, `:app` `androidTest` (E7 copies `ReplayRH` and one scenario into its assets at build time) |
| Metro test graphs (`createDynamicGraph<AndroidAppGraph>(FakeBindings)`, test contributions with `replaces`) | `:app/src/test`, `:app/src/androidTest`, `:desktopApp/src/test` ([01 Test overrides](01-foundation.md#test-overrides)) | graph tests, the no-engine graph tests, E11 |
| `TestServer`, `TestSeeder`, `BackgroundStandInActivity`, `CrossDeviceSyncAndroidTest` | `:app/src/androidTest` | E2E journeys, E12's Android half |
| `SyncLoadTool` (50,000-record upload, S16), the server's test-host fixtures | `:sync:server` test sources (10) | server suite, PB30 |
| Desktop fakes of desktop-only interfaces (`AudioEngine`, `SystemMediaSession`, `PowerMonitor`, `IdleSleepInhibitor`, fake lanes) | the desktop modules' test sources (11) | 11's tests |

Unverified: that islands and the server resolve `:core:testing`'s desktop (JVM) variant from a plain `kotlin("jvm")` test classpath without attribute tweaks (M0a check with `:feeds:jvm`; fallback: an explicit `jvm` attribute request in `neutrodyne.jvm.island` and `neutrodyne.server.application`).

### Fake contract tests

A fake that encodes rules (ordering, uniqueness, state transitions) is verified against the same contract as the real implementation, so fakes cannot drift.

```kotlin
// :core:testing commonMain (kotlin-test is an api dependency)
abstract class GroupRepositoryContract {
    protected abstract fun subject(clock: TestClock): GroupRepository
    @Test fun nameKeyIsUniqueAcrossCaseAndNfc() = runTest {
        val repo = subject(TestClock())
        val tech = (repo.create(GroupDraft(name = "Tech")) as Outcome.Success).value
        assertEquals(Outcome.Failure(GroupError.NameTaken(tech)), repo.create(GroupDraft(name = "tech")))
        assertIs<Outcome.Success<*>>(repo.create(GroupDraft(name = "Café")))
        assertIs<Outcome.Failure<*>>(repo.create(GroupDraft(name = "Café")))
    }
    @Test fun movingAnUnknownGroupFails() = runTest {
        val repo = subject(TestClock())
        val a = (repo.create(GroupDraft(name = "a")) as Outcome.Success).value
        assertEquals(Outcome.Failure(GroupError.NotFound), repo.move(groupId = 999L, after = a))
    }
}
class FakeGroupRepositoryContractTest : GroupRepositoryContract() {             // :core:testing/src/commonTest
    override fun subject(clock: TestClock) = FakeGroupRepository(clock)
}
class GroupRepositoryImplContractTest : GroupRepositoryContract() {             // :core:data/src/desktopTest
    override fun subject(clock: TestClock) = GroupRepositoryImpl(TestDb.inMemory(), clock /* … */)
}
```

Contract bases exist for `GroupRepository`, `QueueRepository` (`orderKey` order), `SettingsRepository`, `SecretStore` (run against `DesktopSecretStore` in `desktopTest` and against the Keystore implementation in `:app`'s device suite, 03), `PodcastRepository` (subscribe/unsubscribe visibility), `EpisodeRepository` (played/favourite), `FeedRepository` (order and filters on a 20-episode fixture), `DownloadController` (state reported after request/pause/resume/cancel) and `SyncController` (link, unlink and status transitions against `InMemorySyncServer`, 10). Signatures follow the owning documents; the sketch shows the pattern only.

### ViewModel test template

```kotlin
class PodcastViewModelTest : MainDispatcherTest() {                  // commonTest
    private val podcasts = FakePodcastRepository()

    @Test fun showsThePodcastThenAMessageWhenEditingTheFeedUrlFails() = runTest {
        podcasts.emitDetail(podcastDetail(id = 7, title = "Tech Talk"))      // FakePodcastRepository test API
        val vm = PodcastViewModel(PodcastKey(7), podcasts)
        vm.uiState.test {                                       // stateIn(WhileSubscribed) starts on collection
            assertEquals(PodcastUiState.Loading, awaitItem())
            val ready = awaitItem() as PodcastUiState.Ready
            assertEquals("Tech Talk", ready.header.title)
            podcasts.failNext = AddPodcastError.NotAFeed
            vm.onEditFeedUrl("https://example.invalid/page.html")       // calls PodcastRepository.editFeedUrl
            assertEquals(1, (awaitItem() as PodcastUiState.Ready).messages.size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

Paged properties (`items: Flow<PagingData<T>>`) are asserted with `paging-testing`'s `asSnapshot()` (multiplatform). Turbine 1.x timeouts are wall-clock, so Room-backed flows on real threads also work inside `runTest` in `desktopTest`.

### Robolectric

- Only for Android-only code: `:app`, `:playback:impl`, `:youtube:ytdlp` and the `androidHostTest` source sets of KMP modules that opt in; runner `RobolectricTestRunner` (or `AndroidJUnit4`), SDK 36, `@GraphicsMode(GraphicsMode.Mode.NATIVE)` on screenshot tests.
- Graph tests in `:app/src/test`: Metro's `AndroidAppGraph` is built with test binding containers (`createDynamicGraph`, Unverified API until S8) and every `NavKey` installer, worker factory entry and `@IntoSet` contribution resolves; the bundled SQLite driver is replaced by `AndroidSQLiteDriver` ([01 Test overrides](01-foundation.md#test-overrides)). The same test runs with `-Pneutrodyne.youtubeEngine=false` in the nightly `no-engine-build`.
- `StateRestorationTester` for Android process-death-like restoration of Compose state; real process death is tested out of process (E10); `SavedState` round trips of `NavKey`s through `NavKeySerializers` run in `commonTest`.

### Room

- **Desktop JVM (the default):** `TestDb.inMemory()` and `TestDb.file(dir)` from `:core:testing` with `BundledSQLiteDriver`, whose `sqlite-bundled-jvm` natives load on the host — `linux_x64` in PRs (S10 go, 2026-10-06: SQLite **3.50.1**; the JNI library extracts as an `androidx_sqliteJni*` temp file under `java.io.tmpdir`), `windows_x64`, `osx_arm64` and `linux_arm64` in the nightly matrix ([sqlite-bundled-jvm 2.7.1](https://dl.google.com/android/maven2/androidx/sqlite/sqlite-bundled-jvm/maven-metadata.xml), spike S10). Migration tests use the JVM `MigrationTestHelper` with `core/database/schemas` as its schema directory (02).
- **Robolectric:** `AndroidSQLiteDriver` only for Android-specific integration (for example `DbMaintenanceWorker` with the WorkManager test driver); the bundled driver's Android `.so` files do not load on the host (`UnsatisfiedLinkError`, S4 2026-10-06). Robolectric's own native SQLite reports **3.44.3** (4.17, `sdk=36`; recorded, above the 3.18 baseline but not a proof of it). `MigrationTestHelper` does not run under Robolectric (no merged assets for a KMP host-test variant; S4) — migration tests live in `desktopTest` (JVM helper) and on GMD.
- **Device** (`:core:database`'s `androidDeviceTest`, GMD API 26 and 36): migration and trigger tests with **both** `BundledSQLiteDriver` and `AndroidSQLiteDriver` (the API 26 framework SQLite is the dialect floor, [02 Tests](02-data-model.md#tests)); schema assets from `core/database/schemas` added to the device-test assets (Unverified DSL for the Android-KMP device-test source set, M0a check).

### Network

- JVM (`desktopTest`, islands, desktop modules): `MockWebServerRule` from `mockwebserver3-junit4`; HTTPS cases with `okhttp-tls` `HeldCertificate`/`HandshakeCertificates`. The Ktor fetch pipeline runs on the OkHttp engine, so the same server covers Ktor and OkHttp paths (S12).
- Sync: `InMemorySyncServer` (`:core:testing`) for client logic in `commonTest`; the real server on Ktor's `testApplication` ([Ktor server testing](https://ktor.io/docs/server-testing.html)) for the server suite; E12 starts the built fat JAR on loopback.
- On device: `TestServer` (JUnit rule in `:app/src/androidTest`) wraps `mockwebserver3.MockWebServer` bound to `127.0.0.1` with a `FixtureDispatcher` serving `androidTest/assets/e2e/**`; `{{base}}` placeholders in fixture feeds are replaced with the server URL so enclosures and artwork point back to it; `throttleBody` simulates slow downloads; `shutdown()` simulates offline. Certificate Transparency (Android 17) cannot be exercised with a local CA and is covered by 01's `NetErrorClassifier` unit test only.
- No test-specific network security config: loopback cleartext is allowed by D28 in every build, and the `debug` build's `<debug-overrides>` (user CAs, [01 Debug build type](01-foundation.md#debug-build-type)) are not needed by tests.

### Media3

`media3-test-utils` and `media3-test-utils-robolectric` (`testImplementation` in `:playback:impl` only): `TestExoPlayerBuilder` with `FakeClock`, `FakeMediaSource`/`FakeTimeline`, `TestPlayerRunHelper.advance(player).untilState(…)` / `play(player).untilPositionAtLeast(…)` (the old `run()` is deprecated). Real-decode cases use 06's generated fixtures with `ShadowMediaCodecConfig`. Instrumented session tests build a `MediaController` against `NeutrodynePlaybackService` ([06 Testing](06-playback.md#testing)). Every Media3 bump re-runs the full playback suite (risk M3r). The desktop engine is Media3-free; its corpus, clock and DSP-parity tests (golden PCM produced by Media3's own processors in a `:playback:impl` unit test) are 11's ([11 Testing](11-desktop.md#testing)).

### WorkManager and JobScheduler

`work-testing` in the `androidHostTest` source sets of `:core:data`, `:core:artwork`, `:download:impl` and `:sync:impl` and in `:youtube:ytdlp`'s `test`: `WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())`, then `getTestDriver(ctx)!!.setAllConstraintsMet(id)` / `setPeriodDelayMet(id)`; single workers with `TestListenableWorkerBuilder`. Job quotas beside an FGS (Android 16), UIDT behaviour and audio hardening cannot be reproduced in Robolectric; they run on GMD nightly ([07](07-downloads.md#instrumented-and-device-tests), [06](06-playback.md#testing)). The desktop counterpart, `DesktopJobRunner` with its lanes, is tested in `desktopTest` with `TestClock` and fake lanes ([11 Testing](11-desktop.md#testing)).

### Compose UI and screenshot tests

- **Behaviour** of shared UI runs once in `commonTest` with `runComposeUiTest` (`org.jetbrains.compose.ui:ui-test` 1.12.1, `@OptIn(ExperimentalTestApi::class)`, [Compose Multiplatform testing](https://kotlinlang.org/docs/multiplatform/compose-test.html)) on the desktop JVM; nodes are found by test tags (08). Android-specific UI (the `MainActivity` shell, permission prompts, predictive back, notification flows) is tested in `:app`'s instrumented suite with the Compose v2 rule `createAndroidComposeRule<MainActivity>()` (`StandardTestDispatcher`; `mainClock.autoAdvance = false` for indeterminate progress).
- **Accessibility:** Android's Accessibility Test Framework checks (`enableAccessibilityChecks()`, `ui-test-junit4-accessibility`) run in the `androidHostTest` captures of feature and UI modules and in every instrumented Compose test; rules and custom-action checks are 08's ([08 Automated checks](08-ui-ux.md#automated-checks)). Unverified: whether ATF reports every check under Robolectric (contrast needs a rendered frame, hence `GraphicsMode.NATIVE`); checked in M1a with a deliberately broken component (a 20 dp clickable without a label must fail the Robolectric test). Fallback: Robolectric keeps the checks that do fire, and each screen gets one instrumented `<Screen>AccessibilityTest` in `:app/src/androidTest` that opens it through the UI or a `neutrodyne://open/…` deep link (PLAN N4 requires instrumented checks either way; the journeys provide them for the screens they visit). The desktop has no ATF: `commonTest` asserts semantics (role, label, state description, traversal order) and, from MD4, that every action of 08's custom-actions catalogue is reachable by keyboard or context menu (PLAN MD4 AC1); VoiceOver and NVDA are manual ([11 MD4 manual checklist](11-desktop.md#md4-manual-checklist)).
- **Roborazzi** (1.76.0): the **Android set** in `androidHostTest` (KMP UI modules) or `test` under Robolectric with `roborazzi`, `roborazzi-compose` and `roborazzi-junit-rule`; the **desktop set** in `desktopTest` with `roborazzi-compose-desktop` (`runDesktopComposeUiTest { …; onRoot().captureRoboImage() }`, tasks `recordRoborazziDesktop`/`verifyRoborazziDesktop`, [Roborazzi](https://github.com/takahirom/roborazzi)). Each module with screenshot tests applies the Roborazzi plugin in its own build file (01 catalog rule 2: tooling plugins are not on the build-logic classpath); references in `screenshots/android/` and `screenshots/desktop/` of the module; `roborazzi.record.resizeScale=0.5` in `gradle.properties`. Determinism: `NeutrodyneTheme(dynamicColor = false)`, `TestClock`, fixed locale, `fakeImageLoader`, animations frozen; the desktop set also fixes density (1.0) and the window size per row of 08's matrix.
- **Tiers** (decides 08 open question 15): `ScreenshotTier.PR` captures every subject in light and dark at font scale 1.0, LTR, plus one stress variant per subject (dark, 2.0, `ar-XB`) — on the desktop at a 900 dp window. `ScreenshotTier.FULL` adds the remaining columns of [08's matrix](08-ui-ux.md#screenshot-matrix) (pure black, 1.5, 2.0 and RTL for every state; all widths and postures; desktop windows of 600 and 1400 dp, PLAN MD4 AC2). PR CI verifies `PR`; nightly verifies `FULL`. All reference PNGs (both tiers, both sets) are committed. Budget: ≤ 800 images and ≤ 30 MB for the Android set, ≤ 400 images and ≤ 15 MB for the desktop set; exceeding it requires dropping redundant variants, not Git LFS (keeps contributors' and Weblate's clones simple).

```kotlin
enum class ScreenshotTier { PR, FULL;                                    // :core:testing commonMain
    companion object { val current: ScreenshotTier get() = if (testSystemProperty("neutrodyne.screenshotTier") == "full") FULL else PR }
}
fun assumeTier(required: ScreenshotTier) { if (ScreenshotTier.current < required) skipTest("tier") }   // expect/actual: JUnit assumption
```

- **Recording policy:** reference images are recorded only on Linux x64 by the `record-screenshots.yml` workflow, both sets (font rasterisation differs on macOS and Windows, which is why the desktop goldens are verified only on `ubuntu-24.04`, [D59](../PLAN.md#3-key-decisions)); it uploads `screenshots-<sha>.zip` and the author applies it with `scripts/ci/apply-screenshots.sh <run-id>` (uses `gh run download`) and commits. PRs verify with `-Proborazzi.test.verify=true`; on failure the `_compare.png` files are uploaded as an artifact. Renovate groups Robolectric, Roborazzi and the Compose BOM, and the Kotlin group carries Compose Multiplatform, so a re-record lands in the same PR ([Renovate configuration](#renovate-configuration)).
- **Test hosts:** `debug` is no longer the published build type, so `:app` declares `ui-test-manifest` and `ui-tooling` as `debugImplementation` again ([01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)); `verifyDependencyPolicy` keeps both off `releaseRuntimeClasspath`, `verifyManifestPermissions` rejects their activities in the release manifest and `check-apk.sh --published` rejects them in a published APK ([Build-output checks](#build-output-checks)). KMP modules' Robolectric captures get ui-test-manifest's `ComponentActivity` from their host-test classpath (Unverified that it merges into the Android-KMP host-test manifest, M1a check; fallback: a JUnit rule in `:core:testing` that registers `ComponentActivity` with Robolectric's `ShadowPackageManager.addOrUpdateActivity` before the compose rule starts). `:app`'s instrumented tests use only `createAndroidComposeRule<MainActivity>()` and reach screens through the UI or deep links.
- Pseudo-locales `en-XA` and `ar-XB` for Compose resources and Android `res/` come from [Pseudo-locales and RTL](#pseudo-locales-and-rtl); `check-apk.sh --published` and `check-desktop-image.sh` assert that no published artefact carries them.

### Gradle Managed Devices

Configured in `neutrodyne.android.testing` for `:app` and `:playback:impl` and, with the same devices, for `:core:database`'s KMP device tests and for `:benchmark`; only those have device tests ([Placement rule](#test-pyramid)).

| Name | Device | API | Image | Groups | Used for |
|---|---|---|---|---|---|
| `api26` | Pixel 2 | 26 | GMD `aosp` (`android-26;default`) | `ci`, `nightly` | minSdk floor (PLAN M0 AC4); migrations and triggers on the framework SQLite 3.18; E2E |
| `api33` | Pixel 6 | 33 | GMD `aosp-atd` | `nightly` | `DataSyncWorkerTest` (07) |
| `api34` | Pixel 6 | 34 | GMD `aosp-atd` | `nightly` | first UIDT level; `PlaybackServiceTest` (06) |
| `api36` | Pixel 6 | 36 | GMD `aosp-atd` | `ci`, `nightly` | main device; `UidtDownloadTest` (07); E7 (debug and release); `release-build-smoke`; `:benchmark`'s release smoke journeys; E12's emulator |
| `bench34` (in `:benchmark` only) | Pixel 6 | 34 | GMD `aosp` (full image: launcher, SystemUI, root) | — | system tests (E10), Macrobenchmark dry runs, baseline-profile generation (M11b) |
| API 37 16 KB | — | 37 | `system-images;android-37.0;google_apis_ps16k;x86_64` via android-emulator-runner | nightly job `api37-16k` | Android 17 hardening, 16 KB page size (including CPython's libraries in `:ytx`), the debug suite and the release smoke (E0, E7, `:benchmark` journeys) on the `x86_64` APKs (PLAN M11 AC7, M9 AC9) |

```kotlin
private fun Project.configureManagedDevices(md: ManagedDevices) = md.apply {
    localDevices {                                      // Unverified: AGP 9.4 may name this allDevices { register<ManagedVirtualDevice>() }
        create("api26") { device = "Pixel 2"; apiLevel = 26; systemImageSource = "aosp" }
        create("api33") { device = "Pixel 6"; apiLevel = 33; systemImageSource = "aosp-atd" }
        create("api34") { device = "Pixel 6"; apiLevel = 34; systemImageSource = "aosp-atd" }
        create("api36") { device = "Pixel 6"; apiLevel = 36; systemImageSource = "aosp-atd" }
    }
    groups {
        create("ci") { targetDevices += listOf(localDevices["api26"], localDevices["api36"]) }
        create("nightly") { targetDevices += listOf("api26", "api33", "api34", "api36").map { localDevices[it] } }
    }
}
// Modules without src/androidTest get no device-test component, so `ciGroupDebugAndroidTest` never boots an emulator
// for an empty suite. Unverified accessor name under AGP 9.4 (8.x: HasDeviceTestsBuilder / androidTest.enable).
private fun Project.disableEmptyDeviceTests() = extensions.getByType<AndroidComponentsExtension<*, *, *>>()
    .beforeVariants { v -> (v as? HasDeviceTestsBuilder)?.deviceTests?.get("AndroidTest")?.enable =
        layout.projectDirectory.dir("src/androidTest").asFile.exists() }
```

- **ATD rule.** Automated Test Device images remove SystemUI, the launcher, the Settings app and bundled apps and disable hardware rendering ([GMD](https://developer.android.com/studio/test/gradle-managed-devices)). Tests that may run on an ATD device therefore never use the notification shade, lock screen, Home, recents or the back gesture: they read their own notifications with `NotificationManager.getActiveNotifications()`, fire notification actions through `PendingIntent.send()`, background the app by starting a test-APK activity (`BackgroundStandInActivity`), and press back with `pressBack()`; `:benchmark`'s release smoke journeys start the app with `am start` rather than through a launcher. Anything that needs real system UI (predictive-back animation, lock-screen controls, the resumption card) is a manual or full-image check (`api26`, `bench34`, API 37 job, or the owning document's device checklist). ATD x86_64 images exist for API 30–36 (`aosp_atd` repository XML; the GMD page's "API 30 only" is stale); there is no ATD or `default` image for API 37.
- **API 26 on GMD:** the GMD page says to use API 27 and higher. Verified 2026-10-06 (M0a): AGP 9.4.1 accepts API 26 only behind `android.experimental.testOptions.managedDevices.allowOldApiLevelDevices=true`, committed in `gradle.properties` — without it, task *creation* itself fails for every managed-device task (`api26*AndroidTest` and the `ci`/`nightly` group tasks), not only the device run. Fallback if a future AGP drops the opt-in: an `instrumented-api26` job with android-emulator-runner (`api-level: 26`, `target: default`, `arch: x86_64`) running `connectedDebugAndroidTest`; PLAN M0 AC4 accepts this fallback.
- **API 37:** newer system-image directories carry a minor SDK version (`android-36.1`, `android-37.0`, `android-37.2`). x86_64 API 37 images exist only with Google APIs: `google_apis` / `google_apis_playstore` (4 KB pages, 37.0) and `google_apis_ps16k` / `google_apis_playstore_ps16k` (37.0–37.2) ([repository XML](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml), read 2026-10-05). Unverified whether GMD accepts a minor-versioned Google APIs 16 KB image, hence android-emulator-runner v2.38.0 with `api-level: 37.0`, `target: google_apis_ps16k`, `arch: x86_64`; Unverified that the action accepts a minor-versioned `api-level` (fallback: `scripts/ci/start-emulator.sh` calling `sdkmanager`, `avdmanager` and `emulator` directly).
- **CI flags and parallelism:** `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`; `--max-workers=2` on every job that runs device tests, so at most two emulators exist at once on the 4-vCPU / 16 GB runner (each module's managed-device task boots its own emulators and Gradle would otherwise run several modules in parallel). No test sharding: the per-module suites are small, and sharding multiplies boots.
- **Release-type test runs.** `:app` sets `testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")` (the AntennaPod pattern; 01's sketch must read the property, [Open questions](#open-questions) 28), with `testProguardFiles("proguard-test.pro")` on `release` (keep rules for the androidTest APK only; the app's own rules stay in 01's `keepRules` source sets). `release-build-smoke` and `api37-16k` pass `-PtestBuildType=release -Pandroid.testInstrumentationRunnerArguments.annotation=ch.lkmc.neutrodyne.core.testing.ReleaseSmoke`, so only E0 and E7 run in-process against the R8-minified, non-debuggable APK that `assembleRelease` publishes, where unit tests cannot see R8 breakage (PLAN M0 AC4, M9 AC9). Instrumentation of a non-debuggable target needs only a test APK signed with the same certificate, which `neutrodynePublic` provides ([Gradle signing configuration](#gradle-signing-configuration)); `release.yml` never passes the property. Unverified: AGP 9.4 with `testBuildType` set to a minified build type and ABI splits, and instrumentation of the non-debuggable target with a same-certificate test APK (M0a check with S19; fallback: the release smoke runs only out of process through `:benchmark`, and E7's hook is armed by a DUMP-protected receiver in `app/src/benchmarkRelease/` against `benchmarkRelease`).
- **ABI splits on emulators:** every device above is `x86_64`, so the managed-device and `connected*` tasks must install the `x86_64` split of `:app` (with the engine). Unverified: that AGP 9.4 picks the split matching the device ABI for test installs (M0a check; fallback: ABI splits only when the Gradle property `neutrodyne.abiSplits=true` is set, which `release.yml`, the `assemble` job and the nightly jobs that inspect published APKs pass, so test builds stay single-APK).
- **Instrumented hygiene:** orchestrator with `clearPackageData`. Locally, `connectedDebugAndroidTest` installs `ch.lkmc.neutrodyne.debug` beside the published app and never touches it; release-type runs and `:benchmark` install the published application ID and key, clear it after each test and uninstall it after the run, so they run only on an emulator or a device whose Neutrodyne holds nothing worth keeping ([01 Debug build type](01-foundation.md#debug-build-type); `CONTRIBUTING.md`'s testing section repeats it). In `debug` builds ACRA is not installed (its mailbox is forced empty, [D62](../PLAN.md#3-key-decisions)), and 01's `DebugToolsInitializer` turns LeakCanary's automatic heap dumps off while the system property below is set, so dumps never stall a device test. ACRA is **on** in the release build, so release-type runs switch it off: the runner `NeutrodyneTestRunner` (`:app/src/androidTest`, an `AndroidJUnitRunner`) sets the system property `neutrodyne.instrumentedTest=true` in `newApplication` before calling `super`, which instantiates the application and calls `attach`, and with it `attachBaseContext` ([AOSP Instrumentation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Instrumentation.java)); 01's call site skips `installAcra` while the property is set ([ACRA configuration](#acra-configuration)). **One exception, `YtxIsolationTest`** ([04 Testing](04-youtube.md#testing), PLAN M9 AC5, in the debug suite): its "no ACRA dialog" assertion needs ACRA as users have it, so its `@BeforeClass` calls `installAcra(application, mailTo = "ytx-isolation-test@invalid")` itself on the main thread — a test-only, non-empty address, so the assertion depends neither on PO-10 nor on the build type — and after each `:ytx` failure asserts that ACRA's `ACRA-unapproved` report directory ([ACRA `ReportLocator`](https://github.com/ACRA/acra/blob/master/acra-core/src/main/java/org/acra/file/ReportLocator.kt)) stays empty and that no `:acra` process runs. Unverified: that ACRA installed after `Application.onCreate` (its setup guide installs it in `attachBaseContext`, [ACRA BasicSetup](https://github.com/ACRA/acra/wiki/BasicSetup)) catches a later main-process crash fully (M9a check by hand; fallback: the assertion moves to `:benchmark`'s out-of-process system tests against a build with `-Pneutrodyne.acraMailto=ytx-isolation-test@invalid`). That ACRA is never installed in `:ytx` itself is `YtxProcessStartTest`'s probe (01), which is meaningful only where ACRA is installed: it is tagged `ReleaseSmoke` and runs in the release-type runs once PO-10 names the mailbox. The property is inert unless an instrumentation sets it inside the app's process (PLAN 7.2's test-hook rule), and R8 cannot remove a system-property read, so `release` needs no keep rule for it. Unverified: that the orchestrator calls `newApplication` in every test process (M0a check with a deliberate crash in a test). Library modules' device tests run no `NeutrodyneApplication`; `:benchmark`'s out-of-process tests leave ACRA on, and a crash fails them anyway.

### Out-of-process system tests

Instrumented tests run inside the app's process, so killing the process (`am kill`, `ProcessDeathResumeTest`) or reinstalling the app kills the test. Such tests live in `:benchmark` (`com.android.test` with the `androidx.baselineprofile` 1.5.0 producer plugin, self-instrumenting, `targetProjectPath = ":app"`), drive the app with UI Automator 2.4.0 and `UiAutomation.executeShellCommand`, and are created in **M6b** (system tests); Macrobenchmarks follow in M10 and the baseline-profile generator in M11b ([Macrobenchmark and profiles](#macrobenchmark-and-profiles)). A test module builds and tests the app variant with the same build-type name ([Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)), so `:benchmark`'s variants map one to one: **`release`** — the system tests (E10 on `bench34`) and the release smoke journeys `ReleaseSmokeJourneyTest` (on `api36` and the API 37 16 KB image: start, five destinations, "Add by URL" with a feed from a MockWebServer inside the `:benchmark` process on `127.0.0.1`, which the app reaches over loopback, play 10 s, download one episode, back) against the published `release` build type; **`benchmarkRelease`** — Macrobenchmarks; **`nonMinifiedRelease`** — `BaselineProfileGenerator`. Compose nodes are found by resource ID through `Modifier.semantics { testTagsAsResourceId = true }` on `NeutrodyneRoot` and 08's test tags ([08 Performance journeys](08-ui-ux.md#performance-journeys)). Unverified: that `com.android.test` with the producer plugin under AGP 9.4 offers a `release` variant matching `:app`'s `release` (M6b check, with S19's throwaway module as the first evidence; fallback: the system tests and smoke journeys run against `benchmarkRelease`, which differs from `release` only in being profileable and in its own source set).

### Recorded responses

- No PR job contacts YouTube, Apple, fyyd, Podcast Index, GitHub's release endpoints or a real sync server. Directory JSON (03), channel pages and oEmbed (04) and the engine's InnerTube traffic (04's `RecordingRH`, driven by `scripts/youtube/record-responses.sh`) are recorded on a developer machine, scrubbed per [04 Recorded responses](04-youtube.md#recorded-responses) (`ip=`, signatures, cookies, visitor data, `expire`), and committed under `youtube/engine/src/test/resources/recorded/`; both engine hosts replay the same files. The update check's GitHub responses (`latest/download` redirects, manifests with `desktop[]` and `server` entries) are hand-written fixtures served by MockWebServer. Sync traffic is never recorded: the tests use `InMemorySyncServer`, Ktor's test host or the built JAR.
- Recording never runs in CI (GitHub runners use data-centre IPs that YouTube bot-challenges).
- A replay test fails on any unrecorded request; that failure means "re-record", never "add a fallback".

### Fixture policy

- **Licensing.** The repository is Unlicense; fixtures must not carry copyrighted prose, artwork or audio. Real-world feeds and OPML are minimised to structure with `lorem` text and `https://example.invalid/…` URLs; images are generated (08's `ArtworkFixtures`); audio is synthesised with ffmpeg by `scripts/fixtures/make-playback-fixtures.sh` (06), `make-media-fixtures.sh` (07) and 11's corpus script (including the HE-AAC samples of MD0), whose outputs are committed together with the ffmpeg version used. Each fixture directory has a `README.md` with origin URL and capture date. Sync vectors are written by hand or generated from a seeded run, never captured from a user's server.
- **Size.** One committed fixture ≤ 1 MB (larger inputs, e.g. 03's 831-item feed, 05's 100k-outline OPML or 10's 50,000-record upload, are generated at test time); all committed fixtures ≤ 20 MB.
- **Binaries.** No `.jar`, `.aar`, `.so`, `.dylib`, `.dll`, `.exe`, `.dex`, `.class`, `.pyc` or `.apk` under any `src/`: every binary in an APK, desktop image or server artefact comes from a Gradle dependency with verification metadata, from a pinned and checksum-verified download (Temurin per `desktopApp/runtime.lock`, python-build-standalone per its lock, FFmpeg and miniaudio sources per `native-components.lock`) or from a build step. The one vendored upstream binary, yt-dlp's release asset `youtube/ytdlp/engine/yt-dlp`, lives outside `src/` and is checked against its upstream signature on every build (`verifyBundledYtDlp`, [01 Python and native components](01-foundation.md#python-and-native-components)). Committed `.zip`/`.gz` fixtures (05's backup ZIPs, 04's `handle_mkbhd.html.gz`) are test inputs, never packaged.

---

## CI pipelines

Serves N8, N11, N12. Delivered in M0a (`ci.yml`, `release.yml` with the `verify-tag`, `android` and `publish` jobs, skeleton `nightly.yml`), M0b (`desktop-smoke`, `desktop-matrix`, the `desktop` and `sources` release jobs, the `server` PR job), MD0 (native builds in the matrix), MD1b (FFmpeg sources), MS0 (`sync-convergence`), MS1 (`server-jar`, the image jobs, `server-image-smoke`), M9a (engine jobs), M9b (`engine-canary.yml`), MD3 (the stdio host in the canary), extended per milestone. Honours [D60](../PLAN.md#3-key-decisions), [D76](../PLAN.md#3-key-decisions), [D79](../PLAN.md#3-key-decisions), [D89](../PLAN.md#3-key-decisions), [D95](../PLAN.md#3-key-decisions), [PO-18](../PLAN.md#48-further-product-owner-decisions) (public GitHub repository: free standard runners on Linux x64 and arm64, Windows and macOS).

### Runners

jpackage cannot cross-package and the native libraries are built per target, so every desktop target has its own runner ([D89](../PLAN.md#3-key-decisions), [native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)). Standard GitHub-hosted runners for public repositories ([GitHub-hosted runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners), read 2026-10-05):

| Label | Hardware | Used by |
|---|---|---|
| `ubuntu-24.04` | x64, 4 vCPU, 16 GB RAM, 14 GB SSD, KVM | every `ci.yml` job, the Android and emulator jobs, `linux-x64` desktop builds, `server-jar`, the `amd64` image, `sources`, `publish` |
| `ubuntu-24.04-arm` | arm64, 4 vCPU, 16 GB RAM, 14 GB SSD | `linux-arm64` desktop builds, the `arm64` image |
| `windows-2025` | x64, 4 vCPU, 16 GB RAM, 14 GB SSD | `windows-x64` desktop builds (also the asset Windows 11 on Arm runs emulated, [PO-40](../PLAN.md#48-further-product-owner-decisions)) |
| `macos-15` | Apple M1, 3 vCPU, 7 GB RAM, 14 GB SSD | `macos-arm64` desktop builds |

No `windows-11-arm`, Intel macOS or 32-bit runner is used in v1.0 (no native Windows-on-Arm build until M17, no Intel Macs, [D88](../PLAN.md#3-key-decisions)). The `macos-15` runner is the smallest and usually the slowest, so it sets the pace of the nightly matrix and of a release (risk P15, [Time budgets](#time-budgets)).

### Workflows

```mermaid
flowchart LR
  pr["pull_request"] --> st["static"]
  pr --> un["unit"]
  pr --> asm["assemble"]
  asm --> ds["desktop-smoke Linux x64"]
  pr --> sv["server"]
  lab["label run-instrumented"] --> ins["instrumented GMD ci"]
  main["push to main"] --> st
  main --> un
  main --> asm
  main --> sv
  main --> ins
  cron["nightly.yml 02:17 UTC"] --> na["Android: instrumented-full, api37-16k, release-build-smoke, system-tests, no-engine-build, bmgr"]
  cron --> nd["desktop-matrix on four runners"]
  cron --> ns["sync-convergence and server-image-smoke"]
  cron --> nr["repro report-only, screenshots-full, mutation-full, canaries"]
  tag["push tag v*"] --> rel["release.yml build jobs: android, desktop x4, sources, server-jar, server-image x2"]
  rel --> pub["publish: one immutable normal GitHub release"]
  pub --> it["image-tags on GHCR"]
  pub --> upd["update checks of installed apps and server notices"]
  pub --> obt["Obtainium"]
  six["schedule every 6 h"] --> can["engine-canary.yml for both engine hosts"]
  can --> pages["GitHub Pages approved engine manifest"]
  pages --> eng["apps with engine updates on"]
```

### `ci.yml`

Triggers: `pull_request`, `push` to `main` and `release/*`, `workflow_dispatch`. `permissions: contents: read` at the top; `concurrency: group ci-${{ github.ref }}`, `cancel-in-progress` for PRs only. Every job: `actions/checkout` (`fetch-depth: 0` in `static` for tag comparisons), `actions/setup-java` with Temurin `25` and `21` (multi-line `java-version`; the last one listed, 21, becomes the default that runs Gradle, and 25 serves the desktop toolchain, [setup-java](https://github.com/actions/setup-java)), `actions/setup-python` with Python 3.14 (Chaquopy compiles `.pyc` at build time with it, and both shim test tasks run on it; fallback 3.13 per [01 S7](01-foundation.md#s7-chaquopy-under-agp-941)), `gradle/actions/setup-gradle` with `cache-provider: basic` (MIT; the default "enhanced" cache is a proprietary component) and `cache-read-only` except on `main`.

| Job | Timeout | Runs | Blocking |
|---|---|---|---|
| `static` | 30 min | `./gradlew spotlessCheck :app:lintRelease assertModuleGraph :app:licenseeRelease :desktopApp:licensee :sync:server:licensee verifyDependencyPolicy :app:verifyManifestPermissions checkSpdxHeaders checkBannedApis checkBrandAssets checkTranslations :youtube:ytdlp:checkPythonLicences :youtube:ytdlp-desktop:checkPythonLicences :playback:native:checkNativeLicences :youtube:ytdlp:verifyBundledYtDlp --continue` (each task from the milestone that creates it: `checkNativeLicences` MD0, the desktop Python lock MD3; `checkBrandAssets` was created early with M0a.1 and joined `static` on 2026-10-06, so a hand-edited generated asset fails CI from then on, per [01 Delivery by milestone](01-foundation.md#delivery-by-milestone) step 31); `clang-format --dry-run --Werror` over `playback/native/src/native/` (from MD0); KGP assertion `./gradlew -q :app:buildEnvironment \| grep -E 'kotlin-gradle-plugin:.*2\.4\.20'` (PLAN M0 AC3); `scripts/ci/check-frozen-schemas.sh`; SARIF upload (`security-events: write`); `./gradlew detekt` with `continue-on-error: true` | yes (detekt no) |
| `unit` | 45 min | `./gradlew allTests test mutationTest :youtube:ytdlp:shimTest :youtube:ytdlp-desktop:shimTestStdio -Proborazzi.test.verify=true --continue` on the Linux x64 host: every KMP module's `commonTest` and `desktopTest`, the opted-in Android host tests, the JVM islands, the desktop-only modules and `:desktopApp` (E11, the desktop goldens, the null-back-end engine tests), `:sync:server:test` (black-box suite, `ServerConformanceTest`), `SyncConvergenceTest` with 1,000 seeds, the Android modules' unit tests, the mutation tests and both shim suites (`shimTestStdio` from MD3); Room schema drift: `test -z "$(git status --porcelain -- core/database/schemas)"` (KSP regenerated the schema while compiling); upload `**/build/reports/tests/` and Roborazzi `_compare.png` on failure; cache `~/.m2/repository/org/robolectric` keyed `robolectric-4.17-sdk36` | yes |
| `assemble` | 30 min | `./gradlew assembleRelease assembleDebug :desktopApp:createDistributable :sync:server:fatJar -Pneutrodyne.abiSplits=true` — the three published APKs (R8, not debuggable, signed with the committed key, so a PR build is signed exactly like a release and needs no secret), the three local debug APKs (they must keep compiling), the Linux x64 desktop app image (from M0b) and the server JAR (from M0b); `scripts/ci/check-apk.sh --published` on the release APKs ([Build-output checks](#build-output-checks)); upload `release-apks` (14 days: test artifacts only, never offered to users; they are signed with the same public key as every release, so they install over a real Neutrodyne, risk P10), `desktop-image-linux-x64` and `server-jar` (1 day, for the two jobs below) | yes |
| `desktop-smoke` (M0b) | 20 min | `needs: assemble`; on `ubuntu-24.04`: start the downloaded app image under `xvfb-run` in smoke mode (`-Dneutrodyne.smoke=true`: open the database, compose the first frame, ping the engine child when bundled, exit 0 within 60 s, [11 Smoke mode](11-desktop.md#smoke-mode)); `scripts/ci/check-desktop-image.sh` and `scripts/ci/check-runtime-sources.sh --image` on the image | yes |
| `server` (M0b) | 20 min | `needs: assemble`; start the downloaded `neutrodyne-server-{v}.jar` with `serve` on `127.0.0.1:8787` and a temporary `NEUTRODYNE_SERVER_DATA`; `curl` `/healthz`, `/readyz` and `/.well-known/neutrodyne-sync`; start it again with `NEUTRODYNE_SERVER_LISTEN=0.0.0.0:8787` and expect a non-zero exit (PLAN M0 AC16). From MS1: `docker build` of `sync/server/deploy/Dockerfile` with the JAR (`--build-arg VERSION`), run the container, wait for its health check, `scripts/ci/check-server-image.sh --no-sources` on the local image; nothing is pushed | yes |
| `instrumented` | 75 min | only on `main` pushes, `workflow_dispatch` or PRs labelled `run-instrumented`: free disk, enable KVM, `./gradlew --max-workers=2 ciGroupDebugAndroidTest <the ci group task of :core:database's device tests> -Pneutrodyne.testScope=ci -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect` (Unverified KMP task name, [Gradle test configuration](#gradle-test-configuration)); upload `**/build/outputs/androidTest-results/` on failure | required green on `main` (DoD), not a PR merge check |

```yaml
# .github/workflows/ci.yml (excerpt; every uses: is pinned by full commit SHA with the tag in a comment)
  unit:
    runs-on: ubuntu-24.04
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@<sha> # v7.0.1
      - uses: actions/setup-java@<sha> # v6.0.1
        with:
          distribution: temurin
          java-version: |
            25
            21
      - uses: actions/setup-python@<sha> # tag kept current by Renovate
        with: { python-version: "3.14" }   # = chaquopy.defaultConfig.version (01) and the desktop's PBS minor; both shim suites run on it
      - uses: gradle/actions/setup-gradle@<sha> # v6.4.0 (also validates the wrapper checksum)
        with: { cache-provider: basic, cache-read-only: "${{ github.ref != 'refs/heads/main' }}" }
      - uses: actions/cache@<sha>
        with: { path: ~/.m2/repository/org/robolectric, key: robolectric-4.17-sdk36 }
      - run: ./gradlew allTests test mutationTest :youtube:ytdlp:shimTest :youtube:ytdlp-desktop:shimTestStdio -Proborazzi.test.verify=true --continue
      - name: Room schema drift
        run: test -z "$(git status --porcelain -- core/database/schemas)" || { echo "::error::Room schema changed without a committed version"; git status --porcelain -- core/database/schemas; exit 1; }
      - uses: actions/upload-artifact@<sha> # v7.0.1
        if: failure()
        with: { name: unit-reports, path: "**/build/reports/tests/\n**/build/outputs/roborazzi/" }
```

The instrumented job's preamble (as AntennaPod does): `sudo rm -rf /usr/share/dotnet /usr/local/lib/android/sdk/ndk /opt/ghc /usr/local/.ghcup`, then the udev rule `KERNEL=="kvm", GROUP="kvm", MODE="0666"` with `udevadm trigger`. The GMD system images of the `ci` group are cached (`~/.android/avd/gradle-managed`, key = image list); `nightly` images are downloaded each night.

### `nightly.yml`

`schedule: cron "17 2 * * *"` plus `workflow_dispatch` with input `scope` (`full`, default; `youtube-smoke` = only `release-build-smoke` and the `api36` debug leg of `instrumented-full` filtered to `YouTubeReleaseSmokeTest` and `SmokeTest`, plus, from MD3, `:youtube:ytdlp-desktop:shimTestStdio` and `StdioYtxTransportTest` on Linux x64 — ≈ 15 min, used by the hotfix path; `desktop` = only `desktop-matrix`, dispatched by PRs that change native code or a bundled component). Changed 2026-10-06: the `ref` dispatch input is gone — every job checks out `github.sha`, the exact commit the run's `head_sha` records, because the input's `main` default let a dispatched run test a different commit than the one `release.sh` gates on (a dispatch on another branch now selects it with GitHub's native ref picker). The Ubuntu GMD jobs (`instrumented-full`, `release-build-smoke`) install the emulator and platform-tools before their Gradle runs (`sdkmanager --install`), as `ci.yml`'s `instrumented` does: the runner image no longer ships the emulator package (added 2026-10-06). A failing job runs `scripts/ci/report-nightly.sh <job>`, which opens or updates one issue per job (label `nightly-failure`, `permissions: issues: write`) and closes it after the next green run.

| Job | From | Runs | Blocks a release? |
|---|---|---|---|
| `instrumented-full` | M0a | **debug** leg: `./gradlew --max-workers=2 -Pneutrodyne.testScope=nightly -Pandroid.testInstrumentationRunnerArguments.ytxReplay=true nightlyGroupDebugAndroidTest` plus `:core:database`'s nightly device task (every device module's suite on the `debug` build, on `api26`, `api33`, `api34` and `api36`; E7 included); **release** leg (from M6b): `:benchmark`'s release smoke journeys and E10's companions against `:app`'s `release` on `api36` ([Out-of-process system tests](#out-of-process-system-tests)) | yes (red nightly ⇒ no tag) |
| `api37-16k` | M0a | android-emulator-runner (`api-level: 37.0`, `target: google_apis_ps16k`, `arch: x86_64`, [GMD notes](#gradle-managed-devices)) on the pinned cmdline-tools `install-android-sdk.sh` installs first — the image's `cmdline-tools/latest` ships revision 12.0, whose avdmanager exits 0 but writes `target=android-0` into the AVD descriptor for the dotted `37.0` (2026-10-08 note below): every test APK is built before the first emulator boots and each emulator script first starts `adb logcat -v threadtime` into `$RUNNER_TEMP`, which a failed run uploads as `api37-16k-logcat` (7 days), then waits until `cmd settings` and `pm` answer; each step recreates its AVD, asserts the written `target=android-37.0` before boot, and — after the waits — requires a running `init.svc.surfaceflinger` and a `hasReadColorBufferDma`-free logcat before the suite; the emulator renders guest-side (`-gpu guest`) and each script switches to three-button navigation first (2026-10-08: under host rendering the image's gralloc mapper aborted surfaceflinger whenever SystemUI sampled the gesture handle's region, 4–12 times in 3 min across `swiftshader_indirect`, `swangle_indirect`, emulator 37.3.3 and the 37.1 image, each abort restarting system_server so installs failed with "Cannot access system provider: 'settings'"; guest rendering with three-button navigation had none); assert `adb shell getconf PAGE_SIZE` = 16384; the debug suite `./gradlew --max-workers=2 -Pneutrodyne.testScope=nightly -Pandroid.testInstrumentationRunnerArguments.ytxReplay=true connectedDebugAndroidTest` (the whole `:app` suite on the `x86_64` debug APK: E0 with `selftest` in `:ytx` while S7 is go, journeys, `YtxProcessStartTest`, 06's `PlaybackServiceTest`, whose hardening cases switch `cmd audio set-enable-hardening throw` on through `UiAutomation.executeShellCommand` and off in `@After`, plus `:playback:impl`'s and `:core:database`'s suites with migrations on both drivers); then the release smoke: `-PtestBuildType=release :app:connectedReleaseAndroidTest` with the `ReleaseSmoke` filter (E0, E7; PLAN M9 AC9) and, from M6b, `:benchmark`'s release smoke journeys; `zipalign -c -P 16 -v 4` and `check-apk.sh --alignment-only` on the release `x86_64` APK (PLAN M11 AC7) | yes |
| `release-build-smoke` | M0a | on `api36`: `./gradlew -PtestBuildType=release -Pandroid.testInstrumentationRunnerArguments.annotation=ch.lkmc.neutrodyne.core.testing.ReleaseSmoke -Pandroid.testInstrumentationRunnerArguments.ytxReplay=true api36ReleaseAndroidTest` — E0 on the published `release` build (PLAN M0 AC4) and, from M9a, E7 (PLAN M9 AC9); `aapt2 dump badging` of the tested APK shows no `application-debuggable` (a failed inspection — missing `aapt2` or APK — fails the step instead of passing as absence, fixed 2026-10-06) | yes |
| `system-tests` | M6b | `:benchmark` system tests on `bench34` (E10) against `release` | yes |
| `no-engine-build` | M9a | the emergency builds without the engine ([01 Emergency build without the engine](01-foundation.md#emergency-build-without-the-engine)): `./gradlew assembleRelease :desktopApp:createDistributable -Pneutrodyne.youtubeEngine=false` on Linux x64, `scripts/ci/check-apk.sh --no-engine` (the published checks plus the no-engine content rules), `scripts/ci/check-desktop-image.sh --no-engine`, and each shell's graph test with the switch off (`:app:testDebugUnitTest --tests '*AppGraphTest*'`, `:desktopApp:test --tests '*DesktopAppGraphTest*'`), so the same-day emergency release of risk L1 never rots for either app | yes |
| `desktop-matrix` | M0b | matrix (`fail-fast: false`) `windows-x64` on `windows-2025`, `macos-arm64` on `macos-15`, `linux-x64` on `ubuntu-24.04`, `linux-arm64` on `ubuntu-24.04-arm`: `./gradlew allTests test` for the desktop JVM targets on that host (Room's bundled natives, `WindowsPathLengthTest`, the DPAPI round trip and `UrlSchemeRegistrarTest` on Windows, `LinuxMprisSessionTest` and `DesktopNotifierTest` on Linux with a private session bus); 2026-10-08, nightly 37831500506: the `linux-arm64` leg excludes `testAndroidHostTest` and `testDebugUnitTest` (AGP 9 creates unit tests only for `debug`, so the two names cover every Android host-test task; `-x` on a nonexistent task fails the invocation) — the SDK's aapt2/aidl and Gradle's aapt2 are x86_64-only binaries and Robolectric ships no linux/aarch64 runtime, so the whole Android host-test subtree cannot run there (the desktop JVM and JVM-island suites still run; `ci.yml`'s `unit` job keeps the Android host coverage on linux-x64) — and installs `libegl1`, the verified provider of `libEGL.so.1`, which skiko-linux-arm64 links but the arm image lacks; from MD0 `buildNdmedia`, `buildFfmpeg` and the audio corpus (`FfmpegCorpusTest`, `FfmpegLayoutTest`, clock and DSP-parity tests with the null back-end); from MD3 `YtxProcessTest` with the bundled python-build-standalone; `packageDistributionForCurrentOS` and the archives of the target — 2026-10-06: run as one `createDistributable` + package task per install kind (`packageMsi`/`packageZip`, `packageDmg`, `packageDeb`/`packageRpm`/`packageTarGz`; the macOS ZIP through `scripts/desktop/mac-zip.sh` before 1.0.0, [11 Packaging pipeline](11-desktop.md#packaging-pipeline)); the smoke start of every package; `check-desktop-image.sh`, `check-runtime-sources.sh --image` and, on macOS, `codesign --verify --deep --strict --verbose=2`; installed and download sizes into the job summary (PB27 trend) ([11 Testing](11-desktop.md#testing)) | yes (DoD from M0b) |
| `sync-convergence` | MS0 | `SyncConvergenceTest` and `ServerConvergenceTest` with `-PsyncSeeds=100000` ([10 Convergence property test](10-sync.md#convergence-property-test)); from MS2 E12 `CrossDeviceSyncTest` (`-PcrossDevice`, an `api36` emulator through android-emulator-runner, the built fat JAR on the runner, the headless desktop graph; [End-to-end journeys](#end-to-end-journeys)) | yes |
| `server-image-smoke` | MS1 | build the image from the fat JAR (`docker buildx build --load`), run the reference `sync/server/deploy/compose.yaml` with a test domain and Caddy's internal CA, `/readyz` through Caddy, an SSE stream held for 5 min through Caddy and through the nginx example (S17, [10 nginx](10-sync.md#nginx)), the nightly `VACUUM INTO` backup triggered and restored with `restore`; `check-server-image.sh`; a `tcpdump` of the compose network showing no outbound request except the update check (`NEUTRODYNE_SERVER_UPDATE_CHECK` pointed at a local stub; [10 Update notice](10-sync.md#update-notice)) | yes |
| `benchmark-dryrun` | M10 | Macrobenchmark journeys against `:app`'s `benchmarkRelease` with `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dryRunMode.enable=true` on `bench34` (catches broken journeys; timings are meaningless on emulators) | no |
| `repro` | M0a | [two signed builds and a diff](#nightly-reproducibility-job) of the APKs, from M0b the desktop application JARs and the server JAR; report-only permanently ([D79](../PLAN.md#3-key-decisions)) | no |
| `bmgr` | M3 | android-emulator-runner API 29 (`backup_rules.xml` path) and API 36 (`data_extraction_rules.xml`), image `default`: `scripts/ci/bmgr-check.sh ch.lkmc.neutrodyne` on the published release `x86_64` APK (05's procedure, with its shell-only `SnapshotNowReceiver` as the snapshot trigger, [05 Testing with bmgr](05-groups-opml-backup.md#testing-with-bmgr), plus 07's "no `Podcasts/`" assertion). Needs uninstall/reinstall, which an in-process instrumented test cannot do | yes |
| `screenshots-full` | M10 | `./gradlew allTests test --tests '*Screenshot*' -PscreenshotTier=full -Proborazzi.test.verify=true` (the Android and desktop sets; Unverified that `--tests` applies across `allTests`, fallback: the Roborazzi verify tasks per module) | yes |
| `mutation-full` | M1a | `./gradlew mutationTest -PmutationIterations=1000` | yes |
| `live-canary` | when 03 ships `feeds/canary/feeds.txt` (M11 at the latest) | `./gradlew :feeds:jvm:liveCanary` | no |
| `youtube-canary` | M9a (04) | 04's subscribe-resolve-chunk smoke through the shim on a host CPython, plus a fetch of the control feeds of 04's small-library outage check (live; runner IPs are bot-checked, so it is noisy by design); from MD3 also through the stdio host | no |
| `engine-nightly-canary` | M9a (04) | `shimTest` and, from MD3, `shimTestStdio` with yt-dlp's latest nightly build (`yt-dlp/yt-dlp-nightly-builds`, signature checked with the same pinned key) in place of the bundled version: the early warning for plugin or internal API drift (risk M8r). Informational; it never approves anything ([D76](../PLAN.md#3-key-decisions): nightlies are never shipped) | no |

Removed 2026-10-05 (scope revision): `dev-tools-build` (developer tooling lives in `debug`, which `assemble` builds on every PR) and the `instrumented-full` leg against the `benchmark` build type (the published `release` build is tested directly). The former `mirror` job was removed with [PO-34](../PLAN.md#48-further-product-owner-decisions) ([GitHub takedown](#github-takedown)).

### `release.yml`

Trigger: `push: tags: ['v*']`. Top-level `permissions: contents: read`. The build jobs read no secret and need no approval, so they run while the maintainer approves the `release` environment, which only `publish` uses (required reviewer = a maintainer; the environment holds no signing secret — the APK key is committed, [Committed keystore](#committed-keystore), and the desktop builds carry no publisher signature — and only `PODCASTINDEX_*` once Podcast Index grants written permission, after which the `android` and `desktop` jobs join the environment to read it, [D26](../PLAN.md#3-key-decisions)). Every job uses `setup-gradle` with `cache-disabled: true` (no cache-poisoning surface for published builds). Job permissions are the minimum: `packages: write` only in `server-image`, `server-image-manifest` and `image-tags`; `id-token: write`, `attestations: write` and `artifact-metadata: write` only in `server-image-manifest` and `publish` (for `actions/attest`); `contents: write` only in `publish`.

```mermaid
sequenceDiagram
  participant M as Maintainer
  participant G as GitHub
  participant V as verify-tag
  participant B as Build jobs
  participant H as GHCR
  participant P as publish in environment release
  M->>M: scripts/release.sh patch
  M->>G: push release commit and tag vX.Y.Z
  G->>V: tag event
  V->>V: preconditions, make_latest, macOS kind
  V->>B: start android, desktop x4, sources, server-jar
  B->>B: assembleRelease and APK checks, jpackage per target and image checks, source bundles, fat JAR
  B->>H: server image per architecture pushed by digest, index, attestation
  P->>M: wait for required reviewer
  M->>P: approve
  B-->>P: workflow artifacts
  P->>P: asset set check, update manifest, runtime sources check, SHA256SUMS
  P->>G: actions/attest over every asset, draft release, upload, publish immutable normal release
  P->>G: gh release verify and verify-asset
  P->>H: image-tags vX.Y.Z, X.Y and latest
  G-->>M: update checks of installed apps, server notices and Obtainium find the release
```

Jobs and steps, in order (target: tag → published release with every asset in < 60 min, N11 and PLAN M11 AC2; [Time budgets](#time-budgets)):

1. **`verify-tag`** (`ubuntu-24.04`): `scripts/ci/verify-tag.sh` checks that the tag equals `v` + `neutrodyne.versionName` and carries no suffix (`^v[0-9]+\.[0-9]+\.[0-9]+$`; `versionCode` ends in S = 95, [Version scheme](#version-scheme)); the tagged commit is on `main` or a `release/*` branch ([hotfix branches](#scriptsreleasesh)); `changelogs/<versionCode>.txt` exists in it ([Changelogs and release notes](#changelogs-and-release-notes)); and **either** `ci.yml` concluded `success` for the tagged commit **or** the tagged commit is a `release.sh` commit — exactly one parent, `ci.yml` `success` on that parent (`gh api repos/{repo}/commits/{sha}/check-runs`), and `git diff --numstat HEAD^ HEAD` showing only `gradle.properties` with two changed lines, both matching `^neutrodyne\.version(Name|Code)=`. The second branch exists because the release commit's own `ci.yml` run starts at the same moment as `release.yml`; a version-line change cannot alter what CI verified. It also makes the first `make_latest` computation: true when the tag's `versionCode` is higher than that of the current latest release (`gh release view --json tagName`), so a late patch on an older line ([hotfix](#scriptsreleasesh)) can never become "latest". When no published, non-draft release exists yet (`v0.1.0`), `gh release view` exits non-zero with "release not found" (gh's `ErrReleaseNotFound` on the 404 of the latest-release endpoint, [gh source](https://github.com/cli/cli/blob/trunk/pkg/cmd/release/shared/fetch.go)); the script then sets `make_latest = true`, and any other error fails the run, so the first release is "latest" and `publish`'s final `curl` succeeds (PLAN M0 AC7). Changed 2026-10-06: this value is only a preview — the authoritative decision moved into the serialized `publish` job (step 8), because two tags can build concurrently and the latest-release pointer may move between `verify-tag` and publication; `verify-tag` keeps the output so the job graph does not change. Outputs: `version`, `versionCode`, `make_latest`, `youtube_engine` (from the tagged `gradle.properties`; an emergency release commits `false` there, [01](01-foundation.md#emergency-build-without-the-engine)), `mac_kind` (`mac-zip` before `1.0.0`, `dmg` from it, [D63](../PLAN.md#3-key-decisions)) and the expected asset list for this release (APKs and mapping from M0a; desktop assets and runtime sources from the first release that has `:desktopApp` packaging; FFmpeg sources once `:playback:native` ships FFmpeg; server JAR, image and image sources once `:sync:server` has the `serve` routes of MS1 — each read from a committed `release-assets.json` that the milestone PR extends).
2. **`android`** (`ubuntu-24.04`, inside the same pinned container and script as the [repro job](#nightly-reproducibility-job), so the published APKs and the nightly check share one toolchain: `python:3.14-slim-trixie@sha256:<digest>`, Docker's official Python image on Debian 13 ([docker-library/python](https://github.com/docker-library/python/tree/master/3.14); Debian trixie's own `python3` is 3.13, [Debian](https://packages.debian.org/trixie/python3), and Chaquopy's build-time `.pyc` compilation needs the packaged minor version, [01 S7](01-foundation.md#s7-chaquopy-under-agp-941)), plus Debian's `openjdk-21-jdk-headless` ([Debian](https://packages.debian.org/trixie/openjdk-21-jdk-headless)); the image digest is Renovate-managed and changes only in a reviewed PR): `scripts/ci/repro-build.sh assembleRelease -Pneutrodyne.abiSplits=true` (nothing to decode: the container signs with the committed keystore through `neutrodynePublic`, [Gradle signing configuration](#gradle-signing-configuration); after Podcast Index's written permission it also passes `-Pneutrodyne.podcastIndexKey/Secret`; nothing else from the runner environment enters the container). Output: `app-arm64-v8a-release.apk`, `app-x86_64-release.apk`, `app-armeabi-v7a-release.apk` (AGP names split outputs `modulename-ABI-buildvariant.apk`, [configure APK splits](https://developer.android.com/build/configure-apk-splits); Unverified for AGP 9) and R8's `mapping.txt`. Per APK: `apksigner verify --verbose --print-certs --min-sdk-version 26` must report the v1 scheme false and v2 and v3 true, and the signer's SHA-256 must equal the output of `scripts/ci/public-cert-sha256.sh` (the committed keystore's certificate, [apksigner](https://developer.android.com/tools/apksigner)); `aapt2 dump badging` shows `package: name='ch.lkmc.neutrodyne'`, the tag's `versionCode` and `versionName`, and **no** `application-debuggable` ([MASTG-TECH-0150](https://mas.owasp.org/MASTG/techniques/android/MASTG-TECH-0150/)); `aapt2 dump xmltree --file AndroidManifest.xml` shows no `android:testOnly` ([application element](https://developer.android.com/guide/topics/manifest/application-element)); `zipalign -c -P 16 -v 4`; `check-apk.sh --published` (sizes per PB12/PB13, content including the absence of debug code, alignment of the `.so` files inside Chaquopy's asset zips; exactly three APKs, no universal APK). Rename to the [asset names](#release-assets) `neutrodyne-{v}-{abi}.apk`; zip `mapping.txt` (with `seeds.txt` and `usage.txt`) into `neutrodyne-{v}-r8-mapping.zip`; upload the workflow artifact `android`.
3. **`desktop`** (matrix `windows-x64` on `windows-2025`, `macos-arm64` on `macos-15`, `linux-x64` on `ubuntu-24.04`, `linux-arm64` on `ubuntu-24.04-arm`; `fail-fast: false`; no environment, no secret): the packaging pipeline of [11 Packaging pipeline](11-desktop.md#packaging-pipeline) — Temurin 25 from `desktopApp/runtime.lock` (checksum-verified), WiX on Windows, the native builds, the python-build-standalone bundle, `createDistributable`, `trainAotCache` (from MD5 a failure blocks; before, the image ships without a cache), one image per install kind, ad-hoc signing on macOS and `scripts/desktop/mac-zip.sh` when `mac_kind` is `mac-zip`, the per-kind package tasks `packageMsi`/`packageDmg` and `packageDeb`/`packageRpm` plus the `packageZip`/`packageTarGz` archives (2026-10-06: the convention plugin's own tasks stand in for `packageDistributionForCurrentOS`, which cannot take our resource overrides — [11 Packaging pipeline](11-desktop.md#packaging-pipeline)); then the smoke start of every package, `check-desktop-image.sh`, `check-runtime-sources.sh --image`, on macOS `codesign --verify --deep --strict`; rename to `neutrodyne-{v}-{os}-{arch}.{ext}`; upload the artifact `desktop-{target}`. The build and smoke steps run in a two-attempt shell loop, because GitHub Actions has no job retry and an immutable release cannot gain assets later: a runner or toolchain flake gets a second chance, a deterministic failure fails twice and fails the run (no partial release; fix forward with the next PATCH, risk P15).
4. **`sources`** (`ubuntu-24.04`): download Adoptium's source tarball named in `runtime.lock` and verify its SHA-256 → `openjdk-{jdk}-temurin-sources.tar.gz`; write `RUNTIME-SOURCES.md` from `runtime.lock` and the jlink module lists (content: [11 Runtime exception obligations and checks](11-desktop.md#runtime-exception-obligations-and-checks)); from MD1b `./gradlew :playback:native:assembleFfmpegSource` → `ffmpeg-{ffmpeg}-neutrodyne-src.tar.xz` (pristine tarball with its signature, empty `changes.diff`, configure lines and scripts, [11 FFmpeg LGPL obligations](11-desktop.md#ffmpeg-lgpl-obligations)); from MS1 `neutrodyne-server-image-sources-{v}.tar.xz` — the source packages of the pinned base image's GPL and LGPL components ([10 Image sources and the runtime exception](10-sync.md#image-sources-and-the-runtime-exception)), plus a second runtime tarball when the base image's Temurin differs from `runtime.lock` (10); from M0b, with every MSI, `wix-{wix}-src.tar.gz` (the pinned WiX release's source named in `desktopApp/wix.lock`, SHA-256 verified; MS-RL, [01 Licence structure](01-foundation.md#licence-structure)); from MD3 `python-build-standalone-{pbsTag}-src.tar.gz` (the PBS repository archive at the tag in `python-components.lock`, SHA-256 verified; its MPL-2.0 build patches, [04 Licence boundary](04-youtube.md#licence-boundary)) (both added 2026-10-05, [D3](../PLAN.md#3-key-decisions)).
5. **`server-jar`** (`ubuntu-24.04`, JDK 21 toolchain): `./gradlew :sync:server:fatJar` → `neutrodyne-server-{v}.jar`; start it with `serve` on loopback and check `/healthz`; upload the artifact `server-jar`.
6. **`server-image`** (`needs: server-jar`; matrix `amd64` on `ubuntu-24.04`, `arm64` on `ubuntu-24.04-arm`; native builds, no QEMU): `docker buildx build` of `sync/server/deploy/Dockerfile` with the JAR and `--build-arg VERSION`, the OCI labels, `--platform linux/{arch}` and `--output type=image,name=ghcr.io/{owner}/neutrodyne-server,push-by-digest=true,name-canonical=true,push=true` (no tag; Unverified exact output options — Docker now documents the per-runner split through its reusable workflows, [multi-platform builds](https://docs.docker.com/build/ci/github-actions/multi-platform/), which may replace these two jobs); `scripts/ci/check-server-image.sh --no-sources ghcr.io/{owner}/neutrodyne-server@<digest>`; output the digest. Two attempts as in `desktop`.
7. **`server-image-manifest`** (`needs: server-image`): `docker buildx imagetools create` of the multi-arch index from both digests and `actions/attest` (v4) with `subject-name: ghcr.io/{owner}/neutrodyne-server`, `subject-digest: <index digest>` and `push-to-registry: true` ([actions/attest](https://github.com/actions/attest)); output the index digest. Unverified whether `imagetools create` pushes an index without a tag ([imagetools create](https://docs.docker.com/reference/cli/docker/buildx/imagetools/create/)); fallback: a throwaway tag `ci-{run_id}` that `image-tags` deletes through the GHCR package API, never `{v}`, `{X.Y}` or `latest` ([Open questions](#open-questions) 29).
8. **`publish`** (`needs` every build job; environment `release`; `concurrency: group release-publish, cancel-in-progress: false` — one serialized group across **all** tags since 2026-10-06: two releases may build in parallel, but only one creates, uploads and flips "latest" at a time, so an older tag that finishes last can never take "latest" backwards): download the artifacts and compare the set with `verify-tag`'s expected list (a missing or extra file fails); `scripts/ci/make-update-json.sh` writes `neutrodyne-update.json` with `apks[]`, `desktop[]` and `server` ([Update manifest](#update-manifest)); `scripts/ci/check-update-json.sh` validates it against the files, the tag, `gradle.properties`, `neutrodyne.repoUrl`, the changelog and the image digest — a release without a valid manifest is never published, because the update checks read `releases/latest/download/neutrodyne-update.json`; `scripts/ci/check-runtime-sources.sh --release` matches the runtime tarball and `RUNTIME-SOURCES.md` with `runtime.lock` and with every desktop image's recorded runtime checks, and requires the WiX and PBS source assets whenever an MSI or the desktop engine ships; `check-server-image.sh` with the image-source bundle (source coverage); `SHA256SUMS` (`sha256sum` format) over every other asset; the release body from the [template](#release-body); `actions/attest` with `subject-path` listing every asset including `neutrodyne-update.json` and `SHA256SUMS` (SLSA build-provenance attestations, verifiable with `gh attestation verify`, [artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)); `make_latest` is recomputed here — the same rule as `verify-tag` step 1 (the tag's `versionCode` higher than the current latest release's, `true` when no published release exists) but evaluated **inside** the serialized job against `releases/latest` as it stands at publish time (2026-10-06: the pre-computed output could be stale after builds and the approval wait, and two concurrent releases could both decide `true`); the job's `make_latest` output is the value downstream steps and `image-tags` must consume. `gh release create vX.Y.Z --draft --verify-tag --title "Neutrodyne X.Y.Z" --notes-file body.md` (never `--prerelease`: every tag, tester builds included, is a normal release, [Tester builds](#tester-builds)) → `gh release upload` of every asset → `gh release edit vX.Y.Z --draft=false --latest=<make_latest>`. Immutable releases are on in the repository settings, so publishing locks the assets and the tag and creates GitHub's release attestation ([immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases)). Then `gh release verify vX.Y.Z` and `gh release verify-asset vX.Y.Z <asset>` for every asset ([gh release verify-asset](https://cli.github.com/manual/gh_release_verify-asset); the command hashes the local file, so it is called with the real `dist/` path of each downloaded asset — not a basename resolved against the repository root, 2026-10-06); when `make_latest` is true, `curl -fsSL {repoUrl}/releases/latest/download/neutrodyne-update.json` must succeed and name the tag's `versionCode`. The `gh` CLI preinstalled on the runner replaces a third-party release action.
9. **`image-tags`** (`needs: publish`, from MS1): first `oras push ghcr.io/{owner}/neutrodyne-server:{v}-sources` with the runtime source tarball and `neutrodyne-server-image-sources-{v}.tar.xz` (2026-10-05: the source from the same place as the image, [10 Image sources and the runtime exception](10-sync.md#image-sources-and-the-runtime-exception)) and `check-server-image.sh --sources-tag`; then `docker buildx imagetools create -t ghcr.io/{owner}/neutrodyne-server:{v} -t …:{X.Y}` and, when `make_latest` is true — `publish`'s recomputed output, never `verify-tag`'s preview (2026-10-06) — `-t …:latest`, from `ghcr.io/{owner}/neutrodyne-server@<index digest>`; verify that each tag resolves to that digest. Tags move only after the GitHub release is published, so a failed release never moves `latest` ([D95](../PLAN.md#3-key-decisions)); a failure here leaves a published release with an untagged image, fixed by re-running the job.

A failure before `publish` leaves no release; a failure inside `publish` before the final edit leaves at most a draft: the maintainer deletes it and re-runs the workflow on the same tag if the cause was outside the source (Unverified: that deleting a never-published draft keeps the tag name usable under immutable releases; GitHub documents that a deleted *published* immutable release's tag name can never be reused). Digests pushed by a failed run stay untagged and unattested; a maintainer may delete them in the package settings. A problem found after publishing is fixed forward with a new PATCH release; nothing published is ever edited except its notes. The first image of the project creates the GHCR package as private; the maintainer links it to the repository and makes it public once in the package settings (irreversible, [package visibility](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility); release runbook in the MS1 release issue). Removed with the store channels ([D79](../PLAN.md#3-key-decisions)): the Play publishing step, the GPL corresponding-source bundle of the APKs and the release-blocking reproducibility job. Removed 2026-10-05 (PO-33–PO-35): decoding a keystore secret, the pre-release flag and the Codeberg push. Changed 2026-10-05 (scope revision): the single `release` job in one container became the job graph above; the published build is `assembleRelease`; the R8 mapping returns as `neutrodyne-{v}-r8-mapping.zip`.

### `engine-canary.yml`

Serves R3.9, R8.6, N11, N12; mitigates risks M1r, M7r, M8r, P14. Delivered in M9b; MD3 adds the stdio host to the gate. Honours [D76](../PLAN.md#3-key-decisions), [D90](../PLAN.md#3-key-decisions), [PO-32](../PLAN.md#48-further-product-owner-decisions). The workflow — schedule, jobs, permissions, environments, signing and deployment — is owned here; **what** it verifies and tests is [04 Engine canary](04-youtube.md#engine-canary), and the manifest format and the app-side checks are [04 Trust chain](04-youtube.md#trust-chain). One approved manifest serves both apps: its `shimApi` range covers both host adapters, so a version is approved only when the Chaquopy host and the stdio host pass ([04 Shared engine module](04-youtube.md#shared-engine-module)).

Triggers: `schedule: cron "23 */6 * * *"` (every 6 h) and `workflow_dispatch` with inputs `tag` (approve a specific yt-dlp stable tag, e.g. after re-recording, 04's runbook path 2), `revoke` (a version to list in `revoked`; dispatched together with `tag` = the last good version, 04's revocation) and `bootstrap` (the very first run, when no manifest exists yet: `sequence` starts at 1). `concurrency: group: engine-canary, cancel-in-progress: false`, so two runs never race on `sequence`. Top-level `permissions: contents: read`.

| Job | Environment, permissions | Steps |
|---|---|---|
| `detect` | none; `contents: read` | 1. Read the current approved manifest from `neutrodyne.engineManifestUrl` (`gradle.properties`, with a cache-busting query) and verify its Ed25519 signature against the committed `youtube/ytdlp/keys/engine-manifest-ed25519.pub`; keep `sequence`, `revoked` and the approved version. 2. Detect the candidate: the `tag` input, else the tag in the `Location` header of `https://github.com/yt-dlp/yt-dlp/releases/latest` (no REST API); output `none` when it equals the approved version and no `revoke` input is given. Output: the candidate tag, the current `sequence` and `revoked` |
| `gate` (needs `detect`; skipped when the candidate is `none` and no `revoke` input is given) | none; `contents: read`, `issues: write`; host CPython 3.14 from `actions/setup-python` | 3. In the job's checkout (never committed): `scripts/engine/bump-ytdlp.sh <tag>` downloads `yt-dlp`, `SHA2-256SUMS` and `SHA2-256SUMS.sig` from `github.com/yt-dlp/yt-dlp/releases/download/<tag>/`, applies 04's size and zip-content rules and updates `bundled.json` and the lockfile's yt-dlp entry exactly as a bump PR would; then `./gradlew :youtube:ytdlp:verifyBundledYtDlp :youtube:ytdlp:checkPythonLicences :youtube:ytdlp-desktop:checkPythonLicences :youtube:ytdlp:shimTest :youtube:ytdlp-desktop:shimTestStdio` (the stdio host from MD3) and `:youtube:engine:test --tests '*UpstreamReleaseVerifierTest*'` (signature against the pinned yt-dlp key with build-logic's and the app's OpenPGP code, SHA-256, `ORIGIN`, top-level packages, the self-test's API probe, and every recorded scenario through `ReplayRH` in contract mode with `-Preplay=contract` — the blocking gate of [04 Engine canary](04-youtube.md#engine-canary)); then both shim suites in strict mode as a non-blocking report, whose mismatches open or update the `engine-canary` issue asking for a re-record without failing the job. 4. Compare the fingerprint of `https://github.com/yt-dlp/yt-dlp/blob/master/public.key` with the pinned one (04 open question 19); a change opens an issue but does not block. 5. On green, output the candidate's `version`, `tag`, `sha256` and `ejsVersion`; on red, `scripts/ci/report-nightly.sh engine-canary --label engine-canary` opens or updates one issue with the failing scenarios |
| `approve` (needs `detect` and `gate`; runs when `gate` is green, when it was skipped because nothing is new — the heartbeat — or with `revoke`) | `engine-approval` (deployment branch `main` only, no reviewer; secret `NEUTRODYNE_ENGINE_MANIFEST_KEY`); `contents: read` | `scripts/engine/make-engine-manifest.sh` writes `engine/ytdlp-approved.json` (`sequence + 1`, `issuedAt`, the candidate, `shimApi` = the range both shim suites ran, `revoked` carried over plus the `revoke` input); `scripts/engine/sign-engine-manifest.sh` signs the exact bytes with the Ed25519 key (`openssl pkeyutl -sign -rawin`, [OpenSSL pkeyutl](https://docs.openssl.org/3.0/man1/openssl-pkeyutl/)) into `ytdlp-approved.json.sig` (base64) and verifies the result against the committed public key before anything leaves the job (a key mismatch fails here, not on users' devices); `actions/upload-pages-artifact` with the `engine/` directory. The key is decoded into `$RUNNER_TEMP` and deleted in an `always()` step |
| `deploy` (needs `approve`) | `github-pages`; `pages: write`, `id-token: write` | `actions/deploy-pages` ([deploy-pages](https://github.com/actions/deploy-pages)); then poll the public URL until it serves the new `sequence` (≤ 15 min, Unverified Pages cache lifetime, 04 open question 18) and record the approval latency (upstream `published_at` → served) in the job summary |

Heartbeat: `approve` runs after **every** canary run whose `gate` is green or skipped, not only with a new candidate; without one it re-publishes the unchanged manifest together with `engine/ytdlp-heartbeat.json` (`{"kind": "heartbeat", "lastRunAt": "…Z", "sequence": <current manifest sequence>}`) and its Ed25519 signature `ytdlp-heartbeat.json.sig`. Both apps fetch it with the manifest (04 [Update flow](04-youtube.md#update-flow)); a validly signed heartbeat older than 48 h puts "Engine approvals stale since {date}" into Settings › YouTube's status line and diagnostics, and never blocks anything. A canary stopped by GitHub's 60-day rule or a broken secret is thereby visible to users and maintainers: the stale-heartbeat line in Settings › YouTube and in diagnostics is the alarm. There is no mirror to host a second one ([PO-34](../PLAN.md#48-further-product-owner-decisions)); a maintainer's own outside check of the served heartbeat is optional and not planned (Unverified which service would host it).

Rules: the signing key never exists in a job that runs fetched code (`gate` runs yt-dlp's code in both shim suites; `approve` only writes and signs JSON); a Pages deployment replaces the whole site, so the artifact always contains the complete `engine/` directory (Unverified for partial uploads; nothing else is hosted on the Pages site); the repository's Pages source is "GitHub Actions" (M9b setting). The canary never approves a nightly or a version below the version bundled in the current release, and it never commits to the repository: moving the bundled version stays a reviewed PR with `bump-ytdlp.sh` ([Review rules per group](#review-rules-per-group)). Time budget: upstream stable release → approved manifest served ≤ 6 h (N11; [Time budgets](#time-budgets)).

Key custody for `NEUTRODYNE_ENGINE_MANIFEST_KEY`, the project's only private signing key (the APK key is public and committed, [Committed keystore](#committed-keystore); the desktop builds and the server image carry no publisher key): generated once in M9b by a maintainer (`openssl genpkey -algorithm ED25519`, [OpenSSL genpkey](https://docs.openssl.org/3.0/man1/openssl-genpkey/)), stored only as that secret of the `engine-approval` environment, and the local file deleted — no ceremony, no offline copy ([04 Security notes](04-youtube.md#security-notes)). Its public key is committed in slot 1 of `engine-manifest-ed25519.pub`; slot 2 is the rotation slot and stays empty in normal operation.

- **Rotation** (planned, or at once after a suspected leak): generate the next key the same way and store it as `NEUTRODYNE_ENGINE_MANIFEST_KEY_NEXT` in `engine-approval`; ship its public half in slot 2 of the next release (APKs and desktop builds pin the same file). After a leak, switch at once: `approve` signs with the next key from then on, and builds that do not pin it yet reject new manifests and keep their last approved engine (fail safe). Otherwise switch once the release with slot 2 has been the latest release for 30 days. Switching means moving the next key into `NEUTRODYNE_ENGINE_MANIFEST_KEY` and deleting `…_NEXT`; a later release moves it to slot 1 and empties slot 2.
- **Loss** (the secret deleted or unreadable): there is no copy, so a new key is generated and pinned in the next release ([PLAN N12](../PLAN.md#22-non-functional-requirements)). Until users install that release, their engines stay on the last approved version; "upstream stable" and "Reset to bundled" keep working.
- A leaked manifest key can only choose among genuine upstream-signed yt-dlp releases at or above the bundled version (risk M7r). A spoofed APK signed with the public APK key, or a tampered desktop build, replaces the pinned keys along with the whole app, which no engine check can prevent (risk P10).

### Helper workflows

| Workflow | Trigger | Does |
|---|---|---|
| `record-screenshots.yml` | `workflow_dispatch` (input: branch, tier) | records both golden sets on `ubuntu-24.04` (`./gradlew allTests test --tests '*Screenshot*' -Proborazzi.test.record=true -PscreenshotTier=…`, which covers the Android host captures and `recordRoborazziDesktop`); uploads `screenshots-<sha>.zip` |
| `keepalive.yml` (M0a) | `schedule: cron "41 4 1 * *"` (monthly) and `workflow_dispatch` | `gh workflow enable` for `nightly.yml`, `engine-canary.yml` (from M9b) and itself (`permissions: actions: write`). In a public repository GitHub disables scheduled workflows "when no repository activity has occurred in 60 days" ([disable and enable workflows](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows)), and a disabled canary would stop approving engine fixes without any failure issue. Unverified: which events count as activity and whether re-enabling resets the clock; the engine heartbeat ([engine-canary.yml](#engine-canaryyml)) detects a stopped canary either way |

Baseline and startup profiles are generated by a maintainer on a managed device (`./gradlew :app:generateBaselineProfile`, [01 Release build and baseline profiles](01-foundation.md#release-build-and-baseline-profiles)) and committed through a normal PR; no workflow generates them, and `release.yml` has no emulator. The `baseline-profile.yml` workflow of an earlier draft stays removed.

Pushing commits from workflows is deliberately avoided: pushes made with `GITHUB_TOKEN` do not trigger new workflow runs, so a bot commit would leave the PR without required checks.

### CI scripts

| Script | Purpose |
|---|---|
| `scripts/ci/check-apk.sh [--published\|--no-engine\|--alignment-only] <apk…>` | per-ABI size budgets, 16 KB alignment including Chaquopy's asset-extracted `.so` files, forbidden content (debug code included), allowed native libraries and Python packages, manifest facts of a published release build (not debuggable, no `testOnly`), locale config, metadata hygiene, signer ([Build-output checks](#build-output-checks)) |
| `scripts/ci/public-cert-sha256.sh [--colons]` | prints the SHA-256 of the certificate in the committed `signing/neutrodyne-public.keystore` (`keytool -list -v -keystore signing/neutrodyne-public.keystore -storepass neutrodyne -alias neutrodyne`), lower-case hex or colon-separated; the expected signer for `release.yml`'s `android` job and `check-apk.sh --published` ([Committed keystore](#committed-keystore)) |
| `scripts/ci/check-desktop-image.sh [--no-engine] <image dir>` | 11's [image scan rules](11-desktop.md#image-scan-rules) on an app image (the Linux image in `desktop-smoke`, every target's images in `desktop-matrix` and `release.yml`); prints installed and package sizes (PB27); with `--no-engine` also asserts that no Python tree, yt-dlp file or `:youtube:ytdlp-desktop` JAR is present |
| `scripts/ci/check-runtime-sources.sh --image <dir> \| --release <dir>` | the runtime exception's checks ([11 Runtime exception obligations and checks](11-desktop.md#runtime-exception-obligations-and-checks)): for an image, `JAVA_VERSION` of `runtime/release` equals `desktopApp/runtime.lock` (a jlink'd `release` holds only `JAVA_VERSION` and `MODULES`), `java.vendor` and `java.vendor.version` of the smoke-mode `SMOKE` line equal the lock, every native library under `runtime/` is byte-identical to the same file of the pinned Temurin archive (macOS: both copies with signatures removed; Linux: the archive's copy after the same `objcopy -g` jlink's `--strip-debug` applies), and `runtime/legal/` is present and non-empty; a release directory carries `openjdk-{jdk}-temurin-sources.tar.gz` with the lock's SHA-256 and a `RUNTIME-SOURCES.md` naming that version (and, when the server image's Temurin differs, its tarball too, [10 Image sources and the runtime exception](10-sync.md#image-sources-and-the-runtime-exception)), `wix-{wix}-src.tar.gz` with `wix.lock`'s SHA-256 when an MSI ships and `python-build-standalone-{pbsTag}-src.tar.gz` with `python-components.lock`'s SHA-256 when the desktop engine ships (2026-10-05) |
| `scripts/ci/check-embedded-natives.sh` (2026-10-05) | lists the native libraries (`.so`, `.dylib`, `.dll`, `.jnilib`) and data resources inside every JAR and AAR on `:app`'s `releaseRuntimeClasspath` and `:desktopApp`'s `runtimeClasspath` (Skiko, `sqlite-bundled`, quickjs-kt, JNA's libffi, OkHttp's `PublicSuffixDatabase.list`) and fails on one that has no manual AboutLibraries entry and no `THIRD_PARTY_NOTICES.md` section, because Licensee sees only POM licences ([01 Licence structure](01-foundation.md#licence-structure)); runs in `static` and in `publish` |
| `scripts/ci/check-server-image.sh [--no-sources] <image ref>` | the server image's content and source coverage ([Build-output checks](#build-output-checks)) |
| `scripts/ci/make-update-json.sh` | writes `neutrodyne-update.json` from `gradle.properties`, the tag, `changelogs/<versionCode>.txt`, `neutrodyne.repoUrl`, the renamed APKs and desktop assets, the server JAR and the image's index digest ([Update manifest](#update-manifest)) |
| `scripts/ci/check-update-json.sh` | validates the manifest before the draft is published ([Update manifest](#update-manifest)); also run in PRs that touch the release scripts |
| `scripts/ci/check-frozen-schemas.sh` | for every `N.json` that exists at the newest `v*` tag, `git diff --exit-code <tag> -- <file>` ([02 Schema export and versioning](02-data-model.md#schema-export-and-versioning)) |
| `scripts/ci/repro-build.sh [--path P] [--cpus N] [--umask U] <gradle args…>` | container build used by `repro` and `release.yml`'s `android` job; every Android build in it is signed with the committed keystore, so there is no signing option and no secret to mount; the `repro` job adds Temurin 25 from `runtime.lock` for the desktop JARs |
| `scripts/ci/install-android-sdk.sh` | the host's pinned cmdline-tools archive (`scripts/ci/android-sdk.lock`: revision + URL + SHA-256), `platforms;android-37.0`, `build-tools;36.0.0`; an existing `cmdline-tools/latest` is reused only when its `source.properties` reports the pinned `Pkg.Revision`, otherwise the verified zip replaces it (2026-10-08); the shipped `bin/sdkmanager` on linux-x64, the pure-Java `SdkManagerCli` inside `lib/` on the other hosts |
| `scripts/ci/bmgr-check.sh <package>` | 05's `bmgr` procedure and assertions (package `ch.lkmc.neutrodyne`, the release build) |
| `scripts/ci/report-nightly.sh <job> [--label L]` | issue per failing nightly or canary job; runs only on `main` so branch dispatches cannot open or close the shared streak (2026-10-08) |
| `scripts/ci/apply-screenshots.sh <run-id>` | download and unpack recorded screenshots (both sets) locally |
| `scripts/ci/verify-tag.sh` | release preconditions (no version suffix), the `make_latest` decision (true when no published release exists yet, "release not found"), `mac_kind` and the expected asset list ([release.yml](#releaseyml) step 1) |
| `scripts/ci/network-capture.sh --android \| --desktop \| --server` | v1.0 gate network capture ([v1.0 gate](#v10-gate)); maintainer-run, needs internet: Android through the emulator's `-tcpdump` with `tshark` DNS names and TLS SNI; desktop through a logging CONNECT proxy (the JVM started with `-Dhttps.proxyHost`/`-Dhttps.proxyPort`, which OkHttp's default `ProxySelector` honours) plus a `tshark` DNS/SNI capture for the engine child, which never uses a proxy (11 clears its proxy variables); server through a capture of the compose network |
| `scripts/ci/start-emulator.sh` | fallback emulator start for API 26 / API 37 if android-emulator-runner or GMD cannot ([Gradle Managed Devices](#gradle-managed-devices)) |

The workflows also call 04's `scripts/engine/*.sh` and `scripts/youtube/record-responses.sh`, 11's `scripts/desktop/mac-zip.sh` and `playback/native/ffmpeg/build.sh` (through `buildFfmpeg`), and `scripts/engine/trim-python.sh` (through `trimPythonStandalone`).

### Hardening

- Every `uses:` is pinned by commit SHA; Renovate's `helpers:pinGitHubActionDigests` keeps the pins current.
- Never `pull_request_target`; fork PRs get no secrets. No build needs one: every APK, PR builds included, is signed with the committed public key ([Committed keystore](#committed-keystore)); desktop builds carry no publisher signature (macOS's ad-hoc signature needs no keychain); the server image is pushed with the job's `GITHUB_TOKEN` (`packages: write`) only from `release.yml`'s image jobs.
- Repository rulesets: `main` and `release/*` require a PR and the checks `static`, `unit`, `assemble`, `desktop-smoke` and `server`; linear history; no force pushes; maintainers may bypass only to push the release commit created by `release.sh`. Tag ruleset: only maintainers create or delete `v*` tags. With a public signing key and unsigned desktop builds, the tag ruleset, the `release` environment's required reviewer and immutable releases are what keep an unreviewed build from becoming a release.
- Repository settings (M0a): immutable releases on; environments `release` (required reviewer; no signing secret; used only by `publish`) and, from M9b, `engine-approval` (deployment branch `main` only) and `github-pages`; Pages source "GitHub Actions" (M9b); GitHub secret scanning with push protection on (the committed keystore may need a documented bypass, [Committed keystore](#committed-keystore)); private vulnerability reporting on ([SECURITY.md](#security-reporting)). From MS1: the GHCR package `neutrodyne-server` linked to the repository (it inherits the repository's access) and made public once ([release.yml](#releaseyml)); Docker Hub is never used ([PO-2](../PLAN.md#po-2-distribution-channels)).

| Secret | Scope | Used by |
|---|---|---|
| `PODCASTINDEX_KEY`, `PODCASTINDEX_SECRET` | environment `release` | only after Podcast Index grants written permission (PO-3 option A): passed as `-Pneutrodyne.podcastIndexKey/Secret` to `assembleRelease` and the desktop packaging in `release.yml` (those jobs then join the environment) |
| `NEUTRODYNE_ENGINE_MANIFEST_KEY` (and `NEUTRODYNE_ENGINE_MANIFEST_KEY_NEXT` only during a rotation) | environment `engine-approval` | signing `ytdlp-approved.json` and the heartbeat ([engine-canary.yml](#engine-canaryyml)) |

No other secret exists; GHCR pushes and attestations use the workflow's `GITHUB_TOKEN` with job-scoped permissions ([release.yml](#releaseyml)). PR, nightly and canary builds read no secret; the only build-time secret that may ever enter an APK or a desktop build is the Podcast Index key, and only through `release.yml` after written permission ([D26](../PLAN.md#3-key-decisions)); the server needs none. Removed 2026-10-05 (PO-34, PO-35): the keystore secrets `NEUTRODYNE_KEYSTORE_B64`, `NEUTRODYNE_KEYSTORE_PASSWORD`, `NEUTRODYNE_KEY_ALIAS` and `NEUTRODYNE_KEY_PASSWORD`, the variables `NEUTRODYNE_CERT_SHA256` and `NEUTRODYNE_PREVIOUS_CERT_SHA256`, and `CODEBERG_MIRROR_KEY`.

### Time budgets

| Pipeline | Target | Hard timeout |
|---|---|---|
| PR checks (`static`, `unit`, `assemble` → `desktop-smoke` and `server`) | ≤ 20 min wall clock (PLAN M0 AC1) | 30 / 45 / 30 + 20 + 20 min |
| `instrumented` on `main` | ≤ 45 min | 75 min |
| Tag → published immutable GitHub release with every asset (APKs, desktop installers for four targets, sources, server JAR and image) and the image tags | ≤ 60 min including the environment approval (N11, PLAN M11 AC2); the slowest desktop job, usually `macos-15`, sets the pace (risk P15; first measured in M0b and MS1, [Open questions](#open-questions) 30) | 90 min per job, 150 min per run |
| Upstream yt-dlp stable release → approved engine manifest served from GitHub Pages | ≤ 6 h (N11: one canary period plus a ≈ 30-min run and the Pages deployment) | 60 min per run |
| Canary gate red (04's runbook path 2) → re-recorded fixtures merged and `engine-canary.yml` dispatched | ≤ 24 h (maintainer target, N11) | — |
| Engine heartbeat age (served `ytdlp-heartbeat.json`) | ≤ 6 h normally; > 48 h is an alarm | — |
| Nightly | ≤ 3 h total; `desktop-matrix` ≤ 90 min per target | per job |

When `unit` exceeds 15 min at p50 over a week, the fix order is: raise `maxParallelForks` only if memory allows, move slow Robolectric and desktop UI suites into a second `unit-2` job (split by module list), then move `FULL`-only screenshot variants out of `PR`.

---

## Static analysis

Serves N8, N10, N11, N12. Delivered in M0a (Lint, formatting, Python licence and APK content checks, with the engine stack from M9a), M0b (desktop image and runtime-source checks, the server's Licensee, the brand check), MD0 (native licences, C formatting), MS1 (server image scan). Honours [D3](../PLAN.md#3-key-decisions), [D60](../PLAN.md#3-key-decisions), [D89](../PLAN.md#3-key-decisions), [D95](../PLAN.md#3-key-decisions).

### Gates

| Gate | Blocking | Where | Configuration |
|---|---|---|---|
| Android Lint | yes | `static` (`:app:lintRelease` with `checkDependencies`: the Android-only modules, the KMP modules' Android target and the JVM islands) | [below](#android-lint) |
| Spotless + ktlint 1.8.0 + compose-rules 0.6.7; clang-format for `ndmedia` | yes | `static` | [below](#formatting) |
| detekt 2.0.0-alpha.6 | no (SARIF only) | `static` | `config/detekt/detekt.yml` |
| Licensee (`:app` `licenseeRelease`, `:desktopApp` and `:sync:server` `licensee`), module graph on the three shells and `:core:testing`, `verifyDependencyPolicy` (including the scope revision's bans: ProGuard, jextract, JavaFX Media, JavaCPP `-gpl`, vlcj, GStreamer, logback, argon2-jvm, MariaDB JDBC, `dev.dirs`, Dagger), `verifyManifestPermissions` (the release manifest: no `debuggable`, no `testOnly`), `checkSpdxHeaders` (our sources carry `Unlicense` only; no GPL, LGPL or AGPL identifier in any source file — LGPL code is never vendored into the repository), `checkBannedApis` (per source set: no `java.*`/`android.*` in `commonMain`; `ProcessBuilder`/`Runtime.exec` only in `:youtube:ytdlp-desktop`; `java.awt.Desktop` only in desktop code of `:desktopApp`, `:desktop:system` and `:core:ui`; the `Text("` literal scan), `checkBrandAssets` | yes | `static` | [01 Gradle-side policy tasks](01-foundation.md#gradle-side-policy-tasks) |
| `checkPythonLicences` (both engine hosts' locks against D3's allow-list), `checkNativeLicences` (`playback/native/native-components.lock`), `verifyBundledYtDlp` (vendored yt-dlp against its upstream signature) | yes | `static` | [01 Python and native components](01-foundation.md#python-and-native-components) |
| `checkTranslations` (Compose resources and Android `res/`: [Localisation](#workflow)) | yes | `static` | 09 specifies it; 01's `neutrodyne.quality` registers it ([Open questions](#open-questions) 36) |
| Python shim tests (`shimTest`, `shimTestStdio`) | yes | `unit` | [04 Testing](04-youtube.md#testing) |
| KGP version assertion | yes | `static` | [01 S1](01-foundation.md#s1-kgp-2420-under-agp-941) |
| Room schema drift and frozen versions | yes | `unit`, `static` | [CI scripts](#ci-scripts) |
| APK size per ABI, 16 KB alignment (including Chaquopy's asset `.so` files), forbidden content, release-build facts (not debuggable, no debug code, committed certificate) | yes | `assemble`, `release.yml` `android`, nightly `no-engine-build` and `api37-16k` | [Build-output checks](#build-output-checks) |
| Desktop image scan, runtime sources, macOS `codesign --verify --deep --strict` | yes | `desktop-smoke` (Linux x64), nightly `desktop-matrix`, `release.yml` `desktop` and `publish` | [Build-output checks](#build-output-checks) |
| Server image content and source coverage | yes | `server` (content, from MS1), nightly `server-image-smoke`, `release.yml` `server-image` and `publish` | [Build-output checks](#build-output-checks) |
| Accessibility checks | yes | `unit` (Robolectric ATF; desktop semantics assertions), `instrumented` | [08 Automated checks](08-ui-ux.md#automated-checks) |

### Android Lint

Configured by `neutrodyne.android.lint` (hook owned by 01):

```kotlin
lint {
    warningsAsErrors = true
    abortOnError = true
    checkDependencies = true                  // :app only: one report covering every module with Android code, islands via com.android.lint
    sarifReport = true
    baseline = file("lint-baseline.xml")      // :app only
    disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion",  // Renovate's job; offline-safe
                     "MissingTranslation")    // partial Weblate languages by design; Compose resources: checkTranslations
    fatal += setOf("StringFormatInvalid", "StringFormatMatches", "MissingQuantity", "UnusedResources", "ExtraTranslation")
    enable += setOf("StopShip")
}
```

`static` lints the published variant (`:app:lintRelease`); the few `app/src/debug/` sources are reviewed, not linted on every PR. Lint sees Android `res/` but not Compose resources (they are packaged as assets), so its string checks cover only Android-only labels; `checkTranslations` covers the rest. Unverified: that Lint analyses the KMP modules' Android target sources through `checkDependencies` (S8, 01).

**Baseline policy:** `app/lint-baseline.xml` is created empty in M0a. Milestone work never adds entries (PLAN DoD). The only allowed additions are new check IDs introduced by an AGP/Lint bump, added in the Renovate PR together with an issue to burn them down before the next minor release. `@Suppress`/`tools:ignore` require a comment naming the reason.

### Formatting

Spotless (root plugin `neutrodyne.quality`): `kotlin { target("**/*.kt"); targetExclude("**/build/**"); ktlint("1.8.0").customRuleSets(listOf("io.nlopez.compose.rules:ktlint:0.6.7")) }`, `kotlinGradle { target("**/*.kts"); ktlint("1.8.0") }` — every source set of every module, `commonMain` included. Rules come from `.editorconfig`:

```ini
root = true
[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 4
[*.{kt,kts}]
max_line_length = 120
ktlint_code_style = ktlint_official
ktlint_function_naming_ignore_when_annotated_with = Composable
compose_allowed_composition_locals = LocalAppNavigator,LocalNavTab,LocalPaneLayout,LocalMiniPlayerInset,LocalReducedMotion,LocalSnackbarHost,LocalArtworkTintEnabled,LocalScrollbars,LocalSystemUiState,LocalPlatformActions,LocalSettingsBadge
compose_disallow_material2 = true
[*.{xml,yml,yaml,json,toml}]
indent_size = 2
[*.md]
trim_trailing_whitespace = false
```

A new `CompositionLocal` requires adding its name here in the same PR (review point). Verified 2026-10-06 (M0a): the compose-rules 0.6.7 key names are exactly `compose_allowed_composition_locals` and `compose_disallow_material2` (read from the `io.nlopez.compose.rules:ktlint` ruleset jar).

`ndmedia`'s C, C++/WinRT and Objective-C sources (`playback/native/src/native/`, from MD0) follow `.clang-format` (based on LLVM style, 4-space indent, 120 columns), checked in `static` with `clang-format --dry-run --Werror` from the runner image's LLVM (a build tool, never shipped; Unverified that `ubuntu-24.04` carries a recent `clang-format`, else it is installed pinned). Vendored upstream sources (miniaudio, the C++/WinRT headers) are excluded. Python scripts and the shim are checked with `ruff format --check` (MIT; a build tool, never shipped).

### detekt

`buildUponDefaultConfig = true`, `parallel = true`, baseline `config/detekt/baseline.xml`, SARIF uploaded with category `detekt`; every source set of every Kotlin module. Tuned rules: `CyclomaticComplexMethod` threshold 15, `LongMethod` 80 lines (ignore `@Composable`), `MagicNumber` off in tests and Compose files, `ForbiddenComment` for `TODO` without an issue link, `TooGenericExceptionCaught` on (reinforces 01's `suspendRunCatching` rule). Becomes blocking when a stable detekt release supports Kotlin 2.4 and AGP 9 (1.23.8 stops at Kotlin 2.0.21 / AGP 8.8.1); Renovate must not "downgrade to stable". Verified 2026-10-06 (M0a): the 2.0 Gradle plugin ID is `dev.detekt` (the 1.x `io.gitlab.arturbosch` ID is gone) and the config keys renamed — `allowedComplexity`/`allowedLines` replace `threshold`, `ForbiddenComment` lives in `style`; `config/detekt/detekt.yml` uses the new names.

### Build-output checks

Four scripts check what CI builds; each fails the job, so a non-compliant artefact is never uploaded, let alone published.

**`scripts/ci/check-apk.sh`** runs in `assemble` (on the release APKs), in `release.yml`'s `android` job (`--published`), in the nightly `no-engine-build` (`--no-engine`: the published checks plus the no-engine rules) and in `api37-16k` (`--alignment-only`). Inputs: the APKs, `app/policy/locales.txt`, `youtube/ytdlp/python-components.lock` (01: which native libraries and top-level Python packages an APK may contain) and the expected certificate from `public-cert-sha256.sh`. It also reads `neutrodyne.youtubeEngine` from `gradle.properties`, so an emergency release is checked with the no-engine rules. Because R8 runs with obfuscation off ([01 Release build and baseline profiles](01-foundation.md#release-build-and-baseline-profiles)), class-name rules work on the minified dex.

1. **Size and set** (blocking): exactly three published APKs (`arm64-v8a`, `x86_64`, `armeabi-v7a`) and no universal APK ([D77](../PLAN.md#3-key-decisions)); `arm64-v8a` and `x86_64` < 40 MB each (PB12), `armeabi-v7a` < 30 MB (PB13) — Unverified estimates for R8-minified release APKs until S7 and S19 measure them in M0a; a miss goes to the PO (PLAN M0 AC1). Sizes go to the job summary and a nightly artifact for trends; the engine's share is printed separately (Chaquopy's `jniLibs` and assets, the yt-dlp asset).
2. **16 KB** ([16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)): `zipalign -c -P 16 -v 4` on every published APK (the uncompressed `.so` files in `lib/<abi>/`: `sqlite-bundled` ([01 S6](01-foundation.md#s6-sqlite-bundled-16-kb-alignment-and-size)), Chaquopy's `libpython3.14.so`, `libcrypto`, `libssl`, `libsqlite3`, `libc++_shared`, and quickjs-kt's library when the JS provider ships); and `llvm-readelf -lW` on every ELF file in `lib/` and inside Chaquopy's asset zips (the `lib-dynload` extension modules, which Chaquopy extracts at run time and zipalign never sees): every `LOAD` segment aligned to ≥ 0x4000. Unverified: the `llvm-readelf` binary name on the runner image (fallback: binutils `readelf -lW`, which reads program headers of any ELF architecture). With legacy native packaging (S7) the `lib/` files are compressed and only the ELF check applies.
3. **Content** (blocking; risks L2 and, for debug code, PLAN 7.2): no zip entry, nested asset-zip entry or dex class descriptor (`dexdump`, build-tools 36.0.0) matching `mutagen`, `readline`, `libreadline`, `org/schabi/newpipe` or `org/mozilla/javascript`; native libraries and top-level Python packages only as the lockfile lists them (Python packages: `neutrodyne_ytx`, the standard library, `yt_dlp`, `yt_dlp_ejs`); the `armeabi-v7a` APK contains no Python or Chaquopy native library, and its unusable Python assets are reported with their size (tolerated only while PB13 holds, 01 open question 13). **No debug code** ([01 Debug build type](01-foundation.md#debug-build-type)): no `Lleakcanary/` or `Lshark/` class, no `androidx.compose.ui.tooling.PreviewActivity` and no activity contributed by `ui-test-manifest` in the merged manifest (`aapt2 dump xmltree --file AndroidManifest.xml`), no class compiled from `app/src/debug/` or `app/src/benchmarkRelease/` (`BenchmarkSeedReceiver`; the script derives the class descriptors from those directories' packages and file names). With the engine, the 64-bit APKs keep `ch.lkmc.neutrodyne.youtube.ytdlp.ytx.PyHttp` and `YtxTestHooks` (R8 keep rules for Python and E7, 01). `--no-engine`: no `Lch/lkmc/neutrodyne/youtube/ytdlp/` class, no Chaquopy, CPython or yt-dlp file and no `YtxService` in the merged manifest. The M0a negative checks of PLAN M0 AC2 (an APK with `mutagen/__init__.py`, one with `libreadline.so`) are recorded in [01 Verification log](01-foundation.md#verification-log).
4. **Manifest facts of a published build** (`--published`, PLAN M0 AC7 and AC9): package `ch.lkmc.neutrodyne` (never `.debug`), **no** `application-debuggable`, no `android:testOnly`, `versionCode` and `versionName` equal to `gradle.properties` (no `-debug` suffix).
5. **Locale config:** `aapt2 dump xmltree --file res/xml/_generated_res_locale_config.xml` (verified generated file name 2026-10-06, S11; the manifest's `android:localeConfig` points at it) on every published APK lists exactly the locales of `app/policy/locales.txt` — no pseudo-locales, no library-only translations; `aapt2 dump configurations` shows no `en-rXA` or `ar-rXB` resource configuration; the packaged Compose resources (`assets/composeResources/**/values-*`) contain no `values-en-rXA`/`values-ar-rXB` directory and, once S11 has settled the filter, no locale outside `locales.txt` ([Shipped locales and per-app language](#shipped-locales-and-per-app-language)).
6. **Metadata hygiene and signing:** `unzip -l` shows no `META-INF/version-control-info.textproto` ([vcsInfo off](#hygiene)); `--published` additionally checks with `apksigner verify --verbose --print-certs` that the signing schemes are exactly v2 and v3 and that the signer equals `public-cert-sha256.sh` (the dependency-info block stays off, 01).

M0a implementation notes (2026-10-06): the `readline` name scan exempts exactly `**/_pyrepl/readline.pyc` — CPython 3.14 ships that file in `stdlib-common.imy` (its own pure-Python readline replacement, part of the lockfile's CPython component); the ELF check uses `llvm-readelf`/`readelf -lW` when present and falls back to an embedded Python program-header parser because the pinned Python release container carries neither; the engine-presence rule (`Lch/lkmc/neutrodyne/youtube/ytdlp/` in any `classes*.dex`) aggregates across dexes — a per-dex rule misfires on multi-dex debug builds; `unzip` is optional in `install-android-sdk.sh` (python3 `zipfile` fallback, then `chmod +x` on `bin/` because zipfile drops mode bits); keytool labels the fingerprint `SHA256:` (no hyphen); the generated ELF-alignment TSV ends every row with a newline — Bash's `while read` loop silently drops an unterminated final record, which would have skipped the last `.so` of every APK (2026-10-06). (2026-10-08) api37-16k: the abort loop traced to tooling, not only rendering — android-emulator-runner reuses the image's `cmdline-tools/latest` (revision 12.0 on `ubuntu-24.04`), whose avdmanager cannot parse a dotted api level: it exits 0, keeps `image.sysdir.1` right, yet writes `target=android-0` into the AVD `.ini` descriptor and no `target=` into `config.ini` (verified locally with the real `android-37.0` ps16k image: 12.0 → `android-0`, pinned 23.0 → `android-37.0`; upstream ReactiveCircus/android-emulator-runner PR 494, issuetracker 546200928). `install-android-sdk.sh` reuses `latest` only when its `source.properties` reports the pinned `Pkg.Revision` and replaces it through the checksum-verified zip otherwise — never `sdkmanager "cmdline-tools;latest"`, which cannot overwrite the directory it runs from and lands in an ignored `latest-2`. The tools-side reproducer needs no KVM: with the image installed, `echo no | avdmanager create avd --force -n test --package 'system-images;android-37.0;google_apis_ps16k;x86_64'` then `grep '^target=' ~/.android/avd/test.ini` prints `android-0` under 12.0 and `android-37.0` under 23.0. KVM-free hosts cannot boot the image, so the guest-render abort count remains a CI observation, not a local proof — nightly run 37858251837 passed the api37-16k debug suite, the release smoke and the 16 KB alignment check with the pinned tools in place (2026-10-08); that run's `instrumented-full` leg died before tests on a runner KVM fault (`x86_64 emulation currently requires hardware acceleration`), unrelated to the job change — `ci.yml`'s instrumented leg was green on the same app code. A `latest.new` left behind by an interrupted install used to swallow the next staged tree (`mv` nests into an existing directory, leaving `latest/bin/sdkmanager` missing while the run still exited 0 once the packages were installed); staging now removes it first, and `scripts/ci/tests/install-android-sdk.test.sh` pins the reuse, stale-revision replacement, stale-staging, checksum-abort and package-install cases against a fixture archive — its same-named counterpart on the #34 branch keeps the cross-platform host-matrix cases, so a later merge holds both scenario sets. The api37-16k logcat tripwire fails closed on a missing, empty or unreadable capture rather than letting `grep` exit 2 pass for clean.

M0b implementation notes (2026-10-08): cmdline-tools 23.x's `bin/sdkmanager` is only a shim that execs the native `bin/android` launcher, and Google ships it for linux-x64, macosx-x86_64 and windows-x64 only — there is no linux/arm64 archive and the macOS archive (`commandlinetools-mac_x86_64-*`) is x86_64 — so `install-android-sdk.sh` selects the archive per host from `scripts/ci/android-sdk.lock` (the desktop jobs need the SDK only to configure AGP, which runs no native SDK tool) and drives the package install through the pure-Java `SdkManagerCli` still inside `lib/` on every host but linux-x64, which keeps the shipped `bin/sdkmanager`; every step calling the script exports `ANDROID_SDK_ROOT` equal to `ANDROID_HOME`, because AGP fails configuration when the runner image's inherited `ANDROID_SDK_ROOT` points at its own SDK; every `run:` step of the `desktop-matrix` and release `desktop` jobs sets `shell: bash`, because the windows-2025 default (pwsh) misparses `-P…` Gradle properties into task names (all three from nightly run 37825401807).

**`scripts/ci/check-desktop-image.sh`** runs on every app image: the Linux x64 image in `desktop-smoke`, every target's images in `desktop-matrix` and `release.yml`'s `desktop` jobs, and the no-engine image in `no-engine-build` (`--no-engine`). The rules are 11's ([11 Image scan rules](11-desktop.md#image-scan-rules)): forbidden files (`_dbm`, `libreadline`, Tcl/Tk, `pip`, `mutagen`, `qjs`, Deno, Node, Bun, `AppRun`, `libfuse`, ProGuard, jextract, JavaFX, vlcj, GStreamer, the FFmpeg libraries we do not build), `runtime/legal/` and `runtime/release`, FFmpeg's libraries under their upstream names with "LGPL version 2.1 or later" in `ffmpeg-license.txt`, JARs only from `:desktopApp`'s Licensee-checked runtime classpath, the Python tree against its lock, no test classes or fixtures, `codesign` on macOS. 09 adds the localisation rule of `check-apk.sh` item 5 for the packaged Compose resources ([Open questions](#open-questions) 37) and prints installed and package sizes for PB27, which fail the job from MD5 when they exceed the budget.

**`scripts/ci/check-runtime-sources.sh`** enforces the runtime exception ([D3](../PLAN.md#3-key-decisions), [D89](../PLAN.md#3-key-decisions), risk L5): `--image` on every desktop image (`JAVA_VERSION` from `runtime/release`, vendor and version from the smoke line, native libraries against the pinned archive, all against `desktopApp/runtime.lock`; `runtime/legal/` present), `--release` in `publish` (the attached `openjdk-{jdk}-temurin-sources.tar.gz` has the lock's SHA-256; `RUNTIME-SOURCES.md` names that vendor and version; both are present whenever a desktop asset or the server image ships; the WiX and PBS source assets are present with their locks' SHA-256 whenever an MSI or the desktop engine ships) ([11 Runtime exception obligations and checks](11-desktop.md#runtime-exception-obligations-and-checks)).

**`scripts/ci/check-server-image.sh`** (from MS1) on the image of each architecture and, in `publish`, with the source bundle: the base layers equal the pinned `gcr.io/distroless/java25-debian13` digest of `sync/server/deploy/Dockerfile`; our layer adds only `/app/neutrodyne-server.jar` (byte-identical to the release's fat JAR), `/app/THIRD_PARTY_NOTICES.md` and the empty `/data`; the image runs as `65532`, declares `VOLUME /data`, `EXPOSE 8787` and the OCI labels (`org.opencontainers.image.source`, `licenses`, `version`); the JAR contains no `proguard`, logback or argon2-jvm class and only Licensee-allowed dependencies; the runtime's `legal/` notices are present; and, without `--no-sources`, every package of the image's package records with a GPL or LGPL licence has its source package in `neutrodyne-server-image-sources-{v}.tar.xz`, and the runtime's Temurin version has its source tarball in the release ([10 Image sources and the runtime exception](10-sync.md#image-sources-and-the-runtime-exception); Unverified that distroless images carry dpkg status records, MS1 check, 10).

### PR template

`.github/PULL_REQUEST_TEMPLATE.md` checklist (each line is a checkbox):

- Tests per [Test obligations per change](#test-obligations-per-change); bug fixes include a failing-first regression test; shared code is tested in `commonTest` and passes on the desktop JVM and on Android (PLAN DoD).
- UI changed → screenshots re-recorded for the Android and desktop sets with `record-screenshots.yml`; accessibility checks pass; from MD4, every new action has a keyboard shortcut or context-menu entry on the desktop.
- Strings externalised as Compose resources (Android-only labels in `res/`), plurals used, no concatenation.
- Schema change → version bump, migration and test; a synced column also updates the capture triggers and `SyncCaptureTest` (from MS0).
- New setting → classified `settings`/`device_settings` and registered (01); a portable key declares whether it syncs (`SettingKey.synced`, [D93](../PLAN.md#3-key-decisions)).
- YouTube UI checked in both capability modes (engine present and external mode) against [04 Capability matrix](04-youtube.md#capability-matrix) ([08 Capability differences in UI](08-ui-ux.md#capability-differences-in-ui)).
- No MockK in `commonTest` or `androidTest`; `commonMain` free of `java.*` and `android.*` (`checkBannedApis`).
- Developer tooling only in the `debug` build type (`app/src/debug/`, `debugImplementation`, branches on `BuildInfo.debug`); no `BuildConfig.DEBUG` outside `:app`; any test hook in `main` inert until an instrumentation test sets it, except a shell-only, `DUMP`-protected trigger that runs only production code (05's `SnapshotNowReceiver`; PLAN 7.2, [D2](../PLAN.md#3-key-decisions)); benchmark-only code only in `app/src/benchmarkRelease/`.
- Licences ([D3](../PLAN.md#3-key-decisions)): a new dependency or bundled component is permissive, LGPL dynamically linked with its source attached, MPL-2.0 only as unmodified files or data (plus only the pinned PBS build patches with their source attached, PO-48 proposed default), MS-RL only as the unmodified WiX components jpackage embeds in the MSI with the WiX source attached (PO-48 proposed default), or part of the unmodified OpenJDK runtime under the runtime exception; its Licensee allow-list or lockfile entry (`python-components.lock`, `native-components.lock`, `runtime.lock`), the Licences screens and `THIRD_PARTY_NOTICES.md` are updated in the same PR; no GPL or AGPL anywhere.
- 01's copied-code rule (verbatim from [01](01-foundation.md#copied-code-and-contributions)).
- Design document updated if behaviour deviates (PLAN DoD).
- Shim PRs (`youtube/engine/python/neutrodyne_ytx/`): `shimTest` and `shimTestStdio` green against the bundled yt-dlp (CI) and against the latest approved version (locally: `scripts/engine/bump-ytdlp.sh <approved version>` without committing, then both tasks); recordings re-recorded when the requests changed; `SHIM_API_VERSION` bumped for incompatible changes (04).
- Bundled-engine bumps: only a version the engine canary approved; `bump-ytdlp.sh` output committed unchanged (04).
- Native code or a bundled runtime component (`playback/native/`, `runtime.lock`, the desktop Python lock): `nightly.yml` dispatched with `scope: desktop` on the branch and green on all four targets.
- Sync protocol or server change: a conformance vector for every merge-behaviour change; the protocol version window respected ([10 Versioning](10-sync.md#versioning)).

Issue templates: `bug.yml` (issue form with a `diagnostics` textarea that [Report a problem](#copy-report-and-export) pre-fills and a platform dropdown: Android, Windows, macOS, Linux, sync server), `feature.yml`, `release.md` (the [Release checklist](#release-checklist) as checkboxes).

---

## Dependency updates

Serves N8, N11; mitigates risks T2, T14, T20, T21, T22, M3r, L2, L5, L6. Delivered in M0a (Renovate app installed on the repository, the Chaquopy rule from the first commit), M0b (the desktop runtime and base-image rules), MD0 (native components). YouTube extraction fixes do not travel through Renovate: they reach users as engine updates ([engine-canary.yml](#engine-canaryyml), [04 Hotfix runbook](04-youtube.md#hotfix-runbook)).

### Renovate configuration

Renovate (Mend-hosted GitHub app) reads `gradle/libs.versions.toml`, the wrapper, `build-logic`, workflow files, the release container's image digest in `scripts/ci/repro-build.sh` and the server image's base digest in `sync/server/deploy/Dockerfile`. The bundled non-Gradle components are pinned in lockfiles with checksums — the Temurin runtime in `desktopApp/runtime.lock`, python-build-standalone in `youtube/ytdlp-desktop/python-components.lock`, FFmpeg, miniaudio and the C++/WinRT headers in `playback/native/native-components.lock`, WiX in the Windows desktop job — and are tracked by regex custom managers that only detect new versions; the PR that moves one updates its archive and source checksums together (by hand or with the lock's documented update command, 11), because a hosted Renovate app cannot compute them (Unverified exact custom-manager configuration, M0b; [Open questions](#open-questions) 35). Dependabot stays disabled.

```json
{
  "$schema": "https://docs.renovatebot.com/renovate-schema.json",
  "extends": ["config:recommended", "helpers:pinGitHubActionDigests", ":dependencyDashboard", "docker:pinDigests"],
  "timezone": "UTC",
  "schedule": ["before 6am on monday"],
  "prConcurrentLimit": 5,
  "prHourlyLimit": 2,
  "minimumReleaseAge": "3 days",
  "labels": ["dependencies"],
  "vulnerabilityAlerts": { "schedule": ["at any time"], "minimumReleaseAge": "0 days", "labels": ["security"] },
  "packageRules": [
    { "groupName": "Kotlin toolchain", "matchPackageNames": ["/^org\\.jetbrains\\.kotlin[.:]/", "/^com\\.google\\.devtools\\.ksp/", "/^dev\\.zacsweers\\.metro/", "/^org\\.jetbrains\\.compose[.:]/", "/^org\\.jetbrains\\.androidx\\./"] },
    { "groupName": "AGP and Lint", "matchPackageNames": ["/^com\\.android\\.tools/", "/^com\\.android\\.(application|library|test|lint|kotlin\\.multiplatform\\.library)$/"] },
    { "groupName": "Screenshot stack", "matchPackageNames": ["/^org\\.robolectric:/", "/^io\\.github\\.takahirom\\.roborazzi/", "androidx.compose:compose-bom"] },
    { "groupName": "Media3", "matchPackageNames": ["/^androidx\\.media3:/"] },
    { "groupName": "Ktor", "matchPackageNames": ["/^io\\.ktor[.:]/"] },
    { "groupName": "Room and SQLite", "matchPackageNames": ["/^androidx\\.room3[.:]/", "/^androidx\\.sqlite:/", "org.xerial:sqlite-jdbc"] },
    { "groupName": "AndroidX Test, benchmark and profiles", "matchPackageNames": ["/^androidx\\.test/", "/^androidx\\.benchmark/", "/^androidx\\.baselineprofile/"] },
    { "description": "D4: toolchain minors need a PLAN amendment", "matchPackageNames": ["/^org\\.jetbrains\\.kotlin[.:]/", "/^com\\.android\\.tools\\.build:gradle$/", "/^com\\.android\\.(application|library|test|kotlin\\.multiplatform\\.library)$/", "/^org\\.jetbrains\\.compose$/"], "matchUpdateTypes": ["minor", "major"], "dependencyDashboardApproval": true },
    { "description": "D4: stay on Gradle 9.7.x until Kotlin's tested matrix includes 9.8", "matchManagers": ["gradle-wrapper"], "allowedVersions": "<9.8.0" },
    { "description": "Chaquopy bumps repeat S7's checks and change python-components.lock (01)", "matchPackageNames": ["/^com\\.chaquo\\.python/"], "dependencyDashboardApproval": true, "labels": ["chaquopy"] },
    { "description": "Bundled runtime and native components: lockfile checksums and source bundles move with them (11)", "matchDepNames": ["temurin", "python-build-standalone", "ffmpeg", "miniaudio", "cppwinrt", "wix"], "dependencyDashboardApproval": true, "labels": ["bundled-component"] },
    { "description": "Server base image and release container: digest bumps change the image sources (10)", "matchDatasources": ["docker"], "dependencyDashboardApproval": true, "labels": ["bundled-component"] },
    { "description": "detekt has no stable release for this toolchain", "matchPackageNames": ["/^dev\\.detekt/"], "ignoreUnstable": false },
    { "matchUpdateTypes": ["major"], "dependencyDashboardApproval": true },
    { "matchPackageNames": ["junit:junit", "com.google.truth:truth", "app.cash.turbine:turbine", "io.mockk:mockk", "com.google.testparameterinjector:test-parameter-injector"], "matchUpdateTypes": ["patch"], "automerge": true },
    { "matchManagers": ["github-actions"], "groupName": "GitHub Actions", "schedule": ["before 6am on the first day of the month"] }
  ]
}
```

### Review rules per group

| Group | What the reviewer checks besides green CI |
|---|---|
| Kotlin toolchain (Kotlin, KSP, Metro, Compose Multiplatform and the JetBrains KMP AndroidX artifacts) | KGP assertion updated to the new version; Kotlin's Gradle/AGP tested matrix ([compatibility](https://kotlinlang.org/docs/gradle-configure-project.html)); Metro's supported Kotlin range ([Metro compatibility](https://zacsweers.github.io/metro/latest/compatibility/), risk T22); Compose Multiplatform's compatibility with the Compose BOM line and the desktop artefacts' versions (D4, risk T21); both golden sets re-recorded (`FULL` tier); `desktop-matrix` dispatched on the branch; clean CI caches (KSP incremental quirks) |
| AGP and Lint | new Lint checks (baseline rule above); R8 behaviour on `release` (dispatch `nightly.yml` before merge so `release-build-smoke` and `api37-16k` run the release smoke); the baseline-profile plugin still wires `benchmarkRelease` and `nonMinifiedRelease`; the `debug` build type's defaults (debuggable, `.debug` suffix, no `testOnly` from the command line) and the split output names unchanged; `compileSdk` coupling with the Compose BOM |
| Screenshot stack | the PR includes the full re-record of both sets (`FULL` tier) |
| Media3 | release notes read for `@UnstableApi` changes; dispatch the nightly instrumented job on the branch (risk M3r) |
| Ktor | S12's matrix tests in `desktopTest` green; `NetErrorClassifier`'s mapping of Ktor exceptions unchanged; the server suite green (the server uses Ktor's CIO engine) |
| Room and SQLite | migration tests on both Android drivers (dispatch instrumented) and the bundled natives on all four desktop targets (dispatch `desktop-matrix`); 16 KB alignment output; a release that adds `windows_arm64` or `osx_x64` natives is noted for [PO-40](../PLAN.md#48-further-product-owner-decisions) (a native Windows-on-Arm build, M17); `sqlite-jdbc` bumps run the server suite and S16's footprint check |
| Chaquopy (`com.chaquo.python`, dashboard approval) | [01 S7](01-foundation.md#s7-chaquopy-under-agp-941) steps 3 and 5 repeated on the branch: the release build, `selftest` in `:ytx` on the API 26 GMD and the API 37 16 KB image (dispatch `nightly.yml`), release APK sizes against PB12/PB13, 16 KB check of the asset `.so` files; `python-components.lock` updated (`chaquopy`, `python` and the runtime's bundled component versions — `checkPythonLicences` fails otherwise) and the Licences entries with it; a new CPython minor version also needs the `actions/setup-python` and release-container versions and makes `EngineStore` re-extract engine versions on devices (04) |
| Bundled yt-dlp (not Renovate-managed) | only a version the engine canary approved (the served manifest's `ytdlp.version`); `scripts/engine/bump-ytdlp.sh <version>` vendors the upstream files and updates `bundled.json` and both locks; CI runs `verifyBundledYtDlp`, both `checkPythonLicences` and both shim suites; the Licences screens show the new version (04) |
| Temurin (`runtime.lock`, label `bundled-component`) | one vendor and version per release ([D89](../PLAN.md#3-key-decisions)); the per-target archive checksums and the source tarball's name and SHA-256 updated together; `check-runtime-sources.sh` green on the four targets (dispatch `scope: desktop`); jlink still finds every module (`suggestModules`, risk T25); the AOT cache retrains on each runner; the server image's base digest moves in the same release when it carries the same Temurin version, else the release attaches both tarballs ([10 Image sources and the runtime exception](10-sync.md#image-sources-and-the-runtime-exception)) |
| python-build-standalone (desktop Python lock) | the component list read from `PYTHON.json` diffed against D3's allow-list; `trim-python.sh`'s output and `check-desktop-image.sh` green on the four targets; `shimTestStdio` and `YtxProcessTest`; the desktop Licences entries updated; the same CPython minor as Chaquopy where possible (both shim suites run on one host interpreter) |
| FFmpeg (`native-components.lock`) | the same major within a release line (risk T20); the configure lines unchanged and `avcodec_license()` reporting "LGPL version 2.1 or later" ([11 FFmpeg build](11-desktop.md#ffmpeg-build)); the corpus suite green on the four targets; `assembleFfmpegSource` builds the source bundle; a new major also updates `ffmpeg-layout.json` and gets a binding review (11) |
| miniaudio, C++/WinRT headers | corpus and clock tests with the null back-end on the four targets; the real-hardware rows of 11's OS-integration checklist at the next minor release |
| WiX (Windows build tool, MS-RL, never shipped except jpackage's own `wixhelper.dll`) | the MSI builds, installs per user and upgrades over the previous release on the Windows runner with the frozen `upgradeUuid` ([11 Windows MSI and ZIP](11-desktop.md#windows-msi-and-zip)) |
| Base images (`gcr.io/distroless/java25-debian13` digest; the release container `python:3.14-slim-trixie` digest) | the server image: `server-image-smoke` and `check-server-image.sh` green and the image-source bundle regenerates; the release container: the nightly `repro` and an `assembleRelease` dry run green |
| GitHub Actions | release notes for breaking input changes; SHA pins updated |

**Gradle wrapper:** Renovate's `gradle-wrapper` manager updates `gradle-wrapper.properties`; Unverified whether the hosted app also regenerates `gradle-wrapper.jar` and `distributionSha256Sum` (self-hosted needs `allowedUnsafeExecutions`). If it does not, the maintainer runs `./gradlew wrapper --gradle-version X --gradle-distribution-sha256-sum <sum>` on the PR branch. Renovate ≥ 44.14.7 fixed a command injection through the wrapper (CVE-2026-88886); the hosted app is current.

---

## Versioning and signing

Serves N11, N12; mitigates risks P9, P10. Delivered in M0a (scheme, `release.sh`, the committed keystore, the first release `v0.1.0`), M0b (desktop package versions, the macOS `0.x` ZIP), MS1 (server JAR and image versions). Honours [D2](../PLAN.md#3-key-decisions), [D61](../PLAN.md#3-key-decisions), [D63](../PLAN.md#3-key-decisions), [D96](../PLAN.md#3-key-decisions), [PO-8](../PLAN.md#48-further-product-owner-decisions) (resolved: `ch.lkmc.neutrodyne`), [PO-33](../PLAN.md#48-further-product-owner-decisions) (resolved 2026-10-05: no beta channel), [PO-35](../PLAN.md#48-further-product-owner-decisions) (re-resolved 2026-10-05: release builds signed with the committed keystore), [PO-39](../PLAN.md#48-further-product-owner-decisions) (macOS tester builds as a ZIP).

### Version scheme

Single source: `gradle.properties` keys `neutrodyne.versionName` and `neutrodyne.versionCode` ([01](01-foundation.md#settingsgradlekts-gradleproperties-root-build)); Gradle never reads git or the clock. One version line serves every product: each tag `vX.Y.Z` publishes the Android, desktop and server assets of that version together ([D63](../PLAN.md#3-key-decisions)). M0a commits `neutrodyne.versionName=0.1.0` and `neutrodyne.versionCode=10095`.

`versionCode = MAJOR·1 000 000 + MINOR·10 000 + PATCH·100 + S`, where S is:

| `versionName` suffix | S | Example |
|---|---|---|
| none — every published tag since [PO-33](../PLAN.md#48-further-product-owner-decisions) | 95 | `0.1.0` → 10095, `1.0.0` → 1000095, `1.2.3` → 1020395 |
| `-beta.N` (N = 1…79) | N | reserved, unused since PO-33 (a later beta channel would need no renumbering) |
| `-rc.N` (N = 1…15) | 79 + N | reserved, unused since PO-33 |
| 96–99 | reserved (never used) | |

Rules: MINOR and PATCH ≤ 99. A version code is never reused, even for a failed release: a published immutable release locks its tag for good ([immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases)), and the update check, Obtainium and Android's downgrade rule all compare codes. All three ABI APKs of a release share its `versionCode` ([D63](../PLAN.md#3-key-decisions)). A hotfix after `1.2.3` is `1.2.4`, never a rebuild. Pre-1.0 tester builds of milestone Mn are `0.{n+1}.P` — P = 0 for its first build, then the next PATCH for later increments and fixes: M0a `0.1.0` (10095), M0b `0.1.1`, M1a `0.2.0`, M1b for example `0.2.1`, …, M10 `0.11.0`. An increment that lands out of order (M11a before M3, M8, M9 or M10) ships as the next PATCH of the current line (PLAN 7.1's example `0.3.1`), so version codes follow release order and stay monotonic; the M11 line `0.12.P` carries M11b's release candidates (and M11a when it lands after M10), then `1.0.0`. Every tag is a normal GitHub release ([Tester builds](#tester-builds)). After 1.0: MINOR for feature releases, PATCH for fixes and YouTube hotfixes; MAJOR only by PO decision.

**Desktop and server versions** derive from the same `versionName` ([D63](../PLAN.md#3-key-decisions)): `nativeDistributions.packageVersion = X.Y.Z` (the MSI limits MAJOR ≤ 255, MINOR ≤ 255, BUILD ≤ 65535 hold because MINOR and PATCH ≤ 99; RPM versions contain no `-`, and the scheme has none); jpackage refuses a macOS app version whose first number is 0, so every tester release before `1.0.0` ships macOS as `neutrodyne-{v}-macos-arm64.zip` with the real `0.Y.Z` written into `Info.plist` after packaging and the DMG appears with `1.0.0` ([PO-39](../PLAN.md#48-further-product-owner-decisions); procedure [11 macOS DMG, ad-hoc signing and the 0.x ZIP](11-desktop.md#macos-dmg-ad-hoc-signing-and-the-0x-zip)); the desktop app compares `versionCode` from its build-info resource exactly as Android does. The server JAR is `neutrodyne-server-{v}.jar` and the image tags are `{v}`, `{X.Y}` and `latest`, moved only after the release is published ([release.yml](#releaseyml) step 9); the sync protocol carries its own version, independent of the release ([D91](../PLAN.md#3-key-decisions)). MD and MS milestones and increments ship as the next PATCH of the current line, so every product's version follows release order; MD0 (a spike) publishes nothing.

```mermaid
stateDiagram-v2
  [*] --> Released: release.sh 0.1.0 tags the prepared version
  Released --> Patch: release.sh patch
  Released --> Minor: release.sh minor
  Released --> Major: release.sh major, PO decision only
  Released --> Hotfix: release.sh patch --hotfix on release/X.Y
  Patch --> Released
  Minor --> Released
  Major --> Released
  Hotfix --> Released
```

### `scripts/release.sh`

Usage: `scripts/release.sh <patch|minor|major|X.Y.Z> [--hotfix] [--dry-run]` (`--beta`, `--rc` and `finalise` were removed with PO-33). Algorithm:

1. Preconditions: on `main` (or, for `--hotfix`, on a `release/X.Y` branch, below), clean tree, `HEAD` equals its `origin` branch, `ci.yml` concluded `success` for `HEAD` — `gh run list --commit "$(git rev-parse HEAD)"`, never the literal ref name: `--commit` is forwarded to the API as `head_sha` and does not resolve references, so literal `HEAD` matched no run (fixed 2026-10-06) — no open issue labelled `release-blocker`, and either every job of the latest finished scheduled `nightly.yml` run except the report-only `repro` succeeded or was skipped (from M0b including `desktop-matrix`, from MS0 `sync-convergence`, from MS1 `server-image-smoke`; judged per job since 2026-10-07, so `repro` never gates a tag) or (with `--hotfix`, PATCH only) a `nightly.yml` `workflow_dispatch` run is green **for the exact `HEAD` sha** and the run's successful jobs include `instrumented-full` and `release-build-smoke` — the `scope: youtube-smoke` job set (tightened 2026-10-06: previously any successful dispatched run on the branch qualified, so a green run of another commit or a narrower scope could approve a hotfix that never ran its smoke tests). `nightly.yml` checks out `github.sha` in every job (also 2026-10-06), so a run's green jobs always tested exactly its `head_sha` — before, the dispatch `ref` input defaulted the checkout to `main`, letting green jobs of another commit pass this gate.
2. Compute the next `versionName` from the current one and the argument (`X.Y.Z` names it explicitly; any suffix is refused). Compute `versionCode` per the table (S = 95); refuse if it is lower than the current code, if it equals the current code while tag `v<current versionName>` exists, or if any existing tag has the new name. Equal to the current code without a tag is the "tag the prepared version" case: the very first release (`scripts/release.sh 0.1.0`, the value 01 commits in M0a) tags `HEAD` without a release commit.
3. Require `changelogs/<versionCode>.txt` **in `HEAD`** (it lands through a normal PR beforehand; `--dry-run` prints the code to name it): non-empty, ≤ 500 characters.
4. Rewrite the two `gradle.properties` lines and nothing else; commit `Release vX.Y.Z`; create an annotated (and, when the maintainer has git signing configured, signed) tag `vX.Y.Z`. The single-file, two-line diff is what `verify-tag.sh` accepts without waiting for the release commit's own CI run.
5. `git push --atomic origin <branch> vX.Y.Z` (ruleset bypass for maintainers). `release.yml` takes over.

**Hotfix while `main` already carries work for the next MINOR** (for example latest release `1.0.3`, while `main` holds unfinished `1.1` work): a patch cut from `main` would ship that work. Instead, create `release/1.0` from tag `v1.0.3` on first need (same ruleset as `main`; `ci.yml` also runs on `push` to `release/*`), cherry-pick the fix, dispatch `nightly.yml` `scope: youtube-smoke` on that branch and run `release.sh patch --hotfix` there → `v1.0.4` (1000495). The fix also lands on `main` and ships with `1.1.0` (1010095). Version codes stay monotonic: 1000395 → 1000495 → 1010095. `verify-tag.sh` accepts a tagged commit on `main` or on a `release/*` branch, and its `make_latest` rule keeps a late patch of an older line from ever becoming "latest" ([release.yml](#releaseyml) step 1).

### Changelogs and release notes

- `changelogs/<versionCode>.txt`: user-facing plain text, English, ≤ 500 characters; one file feeds the GitHub release body ([Release body](#release-body)) and the `notes` field of `neutrodyne-update.json`, and with it the update card and the update notification of both apps ([08 Updates settings](08-ui-ux.md#updates-settings)) and the server's admin notice (10). It covers every product of the release; a line that concerns one platform says so ("Desktop: …", "Server: …"). Not translated (excluded from Weblate).
- Release notes describe YouTube as "subscribe to YouTube channels and listen to them as audio"; nothing advertises downloading YouTube videos ([04 Posture and emergency build](04-youtube.md#posture-and-emergency-build)).
- Tester-build notes start with "Tester build for milestone Mn." and link the milestone's acceptance checklist issue. They are normal releases, so every installed copy is told about them ([Tester builds](#tester-builds)).

### Committed keystore

Every Android build is signed with one keystore committed to the repository ([D61](../PLAN.md#3-key-decisions), [D96](../PLAN.md#3-key-decisions), [01 Signing config](01-foundation.md#signing-config)): `signing/neutrodyne-public.keystore`, PKCS12, alias `neutrodyne`, store and key password `neutrodyne`. It is **public on purpose**. The owner wants no key management ([PO-35](../PLAN.md#48-further-product-owner-decisions), re-resolved 2026-10-05), and a committed key cannot be lost, needs no custody and gives CI and every developer machine the same signer, so every release build installs over every other (PLAN M0 AC8). The published APKs are optimised, non-debuggable `release` builds ([D96](../PLAN.md#3-key-decisions)); the same key also signs the local `debug` build (`ch.lkmc.neutrodyne.debug`, a different app) and the never-published `benchmarkRelease` and `nonMinifiedRelease`. There are no key holders, backups, yearly checks or signing secrets; the costs are listed in [Public key trade-offs](#public-key-trade-offs).

1. **Created once in M0a**, before the first release `v0.1.0` (installs signed with any other key could never update to later releases without uninstalling), with JDK 21 `keytool` on any machine:
   `keytool -genkeypair -v -storetype PKCS12 -keystore signing/neutrodyne-public.keystore -storepass neutrodyne -keypass neutrodyne -alias neutrodyne -keyalg RSA -keysize 4096 -validity 12000 -dname "CN=Neutrodyne (public key), O=Neutrodyne"` (12,000 days ≈ 33 years; Android Studio's generated debug certificate expires after 30 years, [app signing](https://developer.android.com/studio/publish/app-signing)). It is committed with `.gitattributes` `signing/*.keystore binary`. Unverified: whether GitHub push protection flags a committed PKCS12 file; if it does, the push is completed with the documented bypass reason, because the key is meant to be public.
2. **`signing/README.md`** (public text, M0a; content owned here): this keystore signs every Neutrodyne Android build — the published release builds and local debug builds — and is public on purpose, so a matching signature proves nothing about who built an APK (risk [P10](../PLAN.md#8-risks-and-mitigations)); download Neutrodyne only from `{repoUrl}/releases` and check the file ([Release body](#release-body)); anyone who distributes a fork or rebuild must change the application ID or the key, or it installs over Neutrodyne and inherits its data; never use this key for anything else. The desktop builds and the server carry no publisher key at all.
3. **Expected certificate:** `scripts/ci/public-cert-sha256.sh` computes the certificate SHA-256 from the committed file (`keytool -list -v -keystore signing/neutrodyne-public.keystore -storepass neutrodyne -alias neutrodyne`), so no repository variable can drift from the file; `release.yml`'s `android` job and `check-apk.sh --published` compare every APK's signer with it. The value is deliberately **not published as a trust anchor**: the README, release bodies and the help page list no fingerprint, because with a public key it proves nothing.

APK signature schemes: v1 off (minSdk 26 ≥ 24), v2 and v3 on, checked on every published APK by [release.yml](#releaseyml) step 2. Whether v3 key rotation away from this public key could ever spare users a reinstall is Unverified ([Public key trade-offs](#public-key-trade-offs)).

**Desktop and server:** nothing is signed with a publisher key ([D80](../PLAN.md#3-key-decisions)): jpackage signs the macOS app ad hoc (no identity, no keychain, no secret; [11 macOS DMG, ad-hoc signing and the 0.x ZIP](11-desktop.md#macos-dmg-ad-hoc-signing-and-the-0x-zip)), the Windows and Linux packages and the server JAR are unsigned, and the server image is attested by digest, not signed. Integrity rests on the release page, `SHA256SUMS` and the provenance attestations ([Release assets](#release-assets)).

The Ed25519 engine-manifest key is unrelated: it is a secret of the `engine-approval` environment, not an app key, and the project's only private signing key ([engine-canary.yml](#engine-canaryyml)).

Replaced 2026-10-05 (scope revision): `signing/neutrodyne-debug.keystore` (alias `androiddebugkey`, password `android`), the `neutrodyneDebug` signing config and `scripts/ci/debug-cert-sha256.sh`. No release had been published with the old certificate, so nothing installed carries it.

### Gradle signing configuration

Lives in `:app`, applied by `neutrodyne.android.application`; 01's [Build variants and ABIs](01-foundation.md#build-variants-and-abis) sketch shows the same lines in context (09 owns the values):

```kotlin
android {
    signingConfigs {
        create("neutrodynePublic") {                    // committed and public (Committed keystore)
            storeFile = rootProject.file("signing/neutrodyne-public.keystore")
            storeType = "pkcs12"
            storePassword = "neutrodyne"; keyAlias = "neutrodyne"; keyPassword = "neutrodyne"
            enableV1Signing = false; enableV2Signing = true; enableV3Signing = true
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.getByName("neutrodynePublic") }   // the published build type
        getByName("debug") { signingConfig = signingConfigs.getByName("neutrodynePublic") }     // local: ch.lkmc.neutrodyne.debug
        // benchmarkRelease and nonMinifiedRelease (androidx.baselineprofile) get the same config from the convention plugin
    }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")   // release-type smoke runs: -PtestBuildType=release
}
```

- No environment variable, no secret and no unsigned build: PR builds, the nightly `repro` rebuilds, `debug`, `benchmarkRelease` and `nonMinifiedRelease` are signed exactly like releases. AGP's default debug signing, a per-machine `~/.android/debug.keystore` ([build variants](https://developer.android.com/build/build-variants)), is never used, because every machine would then be a different signer.
- The desktop and server builds have no signing configuration ([Committed keystore](#committed-keystore)).

### Public key trade-offs

The owner's decision ([PO-35](../PLAN.md#48-further-product-owner-decisions), [D61](../PLAN.md#3-key-decisions), [D96](../PLAN.md#3-key-decisions)) has these recorded consequences. The README's "About these builds" item ([README "Install and update"](#readme-install-and-update)), 08's BUILDS help card and every release body state them for users:

| Consequence | Why | What Neutrodyne does |
|---|---|---|
| Anyone can sign an APK that Android installs over Neutrodyne as an update — a malicious "update" offered elsewhere or a careless fork — and it inherits the library, settings, downloads, the Android Keystore key and therefore the stored private-feed passwords and the sync token (risk [P10](../PLAN.md#8-risks-and-mitigations)) | Android accepts an update signed by the installed app's certificate, and the private key is in the repository | download only from the GitHub release page (README, help page, `signing/README.md`, every release body); immutable releases, `SHA256SUMS` and provenance attestations make a genuine file checkable (N12); the update card opens only `{repoUrl}/releases/` links and shows the SHA-256 of the device's APK ([Update card and links](#update-card-and-links)); forks are told to change the application ID or the key; a stolen sync token is revocable per device from any linked device or the server's web page ([10 Authentication and device linking](10-sync.md#authentication-and-device-linking)) |
| The certificate fingerprint proves nothing | the key is public | no fingerprint is published as a trust anchor; the release page, checksums and attestations are the anchors ([Release assets](#release-assets)) |
| Auto Backup data reaches any APK signed with the same certificate, a spoofed one included | restore checks the signing certificate ([05 Platform constraints](05-groups-opml-backup.md#platform-constraints)) | the same download rule; covered by P10; the sync token never travels in a backup (05) |
| Anyone can register Neutrodyne's certificate with Google's developer verification, and Neutrodyne cannot register without a private key (risk [P9](../PLAN.md#8-risks-and-mitigations)) | registration needs proof of key possession, which the public key gives everyone | [Package name and key](#package-name-and-key) |
| Signature-level permissions protect nothing against other apps signed with the public key: such an app, installed beside Neutrodyne under another package name, is granted every `signature` permission Neutrodyne declares — including androidx.core's `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which guards `RECEIVER_NOT_EXPORTED` runtime receivers on API 26–32 — so it can send broadcasts to those receivers on Android 8–12L (added 2026-10-05) | Android grants a `signature` permission to any app signed with the declaring app's certificate ([protection levels](https://developer.android.com/guide/topics/manifest/permission-element#plevel)); `ContextCompat.registerReceiver` emulates `RECEIVER_NOT_EXPORTED` below API 33 with that permission ([ContextCompat](https://developer.android.com/reference/androidx/core/content/ContextCompat)) | design rule ([01 Manifest and permissions](01-foundation.md#manifest-and-permissions)): every receiver registered with `RECEIVER_NOT_EXPORTED` and every component protected only by a `signature` permission treats each intent as untrusted — it validates extras, only routes or refreshes state, and changes nothing without a user action; in-process signals use flows, not broadcasts |
| Unverified: malware signed with the public certificate could give that certificate a bad reputation with Play Protect or other scanners, so genuine installs might be flagged (added 2026-10-05) | scanners may score signing certificates; Neutrodyne's is shared with anyone | the same download rule; issue reports are watched; the owner's [private-key option](#public-key-trade-offs) below remains the remedy |
| The desktop builds carry no publisher signature, so Gatekeeper, SmartScreen and Smart App Control treat a genuine build and a tampered one alike (risks P12, P13) | no Apple Developer ID and no Windows code signing ([D80](../PLAN.md#3-key-decisions)) | the same download rule and checks; macOS and Windows install guidance in [11 Install and update](11-desktop.md#install-and-update) |

Retired 2026-10-05 (scope revision, PO-35 re-resolved, [D96](../PLAN.md#3-key-decisions)): the consequences of publishing debuggable builds — app data readable and changeable through `run-as`, a JDWP debugger and `adb backup` (risk P11) and a less smooth, larger app than a release build (risk T18). Published APKs are non-debuggable release builds with R8 and, from M11b, baseline profiles.

**A later switch to a private key** (owner decision; nothing is prepared beyond the release build type that already exists):

- Every user must uninstall and reinstall once: Android refuses an update with another signer. The library moves through a manual backup ZIP (R1.7) or a linked sync server (R7); Auto Backup restore does not cross the switch (05).
- APK Signature Scheme v3 key rotation (proof-of-rotation from API 28, v3.1 from API 33, [v3 scheme](https://source.android.com/docs/security/features/apksigning/v3)) could in principle let installed copies accept a new key without a reinstall. But API 26–27 cannot rotate, and whether rotating away from a key that is already public protects anything is Unverified: anyone holding the old key can also sign. The plan therefore assumes one reinstall.
- A developer registration ([D80](../PLAN.md#3-key-decisions)) becomes possible only with the new key.
- Then needed: key custody, a signing secret for `release.yml`'s `android` job (which then joins the `release` environment), and updated README, help page and release bodies. Not designed further until the owner decides.

Removed 2026-10-05 (PO-35): the RSA-4096 release key, its offline ceremony, encrypted backups, second holder and yearly checks, the repository variables `NEUTRODYNE_CERT_SHA256`/`NEUTRODYNE_PREVIOUS_CERT_SHA256`, and the v3.1 rotation runbook with its M11b rehearsal (PLAN M11 AC12 removed).

---

## Distribution channels

Serves R6.1, R6.5, R6.7, N8, N12; mitigates risks P3, P7, P10, P12, P13, P15, L5, L6. Delivered in M0a (immutable, normal GitHub releases with the APKs from `v0.1.0`), M0b (desktop installers and the runtime sources), MD1b (the FFmpeg source bundle), MS1 (server JAR, GHCR image and image sources, README "Run the server"), MD5 and M11b (release hardening). Honours [D2](../PLAN.md#3-key-decisions), [D3](../PLAN.md#3-key-decisions), [D61](../PLAN.md#3-key-decisions), [D63](../PLAN.md#3-key-decisions), [D77](../PLAN.md#3-key-decisions), [D79](../PLAN.md#3-key-decisions), [D89](../PLAN.md#3-key-decisions), [D95](../PLAN.md#3-key-decisions), [D96](../PLAN.md#3-key-decisions), [PO-2](../PLAN.md#po-2-distribution-channels) (re-resolved 2026-10-05: GitHub Releases, and GitHub Container Registry for the server image), [PO-33](../PLAN.md#48-further-product-owner-decisions) and [PO-34](../PLAN.md#48-further-product-owner-decisions) (no beta channel, no mirror), [PO-39](../PLAN.md#48-further-product-owner-decisions), [PO-40](../PLAN.md#48-further-product-owner-decisions).

**GitHub is the only channel.** The apps, the server JAR and the source bundles are assets of the repository's GitHub releases; the server image lives in GitHub Container Registry, which counts as GitHub (owner decision S10). There is no Google Play, F-Droid, IzzyOnDroid, Mac App Store, Microsoft Store, winget, Homebrew (cask or own tap), Scoop, Flathub, Snap, AUR, apt/dnf repository or Docker Hub listing ([PLAN 1.2](../PLAN.md#12-non-goals-for-v10)); any other copy is not Neutrodyne's. Users install an APK, a desktop package or the server from the repository's releases page ([Developer verification](#developer-verification) describes what Android asks for, [11 Install and update](11-desktop.md#install-and-update) what Windows and macOS ask for). Updates are announced by the apps' [update check](#update-check), which links to the release so that the user installs it with the OS installer, by the server's admin page ([10 Update notice](10-sync.md#update-notice)), or arrive through Obtainium on Android. Engine updates of the YouTube engine are not app releases: they come from yt-dlp's own GitHub releases, approved through GitHub Pages ([engine-canary.yml](#engine-canaryyml)), for both apps.

| Channel | Artefact | Signing | Latency | From |
|---|---|---|---|---|
| GitHub Releases | the [release assets](#release-assets) of each tag, every tag a normal release | APKs: the committed public key (v2 + v3, [Committed keystore](#committed-keystore)); desktop packages: no publisher signature (macOS ad hoc); server JAR: none; every asset attested | minutes after the tag | M0a (APKs), M0b (desktop), MS1 (server) |
| GitHub Container Registry | `ghcr.io/{owner}/neutrodyne-server:{v}` (+ `{X.Y}`, `latest`) for `linux/amd64` and `linux/arm64` | attested by digest (`actions/attest` with `push-to-registry`) | tags move only after the release is published | MS1 |
| In-app update check (both apps) | a notification, a badge and the update card with "Open release on GitHub" and "Download APK for this device" or "Download for this computer"; the user downloads in the browser and installs with the OS installer | — (the apps install nothing) | ≤ 24 h (daily check) or on "Check now"; switch `updates.check_enabled` | M11a |
| Server admin page | "Neutrodyne Sync X.Y.Z is available" with the release link | — (the server installs nothing) | daily; `NEUTRODYNE_SERVER_UPDATE_CHECK=false` turns it off | MS1 |
| Obtainium (third-party, user-installed; Android only) | the same APK, chosen by its APK filter | the committed key | per Obtainium's schedule | M0a (documented) |

### Release assets

Per tag `vX.Y.Z` ([D79](../PLAN.md#3-key-decisions)); always a normal release, never a pre-release; "latest" when its `versionCode` is higher than the current latest release's ([release.yml](#releaseyml) step 1). Each milestone's release carries every product built so far (PLAN 7.2):

| Asset | Content | From |
|---|---|---|
| `neutrodyne-{v}-arm64-v8a.apk` | 64-bit ARM phones and tablets; with the YouTube engine | M0a |
| `neutrodyne-{v}-x86_64.apk` | x86_64 devices and emulators (for example Intel or AMD Chromebooks; Unverified which of them run Android apps as x86_64); with the engine | M0a |
| `neutrodyne-{v}-armeabi-v7a.apk` | 32-bit ARM devices; no engine, YouTube in external mode ([D77](../PLAN.md#3-key-decisions)) | M0a |
| `neutrodyne-{v}-r8-mapping.zip` | R8's `mapping.txt` (with `seeds.txt` and `usage.txt`) of the release build; obfuscation is off, so it is needed only to map inlined and outlined frames of crash reports with `retrace` | M0a |
| `neutrodyne-{v}-windows-x64.msi`, `neutrodyne-{v}-windows-x64.zip` | per-user MSI and portable ZIP for Windows 10 22H2 and 11 on x64, also used on Windows 11 on Arm (emulated, [PO-40](../PLAN.md#48-further-product-owner-decisions)) | M0b |
| `neutrodyne-{v}-macos-arm64.zip` (before `1.0.0`) or `neutrodyne-{v}-macos-arm64.dmg` (from `1.0.0`) | macOS 13+ on Apple silicon, ad-hoc signed ([PO-39](../PLAN.md#48-further-product-owner-decisions)) | M0b (ZIP), `1.0.0` (DMG) |
| `neutrodyne-{v}-linux-x64.deb`, `.rpm`, `.tar.gz`; `neutrodyne-{v}-linux-arm64.deb`, `.rpm`, `.tar.gz` | Linux x64 and arm64 with glibc ≥ 2.31 | M0b |
| `openjdk-{jdk}-temurin-sources.tar.gz`, `RUNTIME-SOURCES.md` | the exact source of the OpenJDK runtime bundled in the desktop packages (and the server image), and what it covers — the runtime exception's source duty ([D3](../PLAN.md#3-key-decisions), [11 Runtime exception obligations and checks](11-desktop.md#runtime-exception-obligations-and-checks)) | M0b |
| `ffmpeg-{ffmpeg}-neutrodyne-src.tar.xz` | pristine FFmpeg source with its signature, an empty `changes.diff`, the configure lines and build scripts — the LGPL source duty ([11 FFmpeg LGPL obligations](11-desktop.md#ffmpeg-lgpl-obligations)) | MD1b |
| `wix-{wix}-src.tar.gz` | the source of the pinned WiX release whose unmodified MS-RL components every MSI embeds (`RemoveFolderEx`'s custom action, WixUI) — the MS-RL source duty (2026-10-05, [01 Licence structure](01-foundation.md#licence-structure)) | M0b |
| `python-build-standalone-{pbsTag}-src.tar.gz` | the python-build-standalone repository archive at the pinned tag, whose MPL-2.0 build patches are compiled into the desktop engine's CPython — the MPL-2.0 source duty (2026-10-05, [04 Licence boundary](04-youtube.md#licence-boundary)) | MD3 |
| `neutrodyne-server-{v}.jar` | the sync server as a fat JAR for any Java 21+ runtime ([10 Deployment](10-sync.md#deployment)) | MS1 |
| `neutrodyne-server-image-sources-{v}.tar.xz` | the source packages of the server image's GPL and LGPL base components ([10 Image sources and the runtime exception](10-sync.md#image-sources-and-the-runtime-exception)) | MS1 |
| `neutrodyne-update.json` | the [update manifest](#update-manifest) the apps' update checks and the server's notice read | M0a |
| `SHA256SUMS` | `sha256sum` format over every other asset | M0a |

Image: `ghcr.io/{owner}/neutrodyne-server:{v}` (multi-arch index; `{X.Y}` and `latest` after publishing), its digest named in the release body and in the manifest's `server.image` (MS1).

**Android:** all three APKs share one `versionCode`, are optimised, non-debuggable release builds ([D96](../PLAN.md#3-key-decisions)) signed with v2 and v3 (no v1), and pass [Build-output checks](#build-output-checks). There is **no universal APK**: it would carry two engines ([D77](../PLAN.md#3-key-decisions)). **Desktop:** one package per target and format, no universal build ([D77](../PLAN.md#3-key-decisions), [D88](../PLAN.md#3-key-decisions)); every package bundles the unmodified, jlink-trimmed Temurin runtime and, from MD1b, the LGPL FFmpeg libraries, whose sources are attached to the same release. **Server:** the JAR and the image carry the same code; the image's runtime and base layers fall under the same runtime exception with their sources attached ([D95](../PLAN.md#3-key-decisions)). The emergency build without the engine ([01](01-foundation.md#emergency-build-without-the-engine)) publishes the same asset set with no engine in any APK or desktop package. A release carries about nine desktop packages of ≈ 100 MB each (Unverified until S13), the runtime source (≈ 121 MB), the FFmpeg source bundle and three APKs; GitHub allows files under 2 GiB each and sets no limit on a release's total size or bandwidth ([about releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)).

**Integrity** (N12): every release is published as an **immutable release**: created as a draft, every asset uploaded, then published. Afterwards assets cannot be changed or deleted, the tag cannot move, and a deleted release's tag name can never be reused ([immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases), GA since 2025-10-28, [changelog](https://github.blog/changelog/2025-10-28-immutable-releases-are-now-generally-available/)). Publishing creates GitHub's release attestation (verified with `gh release verify` and `gh release verify-asset`); `actions/attest` adds SLSA build-provenance attestations for every asset and for the server image by digest (Build Level 2 on GitHub-hosted runners; GitHub calls attestations "not a security guarantee" on their own, [artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)). The APK signing key is public and the desktop builds carry no publisher signature ([Public key trade-offs](#public-key-trade-offs)), so a signature says nothing about origin: **the release page, immutable releases, `SHA256SUMS` and the attestations are the trust anchors**, and the attestations show which workflow run built a file. GHCR tags are mutable, which is why the release body and the manifest name the image by digest and the tags move only after publishing.

### Release body

Generated by `release.yml` from `changelogs/<versionCode>.txt` and this template (`{…}` filled in; a block whose assets the release does not carry is left out):

```text
{changelog text}

Android — pick the APK for your device:
  neutrodyne-{v}-arm64-v8a.apk    most phones and tablets
  neutrodyne-{v}-x86_64.apk       x86_64 devices and emulators
  neutrodyne-{v}-armeabi-v7a.apk  older 32-bit phones (YouTube opens in the YouTube app)
These APKs are release builds signed with a key that is public in this repository
(signing/neutrodyne-public.keystore), so the signature does not show who built a file.

Windows, macOS, Linux — pick the file for your computer:
  Windows 10/11 x64, also Windows 11 on Arm:  neutrodyne-{v}-windows-x64.msi (or the portable .zip)
  Mac with Apple silicon, macOS 13 or later:  neutrodyne-{v}-macos-arm64.{dmg|zip}
  Linux x64 or arm64:                         neutrodyne-{v}-linux-{x64|arm64}.{deb|rpm|tar.gz}
The desktop builds are not signed by a registered developer. macOS: after installing and after
every update, open System Settings › Privacy & Security and click "Open Anyway". Windows: choose
"More info", then "Run anyway"; Smart App Control must be off. They bundle an unmodified OpenJDK
runtime (GPL-2.0 with the Classpath Exception; source: openjdk-{jdk}-temurin-sources.tar.gz,
RUNTIME-SOURCES.md). This software uses code of FFmpeg licensed under the LGPLv2.1 and its source
can be downloaded here: {repoUrl}/releases/download/{tag}/ffmpeg-{ffmpeg}-neutrodyne-src.tar.xz
The MSI embeds unmodified WiX components (MS-RL; source: wix-{wix}-src.tar.gz); the bundled
CPython is python-build-standalone (MPL-2.0 build patches; source:
python-build-standalone-{pbsTag}-src.tar.gz).

Sync server (optional, self-hosted): neutrodyne-server-{v}.jar for Java 21 or later, or the image
ghcr.io/{owner}/neutrodyne-server:{v} (digest {digest}). The image bundles an unmodified OpenJDK
runtime and Debian base packages; sources: neutrodyne-server-image-sources-{v}.tar.xz.

Download only from this page and check the file:
  sha256sum --check --ignore-missing SHA256SUMS        (macOS: shasum -a 256 <file>; Windows: Get-FileHash -Algorithm SHA256 <file>)
  gh release verify-asset {tag} <file> -R {owner}/Neutrodyne
  gh attestation verify <file> -R {owner}/Neutrodyne
  gh attestation verify oci://ghcr.io/{owner}/neutrodyne-server@{digest} -R {owner}/Neutrodyne

Guides: {repoUrl}#install-and-update · {repoUrl}#install-on-windows-macos-or-linux · {repoUrl}#run-the-server
```

The guide links assume the README headings "Install and update" (content: [README "Install and update"](#readme-install-and-update)), "Install on Windows, macOS or Linux" (11's text, [11 README source text](11-desktop.md#readme-source-text)) and "Run the server" ([README "Run the server"](#readme-run-the-server)). `gh attestation verify oci://…` verifies the image's attestation in the registry ([gh attestation verify](https://cli.github.com/manual/gh_attestation_verify)). Tester-build bodies start with "Tester build for milestone Mn." ([Changelogs and release notes](#changelogs-and-release-notes)). Removed 2026-10-05 (scope revision): the paragraph about debug builds (data readable over USB debugging, less smooth than a release build); the APKs are release builds. The AppVerifier certificate block stays removed: with a public key it would suggest a check that proves nothing.

### GitHub Releases and Obtainium

- README badge: `https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/<owner>/Neutrodyne` ([Obtainium deep links](https://wiki.obtainium.imranr.dev/deep_links/)); 08's help page offers the `obtainium://add/{repoUrl}` link directly ([08 Install and updates help](08-ui-ux.md#install-and-updates-help)).
- APK choice: each release has three APKs, so Obtainium needs an APK filter regex per device, for example `neutrodyne-.*-arm64-v8a\.apk$`, or its architecture filter (`autoApkFilterByArch` in its source, [Obtainium](https://github.com/ImranR98/Obtainium)). Obtainium considers only APK assets, so the desktop packages, the JAR, the source bundles, `SHA256SUMS` and the manifest do not confuse it (read from its source on 2026-10-05; the many non-APK assets of a release are Unverified against its asset-count handling). The tag is always `v` + `versionName`, which Obtainium's version detection expects. Every tag is a normal release, so no "Include prereleases" option is needed.
- Obtainium's GitHub source queries the REST API (`api.github.com/…/releases`), so GitHub's limit of 60 unauthenticated requests per hour per IP applies to it ([REST limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)); users behind a shared IP can add a GitHub token in Obtainium. Neutrodyne's own update checks never use the REST API ([Checking](#checking)).
- Obtainium users can turn off Neutrodyne's own check (Settings › Updates › "Check for updates", R6.3); the app does not try to detect Obtainium. Developer verification applies to Obtainium's installs like to any other installer. Obtainium has no desktop counterpart in v1.0; desktop users rely on the in-app check.

### Tester builds

Since [PO-33](../PLAN.md#48-further-product-owner-decisions) there is no beta channel and no `releases.atom` reader. Every milestone's tester build (PLAN DoD) is a normal, immutable GitHub release `0.{n+1}.P` or the next PATCH ([Version scheme](#version-scheme)) with every product built so far: the APKs from M0a, the desktop packages and runtime sources from M0b, the FFmpeg sources from MD1b, the server JAR, image and image sources from MS1. `releases/latest/download/…` resolves to it, because the latest release is the most recent non-prerelease, non-draft one ([latest release](https://docs.github.com/en/rest/releases/releases#get-the-latest-release), [linking to releases](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases)). So the update checks of every installed app and server, and Obtainium, see it: before 1.0, testers are the users. macOS testers get the ZIP and repeat "Open Anyway" after each update (PO-39). Its notes start with "Tester build for milestone Mn." and link the release issue. A broken tester build is fixed forward with the next PATCH; nothing published is withdrawn. After 1.0 every tag reaches every user, so a MINOR is tested on CI artifacts (`assemble`'s `release-apks`, the nightly desktop packages and server image) and by the [release checklist](#release-checklist) before it is tagged.

### README "Run the server"

The README section's text is owned here; the facts are [10 Deployment](10-sync.md#deployment)'s, and the [release checklist](#minor-and-stable-release-additions) compares both. Draft (MS1, final in M11b; `{…}` filled in):

```markdown
### Run the server

Neutrodyne Sync keeps subscriptions, groups, played state, positions and Up next in step across your
phones and computers. It is optional and self-hosted: it stores only that state — never audio or feed
contents — and never fetches anything itself. It holds your listening history and private feed links
without end-to-end encryption, so run it on a machine you control and put it behind TLS.

With Docker (Linux x86-64 or arm64): copy `compose.yaml` and `Caddyfile` from `sync/server/deploy/` at the
release's tag, put your domain into both files and run `docker compose up -d`. The image is
`ghcr.io/{owner}/neutrodyne-server:{v}`; Caddy obtains the TLS certificate.
Without Docker: install Java 21 or later, download `neutrodyne-server-{v}.jar` from the release, check it
against `SHA256SUMS`, and install `neutrodyne-server.service` (systemd) from the same folder; put Caddy or
nginx in front for TLS (`nginx.conf.example` keeps live updates working). The server listens only on loopback
unless its public URL is `https://` (behind the proxy) or it is started with `--insecure-lan` for a trusted home network.

At its first start the server prints a one-time setup code; open `https://<your domain>/setup` and enter it,
or run `java -jar neutrodyne-server-{v}.jar admin create`. Create an account, then link your devices in
Settings › Sync of each app.

Upgrade by replacing the image tag or the JAR; the server backs up its database before it migrates and
refuses to start on a newer database. Copy `backups/` and `account-backups/` from its data directory off
the machine regularly. The admin page tells you when a newer version exists (one daily request to GitHub;
`NEUTRODYNE_SERVER_UPDATE_CHECK=false` turns it off). The image bundles an unmodified OpenJDK runtime and
Debian base packages; their sources are attached to every release and published next to the image as
`ghcr.io/{owner}/neutrodyne-server:{v}-sources`.
```

Wording rules: no claim that the server is "secure" or "encrypted"; the missing end-to-end encryption is stated in the first paragraph, as `PRIVACY.md` and the apps' sync setup state it ([N13](../PLAN.md#22-non-functional-requirements), [Privacy](#privacy)).

### GitHub takedown

GitHub is the single distribution and update channel. Risk [P7](../PLAN.md#8-risks-and-mitigations) is **accepted** ([PO-34](../PLAN.md#48-further-product-owner-decisions): no mirror, no mirrored release assets or manifests, no fallback URL in the apps or the server). A DMCA notice, an abuse report or an account suspension would remove the releases, the update manifest, the engine manifest (GitHub Pages) and the server image (GHCR) at once; youtube-dl's 2020 takedown was reversed, but only after weeks ([GitHub blog](https://github.blog/2020-11-16-standing-up-for-developers-youtube-dl-is-back/)). What keeps working:

- Installed apps keep running. The update checks fail quietly (`Failed(NETWORK)`, or `Failed(MANIFEST_INVALID)` on a 404) and a known `Available` stays visible, although its links would fail. Running servers keep serving; their update notice fails quietly, and already pulled images stay in the hosts' local image stores.
- The YouTube engine keeps its bundled and active versions in both apps; "Reset to bundled" always works. Engine-update checks fail, and the engine heartbeat goes stale and says so ([engine-canary.yml](#engine-canaryyml)).
- The source history and the build recipe survive in every clone. Because the keystore is committed, a rebuild from any clone installs over existing Android copies, so after a takedown users could not tell a genuine new home from a spoofed one by the signature (risk P10). A move would be announced through the project's other channels, if any; no recovery runbook is planned.

The monthly `keepalive.yml`, which counters GitHub's 60-day rule for scheduled workflows, is unrelated to takedowns and stays ([Helper workflows](#helper-workflows)).

---

## Update check

Serves R6.2–R6.4, R6.6, N3, N7, N12; mitigates risks P3, P7, P10, P12, P13, T16. Delivered in M0a (`release.yml` publishes `neutrodyne-update.json` from `v0.1.0`), M0b (its `desktop[]` entries), MS1 (its `server` entry), M11a (the check in both apps; needs only M2 and M0, PLAN 7.1). Honours [D13](../PLAN.md#3-key-decisions), [D78](../PLAN.md#3-key-decisions), [D80](../PLAN.md#3-key-decisions), [PO-31](../PLAN.md#48-further-product-owner-decisions) (resolved 2026-10-05: notify only), [PO-33](../PLAN.md#48-further-product-owner-decisions) (resolved: no beta channel), [PO-36](../PLAN.md#48-further-product-owner-decisions) (notice timing). Screens, notification texts and the help page: [08 Updates settings](08-ui-ux.md#updates-settings), [08 Install and updates help](08-ui-ux.md#install-and-updates-help). The desktop's asset-selection rules, install-kind hints and lane: [11 Desktop update check](11-desktop.md#desktop-update-check). The server's notice: [10 Update notice](10-sync.md#update-notice). What a manually installed update means for playback and downloads: [06 App updates and playback](06-playback.md#app-updates-and-playback), [07 App update](07-downloads.md#app-update). Permissions: none ([01 Manifest and permissions](01-foundation.md#manifest-and-permissions)).

GitHub is the only channel and most users never install Obtainium, so both apps tell users when a newer release exists and link to it. The user downloads the APK or desktop package in the browser and installs it with the OS installer. The owner's words: "Provide a link to github where the user can download and install the new build manually." The apps **never** download, verify or install an update. They hold no install permission (N7), never call `api.github.com`, start no process for it (`ProcessBuilder` exists only in `:youtube:ytdlp-desktop`, 01) and do not interact with playback or downloads. Debug builds — Android's `debug` build type and the desktop app run from Gradle (`InstallKind.DEV`), both `BuildInfo.debug` — have it off (`Disabled(DEV_BUILD)`).

### Modules and API

No modules of its own ([D13](../PLAN.md#3-key-decisions)): interfaces in `:core:domain` (package `ch.lkmc.neutrodyne.core.domain.update`), state types in `:core:model` (`ch.lkmc.neutrodyne.core.model.update`), the implementation in `:core:data` (`ch.lkmc.neutrodyne.core.data.update`): the logic in `commonMain`, the scheduling and notification in `androidMain` and `desktopMain`, bound through Metro contributions ([01 Dependency injection](01-foundation.md#dependency-injection)). `:feature:settings` (Settings › Updates, Install & updates help) and each shell's root (first-run card, on Android the verification notice, the gear badge) use only the interfaces. `:core:data` already hosts comparable work, and the check needs no install permission, no `PackageInstaller` and no playback or download state, which were the only reasons for separate `:update:*` modules.

```kotlin
// :core:domain — ch.lkmc.neutrodyne.core.domain.update
interface AppUpdateChecker {
    val state: StateFlow<UpdateCheckState>
    suspend fun checkNow(): UpdateCheckState   // user action; also with "Check for updates" off; refused (returns the current
                                               // state) only in Disabled(DEV_BUILD); at most one request per 60 s
    fun skip(versionCode: Long)                // from Available: hide this version until a higher versionCode appears
}
interface UpdateNotices {
    val pending: StateFlow<UpdateNotice?>
    fun dismiss(notice: UpdateNotice)
}

// :core:model — ch.lkmc.neutrodyne.core.model.update
sealed interface UpdateCheckState {
    data class Disabled(val reason: UpdateDisabledReason) : UpdateCheckState
    data class Idle(val lastCheckAtMs: Long?) : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Available(val info: UpdateInfo, val lastCheckAtMs: Long,
                         val lastError: UpdateCheckError? = null) : UpdateCheckState   // a failed re-check keeps Available
    data class Failed(val error: UpdateCheckError, val lastCheckAtMs: Long?) : UpdateCheckState
}
enum class UpdateDisabledReason { DEV_BUILD, CHECKS_OFF }
data class UpdateInfo(val versionName: String, val versionCode: Long, val minSdk: Int, val publishedAt: String,
                      val notes: String, val releaseUrl: String,
                      val apk: UpdateApk?,                       // Android: the entry for Build.SUPPORTED_ABIS[0], else null; desktop: null
                      val desktopAsset: UpdateDesktopAsset?,     // desktop: DesktopAssetSelector's pick (11), else null; Android: null
                      val server: UpdateServerInfo?)             // validated, unused by the apps in v1.0
data class UpdateApk(val abi: String, val fileName: String, val url: String, val sizeBytes: Long, val sha256: String)
data class UpdateDesktopAsset(val os: DesktopOs, val arch: DesktopArch, val kind: InstallKind, val fileName: String,
                              val url: String, val sizeBytes: Long, val sha256: String, val minOs: String?)
data class UpdateServerInfo(val jarFileName: String, val jarUrl: String, val jarSizeBytes: Long, val jarSha256: String,
                            val imageDigestRef: String, val minJava: Int)   // "ghcr.io/{owner}/neutrodyne-server@sha256:…"
enum class UpdateCheckError { NETWORK, RATE_LIMITED, MANIFEST_INVALID }
enum class UpdateNotice { FIRST_RUN_CHOICE, VERIFICATION_ENFORCEMENT }
```

`DesktopOs`, `DesktopArch` and `InstallKind` are `:core:model` enums ([01 Build variants and ABIs](01-foundation.md#build-variants-and-abis), `BuildInfo.desktop`); `InstallKind` has `MSI`, `ZIP`, `DMG`, `MAC_ZIP`, `DEB`, `RPM`, `TAR_GZ` and `DEV`, written in the manifest as `msi`, `zip`, `dmg`, `mac-zip`, `deb`, `rpm`, `tar.gz`.

| Class (`:core:data`) | Source set | Responsibility |
|---|---|---|
| `AppUpdateCheckerImpl` | common | the state rules below; reads `updates.check_enabled` (`SettingsRepository`), `BuildInfo` and `UpdateCheckStore`; runs checks through `UpdateCheckRunner` |
| `UpdateCheckRunner` (internal interface) with `WorkUpdateCheckRunner` and `LaneUpdateCheckRunner` | common; Android; desktop | Android: enqueues or cancels `app-update-check` when the switch changes and runs "Check now" as `app-update-check-now`; desktop: runs "Check now" in the application scope and lets `DesktopUpdateCheckLane` do the daily check |
| `UpdateNoticesImpl` | common | notice rules ([Notices](#notices)) |
| `GitHubUpdateSource` | common | the one GET ([Checking](#checking)) on 01's Ktor API client (`NeutrodyneHttpClients`) |
| `UpdateManifestParser`, `VersionScheme` | common | manifest parsing and validation, including the link rule and the `desktop[]` and `server` entries; [D63](../PLAN.md#3-key-decisions)'s `versionCode` from a version name (the same formula as `release.sh`) |
| `DesktopAssetSelector` | common | picks the desktop entry for `BuildInfo.desktop` by 11's rules (OS, architecture with Windows on Arm → x64, install kind with its fallback chain, `minOs`; `DEV` → none) ([11 Desktop update check](11-desktop.md#desktop-update-check)) |
| `UpdateCheckStore` | common (Okio) | `last-check.json` (the last parsed `UpdateInfo` and the check time), in Android's `noBackupFilesDir/updates/` or the desktop's `<data>/updates/`, so `Available` survives a restart without a request |
| `VerificationTimeline` | common | compiled-in notice and enforcement dates ([Notices](#notices)); used on Android only |
| `UpdateCheckWorker`, `UpdateNotifier` | Android | the works `app-update-check` and `app-update-check-now`; the `updates` channel's single notification `NOTIF_ID_UPDATE = 4200` (texts: 08) |
| `DesktopUpdateCheckLane`, `DesktopUpdateNotifier` | desktop | the `app-update-check` lane of `DesktopJobRunner` ([11 Background work](11-desktop.md#background-work)); one `APP_UPDATE` notification per version through 11's `DesktopNotifier` |

Metro contributions bind `AppUpdateCheckerImpl` and `UpdateNoticesImpl` in the app scope of both graphs; they replace the Hilt `UpdateModule` of the previous revision.

```mermaid
stateDiagram-v2
  [*] --> Idle
  [*] --> Disabled: debug build, or checks off
  Idle --> Checking: daily work or lane, or Check now
  Disabled --> Checking: Check now while checks are off
  Checking --> Idle: up to date or skipped
  Checking --> Available: newer release
  Checking --> Failed: network, rate limit, bad manifest
  Failed --> Checking: next daily check or Check now
  Available --> Checking: next daily check or Check now
  Available --> Idle: skipped, or that version now runs
```

State rules (both apps unless marked):

- **At process start** `state` is computed without a request: a debug build (`BuildInfo.debug`) → `Disabled(DEV_BUILD)` (always wins; a restored `updates.check_enabled = true` changes nothing). Otherwise, if `UpdateCheckStore` holds an `UpdateInfo` whose `versionCode` is higher than `BuildInfo.versionCode` and not `updates.skipped_version_code`, and the switch is on → `Available`. Otherwise, switch off → `Disabled(CHECKS_OFF)`, else `Idle(updates.last_check_at)`. A stored version at or below the running one means it was installed: the store entry is dropped and its notification cancelled.
- **Switch off** → `Disabled(CHECKS_OFF)` at once; Android cancels `app-update-check`, the desktop lane skips its runs; the badge goes away. "Check now" still runs (`Checking` → its result), and that result stays until the process ends or the switch changes. **Switch on** → Android re-enqueues `app-update-check`; both recompute as at start.
- **A failed check never hides a known `Available`:** it only sets `lastError` (08 shows "Couldn't check again"). Without a known update it ends in `Failed(error, lastCheckAtMs)`.
- `checkNow()`: a call within 60 s of the last request returns the current state without a request. **Offline** (`NetworkMonitor.status.value.isConnected == false`, [01 NetworkMonitor](01-foundation.md#networkmonitor); the desktop's `DesktopNetworkMonitor`) it sends nothing, schedules nothing and ends at once with `NETWORK` — `Failed(NETWORK, lastCheckAtMs)`, or a known `Available` with `lastError = NETWORK` — so "Check now" never waits for a connection. Otherwise Android enqueues `app-update-check-now` (no network constraint, so a connection lost meanwhile fails fast as `NETWORK`) and the desktop runs the GET in the application scope; either way `checkNow()` suspends until `state` leaves `Checking`, at most 30 s, after which it cancels the check and ends with `NETWORK` the same way. "Check now" never retries: every outcome ends the check (403/429 → `RATE_LIMITED`); only the periodic Android work uses backoff, and the desktop lane retries at its next due time.

### Update manifest

`neutrodyne-update.json`, an asset of every release (written by `make-update-json.sh`, validated by `check-update-json.sh` before the draft is published, [release.yml](#releaseyml) step 8). Schema 1 with this layout; the `desktop[]` and `server` fields of the scope revision are additive, so parsers that predate them ignore them ([D78](../PLAN.md#3-key-decisions)):

```json
{ "schema": 1, "versionName": "1.0.0", "versionCode": 1000095, "minSdk": 26, "published": "2027-…Z",
  "releaseUrl": "https://github.com/<owner>/Neutrodyne/releases/tag/v1.0.0", "notes": "…",
  "apks": [ { "abi": "arm64-v8a", "file": "neutrodyne-1.0.0-arm64-v8a.apk",
              "url": "https://github.com/<owner>/Neutrodyne/releases/download/v1.0.0/neutrodyne-1.0.0-arm64-v8a.apk",
              "size": 0, "sha256": "…" },
            { "abi": "x86_64", "file": "…", "url": "…", "size": 0, "sha256": "…" },
            { "abi": "armeabi-v7a", "file": "…", "url": "…", "size": 0, "sha256": "…" } ],
  "desktop": [ { "os": "windows", "arch": "x64", "kind": "msi", "file": "neutrodyne-1.0.0-windows-x64.msi",
                 "url": "https://github.com/<owner>/Neutrodyne/releases/download/v1.0.0/neutrodyne-1.0.0-windows-x64.msi",
                 "size": 0, "sha256": "…", "minOs": "10.0" },
               { "os": "windows", "arch": "x64", "kind": "zip", "file": "…", "url": "…", "size": 0, "sha256": "…", "minOs": "10.0" },
               { "os": "macos", "arch": "arm64", "kind": "dmg", "file": "…", "url": "…", "size": 0, "sha256": "…", "minOs": "13.0" },
               { "os": "linux", "arch": "x64", "kind": "deb", "file": "…", "url": "…", "size": 0, "sha256": "…" } ],
  "server": { "jar": { "file": "neutrodyne-server-1.0.0.jar",
                       "url": "https://github.com/<owner>/Neutrodyne/releases/download/v1.0.0/neutrodyne-server-1.0.0.jar",
                       "size": 0, "sha256": "…" },
              "image": "ghcr.io/<owner>/neutrodyne-server@sha256:…", "minJava": 21 } }
```

(The example shortens `desktop[]`; a release lists every desktop package: `windows/x64` `msi` and `zip`, `macos/arm64` `mac-zip` before `1.0.0` or `dmg` from it, `linux/x64` and `linux/arm64` `deb`, `rpm` and `tar.gz`.)

| Field | `check-update-json.sh` (CI) | `UpdateManifestParser` (apps) |
|---|---|---|
| `schema` | 1 | 1; anything else → `MANIFEST_INVALID` (08's `Failed` card offers "Open releases", `{repoUrl}/releases`, built from `BuildInfo.repoUrl`) |
| `versionName`, `versionCode` | equal the tag without `v` (no suffix), `gradle.properties` and D63's formula with S = 95 | `versionCode == VersionScheme.versionCode(versionName)`, else `MANIFEST_INVALID` |
| `minSdk` | 26 | parsed; when `minSdk > SDK_INT` the Android card offers only the release page and no notification is posted ([Update card and links](#update-card-and-links)); ignored on the desktop |
| `published`, `notes` | ISO-8601 UTC; `notes` equal to `changelogs/<versionCode>.txt` (≤ 500 characters) | shown as plain text, never as HTML or links |
| `releaseUrl` | `{repoUrl}/releases/tag/{tag}` | the link rule below with path segments exactly `[owner, "Neutrodyne", "releases", "tag", "v{versionName}"]`, else `MANIFEST_INVALID` |
| `apks[]` | exactly the three ABIs built, each `file` present in the release with matching `size` and `sha256`, `url` = `{repoUrl}/releases/download/{tag}/{file}` | every `url` passes the link rule with path segments exactly `[owner, "Neutrodyne", "releases", "download", "v{versionName}", file]` and `file` equal to that entry's `file`, else `MANIFEST_INVALID`; entries with an unknown `abi` are ignored; `sha256` 64 hexadecimal characters |
| `desktop[]` (from M0b; absent before) | exactly the desktop packages of the release, per target the kinds listed above (no `windows/arm64` entries: Windows on Arm uses x64, [PO-40](../PLAN.md#48-further-product-owner-decisions)), each `file` present with matching `size` and `sha256`, `url` as for `apks[]`; `minOs` `"10.0"` on Windows and `"13.0"` on macOS, absent on Linux | the same link and hash rules; entries with an unknown `os`, `arch` or `kind` are ignored; `DesktopAssetSelector` picks one per 11's rules; Android ignores the field |
| `server` (from MS1; absent before) | `jar` like an asset entry; `image` = `ghcr.io/{owner}/neutrodyne-server@sha256:<index digest>` equal to the digest `server-image-manifest` attested; `minJava` 21 | parsed into `UpdateServerInfo` with the link rule for the JAR and a reference check (this repository's owner, image name `neutrodyne-server`, a digest, no tag); unused by the apps in v1.0; the server's own notice reads the manifest with its own parser ([10 Update notice](10-sync.md#update-notice)) |

**Link rule.** A URL is accepted only when its scheme is `https`, its host and port are those of `BuildInfo.repoUrl` (`github.com`, 443), it has no user-info, query or fragment, and its raw path, split on `/` and each segment percent-decoded once, equals the expected segment list exactly; a segment that decodes to `.`, `..` or contains `/` or `\` rejects the manifest. Parsing uses Ktor's `Url` in common code (Unverified how it treats `%2e` forms, so the decoding and comparison are our own and tested, [Tests](#tests)). The app then never opens the manifest's string: it opens the URL rebuilt from `BuildInfo.repoUrl` and the validated components, so what it opens is always this repository's release page or release asset for the manifest's own version.

Unknown fields are ignored, so later schema-1 additions stay compatible; the whole manifest is ≤ 64 KB (larger → `MANIFEST_INVALID`; a full release lists ≈ 13 entries, a few KB). Removed with PO-31/PO-33: `prerelease`, `certSha256` and `previousCertSha256`.

The manifest is **not signed**, and need not be: the apps only show text and open links whose rebuilt form is this repository's release page or release asset for the manifest's own version, so a forged manifest could at worst point to another genuine release page or asset of this repository, or show wrong notes. It cannot make the apps install anything, because they install nothing. The SHA-256 on the card is informational: it comes from the same release as the file, so it reveals a damaged or swapped download, not a fake release. Integrity comes from GitHub — the release page, immutable releases, `SHA256SUMS` and the attestations ([Release assets](#release-assets)).

### Checking

One request per check, on 01's Ktor API client (standard User-Agent, no cookies, no token): `GET {repoUrl}/releases/latest/download/neutrodyne-update.json` (`BuildInfo.updateManifestUrl`). `github.com` answers 302 to `/releases/download/<tag>/…`, which redirects to a short-lived signed `release-assets.githubusercontent.com` URL (≈ 1 h validity, observed 2026-10-05); no REST API is involved ([linking to releases](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases)). Every tag is a normal release, so `latest` is the release `release.yml` marked latest ([Tester builds](#tester-builds)).

A check finds an update when the manifest's `versionCode` is higher than `BuildInfo.versionCode` and not equal to `updates.skipped_version_code`. On Android it then picks the `apks[]` entry whose `abi` equals `Build.SUPPORTED_ABIS[0]`, the device's preferred ABI ([D78](../PLAN.md#3-key-decisions)), so a 64-bit phone that runs the `armeabi-v7a` APK is linked to the `arm64-v8a` APK (08's "Get the 64-bit version", risk T16); no such entry → `UpdateInfo.apk = null`, and the card offers only the release page. On the desktop `DesktopAssetSelector` picks the entry for this OS, architecture and install kind ([11 Desktop update check](11-desktop.md#desktop-update-check)); none (a `DEV` run, no entry, or `minOs` above the running OS) → `desktopAsset = null` or the "needs a newer OS" card (08).

Failures: I/O and timeouts → `NETWORK`; HTTP 403 or 429 from GitHub → `RATE_LIMITED` (the periodic work retries with its backoff; "Check now" does not retry) (GitHub publishes no limit for unauthenticated `releases/download` requests; Unverified, so the check assumes there is one); 404, an unparsable or invalid manifest → `MANIFEST_INVALID`. `api.github.com` (60 unauthenticated requests per hour per IP, shared under CGNAT, [REST limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)) is never called; `GitHubUpdateSourceTest` asserts it.

| Work ([D78](../PLAN.md#3-key-decisions)) | Type and policy | Scheduled |
|---|---|---|
| `app-update-check` (Android) | periodic 24 h with a 6 h flex window, network `CONNECTED`, `ExistingPeriodicWorkPolicy.UPDATE`, exponential backoff from 1 h | by the order-200 initializer (01) only while `updates.check_enabled` is on and the build is not a debug build; the first enqueue has an initial delay of 24 h, so the [first-run card](#notices) is seen before the first scheduled check; cancelled when the switch goes off |
| `app-update-check-now` (Android) | one-time, `REPLACE`, **no** network constraint (a constrained work would wait offline and leave the state in `Checking`), never `Result.retry()` | `checkNow()` ("Check now") when `NetworkMonitor` reports a connection, also while the switch is off; never in debug builds |
| `app-update-check` lane (desktop) | `DesktopUpdateCheckLane` in `DesktopJobRunner`: due 24 h after `updates.last_check_at` with up to 1 h of random jitter, the first run 24 h after the first start (so the first-run card comes first); a failure makes it due again after 1 h, then 2, 4, … up to 24 h; overdue runs start within 2 min after a wake or a restart (R8.7) | only while the app runs, the switch is on and the install kind is not `DEV`; nothing runs while the app is quit (R6.6, [11 Background work](11-desktop.md#background-work)) |

`app-update-download` and `app-update-install` were removed with PO-31.

### Update card and links

What the card shows is 08's ([08 Updates settings](08-ui-ux.md#updates-settings)): the version, release date, the first lines of the notes, the size of the linked file, a copyable SHA-256 of that file, and the actions below. The behaviour behind it:

- **"Open release on GitHub"** → the browser (Android `ACTION_VIEW` + `CATEGORY_BROWSABLE`, desktop `ExternalUrlOpener`, which uses `Desktop.browse`) with the rebuilt `info.releaseUrl`, the tag's release page with the notes, every file and `SHA256SUMS`.
- **"Download APK for this device"** (Android) → `ACTION_VIEW` of `info.apk.url`: the browser downloads `neutrodyne-{v}-{abi}.apk`, and Android's installer installs it when the user opens the file. The browser or Files app needs Android's "install unknown apps" permission once, and developer verification applies there where it is enforced ([Developer verification](#developer-verification)). Absent when `apk == null` or `minSdk > SDK_INT`.
- **"Download for this computer"** (desktop) → `ExternalUrlOpener` with `info.desktopAsset.url`; the user runs the installer or unpacks the archive as 11's install-kind hint says, and macOS needs "Open Anyway" again after every update ([11 Install and update](11-desktop.md#install-and-update)). Absent when `desktopAsset == null`.
- All URLs come only from the validated manifest, rebuilt by the link rule; the UI never builds or rewrites a download URL itself. No browser (Android `ActivityNotFoundException`, desktop `Desktop` unsupported) → 08's "No app can open this link" with "Copy link".
- **"Skip this version"** → `skip(versionCode)`: writes `updates.skipped_version_code`, cancels that version's notification, `state` → `Idle`. A release with a higher `versionCode` supersedes the skip.
- **Badge:** while `state` is `Available`, 08's gear badge and the Settings home row show a dot in both apps; it goes away when the state leaves `Available` (skipped, the version now runs, checks turned off).
- **Notification:** posted when a **scheduled** check (Android work or desktop lane) yields `Available` for a `versionCode` other than `updates.notified_version_code` — on Android `NOTIF_ID_UPDATE` on channel `updates`, only with `POST_NOTIFICATIONS` granted (API 33+) and `minSdk ≤ SDK_INT`; on the desktop one `APP_UPDATE` notification through `DesktopNotifier` (11) — and then writes `updates.notified_version_code`, so each version notifies at most once. A "Check now" result never posts it (the user is looking at the card) but records `updates.notified_version_code`. Title "Neutrodyne {versionName} is available", text the first line of the notes; a tap opens Settings › Updates (`neutrodyne://open/settings/updates`); the Android action "Open on GitHub" (`neutrodyne://open/settings/updates/release`, explicit to `MainActivity` per 01's rule) makes Settings › Updates open `info.releaseUrl` as its own button does; final texts are 08's. It is cancelled when that version runs or is skipped.
- **After a manual install** the OS installer replaces the app (Android also ends its processes like any update; desktop installers expect the app to be closed, and the MSI asks to close it, Unverified wording). At the next start the stored version is at or below the running one, so `state` returns to `Idle` and the notification is cancelled. Positions, the queue and downloads resume as after a restart (06, 07, 11); nothing plays by itself.

### Notices

`UpdateNoticesImpl` raises at most one pending `UpdateNotice`; each shell's root shows it through 08's keys and `dismiss` records it:

| Notice | Platforms | Raised when | Recorded in |
|---|---|---|---|
| `FIRST_RUN_CHOICE` | both | first start of a build with the update check (M11a or later), not in debug builds and not when `updates.check_enabled` is already off (a restored choice; the card is then suppressed and `updates.first_run_choice_done` is still set, so it never appears later — 05's proposed default, its open question 16). It is PO-31's disclosure; "Turn off" writes `updates.check_enabled = false` | `updates.first_run_choice_done` |
| `VERIFICATION_ENFORCEMENT` | Android | `now ≥ VerificationTimeline.NOTICE_FROM`, not in debug builds, once per installation, on every device (the app cannot tell reliably whether a device is certified). Before `GLOBAL_ENFORCEMENT` (or while it is unknown) 08 shows the pre-enforcement notice; at or after it — which is what installations made after enforcement, through the advanced flow, see on their first start — the post-enforcement hint ("If you chose '7 days' … switch to 'indefinitely'") | `updates.verification_notice_shown_at` |

`WHATS_NEW` was removed with PO-31: the app no longer installs updates, so it cannot know which start follows its own install; the notes are on the update card and the release page. The desktop has no notice for Gatekeeper or SmartScreen: those gates appear at install time, and the card's install-kind hint and the help page cover them (11).

`VerificationTimeline` (compiled-in constants, [PO-36](../PLAN.md#48-further-product-owner-decisions)): `NOTICE_FROM = 2026-12-01`, or an earlier date as soon as Google names the global enforcement date; `GLOBAL_ENFORCEMENT` = Google's date, null until announced. Because it is a date comparison on the device, a build shipped before December shows the notice on 2026-12-01 even if the user never updates again. A change of either constant ships in the next release ([Watch and notice timing](#watch-and-notice-timing)).

### Privacy and failure modes

- Network: `github.com` and `release-assets.githubusercontent.com` only, one manifest GET per check, listed as `app-updates` in the [network inventory](#network-inventory). The package itself is downloaded by the user's browser, outside the app. GitHub sees the IP address and the standard User-Agent (app version, OS release) and no identifier; no request carries a cookie or token.
- Backup: only the portable `updates.check_enabled` travels (05's whitelist; a desktop library moved by a backup ZIP carries it too) and it is never synced ([10 What syncs](10-sync.md#what-syncs)); the device keys and `last-check.json` never travel. A restored `true` never overrides `Disabled(DEV_BUILD)`.
- A restart or process death is harmless: nothing is in flight but a GET; WorkManager re-runs the work, the desktop lane becomes due again, and `UpdateCheckStore` restores `Available` without a request.
- Logs carry versions and states, never the signed redirect URLs (01's `Redactor` masks query values).
- No install permission and no `PackageInstaller` anywhere (01's `checkBannedApis`, `verifyManifestPermissions` against `permissions.txt`); on the desktop no download, no file write outside `<data>/updates/` and no process start.

### Tests

| Test class | Module, runner | Cases | Milestone |
|---|---|---|---|
| `UpdateManifestParserTest` | `:core:data`, `commonTest` | schema 1 with unknown fields; each field rule of [Update manifest](#update-manifest), including `desktop[]` and `server` and manifests without them; `versionCode` vs D63's formula on the [Version scheme](#version-scheme) examples (`0.1.0` → 10095, `1.0.0` → 1000095, `1.2.3` → 1020395; `VersionScheme` and `release.sh` share them); link rejection (`releaseUrl` or any `url` outside this repository's releases, `http://`, another owner, a look-alike host, user-info, a port, a query, dot-segments such as `{repoUrl}/releases/../../../attacker/x/releases/download/v1/evil.apk` and their `%2e%2e` forms, encoded `/` and `\`, a tag other than `v{versionName}`, a `url` whose last segment differs from `file`); the opened URL is the rebuilt one; ABI selection by `SUPPORTED_ABIS[0]` (`apk == null` when absent); unknown ABIs, OSes, architectures and kinds ignored; an `image` reference with a tag or another owner rejected; > 64 KB rejected | M11a |
| `DesktopAssetSelectorTest` | `:core:data`, `commonTest` | every rule of [11 Desktop update check](11-desktop.md#desktop-update-check): each install kind picks its own kind; the fallback chain (`msi` ↔ `zip`, `dmg` ↔ `mac-zip`, `deb`/`rpm` → `tar.gz`); Windows on Arm → x64; `DEV` → none; `minOs` above the running OS → none with the OS message; macOS `mac-zip` users moved to the `dmg` at `1.0.0` (PLAN M11 AC3) | M11a |
| `GitHubUpdateSourceTest` | `:core:data`, `desktopTest` + MockWebServer (hosts rewritten) | the two-hop redirect; 404 → `MANIFEST_INVALID`; 403/429 → `RATE_LIMITED`; I/O → `NETWORK`; link rejection end to end (dot-segments included); an interceptor fails the test on any request to `api.github.com` | M11a |
| `AppUpdateCheckerTest` | `:core:data`: the common rules in `commonTest` with a fake `UpdateCheckRunner`, a fake update source and `TestClock`; the Android runner in `androidHostTest` with the WorkManager test driver and MockWebServer | the state rules; checks off → `Disabled(CHECKS_OFF)` and no work scheduled; `checkNow` with checks off makes exactly one request, a repeat within 60 s none; `checkNow` offline (`FakeNetworkMonitor` disconnected) → `Failed(NETWORK)` at once (or `Available` with `lastError = NETWORK`), no request and nothing scheduled; `checkNow` against a 429 → `Failed(RATE_LIMITED)`, one request, no retry; a server that never answers → `NETWORK` after 30 s (`TestClock`) and the check cancelled; skip → `Idle`, notification cancelled, a higher `versionCode` shows again; notification once per version, only from the scheduled check, never with `minSdk > SDK_INT`; a failed check keeps `Available` with `lastError`; `Available` restored from `UpdateCheckStore` after a restart without a request; the stored version now running → `Idle`, notification cancelled; debug build → `Disabled(DEV_BUILD)`, `checkNow` refused; no request to `api.github.com` (PLAN M11 AC6) | M11a |
| `DesktopUpdateCheckLaneTest` | `:core:data`, `desktopTest` with `TestClock` and a fake notifier | due 24 h after the last check with jitter in range; the first run 24 h after the first start; switch off or `DEV` → no request; backoff after a failure; one `APP_UPDATE` notification per version from the lane, none from "Check now"; overdue after a simulated sleep → runs within 2 min (R8.7) | M11a |
| `UpdateNoticesTest` | `:core:data`, `commonTest` + `TestClock` | `FIRST_RUN_CHOICE` once (fresh install and first update to an M11a build) on both platforms, never in debug builds or with checks already off (then `updates.first_run_choice_done` is set without the card); `VERIFICATION_ENFORCEMENT` only on Android, not before `NOTICE_FROM`, once at or after it, with the pre-enforcement variant before `GLOBAL_ENFORCEMENT` and the post-enforcement variant at or after it (a fresh install after the date sees it once) | M11a |

`check-update-json.sh` has its own fixture tests (a valid manifest per milestone stage, each rejection), run in PRs that touch the release scripts. Removed with PO-31: `ApkVerifierTest`, `ApkInspectorTest`, `SelfInstallerTest`, `VerificationFailureMapperTest` and `InstallIdleGateTest`.

### Device checklist (M11a)

Recorded in the M11a release issue; uses two consecutive releases (normal releases, so `releases/latest` serves the newer one), PLAN M11 AC3 and AC6:

- **Android:** with the older release installed and checks on, the scheduled check finds the newer release (run it with `adb shell cmd jobscheduler run -f ch.lkmc.neutrodyne <job id>`, the id from `adb shell dumpsys jobscheduler`): exactly one notification for that version, whose tap opens Settings › Updates; the gear badge appears; the card shows the notes, the size and the SHA-256 of the `Build.SUPPORTED_ABIS[0]` APK.
- "Open release on GitHub" opens the tag's release page; "Download APK for this device" downloads that APK in the browser, and its `sha256sum` equals the card's value. Android's installer installs it over the running app on an API 26 and an API 37 device, and the library, groups, positions, Up next and downloads survive (PLAN M11 AC9).
- An `armeabi-v7a` install on a 64-bit phone is linked to the `arm64-v8a` APK, and installing that over it keeps the data.
- "Skip this version" removes the card, the badge and the notification until a newer release.
- "Check for updates" off: no `app-update-check` job is scheduled (`dumpsys jobscheduler`) and nothing is sent to GitHub until "Check now".
- The merged manifest holds no install permission (`permissions.txt`); a network capture of the session shows no request to `api.github.com` ([v1.0 gate](#v10-gate) row 8).
- Obtainium installs the newer release with the per-ABI filter (the help card's instructions).
- **Desktop, on each OS** (Windows 11 x64 with the MSI and the ZIP, macOS 15 with the tester ZIP — the DMG from `1.0.0` — and Ubuntu 24.04 with the DEB and the tar.gz): with the older release running and checks on, "Check now" and the lane (its due time moved with the setting store's file edited while the app is quit) find the newer release; one OS notification per version from the lane; the card names the asset of this install kind with its size and SHA-256 (`Get-FileHash`, `shasum -a 256`, `sha256sum` match); installing it over the old version keeps the library, settings and downloads (MSI and DEB in place; ZIP, macOS app and tar.gz replaced, then macOS "Open Anyway" again); checks off → no request in the proxy log of `network-capture.sh --desktop`; a Windows 11 on Arm laptop, if available, gets the x64 link.

---

## Reproducible builds

Serves N12 (independent verifiability) and [D79](../PLAN.md#3-key-decisions). Delivered in M0a (hygiene, nightly job for the APKs), M0b (desktop application JARs and the server JAR). **Report-only, permanently:** no store rebuilds Neutrodyne, so nothing gates a release on reproducibility ([D79](../PLAN.md#3-key-decisions)). The nightly check stays because a build anyone can reproduce from a tag is a cheap trust signal next to the attestations. For the APKs it is a strong one: with the signing key in the repository a rebuild can match the published APK byte for byte, signature included. For the desktop it covers the application JARs only: the installers (MSI, DMG, DEB, RPM) embed timestamps and generated IDs, jlink's runtime image and the per-runner AOT cache are not compared, and the archives (ZIP, tar.gz) carry file times. The server JAR is compared as a whole; the image is not (its base layers are pinned by digest, and its own layer holds only the JAR and a notice file).

### Hygiene

| Source of non-determinism | Mechanism | Owner |
|---|---|---|
| Timestamps, git data in `BuildConfig` or the desktop build-info resource | none ever ([01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)) | 01 |
| Build-time secrets | PR, nightly and repro builds read **no** `-P` secret: `neutrodyne.acraMailto`, `neutrodyne.repoUrl` and `neutrodyne.engineManifestUrl` are committed in `gradle.properties`. After Podcast Index's written permission (PO-3 option A) `release.yml` injects its key into the published APKs and desktop packages only; a published APK then differs from a rebuild of its tag in `BuildConfig` and therefore in its signature, and a desktop JAR in its build-info resource (when option A is adopted, the README's "Check it" item and the [release body](#release-body) template gain a sentence saying so; until then nothing differs, [D79](../PLAN.md#3-key-decisions)) | 01, 09 |
| Signing | every APK, the repro job's included, is signed with the committed keystore through `neutrodynePublic` ([Gradle signing configuration](#gradle-signing-configuration)), so the job compares **signed** APKs. Unverified until the job shows it: that AGP's v2/v3 signing produces identical bytes for identical input with this RSA key; if not, the job compares everything except the APK Signing Block and reports that | 09 |
| PNG crunching | `buildTypes.release { isCrunchPngs = false }` in `:app` (`benchmarkRelease` and `nonMinifiedRelease` inherit it); PNGs are committed pre-optimised, the brand assets by `generateBrandAssets` ([08 Brand assets](08-ui-ux.md#brand-assets)); 01's build-type sketch delegates the line here | 09 |
| VCS info | `buildTypes.release { vcsInfo { include = false } }` in `:app` (a rebuild from a source archive has no `.git`; nothing depends on it); AGP adds `META-INF/version-control-info.textproto` to release builds by default (Unverified for AGP 9.4, which is why `check-apk.sh` checks that it is absent) | 09 (delegated by 01) |
| AboutLibraries metadata | offline mode on both shells: no remote licence or funding fetches during the build (Unverified 15.x property names, e.g. `offlineMode = true`, `fetchRemoteLicense = false`) | 01 |
| R8 | the published build is minified; R8 output is expected to be deterministic for the same inputs. Known kotlinx.coroutines service-loader nondeterminism is countered only if the job shows it, with `-keep class kotlinx.coroutines.CoroutineExceptionHandler` and `-keep class kotlinx.coroutines.internal.MainDispatcherFactory` in `app/src/main/keepRules/app.keep` ([01 Release build and baseline profiles](01-foundation.md#release-build-and-baseline-profiles)) | 01, 09 |
| Compiled ART profile (`assets/dexopt/baseline.prof`/`.profm`) | from M11b AGP compiles the committed profile from `app/src/release/generated/baselineProfiles/` into the APK; the same committed profile must give the same bytes (Unverified; the job reports it) | 01, 09 |
| Python bytecode | Chaquopy compiles the shim to `.pyc` at build time with the container's Python (the standard library arrives precompiled from Maven Central; yt-dlp is compiled on the device, 04); CPython writes timestamp-based `.pyc` headers unless `SOURCE_DATE_EPOCH` is set, in which case it writes checked-hash `.pyc` ([py_compile](https://docs.python.org/3/library/py_compile.html)). The container sets `SOURCE_DATE_EPOCH` to the tagged commit's time (Unverified: that Chaquopy's compile step honours it; the job reports it). The desktop compiles nothing at build time: the bundled interpreter compiles on the user's machine (11) | 09 |
| Vendored engine and Python runtimes | the yt-dlp asset is copied byte for byte from `youtube/ytdlp/engine/`; Chaquopy's runtime comes from Maven Central with verification metadata (01); python-build-standalone, Temurin and the FFmpeg source come from pinned, checksum-verified downloads (lockfiles) | 01, 04, 11 |
| JARs (desktop modules, the KMP modules' desktop JARs, `:desktopApp`, the server's fat JAR) | every `Jar`/`Zip` task with `isPreserveFileTimestamps = false` and `isReproducibleFileOrder = true` (set by the convention plugins); the fat JAR merges `META-INF/services` in sorted order (Unverified for the Compose plugin's own JAR tasks; the job reports it) | 01, 09 |
| Toolchain | the [release container](#releaseyml) for Android: Debian 13 with CPython 3.14 (`python:3.14-slim-trixie@sha256:<digest>`) and Debian's OpenJDK 21; `build-tools;36.0.0` and `platforms;android-37.0` pinned by `install-android-sdk.sh`; Gradle 9.7.1 with `distributionSha256Sum`. For the JAR comparison the repro container adds Temurin 25 from `runtime.lock`'s Linux x64 archive (checksum-verified); the release's own JARs are built on the hosted runners with the same pinned JDKs | 09 |
| Build cache, locale, time zone | `--no-build-cache`; `LC_ALL=C.UTF-8`, `TZ=UTC` in the container | 09 |
| `dependenciesInfo` signing block | disabled: it is an encrypted block only Google Play reads | 01 |

Unverified: whether the `platforms;android-37.0` revision can change under the same name and alter the output; the repro job records `source.properties` of the platform in its log. (Deviation 2026-10-06: Google's SDK repository packages the platform as `android-37.0` — minor-versioned since the 36.1 repackaging — not `android-37`; the script name was updated.)

### Nightly reproducibility job

1. Start two containers from the release container image (digest pinned, Renovate-managed) with different checkout paths (`/build/a` and `/home/builder/src/neutrodyne`), different CPU counts (`--cpus=2` and `--cpus=4`) and different umasks (022, 002).
2. In each: install `openjdk-21-jdk-headless git unzip curl` and Temurin 25 from `runtime.lock`, run `install-android-sdk.sh`, then `./gradlew --no-daemon --no-build-cache assembleRelease :desktopApp:createDistributable :sync:server:fatJar -Pneutrodyne.abiSplits=true` (the three release APKs, signed with the committed keystore; the Linux x64 app image; the server JAR).
3. Compare the SHA-256 of each pair of APKs, of the JARs under the app image's `app/` directory whose names start with `neutrodyne` or that come from our modules (dependency JARs come byte for byte from Maven), and of the server JARs; on a difference run `diffoscope` and upload its HTML report.
4. Report-only: a difference opens or updates one issue (label `repro`, through `report-nightly.sh`), never a `release-blocker`; a fix ships with the next regular release.

Anyone can repeat the comparison for a tag: build it with `scripts/ci/repro-build.sh assembleRelease` and compare the whole APK — signature included, since the key is in the repository — with the published APK; for the desktop, compare our JARs inside the published `linux-x64` tar.gz with those of a rebuild; for the server, compare `neutrodyne-server-{v}.jar` as a whole. After PO-3 option A, the Podcast Index fields in `BuildConfig` and in the desktop build-info resource (and with them the APK signature) differ, so the comparison then excludes the APK Signing Block, `BuildConfig` and that resource.

---

## Developer verification

Serves R6.1, R6.4 (Android); mitigates risks P3, P8, P9. Delivered in M0a (README "Install and update" first draft, including the release-build and public-key statement), M11a (in-app help, pre-enforcement notice, README final), M11b (review against Google's then-current rules). Honours [D80](../PLAN.md#3-key-decisions), [PO-5](../PLAN.md#po-5-google-developer-verification) (resolved 2026-10-05: **do not register**), [PO-36](../PLAN.md#48-further-product-owner-decisions), [D61](../PLAN.md#3-key-decisions), [D78](../PLAN.md#3-key-decisions).

Neutrodyne does not register with Google's Android developer verification: registering would tie a legal identity to an app that extracts YouTube streams ([D80](../PLAN.md#3-key-decisions)). Its desktop analogue — no Apple Developer ID or notarisation, no Windows code signing, so Gatekeeper's "Open Anyway", SmartScreen and Smart App Control — is documented in [11 Install and update](11-desktop.md#install-and-update) and the README's "Install on Windows, macOS or Linux" (risks P12, P13); this section is Android's. This section owns the facts that the README's "Install and update" section and 08's help page and notice rely on. The app installs nothing itself ([Update check](#update-check)), so every install and update goes through Android's own installer, which shows whatever the verification policy requires; the help page explains it. All facts checked 2026-10-05 against Google's pages ([overview](https://developer.android.com/developer-verification), [guides](https://developer.android.com/developer-verification/guides), [FAQ](https://developer.android.com/developer-verification/guides/faq), [Help Center](https://support.google.com/android/answer/17065026?hl=en), [advanced flow](https://support.google.com/android/answer/17588095?hl=en), [blog 2026-03-19](https://android-developers.googleblog.com/2026/03/android-developer-verification.html)).

### Phases and devices

| Phase | When | Effect on a Neutrodyne installed from GitHub |
|---|---|---|
| Tooling | March–August 2026 | none; the "advanced flow" for unverified apps launched gradually in August 2026 |
| First enforcement | since 2026-09-30 | none: it covers certified devices in Brazil, Indonesia, Singapore and Thailand and only installs from seven participating stores (Google Play, HONOR App Market, OPPO App Market, Galaxy Store, Palm Store, V-Appstore, GetApps); "if users sideload your app directly, these new verification requirements won't apply to your app yet" ([guides](https://developer.android.com/developer-verification/guides), [FAQ](https://developer.android.com/developer-verification/guides/faq)). Tester builds since M0a are unaffected everywhere |
| Global rollout | "2027"; no date published (Unverified whether it is staged by region or source) | on certified devices every **new install and every update** of an unregistered app is blocked unless the user turned on the advanced flow or installs over ADB |

Affected: certified Android devices with Google Play services (phones and tablets; Android 8 and up per the Help Center, "Android 7+" on the developer site — minSdk 26 is covered by both). Not affected: AOSP and non-certified devices — GrapheneOS, LineageOS without Google apps ([LineageOS statement](https://lineageos.org/Developer-Verification/)), /e/OS, Huawei and Fire OS devices — and regions where Google Mobile Services are unsupported ([Help Center](https://support.google.com/android/answer/17065026?hl=en)). The source of the APK does not matter: an APK the user downloaded from GitHub through the update card, one opened from Files and one installed by Obtainium are all gated the same way. YouTube-engine updates are files the app downloads, not installs, so they keep reaching users whose APK updates are blocked ([04 Engine updates](04-youtube.md#engine-updates)).

### Advanced flow

The one-time device setting Google provides for unverified apps ([Help Center](https://support.google.com/android/answer/17588095?hl=en), [blog](https://android-developers.googleblog.com/2026/03/android-developer-verification.html)):

1. Turn on Developer options (Settings › About phone › tap Build number 7 times).
2. Settings › System › Developer options › "Allow apps from unverified developers".
3. Confirm that nobody is guiding you through this (Google's anti-coercion check) and re-authenticate.
4. The phone restarts.
5. After a one-time 24-hour wait, confirm with fingerprint, face or PIN.
6. Choose **"7 days"** or **"indefinitely"**.
7. Each install or update of an unverified app then shows a warning with "Install anyway".

Consequences the guidance must state: with "7 days", or after switching the setting off, **updates of unregistered apps fail again** ([FAQ](https://developer.android.com/developer-verification/guides/faq)) — the "7 days" trap, which is why every text recommends "indefinitely"; Developer options can be switched off afterwards; the flow is delivered through Google's components and can change without an Android update (risk P8). Unverified: whether the setting is per Android user or profile, and whether re-enabling after "7 days" repeats the wait.

| Situation (from Google's global rollout) | First install | Update (APK downloaded from GitHub or installed by Obtainium) |
|---|---|---|
| Certified device, advanced flow off or "7 days" expired | blocked | blocked: the installed version keeps running; Android's installer shows its block, and the Install & updates help explains the advanced flow and ADB (08) |
| Certified device, advanced flow "indefinitely" | warning, "Install anyway" | warning per update, "Install anyway" |
| Non-certified device | as today | as today |
| Any device, `adb install -r` | allowed | allowed |

### Exempt and fallback paths

- **ADB:** installs over ADB are exempt by design ("no changes to how ADB works", [FAQ](https://developer.android.com/developer-verification/guides/faq)); in AOSP a session whose caller runs as shell or root is marked `INSTALL_FROM_ADB` and skips the verifier ([PackageInstallerSession](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/pm/PackageInstallerSession.java)). Documented as `adb install -r neutrodyne-{v}-{abi}.apk` for users with a computer — impractical for every update.
- **Shell-UID installers such as Shizuku** (e.g. Obtainium in Shizuku mode) inherit that exemption in the current AOSP code (Unverified on an enforcing device); Shizuku must be restarted after every reboot through Wireless debugging ([Shizuku](https://shizuku.rikka.app/guide/setup/)) and Google can close the path. Documented as a power-user fallback only, never built into Neutrodyne.
- **Non-certified systems** need nothing; the README says so. Root-based hooks are not documented.
- **Android Auto** is a separate gate for any non-store install: sideloaded media apps appear only after Android Auto's developer setting "Unknown sources" ([Android Authority](https://www.androidauthority.com/sideload-apps-on-android-auto-3681820/); PLAN M5 AC4).

### Package name and key

- Package names are allocated to registered keys: a key with more than 50 % of known installs has priority, any key with at least 50 installs may register, otherwise first come, first served ([package-name rules](https://developer.android.com/developer-verification/guides/android-developer-console)). Someone could register `ch.lkmc.neutrodyne` with another key before Neutrodyne has an install base, and because the committed key is public, anyone can even prove possession of Neutrodyne's own certificate and register that (risk P9). The consequences for our installs are Unverified (most likely they remain "unverified" and need the advanced flow; a stranger's registration of our certificate could also lead Google to block it). Mitigation: public releases build the install cluster quickly; nothing short of registering prevents it.
- Registering ourselves would need a private key Neutrodyne does not have: a new private key, and with it one reinstall for every user ([Public key trade-offs](#public-key-trade-offs), [D61](../PLAN.md#3-key-decisions)). The free limited-distribution account (at most 20 explicitly authorised devices, [limited distribution](https://developer.android.com/developer-verification/guides/limited-distribution)) is not a public-release path.
- `debug` builds (`ch.lkmc.neutrodyne.debug`, never published) are installed by developers over ADB and are exempt.

### Watch and notice timing

- **Watch:** a recurring calendar issue every 2 weeks (label `verification-watch`) re-reads the overview, guides, FAQ and Help Center pages and Google's Android Developers Blog. A change (a global date, a different advanced flow, new exemptions) updates this section, the README section, 08's help strings and `VerificationTimeline` in the next release, and is reported to the PO, who may reconsider registration (risk P8; it would need a private key, [Package name and key](#package-name-and-key)).
- **Notice timing ([PO-36](../PLAN.md#48-further-product-owner-decisions)):** the one-time in-app notice (08's `VerificationNoticeKey`, raised by [Notices](#notices)) appears from 2026-12-01, or from the date Google names for the global rollout if that is earlier; it is a date check on the device, so builds released before December also show it. Neutral tone: what the advanced flow costs (a restart and a 24-hour wait), why "indefinitely", that non-certified systems are unaffected; no countdown, no urgency, no blame. After the global date the same one-time notice switches to 08's post-enforcement hint: every installation that has not seen the notice — in practice each fresh install, made through the advanced flow — is told once that "7 days" stops updates after a week and how to switch to "indefinitely"; the help page leads with the Google Play card (08). M11a is small and needs only M2 and M0 (PLAN 7.1), so the pre-enforcement notice can reach users well before Google's date; the post-enforcement hint covers installations made afterwards.
- **Support:** a pinned GitHub discussion or issue "Updates stopped working?" links the README section when enforcement starts.
- **Testing:** CI never sees enforcement — Gradle Managed Devices and emulators install over ADB, which is exempt — and the app has no install path of its own to test. The help text and the notice are checked by hand on an enforcing certified device once the global rollout starts (2027): a fresh install and an update downloaded from the update card, with the advanced flow off, on an expired "7 days" and on "indefinitely". `UpdateNoticesTest` covers the notice dates.

### README "Install and update"

The README section's content is owned here (the README is maintained with the PLAN); 08's help page mirrors it and the [release checklist](#minor-and-stable-release-additions) compares both. Draft (M0a, finalised in M11a; `{…}` filled in):

1. **Download only from GitHub:** `{repoUrl}/releases`. There is no Play Store version; any other copy is not Neutrodyne's. Pick `neutrodyne-{v}-arm64-v8a.apk` for most phones and tablets, `-x86_64.apk` for x86_64 devices, `-armeabi-v7a.apk` for older 32-bit phones (YouTube episodes then open in the YouTube app).
2. **Check it (optional):** Neutrodyne's APKs are release builds signed with a key that is public in this repository, so the signature doesn't prove who built a file. Download only from this repository's releases page and compare the file with the release's `SHA256SUMS` (`sha256sum --check --ignore-missing SHA256SUMS`), or, with the GitHub CLI, run `gh release verify-asset {tag} {file} -R {owner}/Neutrodyne` and `gh attestation verify {file} -R {owner}/Neutrodyne`.
3. **Allow the install:** Android asks once whether your browser (or Files app) may install apps.
4. **Phones with Google Play, from 2027:** Android will install apps only from developers registered with Google unless you turn on a one-time setting. Neutrodyne is not registered (why: below). Developer options › "Allow apps from unverified developers"; follow the steps (restart, 24-hour wait, fingerprint or PIN); choose **indefinitely** — with "7 days", updates stop working after a week; each install or update then shows a warning: tap "Install anyway". You can turn Developer options off afterwards. Nothing changes before Google's global start.
5. **Phones without Google certification** (GrapheneOS, LineageOS without Google apps, /e/OS) need none of this.
6. **Other ways (advanced):** `adb install -r {file}` from a computer; installer apps that work through Shizuku. Both may stop working if Google changes its rules.
7. **Updates:** Neutrodyne checks GitHub once a day and, when a new version exists, notifies you and links to it (Settings › Updates › Check for updates); download the APK for your phone and install it over the old one — your library stays. Or use Obtainium with an APK filter for your file (e.g. `neutrodyne-.*-arm64-v8a\.apk$`) and turn Neutrodyne's own check off. YouTube engine updates arrive separately and need no install.
8. **About these builds:** the maintainers publish optimised release builds signed with a key that is public in this repository, so that no signing key has to be kept secret. That means the signature proves nothing: anyone can sign an APK that installs over Neutrodyne and takes over its data — your subscriptions, listening history, the passwords of private feeds and your sync server's device token — so download only from this repository's releases page. A later switch to a private key would need one reinstall, so keep a backup or link a sync server. Details: [Public key trade-offs](#public-key-trade-offs).
9. **Android Auto:** enable "Unknown sources" in Android Auto's developer settings.
10. **Changing phones:** make a manual backup first (Settings › Backup) and restore it on the new phone, or link both phones to your sync server (Settings › Sync, see "Run the server"). Setting up a new phone does not reinstall Neutrodyne; Android may restore your library when you install it there, but that is not guaranteed (PLAN R1.8, [05 Auto Backup](05-groups-opml-backup.md#auto-backup)).
11. **Why Neutrodyne is not registered:** registering would tie a legal identity to the app; the maintainers decided not to. One factual paragraph, no campaigning ([PO-36](../PLAN.md#48-further-product-owner-decisions)).
12. **Computers and the server:** the desktop app and the sync server have their own sections, "Install on Windows, macOS or Linux" (11's text) and "Run the server" ([README "Run the server"](#readme-run-the-server)).

Wording rules: Google built the anti-coercion check against scammers who coach victims through such steps, and this guidance is legitimately similar, so it stays factual — it says what the setting turns off, never urges haste, never addresses a user mid-install with a countdown. Item 8 states the trade-off neutrally, as the owner's choice, without alarm.

---

## Privacy

Serves N3 and the disclosure part of N13. Delivered in M0a (`PRIVACY.md` v0, `SECURITY.md`), M0b (the Desktop section's draft), updated whenever a document adds a network destination (M7 directories, M8/M9a YouTube, M9b engine updates, MD3 the desktop engine, M11a update checks, MS1 the server's update notice, MS2 sync), final in M11b. Honours [D62](../PLAN.md#3-key-decisions), [D76](../PLAN.md#3-key-decisions), [D78](../PLAN.md#3-key-decisions), [D93](../PLAN.md#3-key-decisions), [D94](../PLAN.md#3-key-decisions), [PO-31](../PLAN.md#48-further-product-owner-decisions), [PO-32](../PLAN.md#48-further-product-owner-decisions), [PO-37](../PLAN.md#48-further-product-owner-decisions), [PO-44](../PLAN.md#48-further-product-owner-decisions).

### Commitments

No analytics, advertising, tracking, Firebase or Google Play services in any APK or desktop build (Chromecast is not planned because it would need them, PO-6); no Neutrodyne-operated server and no account at the project; the optional sync server is the user's own, stores only what R7.3 lists and makes no outbound request except its optional update check; network traffic only to hosts the user chose or opted into — including GitHub for the update checks and for YouTube-engine updates, both disclosed and each with an Off switch (PO-31, PO-32), and the user's own sync server once linked; crash reports leave a device only through the user's own mail app after per-crash consent (ACRA on Android, the next-start dialog on the desktop); private feed URLs, feed tokens and sync tokens never appear in logs, crash reports, diagnostics or directory queries; the sync server is disclosed before linking as storing subscriptions with private feed links and listening history without end-to-end encryption ([D94](../PLAN.md#3-key-decisions)).

### `PRIVACY.md` outline

1. Summary (the commitments above, in plain words).
2. What stays on the device: library, groups, history, positions, Up next, downloads, settings; credentials — on Android encrypted with an Android Keystore key, on Windows protected with DPAPI for the user, on macOS and Linux in a file only the user can read ([PO-44](../PLAN.md#48-further-product-owner-decisions)).
3. Where the apps connect ([inventory](#network-inventory)) and what those hosts receive.
4. **Sync (optional):** what a linked server stores (R7.3: subscriptions with their feed URLs including private, tokenised ones; groups; played state, positions, favourites; Up next; the now-playing episode; synced settings; Basic-auth passwords only while "Share feed passwords" is on), what it never receives (audio, downloads, feed contents, artwork, crash data, device-local settings); that there is no end-to-end encryption, so whoever runs the server can read it, and TLS comes from the server's reverse proxy; that the server makes no outbound request except its optional update check and logs no tokens, passwords, feed URLs or payloads; unlinking, revoking devices and "Delete my data on the server"; Android's local-network permission and macOS's Local Network prompt for a server on the home network ([10 Security](10-sync.md#security)).
5. **Desktop:** where data lives (the per-OS directories of [11 AppDirs](11-desktop.md#appdirs)); redacted, rotated log files; crash files and the next-start email dialog; no Auto Backup — a backup ZIP or a sync server moves a library; uninstalling keeps the data, and how to remove it ([11 Uninstall and data retention](11-desktop.md#uninstall-and-data-retention)).
6. Backups: Android Auto Backup carries a daily library snapshot (feed URLs included, possibly with private tokens) to the user's Google account, only on devices with backup encryption (PO-15); manual backup files contain passwords only on opt-in (R1.9); the sync token never travels in a backup; the sync server's own nightly backups and per-account ZIPs stay on the server's disk (10).
7. Crash reports and diagnostics: contents (the same allow-listed fields on both platforms), consent, how to request deletion of an emailed report.
8. Permissions and why (Android, from 01's table; `ACCESS_LOCAL_NETWORK` only for a sync server on the local network).
9. What the YouTube engine and the update check contact and how to turn them off (Settings › YouTube "Play YouTube in the app" and "Engine updates"; Settings › Updates › "Check for updates"; external mode on the `armeabi-v7a` APK). The apps download no update: the user's browser does, from GitHub.
10. Contact and change history (git log of `PRIVACY.md`).

Removed 2026-10-05 (scope revision): the item about debuggable published builds (data readable over USB debugging, risk P11); published APKs are release builds.

### Network inventory

IDs are stable; the in-app "What Neutrodyne connects to" list (Settings › Privacy, both apps) uses the same IDs and `PrivacyInventoryParityTest` (`:feature:settings`, `desktopTest`) fails when the IDs in `PRIVACY.md` and the in-app list differ. The Builds column says which artefacts make the connection: "all" (every APK and desktop build), "with engine" or "server".

| ID | Destination | Purpose | When | Builds | Default |
|---|---|---|---|---|---|
| `feeds` | each subscribed feed's host and its redirect targets | fetch RSS/Atom ([03](03-feeds-and-discovery.md#fetch-pipeline)) | refresh (periodic, on open, pull), subscribe preview, import | all | user's subscriptions |
| `add-input` | the web page the user typed, pasted or shared into Add podcast, up to 5 well-known feed paths on that site (`/feed`, `/rss`, …) and the candidate the user picks | find the feed behind a web page ([03 Fetch, sniff and autodiscovery](03-feeds-and-discovery.md#fetch-sniff-and-autodiscovery)) | adding a podcast by URL or share | all | user action |
| `media` | enclosure hosts and the publishers' measurement redirects in front of them | stream and download audio ([06](06-playback.md#media-items-and-uri-resolution), [07](07-downloads.md#transfer-core)) | play, download, auto-download | all | user action / opt-in |
| `artwork` | artwork hosts referenced by feeds and by directory results (Apple's image CDN, fyyd's image host, Podcast Index `artwork` URLs) | covers, episode images and search-result covers ([08](08-ui-ux.md#artwork-pipeline)) | subscribe, artwork change, display, Discover results | all | on |
| `chapters` | hosts of Podcasting 2.0 chapter files | chapters ([06](06-playback.md#chapters)) | playing an episode that has a chapters URL | all | on |
| `notes-images` | image hosts inside show notes | show-notes images ([03](03-feeds-and-discovery.md#show-notes)) | per 03's `feeds.show_notes_images` setting | all | per 03 |
| `apple` | `itunes.apple.com`, `rss.marketingtools.apple.com` | search, lookup of Apple Podcasts / pod.link / Overcast links, charts ([03](03-feeds-and-discovery.md#search-and-discovery)) | Discover; adding such a link; the YouTube subscribe preview's "also has a podcast feed" check (channel title as query, `youtube.suggest_rss`, [04](04-youtube.md#prefer-the-shows-rss-feed)) | all | on |
| `fyyd` | `api.fyyd.de` | search | Discover; the same YouTube preview check | all | on |
| `podcastindex` | `api.podcastindex.org` | search, trending | with a user key, or with a project key injected into the published builds after Podcast Index's written permission ([PO-3](../PLAN.md#po-3-podcast-index-api-key-handling) option A); then also the YouTube preview check | all | off while no key exists (PO-3 default B); on once a project key exists; Settings › Discover switch (`discover.podcastindex_enabled`, 03) |
| `youtube-subscriptions` | `www.youtube.com` (feeds, channel page head, oEmbed), `i.ytimg.com`, `yt3.googleusercontent.com`, `yt3.ggpht.com` | YouTube channels as podcasts, layer A ([04](04-youtube.md#channel-resolution)) | only when the user adds or has a YouTube channel | all | user action |
| `youtube-streams` | `www.youtube.com` (watch page, `/youtubei/…` InnerTube requests made by yt-dlp: on Android through our OkHttp client in `:ytx`, on the desktop through yt-dlp's own HTTP stack in the CPython child with the IP family the JVM asks for, [11 Networking and TLS](11-desktop.md#networking-and-tls)), `*.googlevideo.com` (media, from the main process or the desktop JVM) (Unverified complete host list; the M9a and MD3 network captures record it) | audio streams, downloads, durations and flags, channel lookup, back catalogue, channel search — layer B ([04 YouTube engine](04-youtube.md#youtube-engine)) | playing, downloading or refreshing YouTube items; channel search | with engine (64-bit APKs, every desktop build) | user action; Settings › YouTube "Play YouTube in the app" turns it off |
| `youtube-engine` | `<owner>.github.io` (approved engine manifest), `github.com` (`yt-dlp/yt-dlp` release files), `release-assets.githubusercontent.com` | YouTube-engine updates without an app update, for both engine hosts ([04 Engine updates](04-youtube.md#engine-updates)) | daily, after a circuit-breaker opening (at most every 3 h), "Check for engine update"; never with policy Off | with engine | on (policy Neutrodyne-approved, PO-32) |
| `app-updates` | `github.com` (`<owner>/Neutrodyne/releases/latest/download/neutrodyne-update.json`, which redirects to the release asset), `release-assets.githubusercontent.com` | the update check of both apps: one manifest GET per check ([Update check](#update-check)); the APK or desktop package itself is downloaded by the browser when the user taps the update card's link (`links`) | daily while "Check for updates" is on (Android work; on the desktop only while the app runs); "Check now" | all | on (`updates.check_enabled`, PO-31; first-run card with one-tap "Turn off"); off in debug builds |
| `sync-server` | the user's own Neutrodyne Sync server at `sync.server_url` | sync: discovery, linking, pushes and pulls, Server-Sent Events for live updates ([10 Client sync engine](10-sync.md#client-sync-engine)) | only after the user configured a server: linking; pushes after changes, pulls at start, return to the foreground and playback pause, periodically (Android at most every 60 min in the background, the desktop every 15 min while running); SSE while the UI is visible or playback runs (Android) or while the app runs (desktop) | all | off (no server configured, R7.1) |
| `links` | any link the user taps (episode page, funding, person, the update card's "Open release on GitHub", "Download APK for this device" and "Download for this computer") | opened in the browser | tap | all | user action |
| `issue-tracker` | `github.com` | "Report a problem" opens the browser | tap | all | user action |
| `server-updates` | `github.com` (`<owner>/Neutrodyne/releases/latest/download/neutrodyne-update.json`), `release-assets.githubusercontent.com` | the sync server's notify-only update notice on its admin page ([10 Update notice](10-sync.md#update-notice)) | daily | server | on; `NEUTRODYNE_SERVER_UPDATE_CHECK=false` turns it off ([PO-45](../PLAN.md#48-further-product-owner-decisions)) |
| — | Neutrodyne-operated servers, analytics, ads, Google Play services, `api.github.com`; from the server: feeds, enclosures, artwork, YouTube or any other host | — | never | — | — |

What hosts receive: every request from an app carries the device's IP address and 01's User-Agent `Neutrodyne/<versionName> (<platform>; +<repo URL>)`, the platform being the OS release and on the desktop the architecture (no device or install identifiers, [01 Interceptors](01-foundation.md#interceptors)); feed requests carry stored `If-None-Match`/`If-Modified-Since`; private feeds and their same-origin enclosures carry Basic credentials (never across origins); directories receive only the query text and country ([03 Privacy](03-feeds-and-discovery.md#privacy)); YouTube receives the consent cookie `SOCS=CAE=` and, with the engine, what yt-dlp's InnerTube clients send (a client-specific User-Agent such as Safari for `visionos`, the video or channel ID, the app locale as `hl`/`gl`; cookies live only in the engine process's memory, [04 Networking bridge](04-youtube.md#networking-bridge)); GitHub (the update checks of both apps and of the server, and the engine's update checks and downloads) sees the IP address and the User-Agent, no identifier. The user's sync server receives the device token, the device's name, platform and app version, and the synced library state of R7.3 — subscriptions with their feed URLs (private ones included), groups, played state, positions, favourites, Up next, the now-playing episode and the synced settings; Basic-auth passwords only while "Share feed passwords" is on for the pushing device ([10 What syncs](10-sync.md#what-syncs)). Publishers' measurement redirects can count downloads by IP and user agent; the apps add nothing to help or hinder that. Outside the apps' control and disclosed: Android's own Auto Backup transfer, system DNS (Private DNS honoured), the OS's own certificate and update checks, and the user's mail app for crash reports.

### Redaction surfaces

01's `Redactor` ([01 Logging and redaction](01-foundation.md#logging-and-redaction)) is the only redaction algorithm in the apps; the server follows 10's logging rules ([10 Logging rules](10-sync.md#logging-rules)). This table lists where redaction must be applied and how it is tested.

| Surface | Rule | Test |
|---|---|---|
| Logs (Android Logcat and `RingBufferLogSink`; desktop `<logs>/neutrodyne.log` and the ring buffer) | every message through `Redactor.text` (01); on the desktop also paths under the user's home written as `~/…` and sync server addresses masked ([11 Logs and rotation](11-desktop.md#logs-and-rotation)) | 01's `Redactor` tests; `RedactionCoverageTest` |
| Desktop engine log (`<logs>/engine.log`) | URLs replaced by `<url>` in the shim, then `Redactor.text` in the JVM (11) | 11's `StdioYtxTransportTest`; `RedactionCoverageTest` |
| Crash reports (Android ACRA) | `STACK_TRACE` and `CUSTOM_DATA` through `Redactor.text`; field allow-list; no `LOGCAT`, `BUILD_CONFIG`, `SHARED_PREFERENCES`, device IDs | `CrashReportRedactorTest` |
| Crash files and the email body (desktop) | the same field set and redaction as the ACRA report ([Crash reporting and diagnostics](#crash-reporting-and-diagnostics), [11 Crash files and the email dialog](11-desktop.md#crash-files-and-the-email-dialog)) | 11's `CrashReporterTest`; `RedactionCoverageTest` |
| Diagnostics text, issue pre-fill (both apps) | built only from redacted values; the sync section shows no server address, record or token | `RedactionCoverageTest` |
| Exported database copy | 02's scrub procedure ([Copy, report and export](#copy-report-and-export)); the `credential` rows, the sync token and `sync_*` payload columns are scrubbed with the other credentials | 02's `DiagExportScrubTest`, `DatabaseCopyExporterTest` |
| OPML export, backup ZIP | passwords only on opt-in; private-URL warning (05); no sync token | 05's tests |
| Server logs and the audit log | no token, link or invite code (except the one-time setup banner), password, feed URL or payload ([10 Logging rules](10-sync.md#logging-rules), N13) | 10's `LogRedactionTest` |

`RedactionCoverageTest` (`:core:data`, `desktopTest`, with an Android part in `:app`'s Robolectric tests for ACRA; M11b) seeds a podcast with feed URL `https://alice:s3cret@feeds.example.invalid/rss/a8F3kq09ZpLm2xQr7Tz4?token=SECRETTOKEN` (the 20-character path token is longer than the 14 characters 01's `Redactor.url` rule 4 keeps) and an episode **without a GUID** whose enclosure is `https://cdn.example.invalid/ep1.mp3?auth=SECRET2` (so its identity key is a `u:` key carrying the URL, [02 Episode identityKey](02-data-model.md#episode-identitykey)), links the test database to an `InMemorySyncServer` at `https://sync.example.invalid` with the token `nds_SYNCTOKENSECRET`, provokes a refresh failure, a sync failure and a crash-report collection, then asserts that none of `s3cret`, `SECRETTOKEN`, `SECRET2`, `a8F3kq09ZpLm2xQr7Tz4`, `nds_SYNCTOKENSECRET` and `sync.example.invalid` occurs in: the diagnostics report text, the issue pre-fill URL, the ring-buffer log and (desktop) the log file, the `CrashReportData` after `CrashReportRedactor` (Android part) and the desktop crash file, and the exported database copy (read back byte-wise, so text left in free pages also fails the test).

### Security reporting

`SECURITY.md`: report vulnerabilities through GitHub private vulnerability reporting; acknowledgement within 7 days; fixes ship as PATCH releases through the normal pipeline (every product of the tag). Scope: parsing of feeds, OPML, backups and import files (N9), the exported `ArtworkProvider`, intent and desktop link handling (URL schemes, file associations, the single-instance handshake), credential storage on both platforms, the update check's manifest and link validation ([Update manifest](#update-manifest)), the engine-update trust chain and the desktop engine child ([04 Trust chain](04-youtube.md#trust-chain), risk P14), the sync client's handling of server data, and the sync server (authentication and device linking, input caps, rate limits, the web UI, the image; [10 Security](10-sync.md#security), N13). The APK signing key is public by design ([Public key trade-offs](#public-key-trade-offs)), so a report of an APK signed with it but offered elsewhere is answered with the download guidance, not as a key compromise; the same holds for unsigned desktop packages offered elsewhere; a suspected engine-manifest-key compromise follows the rotation in [engine-canary.yml](#engine-canaryyml).

---

## Crash reporting and diagnostics

Serves N3, N2 (diagnosability). Delivered in M0a (ACRA wiring in the release build, disabled until PO-10 names a mailbox), M0b (the desktop's crash files, 11), M9a and MD3 (engine health lines), M11a (update-check lines), MS2 (sync lines), M11b (final configuration, the crash dialog on the desktop, the diagnostics screens of both apps). Honours [D62](../PLAN.md#3-key-decisions) (scope revision: ACRA in release builds, crash files on the desktop, logs only on the server), [PO-10](../PLAN.md#48-further-product-owner-decisions) default.

### ACRA configuration

ACRA 5.14.2 with `acra-mail` and `acra-dialog`: zero network traffic from the reporter. `installAcra` is called from `NeutrodyneApplication.attachBaseContext` when `BuildConfig.ACRA_MAILTO` is not empty, the process is not `:ytx` and the system property `neutrodyne.instrumentedTest` is not set (only `:app`'s instrumented tests set it; the check sits at 01's call site, not inside `installAcra`, so `YtxIsolationTest` can call `installAcra` itself with a test address, [Gradle Managed Devices](#gradle-managed-devices)); the `:acra` process returns early from `onCreate` ([01 Application start-up](01-foundation.md#application-start-up)). ACRA is never installed in `:ytx` ([D62](../PLAN.md#3-key-decisions)): an engine crash or hang is recorded by the main process as engine health, never offered to the user as a crash report (PLAN M9 AC5). `ACRA_MAILTO` comes from the committed `neutrodyne.acraMailto` ([Hygiene](#hygiene)) and is forced to `""` **only in `debug` builds**, by `buildConfigField("String", "ACRA_MAILTO", "\"\"")` in `buildTypes.debug` ([01 Debug build type](01-foundation.md#debug-build-type); a build-type field overrides `defaultConfig`'s; debug builds crash fast with StrictMode and LeakCanary instead). ACRA, crash reporting and diagnostics are therefore active in every published release build ([D62](../PLAN.md#3-key-decisions)) once PO-10 names the mailbox. R8 runs with obfuscation off, so stack traces keep their class and method names; line numbers of inlined and outlined frames map back with R8's `retrace` and the release's `neutrodyne-{v}-r8-mapping.zip` ([Release assets](#release-assets)). ACRA's own consumer keep rules cover its reflection (01).

```kotlin
// :app
fun installAcra(app: Application, mailTo: String = BuildConfig.ACRA_MAILTO) = app.initAcra {   // mailTo: YtxIsolationTest only
    buildConfigClass = BuildConfig::class.java
    sharedPreferencesName = "acra"                          // ACRA's own enable flag lives here (see Settings)
    reportFormat = StringFormat.KEY_VALUE_LIST
    reportContent = listOf(                                   // explicit allow-list
        ReportField.REPORT_ID, ReportField.APP_VERSION_NAME, ReportField.APP_VERSION_CODE,
        ReportField.ANDROID_VERSION, ReportField.BRAND, ReportField.PHONE_MODEL,
        ReportField.STACK_TRACE, ReportField.CUSTOM_DATA, ReportField.USER_COMMENT,
        ReportField.USER_CRASH_DATE, ReportField.IS_SILENT,
    )                                                         // never LOGCAT, BUILD_CONFIG (may hold a Podcast Index key, PO-3), SHARED_PREFERENCES, DEVICE_ID
    mailSender {
        this.mailTo = mailTo
        reportAsFile = true
        reportFileName = "neutrodyne-crash.txt"
        subject = app.getString(R.string.crash_mail_subject)
        body = app.getString(R.string.crash_mail_body)
    }
    dialog {
        title = app.getString(R.string.crash_dialog_title)
        text = app.getString(R.string.crash_dialog_text)
        commentPrompt = app.getString(R.string.crash_dialog_comment)
    }
}
```

Dialog text (en): "Neutrodyne stopped. You can send a crash report by email: your mail app opens with the report attached, and nothing is sent until you press Send there. The report contains the error, the app version, the Android version and the phone model — never your subscription list or listening history; passwords and access tokens are removed from any web address in an error message." (Accurate because `Redactor.url` keeps scheme, host, port and short path segments and masks user-info, token-like path segments and every query value.)

- **`CrashReportRedactor`** (`:app`): an ACRA `ReportingAdministrator` loaded through `META-INF/services/org.acra.config.ReportingAdministrator`; in `shouldSendReport` it replaces `STACK_TRACE` and each `CUSTOM_DATA` value with `Redactor.text(…)` and drops custom keys outside the allow-list, then returns `true` (whether reports are offered at all is ACRA's own enabled flag, mirrored from `privacy.crash_reports`, see [Settings](#settings)). In ACRA 5.14.2 `ReportExecutor` calls every administrator's `shouldSendReport` before `saveCrashReportFile`, so the stored file — what the dialog and the mail sender use — is the redacted one (read from the 5.14.2 bytecode, 2026-10-05; `CrashReportRedactorTest` pins it: a collected report with a token URL in `STACK_TRACE` is saved without it).
- **Mail app visibility:** 01's merged manifest carries `<queries>` for `SENDTO mailto:` (Unverified need on API 30+); without any mail app ACRA cannot hand off, so the diagnostics screen's "Copy diagnostics" is the fallback.
- **Engine crashes:** a native crash or `os._exit` in `:ytx` kills only that process; `YtDlpClient` ends the calls in flight with `Transient(ENGINE_UNAVAILABLE)` and counts failed starts ([04 Process and lifecycle](04-youtube.md#process-and-lifecycle)). The count, the last engine error code and the engine version appear in diagnostics (`YOUTUBE`) and in `CrashKey.YOUTUBE_HEALTH` of any later main-process report; no ACRA dialog appears for them.

**Desktop and server.** The desktop has no ACRA. `DesktopCrashReporter` (`:desktopApp`, [11 Crash files and the email dialog](11-desktop.md#crash-files-and-the-email-dialog)) writes a crash file with the same allow-listed fields and redaction as the ACRA report — version, OS and architecture, install kind, runtime, the redacted stack trace or `hs_err` summary, the last 200 redacted log lines, the current destination and the lanes' status; no device identifiers — and at the next start asks per crash whether to send it by email (`mailto:` to the same committed mailbox, the body truncated, the file revealed for attaching). It honours `privacy.crash_reports` ([Settings](#settings)): off means the files stay local and no dialog appears ([Open questions](#open-questions) 33). The CPython child's crashes are engine health, never crash reports (11). The sync server reports nothing; it logs ([10 Observability](10-sync.md#observability)).

### `CrashReporter` and `CrashContext`

Modules cannot see ACRA or the desktop's crash files; they use two small interfaces from `:core:common` (`commonMain`), bound in `:app` (`AcraCrashReporter`, ACRA-backed `CrashContext`; no-op bindings when ACRA is disabled) and in `:desktopApp` (`DesktopCrashReporter` and its in-memory `CrashContext`, whose values go into the crash file).

```kotlin
// :core:common
interface CrashReporter {
    val isAvailable: Boolean                                    // false when the mailbox is empty or privacy.crash_reports is off
    fun reportNonFatal(t: Throwable, where: String)             // Android: ACRA handleException; desktop: crash file + email dialog now
}
interface CrashContext {
    fun put(key: CrashKey, value: String)                       // value passes Redactor.text; last write wins
}
enum class CrashKey { SCREEN, DB_RECOVERY, YOUTUBE_HEALTH, PLAYBACK, RUNNING_WORK, SYNC }
```

| `CrashKey` | Written by | Value |
|---|---|---|
| `SCREEN` | the shells' navigation host | top-level destination and key class name (no IDs) |
| `DB_RECOVERY` | 02 `DatabaseOpener` | `RecoveryCause` or empty |
| `YOUTUBE_HEALTH` | 04 `YouTubeHealth`, `YouTubeEngine.status` | breaker state, rate-limit level; with the engine: availability or `ExternalReason`, active yt-dlp version and source (bundled/updated), failed starts (`:ytx` or the desktop child) |
| `PLAYBACK` | 06 (Android), 11 (desktop engine) | player state and branch (`LOCAL`/`REMOTE`/`YOUTUBE`), never a URL |
| `RUNNING_WORK` | `:app`, `:desktopApp` | unique work names currently `RUNNING` (Android) or running lanes (desktop) |
| `SYNC` | 10's `SyncController` | linked or not, last sync result code, pending outbox rows, a held mass-change batch; never the server address, a record or the token |

Callers of `reportNonFatal`: 08's `StartupGate` "Send report" when the database failed to open, and 02's recovered-database message, on both platforms. When `isAvailable` is false, those UIs show "Copy diagnostics" instead.

```mermaid
sequenceDiagram
  participant T as Crashing thread
  participant A as ACRA
  participant X as CrashReportRedactor
  participant D as Crash dialog in acra process
  participant U as User
  participant Mail as Mail app
  T->>A: uncaught exception
  A->>A: collect allow-listed fields and CUSTOM_DATA
  A->>X: shouldSendReport
  X->>X: Redactor.text over stack trace and custom data
  A->>D: show dialog
  U->>D: Send or Do not send
  D->>Mail: SENDTO mailto with neutrodyne-crash.txt
  U->>Mail: review and press Send
```

### Diagnostics API

The diagnostics screen (`DiagnosticsKey`, visuals in [08 Diagnostics](08-ui-ux.md#diagnostics)) reads one repository in both apps; each module contributes its own lines through a Metro multibinding (`@ContributesIntoSet`), so no implementation module depends on another.

```kotlin
// :core:model
data class DiagnosticsReport(val generatedAt: Long, val sections: List<DiagnosticsSection>)
data class DiagnosticsSection(val id: DiagnosticsSectionId, val lines: List<DiagnosticsLine>)
data class DiagnosticsLine(val key: String, val value: String, val severity: DiagnosticsSeverity = DiagnosticsSeverity.INFO)   // English keys, redacted values
enum class DiagnosticsSectionId { APP, REFRESH, BACKGROUND, JOBS, DOWNLOADS, YOUTUBE, SYNC, DESKTOP, DATABASE, NOTIFICATIONS, PARSE_WARNINGS, LOG }
enum class DiagnosticsSeverity { INFO, WARNING, PROBLEM }

// :core:domain
interface DiagnosticsContributor {                    // @ContributesIntoSet from :core:data (update check included), :download:impl, :playback:impl, :playback:desktop, :youtube:ytdlp, :youtube:ytdlp-desktop, :sync:impl, :desktop:system, both shells
    val section: DiagnosticsSectionId
    suspend fun collect(): List<DiagnosticsLine>
}
interface DiagnosticsRepository {                     // implemented by DiagnosticsRepositoryImpl in :core:data
    suspend fun snapshot(): DiagnosticsReport
    fun toPlainText(report: DiagnosticsReport, maxBytes: Int = 64 * 1024): String
    suspend fun exportDatabaseCopy(): Outcome<String /* Android: content URI; desktop: path chosen in the save dialog */, DiagnosticsError>
    fun observeLogLines(): Flow<List<String>>          // RingBufferLogSink (01), already redacted
}
enum class DiagnosticsError { NOT_ENOUGH_SPACE, DATABASE_BUSY, CANCELLED, FAILED }
```

### Diagnostics contents

| Section | Lines | Source (owner) | Platform, API level |
|---|---|---|---|
| `APP` | version name and code; build (`release`, or `debug` from `BuildInfo.debug`; on the desktop the install kind); app locales; SQLite version (`sqlite_version()`); update check: "Check for updates" on or off, last check time and result, available version. Android: Android release and SDK, manufacturer and model, the APK's ABI (`BuildInfo.apkAbi`) and the device's `SUPPORTED_ABIS`, Media3 version, installer package (`getInstallSourceInfo`, informational), first 8 hex digits of the signing certificate SHA-256 (the public key's value on every genuine build, so a different value means a rebuild with another key — the same value proves nothing, [Public key trade-offs](#public-key-trade-offs)). Desktop: OS, version and architecture (emulated or native), runtime vendor and version, AOT cache in use, first frame of this start (11) | `:app`, `:desktopApp`, 09's update check in `:core:data` | both; installer: Android 30+ |
| `REFRESH` | `feeds.last_run_finished_at`, `feeds.last_run_summary`, `feeds.last_run_stop_reason` | 03 | both |
| `BACKGROUND` | Android: standby bucket (`UsageStatsManager.getAppStandbyBucket`), background restricted (`ActivityManager.isBackgroundRestricted`), battery optimisation (`PowerManager.isIgnoringBatteryOptimizations`), Data Saver (`ConnectivityManager.getRestrictBackgroundStatus`), network status (01 `NetworkMonitor`), last 10 process exits with reason (`getHistoricalProcessExitReasons`). Desktop: `LaneStatus` per lane, last wake, network status ([11 Runner diagnostics](11-desktop.md#runner-diagnostics)) | `:app`, `:desktopApp` | bucket and restriction Android 28+; exits 30+ |
| `JOBS` | for each canonical unique work name: state, run attempt count, stop reason, next schedule time | `:core:data` (WorkManager) | Android (Unverified WorkManager accessor names) |
| `DOWNLOADS` | 07's `DownloadDiagnostics` (stop ring, pending job reasons on Android, roots and free space) | 07 | both; pending reasons Android 36+ |
| `YOUTUBE` | feed outage (every build); with the engine: breaker, rate limit, `EngineStatus` (availability or `ExternalReason`, active and bundled yt-dlp versions, source, update policy, last engine-update check and outcome, JS challenges), failed starts; Android: `:ytx` running or stopped; desktop: child state, PID, starts, last kill reason, engine log path (11) | 04, 11 | both |
| `SYNC` | configured or not (the server's address is never shown: diagnostics end up in public issues), linked device name, protocol version, last sync time and result code, pending outbox rows, parked records, a held mass-change batch, live-update (SSE) state ([10 Diagnostics](10-sync.md#diagnostics)) | `:sync:impl` | both (MS2) |
| `DESKTOP` | 11's desktop rows: directories with free space, audio (back-end, device, sample rate, underruns, FFmpeg version and licence string, `ndmedia` version), OS integration (media session, idle-sleep inhibitor, last suspend and resume, tray, notifications, link registration, login item), Java Access Bridge loaded ([11 Diagnostics screen additions](11-desktop.md#diagnostics-screen-additions)) | `:desktopApp`, `:playback:desktop`, `:desktop:system` | desktop |
| `DATABASE` | file size, row counts, last `db-maintenance` step durations, `diagnostics.db_quick_check_failed_at`, last recovery cause | 02 | both |
| `NOTIFICATIONS` | notifications enabled, per-channel importance (blocked channels flagged) | `:app` | Android; channels 26+ (the desktop's notification state is in `DESKTOP`) |
| `PARSE_WARNINGS` | per-feed parse warnings of the last ingest (in-memory LRU of 50) | 03 | both |
| `LOG` | last 500 redacted log lines; on the desktop also the log directory | 01 `RingBufferLogSink`, 11 | both |

Threading and failures: `snapshot()` runs all contributors concurrently on `@Dispatcher(IO)` with a 2 s timeout each; a timeout or exception becomes one line `"<section> unavailable (<ExceptionClass>)"` with `DiagnosticsSeverity.WARNING`; the screen never fails as a whole. `DiagnosticsRepositoryImpl` (`:core:data`, `commonMain`) passes every line value through `Redactor.text` once more before returning (contributors should already have redacted; parse-warning details and stop-reason texts can quote URLs), and never emits feed URLs, server addresses, credentials, tokens or device identifiers.

### Copy, report and export

- **Copy diagnostics:** `toPlainText` (sections in enum order, log last, truncated from the log's oldest lines to 64 KB) to the clipboard.
- **Report a problem:** copies the full `toPlainText(report)` to the clipboard, then opens `{repoUrl}/issues/new?template=bug.yml&diagnostics=<url-encoded summary>` in the browser; issue-form fields are pre-filled by their `id` ([creating an issue from a URL query](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/creating-an-issue#creating-an-issue-from-a-url-query)). The summary is `toPlainText` without the `LOG` and `PARSE_WARNINGS` sections, cut from the end until the **encoded** URL is ≤ 7,000 characters (GitHub answers `414 URI Too Long` beyond its unpublished limit; percent-encoding inflates multi-line text two- to threefold, so a raw byte cap is not enough), followed by the line "(truncated — the full diagnostics are on your clipboard; paste them below)" when cut. `bug.yml`'s `diagnostics` textarea says the same, and its platform dropdown is pre-filled from `BuildInfo`.
- **Export database copy:** `DatabaseCopyExporter` (`:core:data`, common logic with platform targets) runs 02's diagnostics export procedure — `VACUUM INTO`, scrub of credentials (the sync token included) and of every `TEXT` column outside 02's `DiagExportScrub.KEEP` allow-list on a raw driver connection, then `VACUUM` so deleted bytes leave the file ([02 db-maintenance worker](02-data-model.md#db-maintenance-worker); the SQL is 02's and is not repeated here). On Android the target is `cacheDir/export/neutrodyne-diagnostics-<yyyy-MM-dd-HHmm>.db`, because only `cache/export/` is shared by the FileProvider (02 uses the same target), and the file is shared through it and deleted after 1 h or at the next app start. On the desktop the target is `<cache>/export/` and the file is copied to the place the user picks in the save dialog (`FileSaver`, `:core:ui`), then deleted; cancelling the dialog ends with `CANCELLED`. Flow owned here, both platforms: a confirmation first states that subscriptions, titles and listening history are included and that addresses, show notes and descriptions are masked; the export fails with `NOT_ENOUGH_SPACE` when free space < 2 × database size + 50 MB and with `DATABASE_BUSY` when the writer is held longer than 30 s. A database quarantined by 02's recovery is never exported: it may be unreadable, so it cannot be scrubbed; diagnostics show only its `RecoveryCause` and quarantine date.

On Android the diagnostics screen links to `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (fallback `ACTION_APPLICATION_DETAILS_SETTINGS`) and never requests an exemption (N2): Android documents the direct request for apps whose core function would be adversely affected, which a podcast player's is not ([Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)); 01's `checkBannedApis` blocks `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. On the desktop it offers "Open log folder" ([11 Logs and rotation](11-desktop.md#logs-and-rotation)).

"Detailed log for 24 hours" (`diagnostics.verbose_log_until`) lowers the ring buffer's level — and on the desktop the log file's — to `DEBUG` (still redacted) so a user can reproduce a problem and copy the log.

---

## Localisation

Serves N10. Delivered in M0a (Compose resources conventions, `checkTranslations`, per-app language plumbing; spike S11), M0b (the desktop language setting), M10 (pseudo-locale screenshots in both golden sets), M11 (Weblate project per PLAN M11, launch languages). Honours [D83](../PLAN.md#3-key-decisions), [PO-14](../PLAN.md#48-further-product-owner-decisions) default; risk T23.

### Workflow

- **Hosted Weblate, Libre plan** (free for public libre projects), project `neutrodyne`, created at the start of M11 at the latest (PLAN M11 deliverable); opening it at the end of M10, once M10's string changes have landed, is preferred so translators have the whole M11 tester-build period to reach PO-14's 90 % threshold. One translation serves both apps: the strings are shared Compose resources ([D83](../PLAN.md#3-key-decisions)).
- **Components** via Weblate's component-discovery add-on: one component per module with strings, file mask `{module path}/src/commonMain/composeResources/values-*/strings.xml` with the monolingual base `…/composeResources/values/strings.xml` (Compose resources use Android's string-resource XML, so Weblate's Android string format applies, [Weblate Android format](https://docs.weblate.org/en/latest/formats/android.html); Unverified that Compose resources' `values-<qualifier>` names match Weblate's Android language-code mapping for every language, checked in S11's dry run), plus one component for `:app`'s Android-only labels (`app/src/main/res/values-*/strings.xml`: `app_name` and the labels the Android system shows). Strings stay in the module that owns them. Release notes (`changelogs/`), the README, the release body and the server's web UI (English-only in v1.0, N10) are not Weblate components.
- **Flow:** Weblate commits to its own branch and opens a PR (squash add-on); CI runs the normal checks; maintainers merge at least weekly. Developers never edit translated files by hand except to revert a broken string. Before a PR that renames or deletes many string keys, lock the Weblate component and merge Weblate's pending PR first.
- **`checkTranslations`** (09 specifies it; 01's `neutrodyne.quality` registers it, [Open questions](#open-questions) 36): Lint does not see Compose resources (they are packaged as assets, risk T23), so this task does for every translated `strings.xml`, Compose and Android alike, what Lint's fatal checks did: it fails on a key that is absent from the base file (`ExtraTranslation`), on placeholders whose positions or types differ from the base string (`StringFormatMatches`/`StringFormatInvalid`), on a `<plurals>` missing a quantity the locale's CLDR plural rules require (`MissingQuantity`), and on malformed XML or unescaped apostrophes. It never fails on missing translations (partial languages are normal); it prints each locale's completeness, which `update-shipped-locales.sh` cross-checks.
- Weblate maps language codes to Android qualifiers (`pt_BR` → `values-pt-rBR`, `zh_Hant` → `values-b+zh+Hant`); Unverified that Compose resources resolve BCP-47 `b+` qualifiers on both platforms (S11).

### String conventions

- Every user-visible text is a resource: shared UI uses Compose resources (`Res.string.x` with `stringResource` in composition, `getString(Res.string.x)` outside it, `pluralStringResource`/`getPluralString` for counts, [Compose resources](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html)); Android-only labels live in `res/` ([D83](../PLAN.md#3-key-decisions)); ViewModels carry `UiText` (01) holding a `StringResource` and its arguments. Never concatenate fragments; placeholders are positional (`%1$s`) with an XML comment above the string explaining each one; non-translatable parts use `<xliff:g id="…" example="…">` in Android `res/` and are kept out of Compose resources altogether (brand names, commands, hashes, file names and package IDs are Kotlin constants inserted as arguments; Unverified whether Compose resources honour `translatable="false"`).
- Numbers, dates and durations are formatted with the app locale — Android's per-app language (`AppCompatDelegate.getApplicationLocales()[0]`, else `Locale.getDefault()`), the desktop's `desktop.language` (else the OS language) — through 01's `DateFormatter` (`expect`/`actual`: `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)` and `android.icu.text.RelativeDateTimeFormatter` on Android, `java.time` formatters on the desktop, relative times there from Compose resources' plurals) and `NumberFormat`; wire formats always use `Locale.ROOT` (01).
- Icons with direction use auto-mirroring; layouts use start/end, never left/right.
- External-mode and engine texts are ordinary resources of the module that shows them; there are no per-build string sets ([08 Capability differences in UI](08-ui-ux.md#capability-differences-in-ui)). Commands, hashes, file names and package IDs in the install help are constants, not translations (08).
- Hard-coded Compose text is caught by `checkBannedApis`' `Text("…")` literal scan (01) and by the `en-XA` screenshots (unlocalised text stays plain ASCII there).

### Shipped locales and per-app language

- `app/policy/locales.txt` lists the shipped locales of both apps (`en-US` plus every language ≥ 90 % translated across all string components when the minor release is prepared; PO-14). `scripts/l10n/update-shipped-locales.sh` reads Weblate's per-language statistics API and rewrites the file; it runs during the [minor-release checklist](#minor-and-stable-release-additions). Unverified: the statistics endpoint and field names.
- **Android:** `:app` reads the file into `androidResources.localeFilters` (for its own and the libraries' `res/`) and generates `BuildInfo.shippedLocales` for 08's in-app picker (Appearance › Language), which calls `AppCompatDelegate.setApplicationLocales(…)`; Compose resources follow the AppCompat locale without a restart (S11). `generateLocaleConfig = true` with `res/resources.properties` (`unqualifiedResLocale=en-US`) lists the same locales for Android 13+'s system app-language settings ([per-app languages](https://developer.android.com/guide/topics/resources/app-languages)). Verified 2026-10-06 (S11): the generated `res/xml/_generated_res_locale_config.xml` honours `localeFilters` and ignores library translations — `debug` additionally lists `en-XA`/`ar-XB`, which `isPseudoLocalesEnabled` generates as resource qualifiers; `check-apk.sh --published` keeps them out of release.
- **Every shipped locale has `:app` Android resources** (at least `res/values-xx/strings.xml` with `app_name`; S11 finding, 2026-10-07): below API 33 the framework reorders the configuration so a locale the APK's Android resources ship comes first, and AppCompat sets the process default that Compose resources read from it, so a locale with Compose strings but no `:app` resources would stay English on Android 8–12. `checkTranslations` enforces the rule when it lands; until a second locale ships, `PerAppLanguageTest` skips its switch below API 33 with that reason.
- **Desktop:** Settings › Desktop › Language (`desktop.language`, `null` = the OS language) offers "System default" and `BuildInfo.shippedLocales`; a change sets `Locale.setDefault` and re-composes the window with a composition key (S11, [11 Desktop settings](11-desktop.md#desktop-settings)).
- **Compose resources are assets,** so `localeFilters` does not filter them, and a partial translation in the package would be used whenever the system language matches. The convention plugin therefore packages only the `values-*` directories of `locales.txt` (plus the base) into the published APKs and desktop images (Unverified mechanism for Compose resources' packaging tasks, S11 decides; fallback: partial languages ship but are not offered in either picker, and a device or computer whose system language is partial shows that language with English fallbacks — accepted for v1.0, [Open questions](#open-questions) 32). `check-apk.sh` and `check-desktop-image.sh` assert the chosen rule ([Build-output checks](#build-output-checks)).
- Missing strings in shipped languages fall back to English.

### Pseudo-locales and RTL

Pseudo-locales `en-XA` (accented, about 40 % longer) and `ar-XB` (right-to-left) exist only in tests and local `debug` builds:

- **Android `res/`:** `isPseudoLocalesEnabled = true` on `:app`'s `debug` build type ([01 Debug build type](01-foundation.md#debug-build-type)); `release` generates none, and `check-apk.sh --published` asserts that the published APK carries neither ([Build-output checks](#build-output-checks)).
- **Compose resources:** aapt2 never processes them, so a build-logic task `generatePseudoLocales` (09) writes `values-en-rXA/strings.xml` and `values-ar-rXB/strings.xml` from each module's base strings into a generated directory that only test compilations and `debug`/`DEV` runs see (Unverified that Compose resources accept a generated resource directory per compilation; S11 decides; fallback: the directory is added to `commonMain` only when `-PpseudoLocales` is set, which the screenshot test runs and `record-screenshots.yml` pass and no packaging task does). `check-apk.sh --published` and `check-desktop-image.sh` reject `rXA`/`rXB` directories in any published artefact.
- Roborazzi captures `en-XA` and `ar-XB` in the Android and desktop golden sets per [Compose UI and screenshot tests](#compose-ui-and-screenshot-tests); `android:supportsRtl="true"` (01); per release a manual Arabic pass and a 200 % font pass on an Android device, and an RTL and 200 % UI-scale pass on one desktop (08's manual checks).

---

## Performance budgets

Serves N5, R2.9, R7.4 (propagation); mitigates risks T6, T15, T24. Delivered in M0a (per-ABI sizes of the release APKs, S7 and S19), M0b (desktop sizes, first frame and idle RSS from S13), M2 (query timing), M9a (Android engine budgets), M10 (grid jank on `benchmarkRelease`), MD1 (desktop local playback start), MD3 (desktop engine budgets), MS1 (the server on a Raspberry Pi 4, S16), MS3 (propagation), MD5 (desktop budgets re-measured), M11b (start-up with the committed profiles; every budget re-measured). Journeys are defined by [08 Performance journeys](08-ui-ux.md#performance-journeys); seeded data by 02's `SeedDatabase` (300 podcasts, 50,000 episodes, 20 groups). Honours [D2](../PLAN.md#3-key-decisions), [D96](../PLAN.md#3-key-decisions), [PO-28](../PLAN.md#48-further-product-owner-decisions), [PO-42](../PLAN.md#48-further-product-owner-decisions), [PO-43](../PLAN.md#48-further-product-owner-decisions).

**Where N5 is measured.**

- **Android:** every published APK is an optimised, non-debuggable `release` build ([D96](../PLAN.md#3-key-decisions)). Macrobenchmark needs a non-debuggable, profileable target ([Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)), so cold start and jank (PB1–PB5, PB15) are measured and gated on `benchmarkRelease`, which `androidx.baselineprofile` creates from `release` — the same R8 configuration and code, plus `profileable` — and which is never published. APK sizes (PB12, PB13) and the YouTube engine budgets (PB18–PB21) are measured on the published release APKs.
- **Compilation mode:** until M11b no baseline profile exists, and the gated runs use `CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable, warmupIterations = 3)` — warm-up runs, then compilation of the JIT profile they recorded, approximating an install after a few days of use and background dexopt ([CompilationMode.Partial](https://developer.android.com/reference/kotlin/androidx/benchmark/macro/CompilationMode.Partial)). From M11b the committed baseline and startup profiles ship ([Macrobenchmark and profiles](#macrobenchmark-and-profiles)) and the gated runs use `CompilationMode.Partial(BaselineProfileMode.Require)`, which is what ProfileInstaller and background dexopt give a sideloaded install (Unverified how soon after an install or update outside Google Play, 01). Both modes also report `CompilationMode.None()` (the first starts after an install) without a budget.
- **Desktop:** the packaged release images on the reference laptops ([PO-43](../PLAN.md#48-further-product-owner-decisions)) at S13 (M0b) and again in MD5, by 11's procedures ([11 Budgets PB24–PB29](11-desktop.md#budgets-pb24pb29)); CI runners only record trends (shared VMs, software rendering).
- **Server:** S16 on a Raspberry Pi 4 in MS1, re-measured in M11b ([10 Spikes](10-sync.md#spikes)).
- **Sync propagation:** E12 records it nightly as a trend; MS3's manual check on a phone and a laptop gates it.
- Removed 2026-10-05 (scope revision): the report-only measurements of the debuggable published build (PB22, PB23) and the reasoning about ART's JIT-only debuggable mode and CheckJNI — published APKs are release builds.

### Reference devices

| Role | Device | Use |
|---|---|---|
| Reference phone (N5 "PO-agreed mid-range phone", PO-28) | default until the PO names one: Google Pixel 7a on Android 16 or later, **dedicated to testing** — Macrobenchmark, profile generation and release-type runs install `benchmarkRelease`, `nonMinifiedRelease` or `release` with the published application ID and key and uninstall them afterwards ([01 Debug build type](01-foundation.md#debug-build-type)) | PB1–PB5 and PB15 on `benchmarkRelease`, the rest on the release APKs; Macrobenchmark runs before each minor release |
| Android floor | any 2–3 GB RAM device on API 26–28 | functional smoke and scroll feel of the release APK only, no numeric budgets; `:ytx` start on a low-memory device (informational) |
| 32-bit | any `armeabi-v7a`-only device | the `armeabi-v7a` APK: external mode with its reason (PLAN M9 AC8), functional only |
| Reference laptops (PO-43) | one 2022-class mid-range laptop per OS: Windows 11 x64 (8 GB RAM, Intel Core i5 12th gen or AMD Ryzen 5 6000 class), MacBook Air M1 or M2 (8 GB) on macOS 15 or later, an Ubuntu 24.04 x64 laptop of the same class | PB24–PB29 (11) |
| Desktop floor (informational) | a macOS 13 Mac and a Windows 10 22H2 PC once in S13 ([11 macOS floor](11-desktop.md#macos-floor)); a Windows 11 on Arm laptop (x64 emulated) and a Linux arm64 machine if available | start-up and playback recorded, no budgets |
| Server reference (PO-43) | Raspberry Pi 4 (4 GB, 64-bit Raspberry Pi OS) | PB30 (S16) |
| CI emulators and runners | GMD `api36`, `bench34`; the four desktop runners | trends and dry runs only; never gate on emulator or runner timings (shared VMs are noisy) |

### Budgets

| ID | Metric | Budget | Measured by | Gate |
|---|---|---|---|---|
| PB1 | Cold start to Feeds, time to initial display, p50 | < 600 ms (N5) | `ColdStartToFeeds` on `benchmarkRelease`: `StartupTimingMetric`, `StartupMode.COLD`, 15 iterations, `CompilationMode.Partial(BaselineProfileMode.Require)` from M11b (before: `Partial(Disable, warmupIterations = 3)`) | v1.0 (M11b, PLAN M11 AC1) |
| PB2 | Same, p90 | < 900 ms | same | soft (investigate) |
| PB3 | Cover-grid fling jank | < 1 % of frames late, measured as `frameOverrunMs` P99 ≤ 0 ms (Macrobenchmark reports percentiles, not shares; P99 ≤ 0 means at most 1 % of frames overran) | `CoverGridFling` on `benchmarkRelease`: `FrameTimingMetric`, 5 iterations × 3 flings over 300 tiles | M10 AC7, v1.0 (M11b) |
| PB4 | All-feed fling and group-pager swipe jank | `frameOverrunMs` P99 ≤ 0 ms | `AllFeedFling`, `GroupPagerSwipe` on `benchmarkRelease` | v1.0 (M11b) |
| PB5 | Player expand/collapse jank | `frameOverrunMs` P99 ≤ 0 ms | `PlayerExpandCollapse` on `benchmarkRelease` | soft |
| PB6 | Group feed first page (count + 80 rows) | ≤ 60 ms | 02's `FeedQueryTimingTest`, median of 20 | M2 AC2 on the reference device; CI records on GMD |
| PB7 | All feed first page | ≤ 100 ms | same | same |
| PB8 | Subsequent page load | ≤ 20 ms | same | same |
| PB9 | Podcast screen open to content | < 300 ms | manual trace on the reference device (M1 AC5); from M11 `PodcastOpen` journey with a trace section on `benchmarkRelease` | M1 manual, v1.0 soft |
| PB10 | 300-feed OPML → 300 pending tiles | ≤ 2 s (R1.3) | 05's import test on device | M3 |
| PB11 | Parse the 831-item 3.5 MB feed | < 1 s on the JVM | 03's corpus test | every PR |
| PB12 | `arm64-v8a` and `x86_64` release APKs, each (with the YouTube engine) | < 40 MB (N5; Unverified estimate until S7 and S19 measure it in M0a) | `check-apk.sh --published` | every PR, every release |
| PB13 | `armeabi-v7a` release APK (no engine runtime; counts any unusable Python assets ABI splits leave in it, ≈ 12–13 MB Unverified, [01 S7](01-foundation.md#s7-chaquopy-under-agp-941)) | < 30 MB (N5; Unverified estimate until S7 and S19) | `check-apk.sh --published` | every PR, every release |
| PB14 | Database at the N5 scale | ≤ 100 MB | 02's size measurement | M11 |
| PB15 | Main-process PSS peak during `CoverGridFling` + `PlayerExpandCollapse` (`:ytx` excluded, PB20) | ≤ 250 MB (starting value, Unverified) | `MemoryUsageMetric(Mode.Max)` on `benchmarkRelease` (experimental Macrobenchmark metric; fallback `dumpsys meminfo ch.lkmc.neutrodyne` in `teardownBlock`) | soft |
| PB16 | 300-feed refresh with all feeds answering 304 | ≤ 3 min on Wi-Fi | 03's M11 performance check | soft |
| PB17 | Splash hold | ≤ 400 ms | 01's start-up rule | M0a |
| PB18 | Cold YouTube resolve (`:ytx` not running), p50 over 20 videos | ≤ 3 s (N5) | [04 Spike results](04-youtube.md#spike-results) procedure on the reference device with the release `arm64-v8a` APK: time from the `YtDlpClient` call to its result, `:ytx` killed before each video | M9 AC4 (M9a spike); soft at v1.0 |
| PB19 | Warm YouTube resolve (`:ytx` running), p50 | ≤ 1.5 s (N5) | same, `:ytx` kept alive | M9 AC4; soft at v1.0 |
| PB20 | `:ytx` PSS while alive, idle and peak during a resolve | ≤ 90 MB (N5) | `dumpsys meminfo ch.lkmc.neutrodyne:ytx` during the spike's runs (release `arm64-v8a` APK) | M9 AC4; soft at v1.0 |
| PB21 | `:ytx` gone after the last call | ≤ 3 min (N5) | `YtDlpClientTest` with `TestClock` (every PR); on the device `adb shell pidof ch.lkmc.neutrodyne:ytx` returns nothing 3 min + 10 s after the last call | every PR (logic), M9 AC4 (device) |
| PB22 | Removed 2026-10-05 (release builds): the report-only cold start of the debuggable published build | — | — | — |
| PB23 | Removed 2026-10-05 (release builds): the report-only grid jank of the debuggable published build | — | — | — |
| PB24 | Desktop first frame: JVM start to the end of the first frame, median of 5 warm starts and the first start after a reboot, 300-podcast library | ≤ 1.0 s with the AOT cache, ≤ 2.5 s without (N5; Unverified estimates) | 11's procedure on the packaged release image on each reference laptop | measured in S13 (M0b, PLAN M0 AC14); MD5 AC3; v1.0 (M11 AC1) |
| PB25 | Desktop idle RSS, 30 s after the first frame on Library (the engine child excluded) | ≤ 350 MB | 11 (Windows working set, macOS `ps` RSS, Linux `VmRSS`) | same |
| PB26 | Desktop RSS after 10 min of streaming at 1.5× with skip silence | ≤ 450 MB | 11 | MD5 AC3; v1.0 |
| PB27 | Desktop installed size / download size per target | ≤ 300 MB / ≤ 130 MB | `check-desktop-image.sh` prints both in every desktop job (nightly trend); 11's [Sizes](11-desktop.md#sizes) | recorded from M0b (S13); blocking in `release.yml` from MD5 |
| PB28 | Desktop local playback start: `playEpisode` of a downloaded MP3 to the first advance of `framesPlayed`, median of 10 warm starts | ≤ 300 ms | 11 | MD1 (measured), MD5, v1.0 |
| PB29 | Desktop YouTube engine: cold resolve p50, warm resolve p50, child RSS, child gone after the last call | ≤ 3 s, ≤ 1.5 s, ≤ 120 MB, ≤ 3 min | 11 (`YtDlpClient` timings with the child stopped and running, child RSS by PID); the idle stop also by `YtxProcessTest` nightly | MD3 AC6; soft at v1.0 |
| PB30 | Sync server on a Raspberry Pi 4 with the documented JVM flags: idle RSS 10 min after start; a 50,000-record initial upload | ≤ 160 MB; ≤ 60 s | S16 ([10 Spikes](10-sync.md#spikes)) with `SyncLoadTool` | MS1 AC6; v1.0 (M11 AC1) |
| PB31 | Sync propagation with both apps in the foreground or playing: from the local write to the change shown on the other device | ≤ 10 s (R7.4) | MS3's manual check (a phone and a laptop on one network, a played mark, a group rename and an Up next reorder); E12 records it nightly as a trend | MS3 AC1; v1.0 |

Budget changes are PO decisions (N5) and are recorded in this table. PB12 and PB13 are Unverified estimates for R8-minified release APKs; S7 and S19 measure them in M0a, and a miss goes to the PO (PLAN M0 AC1). The engine budgets PB18–PB20 start as estimates (≈ 15–22 MB of engine per 64-bit APK, ≈ 70 MB in `:ytx`, 1–3 s first resolve, [04 Host and packaging](04-youtube.md#host-and-packaging)); a miss in the M9a spike leads to 04's fallbacks or a PO amendment. The desktop budgets PB24–PB29 are Unverified estimates from the research build under software rendering (first frame 0.58–0.63 s with the AOT cache and 1.70–2.09 s without, idle RSS ≈ 199 MB with the cache, [11 AOT cache](11-desktop.md#aot-cache)); S13 sets them on real hardware (PLAN M0 AC14) and MD5 confirms them, or the PO amends them. PB30 starts from the server prototype (92 MB idle RSS on a laptop, 50,000 changes pushed in about 2 s, [D94](../PLAN.md#3-key-decisions)). PB1 must stay unchanged with the engine (Python never starts in the main process, [D73](../PLAN.md#3-key-decisions)) and with sync not configured (nothing of `:sync:impl` is constructed before the first frame, R7.1).

### Macrobenchmark and profiles

- `:benchmark` (`com.android.test` with the `androidx.baselineprofile` 1.5.0 producer plugin; created in M6b for the system tests, Macrobenchmarks from M10, the profile generator from M11b) has `targetProjectPath = ":app"`, self-instrumentation (`experimentalProperties["android.experimental.self-instrumenting"] = true`) and `benchmark-macro-junit4` 1.5.0. Its variants mirror `:app`'s build types ([Out-of-process system tests](#out-of-process-system-tests)): `benchmarkRelease` measures `:app`'s `benchmarkRelease` (PB1–PB5, PB9, PB15), `nonMinifiedRelease` generates profiles against `:app`'s `nonMinifiedRelease`, `release` runs the system tests and release smoke journeys. Unverified: the plugin's variant matching under AGP 9.4 with KMP library modules in the graph (S19 decides, M10 confirms; 01's module table points here). Managed device `bench34` (`aosp`, API 34) for `benchmark-dryrun` and profile generation; the reference device, connected, for measurements.
- **Seeding:** `BenchmarkSeedReceiver` lives in `app/src/benchmarkRelease/`, the `benchmarkRelease` build type's own source set, never in `release` ([01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)). It is a receiver without intent filters, `exported="true"` and protected by `android:permission="android.permission.DUMP"` (held by the shell, not by apps), which fills the database with `SeedDatabase` (`:core:testing` on the `benchmarkRelease` classpath). The benchmark's `setupBlock` sends `am broadcast -n ch.lkmc.neutrodyne/.benchmark.BenchmarkSeedReceiver` once and waits for its marker file. The release APK never contains it (`check-apk.sh --published`).
- **Baseline and startup profiles** (M11b, [D96](../PLAN.md#3-key-decisions)): `BaselineProfileGenerator` in `:benchmark` runs `BaselineProfileRule` journeys owned here against `nonMinifiedRelease` on `bench34` — `ColdStartToFeeds` with `includeInStartupProfile = true` (so R8 lays out the startup DEX), then `CoverGridFling`, `PodcastOpen` and `PlayerExpandCollapse` — and writes `app/src/release/generated/baselineProfiles/` through the consumer's `saveInSrc` ([01 Release build and baseline profiles](01-foundation.md#release-build-and-baseline-profiles), [Baseline Profiles overview](https://developer.android.com/topic/performance/baselineprofiles/overview)). A maintainer regenerates them with `./gradlew :app:generateBaselineProfile` when start-up code changes and before each MINOR release, and commits them through a normal PR; `release.yml` never generates them. The M11b release issue records PB1 with `BaselineProfileMode.Require` against `Disable`, so the profiles' effect on a sideloaded install is known.
- **Full display:** 08's Feeds route calls `ReportDrawnWhen { first page loaded }` ([08 Performance journeys](08-ui-ux.md#performance-journeys)), so `StartupTimingMetric` also reports time to full display; not budgeted in v1.
- Results (`*-benchmarkData.json`) from the reference device are attached to the release checklist issue; a regression > 10 % against the previous minor release on PB1–PB4 blocks the release until explained.
- **R8** (`release` and `benchmarkRelease`): full mode via `optimization { enable = true }` and `-dontobfuscate` (01); keep rules reviewed with R8's `-printconfiguration` and `-printusage` (S19). Size tips when PB12 or PB13 is at risk: legacy native packaging (compressed `.so` files, ≈ 6 MB less per 64-bit APK, decided by S7, [01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)); Chaquopy's foreign-ABI assets left in each split (S7 measures them; > 5 MB per APK triggers [D2](../PLAN.md#3-key-decisions)'s ABI-flavor fallback) and its ABI-independent Python assets in the `armeabi-v7a` APK (01 open question 13); `packaging` excludes such as `/DebugProbesKt.bin`.
- **Desktop and server:** no Macrobenchmark equivalent; PB24–PB29 follow 11's procedures (the app logs its own first-frame time) and PB30 follows S16. Desktop JARs are not minified (no ProGuard, [D89](../PLAN.md#3-key-decisions)); the AOT cache is the desktop's start-up lever ([PO-42](../PLAN.md#48-further-product-owner-decisions)).

---

## Release checklist

Serves N1–N13. Copied into `.github/ISSUE_TEMPLATE/release.md`; one issue per release. Items marked with a milestone apply from that milestone on.

### Every release

Before tagging:

- [ ] `scripts/release.sh … --dry-run` prints the expected `versionName`/`versionCode` (no suffix, S = 95).
- [ ] `main` green: `ci.yml` and the last `nightly.yml`, including `no-engine-build` (M9a), `desktop-matrix` (M0b), `sync-convergence` (MS0) and `server-image-smoke` (MS1); no open `release-blocker` issue.
- [ ] `changelogs/<versionCode>.txt` for the new `versionCode` (name it from `release.sh … --dry-run`) merged to `main` through a PR.
- [ ] Weblate PR merged (or explicitly deferred).
- [ ] Any schema change since the last tag has its migration test; the frozen-schema check passes; a synced-column change has its capture-trigger update (MS0).
- [ ] `release-assets.json` lists the assets this release must carry (a milestone that adds a product extends it).
- [ ] A bundled component moved since the last tag (Temurin, python-build-standalone, FFmpeg, miniaudio, the server's base image): its lockfile, source bundle and Licences entries moved with it.

Tag and publish:

- [ ] `scripts/release.sh …`; approve the `release` environment (only `publish` waits for it); `release.yml` green in < 60 min.
- [ ] The GitHub release is immutable, a normal release (not pre-release), "latest" exactly when its `versionCode` is the highest, and carries the expected asset set — the three APKs and the R8 mapping; from M0b the desktop packages of the four targets and the runtime sources; from MD1b the FFmpeg sources; from MS1 the server JAR and the image sources; `neutrodyne-update.json` and `SHA256SUMS`; `publish`'s verification passed; one APK, one desktop package per OS and the server JAR spot-checked locally with `sha256sum` (or `Get-FileHash`), `gh release verify-asset` and `gh attestation verify`, and the image with `gh attestation verify oci://ghcr.io/{owner}/neutrodyne-server@{digest}`.
- [ ] (MS1) `image-tags` green: `{v}`, `{X.Y}` and, when latest, `latest` resolve to the attested digest named in the release body; the first image release made the GHCR package public once.
- [ ] A test device with the previous release finds the new one through the update check (from M11a: Settings › Updates › Check now) and installs it from "Download APK for this device" with Android's installer, keeping its data; another updates through Obtainium with the per-ABI filter. (M11a) On one desktop per OS, "Check now" links the package of its install kind, and installing it over the previous version keeps the data.
- [ ] (MS1) A test server on the previous release shows the update notice on its admin page and upgrades by image tag, migrating after its automatic backup.
- [ ] The last nightly `repro` result is noted (report-only).

### Minor and stable release additions

- [ ] Macrobenchmarks PB1–PB5 on `benchmarkRelease` on the reference phone (a dedicated test device: the runs install and remove the published application ID, [01 Debug build type](01-foundation.md#debug-build-type)); baseline and startup profiles regenerated when start-up code changed (M11b); results attached; no unexplained regression > 10 % on PB1–PB4; per-ABI sizes (PB12, PB13) from `check-apk.sh --published` attached.
- [ ] (M0b) Desktop sizes (PB27) from the release jobs attached; PB24–PB28 re-measured on the reference laptops when the release changes start-up, rendering, the runtime or the engine (otherwise the CI trend is noted); (MD3) PB29 when the engine host changed; (MS1) PB30 on the Raspberry Pi 4 when the server changed.
- [ ] `update-shipped-locales.sh` run; `locales.txt` committed.
- [ ] Manual device matrix of [06](06-playback.md#testing) and checklist of [07](07-downloads.md#instrumented-and-device-tests) re-run on the reference phone (release `arm64-v8a` APK); external mode checked once on the `armeabi-v7a` APK.
- [ ] (MD2) 11's OS-integration checklists on each OS ([11 OS-integration manual checklists](11-desktop.md#os-integration-manual-checklists)); (MD5) the install and upgrade walkthroughs per OS match the README step by step (macOS "Open Anyway", SmartScreen, Smart App Control, Linux packages; PLAN MD5 AC5).
- [ ] 08's manual checks: TalkBack, Switch Access, 200 % font, Arabic RTL, keyboard-only, foldable postures, grid → podcast transition review, airplane mode with downloads; (MD4) VoiceOver on macOS and NVDA on Windows ([11 MD4 manual checklist](11-desktop.md#md4-manual-checklist)).
- [ ] `bmgr` check on a device (05/07).
- [ ] `PRIVACY.md` matches the network inventory (parity test green) and any new destination; when the release adds a destination or changes networking code, `network-capture.sh` re-run for the affected platforms and its host list attached.
- [ ] The README sections "Install and update", "Install on Windows, macOS or Linux" and "Run the server", 08's Install & updates help page and [10 Deployment](10-sync.md#deployment) agree with [Developer verification](#developer-verification), [11 Install and update](11-desktop.md#install-and-update) and [README "Run the server"](#readme-run-the-server), including the README's "About these builds" item and the help page's BUILDS card ([Public key trade-offs](#public-key-trade-offs)); the latest `verification-watch` issue is closed; the release body template is current.
- [ ] The engine canary is green for both hosts and the bundled yt-dlp is the latest approved version, or the difference is explained (04).
- [ ] (MS2) When `:sync:protocol` changed: this release's apps sync with the previous release's server and the previous release's apps with this server, within the protocol window ([10 Versioning](10-sync.md#versioning)), checked by hand and recorded.

### Hotfix (YouTube fast lane)

Engine path first ([04 Hotfix runbook](04-youtube.md#hotfix-runbook)): when the fix is in a yt-dlp **stable** release and the shim needs no change, no app release ships — [engine-canary.yml](#engine-canaryyml) approves the release within 6 h (path 1), or, after re-recorded fixtures, a maintainer dispatches it with `tag` (path 2); both apps activate it within 24 h, sooner after a breaker opening (N11). This reaches Android users whose APK updates Android blocks ([Developer verification](#developer-verification)) and desktop users who have not installed the latest package.

Release path second (path 3: a shim change, a new `SHIM_API_VERSION`, a new pinned key, or the bundled version must move): shim fix PR or `scripts/engine/bump-ytdlp.sh <approved version>` → CI (`verifyBundledYtDlp`, both `checkPythonLicences`, `shimTest`, `shimTestStdio`, recorded-response tests) → merge → dispatch `nightly.yml` with `scope: youtube-smoke` on `main` (E0 and E7 through `:ytx` on the debug and release APKs, the stdio host's tests on Linux x64) → `release.sh patch --hotfix` → `release.yml`, which publishes a normal release with every product's assets (APKs, desktop packages for all four targets, sources, server JAR and image). Skipped for hotfixes: benchmarks, locales, manual matrices. N11's target is < 60 min from **tag** to the published release with every asset; the whole path is about 1.5 h (PR CI ≈ 20 min, `youtube-smoke` ≈ 15 min, release ≤ 60 min; timeline in 04's runbook). After that the update checks announce the release and users install it from GitHub, or Obtainium delivers it.

### Milestone tester build

Per PLAN DoD: the milestone's acceptance criteria are listed in the release issue with the test or manual check that proves each one; tag `0.{n+1}.P` (P = 0 for the milestone's first build; an increment, MD milestone or MS milestone that lands out of order ships as the next PATCH of the current line, [D63](../PLAN.md#3-key-decisions)); an immutable, **normal** release (never pre-release) with every product built so far — the three APKs and the R8 mapping from M0a, the desktop packages and runtime sources from M0b, the FFmpeg sources from MD1b, the server JAR, image and image sources from MS1 — plus `SHA256SUMS`, `neutrodyne-update.json` and attestations, which the update checks of every installed copy announce ([Tester builds](#tester-builds)); MD0, a spike, publishes none; design documents updated for deviations.

### v1.0 gate

| [PLAN M11](../PLAN.md#m11-release-hardening-and-v10) acceptance | Evidence |
|---|---|
| 1 Cold start p50 < 600 ms on `benchmarkRelease`; the release APKs within the per-ABI budgets; the desktop budgets PB24–PB29 on the reference laptops (MD5) and the server budget PB30 on the Raspberry Pi 4 (MS1), or amended by the PO | PB1 Macrobenchmark on the reference phone with the committed profiles; PB12/PB13 from `check-apk.sh --published` on the `v1.0.0` APKs; 11's measurements in the MD5 release issue and on the `1.0.0` packages; S16's record (MS1) and its M11b re-run |
| 2 Tagging `v1.0.0` produces, in < 60 min, one immutable, normal release that becomes "latest", with the three APKs, every desktop asset of D89 including the first DMG, the server JAR, the source bundles, `SHA256SUMS`, `neutrodyne-update.json` and notes; `ghcr.io/{owner}/neutrodyne-server:1.0.0` and `latest` follow; `gh release verify` and `gh attestation verify` pass for every asset and the image digest; the update checks of the previous release candidate announce it on Android and each desktop OS with working links; Obtainium installs it | `release.yml` timing and `publish`'s verification on a `0.12.P` release candidate and on `v1.0.0`; `image-tags`; device and desktop checks with the candidate's update check, Obtainium's per-ABI filter and a macOS tester moving from the `0.12.P` ZIP to the `1.0.0` DMG |
| 3 (M11a) A newer release produces one notification per version and the update card with notes, size, SHA-256 and the two links; "Download APK for this device" links the `Build.SUPPORTED_ABIS[0]` asset, "Download for this computer" the asset of the install kind (Windows on Arm → x64); links outside `{repoUrl}/releases/` rejected (dot-segments included, [Update manifest](#update-manifest)); the manual install keeps all data on API 26 and API 37 | `AppUpdateCheckerTest`, `GitHubUpdateSourceTest`, `UpdateManifestParserTest`, `DesktopAssetSelectorTest`, `DesktopUpdateCheckLaneTest`; [device checklist](#device-checklist-m11a) |
| 4 Removed 2026-10-05 (no in-app install, PO-31; the idle gate is gone) | — |
| 5 Removed 2026-10-05 (no in-app install, PO-31: a developer-verification block happens in Android's installer, which the help page explains) | — |
| 6 (M11a) With "Check for updates" off no update work (Android) or lane run (desktop) is scheduled and nothing is sent until "Check now"; never `api.github.com`; no install permission in the merged manifest; the first-run card and the verification notice once each | `AppUpdateCheckerTest`, `UpdateNoticesTest`, `GitHubUpdateSourceTest`, `DesktopUpdateCheckLaneTest`; `verifyManifestPermissions` against `permissions.txt`; device checklist |
| 7 The instrumented suite passes on the debug build on API 26 and API 36 and on an API 37 16 KB-page image; the release APKs pass `:benchmark`'s smoke journeys and the YouTube smoke test of M9 AC9 on the API 36 GMD and the API 37 16 KB image | `instrumented-full` (debug leg on `api26` and `api36`; release leg), `release-build-smoke` (E0 and E7 on the release build) and `api37-16k` (the debug suite, the release smoke, `PAGE_SIZE` 16384, `zipalign -P 16` and the ELF check of the CPython libraries and extension modules on the release `x86_64` APK); all green on the release commit or its parent per `verify-tag.sh` |
| 8 Network captures of fresh-install sessions on Android and each desktop OS map every host to the inventory; the server's capture shows no outbound request except the update check | `scripts/ci/network-capture.sh`, run by a maintainer on a workstation (it needs the real internet, so it is never a CI job) against a `0.12.P` release candidate: `--android` with the `x86_64` APK on an emulator (`system-images;android-36;default;x86_64`, no Google apps) started with `-tcpdump`, a UI Automator session (subscribe 2 real feeds, refresh, stream 30 s, download one episode, search "news", add and play one YouTube channel, link to a test sync server, Settings › Updates › Check now, Settings › YouTube › Check for engine update), `tshark` DNS names and TLS SNI; `--desktop` on each reference laptop with the same session through the logging proxy plus `tshark` for the engine child; `--server` on the reference compose deployment over 24 h. Every host maps to an inventory ID (`sync-server`, `app-updates`, `server-updates` and `youtube-engine` included; never `api.github.com`); OS hosts (connectivity checks, NTP, OS updates) are listed separately. Unverified: emulator `-tcpdump` on API 36 images |
| 9 Migration from the first tester schema on Android and the desktop; a device or computer upgraded from the previous release by installing the downloaded APK or desktop package over it keeps all data; the server migrates after an automatic backup and refuses a newer schema | 02's `MigrateAllTest` (desktop JVM and device suite); manual upgrades on the reference phone through Settings › Updates › "Download APK for this device" and on each reference laptop through "Download for this computer", comparing library, groups, history, Up next and downloads; 10's migration tests and an upgrade of the reference server |
| 10 PO-10, PO-14 and PO-36 resolved; PO-37–PO-45 resolved or their defaults accepted | PLAN §4 updated (PO-1, PO-2, PO-5, PO-8 and PO-31–PO-35 resolved on 2026-10-05) |
| 11 Database ≤ 100 MB at the N5 scale; retention deletes exactly the unprotected absent episodes on both platforms, without any sync push | PB14 (02's size measurement on `SeedDatabase`); 02's `RetentionTest` on the desktop JVM and the device suite, with a linked fixture |
| 12 Removed 2026-10-05 (no release key and no rotation runbook, PO-35) | — |
| 13 The README's install sections, the Install & updates help and every release body state: the APKs are release builds signed with a public key (download only from the GitHub release page; the signature proves nothing); the desktop builds are unsigned (macOS "Open Anyway" after the install and after every update, SmartScreen "Run anyway", Smart App Control must be off) and bundle an unmodified OpenJDK runtime and an LGPL FFmpeg whose sources are attached; how to check a download; no debug tooling in the `v1.0.0` APKs | review of the README sections, 08's help (BUILDS card and desktop sections), 11's install text and the [release body](#release-body) template against [Public key trade-offs](#public-key-trade-offs); `check-apk.sh --published` on the `v1.0.0` APKs |
| 14 Cross-device gate: a subscription and a group added on Android appear on each desktop; an episode paused on a desktop continues on Android from the same position through "Continue on this device"; unsubscribing 11 of 50 podcasts on one device is held on the others; offline edits on two devices converge; the server's nightly backup restores | E12 `CrossDeviceSyncTest` green on the release commit; the manual checklist with an Android phone, a desktop on each OS and a server from the release candidate ([10 Cross-device journey](10-sync.md#cross-device-journey)); a `restore` of the nightly backup on a scratch server |

Plus: every earlier milestone's acceptance criteria green (M, MD and MS tracks); the README's install sections and "Run the server" final and identical in substance to 08's help page, 11's install text and 10's deployment guidance; `PRIVACY.md` final; the Licences screens of both apps and `THIRD_PARTY_NOTICES.md` list every bundled component — Android: CPython and its libraries, Chaquopy, yt-dlp, yt-dlp-ejs, the CA bundle ([04 Notices](04-youtube.md#notices)); desktop: the OpenJDK runtime with its `legal/` notices, FFmpeg, miniaudio, the python-build-standalone stack, Skiko and every Gradle dependency (PLAN MD5 AC2); server: the Licensee report in the image's `THIRD_PARTY_NOTICES.md`; `check-runtime-sources.sh` and `check-server-image.sh` passed on `v1.0.0`; the ACRA mailbox configured, a test report received from a release build and a crash email received from a packaged desktop build.

---

## Settings

Keys owned here ([01 DataStore files and typed setting keys](01-foundation.md#datastore-files-and-typed-setting-keys)), the same keys in both apps; none of them syncs (`SettingKey.synced = false`, [10 What syncs](10-sync.md#what-syncs)). UI on 08's `PRIVACY`, `UPDATES` and `ABOUT` pages ([08 Updates settings](08-ui-ux.md#updates-settings)).

| Key | Type | Default | File | UI location | Milestone |
|---|---|---|---|---|---|
| `privacy.crash_reports` | Bool | true | `settings` | Settings › Privacy › "Offer to send crash reports" (dialog per crash; off = ACRA reports nothing on Android, and the desktop keeps its crash files local without asking) | M0a (UI M11b) |
| `diagnostics.verbose_log_until` | Long? (epoch ms) | null | `device_settings` | Settings › About › Diagnostics › "Detailed log for 24 hours" | M11b |
| `updates.check_enabled` | Bool | true ([PO-31](../PLAN.md#48-further-product-owner-decisions)) | `settings` | Settings › Updates › "Check for updates"; the first-run card's "Turn off" | M11a |
| `updates.last_check_at` | Long? (epoch ms) | null | `device_settings` | Settings › Updates "Last checked" line | M11a |
| `updates.skipped_version_code` | Long? | null | `device_settings` | none ("Skip this version") | M11a |
| `updates.notified_version_code` | Long? | null | `device_settings` | none (at most one notification per version) | M11a |
| `updates.first_run_choice_done` | Bool | false | `device_settings` | none (first-run card) | M11a |
| `updates.verification_notice_shown_at` | Long? (epoch ms) | null | `device_settings` | none (verification notice; Android only) | M11a |

On Android, `privacy.crash_reports` is mirrored into ACRA's own SharedPreferences file `acra` (`sharedPreferencesName` above), key `acra.enable` (`ACRA.PREF_ENABLE_ACRA`), by an `AppInitializer` (order 20, platform band) and on every change of the setting. ACRA reads that key when it initialises in `attachBaseContext` — long before DataStore can be read — and its `ErrorReporterImpl` listens for changes to it, so the switch takes effect immediately and survives process restarts (`ACRA.errorReporter.setEnabled(…)` alone would last only until the process dies). The `acra` file is outside the Auto Backup include list ([D34](../PLAN.md#3-key-decisions)), so a restored device starts enabled until the initializer mirrors the restored setting. `diagnostics.db_quick_check_failed_at` is 02's key.

`updates.check_enabled` is portable (05's backup whitelist) and not synced; the other `updates.*` keys are device-bound state and never backed up or synced, so a restored phone shows the verification notice again where it applies, and a restored device or computer the first-run card unless the restored switch is already off. A restored `true` never overrides `Disabled(DEV_BUILD)` ([Update check](#modules-and-api)). Removed before any build had them (PO-31, PO-33): `updates.mode`, `updates.channel` and `updates.whats_new_version_code`.

Settings › Privacy page content (09, both apps): crash-reports switch; "What Neutrodyne connects to" (the [inventory](#network-inventory) with each row's current state, e.g. Podcast Index "off — no key" or "on" (user key, or a project key under PO-3 option A), app update check "On" or "Off", YouTube-engine updates "Neutrodyne-approved", sync server "Not set up" or "Linked" with a link to Settings › Sync); links to the Discover providers (03), show-notes images (03), Settings › Updates and Settings › YouTube; "Privacy policy" (opens `PRIVACY.md`).

---

## Delivery by milestone

| Milestone | Delivered in this area |
|---|---|
| [M0](../PLAN.md#m0-scaffold-and-ci) (M0a) | `configureNeutrodyneTestTasks` in every test-bearing convention plugin; `neutrodyne.android.testing` content (including `NeutrodyneTestRunner` and the `neutrodyne.instrumentedTest` property); `:core:testing` as a KMP module (`TestClock`, `MainDispatcherTest`, `MainDispatcherRule`, `Goldens`, `FakeNetworkMonitor`, `FakeSettingsRepository`, `FakeCrashReporter`, `FakeCrashContext`, `Nightly`, `ReleaseSmoke`); `commonTest` on the desktop JVM in every KMP module (PLAN M0 AC10); Robolectric `sdk=36`, Roborazzi wiring with one Settings screenshot in the Android set; GMD `api26`/`api36` (API 26 GMD check, emulator-runner fallback if needed; split-install check), `disableEmptyDeviceTests`, the `:core:database` device-test opt-in; E0 (version and ABI; `selftest` in `:ytx` when S7 is go) on the debug build and, in `release-build-smoke`, on the release build (PLAN M0 AC4); `keepalive.yml`; `ci.yml` (`static` with `licenseeRelease`, `verifyDependencyPolicy`'s new bans, `checkBannedApis`' source-set rules, `checkTranslations`, `checkPythonLicences` and `verifyBundledYtDlp`; `unit` with `allTests`; `assemble` with `assembleRelease assembleDebug` and `check-apk.sh --published`; `instrumented`; Temurin 21 and 25, `actions/setup-python`); `nightly.yml` (`instrumented-full` debug leg, `api37-16k`, `release-build-smoke`, `repro` report-only); `release.yml` (`verify-tag`, the `android` job in the pinned container with `assembleRelease`, the three release APKs checked against `public-cert-sha256.sh`, the R8 mapping zip; `publish` with `make-update-json.sh`/`check-update-json.sh`, `SHA256SUMS`, `actions/attest`, draft → publish as an immutable, normal release, `gh release verify`); `release-assets.json`; repository settings (immutable releases, rulesets, environment `release` without signing secrets); `changelogs/`; the committed keystore `signing/neutrodyne-public.keystore`, `signing/README.md` and `public-cert-sha256.sh` ([Committed keystore](#committed-keystore)); first release `v0.1.0` (10095, PLAN M0 AC7) and the M0 AC8/AC9 checks (same certificate on CI and a developer machine with install-over; the `debug` build `ch.lkmc.neutrodyne.debug` beside the release build, no debug code in published APKs); README "Install and update" first draft with the release-build and public-key statement; Renovate with the Chaquopy rule; `.editorconfig`, Lint, Spotless, detekt; PR and issue templates; `installAcra` (never in `:ytx`; on in release builds, off in debug builds) + `CrashReportRedactor` (disabled until PO-10), `CrashReporter`/`CrashContext`; `PRIVACY.md` v0, `SECURITY.md`; `privacy.crash_reports` key |
| [M0](../PLAN.md#m0-scaffold-and-ci) (M0b) | Desktop: `desktopTest` in the `unit` job (`AppDirsTest`, `SingleInstanceTest`, `DesktopAppGraphTest`, E11's first part), the desktop golden set wiring (`roborazzi-compose-desktop`), `assemble`'s `createDistributable`, the `desktop-smoke` PR job, `nightly.yml`'s `desktop-matrix` on the four runners (tests, `packageDistributionForCurrentOS`, smoke start, image scan, `codesign --verify`, sizes), `release.yml`'s `desktop` matrix (with the macOS `0.x` ZIP through `mac-zip.sh` and the two-attempt loop) and `sources` job (runtime source tarball, `RUNTIME-SOURCES.md`), `check-desktop-image.sh` and `check-runtime-sources.sh` as blockers (PLAN M0 AC11–AC13), the `desktop[]` manifest entries; server: the `server` PR job (JAR smoke and the listen rule, PLAN M0 AC16), `:sync:server:test` in `unit`, Licensee on `:desktopApp` and `:sync:server`; `checkBrandAssets` in `static` (moved to M0a.1 on 2026-10-06 with the generator's early delivery); the README's draft "Install on Windows, macOS or Linux" pointer; S13's measurements recorded as the first PB24, PB25 and PB27 values (PLAN M0 AC14) with the reference laptops of PO-43 |
| [M1](../PLAN.md#m1-subscribe-and-ingest-rss) | M1a: fakes for 03's interfaces + contracts; `TestDb` and the database helpers in `:core:testing` (02's open question 16); golden switch in `:feeds:jvm`; `MutationRobustnessTest` for `FeedParser` and `mutation-full`; platform-parser corpus in `:app`'s device suite; schema drift and frozen-schema checks live; `TestServer`, E1 (Add by URL); E11's subscribe step; data builders, `fakeImageLoader`; accessibility checks under Robolectric verified (or the instrumented fallback adopted); inventory row `add-input` (typed URLs). M1b: `FakeSecretStore` and the `SecretStore` contract on both platforms |
| [M2](../PLAN.md#m2-groups-and-group-feeds) | fakes for 05/08 interfaces, `FakeYouTubeCapabilitiesSource` and `testCapabilities`; `ScreenshotTier` for both sets; E2; `FeedQueryTimingTest` recorded on GMD and run on the reference device (R2.9, PLAN M2 AC2); reference device confirmed with the PO |
| [M3](../PLAN.md#m3-import-export-and-backup) | OPML/backup mutation providers; E5, E6, E9; nightly `bmgr` job on the release APK; `RecordingAppNavigator` in `:core:testing` |
| [M4](../PLAN.md#m4-playback-core) | Media3 test-utils forcing verified; `:playback:core` tests in `commonTest`; `api34` device; 06's `PlaybackServiceTest` in nightly (incl. API 37 hardening); E3 |
| [M5](../PLAN.md#m5-playback-features-and-system-surfaces) | manual device-matrix template in the release issue |
| [M6](../PLAN.md#m6-downloads) | M6a: E4; `api33` device; the desktop download lanes' tests in `desktopTest`. M6b: `:benchmark` (`com.android.test` + baseline-profile producer) with its `release` variant for system tests and the release smoke journeys, `bench34`, `system-tests` job, E10, the `instrumented-full` release leg; `bmgr` assertion for `Podcasts/` |
| [M7](../PLAN.md#m7-discovery) | `PRIVACY.md` and the in-app inventory gain `apple`, `fyyd`, `podcastindex` and the autodiscovery probes of `add-input`; `PrivacyInventoryParityTest`; E1 deep-link case |
| [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds) | E8 (`ExternalYouTubeModeTest`) in `instrumented` and its desktop variant in `:desktopApp`'s JVM tests (every build is in external mode until M9a on Android and MD3 on the desktop); YouTube import-parser mutation providers; inventory `youtube-subscriptions` |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-via-the-embedded-yt-dlp-engine) (M9a) | E7 through `:ytx` on the debug APK and the release APK (`ReplayRH` hook, `ytxReplay` argument, `ReleaseSmoke` filter); `shimTest` in `unit`; `check-apk.sh` content scan and ELF alignment check against the real engine stack; nightly `no-engine-build` (blocking), `youtube-canary` and `engine-nightly-canary`; `FakeYouTubeEngine`; PB18–PB21 from the spike on the release APK; inventory `youtube-streams` rewritten for the engine; YouTube engine lines in diagnostics and `CrashKey.YOUTUBE_HEALTH` |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-via-the-embedded-yt-dlp-engine) (M9b) | [`engine-canary.yml`](#engine-canaryyml) (`detect`, `gate`, `approve`, `deploy`) with environments `engine-approval` and `github-pages`, the Ed25519 manifest key generated once by a maintainer (no ceremony), Pages source "GitHub Actions", the bootstrap manifest, the engine heartbeat and `keepalive.yml` covering the canary; contract-mode gate and strict-mode report; label `engine-canary`; inventory `youtube-engine`; time budget "upstream stable → approved ≤ 6 h" measured on a real yt-dlp release |
| [M10](../PLAN.md#m10-covers-theming-adaptive-layouts-and-accessibility) | `FULL` screenshot tier and `screenshots-full` for both sets; `generatePseudoLocales` and the pseudo-locale/RTL captures; Weblate project opened at the end of M10 if strings are stable (otherwise M11); `:benchmark` Macrobenchmark journeys against `benchmarkRelease`, `BenchmarkSeedReceiver` in `app/src/benchmarkRelease/`, `benchmark-dryrun`; PB3 on `benchmarkRelease` on the reference device (M10 AC7) |
| [M11](../PLAN.md#m11-release-hardening-and-v10) (M11a) | the [update check](#update-check) in `:core:domain`, `:core:model` and `:core:data` for both apps (all classes, the Android works and the desktop lane, the notifications, the badge state, `UpdateCheckStore`, `DesktopAssetSelector`, `VerificationTimeline`), `FakeAppUpdateChecker`, `FakeUpdateNotices`, its tests and [device checklist](#device-checklist-m11a) on Android and each desktop OS; `updates.*` keys; inventory `app-updates` and the Settings › Privacy rows; README "Install and update" final draft (including "About these builds") and the facts for 08's help page, BUILDS card and notice ([Developer verification](#developer-verification), [Public key trade-offs](#public-key-trade-offs)); the `verification-watch` calendar issue |
| [M11](../PLAN.md#m11-release-hardening-and-v10) (M11b) | the committed baseline and startup profiles (`BaselineProfileGenerator`, PB1 with and without them); all budgets measured (PB1–PB5 on `benchmarkRelease`, per-ABI sizes and engine budgets on the release APKs, PB24–PB29 with MD5, PB30 re-run, PB31); `DiagnosticsRepository` and contributors on both platforms, `DatabaseCopyExporter` with the desktop save dialog, `RedactionCoverageTest`, `diagnostics.verbose_log_until`; ACRA mailbox (PO-10) and the desktop crash email; `PRIVACY.md` final with its Sync and Desktop sections; launch languages (PO-14); GitHub release hardening (release body template final for every product, immutable releases and attestations verified end to end on a `0.12.P` release candidate, the first DMG at `1.0.0`); `live-canary`; network captures on every platform; the [v1.0 gate](#v10-gate) including the cross-device gate (M11 AC14) |
| [MD0](../PLAN.md#md0-desktop-audio-engine-spike) | `desktop-matrix` gains `buildNdmedia`, `buildFfmpeg`, the audio corpus and clock tests with the null back-end on the four runners; `checkNativeLicences` in `static`; clang-format for `ndmedia`; no tester build (spike) |
| [MD1](../PLAN.md#md1-desktop-playback) | MD1a: the engine's `desktopTest` suites in `unit` (null back-end), PB28 measured. MD1b: the corpus suite on every target as a nightly blocker; `sources` builds the FFmpeg source bundle, which `publish` requires from then on; the FFmpeg rules of `check-desktop-image.sh` active |
| [MD2](../PLAN.md#md2-desktop-shell-behaviours-and-os-integration) | `LinuxMprisSessionTest` with a private session bus and `UrlSchemeRegistrarTest` on Windows in `desktop-matrix`; 11's OS-integration checklists in the release template |
| [MD3](../PLAN.md#md3-desktop-youtube-engine) | `shimTestStdio` in `unit` and in `engine-canary.yml`'s `gate`; `checkPythonLicences` for the desktop lock; `YtxProcessTest` and `StdioYtxTransportTest` in the matrix; the Python rules of `check-desktop-image.sh`; PB29; inventory `youtube-streams` and `youtube-engine` cover the desktop host; `youtube-smoke` gains the stdio host |
| [MD4](../PLAN.md#md4-desktop-ux-and-accessibility) | the desktop screenshot matrix in `FULL` (window widths 600, 900, 1400 dp; PLAN MD4 AC2); the keyboard/context-menu reachability test in `commonTest` (MD4 AC1); VoiceOver and NVDA rows in the release template |
| [MD5](../PLAN.md#md5-desktop-packaging-and-release) | `trainAotCache` failures and PB27 blocking in `release.yml`; `check-runtime-sources.sh`, `check-desktop-image.sh` and `checkNativeLicences` confirmed as release blockers; PB24–PB28 on the reference laptops; desktop install and upgrade walkthroughs; `network-capture.sh --desktop` (with M11 AC8) |
| [MS0](../PLAN.md#ms0-sync-groundwork) | `ConformanceVectorTest`, `HlcTest`, `OrderKeyTest` and `SyncConvergenceTest` (1,000 seeds) in `unit`; `InMemorySyncServer` in `:core:testing`; the nightly `sync-convergence` job (100,000 seeds); the `:sync:protocol` mutation tests; the capture-trigger obligation in the PR template and the release checklist |
| [MS1](../PLAN.md#ms1-sync-server-core) | the full server suite and `ServerConformanceTest` in `unit`; the `server` job's image build and `check-server-image.sh`; nightly `server-image-smoke`; `release.yml`'s `server-jar`, `server-image`, `server-image-manifest` and `image-tags` jobs and the image-source bundle in `sources`; the GHCR package made public; the `server` manifest entry; inventory `server-updates`; README "Run the server" draft; PB30 from S16 |
| [MS2](../PLAN.md#ms2-client-sync) | `FakeSyncController` and the sync fakes; E12 (`CrossDeviceSyncTest`) in `sync-convergence`; the `SYNC` diagnostics section and `CrashKey.SYNC`; inventory `sync-server` and `PRIVACY.md`'s Sync section; `RedactionCoverageTest`'s sync token case |
| [MS3](../PLAN.md#ms3-live-updates-and-handoff) | PB31 (manual gate, E12 trend); SSE through Caddy and nginx in `server-image-smoke` (S17) |
| [M12–M17](../PLAN.md#74-after-v10-v1x-themes) | Glance widget screenshot tests (M13); SponsorBlock destination in the inventory, with the engine (M14); the JS challenge provider in the engine canary's test set if it moves to M14; gpodder interop fixtures in the server suite (M16); a `windows-11-arm` runner and target, OS sandboxing tests of the engine child and R8 for the desktop JARs (M17); revisit Compose Preview Screenshot Testing once AGP test suites are stable |

---

## New names introduced here

| Name | Kind | Location |
|---|---|---|
| `configureNeutrodyneTestTasks()`, `configureAndroidTesting()`, `configureManagedDevices()`, `disableEmptyDeviceTests()` | build-logic functions | `build-logic/convention` |
| Tasks `mutationTest` (`:feeds:jvm`, `:youtube:api`, `:sync:protocol`), `generatePseudoLocales` (build-logic, test and debug compilations only), `checkTranslations` (specified here; registered by 01's `neutrodyne.quality`) | Gradle tasks | build-logic, modules |
| Gradle properties `updateGoldens`, `screenshotTier`, `mutationIterations`, `syncSeeds`, `crossDevice`, `testBuildType` (`debug` or `release`), `neutrodyne.testScope`, `neutrodyne.abiSplits` (also passed by every job that inspects published APKs), `pseudoLocales` (only if the fallback of [Pseudo-locales and RTL](#pseudo-locales-and-rtl) is needed); instrumentation arguments `ytxReplay` and the `ReleaseSmoke` annotation filter | build switches | — |
| System properties `neutrodyne.updateGoldens`, `neutrodyne.moduleDir`, `neutrodyne.rootDir`, `neutrodyne.screenshotTier`, `neutrodyne.mutationIterations`, `neutrodyne.syncSeeds` | test configuration | — |
| System property `neutrodyne.instrumentedTest` (set only by `NeutrodyneTestRunner`; `installAcra`'s call site and 01's `DebugToolsInitializer` read it) | inert test hook | `:app` process |
| `NeutrodyneTestRunner` (an `AndroidJUnitRunner`) | test runner | `:app/src/androidTest` |
| `TestClock`, `MainDispatcherTest`, `Goldens` (with the `expect` `testModuleDir()`), `testSystemProperty`, `skipTest` (`commonMain`); `MainDispatcherRule` (`desktopMain` and `androidMain`) | test helpers | `:core:testing`, package `ch.lkmc.neutrodyne.core.testing` |
| `Nightly`, `ReleaseSmoke` (Android annotations); `Flaky`, `CrossDevice` (JUnit categories); `ScreenshotTier`, `assumeTier` | test selection | `:core:testing` |
| `Fake*` per [inventory](#coretesting-inventory) (including `FakeSecretStore`, `FakeAppUpdateChecker`, `FakeUpdateNotices`; 10's sync fakes and `InMemorySyncServer` are 10's names), `*Contract` bases (including `SecretStoreContract`, `SyncControllerContract`), data builders, `fakeImageLoader`, `testCapabilities(external)` | fakes | `:core:testing` |
| `TestDb`, `SeedDatabase`, `FeedFixture`, `MigrationInvariants`, `SqlEnumLiterals` (02's names; location decided here), `RecordingAppNavigator` | test helpers | `:core:testing`, packages `…core.testing.database` and `…core.testing.navigation` |
| `TestServer`, `FixtureDispatcher`, `TestSeeder`, `BackgroundStandInActivity` (declared in the androidTest manifest), `CrossDeviceSyncAndroidTest` (E12's Android half) | E2E helpers | `:app/src/androidTest` |
| `YtxTestHooks` (04 owns the class; 01's consumer rule keeps it in the release build) | test hook | `:youtube:ytdlp` |
| `SmokeTest`, `SubscribeJourneyTest`, `GroupFeedJourneyTest`, `PlayJourneyTest`, `DownloadJourneyTest`, `ImportJourneyTest`, `BackupRestoreJourneyTest`, `YouTubeReleaseSmokeTest` (journeys; E8 is 04's `ExternalYouTubeModeTest`) | E2E tests | `:app/src/androidTest` |
| `DesktopSmokeJourneyTest` (E11), `CrossDeviceSyncTest` (E12's host test; the name is shared with 10) | E2E tests | `:desktopApp` test sources |
| `ReleaseSmokeJourneyTest` | out-of-process release smoke journeys | `:benchmark` (`release` variant) |
| `MutationRobustnessTest` | N9 robustness | `:feeds:jvm`, `:youtube:api`, `:sync:protocol` |
| `PrivacyInventoryParityTest`, `RedactionCoverageTest`, `CrashReportRedactorTest`, `DatabaseCopyExporterTest` | tests | `:feature:settings`, `:core:data` (+ `:app`), `:app`, `:core:data` |
| GMD devices `api26`, `api33`, `api34`, `api36`, `bench34`; groups `ci`, `nightly` | devices | build-logic, `:benchmark` |
| `CrashReporter`, `CrashContext`, `CrashKey` (with `SYNC`) | interfaces/enum | `:core:common` |
| `installAcra` (01's name, defined here), `AcraCrashReporter`, `CrashReportRedactor` | ACRA integration | `:app` |
| `DiagnosticsReport`, `DiagnosticsSection`, `DiagnosticsLine`, `DiagnosticsSectionId` (with `SYNC`, `DESKTOP`), `DiagnosticsSeverity` | data | `:core:model` |
| `DiagnosticsContributor`, `DiagnosticsRepository`, `DiagnosticsError` (with `CANCELLED`) | interfaces | `:core:domain` |
| `DiagnosticsRepositoryImpl`, `DatabaseCopyExporter` | implementations | `:core:data` |
| `BenchmarkSeedReceiver`, `BaselineProfileGenerator` journeys | benchmark-only receiver; profile journeys | `app/src/benchmarkRelease`; `:benchmark` |
| `AppUpdateChecker`, `UpdateNotices` | update-check interfaces | `:core:domain`, package `ch.lkmc.neutrodyne.core.domain.update` |
| `UpdateCheckState`, `UpdateDisabledReason` (`DEV_BUILD`, `CHECKS_OFF`), `UpdateInfo`, `UpdateApk`, `UpdateDesktopAsset`, `UpdateServerInfo`, `UpdateCheckError`, `UpdateNotice` | update-check state types | `:core:model`, package `ch.lkmc.neutrodyne.core.model.update` |
| `AppUpdateCheckerImpl`, `UpdateNoticesImpl`, `GitHubUpdateSource`, `UpdateManifestParser`, `VersionScheme`, `DesktopAssetSelector`, `UpdateCheckStore`, `VerificationTimeline`, `UpdateCheckRunner` (internal) with `WorkUpdateCheckRunner` and `LaneUpdateCheckRunner`, `UpdateCheckWorker`, `UpdateNotifier`, `DesktopUpdateCheckLane`, `DesktopUpdateNotifier` | update-check implementation | `:core:data`, package `ch.lkmc.neutrodyne.core.data.update` (`commonMain`, `androidMain`, `desktopMain`) |
| Work `app-update-check`, `app-update-check-now` (Android); lane `app-update-check` (desktop); file `last-check.json` in `noBackupFilesDir/updates/` or `<data>/updates/` | update check | `:core:data` |
| `UpdateManifestParserTest`, `DesktopAssetSelectorTest`, `GitHubUpdateSourceTest`, `AppUpdateCheckerTest`, `DesktopUpdateCheckLaneTest`, `UpdateNoticesTest` | tests | `:core:data` |
| `BuildInfo.shippedLocales` | field request | `:core:model` (01) |
| `privacy.crash_reports`, `diagnostics.verbose_log_until`, `updates.check_enabled`, `updates.last_check_at`, `updates.skipped_version_code`, `updates.notified_version_code`, `updates.first_run_choice_done`, `updates.verification_notice_shown_at` | setting keys | `:core:model` registry |
| Workflows `ci.yml` (jobs `static`, `unit`, `assemble`, `desktop-smoke`, `server`, `instrumented`), `nightly.yml` (jobs `instrumented-full`, `api37-16k`, `release-build-smoke`, `system-tests`, `no-engine-build`, `desktop-matrix`, `sync-convergence`, `server-image-smoke`, `benchmark-dryrun`, `repro`, `bmgr`, `screenshots-full`, `mutation-full`, `live-canary`, `youtube-canary`, `engine-nightly-canary`; dispatch scopes `full`, `youtube-smoke`, `desktop`), `release.yml` (jobs `verify-tag`, `android`, `desktop`, `sources`, `server-jar`, `server-image`, `server-image-manifest`, `publish`, `image-tags`), `engine-canary.yml` (jobs `detect`, `gate`, `approve`, `deploy`), `record-screenshots.yml`, `keepalive.yml`; engine heartbeat `engine/ytdlp-heartbeat.json` (+ `.sig`) on GitHub Pages; GitHub environments `release`, `engine-approval`, `github-pages`; labels `run-instrumented`, `nightly-failure`, `release-blocker`, `engine-canary`, `repro`, `verification-watch`, `chaquopy`, `bundled-component` | CI | `.github/` |
| Release assets per [Release assets](#release-assets) (`neutrodyne-{v}-{abi}.apk`, `neutrodyne-{v}-r8-mapping.zip`, `neutrodyne-{v}-{os}-{arch}.{msi,zip,dmg,deb,rpm,tar.gz}`, `neutrodyne-server-{v}.jar`, `openjdk-{jdk}-temurin-sources.tar.gz`, `RUNTIME-SOURCES.md`, `ffmpeg-{ffmpeg}-neutrodyne-src.tar.xz`, `neutrodyne-server-image-sources-{v}.tar.xz`, `neutrodyne-update.json`, `SHA256SUMS`); image `ghcr.io/{owner}/neutrodyne-server` | release | GitHub Releases, GHCR |
| Scripts per [CI scripts](#ci-scripts) (new or renamed: `public-cert-sha256.sh`, `check-desktop-image.sh`, `check-runtime-sources.sh`, `check-server-image.sh`; extended: `check-apk.sh --published`, `make-update-json.sh`, `check-update-json.sh`, `verify-tag.sh`, `network-capture.sh --android/--desktop/--server`), `scripts/release.sh`, `scripts/l10n/update-shipped-locales.sh` | scripts | `scripts/` |
| `release-assets.json` (the expected asset list per release stage, read by `verify-tag.sh`), `app/policy/locales.txt`, `app/lint-baseline.xml`, `app/proguard-test.pro`, `config/detekt/detekt.yml`, `renovate.json`, `changelogs/<versionCode>.txt`, `PRIVACY.md`, `SECURITY.md`, `.editorconfig`, `.clang-format`, `signing/neutrodyne-public.keystore` and `signing/README.md` (content) | files | repository |
| Secrets `NEUTRODYNE_ENGINE_MANIFEST_KEY`, `NEUTRODYNE_ENGINE_MANIFEST_KEY_NEXT` (only during a rotation) | CI configuration | GitHub environment `engine-approval` |
| Budget IDs PB12, PB13 (release APKs, < 40 MB and < 30 MB), PB18–PB21, PB24–PB31; PB22 and PB23 removed | budget IDs | this document |

Removed 2026-10-05 (PO-31–PO-35): `AppUpdater`, `UpdateState`, `UpdateMode`, `UpdateChannel`, `InstallBlockReason`, `UpdateError`, `AppUpdaterImpl`, `UpdateDownloadWorker`, `UpdateInstallWorker`, `ApkVerifier`, `ApkInspector`, `SelfInstaller`, `UpdateStatusReceiver`, `InstallIdleGate`, `InstallerOfRecordDetector`, `VerificationFailureMapper`, `FakeAppUpdater`, their tests, the works `app-update-download` and `app-update-install`, `pending.json`, the keys `updates.mode`, `updates.channel` and `updates.whats_new_version_code`, the workflow `baseline-profile.yml`, the `mirror` job, the asset `neutrodyne-{v}-mapping.txt`, `neutrodyne.lineage`, the secrets and variables listed in [Hardening](#hardening) and the manifest fields `prerelease`, `certSha256` and `previousCertSha256`. Removed 2026-10-05 (scope revision): `signing/neutrodyne-debug.keystore`, the `neutrodyneDebug` signing config and `debug-cert-sha256.sh` (replaced by the committed public keystore, `neutrodynePublic` and `public-cert-sha256.sh`); the `benchmark` build type and the `-PtestBuildType=benchmark` leg; the nightly `dev-tools-build`; PB22 and PB23; the `debug-apks` artifact (now `release-apks`); the Hilt `UpdateModule` and `@TestInstallIn` test modules; the `java-test-fixtures` of `:core:common`, `:core:database` and `:core:navigation`.

---

## Open questions

1. Resolved 2026-10-05 (scope revision): `:core:testing` is a KMP module (`neutrodyne.kmp.library`, [01 Module layout](01-foundation.md#module-layout)); `TestClock`, `MainDispatcherTest` and `Goldens` live in its `commonMain`, `MainDispatcherRule` in its `desktopMain` and `androidMain`. The earlier resolution (an Android library re-exporting `:core:common` test fixtures) is superseded.
2. Resolved: `:benchmark` is created in M6b (system tests) and gains Macrobenchmarks against `:app`'s `benchmarkRelease` in M10 (PLAN 5.1, M6, M10; 01's module table). Resolved 2026-10-05 (scope revision): profiles are no longer deferred — published APKs are release builds, and `BaselineProfileGenerator` produces the committed baseline and startup profiles from M11b ([Macrobenchmark and profiles](#macrobenchmark-and-profiles)).
3. Resolved: 01 commits `neutrodyne.acraMailto` in `gradle.properties`, [D62](../PLAN.md#3-key-decisions) records it, and [PO-3](../PLAN.md#po-3-podcast-index-api-key-handling) option A injects a Podcast Index key into the published builds only (`release.yml`), after Podcast Index's written permission ([D26](../PLAN.md#3-key-decisions)).
4. Resolved 2026-10-05 (scope revision): PLAN M0a delivers the committed keystore `signing/neutrodyne-public.keystore` and the first release `v0.1.0`; [PO-8](../PLAN.md#48-further-product-owner-decisions) is resolved (`ch.lkmc.neutrodyne`), and [PO-35](../PLAN.md#48-further-product-owner-decisions) is re-resolved: published APKs are non-debuggable release builds signed with the committed key (no private key, so no holders, ceremony or backups, [Committed keystore](#committed-keystore)); [PO-5](../PLAN.md#po-5-google-developer-verification) is resolved (not registering), and directly sideloaded tester builds are unaffected until Google's global rollout.
5. Resolved 2026-10-05 (scope revision): 02's database test helpers live in `:core:testing` (package `…core.testing.database`, 02's open question 16), because Gradle test fixtures do not apply to KMP modules; see 27.
6. Resolved: PLAN M0 acceptance 4 reads "API 26 device (Gradle Managed Device, or an android-emulator-runner API 26 emulator)".
7. Resolved in 02 ([02 db-maintenance worker](02-data-model.md#db-maintenance-worker)): the scrub covers `episode.identityKey`, `episode.guid`, `podcast.artworkUrl`, `episode.imageUrl`, `episode.chaptersUrl`, `episode_alt_enclosure.sourcesJson` and `artwork.url`, and the Android target lies under `cacheDir/export/`.
8. Resolved in 01: rule 4 keeps segments shorter than 15 characters, so the 15-character example is masked.
9. Moved to [PO-28](../PLAN.md#48-further-product-owner-decisions) (default: Pixel 7a); the desktop and server reference hardware is [PO-43](../PLAN.md#48-further-product-owner-decisions).
10. Obsolete since 2026-10-05: there is one Android build; ACRA by email in every release APK by default ([PO-10](../PLAN.md#48-further-product-owner-decisions)), never in `:ytx`.
11. Obsolete since 2026-10-05: there is no store listing, so no audience rating to decide.
12. Resolved in 01: committed `neutrodyne.acraMailto` (forced empty only in `debug` builds since the scope revision); AboutLibraries offline mode; `BuildInfo.shippedLocales`; the `Text("` literal pattern in `checkBannedApis`; every test-bearing convention plugin calls `configureNeutrodyneTestTasks()` (`neutrodyne.jvm.library` no longer exists); `verifyDependencyPolicy` fails on `io.mockk` in any `*AndroidTestRuntimeClasspath`; `include(":benchmark")` in M6b. The test fixtures on `:core:common`, `:core:database` and `:core:navigation` are superseded (1, 5, 27).
13. Obsolete since 2026-10-05: no store scans the source tree; committed `.zip`/`.gz` fixtures stay test inputs ([Fixture policy](#fixture-policy)).
14. Unverified, checked in the named milestone: GMD API 26 and AGP 9.4 managed-device DSL names (M0a); the device-test disable accessor (M0a); that GMD and `connected*` test tasks install the ABI split matching the emulator (M0a); the Android-KMP plugin's host- and device-test DSL and task names (M0a, S8/S10); that islands and the server resolve `:core:testing`'s JVM variant (M0a); Robolectric default-locale override (M0a); accessibility checks under Robolectric and ui-test-manifest's activity in the KMP host-test manifest (M1a); compose-rules `.editorconfig` keys (M0a); `generateLocaleConfig` with `localeFilters` and the generated locale-config file name (M0a); android-emulator-runner with a minor-versioned `api-level` (M0a); AGP 9's ABI-split output file names for `release` and the `llvm-readelf` binary on the runner (M0a); `testBuildType = release` with R8 and ABI splits (M0a, S19); byte-identical signed APKs from two rebuilds with the committed key (M0a, `repro`); whether GitHub push protection flags the committed PKCS12 keystore (M0a); AGP's default VCS info in release builds (M0a); that the orchestrator calls `NeutrodyneTestRunner.newApplication` in every test process (M0a); `actions/setup-python` and the `python:3.14-slim-trixie` image as Chaquopy's build Python (M0a, with S7); Chaquopy's `.pyc` determinism under `SOURCE_DATE_EPOCH` (M0a); desktop UI tests without a display on every runner (M0b); reproducible JARs from the Compose plugin's tasks (M0b); R8 keeping `YtxTestHooks` (S19, M9a); GitHub Pages cache lifetime and partial-deployment behaviour (M9b); `com.android.test` with the baseline-profile producer matching `:app`'s `release`, `benchmarkRelease` and `nonMinifiedRelease` under AGP 9.4 (M6b, M10); how soon ProfileInstaller's profile applies to a sideloaded install (M11b); Renovate hosted wrapper regeneration (M0a) and the custom managers (35); GitHub's limits for unauthenticated `releases/download` requests (M11a); whether deleting a never-published draft keeps a tag name usable under immutable releases (M0a); which events count as repository activity for GitHub's 60-day schedule rule and whether `gh workflow enable` resets it (M0a, `keepalive.yml`); distroless dpkg records for the image-source bundle (MS1, with 10); emulator `-tcpdump` on API 36 (M11b); Weblate statistics API (M11b). Dropped 2026-10-05 with PO-31–PO-35: the Codeberg mirror policy and heartbeat check, `releases.atom`, Obtainium's package IDs, Robolectric's install-constraint support, `pm set-developer-verification-result` on a retail device and AGP signing with a rotation lineage. Dropped 2026-10-05 with the scope revision: whether a command-line `assembleDebug` sets `testOnly` and whether Macrobenchmark needs `NOT-PROFILEABLE` suppressed for a debuggable published build (nothing debuggable is published), and Kotlin in Android test fixtures (none exist).
15. Resolved (asked by 08), updated 2026-10-05 (PO-31): "Check now" works while "Check for updates" is off, as a one-off user action, and is refused only in `Disabled(DEV_BUILD)` (both apps' debug builds since the scope revision).
16. Resolved 2026-10-05 (PO-31): there is no install gate. The update check installs nothing, so it never waits for playback or downloads; a manual install ends the process like any update (06, 07, 11).
17. Resolved 2026-10-05 (PO-31): the app does not read the installer of record and does not detect Obtainium; Obtainium users turn "Check for updates" off themselves (R6.3).
18. Resolved 2026-10-05 (PO-34): no mirror, no mirrored assets or manifests, no fallback URL; a GitHub takedown is an accepted risk ([GitHub takedown](#github-takedown)).
19. Resolved 2026-10-05 (PO-31; risk T17 retired): there is no self-update whose behaviour under enforcement could differ. Every update goes through Android's own installer, and the help text is checked by hand on an enforcing device in 2027 ([Watch and notice timing](#watch-and-notice-timing)).
20. Unverified (risk P9): the consequences for our installs if someone registers `ch.lkmc.neutrodyne` with another key before Neutrodyne has 50 installs, or registers Neutrodyne's own public certificate; no mitigation short of registering exists, and registering would need a private key and one reinstall ([Package name and key](#package-name-and-key)).
21. Should the engine canary also re-run against the currently approved version when the shim changes on `main` (so a shim PR cannot silently break the approved engine)? Default: no — shim PRs run both shim suites against the bundled version in CI and against the latest approved one locally (PR template); revisit if a regression slips through.
22. Unverified: GitHub's behaviour for `make_latest` when a patch of an older line is published after a newer release; `verify-tag.sh` avoids the case by computing `make_latest` itself (every release is a normal release since PO-33).
23. Resolved 2026-10-05 (PO-31): no blocked sheet and no "Continue in Android" — the app installs nothing, so it never receives an installer result.
24. Superseded 2026-10-05 (scope revision): `debug` is no longer the published build type, so `:app` declares `ui-test-manifest` as `debugImplementation` again; KMP modules' Robolectric captures host composables through ui-test-manifest on their host-test classpath (Unverified merge, M1a check, with a `ShadowPackageManager` fallback); `:app`'s instrumented tests use only `createAndroidComposeRule<MainActivity>()` ([Compose UI and screenshot tests](#compose-ui-and-screenshot-tests)).
25. Updated 2026-10-05 (scope revision): ACRA is off in `debug` builds, so the debug device suite has none; release-type runs keep it out through `NeutrodyneTestRunner` and the inert system property `neutrodyne.instrumentedTest`, which 01's `installAcra` call site and `DebugToolsInitializer` read; `YtxIsolationTest` installs ACRA itself with the test-only address `ytx-isolation-test@invalid` (Unverified late installation, M9a check; [Gradle Managed Devices](#gradle-managed-devices)).
26. New (scope revision): 01's `neutrodyne.kmp.library` says "device tests never in library modules (they run in `:app`)", while 02 runs its migration, driver and trigger tests as `androidDeviceTest` in `:core:database`. Default here: device tests are enabled in `:core:database` only (they need the schema assets and both Android drivers next to the database code); every other device test runs from `:app`. Ask 01 to state the exception (Unverified DSL, M0a; fallback: the tests move to `:app/src/androidTest` with the schemas as `:app` test assets).
27. New (scope revision, 02's open question 16): KMP modules cannot publish Gradle test fixtures, so `TestDb` and its siblings and `RecordingAppNavigator` move into `:core:testing`, which then depends on `:core:database` and `:core:navigation` as test-support edges beyond 01's rule 9 (`:core:testing` → `:core:{domain, model, common}`, `:*:api`, `:sync:protocol`) and its rule 13's mention of test fixtures. Default: the edges, with `:core:database`'s own tests depending on `:core:testing` (a test-scope dependency on a project that depends on the module's main code, which Gradle resolves without a task cycle; Unverified for KMP, M1a). Alternative: a separate `:core:database:testing` module. Ask 01 to amend rules 9 and 13. **Resolved 2026-10-05 (scope revision):** 01 rules 9 and 13 and its `allowed` list carry the two edges, a `restricted` rule keeps `:core:testing` off every main classpath, and PLAN 5.1 records it.
28. New (scope revision): 01's `:app` sketch sets `testBuildType = "debug"`; the release smoke runs (E0 and E7 in-process on the release build, PLAN M9 AC9) need `testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")` ([Release-type test runs](#gradle-managed-devices)). Ask 01 to adopt the property; the default stays `debug`.
29. Can `docker buildx imagetools create` push the multi-arch index by digest without a tag? Default: try it in MS1; otherwise a throwaway `ci-{run_id}` tag that `image-tags` deletes, never a user-facing tag before publishing ([release.yml](#releaseyml) step 7).
30. Is tag → published release with every asset < 60 min realistic with four desktop runners (the 3-vCPU `macos-15` packaging the app, the CPython bundle and the AOT cache) and two image builds? Measured on the M0b and MS1 releases; if not, the PO amends N11's hotfix budget or accepts a slower desktop path (risk P15).
31. Desktop goldens depend on the Linux runner image's fonts and Skiko's rendering; default: re-record when `ubuntu-24.04` changes fonts (Renovate cannot see it, so a red `screenshots-full` after an image update leads to a re-record PR), no bundled test fonts (their licences are not on D3's list).
32. Compose resources and shipped locales: can the packaging tasks leave out non-shipped `values-*` directories (S11)? Default fallback: partial languages ship but are hidden from both pickers ([Shipped locales and per-app language](#shipped-locales-and-per-app-language)).
33. New (asked of 11): the desktop crash dialog should honour `privacy.crash_reports` (off: crash files stay local, no dialog), as ACRA does on Android ([Crash reporting and diagnostics](#crash-reporting-and-diagnostics)); 11 to confirm in its crash-file rules.
34. E12 on one runner: an emulator, the server JAR and a desktop JVM driven phase by phase through `am instrument` (MS2 check); fallback: two independent suites against one server, compared through the server's records ([End-to-end journeys](#end-to-end-journeys)).
35. Hosted Renovate and the lockfiles: detecting new Temurin, python-build-standalone, FFmpeg, miniaudio, C++/WinRT and WiX versions needs regex custom managers (Unverified configuration, M0b); refreshing their checksums stays a manual step or a documented command in the PR, because the hosted app runs no post-upgrade tasks.
36. New (asked of 01): `checkTranslations` ([Localisation](#workflow)) is specified here and should be registered by `neutrodyne.quality` next to `checkSpdxHeaders` and `checkBannedApis`.
37. New (asked of 11): `check-desktop-image.sh` gains the localisation rule (no `rXA`/`rXB` Compose resources; once S11 settles the filter, no locale outside `locales.txt`); 11 owns the image-scan rule list.

---

## Sources

All checked 2026-10-04 by the research behind this plan unless marked otherwise; entries marked (2026-10-05) were checked or re-checked for the product owner's decisions of that day, including the scope revision.

Testing and build:
- JUnit 5/6 on Android and its API 35+ instrumentation requirement — https://github.com/mannodermaus/android-junit5
- Compose testing v2 APIs — https://developer.android.com/develop/ui/compose/testing/migrate-v2
- Compose accessibility testing — https://developer.android.com/develop/ui/compose/accessibility/testing
- Robolectric 4.17 (SDK 37 support; JDK 21 for SDK 36 since 4.16) — https://github.com/robolectric/robolectric/releases
- Roborazzi 1.76.0 — https://github.com/takahirom/roborazzi
- Paparazzi status — https://github.com/cashapp/paparazzi/blob/master/CHANGELOG.md
- Compose Preview Screenshot Testing (alpha) — https://developer.android.com/studio/preview/compose-screenshot-testing
- Media3 `TestPlayerRunHelper` — https://developer.android.com/reference/kotlin/androidx/media3/test/utils/robolectric/TestPlayerRunHelper
- Media3 test-utils POM (pulls MockWebServer 4.12, Robolectric 4.16) — https://dl.google.com/android/maven2/androidx/media3/media3-test-utils/1.11.1/media3-test-utils-1.11.1.pom
- Coil testing (`FakeImageLoaderEngine`) — https://coil-kt.github.io/coil/testing/
- Room 3 releases and migration testing — https://developer.android.com/jetpack/androidx/releases/room3 · https://developer.android.com/training/data-storage/room/migrating-db-versions
- Gradle Managed Devices (ATD removes SystemUI, launcher and Settings; "use API levels 27 and higher"; GPU and sharding properties; re-checked 2026-10-05) — https://developer.android.com/studio/test/gradle-managed-devices
- System images: ATD x86_64 API 30–36, `default` x86_64 API 26–36, API 37 only as `android-37.x` Google APIs images incl. `ps16k` (read 2026-10-05) — https://dl.google.com/android/repository/sys-img/aosp_atd/sys-img2-3.xml · https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml · https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml · https://dl.google.com/android/repository/sys-img/google_apis_playstore/sys-img2-3.xml
- Pseudolocales and locale filters — https://developer.android.com/guide/topics/resources/pseudolocales
- AGP 9.0 defaults (tested build type only, R8 strict keep rules, GMD replaces device providers) — https://developer.android.com/build/releases/agp-9-0-0-release-notes
- R8 and shrinking — https://developer.android.com/build/shrink-code · https://developer.android.com/build/releases/past-releases/agp-8-0-0-release-notes
- Baseline Profiles overview (installed for non-debuggable builds; non-Play installs may not apply them) (2026-10-05) — https://developer.android.com/topic/performance/baselineprofiles/overview
- Testing backup and restore (`bmgr`) — https://developer.android.com/identity/data/testingbackup
- TestParameterInjector — https://repo1.maven.org/maven2/com/google/testparameterinjector/test-parameter-injector/maven-metadata.xml
- OkHttp / MockWebServer 5.5.0 — https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml
- `py_compile`: checked-hash `.pyc` when `SOURCE_DATE_EPOCH` is set (2026-10-05) — https://docs.python.org/3/library/py_compile.html

CI and tooling:
- GitHub-hosted runners (4 vCPU / 16 GB / 14 GB for public repositories) — https://docs.github.com/en/actions/reference/runners/github-hosted-runners
- Hardware-accelerated Android emulation on Linux runners — https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/
- setup-gradle v6 (basic vs enhanced caching, wrapper validation) — https://github.com/gradle/actions/blob/main/docs/setup-gradle.md · https://github.com/gradle/actions/releases
- `actions/setup-python` — https://github.com/actions/setup-python
- android-emulator-runner — https://github.com/ReactiveCircus/android-emulator-runner
- Renovate Gradle manager and wrapper advisory — https://docs.renovatebot.com/modules/manager/gradle/ · https://www.vulncheck.com/advisories/renovate-before-44.14.7-command-injection-via-gradle-wrapper
- Dependabot version catalogs and lockfiles — https://github.blog/changelog/2023-03-13-dependabot-version-updates-keeps-gradle-version-catalogs-up-to-date/ · https://github.blog/changelog/2025-06-24-dependabot-support-for-gradle-lockfiles-is-now-generally-available/
- detekt compatibility table — https://detekt.dev/docs/introduction/compatibility/
- Licensee — https://github.com/cashapp/licensee
- Kotlin Gradle plugin compatibility — https://kotlinlang.org/docs/gradle-configure-project.html
- Gradle current version — https://services.gradle.org/versions/current
- Release container (2026-10-05): Docker's official Python 3.14 image variants incl. `slim-trixie` — https://github.com/docker-library/python/tree/master/3.14 · Debian trixie `python3` 3.13.5 — https://packages.debian.org/trixie/python3 · Debian trixie `openjdk-21-jdk-headless` — https://packages.debian.org/trixie/openjdk-21-jdk-headless
- `actions/deploy-pages` (permissions `pages: write`, `id-token: write`; environment `github-pages`; needs `actions/upload-pages-artifact`) (2026-10-05) — https://github.com/actions/deploy-pages
- GitHub Pages limits — https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits
- Scheduled workflows disabled in public repositories after 60 days without activity (checked 2026-10-05) — https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows
- OpenSSL `pkeyutl` (`-rawin` signing for Ed25519) — https://docs.openssl.org/3.0/man1/openssl-pkeyutl/

Distribution, signing and updates (2026-10-05):
- GitHub immutable releases (assets and tag locked, draft → publish, release attestation, tag names not reusable) — https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases · https://github.blog/changelog/2025-10-28-immutable-releases-are-now-generally-available/
- `actions/attest` v4 (`subject-path` globs and lists; permissions `id-token`, `attestations`, `artifact-metadata`) — https://github.com/actions/attest
- Artifact attestations (SLSA v1.0 Build Level 2; "not a security guarantee") — https://docs.github.com/en/actions/concepts/security/artifact-attestations
- `gh release verify-asset` and `gh release verify` — https://cli.github.com/manual/gh_release_verify-asset
- `releases/latest/download/<asset>` links — https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases
- Latest release = most recent non-prerelease, non-draft — https://docs.github.com/en/rest/releases/releases#get-the-latest-release
- REST API rate limits (60 unauthenticated requests per hour per IP) — https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api
- youtube-dl reinstated on GitHub (takedown reversed after weeks) — https://github.blog/2020-11-16-standing-up-for-developers-youtube-dl-is-back/
- Obtainium (APK-only asset filter, `autoApkFilterByArch`, REST source) — https://github.com/ImranR98/Obtainium · deep links and badge — https://wiki.obtainium.imranr.dev/deep_links/
- `apksigner` (`verify --verbose --print-certs`) — https://developer.android.com/tools/apksigner
- APK Signature Scheme v3 / v3.1 (key rotation from API 28, v3.1 from API 33; only for the "later private key" note) — https://source.android.com/docs/security/features/apksigning/v3
- AOSP `PackageInstallerSession` (verifier skipped for shell/root callers, so ADB installs are exempt) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/pm/PackageInstallerSession.java
- OpenSSL `genpkey` (Ed25519 key generation for the engine-manifest key) — https://docs.openssl.org/3.0/man1/openssl-genpkey/
- 16 KB page sizes — https://developer.android.com/16kb-page-size · https://developer.android.com/guide/practices/page-sizes
- yt-dlp release files and signing key (engine canary inputs) — https://github.com/yt-dlp/yt-dlp#release-files · https://github.com/yt-dlp/yt-dlp/blob/master/public.key
- Chaquopy (build-time `.pyc`, Python ≥ 3.12 64-bit only) — https://chaquo.com/chaquopy/doc/current/android.html · FAQ — https://chaquo.com/chaquopy/doc/current/faq.html
- Unlicense — https://en.wikipedia.org/wiki/Unlicense

Release builds, signing and measurement (2026-10-05, PO-35 re-resolved):
- R8 is BSD-3-Clause — https://r8.googlesource.com/r8/+/refs/heads/main/LICENSE
- `androidx.baselineprofile` Gradle plugin 1.5.0 (latest release) — https://dl.google.com/android/maven2/androidx/baselineprofile/androidx.baselineprofile.gradle.plugin/maven-metadata.xml
- `android:debuggable`, `android:testOnly` (added by Android Studio's Run; such APKs install only over adb) — https://developer.android.com/guide/topics/manifest/application-element
- Build types, `signingConfigs` per build type, `initWith`, `matchingFallbacks`; AGP's default debug signing with a per-machine debug keystore — https://developer.android.com/build/build-variants
- App signing (Android Studio's debug certificate expires after 30 years) — https://developer.android.com/studio/publish/app-signing
- ABI splits (outputs named `modulename-ABI-buildvariant.apk`; universal APK only with `universalApk`) — https://developer.android.com/build/configure-apk-splits
- Macrobenchmark (target must be non-debuggable; the test module builds the app variant with the same build type name; `CompilationMode` options) — https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- Macrobenchmark instrumentation arguments (`dryRunMode.enable`) — https://developer.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args
- `CompilationMode.Partial` (`BaselineProfileMode.Disable` needs `warmupIterations` > 0) — https://developer.android.com/reference/kotlin/androidx/benchmark/macro/CompilationMode.Partial
- `ApplicationBuildType` (`isProfileable`, `isDebuggable`) — https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/ApplicationBuildType
- `aapt2 dump badging` prints `application-debuggable` for debuggable APKs — https://mas.owasp.org/MASTG/techniques/android/MASTG-TECH-0150/
- AOSP `Instrumentation.newApplication` instantiates the application and calls `attach` (so a runner can act before `attachBaseContext`) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Instrumentation.java
- Removed 2026-10-05 (scope revision): the sources for the debuggable-build reasoning (ART's JIT-only debuggable mode, CheckJNI and JDWP for debuggable apps, `run-as`, Android 12's `adb backup` rule, Compose's debug-mode cost, PNG crunching defaults of the `debug` type); published APKs are release builds.

Kotlin Multiplatform, desktop and server testing (2026-10-05):
- Compose Multiplatform UI testing (`runComposeUiTest`, `@OptIn(ExperimentalTestApi::class)`, common tests cannot run as Android local tests) — https://kotlinlang.org/docs/multiplatform/compose-test.html
- Roborazzi Compose Desktop (`roborazzi-compose-desktop`, `runDesktopComposeUiTest`, `captureRoboImage`, `recordRoborazziDesktop`/`verifyRoborazziDesktop`) — https://github.com/takahirom/roborazzi
- Ktor server testing (`testApplication`) — https://ktor.io/docs/server-testing.html
- Android-KMP library plugin (one variant; host and device tests opt-in) — https://developer.android.com/kotlin/multiplatform/plugin ; no shared JVM and Android source set — https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html
- Room KMP (multi-instance invalidation Android-only; bundled driver) — https://developer.android.com/kotlin/multiplatform/room ; `sqlite-bundled-jvm` natives (no `windows_arm64`, no `osx_x64`) — https://dl.google.com/android/maven2/androidx/sqlite/sqlite-bundled-jvm/maven-metadata.xml
- Compose resources usage (`Res.string`, `getString` outside composition) — https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html ; setup — https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-setup.html
- Weblate Android string format — https://docs.weblate.org/en/latest/formats/android.html
- Metro compatibility (Kotlin version range) — https://zacsweers.github.io/metro/latest/compatibility/

Desktop packaging, runtime and server image (2026-10-05):
- GitHub-hosted runners for public repositories (`ubuntu-24.04`, `ubuntu-24.04-arm` and `windows-2025` with 4 vCPU and 16 GB; `macos-15` M1 with 3 vCPU and 7 GB) — https://docs.github.com/en/actions/reference/runners/github-hosted-runners
- `actions/setup-java` multi-line `java-version` (the last one becomes the default) — https://github.com/actions/setup-java
- Compose native distributions (no cross-compilation, ProGuard tasks, version rules) — https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html ; jpackage refuses macOS versions starting with 0 — https://github.com/openjdk/jdk25u/blob/master/src/jdk.jpackage/macosx/classes/jdk/jpackage/internal/CFBundleVersion.java
- OpenJDK GPL-2.0 with the Classpath Exception — https://openjdk.org/legal/gplv2+ce.html ; Adoptium FAQ — https://adoptium.net/docs/faq/ ; Temurin 25 binaries and source tarballs — https://github.com/adoptium/temurin25-binaries/releases
- FFmpeg legal checklist — https://ffmpeg.org/legal.html ; LGPL-2.1 — https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
- GitHub release assets: each file under 2 GiB, no limit on a release's total size or bandwidth — https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases
- GitHub Container Registry — https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry ; package visibility (making a package public is irreversible) — https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility
- `actions/attest` (`subject-name`, `subject-digest`, `push-to-registry`) — https://github.com/actions/attest ; `gh attestation verify` with `oci://` image URIs — https://cli.github.com/manual/gh_attestation_verify
- `docker buildx imagetools create` (index from digests; `--tag`) — https://docs.docker.com/reference/cli/docker/buildx/imagetools/create/
- distroless Java images — https://github.com/GoogleContainerTools/distroless/blob/main/java/README.md

Developer verification (2026-10-05):
- Overview and timeline — https://developer.android.com/developer-verification
- Guides (participating stores, phases) — https://developer.android.com/developer-verification/guides
- FAQ (sideloads unaffected before the global rollout, ADB exempt, updates fail when the advanced flow is off, lost key prevents registration) — https://developer.android.com/developer-verification/guides/faq
- Affected devices (Help Center) — https://support.google.com/android/answer/17065026?hl=en
- Advanced flow (Help Center) — https://support.google.com/android/answer/17588095?hl=en
- Advanced flow announcement (blog 2026-03-19) — https://android-developers.googleblog.com/2026/03/android-developer-verification.html
- Package-name registration rules — https://developer.android.com/developer-verification/guides/android-developer-console
- Limited distribution (20 devices) — https://developer.android.com/developer-verification/guides/limited-distribution
- LineageOS statement — https://lineageos.org/Developer-Verification/
- Shizuku setup (restart after reboot) — https://shizuku.rikka.app/guide/setup/
- Android Auto and sideloaded apps — https://www.androidauthority.com/sideload-apps-on-android-auto-3681820/
- Doze, App Standby and battery-optimisation exemptions — https://developer.android.com/training/monitoring-device-state/doze-standby
- YouTube bot challenges from data-centre IPs (secondary) — https://www.technetexperts.com/?p=11741

Privacy, crash reporting, localisation and platform:
- ACRA setup, senders, interactions — https://www.acra.ch/docs/Setup · https://www.acra.ch/docs/Senders · https://www.acra.ch/docs/Interactions
- ACRA 5.14.2 internals (administrator `shouldSendReport` before `saveCrashReportFile`; `acra.enable`/`acra.disable` preferences, `sharedPreferencesName`, `ErrorReporter.setEnabled`), read from the published bytecode 2026-10-05 — https://repo1.maven.org/maven2/ch/acra/acra-core/5.14.2/
- GitHub issue creation from URL query parameters (issue-form fields, `414 URI Too Long`) — https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/creating-an-issue#creating-an-issue-from-a-url-query
- Hosted Weblate Libre plan — https://weblate.org/en/hosting/
- Per-app languages — https://developer.android.com/guide/topics/resources/app-languages
- Android 16 behaviour changes (targeting 36; all apps) — https://developer.android.com/about/versions/16/behavior-changes-16 · https://developer.android.com/about/versions/16/behavior-changes-all
- Android local network permission (`ACCESS_LOCAL_NETWORK`) (2026-10-05) — https://developer.android.com/privacy-and-security/local-network-permission
- Android 17 behaviour changes and background audio — https://developer.android.com/about/versions/17/behavior-changes-17 · https://developer.android.com/about/versions/17/changes/bg-audio
- Prior-art reliability lessons (position-loss bug class, database growth) — https://github.com/AntennaPod/AntennaPod/releases/tag/3.12.0 · https://github.com/AntennaPod/AntennaPod/issues/4426
