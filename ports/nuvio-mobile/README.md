# NuvioMobile four-range downloader prototype

**Status: prototype, NOT tested on an Android device or released.** The Android app repository is NuvioMedia/NuvioMobile, not the Windows/Desktop fork that stores this portability patch. This branch does not alter any production Nuvio installation.

- Pinned upstream: `NuvioMedia/NuvioMobile@fc4608d2928c805fdf368399bff39f17bb55b4da` (2026-09-28).
- Apply `android-multirange.patch` using `git apply --check` followed by `git apply` in a fresh checkout of that exact revision. The patch was verified to apply cleanly on the pinned source.
- Implementation: keep Android's existing OkHttp, user-initiated JobScheduler, WorkManager fallback, persisted transfer store and offline-player integration. For new HTTP direct-file downloads >=16 MiB, probe byte-range support and require a strong ETag before opening up to four simultaneous connections. Write independent 8-MiB ranges into a single sparse .part file; atomically checkpoint only fsynced completed ranges. Preserve legacy partials using the original sequential code path.
- On restart, require matching full URL hash, strong ETag, total size and checkpoint layout. A changed URL, same-size/different-ETag content, invalid response ranges, or unusable checkpoint must not silently append unverified bytes. Signed-link re-resolution is NOT implemented: a changed signed URL is deliberately fail-closed.
- If the server ignores Range with HTTP 200, consume the existing HTTP body without refetching the one-use URL. If the server cannot supply a strong ETag for byte ranges, fall back to the previous sequential downloader.
- `AndroidMultiRangeTransferTest` adds byte-exact synthetic multi-range, restart, ETag mismatch, changed URL, no-range fallback and legacy-part scenarios. The baseline `AndroidDownloadTransferTest` adds no changes beyond the port's link. These tests are source-only until CI runs.
- The full app can only be declared accepted after successful Android compilation/tests, a signed isolated APK installed on an actual phone, real authorized TorBox A/B throughput testing with matching source and network, pause/force-stop/restart, 403/416/429, cancellation/cleanup, SHA of synthetic reference files, and local playback with seek. Native Android APK testing and TorBox signed-link refresh remain separate gates.
- **Do not** install this prototype over the existing mobile app, copy its authenticated account data, paste TorBox signed links into CI/logs, or claim the Desktop MSI's status proves Android readiness.
- NuvioMobile upstream CONTRIBUTING requires explicit maintainer approval before an architecture/behavior feature PR. This is an isolated user's-fork prototype, not an approved upstream submission.

## Local verification

```sh
git clone --branch cmp-rewrite https://github.com/NuvioMedia/NuvioMobile.git mobile
cd mobile
git checkout fc4608d2928c805fdf368399bff39f17bb55b4da
git apply --check ../android-multirange.patch
git apply ../android-multirange.patch
./gradlew :composeApp:testAndroidHostTest -Pnuvio.android.distribution=full -Pnuvio.ios.distribution=appstore
./gradlew :androidApp:assembleFullDebug -Pnuvio.android.distribution=full -Pnuvio.ios.distribution=appstore
```

The debug APK uses a separate `com.nuviodebug.com` application ID in upstream's current Gradle configuration. Without Nuvio's public runtime configuration, APK authentication may not work; do not treat a successful build as sign-in or live-download verification.
