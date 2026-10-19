# M10 W1 report

Branch impl/m10-p1; local commit only as requested. No push, PR, CI, review rounds
or merge. Initial status at a84c7f10 was clean; existing context was preserved.
No helpers, dependencies, schema/entity/index changes or production success stubs.

Interfaces: ArtworkColors(key, version, seedArgb?, avgArgb?) and domain
ArtworkRepository.observeColors(key, fallbackPodcastId?): Flow<ArtworkColors?>,
with FakeArtworkRepository; ArtworkPixelSampler.sample(Image): ArtworkPixels and
ExtractedArtworkColors; ordered MosaicSnapshot; appearance defaults (tint true,
black false, density MEDIUM=100 dp, choices 72/100/152), device-only panel-hidden
false; PlayerPresentation/window-dp posture; screenshot case IDs/PR-FULL types.
The ownership map freezes future descriptor/extractor, player callbacks and
shared-art scope/key signatures with ONE implementation owner each.

Files changed: contracts/settings/tests in core:model, domain interface in
core:domain, fake/screenshot contracts/tests in core:testing, pixel/result contract
and buffer tests in core:artwork, design 06/08/09 and these two context records.

Dated owning-document notes cover adopted resolutions 1–10. They reconcile
monochrome versus pending/fallback, transparent completion, status-bar inversion
and the existing platform boundary, content-width arithmetic/obsolete directive
spike, Android-only mosaics, Android pseudo-locales/desktop forced RTL, PR versus
final FULL acceptance, explicit duplicate coverage/image caps, M10 benchmarks
versus M11b profiles/PB3 wording, and optional fonts/illustrations/platform defaults.
Pure black includes surfaceDim; existing navigation/Coil deviations stay intact.

MCU 5.0.1 published source/JVM API verified; probe results: grey/empty score no
seed with null fallback; unfiltered transparent red scores opaque red, requiring
caller alpha filtering. SchemeContent accepts optional specVersion/platform;
its default is 2021, MaterialDynamicColors internally uses 2025 (W3 role-mapping
integration consideration). Source jar SHA-256:
c06749aa620702c9fe419b2aa87d4ff2ad28cb6fa1fa5efa72a67f370ba60bb1.

Validation used the required JDK 21/Android/gfx environment and --no-daemon,
-Pkotlin.incremental=false, -Pkotlin.compiler.execution.strategy=in-process.
spotlessApply ran alone first; tests ran together with --continue.

| Test task | Passed | Failed/errors/skipped |
|---|---:|---:|
| :core:model:desktopTest | 2559 | 0 |
| :core:domain:desktopTest | 3 | 0 |
| :core:testing:desktopTest | 100 | 0 |
| :core:artwork:desktopTest | 27 | 0 |
| :core:artwork:testAndroidHostTest | 52 | 0 |

2741 test executions, including 13 new distinct tests / 15 target executions.
Documentation: checkdocs 0 problems/14 files; mmdcheck 0 invalid/78 diagrams.
checkBannedApis was clean; :app:compileDebugKotlin and
:desktopApp:compileKotlin both passed. git diff --check was clean.

Production extraction/backfill/schemes/mosaics/adaptive UI/captures/benchmarks and
hardware evidence remain W2–W11/MD1/MD4 scope. W1 claims contract/fake proof only.
