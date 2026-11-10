# sync-client-auth-context-current

Couple each authenticated sync request's base URL and bearer token into one
per-request snapshot, so a saved-address switch between the two provider reads
can no longer send one server's token to the other's URL.

## Scope

- `sync/impl/.../SyncClient.kt` — `KtorSyncClient` gains an optional trailing
  `tokenForBase: (suspend (String) -> String?)? = null` constructor callback and
  a private `AuthContext` snapshot (`baseUrl` + `token`, `toString` redacts the
  token). `authContext()` resolves the URL once and loads the token for exactly
  that URL.
- `sync/impl/.../SyncBindings.kt` — `syncClient` supplies
  `tokenForBase = { url -> tokens.load(hostOf(url)) }`.
- `sync/impl/src/desktopTest/.../KtorSyncClientAuthContextTest.kt` — new wire
  test driving the production `SyncBindings.syncClient` over two loopback
  `MockWebServer`s, `FakeSecretStore`-backed `SyncTokenStore` fixture tokens,
  and a `SyncServerAddress` seam returning captured A on the first read and B
  ever after.

Out of scope per task: `SyncClient` interface changes (ROOT adds the
`authenticated(serverUrl, token)` snapshot binding on the separate resync
branch), engine/profile/account fixes.

## Observed OLD behavior (not inference)

Before the fix, the new test suite was run against unmodified code
(`71fb282`+test only). The recorded wire traffic showed the leak directly —
JUnit XML saved outside the tree at
`/tmp/nd-red-auth-context/TEST-...KtorSyncClientAuthContextTest.xml` plus
`red-console.txt`:

- `everyAuthenticatedCallSendsTheResolvedUrlsOwnToken` FAILED — all eight
  authenticated verbs (`account`, `devices`, `changes`, `logout`, `sync`,
  `reset`, `renameDevice`, `revokeDevice`) hit server A carrying
  `Authorization: Bearer fake-token-for-server-b`.
- `eachCallPairsTheResolvedUrlWithThatUrlsToken` FAILED —
  `expected Bearer fake-token-for-server-a but was Bearer fake-token-for-server-b`.
- `aUrlWithoutAStoredTokenSendsNoBearer` FAILED — with no token stored for A,
  the request to A still carried `Bearer fake-token-for-server-b` (the second
  provider read re-resolved the by-then-switched address).
- `explicitAddressCallsNeverReadTheSavedToken` PASSED — explicit-address
  unauthenticated and explicit-token calls were already unaffected.

## Endpoints covered

Coupled context applied to all linked authenticated verbs:

| Verb | Method | Path |
| --- | --- | --- |
| `account` | GET | `/api/v1/account` |
| `devices` | GET | `/api/v1/devices` |
| `changes` | GET | `/api/v1/changes` |
| `logout` | POST | `/api/v1/auth/logout` |
| `sync` | POST | `/api/v1/sync` |
| `reset` | POST | `/api/v1/account/reset` |
| `renameDevice` | PATCH | `/api/v1/devices/{id}` |
| `revokeDevice` | DELETE | `/api/v1/devices/{id}` |

Unchanged by design: `discover`, `login`, `redeemInvite`, `linkStart`,
`linkToken` (explicit-address, unauthenticated) and `linkApprove`,
`linkConfirm` (explicit-address, explicit token). The test asserts `login`
sends no `Authorization` to its explicit URL and `linkApprove` sends only its
explicit token while the saved address reads A-then-B underneath.

Fallback discipline: `request`, `getJson` and `patchJson` no longer call
`tokenProvider()` — a null token now means "send no `Authorization`", never
"re-resolve the credential". The only remaining `tokenProvider()` use is
`authContext()`'s legacy path for callers constructed without `tokenForBase`
(existing test fixtures), where a missing snapshot token is still never
backfilled post-URL in the bound (production) configuration.

## Checks and results (all on the fixed tree)

- `:sync:impl:desktopTest` — full module suite, 32 classes, 0 failures
  (includes `KtorSyncClientTest` 14/14, `KtorSyncClientAuthContextTest` 4/4,
  `SyncEventsHttpTest` 4/4, `TwoClientWireTest` 1/1).
- `spotlessCheck`, `checkSpdxHeaders`, `checkBannedApis`,
  `assertModuleGraph`, `verifyDependencyPolicy` — clean.
- `:app:dumpReleaseR8Inputs` + `scripts/ci/trace-instrumentation-abi.py
  --check` — "Instrumentation ABI check passed"; no keep-file drift, nothing
  regenerated.
- `:app:assembleRelease -Pneutrodyne.abiSplits=true` — `minifyReleaseWithR8`
  and packaging green.

All Gradle runs through `~/nd-wt/bin/gradle-local.sh --max-workers=4`.

## Remaining risks

- `tokenProvider`-only constructions (test fixtures, `TwoClientWireTest`'s
  inline client) keep the decoupled lookup by contract; their providers are
  constant lambdas, so the race does not materialize there. Production now
  always binds `tokenForBase`.
- The race window closed is the per-request URL/token pairing. A switch
  mid-flight still sends the request to the URL resolved at request start —
  correct behavior — and the response handling is unchanged.
- `AuthContext` is per call, not memoized: every call re-reads the saved
  address once, so a completed relink picks up the new URL on the next call
  (verified: second `devices()` hits B with B's token).
- `KtorSyncClient`'s public surface is unchanged apart from the trailing
  optional constructor parameter; Metro injection at `SyncBindings` is the
  only production construction site.
