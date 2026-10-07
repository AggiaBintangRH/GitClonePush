# Loading, safe errors, and function audit

## Scope

Reviewed the existing production flow, dependency wiring, asynchronous state transitions, cancellation handling, and error presentation across authentication, repository listing/refresh/clone, workspace mutations, branch/merge/rebase/history operations, Files, history/diff, and settings. This is a source and regression-test audit, not a guarantee that every possible device/network/Git condition is bug-free.

The approved navigation and Git/OAuth infrastructure are preserved. No real remote mutations or account credentials are required by the automated tests.

## Loading behavior

- OAuth: preparing browser → awaiting browser authorization → exchanging callback/loading profile. A blocking Material 3 overlay displays the current phase. Duplicate sign-in taps are ignored. While waiting for the browser, Cancel abandons only the pending OAuth transaction, not the stored account.
- Callback completion belongs to the retained LoginViewModel, rather than the lifetime of a Compose collector. Existing one-shot callback delivery and state/PKCE checks remain in place.
- Session restore: overlay while authentication state is Loading.
- Repository catalog: existing initial loading, native pull-to-refresh indicator, picker loading, and per-card real clone progress remain non-destructive.
- Workspace: overlay for status refresh, stage/unstage/commit, remote and branch mutations, merge/rebase/revert/cherry-pick/stash operations, clone removal, and Files reads/launch.
- History/detail/diff: existing loading states; incremental history loading now includes a spinner without hiding earlier commits.
- Settings: identity read and sign-out overlays.
- Instant local interactions such as typing, filtering, and theme switching do not receive artificial delays or loading indicators.

## Findings fixed

- Browser launch previously ended the login busy state too early. Login now retains its phase until callback success, failure, or cancellation.
- Login failures now explain safe categories (configuration, connectivity, HTTP status, invalid response, secure storage, missing browser). Errors survive recomposition and clear on retry/success. Raw exception text and OAuth values are not rendered.
- Callback execution is ViewModel-owned and serialized with begin/cancel; recomposition does not replay it.
- Cancellation is rethrown at the authentication and workspace optional-read boundaries instead of becoming a normal failure.
- Workspace operations share busy guards, including merge jobs and author lookup. Progress clears on completion/failure; repeated clone-removal requests are blocked before scheduling.
- Files reads cancel superseded requests and use request generations so stale results cannot overwrite current navigation. Unexpected read/launch errors become safe UI errors; loading clears in finally.
- Backend access tokens must be nonblank JSON strings; JSON null, arrays, objects, and numbers are rejected rather than being coerced to strings.
- Lint found API compatibility issues below Android 8. Core-library NIO/time desugaring is enabled without increasing minSdk. Bounded blob reading no longer calls Android-13-only InputStream.readNBytes. Files initial-URI hints are API guarded.

## Cleanup

Removed the unbound direct token-exchange implementation, unused production fake repository source, and unused template XML colors. Test fakes remain. Login preview now renders real stateless content rather than a placeholder.

## Architecture and security

The shared overlay only renders state. ViewModels orchestrate presentation/lifecycle; domain interfaces and injected infrastructure still perform Git/network/storage work on background dispatchers. AuthRepository's focused cancellation operation clears only pending authorization. No new token store, backend, Git stack, logging library, or service locator was introduced.

No new diagnostic payload contains token, code, verifier, state, response-body, file-content, or credential values. Public redirect/callback URIs, PKCE, state validation, secure per-user session storage, and operation-time Git credentials are unchanged.

SOLID review: the overlay renders only progress; LoginViewModel owns login presentation; repositories/gateways own authentication semantics and transport respectively (S). New progress states reuse one component without altering Git engines (O). Real and fake AuthRepository implementations implement the same pending-cancellation contract (L). Cancellation remains a single focused auth operation, not a general service interface (I). ViewModels continue receiving repository abstractions through constructors (D). Existing large workspace orchestration remains a future focused-refactoring opportunity; it was not rewritten in this UI task.

## Validation boundaries

Automated coverage includes OAuth loading across browser/callback/recomposition, safe persistent failure and retry, cancellation and missing browser, no duplicate login initiation, workspace mutation progress/duplicate protection/author failure, Files latest-request/error/retry behavior, and invalid backend token types. Existing deterministic Git and provider-security suites are retained.

Live GitHub OAuth and real push/pull are not repeated by this audit. Manual acceptance: sign in, cancel once, retry, return via callback, verify repository navigation; repeat offline and confirm a visible error with no stuck overlay. Run workspace operations and confirm the overlay clears; existing clone/pull refresh must keep loaded data visible.

Dependency-update suggestions and third-party/platform-policy lint findings must be reviewed separately; do not upgrade dependencies or hide warnings simply to obtain an empty report.

## Executed validation

- `:app:compileDebugKotlin`: PASS.
- `:app:testDebugUnitTest`: PASS, 103 tests, zero failures/errors/skips.
- `:app:compileDebugAndroidTestKotlin`: PASS.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest`: PASS.
- `:app:lintDebug`: PASS, zero errors and 23 warnings. Remaining categories: dependency-update suggestions (11), KTX suggestions (7), Compose modifier parameter ordering (1), version-catalog suggestion (1), shared-storage Play-policy warning (1), and JGit dependency trust-manager findings (2). No lint baseline or blanket suppression was added. The application does not configure an insecure TLS override; the third-party findings still require a dedicated dependency/security review before claiming a warning-free release.
- Installed the final app/test APKs on the API 35 emulator and ran the full instrumentation suite: PASS, 50 tests.
- Whitespace validation: `git diff --check` PASS.
- Emulator storage permission used by deterministic shared-storage tests was restored to its prior package-level default after testing.
- Final APK installed and MainActivity cold launch returned `Status: ok`.

Build artifact: `app/build/outputs/apk/debug/app-debug.apk`. No signing files or user repository data were deleted.

Compatibility reference: [Android core-library desugaring](https://developer.android.com/studio/write/java8-support).
