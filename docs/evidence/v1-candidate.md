# v1.0 candidate acceptance

Status: candidate preparation, 2026-11-11. Version 1.0.0, Android version
code 1000095. Runtime implementation base:
`4bc613308eff0848c8cb72057a8e470efd594373`. No release tag, engine-approval
publication or production deployment has been made.

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
| macOS packaged media | Fresh local image resolves through bundled Python, plays recorded audio past 500 ms and downloads 22,020,219 bytes with exact ranges and digest; child exits and AOT cache is in use. Runtime source and deep signature checks pass. | Runtime base `a63458d4e`, macOS 27.0.1 ARM64 on a Mac Studio M4 Max, development package version 0.1.0; these are not reference-laptop measurements. |
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
([local receipt](v1-release-smoke-api36.json), runtime `4bc613308`, version
0.1.0/code 10095). The candidate version is being packaged separately.
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

The owner must provision the main-only `engine-approval` environment and
`NEUTRODYNE_ENGINE_MANIFEST_KEY`, matching the public key pinned in the apps.
The available repository credentials have push access but no admin access,
so environment provisioning cannot be completed with them. Key material
must stay out of chat, logs and commits. An ephemeral-key test does not
replace hosted production signing and publication acceptance.

After the candidate's remaining checks and operational acceptance, the
release must contain the APKs, all desktop packages, server JAR, exact
sources, notices, checksums, update manifest and attestations described in
[the release checklist](../design/09-quality-and-release.md#release-checklist).
