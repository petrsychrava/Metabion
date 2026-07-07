# MCP OAuth Dynamic Client Registration Design

## Context

Metabion already exposes MCP over `POST /api/mcp` and supports OAuth authorization code with PKCE for patient-scoped access tokens. The first OAuth implementation intentionally skipped Dynamic Client Registration (DCR) and relied on preregistered clients such as Codex and Claude, plus HTTPS client metadata documents.

Real desktop MCP clients showed that this is not enough. LM Studio can trigger browser OAuth against other MCP servers, but reports that DCR or credentials are required for Metabion. Codex Desktop recognizes the configured Metabion MCP server, but does not expose tools because its OAuth client expects a `registration_endpoint` when no saved client information exists.

## Goals

- Make Codex Desktop, LM Studio, Claude, and similar desktop MCP clients able to authenticate without manual patient token creation.
- Add standards-aligned DCR while keeping the initial trust boundary narrow.
- Keep deployed server URLs configurable through existing OAuth properties.
- Preserve existing session login, consent, PKCE, patient role checks, resource binding, and scope enforcement.
- Remove preregistered Codex/Claude redirect URIs from the normal happy path.

## Non-Goals

- Do not support confidential OAuth clients or client secrets.
- Do not support arbitrary HTTPS redirect URIs in the first version.
- Do not build admin UI for registered clients.
- Do not add refresh tokens.
- Do not implement mobile-app custom URI schemes yet.

## Architecture

Add a new DCR endpoint:

```text
POST /oauth/register
```

The endpoint accepts OAuth public-client registration metadata and returns generated client information. Registered clients are stored in a new `oauth_registered_clients` table and resolved by `OAuthClientResolver` during authorization and token exchange.

`OAuthMetadataController` will advertise the registration endpoint from the configured issuer:

```json
{
  "registration_endpoint": "<metabion.oauth.issuer>/oauth/register",
  "token_endpoint_auth_methods_supported": ["none"],
  "code_challenge_methods_supported": ["S256"]
}
```

No Metabion server origin is hardcoded. Local development uses `http://localhost:8080` through `app.base-url`; deployment uses the configured public DNS origin through `metabion.oauth.issuer` and `metabion.oauth.resource`.

## Registration Rules

DCR clients are public clients only.

Accepted request shape:

```json
{
  "redirect_uris": ["http://127.0.0.1:49152/callback"],
  "client_name": "Codex",
  "scope": "patient:profile:read",
  "token_endpoint_auth_method": "none",
  "grant_types": ["authorization_code"],
  "response_types": ["code"]
}
```

Validation rules:

- `redirect_uris` is required, non-empty, and has at most 10 items.
- Every redirect URI must be loopback HTTP:
  - `http://127.0.0.1:<port>/...`
  - `http://localhost:<port>/...`
- Redirect URI ports must be explicit and valid.
- HTTPS callbacks, non-loopback hosts, wildcard hosts, fragments, user-info, and blank paths are rejected.
- `token_endpoint_auth_method` must be absent or `none`.
- `grant_types`, if present, must contain only `authorization_code`.
- `response_types`, if present, must contain only `code`.
- `scope` must be a non-empty subset of supported patient MCP scopes.
- `client_name` is optional, trimmed, limited to 120 characters, and used only for display/audit.
- Unknown JSON members are ignored after the request passes the normal JSON/body-size limit.
- Requests larger than 32 KB are rejected before validation.

The response includes no secret:

```json
{
  "client_id": "generated-public-client-id",
  "client_id_issued_at": 1783468800,
  "redirect_uris": ["http://127.0.0.1:49152/callback"],
  "client_name": "Codex",
  "scope": "patient:profile:read",
  "token_endpoint_auth_method": "none",
  "grant_types": ["authorization_code"],
  "response_types": ["code"]
}
```

## Client Classification

Preregistered clients should no longer be required for OAuth success. Existing `metabion.oauth.clients.*` support can remain temporarily for backward compatibility, but dynamic registration is the default path.

Audit/client type classification is separate from client authorization:

- `client_name` or known client ID containing Codex maps to `MCP_CODEX`.
- `client_name` or known client ID containing Claude maps to `MCP_CLAUDE`.
- LM Studio and unknown clients map to `MCP_OTHER` unless a later enum value is added.

Classification affects labels and audit records only. It must not decide whether a client may authenticate.

## OAuth Flow

1. MCP client calls `POST /api/mcp` without a token.
2. Metabion returns `401` with a `WWW-Authenticate` challenge pointing at protected resource metadata.
3. Client fetches protected resource metadata and authorization server metadata.
4. Client sees `registration_endpoint` and registers itself.
5. Client starts `/oauth/authorize` with generated `client_id`, registered loopback redirect URI, requested resource, scopes, and PKCE S256 challenge.
6. Patient logs in and approves consent.
7. Client exchanges the authorization code at `/oauth/token` using the same registered client, redirect URI, resource, and PKCE verifier.
8. Metabion issues a patient-bound access token for `metabion.oauth.resource`.
9. MCP calls use `Authorization: Bearer <access_token>`.

## Error Handling

DCR validation failures return OAuth-style JSON errors with appropriate HTTP status:

- `invalid_client_metadata` for malformed, unsupported, or unsafe registration metadata.
- `invalid_redirect_uri` only if the framework or chosen DTO design needs a more specific internal error; external clients should still receive standards-compatible OAuth error JSON.
- `invalid_scope` for unsupported or empty scopes.

Authorization and token endpoints continue to reject unknown clients, unregistered redirect URIs, unsupported scopes, missing PKCE, invalid resource values, expired codes, consumed codes, and non-patient users.

## Testing

Add focused tests for:

- Authorization server metadata includes `registration_endpoint`.
- Registration accepts Codex/LM Studio style loopback redirect URIs.
- Registration rejects non-loopback, HTTPS, missing-port, fragment, user-info, unsupported auth method, unsupported grant/response type, empty scope, unsupported scope, and oversized client names.
- `OAuthClientResolver` resolves dynamically registered clients and validates redirect URI membership.
- Existing config/preregistered clients still work if retained for compatibility.
- Full OAuth flow succeeds with a dynamically registered client.
- Existing MCP resource binding, bearer challenge, scope enforcement, and patient role tests still pass.

## Rollout

The first implementation keeps static config clients available for compatibility but moves examples and tests to dynamic registration. After Codex, LM Studio, and Claude are confirmed against DCR, static preregistered redirect URI properties can be deprecated or removed in a later cleanup.
