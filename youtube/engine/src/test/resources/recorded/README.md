# Shared shim synthetic replay corpus

All scenarios here are **synthetic**, generated 2026-10-19 by
`youtube/engine/python/tests/make_scenarios.py` with official yt-dlp
2026.08.19. Origin: invented player/channel/search JSON and HTML, never a
live capture. Prose and media URLs are invented. No artwork/audio is included.
The fixed test wall clock is 1792368000; monotonic deadlines remain real.

Each scenario stores wire input/output plus strict HTTP exchanges. `bodies`
is a shared string table within that scenario; `bodyRef` indexes it.
Replay matches method, scrubbed URL and the entire canonical JSON request
body, once per recorded exchange. No other network handler is installed.
An unrecorded request poisons the call even if yt-dlp catches the exception.
Contract mode is explicit, informational only, and answers misses with 404.

The AC1 error corpus is generated into ignored build output by the same
synthetic source and then strictly replayed, with independently asserted
expected codes. These parser vectors are not captured server evidence.
Structured upcoming/live/post-live and concurrent warning isolation use
bounded fake extractors. Residential recordings remain pending; the manual
recording script uses an explicit host/live urllib adapter and writes to a
new staging directory for privacy/licensing inspection before copying here.
Neither Android PyHttp nor a production desktop host is claimed by this suite.
