# OAuth token exchange worker

This Cloudflare Worker keeps the GitHub OAuth client secret off Android devices and GitHub Pages. It exposes only `POST /oauth/token`.

## Configuration

```powershell
npx wrangler secret put GITHUB_CLIENT_ID
npx wrangler secret put GITHUB_CLIENT_SECRET
npx wrangler deploy
```

Configure the deployed HTTPS Worker URL as the Android developer build value `oauth.backendBaseUrl` or CI variable `OAUTH_BACKEND_BASE_URL`. End users do not configure it.

`POST /oauth/token` accepts JSON containing only the authorization code and PKCE verifier:

```json
{
  "code": "authorization-code",
  "code_verifier": "pkce-verifier"
}
```

The GitHub OAuth App redirect URI must be exactly:

`https://aggiabintangrh.github.io/HomePageGitClonePush/oauth/callback.html`

Never commit the secret or place it in Android source, BuildConfig, GitHub Pages, logs, or this repository.
