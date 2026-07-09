Task 5: Advertise DCR and Exercise Dynamic OAuth Flow

Status: complete

Changes:
- Added `registration_endpoint` to OAuth authorization-server metadata.
- Updated metadata tests to expect `/oauth/register`.
- Updated `McpOAuthFlowIT` to remove static Codex client properties.
- Made the integration flow register a public OAuth client first, then authorize and exchange using the generated `client_id`.
- Added end-to-end assertions that the stored patient token is resource-bound and classified as `MCP_CODEX` with display label `Codex`.

TDD evidence:
- Added metadata expectation for `registration_endpoint`.
- Updated MCP OAuth integration flow to use DCR instead of static client config.
- Verified the red run failed on missing `registration_endpoint`.

Verification:
- `./gradlew test --tests com.metabion.controller.api.OAuthMetadataControllerTest --tests com.metabion.integration.McpOAuthFlowIT` passed.
