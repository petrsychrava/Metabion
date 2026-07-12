# PR Family Revocation Fix Report

## Outcome

- Added `OAuthTokenFamilyRevocationService` as the shared family-wide revocation boundary.
- Family revocation pessimistically locks the family marker first, then revokes the marker, all non-revoked refresh members, and all active family-bound access tokens in the caller's transaction.
- `PatientAccessTokenService.revokeForCurrentPatient` preserves row-only revocation for manual tokens and delegates family-bound tokens only after verifying patient ownership.
- `OAuthRefreshTokenService` now uses the same shared family-first revocation sequence for refresh-token reuse.
- Updated the refresh-token design specification with account-deletion semantics.

## TDD Evidence

### RED

`./gradlew test --tests '*PatientAccessTokenServiceTest' --tests '*OAuthAccountTokenFamilyRevocationIntegrationTest'`

Failed at `compileTestJava` because the test-required `OAuthTokenFamilyRevocationService` did not yet exist.

### GREEN

`./gradlew test --tests '*PatientAccessTokenServiceTest' --tests '*OAuthAccountTokenFamilyRevocationIntegrationTest' --tests '*OAuthRefreshTokenServiceTest' --tests '*OAuthRefreshTokenReuseIntegrationTest'`

Passed.

`./gradlew test --tests '*OAuthRefreshTokenConcurrencyTest'`

Passed after adding the shared service to the concurrency test's narrow Spring import set.

### Full suite

`./gradlew test`

Passed: 739 tests, 0 failures. The first full-suite attempt exposed only the missing narrow test import; after that correction, the fresh full suite completed successfully.

## Self-review

- Transaction boundary: no `REQUIRES_NEW`; both callers retain their existing `@Transactional` scope.
- Lock ordering: shared revocation always acquires `OAuthRefreshTokenFamilyRepository.findByIdForUpdate` before touching refresh-token or access-token members. Refresh reuse may reacquire the already-held family lock in the same transaction.
- Ownership: the account path reads the selected access token and verifies its patient owner before delegating; foreign tokens remain hidden as not found and cannot trigger family revocation.
- Manual tokens: null `refreshFamilyId` still revokes only the selected access-token row with `patient_request`.
- Secret safety: production changes add no token or credential logging. Test plaintext token fixtures are synthetic and remain confined to test code.

## Concerns

None identified. Family-bound account revocation depends on the family marker existing; a missing marker results in no family mutation, consistent with the database foreign-key invariant for valid persisted family-bound tokens.
