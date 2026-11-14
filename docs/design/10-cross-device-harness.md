# Cross-device sync harness

The E12 host coordinator runs the real Android instrumentation driver and the
desktop test driver against a server started from the executable server JAR.
It creates a private workspace, a new headless API 36 AVD and a password account
through the server's normal setup form. It never reuses an existing AVD or app
directory. Tokens and passwords stay in that workspace and do not appear in
command arguments or the public report.

Run from a checkout containing both drivers, with JDK 21, the Android SDK and
the API 36 Google APIs system image installed:

```sh
python3 scripts/ci/cross-device-sync.py \
  --system-image 'system-images;android-36;google_apis;x86_64'
python3 scripts/ci/assert-cross-device-report.py build/reports/cross-device-summary.json
```

On an Apple Silicon host use `arm64-v8a` in the image name. On the shared
development Mac, pass `--gradle /Users/lkm/nd-wt/bin/gradle-local.sh` so the
machine's normal Gradle serialization applies. `--workspace-parent` can select
a volume with enough space for the isolated AVD. The coordinator builds the
apps, test APK and server before starting them.

The journey seeds 50 podcasts, two groups and episode state on both clients,
then links normally. It makes reproducible random edits on each client while
the local sync gateway returns HTTP 503. This is a sync-server outage; it does
not disable either client's radio. After reconnection it requires matching
canonical wire records and materialized library state, preserving group,
membership and Up next order. It separately writes a desktop position and
checks Android's stored position within one second of that value.

Finally, Android removes eleven subscriptions during an outage. Before any
approval, the coordinator requires exactly those eleven removals in the
desktop's held batch and all fifty subscriptions still present there. It then
invokes the desktop driver's explicit public-service approval phase and
requires both libraries to converge to 39 subscriptions.

The `sync-convergence` scope of `nightly.yml` runs the same coordinator. Only
`build/reports/cross-device-summary.json` is uploaded. The coordinator stops its
owned emulator and server on success and failure. A successful run deletes its
private workspace. A failed run prints the private workspace path for local
diagnosis; do not publish its config, database or logs. Before removing a failed
workspace after an interrupted process, check its ownership marker and child
manifests against the running processes.

The normal release precondition requires a successful `sync-convergence` job
in the scheduled nightly inventory. A missing, skipped or unsuccessful job
does not supply E12 evidence. The existing PATCH hotfix smoke exception keeps
its separate documented conditions.

This harness proves persistent cross-device state on one runner. Foreground
propagation timing on physical devices, playback through “Continue on this
device”, each desktop OS, server backup restoration and the remaining manual
cross-device checklist still need their own evidence. An emulator result does
not satisfy those checks.
