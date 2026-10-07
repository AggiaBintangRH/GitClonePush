# Warning cleanup and remaining decisions

Update 2026-10-07: the subsequent [dependency compatibility review](dependency-compatibility.md) reduces the eleven version advisories to eight. The latest release lint report has eleven total warnings: eight version advisories, two JGit TLS findings, and one storage-policy advisory. Results below describe the earlier source-cleanup run and are retained as history.

The initial release lint report contained 23 warnings, not 23 runtime errors or phone installation alerts. This task does not mark the zero-warning requirement complete.

## Source issues corrected

- Seven KTX suggestions: four URI conversions and three SharedPreferences edits now use the existing AndroidX extensions. Default asynchronous apply semantics are retained; no OAuth/token values are logged.
- The shared primary button's Modifier is now its first optional parameter. Its caller uses named optional arguments to avoid positional mistakes.
- Core-library desugaring is declared through the existing version catalog, not a hardcoded dependency string.
- Obsolete URI imports were removed. OAuth callback/redirect, credential storage format, and disk clone behavior are unchanged.

## Remaining categories

- Eleven dependency/toolchain update suggestions: AGP, core, lifecycle (two), activity, navigation, Compose BOM, Kotlin, OkHttp, and coroutines (two). The installed Android SDK contains only android-36.1. A coordinated upgrade needs compatibility checks and an appropriate SDK/Gradle/JDK combination, not blindly editing version strings. A newer available version is not by itself evidence that the installed version is broken or insecure.
- Two JGit trust-manager findings: inspected dependency bytecode identifies NoCheckX509TrustManager's empty certificate-check methods and HttpSupport.disableSslVerify. No direct calls or insecure TLS overrides were found in application source, but that does not remove the library code or prove all possible Git configuration is safe. Resolving dependency findings requires an upstream fix, an audited maintained fork, or an engine migration; do not rewrite a cached jar or disguise the findings.
- One storage-policy warning: MANAGE_EXTERNAL_STORAGE supports the user's approved direct-on-disk clone/edit workflow. Removing it alone breaks that workflow. A permission-free redesign (app-owned worktree/DocumentsProvider or a carefully designed SAF synchronization layer) changes the existing storage behavior and requires an explicit product decision. Play approval would not automatically erase this lint advisory.
- Native packaging additionally reported inability to strip libandroidx.graphics.path.so. No Android NDK directory is installed in the local SDK. Native toolchain setup/verification is required before calling this message resolved; no keepDebugSymbols shortcut was added to hide it.

No lint suppression, baseline, disabled detector, or package/permission disguise was added. No signing files or user repositories were changed.

Reference: [Android all-files access guidance](https://developer.android.com/training/data-storage/manage-all-files).

## Verified results

- Lint fell from 23 to 14 warnings; zero errors. All nine source-level findings above are absent from the new report. The remaining 14 are exactly eleven version-update suggestions, two JGit trust-manager findings, and one storage-policy advisory.
- `testDebugUnitTest`: PASS, 107 tests, zero failures/errors/skips.
- `compileDebugAndroidTestKotlin`, `assembleDebug`, `assembleRelease`, `lintRelease`: PASS. This does not mean zero warnings; the separate native stripping message still occurred.
- `git diff --check`: PASS.
- No emulator/device was connected, so this change does not claim new runtime acceptance testing.

The task remains incomplete against the requested zero-warning criterion. Changing the approved direct-disk storage workflow requires user approval. Coordinate dependency/SDK/native-toolchain and JGit remediation rather than hiding findings or damaging working features.
