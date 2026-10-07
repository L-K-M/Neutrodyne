<!-- SPDX-License-Identifier: Unlicense -->
<!-- docs/design/09-quality-and-release.md "Release checklist" as checkboxes; one
     issue per release. Items marked with a milestone apply from that milestone on. -->
---
name: Release checklist
about: One issue per release — the checklist of 09
title: "Release v"
labels: ["release"]
---

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

- [ ] Macrobenchmarks PB1–PB5 on `benchmarkRelease` on the reference phone; baseline and startup profiles regenerated when start-up code changed (M11b); results attached; no unexplained regression > 10 % on PB1–PB4; per-ABI sizes (PB12, PB13) from `check-apk.sh --published` attached.
- [ ] (M0b) Desktop sizes (PB27) from the release jobs attached; PB24–PB28 re-measured on the reference laptops when the release changes start-up, rendering, the runtime or the engine; (MD3) PB29 when the engine host changed; (MS1) PB30 on the Raspberry Pi 4 when the server changed.
- [ ] `update-shipped-locales.sh` run; `locales.txt` committed.
- [ ] Manual device matrix of 06 and checklist of 07 re-run on the reference phone (release `arm64-v8a` APK); external mode checked once on the `armeabi-v7a` APK.
- [ ] (MD2) 11's OS-integration checklists on each OS; (MD5) the install and upgrade walkthroughs per OS match the README step by step (macOS "Open Anyway", SmartScreen, Smart App Control, Linux packages; PLAN MD5 AC5).
- [ ] 08's manual checks: TalkBack, Switch Access, 200 % font, Arabic RTL, keyboard-only, foldable postures, grid → podcast transition review, airplane mode with downloads; (MD4) VoiceOver on macOS and NVDA on Windows.
- [ ] `bmgr` check on a device (05/07).
- [ ] `PRIVACY.md` matches the network inventory (parity test green) and any new destination; when the release adds a destination or changes networking code, `network-capture.sh` re-run for the affected platforms and its host list attached.
- [ ] The README sections "Install and update", "Install on Windows, macOS or Linux" and "Run the server", 08's Install & updates help page and 10's Deployment agree with the developer-verification facts and the public-key trade-offs; the latest `verification-watch` issue is closed; the release body template is current.
- [ ] The engine canary is green for both hosts and the bundled yt-dlp is the latest approved version, or the difference is explained (04).
- [ ] (MS2) When `:sync:protocol` changed: this release's apps sync with the previous release's server and the previous release's apps with this server, within the protocol window, checked by hand and recorded.

### Hotfix (YouTube fast lane)

Engine path first (04's runbook): when the fix is in a yt-dlp stable release and the shim needs no change, no app release ships — `engine-canary.yml` approves the release within 6 h, or a maintainer dispatches it after re-recorded fixtures.

Release path second (path 3): shim fix PR or `bump-ytdlp.sh` → CI → merge → dispatch `nightly.yml` with `scope: youtube-smoke` on `main` → `release.sh patch --hotfix` → `release.yml`. Skipped for hotfixes: benchmarks, locales, manual matrices.

### Milestone tester build

- [ ] The milestone's acceptance criteria are listed here with the test or manual check that proves each one.
- [ ] Tag `0.{n+1}.P` (or the next PATCH of the current line); an immutable, normal release — never pre-release — with every product built so far, plus `SHA256SUMS`, `neutrodyne-update.json` and attestations.
- [ ] Design documents updated for deviations.
