# Discovery replay fixtures

Captured 2026-10-19 outside CI. Apple and fyyd JSON retain two ordered
records and only factual identifiers/metadata used by the implementation.
No artwork bytes, descriptions, credentials, IP addresses or user queries
are redistributed. Artwork URLs are parser data and are never fetched in tests.

- apple-search.json: https://itunes.apple.com/search?media=podcast&entity=podcast&term=news&country=US&limit=2
- apple-lookup.json: https://itunes.apple.com/lookup?id=73329284,1200361736&entity=podcast
- apple-top.json: https://rss.marketingtools.apple.com/api/v2/us/podcasts/top/100/podcasts.json
- apple-genres.json: ID/name projection from https://itunes.apple.com/WebObjects/MZStoreServices.woa/ws/genres?id=26
- apple-genre.json: https://itunes.apple.com/us/rss/toppodcasts/limit=100/genre=1318/json
- fyyd.json: https://api.fyyd.de/0.2/search/podcast?title=news&count=2
- podcast-index.json: authored spec-derived example, NOT a live authenticated recording.
  https://podcastindex-org.github.io/docs-api/pi_api.json
- HTML: authored minimal structural fixtures, Unlicense. They reproduce the
  alternate/base, Apple-anchor-only, WordPress and multiple-feed AC3 shapes.

Apple's promotional-use framing and approximate 20/min restriction were
rechecked at https://performance-partners.apple.com/search-api. Directory
permission remains owner risk L3; factual field excerpts imply no broader
permission to redistribute artwork. The legacy genre endpoint is undocumented
and only US/technology was live-checked on that endpoint. All 19 IDs match
Apple's live taxonomy. Modern overall chart requests also succeeded for DE and
CH (same v2 shape); these country responses are not duplicated in fixtures.
Other country/per-genre availability is not proven by this replay. fyyd's v0.2 endpoint returned status=1 and per-feed
HTTP status=200, with document-order ranking. Upstream Codeberg documentation
was unavailable; no rate-limit or terms permission is inferred. PI auth,
401 Date and 429 shapes are deterministic spec-based tests, not live proof.
PI terms: https://github.com/Podcastindex-org/legal/blob/main/TermsOfService.md;
no embedded release credentials are enabled. Empty/missing/invalid variants
are authored in tests. Live provider availability is never a test requirement.
