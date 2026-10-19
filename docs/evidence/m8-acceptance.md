# M8 external YouTube acceptance

Local integration, 2026-10-19. M8 remains open. No publication, CI, external
review, device execution or main merge is claimed. The parent owns publication
and PR review. Current shells bind external-only implementations regardless of
packaged self-test scaffolding, with no engine start or stream resolution.

## Acceptance ledger

| AC | Implemented behavior and deterministic tests | Outstanding evidence |
|---|---|---|
| 1 | `YouTubeUrlClassifierTest`, `YouTubeIdsTest` and classifier mutation tests cover canonical URLs, variant prefixes, handles, Unicode and invalid identities | Latest-candidate CI/review and real Unicode YouTube shares |
| 2 | `youtube:impl` resolver replay covers reconstructed handle/oEmbed HTML, headers, redirect/consent failure, byte caps and timeout/cancellation; graph journeys use the same application clients | Fresh successful handle/oEmbed recordings; actual channel-head median and metered-avatar budget. Prior consent capture is not a successful channel capture |
| 3 | `YouTubeSourceAdapterTest`, `YouTubeIngestRegressionTest`, both platform date-provenance suites and controlled journeys cover author title, video GUID, published-only, Shorts and INITIAL/no-new/no-download | Android device execution of the graph journey |
| 4 | `YouTubeHealthTest`, `YouTubeOutageIntegrationTest` and E8 UI banner/retry assertions cover controls, cached checks, bounded probes, backoff and badge suppression | Actual application-client control/automatic-refresh captures on both platforms; successful scheduled canary. Scheduled mutation failure #211 remains independently open |
| 5 | Android `ExternalYouTubeModeTest` uses the application graph and test-only HTTP/browser seams, semantics, failed/successful open, Undo, no queue/download, all-external and mixed-group selection, settings reason and one banner | API26/33/34/36/37, optimized release, ARMv7/arm64/x86_64; physical browser/YouTube app/no-handler, warm/cold shares and TalkBack |
| 6 | Import parser and durable import tests cover LibreTube memberships, Takeout RFC4180/ZIP, NewPipe services and limits. Graph Add/import journeys replay a synthetic LibreTube document through the application client and repository | Owner's current localized Takeout ZIP and grouped LibreTube export; Neutrodyne NewPipe export imported into both NewPipe and LibreTube |
| 7 | `YouTubeSourceAdapterTest` and ingest regressions preserve scrolled-out state across ten windows, raw variant absence floors, sticky Shorts and unchanged metadata | Latest-candidate CI/review |
| 8 | Recorded shared-handle Add waits for confirmation, writes canonical identity and chosen group, performs INITIAL refresh and rejects queue/download. Suggestion searches use enabled directories and channel title, never the handle | Android device execution; real share targets and title-query network captures |
| 9 | Desktop `ExternalYouTubeModeTest` uses the real shell graph, captured opener, routes, settings reason, durable import lane and mixed-group selection | Packaged Windows x64, macOS arm64, Linux x64/arm64, actual browser policies and screen-reader traversal. Desktop audio remains MD1 |

## Verification receipts

Verification is in progress at this checkpoint. Commands, final SHA, task status
and counts will be recorded here before the local commit is handed back.

## Provenance and network evidence

`youtube/impl/src/test/resources/recorded/PROVENANCE.md` identifies reconstructed
success HTML/oEmbed shapes and the unsuccessful 2026-10-19 consent capture.
`feeds/src/test/resources/youtube/m8-atom.xml` is synthetic. The acceptance
LibreTube document and empty directory replies are synthetic. None is a live
capture. The same fixtures enter the desktop test resources and Android test
APK assets, never ordinary app source sets.

The replay interceptor captures requests after the real client's User-Agent
interceptor, asserting fixed `SOCS=CAE=`, locale and page/feed requests. It
rejects unplanned requests and opens no external socket. Image loading is faked
for deterministic UI, so it provides no image-host or visual-quality capture.
Live captures of automatic refresh, controls, title-based RSS suggestions and
all avatar/banner hosts remain missing on both platforms. Known destinations
are disclosed in [Privacy](../../PRIVACY.md) and the existing
[network inventory](../design/09-quality-and-release.md#network-inventory).
The stdlib feed canary is explicitly not an application-client capture.

## Adopted behavior

Both shells remain external. Primary variant 404/410 tries canonical first;
failed fallback keeps its real diagnostic. Gap pull-in spans the triggering
success and two subsequent successes while preserving failure/deferred floors.
Generic Atom fallback is unchanged; YouTube uses published provenance, sticky
Shorts and a normalized channel-author digest. Global outage suppresses badges
without deleting subscriptions. Past-live toggles affect future polls; old live
rows remain visible because external mode has no per-row live provenance.
Imports persist ordinal input/variant hints, use canonical identity and lazily
upgrade unfinished sessions. Layer A fixtures stay outside the engine tree.
Only one feed-only report canary exists in this milestone.
