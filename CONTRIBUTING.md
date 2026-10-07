<!-- SPDX-License-Identifier: Unlicense -->

# Contributing to Neutrodyne

Thanks for helping! This file collects the rules that apply to every pull
request. `docs/PLAN.md` is the source of truth; the design documents in
`docs/design/` elaborate it. Record any deviation from a design document in the
owning document's section (and in `docs/PLAN.md` for D-ids).

## Process

- Work goes through pull requests against `main`; CI (`ci.yml`: `static`,
  `unit`, `assemble`, plus `desktop-smoke` and `server` from M0b) must be green.
- Keep changes focused. Match `docs/design/` names exactly (types, functions,
  packages, files); where reality differs, pick the most faithful working
  alternative and record the deviation.
- Every source file starts with `// SPDX-License-Identifier: Unlicense` (`#` in
  scripts/YAML/properties, `<!-- -->` in XML). `commonMain` never imports
  `java.*`, `javax.*` or `android.*`; `checkBannedApis` enforces it.
- A PR changes one thing. Drive-by formatting of unrelated files is not merged;
  `spotlessCheck` keeps the codebase formatted.

## Copied code and contributions

The rule below is binding (01's "Copied code and contributions"):

1. **Behaviour-only reuse.** Never copy code from GPL or AGPL projects
   (AntennaPod, the NewPipe app, NewPipe Extractor, LibreTube, Podcini,
   youtubedl-android, Seal, YTDLnis, mpv's skip-silence scripts, GPL sync
   servers) or MPL projects (Pocket Casts) into any module, and never copy LGPL
   code into our source either: LGPL components are used only as the separately
   linked libraries [D3](docs/PLAN.md) lists. There is no exception.
   Re-implement behaviour from documentation and design notes.
2. **Clean-room areas.** The gpodder and Open Podcast API compatibility layers
   (v1.x) are written from their published documentation and recorded traffic
   only, by people who have not read GPL or AGPL server or client sync code.
   Desktop skip silence is a port of Media3's Apache-2.0 silence-skipping
   processor, never of a GPL mpv script.
3. **Permissive and public-domain code** (Apache-2.0, MIT, BSD, ISC, CC0,
   Unlicense — e.g. nav3-recipes, Media3's silence skipping, Sonic,
   `rocicorp/fractional-indexing`) may be copied or ported only with its
   original copyright header and `SPDX-License-Identifier` line kept (or a
   credit header for a port), plus an entry in `THIRD_PARTY_NOTICES.md`. yt-dlp
   is Unlicense: porting its logic (for example the Kotlin InnerTube fallback
   of D72) is allowed, with a credit header in each ported file ("Ported from
   yt-dlp `<path>` at `<commit>`, Unlicense") and an entry in
   `THIRD_PARTY_NOTICES.md`. Specification text under CC BY-SA (the Open
   Podcast API) is linked, never pasted into code or documents.
4. All contributions are dedicated under the Unlicense.

The PR template's licence checkboxes restate this: no code copied from GPL,
AGPL, LGPL or MPL projects; copied or ported permissive or Unlicense code keeps
its header (or credit line) and is listed in `THIRD_PARTY_NOTICES.md`; a new
bundled component (Gradle, Python, native or runtime) has its lockfile or
Licensee entry, its Licences entry and its notice.

## Licences (D3)

The repository is Unlicense. A new dependency or bundled component is
permissive, LGPL dynamically linked with its notices met and its exact source
attached to the release, MPL-2.0 only as unmodified files or data, MS-RL only
as the unmodified WiX components jpackage embeds in the MSI with the WiX source
attached, or part of the unmodified OpenJDK runtime under the runtime
exception. **No GPL or AGPL code or dependencies anywhere** — Gradle, Python or
native. Its Licensee allow-list or lockfile entry (`python-components.lock`,
`native-components.lock`, `runtime.lock`), the Licences screens and
`THIRD_PARTY_NOTICES.md` move in the same PR.

## Testing rules (09)

A PR is not mergeable without the tests 09's "Test obligations per change"
table assigns to the kind of code it touches — the PR template repeats them:

- Bug fixes include a failing-first regression test.
- Shared code is tested in `commonTest`, which runs on the desktop JVM
  (`./gradlew :<module>:desktopTest`), and on Android where 09 requires it.
- Parsers/writers get golden fixtures and hostile-input cases; schema changes
  get a version bump, an exported JSON, a migration and a migration test;
  interfaces get fakes plus contract tests; ViewModels get Turbine tests of
  `uiState`; UI gets Compose behaviour tests and Roborazzi captures.
- No automatic test retries in CI; flaky tests are fixed, not retried.
- No MockK in `commonTest` or `androidTest`; the fake catalogue lives in
  `:core:testing`.
- Release and verification code (`release.yml`, `scripts/ci/*.sh`) gets a
  negative test per rejection reason: run `check-update-json.sh` on the changed
  output in the PR.

## Commits and releases

- Version numbers live only in `gradle.properties`; `scripts/release.sh` is the
  only writer (09's version scheme, S = 95). Release notes go into
  `changelogs/<versionCode>.txt` (≤ 500 chars, English, user-facing).
- Releases are normal, immutable GitHub releases — never pre-releases.
