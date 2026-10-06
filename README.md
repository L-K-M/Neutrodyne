# Neutrodyne

> [!IMPORTANT]
> LLM disclosure: This codebase was written with substantial help from large language models: AI coding agents working from the [`AGENTS.md`](AGENTS.md) brief in this repo.

Neutrodyne is an open-source podcast player for Android and the desktop (Windows, macOS, Linux), with an optional self-hosted sync server. It is organised around groups: a group such as "tech", "news" or "fiction" is a user-defined set of podcasts and YouTube channels, and every group is its own newest-first episode feed. Each app polls its feeds itself — there is no Neutrodyne-operated backend, no account at a third party and no tracking. Whoever wants their phones and computers in step runs Neutrodyne Sync on a machine of their own; it keeps subscriptions, groups and listening state, never audio or feed contents, and the apps work fully without it.

**Status: planning — no code yet.** The repository holds the master plan and eleven design documents. Implementation starts with milestone M0a ([roadmap](docs/PLAN.md#7-roadmap)); Android tester builds come from M0a, desktop tester builds from M0b and the sync server from MS1. Android, desktop and server ship together as v1.0.

## Headline features

| ID | Feature | Plan |
|---|---|---|
| R1 | Import and export of subscriptions: tolerant OPML import with preview and report, grouped OPML export, NewPipe/LibreTube/Takeout import, full backup and restore that moves a library between Android and the desktop, automatic Android backup (Android 9+ with a screen lock and a system backup transport such as Google backup or Seedvault) | [R1](docs/PLAN.md#21-functional-requirements), [05](docs/design/05-groups-opml-backup.md) |
| R2 | Groups, each with its own episode feed: many-to-many groups shown as swipeable tabs, with filters, counts and per-group defaults | [R2](docs/PLAN.md#21-functional-requirements), [05](docs/design/05-groups-opml-backup.md#group-feeds) |
| R3 | YouTube channels as podcasts: subscribe by link or handle; audio playback and downloads with the built-in yt-dlp engine (64-bit APKs and every desktop build), "Watch on YouTube" otherwise; the engine updates itself to verified yt-dlp releases without an app update | [R3](docs/PLAN.md#21-functional-requirements), [04](docs/design/04-youtube.md) |
| R4 | Streaming and downloading: background player with a database-owned queue, speed with pitch kept, skip silence, chapters, sleep timer, resumable downloads, auto-download and cleanup | [R4](docs/PLAN.md#21-functional-requirements), [06](docs/design/06-playback.md), [07](docs/design/07-downloads.md) |
| R5 | A cover-first UI: adaptive cover grid, group mosaics, artwork-derived colour, offline artwork for system surfaces; one Compose Multiplatform UI for both apps | [R5](docs/PLAN.md#21-functional-requirements), [08](docs/design/08-ui-ux.md) |
| R6 | Installing and updating from GitHub only: per-ABI Android APKs (optimised release builds signed with a key that is public in this repository), unsigned desktop installers, the server JAR and a container image on GitHub Container Registry, all with checksums and attestations; a daily update check in both apps that notifies and links to the new release; guidance for Android's developer verification and the desktop OS prompts | [R6](docs/PLAN.md#21-functional-requirements), [09](docs/design/09-quality-and-release.md#update-check), [11](docs/design/11-desktop.md#install-and-update) |
| R7 | Optional self-hosted sync: subscriptions (private feed links included), groups and their order, played state, positions, favourites, Up next, the now-playing episode and portable settings; devices linked with short one-time codes; "Continue on this device"; offline changes merge per field; a pull that would remove many podcasts asks first | [R7](docs/PLAN.md#21-functional-requirements), [10](docs/design/10-sync.md) |
| R8 | Desktop app for Windows, macOS (Apple silicon) and Linux: the same library, groups and screens as Android, its own audio engine, OS media controls (SMTC, Now Playing, MPRIS), tray while playing or downloading, keyboard and mouse throughout, per-user installation | [R8](docs/PLAN.md#21-functional-requirements), [11](docs/design/11-desktop.md) |

## How the documents are organised

[docs/PLAN.md](docs/PLAN.md) is the single source of truth. It holds the requirement IDs (R1–R8, N1–N13), the decision register (D-ids), the product-owner decisions with their defaults or resolutions (PO-ids), the roadmap with acceptance criteria per milestone (M0a–M11b for shared code and Android, MD0–MD5 for the desktop, MS0–MS3 for sync) and the risks. Where a design document and the plan disagree, the plan wins until it is amended. Raw research notes and change briefs are kept under `docs/research/`; they are snapshots, not the plan.

The design documents elaborate the plan; each owns its area and links to the others instead of restating them:

| Document | Covers |
|---|---|
| [01-foundation.md](docs/design/01-foundation.md) | Toolchain and version catalog, Kotlin Multiplatform modules and source sets, dependency rules, architecture patterns, DI (Metro), build variants (release and debug) and ABIs, networking baseline, platform compliance, licensing policy (Gradle, Python and native components), M0 scaffold and spikes |
| [02-data-model.md](docs/design/02-data-model.md) | Room 3 schema (Android and desktop), identity keys, indices, key queries, sync tables and capture triggers, invalidation hygiene, retention, migrations |
| [03-feeds-and-discovery.md](docs/design/03-feeds-and-discovery.md) | Feed parser, fetch pipeline, ingestion, refresh scheduling (WorkManager and the desktop runner), show notes, add-podcast flow, search, deep links, notifications |
| [04-youtube.md](docs/design/04-youtube.md) | Capability matrix, channel resolution, Atom ingestion, the yt-dlp engine and its two hosts (Chaquopy on Android, a CPython child process on the desktop), stream resolution, engine updates, licensing and hotfix process |
| [05-groups-opml-backup.md](docs/design/05-groups-opml-backup.md) | Groups, group feeds, effective settings, OPML import and export, backup and restore across platforms, restore while linked, Auto Backup |
| [06-playback.md](docs/design/06-playback.md) | The shared playback core, the Android Media3 service, URI resolution, streaming cache, queue and play context, positions, sleep timer, chapters, system surfaces, sync interplay |
| [07-downloads.md](docs/design/07-downloads.md) | Download engine, state machine, Android runners and desktop lanes, storage layout, auto-download, cleanup, reconciliation |
| [08-ui-ux.md](docs/design/08-ui-ux.md) | Information architecture, navigation, screens, player sheet, sync screens, theming and brand, artwork pipeline, adaptive layouts, keyboard and mouse, accessibility |
| [09-quality-and-release.md](docs/design/09-quality-and-release.md) | Testing, CI (Android, four desktop runners, server image), static analysis, versioning and signing (committed public keystore, release builds), GitHub-only distribution, update check (notify only), developer verification, reproducibility check (report-only), privacy, crash reporting, performance budgets |
| [10-sync.md](docs/design/10-sync.md) | Neutrodyne Sync: what syncs, identity mapping, protocol, conflict resolution with hybrid logical clocks, the client sync engine, linking and first merge, the Kotlin/Ktor server, authentication, deployment, security, testing |
| [11-desktop.md](docs/design/11-desktop.md) | The desktop app: platform matrix, shell and background work, OS integration, the FFmpeg-based audio engine, downloads and storage, the YouTube engine host, packaging and the runtime exception, install and update, desktop UX and accessibility |

## Install and update

Neutrodyne is distributed **only through GitHub Releases** (the server image through GitHub Container Registry) — there is no Google Play, F-Droid, winget, Homebrew, Flathub or other store or catalogue version, no mirror, and any other copy is not Neutrodyne's. Nothing is released yet; the first Android tester build comes with milestone M0a. On Android ([full guidance](docs/design/09-quality-and-release.md#readme-install-and-update), [developer verification](docs/design/09-quality-and-release.md#developer-verification)):

1. **Download** from the repository's releases page: `neutrodyne-{v}-arm64-v8a.apk` for most phones and tablets, `-x86_64.apk` for x86_64 devices, `-armeabi-v7a.apk` for older 32-bit phones (YouTube episodes then open in the YouTube app).
2. **Check it (optional):** Neutrodyne's APKs are release builds signed with a key that is public in this repository (`signing/neutrodyne-public.keystore`), so the signature doesn't prove who built a file. Download only from this repository's releases page and compare the file with the release's `SHA256SUMS`, or check it with the GitHub CLI:

   ```sh
   sha256sum --check --ignore-missing SHA256SUMS
   gh release verify-asset v{v} neutrodyne-{v}-arm64-v8a.apk -R {owner}/Neutrodyne
   gh attestation verify neutrodyne-{v}-arm64-v8a.apk -R {owner}/Neutrodyne
   ```

3. **About these builds:** the maintainers publish optimised release builds signed with a key that is public in this repository, so that no signing key has to be kept secret. That means the signature proves nothing: anyone can sign an APK that installs over Neutrodyne and takes over its data — your subscriptions, listening history, the passwords of private feeds and your sync server's device token — so download only from this repository's releases page. A later switch to a private key would need one reinstall, so keep a backup or link a sync server. Details: [public-key trade-offs](docs/design/09-quality-and-release.md#public-key-trade-offs), [D61](docs/PLAN.md#3-key-decisions).
4. **Allow the install** when Android asks whether your browser or Files app may install apps.
5. **Phones with Google Play, from Google's global rollout in 2027:** Android will install apps only from developers registered with Google unless you turn on a one-time setting, and Neutrodyne is not registered. Turn on Developer options › "Allow apps from unverified developers", follow the steps (restart, 24-hour wait, fingerprint or PIN) and choose **"indefinitely"** — with "7 days", updates stop working after a week. Each install or update then shows a warning; tap "Install anyway". Nothing changes before Google's global start. Phones without Google certification (GrapheneOS, LineageOS without Google apps, /e/OS) need none of this; `adb install -r` and Shizuku-based installers are power-user fallbacks that Google may close.
6. **Updates:** Neutrodyne checks GitHub once a day and, when a new version exists, notifies you and links to it (Settings › Updates › Check for updates, on by default; "Check now" works even when it is off). Its update card offers "Open release on GitHub" and "Download APK for this device" and shows that APK's SHA-256; download the APK in your browser and install it over the old one — your library stays. Neutrodyne never downloads or installs an update itself. Or use [Obtainium](https://github.com/ImranR98/Obtainium) with an APK filter for your file (e.g. `neutrodyne-.*-arm64-v8a\.apk$`) and turn Neutrodyne's own check off. YouTube engine updates arrive separately and need no install.
7. **Changing phones:** make a manual backup (Settings › Backup) and restore it on the new phone, or link both phones to your sync server (Settings › Sync); Android may restore your library when you install Neutrodyne there, but that is not guaranteed for apps installed outside a store ([R1.8](docs/PLAN.md#21-functional-requirements)).
8. **Android Auto:** enable "Unknown sources" in Android Auto's developer settings.

The same guidance is in the app under Settings › About › Install & updates.

### Install on Windows, macOS or Linux

Download the file for your computer from the latest release on GitHub — only from there:
Windows 10 22H2/11 (x64, also Windows 11 on Arm, which runs it emulated): `neutrodyne-{v}-windows-x64.msi` (per-user, no administrator rights) or the portable `.zip`; Mac with Apple silicon, macOS 13 or later: `neutrodyne-{v}-macos-arm64.dmg` (tester builds before 1.0.0: a `.zip` of the app); Linux x64 or arm64 with glibc 2.31 or later: `.deb`, `.rpm` or `.tar.gz`. Check it against `SHA256SUMS` or with `gh attestation verify <file> --repo {owner}/Neutrodyne`.

The desktop builds are not signed by a registered developer:

- **macOS:** after the first start of every newly installed or updated version, open System Settings › Privacy & Security and click "Open Anyway" (or run `xattr -dr com.apple.quarantine /Applications/Neutrodyne.app`).
- **Windows:** SmartScreen shows "Windows protected your PC" — choose "More info", then "Run anyway". Smart App Control must be off; it has no per-app exception.
- **Linux:** `sudo apt install ./<file>.deb`, `sudo dnf install ./<file>.rpm`, or extract the `.tar.gz`.

Updating means installing the newer file the same way; your library, settings and downloads stay, and uninstalling never deletes your library. The desktop app checks GitHub once a day while it runs and links to the right file for your computer; it never installs anything itself. Closing the window while playing or downloading keeps Neutrodyne in the tray or menu bar; closing it while idle quits. Screen readers work on macOS (VoiceOver) and Windows (NVDA, with Java Access Bridge turned on — the help explains how); **Linux screen readers are not supported** ([R8.10](docs/PLAN.md#21-functional-requirements)). The installers bundle an unmodified OpenJDK runtime and an LGPL FFmpeg; their sources are attached to every release. Details: [11 Install and update](docs/design/11-desktop.md#install-and-update).

### Run the server

Neutrodyne Sync keeps subscriptions, groups, played state, positions and Up next in step across your phones and computers. It is optional and self-hosted: it stores only that state — never audio or feed contents — and never fetches anything itself. It holds your listening history and private feed links without end-to-end encryption, so run it on a machine you control and put it behind TLS.

With Docker (Linux x86-64 or arm64): copy `compose.yaml` and `Caddyfile` from `sync/server/deploy/` at the release's tag, put your domain into both files and run `docker compose up -d`. The image is `ghcr.io/{owner}/neutrodyne-server:{v}`; Caddy obtains the TLS certificate. Without Docker: install Java 21 or later, download `neutrodyne-server-{v}.jar` from the release, check it against `SHA256SUMS`, and install `neutrodyne-server.service` (systemd) from the same folder; put Caddy or nginx in front for TLS (`nginx.conf.example` keeps live updates working). The server listens only on loopback unless its public URL is `https://` (behind the proxy) or it is started with `--insecure-lan` for a trusted home network.

At its first start the server prints a one-time setup code; open `https://<your domain>/setup` and enter it, or run `java -jar neutrodyne-server-{v}.jar admin create`. Create an account, then link your devices in Settings › Sync of each app with a short code.

Upgrade by replacing the image tag or the JAR; the server backs up its database before it migrates and refuses to start on a newer database. Copy `backups/` and `account-backups/` from its data directory off the machine regularly. The admin page tells you when a newer version exists (one daily request to GitHub; `NEUTRODYNE_SERVER_UPDATE_CHECK=false` turns it off). The image bundles an unmodified OpenJDK runtime and Debian base packages; their sources are attached to every release and published next to the image as `ghcr.io/{owner}/neutrodyne-server:{v}-sources`. Details: [10 Deployment](docs/design/10-sync.md#deployment).

## Licence

The repository — own Kotlin code, the Python shim, the native glue, scripts and documentation — is released into the public domain under the [Unlicense](LICENSE). Shipped third-party components follow [D3](docs/PLAN.md#3-key-decisions) ([PO-1](docs/PLAN.md#po-1-licensing-of-shipped-binaries), re-resolved 2026-10-05; [PO-48](docs/PLAN.md#48-further-product-owner-decisions)):

- **Permissive** components in every artefact (Apache-2.0, MIT, BSD, ISC, PSF, zlib, Unicode, public domain and similar; FreeType under the FTL, libpng, libjpeg-turbo under its IJG and BSD terms).
- **LGPL, dynamically linked, with its source attached:** the minimal LGPL-2.1 FFmpeg build that the desktop audio engine loads as separate, replaceable shared libraries (`ffmpeg-{ffmpeg}-neutrodyne-src.tar.xz` on every release), and glibc in the server image.
- **MPL-2.0 for unmodified files and data** (certifi's CA bundle, the Public Suffix List data), plus two named cases with their sources attached to each release: python-build-standalone's MPL-2.0 build patches in the desktop YouTube engine's CPython, and the unmodified WiX components (MS-RL) inside the Windows MSI.
- **The runtime exception:** the desktop installers and the server image bundle an unmodified, jlink-trimmed OpenJDK runtime (Temurin; GPL-2.0 with the Classpath Exception, the GCC runtime under its exception, Microsoft's VC++ redistributables), whose `legal/` notices are kept and whose exact source (`openjdk-{jdk}-temurin-sources.tar.gz`, `RUNTIME-SOURCES.md`) is attached to every release that ships it. The Android APKs and the server JAR contain no LGPL and no runtime.

**No other GPL and no AGPL code anywhere**, and nothing GPL is linked into or derived from our code. Licensee (Gradle), the Python component locks of both engine hosts, `native-components.lock`, the APK, desktop-image and server-image scans, the embedded-native inventory and `check-runtime-sources.sh` enforce this in CI ([01 Licensing and dependency policy](docs/design/01-foundation.md#licensing-and-dependency-policy)). Each app's Licences screen and `THIRD_PARTY_NOTICES.md` list every component with its full licence text.

Bundled as the YouTube engine — in the 64-bit APKs through Chaquopy, on the desktop through python-build-standalone: yt-dlp with yt-dlp-ejs (Unlicense; the solver adds meriyah, ISC, and astring, MIT); CPython (Python-2.0, the PSF licence stack) with OpenSSL (Apache-2.0), SQLite (public domain), libffi, expat, mimalloc and HACL* (MIT), mpdecimal (BSD-2-Clause), zstd (BSD-3-Clause), xz (0BSD), bzip2, zlib and the Unicode Character Database extract (Unicode-3.0); on Android the Chaquopy runtime (MIT; `libc++_shared` Apache-2.0 WITH LLVM-exception); a CA certificate bundle (MPL-2.0, unmodified data); and quickjs-kt with QuickJS (Apache-2.0, MIT) if the JS challenge provider ships.

This software uses code of FFmpeg licensed under the LGPLv2.1, and its source can be downloaded from each release page. Portions of this software are copyright © {year} The FreeType Project (www.freetype.org). All rights reserved. This software is based in part on the work of the Independent JPEG Group.
