# Building and testing ServiceTag

ServiceTag is an Android/Jetpack Compose application with a pure-Kotlin `:core` domain module and the shared `nfc-tag-core` library as a git submodule.

For product behavior, start with [Current capabilities](capabilities.md). For release/version rules, see [Versioning](versioning.md).

## Clone

```bash
git clone --recurse-submodules https://github.com/GonzRon/ServiceTag.git
cd ServiceTag
```

If the repository was cloned without submodules, initialize them before building.

## Debug build

```bash
./gradlew :app:assembleDebug
```

## Main local / CI JVM gate

```bash
./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug
```

The project deliberately keeps most deterministic/domain coverage in JVM tests.

## Instrumented Android tests

Run connected tests on an emulator:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Do not use a physical phone holding real ServiceTag data as the ordinary instrumentation target.

The device suite is intended for behavior that genuinely depends on Android/Compose/platform boundaries. Current device-suite performance policy is tracked separately in the roadmap/test-maintenance work.

## Automation tooling

The repository also contains workstation tooling:

- [ServiceTag MCP](../tools/servicetag-mcp/README.md)
- schedule-loader tooling under `tools/servicetag-schedules/`

The HTTP contract used by workstation tooling is documented in [Developer API v1](api/v1.md).

## Release and verification references

- [Versioning and release policy](versioning.md)
- [Release proofs](release-proofs.md)
- [Release notes](releases/)
- [Current roadmap — issue #104](https://github.com/GonzRon/ServiceTag/issues/104)

Released versions are never renamed or re-cut. The release workflow expects the release tag and the APK's `versionName` to agree.
