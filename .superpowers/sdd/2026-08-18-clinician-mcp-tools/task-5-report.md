# Task 5 Report: Common MCP Bearer Authentication Routing

## Changed, Deleted, and Renamed Files

Created:
- `src/main/java/com/metabion/config/McpTokenAuthentication.java`
- `src/main/java/com/metabion/config/ClinicalAccessTokenAuthentication.java`
- `src/main/java/com/metabion/config/McpBearerTokenAuthenticationFilter.java`
- `src/main/java/com/metabion/service/McpAccessAuditService.java`
- `src/test/java/com/metabion/service/McpAccessAuditServiceTest.java`

Modified:
- `src/main/java/com/metabion/config/PatientAccessTokenAuthentication.java`
- `src/main/java/com/metabion/config/SecurityConfig.java`
- `src/main/java/com/metabion/mcp/PatientMcpTools.java`
- `src/test/java/com/metabion/config/SecurityConfigTest.java`
- `src/test/java/com/metabion/integration/McpBearerSessionPersistenceIT.java`
- `src/test/java/com/metabion/mcp/PatientMcpToolsTest.java`

Deleted:
- `src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java`
- `src/main/java/com/metabion/service/PatientAccessAuditService.java`

Renamed:
- `src/test/java/com/metabion/config/PatientBearerTokenAuthenticationFilterTest.java` -> `src/test/java/com/metabion/config/McpBearerTokenAuthenticationFilterTest.java`
- `src/test/java/com/metabion/service/PatientAccessAuditServiceTest.java` -> `src/test/java/com/metabion/service/McpAccessAuditServiceTest.java`

## RED Evidence

Command:
`./gradlew test --tests 'com.metabion.config.McpBearerTokenAuthenticationFilterTest' --tests 'com.metabion.config.SecurityConfigTest'`

Result:
FAILED at `:compileTestJava` before implementation, as expected.

Key errors:
- `cannot find symbol: class McpAccessAuditService`
- `cannot find symbol: class McpBearerTokenAuthenticationFilter`
- `cannot find symbol: class ClinicalAccessTokenAuthentication`

This confirmed the new route/security tests were exercising missing common MCP bearer authentication behavior, not existing patient-only behavior.

## GREEN Evidence

Command:
`./gradlew test --tests 'com.metabion.config.McpBearerTokenAuthenticationFilterTest' --tests 'com.metabion.config.SecurityConfigTest'`

Result:
BUILD SUCCESSFUL in 7s.

Command:
`./gradlew test --tests 'com.metabion.config.McpBearerTokenAuthenticationFilterTest' --tests 'com.metabion.config.McpSecurityContextRepositoryTest' --tests 'com.metabion.integration.McpBearerSessionPersistenceIT' --tests 'com.metabion.mcp.PatientMcpToolsTest'`

Result:
BUILD SUCCESSFUL in 6s.

Command:
`./gradlew test --tests 'com.metabion.service.McpAccessAuditServiceTest'`

Result:
BUILD SUCCESSFUL in 1s.

Full-suite non-Docker attempt:
`./gradlew test`

Result:
FAILED after running broad non-Docker coverage because Docker/Testcontainers initialization failed in repository/integration tests. Exact summary: `1193 tests completed, 29 failed, 9 skipped`. The failures were `initializationError` failures from `DockerClientProviderStrategy`, matching the brief's Docker-backed test warning.

## Routing, Auth, Audit, and Security Context Decisions

- `McpBearerTokenAuthenticationFilter` runs only for `/api/mcp` and `/api/mcp/**`; non-MCP bearer requests fall through without invoking token services.
- Bearer values are routed through `McpTokenCodec.route(token)`:
  - `pat_` -> `PatientAccessTokenService.authenticateForResource(token, resource)`
  - `clin_` -> `ClinicalAccessTokenService.authenticateForResource(token, resource)`
  - legacy unprefixed 43-character Base64 URL tokens -> patient service only
  - invalid or malformed values -> no token service call and `401 invalid_token`
- Resource binding is preserved by passing `oauthProperties.resource()` into both patient and clinical service authentication.
- Patient authentication keeps the existing `token()` accessor and patient authorities, while implementing `McpTokenAuthentication`.
- Clinical authentication exposes `token()`, clinical subject metadata, current user role authorities, and `SCOPE_clinician:*` authorities derived from `ClinicalAccessTokenScope.authority()`.
- `PatientMcpTools.patientAuth()` remains type-strict: only `PatientAccessTokenAuthentication` is accepted, so clinical authentication cannot invoke patient tools.
- `McpAccessAuditService` records metadata only: subject, path or operation, user identity, token id, client label, and coarse failure reason. It does not log bearer token plaintext or clinical/patient request payload values.
- Existing `401` and `403` response behavior is preserved, including protected-resource `WWW-Authenticate` metadata and `insufficient_scope` mapping for forbidden token-service responses.
- Security context persistence continues to use the configured `McpSecurityContextRepository`, preserving request-attribute MCP context storage and existing async/error dispatch behavior.

## Concerns

- Full `./gradlew test` is not green in this environment because Docker/Testcontainers is unavailable; focused Task 5 commands and the added non-Docker audit smoke test pass.
- No clinical tool methods or assignment logic were added in this task.
