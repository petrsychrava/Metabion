# MCP OAuth Authorization Design

## Goal

Add a browser-based OAuth 2.1-style authorization flow for the patient MCP server, aligned with the MCP 2025-11-25 authorization specification. The first implementation should let MCP clients trigger patient login and consent in a browser, then receive a scoped bearer token for `/api/mcp`.

This design keeps the existing Metabion session login as the patient authentication layer and keeps the existing `PatientAccessToken` model as the MCP bearer-token mechanism. The new work adds OAuth discovery, authorization-code handling, PKCE validation, client validation, consent, and MCP-compatible authentication challenges.

## Current Context

The repository already has:

- Spring AI Streamable HTTP MCP server configured at `/api/mcp`, disabled by default.
- `PatientAccessToken` entities with random bearer tokens hashed at rest.
- Scoped patient token authorities through `PatientAccessTokenScope`.
- `PatientBearerTokenAuthenticationFilter` for `/api/mcp`.
- Patient-only token issuance, listing, and revocation through `/api/account/access-tokens`.
- Audit logging for MCP authentication and tool actions.
- Localhost-only MCP protection when `metabion.mcp.allowed-localhost-only=true`.

The missing piece is OAuth-compatible browser authorization and client discovery. Today a patient has to manually issue and copy a token.

## Target MCP Authorization Behavior

The implementation targets the MCP authorization specification version `2025-11-25`.

The first slice supports:

- OAuth protected resource metadata.
- OAuth authorization server metadata.
- Authorization code grant.
- PKCE with `S256` only.
- `WWW-Authenticate` challenges on unauthenticated and insufficient-scope MCP requests.
- Pre-registered clients for Codex and Claude.
- Client ID Metadata Document clients for other compatible MCP clients.
- Audience/resource binding for issued patient access tokens.

The first slice does not support:

- Dynamic Client Registration.
- Refresh tokens.
- JWT access tokens.
- Third-party identity providers.
- Non-patient authorization.

## Client Registration Strategy

### Pre-Registered Clients

Codex and Claude are configured as first-class clients.

Each pre-registered client has:

- `client_id`
- display label
- allowed redirect URI list
- allowed scopes or default scope set
- access token lifetime policy

Configuration should live in application properties under a dedicated namespace, for example:

```properties
metabion.oauth.clients.codex.display-label=Codex
metabion.oauth.clients.codex.redirect-uris=http://127.0.0.1:1455/oauth/callback
metabion.oauth.clients.claude.display-label=Claude
metabion.oauth.clients.claude.redirect-uris=http://127.0.0.1:1456/oauth/callback
```

The exact redirect URIs can be adjusted during implementation once local client behavior is confirmed.

### Client ID Metadata Documents

Other MCP clients can use Client ID Metadata Documents. In this mode, `client_id` is an HTTPS URL that returns client metadata. Metabion fetches that document and validates the requested redirect URI against the metadata.

Validation requirements:

- Client metadata document URL must use HTTPS.
- Fetching is disabled or tightly restricted in tests unless explicitly enabled.
- Request timeout is short.
- Response size is capped.
- Redirects are disabled or tightly limited.
- Private, loopback, link-local, multicast, and internal network addresses are rejected for metadata document fetches.
- The returned metadata must include a usable client name and redirect URI list.
- Requested redirect URI must exactly match a metadata redirect URI.
- Redirect URI must be HTTPS or loopback localhost HTTP.

### Dynamic Client Registration

Dynamic Client Registration is intentionally not implemented in the first slice.

Metabion will not expose `/oauth/register`, and authorization server metadata will not advertise a registration endpoint. A client that only supports Dynamic Client Registration will not work until that feature is deliberately designed and added later.

## Endpoints

### Protected Resource Metadata

`GET /.well-known/oauth-protected-resource`

Returns metadata for the MCP resource server. It identifies the protected resource, points to the authorization server, and declares bearer-token use for `/api/mcp`.

### Authorization Server Metadata

`GET /.well-known/oauth-authorization-server`

Returns OAuth metadata including:

- `issuer`
- `authorization_endpoint`
- `token_endpoint`
- `response_types_supported=["code"]`
- `grant_types_supported=["authorization_code"]`
- `code_challenge_methods_supported=["S256"]`
- supported scopes
- no `registration_endpoint`

### Authorization Endpoint

`GET /oauth/authorize`

Accepts standard authorization request parameters:

- `response_type=code`
- `client_id`
- `redirect_uri`
- `scope`
- `state`
- `code_challenge`
- `code_challenge_method=S256`
- `resource`

Behavior:

1. Validate request shape.
2. Resolve the client as either pre-registered or Client ID Metadata Document based.
3. Validate exact redirect URI match.
4. Validate requested scopes against `PatientAccessTokenScope`.
5. Validate `resource` against the configured MCP resource identifier.
6. Require patient session authentication. Anonymous users are sent through the existing login flow and returned to the authorization request afterward.
7. Require enabled, unlocked patient user.
8. Show a consent page summarizing client, scopes, resource, and token lifetime.

