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

---

## Architectural Correction: Family-Row Serialization

The original `REQUIRES_NEW` design was replaced after review identified a
PostgreSQL self-block: rotation held a pessimistic refresh-token row lock, then
suspended that transaction while the new transaction attempted to update the
same row.

### Corrected implementation

- Added first-class `OAuthRefreshTokenFamily` persistence with validated ID,
  creation time, one-way revocation state, and a pessimistic family lookup.
- Updated V17 so refresh tokens and nullable OAuth access-token family IDs
  reference `oauth_refresh_token_families`.
- Initial issuance persists the family before its first token members.
- Refresh processing discovers the immutable family ID without a lock, locks
  the family row, rereads the token, and performs rotation or reuse revocation
  while holding that single serialization lock.
- Reuse marks the family and revokes all refresh/access members in the same
  transaction; the unsafe `OAuthRefreshTokenRevocationService` was removed.
- `OAuthRefreshTokenService.refreshGrant` returns a normal success-or-invalid
  result so its transaction commits. `OAuthAuthorizationService.refresh` uses
  `NOT_SUPPORTED` and raises `OAuthTokenException` only after the transactional
  call returns.
- Access-token issuance for a successful refresh now occurs inside the family-
  locked refresh transaction.
- The approved design and implementation plan now document the family-row and
  result-boundary architecture.

### Corrected TDD evidence

1. Family persistence RED: focused compilation failed with 11 expected errors
   for the absent family entity/repository, family discovery, family lock, and
   token reread interfaces.
2. Family persistence GREEN: family domain, repository, and refresh service
   tests passed after adding the family aggregate and lock order.
3. Commit-boundary RED: `OAuthRefreshTokenReuseIntegrationTest` failed to
   compile because transactional `refreshGrant` and its result type were absent.
4. Commit-boundary GREEN: the real transactional service test persisted a
   consumed family, invoked reuse handling without mocked revocation, returned
   invalid normally, then verified committed family, refresh-token, and access-
   token revocation before the public exception was raised.
5. Revoked-family RED: the focused test proved the old implementation created
   a replacement for a revoked family.
6. Revoked-family GREEN: checking the locked family marker prevented both
   replacement refresh and access-token issuance; a real-service follow-up
   attempt also left the revoked family member count unchanged.
7. Lifecycle-validation RED/GREEN: overlong family IDs/reasons and revocation
   before creation were first accepted, then rejected after domain validation.

### Verification

- Covering focused suite:
  `./gradlew test --tests '*OAuthRefreshTokenFamilyTest' --tests '*OAuthRefreshTokenRepositoryTest' --tests '*PatientAccessTokenRepositoryTest' --tests '*OAuthRefreshTokenServiceTest' --tests '*OAuthRefreshTokenReuseIntegrationTest' --tests '*OAuthAuthorizationServiceTest' --tests '*OAuthTokenControllerTest'`
  — `BUILD SUCCESSFUL` in 6s.
- Full suite: `./gradlew test` — `BUILD SUCCESSFUL` in 1m 8s.

### Corrected concerns

Task 5 verifies the serialization invariant and committed reuse path on H2.
Task 6 remains responsible for broader PostgreSQL/Testcontainers interleaving
coverage of the pessimistic family lock.
