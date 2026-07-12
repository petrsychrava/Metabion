# Task 5 Report: Reuse Detection, Family Revocation, and OAuth Errors

## Status

Implemented consumed refresh-token reuse detection, committed refresh/access-token family revocation through a separate `REQUIRES_NEW` service, and stable OAuth token endpoint errors.

## Changes

- Added `OAuthTokenException` with generic `invalid_grant` and `unsupported_grant_type` contracts.
- Added proxied `OAuthRefreshTokenRevocationService.revokeFamily` with `REQUIRES_NEW` semantics.
- Reused consumed refresh tokens revoke all active refresh and access tokens in their family before returning an error.
- Unknown, expired, revoked, client/resource mismatched, and patient credential-state failures share the same generic refresh-token error.
- Added focused service, persistence, and controller tests, including verification that the revocation transaction remains committed outside the caller transaction.

## TDD Evidence

- RED: focused suite failed to compile because `OAuthTokenException` and `OAuthRefreshTokenRevocationService` did not exist.
- GREEN: `./gradlew test --tests '*OAuthRefreshTokenServiceTest' --tests '*OAuthRefreshTokenRevocationServiceTest' --tests '*OAuthTokenControllerTest'` passed.
- Full verification: `./gradlew test` passed (`BUILD SUCCESSFUL`, 1m 8s).

## Self-review

- No secrets or plaintext tokens are logged.
- Revocation is isolated in a Spring bean so transaction propagation is proxy-applied.
- Unrelated refresh families and manual/unbound access tokens are not affected.
- Public credential-state failures do not disclose account state.

## Concerns

None identified.
