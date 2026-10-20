# M9a wire and replay freeze

`resolve.json` is a synthetic golden wire-v1 result, not captured YouTube
traffic. It fixes camelCase, fractional seconds, full formatId, xtags and
redaction expectations. Facts use description plus descriptionTruncated.
The Python bridge owns wire serialization; the Kotlin client owns strict
bounded decoding/mapping. Unknown error codes become EXTRACTION;
cancellation propagates.

Scenario directories under `recorded/` contain `request.json`, `http.json`
and `result.json`. The request names the method and its wire-v1 payload,
including `ctx`. The result is the method DTO or `{ "error": "RATE_LIMITED" }`
without a raw message. Provenance is recorded in the corpus README.
`http.json` contains `exchanges` with canonical request keys and responses;
`bodies` shares scrubbed response text within a scenario via `bodyRef`.
Strict replay consumes each matching exchange once and poisons unrecorded
requests even when the extractor catches its transport error. Contract mode
is informational only. Bodies stay under the fixture policy limits
(1 MiB each, 20 MiB overall).

Test arming starts null, is explicit before first bind, clears between tests
and is never accepted from exported components. Release instrumentation
arming and real device evidence remain pending. Live host adapters remain
tests, never production desktop bindings (adopted resolution 10).
