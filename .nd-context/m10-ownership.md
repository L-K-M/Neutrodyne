# M10 W1 ownership and integration

Frozen 2026-10-19. Each shared implementation has ONE owner. Consumers use the
core:testing fake until its implementation lands; send patches to its owner.

| Shared implementations | ONE owner |
|---|---|
| Pixel sampler/Android adapter; colour extractor; repository/fallback observation/DI; colour backfill/persistence and runner/store publication | W2 |
| MCU role mapper; BrandColors; artwork scheme LRU/theme; appearance registry/backup classification and shell collectors; header/player tint seam | W3 |
| Mosaic snapshot observer/descriptor builder/debounce/invalidation; Android renderer; Auto group art/notifications | W4 |
| SharedKeys/shared scope/shared-art modifier/frame harness; platform status-bar contrast/restoration | W5 |
| Root/presentation selector; window-dp posture adapter; panel callbacks; adaptive player controls/panel/tabletop/landscape | W6 |
| Grid density chooser/application; existing empty-state completion | W7 |
| Accessibility harness/action inventory/audit | W8 |
| Screenshot registry/runtime tiers/coverage map/reference-art fixtures; pseudo-locales; recording/apply tools/image-budget guards | W9 |
| Benchmark seed/art installer/readiness protocol/journey tags | W10 |
| Shared graph/build/workflow integration; acceptance ledger | W11 |

W4 submits runner/store patches to W2; W3 tint patches to W6; W8 fixes to surface
owners. Surface owners supply W9 cases; W11 applies shared integration once.
Tests stay with implementations. MD1 owns later Skia pixels, MD4 native desktop
UX; no M10 desktop raster/widgets. No schema/entity/index change.

## Future signatures and integration

Compiled contracts carry pixel/result/repository/snapshot semantics in KDoc.
W2 adds ArtworkColorExtractor.extract(ArtworkPixels): ExtractedArtworkColors,
Default outside publication lock; replaces ArtworkProduction.monogramColor with
colours: ExtractedArtworkColors? (null=unprocessed). Bounded restartable local-only
backfill preserves byte versions and old valid files/colours/retry semantics.
Monograms retain RASTER seed/average; mosaic seed is null.

W4 adds fun mosaicDescriptor(snapshot: MosaicSnapshot): String returning
nd:mosaic:v1:{sha256} over all snapshot fields, using canonical length-prefixed
fields/null markers/order, never hashCode or ambiguous delimiters. Output key
ArtworkKeys.mosaic(groupUuid). Distinct sources/debounce 10 s, exclude own output,
recheck changed/deleted source under store lock. Empty groups render too:
512 px JPEG, four 254 px cells/4 px gaps, pinned file → monogram → group tone.

W6 extends core:ui PlayerSlotState: presentation: PlayerPresentation,
posture: WindowPosture, onClosePanel/onReopenPanel: () -> Unit. RootActions gains
closePlayerPanel/reopenPlayerPanel: () -> Unit, supplied by both shells through
settings, without successful no-op defaults. Close persists hidden; ≥ 840 dp mini
tap/up-drag clears it instead of expanding sheet; new playback never resets it.
One presentation/collector; none without now-playing; panel→sheet starts collapsed;
no panel drag/back. Tabletop/landscape are layouts within either presentation.
Reuse ndPaneLayout/actual suite chrome; reserve panel once. Hinge bounds are
WINDOW dp: convert pixels once, then offset into player. HALF_OPENED horizontal
means tabletop; vertical separating hinges stay excluded. Desktop has no hinges.

W3 owns LRU 16(seed,resolved dark,contrast), applying pure black afterwards;
null/disabled tint uses current app scheme, never cached fallback preferences.

W5 owns these core:ui signatures (experimental types stay inside that boundary):

```kotlin
object SharedKeys {
    fun cover(tab: TopLevelKey, podcastId: Long): String
    fun episodeArt(tab: TopLevelKey, episodeId: Long): String
}
val LocalSharedTransitionScope: ProvidableCompositionLocal<SharedTransitionScope?>
@Composable fun Modifier.sharedArt(key: String): Modifier
```

Stable tab IDs feeds/library/upnext/downloads/discover, kind and ID form keys:
library:cover:42, feeds:episode-art:42. Host provides shared scope around NavDisplay;
reuse LocalNavAnimatedContentScope.current per entry, no second entry local or
artificial AnimatedVisibility. Modifier needs both scopes/one partition; respect
reduced motion. Retain expanded cover/wipe/movable state and Coil public 200 ms
crossfade/THUMB-HERO identity. Designsystem remains navigation-free.

## Screenshot IDs and tiers

Compiled ScreenshotCase(id, platform, requiredTier); platform selects golden root.
ID: subject__state__theme__font__direction-locale__viewport__artwork.
W9 owns registry, ScreenshotTier.current/assumeTier property/JUnit adapters
(neutrodyne.screenshotTier=pr/full), duplicate coverage map and budget guards.

Tokens: light/dark/black; f100/f150/f200; ltr-en/rtl-ar-xb/ltr-en-xa/rtl-en;
compact/medium/expanded/large/tabletop or desktop-w600/desktop-w900/desktop-w1400;
colourful/monochrome/near-white/none. Frozen subject/state families:

| Subjects | States |
|---|---|
| episode-row | unplayed/new/in-progress/playing/paused/played/offline/video/youtube-thumbnail/youtube-avatar/external; download-{state}/wait-{reason}/failed-{error} |
| download-entry-row | download-{state}/wait-{reason}/failed-{error}/completed-played/completed-unplayed |
| cover-tile, monogram | cover/monogram/badge/long-name/emoji (Android only) |
| group-mosaic, group-tab-label | members-0..members-4/badge/long-name/emoji |
| player-mini, player-full, player-panel, player-tabletop | playing/paused × three reference artworks |
| feeds, up-next, downloads, discover, episode-detail, settings-home | content/empty/error where applicable |
| library | podcasts/groups/empty/filtered-empty |
| podcast-detail | rss/youtube/youtube-banner/empty/error |
| import | preview/report |
| brand-mark, brand-scheme | startup/about; contrast-0/contrast-50/contrast-100 |

Enum tokens lowercase, underscores→hyphens: all 8 DownloadStates, eight non-NONE
WaitReasons, each distinct DownloadError text. NONE is active baseline. W9 lists
valid products; future-only M9/M11/MS/MD4 cases wait for those milestones.
PR: every state light/dark f100 LTR plus Android dark/f200/rtl-ar-xb stress or
desktop dark/f150/rtl-en/w900/long strings. FULL adds remaining applicable design
combinations. Explicitly map duplicate images, assert IDs/counts, guard 800 images/30 MB
Android and 400 images/15 MB desktop. Mandatory distinct overflow needs owner resolution.
PR green never closes final-candidate FULL AC1/8.
