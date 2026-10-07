# Release distribution and installation warnings

## What the warning means

The supplied screenshot says the phone cannot find information about this app. It does not identify a harmful-app classification. The exact scanner/vendor cannot be established from that screenshot. It must not be described as confirmed Google Play Protect malware detection.

[Google's guidance](https://developers.google.com/android/play-protect/warning-dev-guidance) distinguishes unknown-app scans, harmful-app classifications, sensitive-permission blocks, and outdated-target warnings. Unknown-app registration/scanning is not something an Android manifest can guarantee. An appeal is appropriate only for a mistaken classification/block, not automatically for an unknown-app message. Never disable protection or add misleading metadata to bypass the warning.

## Changes made locally

- Release explicitly remains non-debuggable; debug diagnostics are already replaced by NoOp implementations in production wiring.
- Cleartext connections are explicitly denied. Network security configuration trusts system certificate authorities only, with no user CA or debug trust override. This makes the existing HTTPS/default-trust policy explicit; it is not a security-scanner approval or a claim about arbitrary raw socket implementations.
- Android cloud backup and device-transfer rules exclude all app-private data domains, including encrypted session/pending OAuth preferences and internal repository data. Existing data is not deleted. Local restart/session restore still reads the same secure store; reinstall/new-device recovery requires signing in again. User-selected shared disk repositories are not erased or made inaccessible by these backup settings. Maintain your own repository backups before uninstalling.
- Signing credentials, key containers, AABs, and Android Studio release outputs are ignored by Git. Existing signing keys are not generated, read, rotated, or deleted.
- Source policy regression tests guard permissions, backup, network trust, callback URI, and provider protection.
- `scripts/verify-release.ps1` checks an actual signed APK: valid signature, no Android debug certificate, non-debuggable manifest, target SDK >= 36, explicit backup/cleartext policy, and approved permissions. It prints an APK SHA-256 for identifying the artifact. It does not upload or sign anything.

## Permission audit

Declared platform permissions: INTERNET, MANAGE_EXTERNAL_STORAGE, and legacy READ/WRITE_EXTERNAL_STORAGE limited to Android 9 and lower. No SMS, accessibility, notification-listener, contacts, microphone, location, install-packages, or overlay permission is declared by the app.

The merged APK also contains AndroidX's app-specific signature-protected private-receiver permission; this is not a permission to control other apps. The exported DocumentsProvider remains protected by MANAGE_DOCUMENTS and URI grants. The launcher/OAuth activity must remain exported for the existing callback.

All-files access is retained because the approved direct-on-disk JGit workflow requires real filesystem paths. Removing it without changing that workflow would break clone/edit/commit. Google Play eligibility/declaration for this permission still requires manual policy review; no approval is claimed. Do not disguise the permission or change the app identity simply to evade scanning.

## Build and verify your distributable APK

1. Run the existing debug unit tests, release build, and release lint.
2. Android Studio → Build → Generate Signed App Bundle or APK → APK → release. Select your existing release key. Keep its password/private key outside source control; do not share it in chat. Keep the same signing identity for updates.
3. The CLI release build is unsigned unless signing is supplied separately. Never distribute `app-release-unsigned.apk`. Android Studio's signed output is commonly `app/release/app-release.apk`; rebuild it after these source changes. The old signed artifact is not automatically replaced by a CLI unsigned build.
4. Verify the newly signed APK from PowerShell:

```powershell
./scripts/verify-release.ps1 -ApkPath ./app/release/app-release.apk
```

The script requires a JDK and Android SDK Build-Tools. Pass `-SdkPath` if your SDK is elsewhere. Verification is read-only and errors have RELEASE_* codes. Changing any APK bytes after signing invalidates the signature: rebuild and sign instead.

5. Install on a real device; allow the vendor/Play Protect scan if offered. Check login, clone to the chosen disk folder, Files access, status, and push. Compare the installed artifact's SHA-256 to the verified APK. Do not uninstall an existing app with valuable private data just to work around a certificate mismatch.

Client ID and Worker URL are developer build configuration already loaded from ignored local.properties/environment/Gradle properties. Users do not configure them. GitHub Client Secret remains backend-only.

## Manual work outside this repository

- Vendor-specific unknown-app reputation/scanning cannot be controlled from this project. Google Play Protect approval has not been obtained by these changes.
- If using Google Play: prepare the listing, privacy policy and accurate Data Safety declaration, review all-files access eligibility, sign an AAB and submit using your Play Console account. Use a testing track before public rollout.
- If a genuine harmful-app classification is shown and appears incorrect, record its exact text, package/version, APK hash, signing certificate fingerprint, device/vendor, and distribution URL before filing the appropriate appeal. The warning in the screenshot alone does not establish this condition.
- No APK was uploaded to Google, a malware-scanning site, or a store, and no appeal or publication was submitted automatically.

References: [Android backup rules](https://developer.android.com/identity/data/autobackup), [network security configuration](https://developer.android.com/privacy-and-security/security-config), [APK signature verification](https://developer.android.com/tools/apksigner).

## Validation results for this change

These results describe the release-security phase. See [the subsequent warning audit](warning-audit.md) for source-level cleanup and remaining zero-warning decisions.

- Debug unit tests: PASS, 107 tests, zero failures/errors/skips (includes four new security-policy tests).
- Debug APK and release APK packaging: PASS.
- Release lint: PASS, zero errors and 23 warnings (including dependency recommendations, all-files-access policy, and third-party JGit trust-manager findings). These are recorded in `app/build/reports/lint-results-release.html`; passing lint is not a full dependency/malware audit.
- Packaged release manifest: backup and cleartext explicitly false, debuggable absent (default false), approved permission list preserved. Packaged network and transfer XML were inspected.
- Verifier failure paths exercised: debug-signed APK rejected with RELEASE_003_DEBUG_KEY; unsigned release rejected with RELEASE_002_INVALID_SIGNATURE; old signed Android Studio APK rejected with RELEASE_006_UNSAFE_POLICY because it predates the new backup settings. These are expected rejections, not successful verification of a new signed distributable.
- No new release key was created or supplied. CLI artifact is `app/build/outputs/apk/release/app-release-unsigned.apk` and must not be distributed; sign a fresh release through Android Studio.
- No connected emulator/phone was available for this change, so real-device install/security-scanner and OAuth/Git acceptance remain manual.
- Initial attempt to run `testReleaseUnitTest` found that task is unavailable in this project's variant configuration; the available debug unit suite was run successfully instead.
