# M9a wire and replay freeze

`resolve.json` is a synthetic golden wire-v1 result, not captured YouTube
traffic. It fixes camelCase, fractional seconds, full formatId, xtags and
redaction expectations. Facts use description plus descriptionTruncated.
W3 owns real shim serialization; W5 owns strict bounded decoding/mapping.
Unknown error codes become EXTRACTION; cancellation propagates.

W3 recorded scenario directories contain `manifest.json`, ordered
`requests.jsonl`, response bodies and `result.json`. Manifest fields:
`schemaVersion: 1`, `provenance: synthetic|captured`, `capturedAt` (UTC or
null for synthetic), `ytDlp`, `shimApi`, `method` (wire name), `payload`
(including ctx), `resultKind: success|error`. Success result.json is the
method DTO, error result.json is `{ "code": "RATE_LIMITED" }` (no raw message).
Request entries contain `method`, scrubbed `url`, ordered header pairs,
body filename or null; response contains `status`, ordered header pairs,
`finalUrl`, body filename. Sequence is significant; unrecorded requests fail
closed. Cursor and signed tokens are scrubbed consistently. Bodies stay
under the fixture policy limits (1 MiB each, 20 MiB overall). Test arming
starts null, is explicit before first bind, clears between tests and is never
accepted from exported components. W4 implements release instrumentation
arming; W12 supplies real device evidence. Live host adapters remain tests,
never production desktop bindings (adopted resolution 10).
