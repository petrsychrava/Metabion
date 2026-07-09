Task 4: Resolve Registered Clients During OAuth

Status: complete

Changes:
- Added `OAuthRegisteredClientRepository` to `OAuthClientResolver`.
- Resolved persisted dynamic clients before static configured clients and client-id metadata documents.
- Required exact redirect URI match for persisted dynamic clients.
- Returned dynamic client metadata using stored client id, client name, redirect URIs, and sorted scopes.
- Updated OAuth token issuance client-type classification to use resolved client metadata, so generated dynamic clients labeled Codex or Claude audit as their known MCP client type.

TDD evidence:
- Added failing resolver tests for dynamic client resolution and redirect rejection.
- Added failing token exchange test for a dynamically registered Codex client.
- Verified the initial red run failed because `OAuthClientResolver` had no dynamic-client constructor path.
- Verified the behavioral red run failed because dynamic Codex clients were not issued as `MCP_CODEX`.

Verification:
- `./gradlew test --tests com.metabion.service.oauth.OAuthClientResolverTest --tests com.metabion.service.oauth.OAuthAuthorizationServiceTest` passed.
- `git diff --check` passed.
