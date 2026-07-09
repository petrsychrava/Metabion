Task 6: Final Verification

Status: complete

Verification:
- `./gradlew test --tests com.metabion.config.OAuthAuthorizationPropertiesTest` passed after removing static client defaults.
- `./gradlew test --tests com.metabion.config.OAuthAuthorizationPropertiesTest --tests com.metabion.repository.OAuthRegisteredClientRepositoryTest --tests com.metabion.service.oauth.OAuthClientRegistrationServiceTest --tests com.metabion.controller.api.OAuthClientRegistrationControllerTest --tests com.metabion.service.oauth.OAuthClientResolverTest --tests com.metabion.service.oauth.OAuthAuthorizationServiceTest --tests com.metabion.controller.api.OAuthMetadataControllerTest --tests com.metabion.integration.McpOAuthFlowIT` passed.
- `./gradlew test --rerun-tasks` passed after removing static client defaults.

Notes:
- Dynamic Client Registration is advertised at `${metabion.oauth.issuer}/oauth/register`.
- Public clients use `token_endpoint_auth_method: none`; no client secret is returned.
- Redirect policy allows HTTPS redirect URIs and loopback HTTP redirect URIs with explicit ports.
- Codex and Claude are no longer preregistered in `application.properties`; explicit static client binding remains supported if configured.
