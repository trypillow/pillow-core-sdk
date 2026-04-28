# Changelog

## [Unreleased]

## [0.1.5] - 2026-04-28

- Drop `AudienceInstallSentinel`. The SQLite installation row is now the sole source of truth for fresh-install detection, fixing spurious installation_id rotations on iOS when NSUserDefaults didn't flush before SIGKILL.
- Embed the Pillow SDK version as a build-time constant (`PillowSdkBuildConfig.SDK_VERSION`) sourced from `public/android/gradle.properties:VERSION_NAME`, so audience telemetry no longer reports the host app's version.

## [0.1.4] - 2026-04-17

- Add launch study runtime support: persist backend-provided launch study instructions and present them on app ready.

## [0.1.3] - 2026-04-10

- Fix study webview memory retention after dismiss.

## [0.1.1] - 2026-04-10

- Initial public source repository layout for the shared Kotlin Multiplatform core.
