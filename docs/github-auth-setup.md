# GitHub authentication setup

## Developer setup

1. The application developer creates one GitHub OAuth App and registers `gitclonepush://oauth/callback` as its callback URI.
2. Before building the APK/AAB, provide that application's public client ID through one of these build-time inputs:
   - `github.clientId=...` in the developer's ignored `local.properties`
   - the `github.clientId` Gradle property in CI
   - the `GITHUB_CLIENT_ID` CI environment variable
3. Gradle embeds the public client ID into `BuildConfig.GITHUB_CLIENT_ID` and the callback into `BuildConfig.GITHUB_REDIRECT_URI`.
4. Never put user access tokens, authorization codes, PKCE verifiers, or client secrets in BuildConfig or source control.

## End-user flow

End users do not create an OAuth App, edit `local.properties`, enter a client ID, enter a client secret, or paste a Personal Access Token. They install the built application and tap **Continue with GitHub**. Their GitHub account produces the user-specific access token stored by the app.

The app requests `read:user` and `repo`: profile identification and repository read/write access required by clone, pull, and push. The login flow uses Authorization Code + PKCE (S256), validates the returned `state`, and stores the access token encrypted with an Android Keystore AES key.

Native Android clients are public clients. A client ID is not secret, and this implementation does not ship a client secret. Token exchange is isolated behind `OAuthTokenExchangeGateway`; `DirectGitHubTokenExchangeGateway` can later be replaced by `BackendTokenExchangeGateway` without changing the UI, ViewModels, or `AuthRepository` contract.

Tokens are not logged, exposed in UI state, placed in navigation arguments, or embedded in Git remote URLs. JGit receives credentials only through its operation-time credentials provider.
