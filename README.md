# Pillow Core SDK

`Pillow Core SDK` is the public Kotlin Multiplatform source repository that backs the Pillow mobile SDKs.

Platform install surfaces live separately:

- iOS: `https://github.com/trypillow/pillow-ios-sdk`
- Android: `https://github.com/trypillow/pillow-android-sdk`

This repository exists for source transparency, architecture review, and shared-core development visibility.

## Repository contents

- `src/commonMain` shared audience and study runtime
- `src/androidMain` Android-specific implementation
- `src/iosMain` iOS-specific implementation
- `src/desktopMain` and `src/desktopTest` smoke-harness and shared verification support
- `src/nativeInterop` native interop definitions

## Verification

```bash
./gradlew verifyCore
```

## Install the SDKs

Use the platform-specific repos for installation:

- iOS SDK install and API docs: `https://github.com/trypillow/pillow-ios-sdk`
- Android SDK install and API docs: `https://github.com/trypillow/pillow-android-sdk`

## Support

Use GitHub Issues in this repository for shared core architecture questions, source-level bugs, and cross-platform issues.
