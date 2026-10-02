# JASIRI releases (maintainer note)

## Where releases live

- JASIRI's signed releases are published on GitHub at
  [`waltleros/bitchat-android`](https://github.com/waltleros/bitchat-android/releases).
- The in-app updater and the "share the app" download both read from there. The repository is
  defined once, in `app/src/main/java/com/jasiri/JasiriRelease.kt`; renaming the repository is a
  one-line change in that file.

## Release asset name

- Attach the APK to the release as **`bitchat-android-universal.apk`**. The app downloads
  `releases/latest/download/bitchat-android-universal.apk`, and falls back to the legacy name
  `app-universal-release.apk` only when the first is missing.
- The release tooling in `tools/reproducible-builds/` already produces these names.

## Signing key

- The in-app updater only accepts an APK signed with the certificate whose SHA-256 fingerprint is
  `BITCHAT_GITHUB_RELEASE_CERT_SHA256` in `gradle.properties` (an environment variable of the same
  name overrides it at build time).
- That fingerprint must be **JASIRI's own** release key, not upstream bitchat's. With the wrong
  fingerprint, every update is rejected as untrusted.
- The keystore lives **outside** the repository and is never committed. Keep offline backups.
  **Losing it means existing installs can never be updated**: Android only installs an update
  signed with the same key, so users would have to uninstall and reinstall.
