# Task 2 Report: Validate And Save DCR Metadata

## Status

Implemented.

## Summary

- Added DCR request, response, and OAuth error DTO records under `com.metabion.dto.oauth`.
- Added `OAuthClientRegistrationException` for controller-layer OAuth error serialization in a later task.
- Added `OAuthClientRegistrationService.register(...)` to validate public-client DCR metadata, generate an `mcp_client_` client id, save an `OAuthRegisteredClient`, and return a public-client registration response with no client secret.
- Added focused service tests for loopback HTTP, HTTPS, invalid redirect metadata, unsupported scopes, confidential auth methods, and redirect URI count limits.

## Implementation Notes

- Scope validation is performed in the service so unsupported scopes return `invalid_scope`.
- Redirect URI policy, client name trimming/length, token endpoint auth persistence, supported scope persistence, maximum redirect count, and ordered redirect persistence are delegated to `OAuthRegisteredClient` from Task 1 where possible.
- Entity `IllegalArgumentException`s from metadata validation are translated to `OAuthClientRegistrationException` with `invalid_client_metadata`.
- No `/oauth/register` controller, security, metadata, or resolver integration was added.
- No client secret is issued or accepted.
- `MAX_REQUEST_BYTES` is exposed as `32_768` for the later controller request-size enforcement task.

## Verification

Red run:

```text
./gradlew test --tests com.metabion.service.oauth.OAuthClientRegistrationServiceTest
```

Failed at `:compileTestJava` because `OAuthClientRegistrationRequest`, `OAuthClientRegistrationService`, and `OAuthClientRegistrationException` did not exist.

Green run:

```text
./gradlew test --tests com.metabion.service.oauth.OAuthClientRegistrationServiceTest
```

Passed with `BUILD SUCCESSFUL`.

## Concerns

- IDEA MCP could read indexed project files but resolved new linked-worktree files against the main checkout during build validation, so final validation used the exact Gradle command from the task brief.

## Review Fix: 2026-07-09

### Status

Implemented review fixes.

### Summary

- Added explicit `client_secret` request metadata support and rejected nonblank values with `invalid_client_metadata`.
- Hard-capped `OAuthClientRegistrationService.maxRequestBytes()` at `32_768` even when configuration is higher.
- Added focused service tests for both reviewed behaviors.

### Verification

Red run:

```text
./gradlew test --tests com.metabion.service.oauth.OAuthClientRegistrationServiceTest
```

Failed at `:compileTestJava` because `OAuthClientRegistrationRequest` did not yet accept the added `client_secret` constructor argument.

Green run:

```text
./gradlew test --tests com.metabion.service.oauth.OAuthClientRegistrationServiceTest
```

Passed with `BUILD SUCCESSFUL`.

### Concerns

- Gradle emitted existing Java native-access and class-data-sharing warnings during the focused test run.