### Authorization Approval

`POST /oauth/authorize`

Requires CSRF protection and a session-authenticated patient.

On approval:

1. Re-validate the authorization request.
2. Store a short-lived authorization code record.
3. Store only the code hash.
4. Redirect to `redirect_uri` with `code` and original `state`.

On denial:

1. Redirect to `redirect_uri` with `error=access_denied` and original `state`.

### Token Endpoint

`POST /oauth/token`

Accepts form-encoded authorization-code exchange:

- `grant_type=authorization_code`
- `code`
- `redirect_uri`
- `client_id`
- `code_verifier`
- `resource`

Behavior:

1. Validate client.
2. Look up authorization code by hash.
3. Reject missing, expired, consumed, or mismatched code.
4. Validate client, redirect URI, resource, and patient.
5. Validate PKCE using `S256`.
6. Mark code consumed.
7. Issue a normal `PatientAccessToken` with the authorized scopes and resource.
8. Return OAuth token response with `access_token`, `token_type=Bearer`, `expires_in`, and `scope`.

The token endpoint does not issue refresh tokens in the first slice.

## Persistence

Add `oauth_authorization_codes`:

- `id`
- `code_hash`
- `user_id`
- `client_id`
- `client_display_label`
- `redirect_uri`
- `resource`
- `code_challenge`
- `code_challenge_method`
- `scopes`
- `created_at`
- `expires_at`
- `consumed_at`

Update `patient_access_tokens`:

- Add a `resource` column.
- Existing records can use the configured MCP resource identifier as a migration default if needed.

The bearer-token authentication filter must reject tokens whose `resource` does not match `/api/mcp`'s configured resource identifier.

## Security Rules

- PKCE `S256` is required.
- Plain PKCE is not supported.
- Authorization codes are random, high entropy, hashed at rest, one-time use, and short lived.
- Access tokens remain random, high entropy, hashed at rest, scoped, revocable, and bounded by expiry.
- Redirect URI matching is exact.
- Production redirect URIs must be HTTPS unless they are loopback localhost redirects.
- Production issuer and resource metadata URLs must be HTTPS.
- No token, authorization code, code verifier, session id, or credential is logged.
- Bearer-token authentication cannot mint additional tokens.
- Client metadata document fetches must include SSRF protections.
- Dynamic Client Registration is not exposed.
- CSRF remains enabled for browser approval.

## Error Handling

Unauthenticated `/api/mcp` requests return `401` and a `WWW-Authenticate` header that includes the protected resource metadata URL and useful scope guidance.

Insufficient MCP scopes return `403` and a `WWW-Authenticate` header with:

- `error="insufficient_scope"`
- required `scope`
- protected resource metadata URL

Authorization endpoint errors redirect to the client redirect URI only after the redirect URI has been validated. Before that point, errors are rendered as safe server-side pages or returned as `400`.

Token endpoint errors use OAuth-style JSON error responses without leaking whether a code exists for another client or user.

## Testing Plan

Add focused tests for:

- Protected resource metadata.
- Authorization server metadata, including `S256` PKCE and no registration endpoint.
- MCP `401` challenge with protected resource metadata.
- MCP `403 insufficient_scope` challenge.
- Pre-registered Codex and Claude client resolution.
- Client ID Metadata Document client resolution with mocked HTTP fetch.
- Rejection of unsafe metadata document URLs and unsafe redirect URIs.
- Authorization request validation failures.
- Anonymous authorization request routes through login.
- Patient consent creates only a hashed authorization code.
- Denial redirects with `access_denied`.
- Token exchange success issues a scoped, audience-bound patient token.
- Token exchange rejects wrong verifier, wrong redirect URI, wrong client, wrong resource, expired code, and reused code.
- Bearer authentication rejects tokens for the wrong resource.
- Existing patient MCP tool, access token, CSRF, and session-authentication tests still pass.

## Implementation Phases

1. Add OAuth and MCP authorization configuration properties.
2. Add metadata endpoints.
3. Add MCP `WWW-Authenticate` challenge support.
4. Add client resolver for pre-registered clients and Client ID Metadata Documents.
5. Add authorization-code domain, migration, and repository.
6. Add authorization service with request validation and PKCE helpers.
7. Add browser authorization and consent controller/template.
8. Add token endpoint.
9. Add resource binding to patient access tokens and bearer authentication.
10. Add tests and run focused verification, then full `./gradlew test` if feasible.

## Configuration Decision

The implementation will not hardcode production redirect URIs for Codex or Claude. It will provide configuration properties for both clients and use fixed loopback values only in tests and local development examples. A deployment must configure exact redirect URIs before those pre-registered clients are usable.
