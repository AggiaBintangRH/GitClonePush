# Dependency compatibility review

Review date: 2026-10-07. Scope: eleven dependency-update advisories, without changing storage, OAuth, JGit TLS handling, or the Android toolchain.

The project retains compileSdk 36.1, minSdk 24, AGP 9.1.1, Gradle 9.3.1, JDK 17, and built-in Kotlin / Compose compiler 2.2.10. Published Maven POM and AAR metadata were checked; compilation and tests are required in addition to that metadata review.

| Dependency | Previous | Selected | Decision |
| --- | --- | --- | --- |
| Activity Compose | 1.10.1 | 1.13.0 | Android metadata supports SDK 36; requires compatible Core/Lifecycle updates below. |
| Core KTX | 1.17.0 | 1.18.0 | Align with Activity's dependency. Latest 1.19.1 requires SDK 37. |
| Lifecycle runtime KTX | 2.9.3 | 2.9.4 | Align with Activity. Latest 2.11.0 requires SDK 37. |
| Lifecycle ViewModel Compose | 2.9.3 | 2.9.4 | Align Lifecycle modules. Latest 2.11.0 requires SDK 37. |
| Coroutines Android and test | 1.9.0 | 1.11.0 | Update together; compatible compilation and coroutine regression tests must pass. |
| OkHttp | 4.12.0 | 4.12.0 | Trial upgrade to 5.5.0 failed checkDebugAarMetadata: okhttp-android requires SDK 37. Reverted only this trial edit. |
| Navigation Compose | 2.8.9 | 2.8.9 | Latest 2.10.2 requires SDK 37. |
| Compose BOM | 2025.08.00 | 2025.08.00 | Latest 2026.09.00 selects Compose UI 1.12.1, whose AAR requires SDK 37. |
| AGP | 9.1.1 | 9.1.1 | Latest 9.4.1 requires Gradle 9.6.0, not the current wrapper. |
| Compose compiler plugin | 2.2.10 | 2.2.10 | Must remain aligned with the current built-in Kotlin compiler; do not independently upgrade to 2.4.20. |

No dependency was forcibly pinned below another dependency's required version. No lint warning was suppressed. Newer-version advisories for intentionally retained versions remain valid; compatible intermediate updates do not make those advisories disappear.

References: [AGP/Gradle compatibility](https://developer.android.com/build/releases/about-agp), [Compose compiler setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler), [Coroutines releases](https://github.com/Kotlin/kotlinx.coroutines/releases). SDK requirements were additionally verified from the actual published AAR metadata, including the Android OkHttp variant rather than its JVM POM alone.

## Validation

- PASS: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin`, `:app:assembleDebug`, `:app:assembleRelease`, and `:app:lintRelease` in one final-config build.
- Unit tests: 107, zero failures/errors/skips.
- PASS: `:app:connectedDebugAndroidTest` on the existing Pixel 7 API 35 emulator: 50 tests, zero failures/skips. This exercises deterministic fixtures, not a fresh real-account GitHub OAuth/clone/push acceptance run.
- Final lint: zero errors, eleven warnings. Eight dependency advisories remain (down from eleven); two JGit TLS findings and the storage-policy advisory are unchanged. No suppression or baseline was introduced.
- `git diff --check`: PASS.
- The trial OkHttp 5.5.0 build failed SDK metadata validation as documented above; it is not the selected final configuration.
- The release APK produced by Gradle is unsigned. This task did not change signing files or regenerate the separately user-signed release APK.
- Native stripping tasks were up to date in this run; this does not establish that the earlier missing-NDK stripping message is fixed.
