const GITHUB_TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token";
const FIXED_REDIRECT_URI = "https://aggiabintangrh.github.io/HomePageGitClonePush/oauth/callback.html";

interface Env {
  GITHUB_CLIENT_ID: string;
  GITHUB_CLIENT_SECRET: string;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname !== "/oauth/token") return json({ error: "not_found" }, 404);
    if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
    if (request.headers.get("content-type")?.split(";", 1)[0] !== "application/json") return json({ error: "invalid_content_type" }, 415);

    let payload: unknown;
    try {
      payload = await request.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }
    const body = payload as { code?: unknown; code_verifier?: unknown };
    const code = body.code;
    const verifier = body.code_verifier;
    if (typeof code !== "string" || code.length === 0 || typeof verifier !== "string" || verifier.length === 0) return json({ error: "invalid_request" }, 400);

    const tokenResponse = await fetch(GITHUB_TOKEN_ENDPOINT, {
      method: "POST",
      headers: { "accept": "application/json", "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ client_id: env.GITHUB_CLIENT_ID, client_secret: env.GITHUB_CLIENT_SECRET, code, redirect_uri: FIXED_REDIRECT_URI, code_verifier: verifier }),
    });
    if (!tokenResponse.ok) return json({ error: "token_exchange_failed" }, 502);

    const tokenPayload = await tokenResponse.json() as { access_token?: string; token_type?: string; scope?: string };
    if (!tokenPayload.access_token) return json({ error: "token_missing" }, 502);
    return json({ access_token: tokenPayload.access_token, token_type: tokenPayload.token_type, scope: tokenPayload.scope });
  },
};
