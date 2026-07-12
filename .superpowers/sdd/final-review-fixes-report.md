# Final Review Fixes Report

## Scope

Addressed the final whole-branch review findings for OAuth client grant capability enforcement and OAuth-compliant `/oauth/token` error responses.

## Changes

- Authorization requests and authorization-code exchanges now reject resolved clients that do not support `authorization_code`.
- Authorization-code-only clients receive a bearer access token through the non-family issuance path. Their response omits `refresh_token` and the persisted access token has no refresh-family binding.
- Only clients explicitly supporting `refresh_token` receive an initial refresh token and family-bound access token.
- Token endpoint parameters are optional at Spring binding and validated by grant routing, preventing generic Spring missing-parameter responses.
- Missing or blank common and grant-specific parameters return `invalid_request` JSON.
- Invalid, expired, or consumed codes; verifier failures; client/redirect/resource mismatches; and unsupported authorization-code client capabilities return generic `invalid_grant` JSON.
- Unknown grants continue to return `unsupported_grant_type`; refresh credential failures retain their generic refresh `invalid_grant` response.
- `OAuthTokenResponse` uses `NON_NULL`, so authorization-code-only responses omit `refresh_token`.
- Authorization-endpoint validation remains MVC-oriented (`ResponseStatusException`) and was not weakened.

## TDD Evidence

### RED

Command:

```text
./gradlew test --tests com.metabion.service.oauth.OAuthAuthorizationServiceTest --tests com.metabion.controller.api.OAuthTokenControllerTest
```

Result: `26 tests completed, 7 failed`.

Expected failures demonstrated:

- unconditional initial refresh-token issuance and family dereference for authorization-code-only clients;
- no authorization-code capability enforcement;
- null `refresh_token` serialized in the token response;
- missing parameters bypassing OAuth error mapping;
- token-flow credential failures using MVC-oriented exceptions.

The first full-suite run then provided a second RED regression signal:

```text
./gradlew test
```

Result: `737 tests completed, 1 failed` (`McpOAuthFlowIT`). The failure showed that passing a null family to the family-bound token constructor violated its invariant. The implementation was corrected to use the existing non-family access-token issuance overload.

### GREEN

Focused command:

```text
./gradlew test --tests com.metabion.integration.McpOAuthFlowIT --tests com.metabion.service.oauth.OAuthAuthorizationServiceTest --tests com.metabion.controller.api.OAuthTokenControllerTest --tests com.metabion.service.oauth.OAuthClientResolverTest --tests com.metabion.service.oauth.OAuthClientRegistrationServiceTest --tests com.metabion.controller.api.OAuthClientRegistrationControllerTest
```

Result: `BUILD SUCCESSFUL in 7s`, 53 tests total, 0 failures:

- `OAuthAuthorizationServiceTest`: 17
- `OAuthTokenControllerTest`: 9
- `OAuthClientResolverTest`: 10
- `OAuthClientRegistrationServiceTest`: 12
- `OAuthClientRegistrationControllerTest`: 4
- `McpOAuthFlowIT`: 1

Final full verification:

```text
./gradlew test
```

Result: `BUILD SUCCESSFUL in 1m 22s`, 737 tests, 0 failures. Jacoco report generation completed.

Additional check:

```text
git diff --check
```

Result: exit 0, no whitespace errors.

## Concerns

No known functional concerns remain. Gradle emits its existing Java native-access warning; it does not affect test results.
