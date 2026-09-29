# Nuvio JJ — Windows fork maintenance

- Upstream source of truth: NuvioMedia/NuvioDesktop, branch `Dev`.
- Maintained source: jaskaransingh11/NuvioDesktop, branch `jj/maintained`.
- The app's title shows `Nuvio JJ · <version>`; the MSI intentionally retains
  the original `Nuvio` package identity so a controlled upgrade can preserve
  the existing profile and in-place rollback path.
- Desktop update checks point **only** to releases from the JJ fork.
  No official Nuvio MSI is an automatic replacement for this edition.
- Current installed build on Jassi was 1.1.27 at branch creation. This source
  targets 0.1.28-alpha (Windows MSI numeric version 1.1.28).
  An edited source tree is **not** an installed release.

## Upstream intake and release gates

The `JJ Safe Upstream Intake` workflow runs on the GitHub default branch.
Its scheduled job checks out `jj/maintained`, merges upstream `Dev`, and
refuses to advance if merge conflicts or guarded source/build/update paths
are touched. On changes outside those paths, the workflow still requires
desktop compile and updater/downloader regressions before a fast-forward push.

The intake intentionally **never publishes an MSI or installs software**.
Changes in shared dependencies can affect downloads even when Git reports no
conflicts. Review blocked intake, compare runtime behavior, package an MSI,
check version/ProductCode/UpgradeCode, preserve downloads/rollback, then
publish a GitHub prerelease on the JJ fork after acceptance.

The app's existing in-app updater checks its configured fork and offers an
install action; do not assume that checking for updates means silent install.
Keep the installed JJ app and backed-up Odyssey partial untouched until the
new candidate passes signed-source resume, recovery, playback/seek, auth,
and profile/storage checks. This fork's MSI is currently unsigned.
