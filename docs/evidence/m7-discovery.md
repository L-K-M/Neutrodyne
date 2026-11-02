# M7 discovery evidence

This ledger maps [PLAN M7](../PLAN.md#m7-discovery) to the concrete tests on
`impl/m7-discovery-current`, so that compilation and an empty green task are
never read as execution evidence. Test names below are exact class/method
selectors. Every directory request in these tests is a fixture, a fake or a
MockWebServer exchange; Podcast Index fixture data is spec-authored, not a
live authenticated recording ([fixture provenance](../../feeds/src/test/resources/discovery/README.md)).
The CI runs that executed these suites are listed in the parent's integration
report `~/nd-wt/reviews/m7-current-final.md`; this file cannot name the run of
the commit that contains it.

## Acceptance ledger

| AC | Automated evidence | Remaining manual or owner evidence |
|---|---|---|
| 1 | `SearchRepositoryTest.recordedNewsFusesProvidersAndAnnotatesAliasesWithoutWrites`, `partialAndAllProviderFailuresFinishWithoutLeakingErrors`; `DirectoryParsersTest` rejects hits without a usable feed URL | Live provider availability and Apple's terms (O3) |
| 2 | `DirectoryInfrastructureTest.rollingWindowIsSharedAndConcurrentCallsDoNotQueue`; `SearchRepositoryTest.searchLookupAndChartsShareRollingLimiterAndCooldown`; `DiscoveryViewModelTest.debounceEmitsAt600ButNot599`; `DiscoveryScreenTest.enterAndImeOpenFullDirectoryEvenForShortQuery` | None for the deterministic limit and debounce behaviour |
| 3 | `AddDiscoveryTest.alternateFeedResolvesAndNeverWritesBeforeSubscribe`, `appleOnlyAnchorUsesSharedLookupAndArtworkFallback`, `wordpressBlogFeedFallsBackToPodcastFeed`, `multipleFeedsProduceChooserAndSelectedFeedCanBeResolvedAgain` | None for the four recorded shapes |
| 4 | `DiscoveryIntentTest`, `DiscoveryManifestTest`; `SubscribeJourneyTest.sharedAppleLinkResolvesWithoutWritingUntilConfirmation` (device) | Browser Share and API 31+ open-by-default approval on a device (D2) |
| 5 | `SearchRepositoryTest.verifyBeforeReplacementUserOverridesBuildAndClearFallsBack`, `revokedKeyFailsOnlyIndexAndKeyLossDisablesIt`; the `SecretStore` suites; `DiscoverSettingsViewModelTest` | Real Keystore loss, Windows DPAPI user binding, packaged POSIX permissions (O2) |
| 6 | `DiscoveryScreenTest.disclosureListsEffectiveRecipientsAndPartialFailureIsVisible`; `PrivacyInventoryParityTest`; `DiscoveryScreenshotTest`/`PreviewScreenshotTest` | TalkBack and screen readers (D3); network-host captures (O1) |
| 7 | `DiscoveryIntentTest`, `DiscoveryManifestTest`; `SubscribeJourneyTest.directFeedSchemeWaitsForSubscribe`, `externalSubscribeWrapperWaitsForSubscribe`, `sharedTextWaitsForSubscribe` (device) | Browser and other-app delivery on a device (D2) |
| 8 | `DirectoryReplayTest.appleSearchAndLookup`, `modernAndLegacyChartOrder`, `fyydAndIndexFields`; `DirectoryParsersTest`; `SearchRepositoryTest.recordedNewsFusesProvidersAndAnnotatesAliasesWithoutWrites` | Live BYOK Podcast Index and wider country/genre coverage (O3) |
| 9 | `DiscoveryHtmlReplayTest.allRecordedCandidatesMatch`, `htmlReadStopsAtTwoMiB`; `AutodiscoveryTest`; `AddDiscoveryTest.htmlTransportStopsAtTwoMiBBeforeDelayedTailAndFeedsKeepTheirFullLimit` (the transport cap, not just the parser cap) | None for the offline bounded reads |
| 10 | The same parser assertions in `:feeds` `desktopTest`, `:core:data` `desktopTest` and `:core:data` `testAndroidHostTest`; device `DiscoveryFixtureDeviceTest`; desktop `DiscoveryJourneyTest.addConfirmationWritesEpisodesAndMembership`, `previewConfirmationWritesEpisodesAndMembership` | Windows and macOS UI evidence (O1) |

## Where the evidence runs

- **Host gate.** `scripts/ci/assert-discovery-tests.py` runs in `ci.yml`'s
  `unit` job over the aggregated test-results XML and refuses a missing,
  skipped, failed or duplicated case: the shared replay in `:feeds`
  `desktopTest` and `:core:data` `testAndroidHostTest`, the HTML replay in
  `:core:data` `desktopTest` and `testAndroidHostTest`, the desktop journeys
  and both graph tests, and the loading/error/content screenshot surfaces of
  `:feature:discover` and `:feature:podcast` on desktop and Android host. The
  unit job uploads the XML on success as well as on failure. The script's own
  regression suite (`scripts/ci/tests/assert-discovery-tests.test.py`) runs in
  `ci.yml`'s `static` job.
- **Device gate.** The same script with `--device` runs in `ci.yml`'s
  `instrumented` job (API 26 and 36 GMDs) and in `nightly.yml`'s
  `instrumented-full` GMDs (API 26/33/34/36), the API 37 debug leg, the API 37
  release smoke and the API 36 release smoke, requiring the four share and
  deep-link journeys of `SubscribeJourneyTest` and the five shared replay cases
  of `DiscoveryFixtureDeviceTest` per device. Both classes carry `@ReleaseSmoke`
  and neither carries `@Nightly`, so every nightly lane executes them.
- **Suggestion surfaces.** `SuggestedGroupsTest`, `GroupsManageViewModelTest`
  and `PodcastRepositoryTest` cover the suggestion query and its writes; the
  card has desktop and Roborazzi goldens in `core/ui/screenshots/`.

## Recorded screenshots

The M7 goldens (`core/ui/screenshots/*/suggested_groups.png`,
`feature/discover/screenshots/*`, `feature/podcast/screenshots/*`) are verified
in the unit job with `-Proborazzi.test.verify=true`. The suggested-groups
desktop golden was recorded with English labels while the desktop test JVM
defaults to de-DE (09's test conventions); the test now pins `Locale.ENGLISH`
around its capture, like `DiscoveryScreenshotTest` and `SettingsScreensTest`,
and the golden verifies unchanged (the CI comparison image showed only the
text rows as different). No golden was regenerated: the `record-screenshots`
workflow was not needed and was not run.

## Outstanding evidence

Each completed manual row must record OS/API, build SHA, exact action,
expected and observed result, attachments and an executed/non-skipped count
(or state that it is manual). An unrelated green CI job never closes a row.

| ID | Status | Required evidence |
|---|---|---|
| D1 | Closed on the device lanes, open on hardware | The four share and deep-link journeys pass on the API 26 and API 36 GMDs and on the desktop JVM. Their first device runs failed on API 26 at the post-preview subscribe step: the add sheet scrolls in the smaller API 26 window, so the click on the still off-screen Subscribe button did nothing. The journeys now scroll the checkbox and the button into view before clicking (the pattern every other screen test uses), and the wait reports the button's clickability and the visible texts if a write ever fails to land. Delivery from a real browser share and OEM quirks stay in D2. |
| D2 | Open, owner/device | Browser Share of a podcasts.apple.com URL, cold and warm VIEW/SEND delivery, the API 31+ open-by-default approval, custom-scheme handling with another podcast app installed, OEM delivery quirks. Explicit-component tests prove none of these. |
| D3 | Open, owner/device | TalkBack, keyboard (Enter/Escape/Tab) and desktop screen-reader usability of Discover, the directory list, the preview and the add sheet. |
| O1 | Open, owner/platform | Discovery network-host captures per 09; Windows/macOS UI and parser replay on those hosts. |
| O2 | Open, owner/platform | Real Android Keystore loss and re-entry, Windows DPAPI user binding, packaged macOS/Linux `0600` permissions. |
| O3 | Open, owner/provider | Apple directory-use permission (risk L3), fyyd terms and rate limits, a live Podcast Index BYOK pair, and written permission before any embedded release key (PO-3 default B stays keyless). Fixture provenance lists the limited live checks that were made. |
| O4 | Open, owner/release | Milestone definition-of-done, device acceptance and the tester build for M7. |

## Known device-lane behaviour

- `SmokeTest.licencesListsLibraries` waits 10 s for the AboutLibraries list to
  load and has run near that budget on the API 26 GMD for a long time: it
  passed at 17.5 s total on main's `main-3e3e` run and failed earlier on
  `pr253-d907` with an unrelated Compose measure crash. M7's two new Settings
  rows (Discover, Privacy) put the Licences row near the bottom of the small
  API 26 window, where a click on an out-of-view row does nothing, so the row
  is now scrolled into view before the click like the other screen tests. The
  10 s load budget itself is unchanged; treat a lone failure of this case as
  the known margin unless a change to the Licences screen accompanies it.
- The `ci` GMD group runs its API 26 and API 36 emulators concurrently on one
  hosted runner, which roughly halves each device's throughput; the device
  budgets assume that load.
