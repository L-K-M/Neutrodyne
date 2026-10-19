# M7 discovery evidence

Local integration, 2026-10-19, Linux x86_64. Baseline `c23d7140` plus the
W8 changes on `impl/m7-p1`. No push, PR, CI dispatch, review or merge was
authorized. M7 remains open until the owner/device rows below are supplied.
This ledger maps [PLAN M7](../PLAN.md#m7-discovery) to concrete tests rather
than treating compilation or an empty green task as execution evidence.

## Acceptance ledger

Test names below are exact class/method selectors. Local task counts and
command logs are recorded in `.nd-context/m7-w8-report.md`. All directory
requests in tests use fixtures, fakes or MockWebServer. PI fixture data is
spec-authored, not a live authenticated recording.

| AC | Automated evidence | Remaining manual or owner evidence |
|---|---|---|
| 1 | `SearchRepositoryTest.recordedNewsFusesProvidersAndAnnotatesAliasesWithoutWrites`, `partialAndAllProviderFailuresFinishWithoutLeakingErrors`; `DirectoryParsersTest` rejects missing/invalid feed URLs | Current provider availability and terms in O3 |
| 2 | `DirectoryInfrastructureTest.rollingWindowIsSharedAndConcurrentCallsDoNotQueue`; `SearchRepositoryTest.searchLookupAndChartsShareRollingLimiterAndCooldown`; `DiscoveryViewModelTest.debounceEmitsAt600ButNot599`, `directoryExplicitShortQueryIsNotDebouncedAndIgnoresGenre`; `DiscoveryScreenTest.enterAndImeOpenFullDirectoryEvenForShortQuery` | None for deterministic limit/debounce behavior |
| 3 | `AddDiscoveryTest.alternateFeedResolvesAndNeverWritesBeforeSubscribe`, `appleOnlyAnchorUsesSharedLookupAndArtworkFallback`, `wordpressBlogFeedFallsBackToPodcastFeed`, `multipleFeedsProduceChooserAndSelectedFeedCanBeResolvedAgain` | None for the four recorded shapes |
| 4 | `DiscoveryIntentTest`, `DiscoveryManifestTest`; E1 `SubscribeJourneyTest.sharedAppleLinkResolvesWithoutWritingUntilConfirmation` | E1 local device result in D1; real browser/domain/chooser in D2 |
| 5 | `SearchRepositoryTest.verifyBeforeReplacementUserOverridesBuildAndClearFallsBack`, `revokedKeyFailsOnlyIndexAndKeyLossDisablesIt`; existing SecretStore suites; `DiscoverSettingsViewModelTest` | Real Keystore loss, Windows DPAPI user binding and packaged POSIX permissions in O2 |
| 6 | `DiscoveryScreenTest.disclosureListsEffectiveRecipientsAndPartialFailureIsVisible`; `PrivacyInventoryParityTest`; Android/desktop `DiscoveryScreenshotTest` | TalkBack/screen reader and network-host captures in D3/O1 |
| 7 | `DiscoveryIntentTest`, `DiscoveryManifestTest`; E1 `SubscribeJourneyTest.directFeedSchemeWaitsForSubscribe`, `externalSubscribeWrapperWaitsForSubscribe`, `sharedTextWaitsForSubscribe` | E1 local device result in D1; browser and installed-app delivery in D2 |
| 8 | Shared `DirectoryReplayTest.appleSearchAndLookup`, `modernAndLegacyChartOrder`, `fyydAndIndexFields`; `DirectoryParsersTest`; `SearchRepositoryTest.recordedNewsFusesProvidersAndAnnotatesAliasesWithoutWrites` | Live PI BYOK and wider country/genre coverage in O3 |
| 9 | Shared `DiscoveryHtmlReplayTest.allRecordedCandidatesMatch`, `htmlReadStopsAtTwoMiB`; `AutodiscoveryTest`; `AddDiscoveryTest.htmlTransportStopsAtTwoMiBBeforeDelayedTailAndFeedsKeepTheirFullLimit` proves the transport cap, not just a parser cap | None for offline bounded reads |
| 10 | Same parser assertions on `:feeds:desktopTest` and `:core:data:testAndroidHostTest`; same HTML assertions on `:core:data:desktopTest` and `testAndroidHostTest`; device `DiscoveryFixtureDeviceTest`; desktop `DiscoveryJourneyTest.addConfirmationWritesEpisodesAndMembership`, `previewConfirmationWritesEpisodesAndMembership` | Real Android replay result in D1; Windows/macOS UI evidence in O1 |

## Integration and CI execution

Both `AndroidAppGraphTest` and `DesktopAppGraphTest` check discovery,
search/overall/genre directories, preview, Add, Discover settings and Privacy
entries through the production Metro installers, including their pane/sheet
metadata and unopened database gate. Desktop journeys use the production
transport/parser/subscribe pipeline and TestDb, assert no pre-confirmation
rows, then assert episodes and selected group membership. Preview expiry
forces a second publisher fetch. The M2 group journey now enters via
Library → + New group; All groups is intentionally hidden while no groups
exist (08). W4 replaced the former first-group
placeholder with category suggestions, so waiting for the removed card was
stale test behavior. Its membership/filter/play/reorder/delete/undo assertions
remain intact.

Shared assertions live under `feeds/src/replay/kotlin/`; JSON/HTML assets stay
in `feeds/src/test/resources/discovery/`. Desktop, Android host and Android
device loaders call the same assertions. The device APK packages fixtures
only as test assets. Both Discover and podcast preview have loading, error
and content goldens under their owning feature's `screenshots/android/` and
`screenshots/desktop/`, using fixed inputs, fake artwork and a frozen frame.
Android screenshots are Robolectric evidence, not device accessibility proof.
Suggested-groups goldens remain in `core/ui/screenshots/` from W4.

`scripts/ci/assert-discovery-tests.py` rejects missing, skipped, failed and
duplicate cases. PR unit CI checks host XML and uploads it even on success;
API 26/36 instrumented CI requires all four E1 and five replay cases per
device. Nightly requires the same on API 26/33/34/36 and API 37; the optimized
release smoke annotation includes these cases on API 36/37. YouTube-only
nightly runs intentionally do not claim discovery evidence. The Apple HTTPS filter explicitly sets `autoVerify="false"`: release lint
requires the explicit value, and the manifest regression pins it without
requesting domain verification. No workflow was dispatched locally. Release compilation/execution is still CI evidence to
obtain, not inferred from the debug compile.

## Outstanding evidence

Each completed manual row must record OS/API, build SHA, exact action,
expected/observed result, attachments and executed/non-skipped count (or
explicitly manual). Do not close a row from an unrelated green CI job.

| ID | Status | Required evidence |
|---|---|---|
| D1 | Open, local emulator unhealthy | Four E1 cases plus five shared device replay cases. W7's API 30 attempt hit a system-server ANR before assertions; no E1 pass was claimed. W8 tried an isolated API 30 AOSP x86_64 software emulator without KVM for about 13 minutes. Boot never completed and `service check package` returned `not found`; installation/instrumentation was unavailable. The emulator was stopped. W8 executed 0 of 9 device cases, with 0 passing and no skip claim. Health and logcat evidence is retained under `.nd-context/m7-w8-emulator-*`. CI still needs API 26/36, nightly APIs and optimized release XML. |
| D2 | Open, owner/device | Browser Share of Apple URL, cold and warm VIEW/SEND, API 31+ open-by-default approval, custom-scheme chooser with another podcast app, OEM delivery. Use both a no-Google-services and certified device. Explicit-component tests do not prove these actions. |
| D3 | Open, owner/device | TalkBack, keyboard/Enter/Escape/Tab and desktop screen-reader usability. Linux desktop automated keyboard tests cover only their asserted interactions. |
| O1 | Open, owner/platform | Android/desktop discovery network-host captures per 09; Windows/macOS/arm64 UI and parser replay. The desktop cross-OS nightly matrix remains unimplemented. |
| O2 | Open, owner/platform | Real Android Keystore loss and re-entry, Windows DPAPI user binding, packaged macOS/Linux 0600 permissions. Existing earlier-milestone evidence gaps remain open. |
| O3 | Open, owner/provider | Apple directory-use permission (L3), fyyd terms/rate limits, PI live BYOK/auth and written permission before any embedded release keys (PO-3 B remains keyless/BYOK). More country/genre availability; fixture provenance lists the limited live checks. |
| O4 | Open, owner/release | Milestone DoD, device acceptance, tester build and immutable release. This local task authorizes no publication. |

## Local verification

The full requested gate ran once with `--continue` and screenshot verification:
**6,991 passed, 3 skipped, 0 test failures/errors** across 56 leaf test tasks.
The only gate failure was release lint requiring an explicit Apple HTTPS
`autoVerify` value. The updated manifest regression failed on the old manifest;
after setting false, all 148 app unit tests, Android test compilation, both
app compilations, release lint, formatting, module graph and banned-API checks
passed in the affected rerun. That rerun validates the manifest-only fix;
unaffected full-suite results remain applicable.

Mutation evidence: `:feeds:jvm:mutationTest` 38 passed,
`:core:data:mutationTest` 1 passed, `:youtube:api:mutationTest` 1 passed;
`:sync:protocol:mutationTest` discovered no matching cases and contributes
no executed mutation evidence. The 40 mutation cases are included in 6,991.
The three skips are the Linux run's Windows-only DPAPI round-trip and the two
existing native File-menu accelerator cases. No discovery case was skipped.

The host XML evidence gate confirms 26 required cases, 0 skipped; its own four
Python regression tests pass. Twelve Android/desktop goldens were recorded,
visually inspected on representative states, and verified. Both documentation
checks report 0 problems and 0 invalid of 78 diagrams. A supplementary link
check including this ledger, HANDOFF and fixture provenance reports 0 problems.
Per-task counts and command logs are in `.nd-context/m7-w8-report.md`.
Device runtime remains D1's explicit 0 executed / 9 not run.
