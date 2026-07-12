# MCP OAuth Refresh Tokens Design

## Goal

Make Metabion's OAuth server interoperable with Codex dynamic client
registration and provide one secure, rotating refresh-token implementation
that is reusable by MCP clients and pre-registered native mobile apps.

## Scope

This change covers:

- accepting Codex's public native-client registration metadata;
- advertising and accepting the `refresh_token` grant;
- issuing a refresh token with the initial authorization-code exchange;
- rotating refresh tokens on every successful refresh;
- detecting reuse of a consumed refresh token and revoking its token family;
- revoking access tokens associated with a compromised refresh-token family;
- returning OAuth-compatible token errors without leaking credential state;
- resolving refresh-capable OAuth clients through a shared client abstraction
  regardless of whether they are dynamically or statically registered.

It does not add confidential OAuth clients, client secrets, mobile application
endpoints, offline consent controls, or a refresh-token management UI.

## Shared OAuth Client Model

Refresh-token issuance and rotation target a shared `OAuthClientMetadata`
model rather than an MCP-specific registration entity. The model exposes the
client ID, display label, application type, registration source, redirect URIs,
allowed scopes, and allowed grant types.

The resolver supports these public-client sources:

- dynamically registered native clients such as Codex;
- pre-registered native clients from `metabion.oauth.clients`, intended for
  first-party mobile applications;
- HTTPS client-ID metadata documents already supported by Metabion.

Pre-registered mobile clients declare `application-type=native` and grant types
including `authorization_code` and `refresh_token`. They use Authorization Code
with PKCE and receive the same token responses, rotation behavior, reuse
detection, scope enforcement, and revocation as Codex. No mobile app embeds a
client secret.

The refresh-token table stores the stable client ID and registration source,
not a foreign key limited to the dynamic MCP registration table. At refresh
time, the client is resolved again by ID and checked against the stored source,
grant, resource, and scope constraints. Removing or disabling a pre-registered
mobile client therefore prevents further refreshes. Dynamic-client deletion,
if added later, has the same effect.

## Dynamic Client Registration

The registration endpoint accepts the Codex request shape:

```json
{
  "client_name": "Codex",
  "redirect_uris": ["http://127.0.0.1:63603/callback/example"],
  "grant_types": ["authorization_code", "refresh_token"],
  "token_endpoint_auth_method": "none",
  "response_types": ["code"],
  "scope": "patient:profile:read",
  "application_type": "native"
}
```

`application_type` is modeled explicitly and must be absent or `native`.
Unknown extension metadata is ignored so standards-compatible clients can add
non-security-sensitive registration fields without causing a JSON mapping
failure. Security-sensitive supported fields remain explicitly validated.

The only accepted grant-type combinations are `authorization_code` and
`authorization_code refresh_token`; order and duplicates are normalized for
validation. `refresh_token` alone and all other grants are rejected. The
registration response returns the normalized grants supported for that client.
Registered clients remain public clients and must use
`token_endpoint_auth_method=none` without a client secret.
The normalized grant types and `application_type=native` are persisted with the
registered client so later refresh requests can verify the client's original
registration capabilities.

Authorization-server metadata advertises both `authorization_code` and
`refresh_token` in `grant_types_supported`.

## Refresh-Token Persistence

A new Flyway migration creates an `oauth_refresh_tokens` table and a scope
collection table. Each refresh-token row stores:

- a unique SHA-256 token hash, never the plaintext token;
- a cryptographically random family identifier shared across rotations;
- the patient, stable OAuth client ID, and client registration source;
- the resource, client display label, and patient access client type;
- the granted scopes;
- creation and absolute expiry timestamps;
- consumption time and replacement-token reference;
- revocation time and reason.

The initial and every rotated refresh token expire 30 days after issuance.
Rotation therefore uses a sliding 30-day lifetime. Family revocation prevents
future rotation even if another member has not expired.

Access tokens issued through OAuth gain an optional refresh-family identifier.
Manually issued patient access tokens keep this field null. This association
allows a compromised family to revoke only access tokens derived from that
family without affecting unrelated clients or manually issued tokens.

Patient and replacement-token foreign keys use database constraints. Token
hashes, family identifiers, and client IDs are indexed, and token hashes are
unique. The stored client ID and source bind refresh requests to the exact
resolved client identity without coupling the table to one registration source.

## Token Flows

### Authorization-code exchange

The existing PKCE and authorization-code validation remains unchanged. After
the code is locked and validated, one transaction:

1. consumes the authorization code;
2. creates the refresh-token family and stores the initial hashed refresh
   token;
