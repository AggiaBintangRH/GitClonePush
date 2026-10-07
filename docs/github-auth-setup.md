# GitHub authentication setup

## Developer setup

1. The application developer creates one GitHub OAuth App and registers this GitHub-facing redirect URI:

   `https://aggiabintangrh.github.io/HomePageGitClonePush/oauth/callback.html`

   The callback page forwards the returned `code`, `state`, and any OAuth error to the Android handoff URI `gitclonepush://oauth/callback`. These are different URIs: GitHub uses the HTTPS Pages URL; Android receives the custom-scheme handoff.
2. Before building the APK/AAB, provide that application's public client ID through one of these build-time inputs:
   - `github.clientId=...` in the developer's ignored `local.properties`
   - the `github.clientId` Gradle property in CI
   - the `GITHUB_CLIENT_ID` CI environment variable
3. Deploy the OAuth Worker in `oauth-backend/`, configure its `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET` as Cloudflare secrets, then provide its HTTPS URL through `oauth.backendBaseUrl` or `OAUTH_BACKEND_BASE_URL` during the Android build.
4. Gradle embeds only the public client ID, the fixed HTTPS GitHub redirect URI, and the backend base URL into BuildConfig. The client secret remains server-side.
5. Never put user access tokens, authorization codes, PKCE verifiers, or client secrets in BuildConfig or source control.

## End-user flow

End users do not create an OAuth App, edit `local.properties`, enter a client ID, enter a client secret, or paste a Personal Access Token. They install the built application and tap **Continue with GitHub**. Their GitHub account produces the user-specific access token stored by the app.

The app requests `read:user` and `repo`: profile identification and repository read/write access required by clone, pull, and push. The login flow uses Authorization Code + PKCE (S256), validates the returned `state`, and stores the access token encrypted with an Android Keystore AES key.

Native Android clients are public clients. A client ID is not secret, and this implementation does not ship a client secret. Android sends only `code` and `code_verifier` to the fixed backend endpoint `/oauth/token`. Token exchange is isolated behind `OAuthTokenExchangeGateway`; `BackendOAuthTokenExchangeGateway` can later be replaced without changing the UI, ViewModels, or `AuthRepository` contract.

The configured Worker base URL is `https://gitclonepush-oauth.narutooasis55.workers.dev`. Android sends JSON to `POST /oauth/token`:

```json
{
  "code": "authorization-code",
  "code_verifier": "pkce-verifier"
}
```

Tokens are not logged, exposed in UI state, placed in navigation arguments, or embedded in Git remote URLs. JGit receives credentials only through its operation-time credentials provider.

GitHub OAuth App expiring access tokens are assumed to be disabled for this phase. Token refresh is intentionally not implemented yet.

## Automatic commit identity

Git author identity is read-only and follows the currently authenticated GitHub account. Settings displays it automatically; end users cannot edit it or save a separate manual identity.

- Author name uses the profile's display name, falling back to the GitHub username.
- Author email uses the public email returned by `GET /user`. When absent or invalid, the app uses `ID+USERNAME@users.noreply.github.com` to avoid requesting private email access. This is GitHub's modern noreply format; older accounts may have a different noreply configuration. GitHub account attribution is subject to the account's email settings, not an additional guarantee from this app. See [GitHub's email reference](https://docs.github.com/en/account-and-profile/reference/email-addresses-reference).
- Session restoration and account switching reload the profile and derive a fresh identity. Signing out clears the active identity together with the authenticated session.
- Normal commits explicitly supply both author and committer from this identity, so stale per-repository Git config cannot override the active account's identity.

`SessionGitAuthorIdentityReader` depends only on `AuthRepository`. Settings and workspace ViewModels depend on the focused, read-only `GitAuthorIdentityReader` interface, not on GitHub JSON, HTTP clients, tokens, or Android preferences. The previous manual identity preference store is no longer used; previously stored manual values are ignored. Existing commit history is not rewritten.

OAuth redirects, PKCE, state validation, token exchange, token storage, and scopes remain unchanged. No additional `/user/emails` request or OAuth scope is needed, and neither profile names nor email addresses are added to diagnostics.
