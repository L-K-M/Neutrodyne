# v1.0 candidate acceptance

Status: candidate preparation, 2026-11-11. Version 1.0.0, Android version
code 1000095. Runtime implementation base:
`1b9c276a89bb2035f28cdef621a7e314d5acc067`. The versioned APKs and DMG
were built at `021bf89afb4c226784e6b0265fa2af5772b3d1de`; packaging scripts
from [#508](https://github.com/L-K-M/Neutrodyne/pull/508) are integrated at
`fdd9cb5c6de1fb47e0c8a309c077d014bebdfd77`. No release tag or production
deployment has been made. First engine-approval publication is queued.

## Owner decisions

- Required GitHub CI is waived. Actual failed, missing or skipped checks
  remain recorded with those outcomes.
- Two consecutive automated-review integration failures may use the owner's
  standing review waiver. A waived review is not a passing review.
- The remaining acceptance uses simulators and hosted virtual machines at
  the owner's direction. Physical reference-device timings, radios,
  peripherals and Raspberry Pi measurements are not claimed.
- PO-48 admits the two named unmodified source exceptions with their exact
  sources attached. PO-10 selects `neutrodyne@lkmc.ch` for consented reports.
  PB27 permits 500 MB installed and 200 MB downloaded per desktop target.
  These earlier decisions are recorded in [PLAN](../PLAN.md).

## Source-pinned evidence

| Scope | Result | Source and receipt |
|---|---|---|
| Android 14 recreation | The final activity-lifetime regression fails with the original activity context and passes using the application context. Ten cases pass on each of API 34 and API 36 ARM64 emulators. StrictMode detection and fatal handling are unchanged. | [#506](https://github.com/L-K-M/Neutrodyne/pull/506), tested head `8482ab677d2c3c30ba95008b52fc3bc309372704` |
| Android, desktop and server sync | Real Android, desktop JVM and server converge after 40 offline edits per client; held removals and persisted position are checked. | [E12](https://github.com/L-K-M/Neutrodyne/actions/runs/42925529282), `a8a0d6029`; a local repeat on runtime `b5324cfa` also passes. [Current main nightly](https://github.com/L-K-M/Neutrodyne/actions/runs/42994268514) passes sync-convergence on `a63458d4e`. |
| Desktop native matrix | Windows x64, macOS ARM64, Linux x64 and Linux ARM64 pass their native matrix jobs. | [Current main nightly](https://github.com/L-K-M/Neutrodyne/actions/runs/42994268514), `a63458d4e` |
| macOS 1.0 DMG | Real read-only-mounted DMG passes the image scan; extracted app resolves through bundled Python, plays recorded audio past 500 ms and downloads 22,020,219 bytes with exact ranges and digest. Child exit, AOT use, runtime source and deep signature checks pass. Installed size 377 MiB; package 183,732,525 bytes. | Artifact source `021bf89af`, verifier `fdd9cb5c6`; macOS 27.0.1 ARM64 on a Mac Studio M4 Max. These are not reference-laptop measurements. |
| Android 1.0 packages | All three ABI release APKs pass published-mode checks, including signing, non-debuggable flags, 16 KB alignment, notices and size limits. All 29 release smoke cases pass with per-case isolation, without skips. | Artifact source `021bf89af`; [API 36 ARM64 receipt](v1-release-smoke-api36.json) |
| Android install-over upgrade | Two real UI subscriptions, two episodes, a named group and its membership are unchanged in every database column after installing 1.0.0/code 1000095 over 0.1.0/code 10095 and launching normally. | Artifact source `021bf89af`; [API 36 ARM64 receipt](v1-upgrade-api36.json), synthetic loopback feed fixtures |
| Server 1.0 JAR | Actual packaged JAR completes migrate, doctor, then serve with HTTP 200 `ok` readiness. The owned process exits after the check. | Source `fdd9cb5c6`; [local JVM 21 receipt](v1-server-smoke.json), isolated temporary data and loopback interface |
| Room handoff | Five API 36 cases pass without skips. | [Hosted run](https://github.com/L-K-M/Neutrodyne/actions/runs/42926652919), `fd469826` |
| Benchmarks | Fifteen fully drawn startup samples, five cover-grid samples and 20 traces pass in the earlier emulator run. | [Hosted run](https://github.com/L-K-M/Neutrodyne/actions/runs/42924687426), `0ec25393`; timings are diagnostic. |
| Broader host suites | 11,745 host, 49 mutation and 89 shim passes; 43 host skips. Later scoped fixes have their own checks; this total was not rerun on the candidate. | Source `561faf18`; exact-head integration details are in the corresponding merged PRs. |
| Backup, process death, screenshots and server image | The current main nightly passes bmgr, API 37 16 KB, background-start, system-tests, screenshots, server-image-smoke, mutation and no-engine jobs. | [Current main nightly](https://github.com/L-K-M/Neutrodyne/actions/runs/42994268514), `a63458d4e` |

## Current validation gaps

The same main nightly remains failed. Its three API 34 debug recreation
deaths led to the verified activity-retention fix in #506. Its release
smoke executed 17 passing cases but missed the required playback and engine
cases. A fresh local minified release discovers all 29 expected cases;
all 29 then pass with the intended per-case package isolation on API 36 ARM64
at runtime `4bc613308`, version 0.1.0/code 10095. The actual 1.0.0 candidate
also passes all 29 cases with that isolation
([candidate receipt](v1-release-smoke-api36.json)).
The earlier single-process diagnostic executed 29 with four failures. All
four pass with per-case package isolation; the failed diagnostic remains
recorded and is not relabeled as a pass.

The cold-start benchmark failed because AndroidX could not associate a
render-thread frame with reportFullyDrawn; cover-grid completed. Server
convergence shard 7 exceeded its three-hour job limit while the other 15
shards passed. The live feed canary passed its five feeds, while both live
engine hosts reported RATE_LIMITED from YouTube. These outcomes remain
failed or incomplete; the CI waiver does not relabel them.

## Operational prerequisite

The owner provisioned `engine-approval`; its custom branch policy permits
only the branch `main`. The matching `NEUTRODYNE_ENGINE_MANIFEST_KEY` is an
environment secret, with no repository-scoped copy. Metadata and the
public-key match were checked on 2026-11-11. Private material remains out of
chat, logs and commits. [#507](https://github.com/L-K-M/Neutrodyne/pull/507)
corrected the public pin's raw-base64 format without changing its value;
43 canary tests pass, including the regression against the committed pin.

The first main-only [engine bootstrap](https://github.com/L-K-M/Neutrodyne/actions/runs/43083187019)
is queued. Hosted production signing and publication remain unverified
until that run completes. The candidate's [four-target package run](https://github.com/L-K-M/Neutrodyne/actions/runs/43088177382)
is also in progress or queued; local macOS acceptance does not replace its
Windows and Linux results. Repository release immutability requires owner
confirmation because the available credentials lack admin read access.

After the candidate's remaining checks and operational acceptance, the
release must contain the APKs, all desktop packages, server JAR, exact
sources, notices, checksums, update manifest and attestations described in
[the release checklist](../design/09-quality-and-release.md#release-checklist).