3. issues the one-hour access token associated with that family;
4. returns the plaintext access and refresh tokens once.

The token response includes `refresh_token` in addition to the existing
`access_token`, `token_type`, `expires_in`, and `scope` fields.

### Refresh-token exchange

The token endpoint accepts `grant_type=refresh_token`, `refresh_token`,
`client_id`, and `resource`. Authorization-code-only parameters are optional at
the controller boundary and validated according to the selected grant.

The service hashes the presented token and loads its row with a pessimistic
write lock. It then validates that:

- the token exists, is not expired, and is not revoked;
- the client ID and resource match the stored values;
- the OAuth client still resolves from the same registration source and
  supports the refresh grant and stored scopes;
- the patient is enabled, unlocked, and still has the patient role.

For a valid unused token, the same transaction consumes it, stores its rotated
replacement in the same family, issues a new one-hour family-bound access
token, and returns the new plaintext refresh token.

Concurrent attempts are serialized by the database lock. Exactly one can
rotate successfully. A later attempt observes a consumed token and triggers
reuse handling.

## Reuse Detection and Revocation

Presentation of a previously consumed refresh token is treated as credential
reuse, even when the replacement token remains valid. In one transaction the
service:

1. revokes every non-revoked refresh token in the family with reason
   `refresh_token_reuse`;
2. revokes every non-revoked access token associated with that family using the
   same reason;
3. returns a generic OAuth token error.

The family and access-token revocations must commit before the error response is
raised. The service therefore records the revocation in a transaction that is
not rolled back by the public OAuth error (or returns a failure result that the
controller maps after the transaction commits).

An expired, unknown, wrong-client, wrong-resource, or already revoked token
also returns the same public error but does not revoke unrelated credentials.
The implementation must not log plaintext authorization codes, access tokens,
refresh tokens, session identifiers, or client secrets.

## Error Responses

The token endpoint maps token-flow failures to JSON OAuth errors rather than
Spring's default error body:

```json
{
  "error": "invalid_grant",
  "error_description": "refresh token is invalid"
}
```

Unsupported grant types use `unsupported_grant_type`. Invalid public-client or
resource binding during a token exchange uses a generic token error; responses
do not distinguish unknown, expired, revoked, consumed, or mismatched tokens.
All token endpoint failures use HTTP 400 unless authentication policy requires
another status.

Dynamic registration keeps its existing OAuth JSON error format, but malformed
JSON and invalid client metadata remain distinct internally and in tests.

## Configuration

Add `metabion.oauth.refresh-token-ttl` with environment override
`METABION_OAUTH_REFRESH_TOKEN_TTL` and default `P30D`. Existing one-hour access
token and five-minute authorization-code defaults remain unchanged.

Each entry under `metabion.oauth.clients` gains optional `application-type` and
`grant-types` properties. Defaults preserve existing configured clients as
public native authorization-code clients; mobile clients opt into refresh by
including `refresh_token` explicitly.

## Testing Strategy

Implementation follows red-green-refactor cycles. Coverage includes:

- controller acceptance of the captured Codex registration payload;
- client-resolution tests proving dynamic Codex and pre-registered mobile
  clients share the refresh service while retaining distinct policies;
- rejection of confidential, unsupported, and refresh-only registrations;
- metadata advertising both grants;
- initial exchange returning a refresh token while storing only its hash;
- successful rotation returning a different refresh token in the same family;
- rejection of unknown, expired, revoked, wrong-client, and wrong-resource
  refresh tokens;
- consumed-token reuse revoking the complete refresh family and associated
  access tokens;
- concurrent refresh attempts producing one rotation and one family
  revocation outcome;
- disabled, locked, or no-longer-patient users being unable to refresh;
- repository persistence and locking behavior against H2 where portable and
  PostgreSQL/Testcontainers where locking semantics require it;
- the complete test suite and a live `codex mcp login metabion` smoke test.

## Success Criteria

- `codex mcp login metabion` reaches the browser authorization page.
- Approving consent completes login and exposes Metabion MCP tools in Codex.
- Access-token expiry can be recovered through refresh without user login.
- Every successful refresh rotates the refresh token.
- Reusing a rotated token revokes its family and associated access tokens.
- No plaintext refresh token is persisted or logged.
- Existing LM Studio MCP authentication and manually issued patient access
  tokens continue to work.
- A pre-registered native mobile client can use the same PKCE authorization,
  refresh rotation, reuse detection, and revocation lifecycle without a client
  secret or a second token implementation.
