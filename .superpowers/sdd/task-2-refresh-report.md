# Task 2 Refresh-Token Persistence Report

## Summary

- Added the `OAuthRefreshToken` persistence aggregate with hashed-token lookup, eager owner/role/scope loading, family lookup, expiration, consumption/replacement, and revocation state.
- Added the refresh-token scope embeddable and repository, including pessimistic-write lookup by token hash.
- Bound OAuth-issued patient access tokens to an optional refresh family while preserving the existing manual-token constructor.
- Added a bulk family revocation update for active access tokens.
- Extended the existing V17 migration from Task 1 so the client-capability, access-token family, and refresh-token schema remain one internally consistent Flyway version.

## Commands and Results

1. `./gradlew test --tests '*OAuthRefreshTokenRepositoryTest' --tests '*PatientAccessTokenRepositoryTest'`
   - RED: failed during `compileTestJava` with 6 expected missing-symbol/API errors.
2. `./gradlew test --tests '*OAuthRefreshTokenRepositoryTest' --tests '*PatientAccessTokenRepositoryTest'`
   - GREEN: `BUILD SUCCESSFUL` in 5s.
3. `./gradlew test`
   - Full verification: `BUILD SUCCESSFUL` in 1m 10s; 712 tests, 0 failures, 0 errors, 0 skipped.
4. `git diff --check`
   - Passed with no whitespace errors.

## RED Evidence

The focused tests failed before production changes because `OAuthRefreshToken` and `OAuthRefreshTokenRepository` did not exist, `PatientAccessToken` lacked the family-aware constructor and `getRefreshFamilyId()`, and `PatientAccessTokenRepository` lacked `revokeByRefreshFamilyId(...)`. Gradle exited 1 with `:compileTestJava FAILED` and exactly those six errors.

## GREEN Evidence

After the minimal entity, repository, migration, and access-token changes, the identical focused command completed with exit 0 and `BUILD SUCCESSFUL`. The subsequent complete `./gradlew test` run also completed with exit 0 and all 712 tests passing.

## Files Changed

- `src/main/resources/db/migration/V17__oauth_client_capabilities.sql`
- `src/main/java/com/metabion/domain/OAuthRefreshToken.java`
- `src/main/java/com/metabion/domain/OAuthRefreshTokenScopeGrant.java`
- `src/main/java/com/metabion/domain/PatientAccessToken.java`
- `src/main/java/com/metabion/repository/OAuthRefreshTokenRepository.java`
- `src/main/java/com/metabion/repository/PatientAccessTokenRepository.java`
- `src/test/java/com/metabion/repository/OAuthRefreshTokenRepositoryTest.java`
- `src/test/java/com/metabion/repository/PatientAccessTokenRepositoryTest.java`

## Self-Review

- Confirmed all required aggregate methods and repository interfaces are present.
- Confirmed the token-hash lookup has `PESSIMISTIC_WRITE` and eagerly loads user, roles, and scopes.
- Confirmed family lookup eagerly loads the same aggregate associations.
- Confirmed replacement IDs reference a persisted replacement token in the round-trip test, matching the production foreign key.
- Confirmed family revocation excludes already-revoked tokens and cannot affect manual tokens because their family ID remains null.
- Confirmed Task 1's existing V17 structures were preserved and extended instead of introducing a duplicate Flyway version.
- Confirmed unrelated untracked SDD coordination files were not modified or staged.

## Concerns

- The repository persistence tests use Hibernate H2 schema generation, as existing repository tests do. The full suite passed, but this task does not add a dedicated PostgreSQL migration integration test.

## Fix Review Findings

### Summary

- Tightened `OAuthRefreshToken` construction to accept only exactly 64 hexadecimal characters as the persisted token hash.
- Made the family-aware `PatientAccessToken` constructor reject null and blank family IDs while preserving the legacy constructor as the sole null-family issuance path.

### RED Evidence

Command: `./gradlew test --tests '*OAuthRefreshTokenRepositoryTest' --tests '*PatientAccessTokenRepositoryTest'`

Result: exit 1, `BUILD FAILED` in 5s. Six focused tests ran and two failed as intended:

- `OAuthRefreshTokenRepositoryTest > rejectsTokenHashesThatAreNotExactly64HexadecimalCharacters()` failed because short and 64-character non-hex hashes were accepted.
- `PatientAccessTokenRepositoryTest > familyAwareConstructorRejectsMissingFamilyId()` failed because a blank family ID was accepted (the first assertion stopped execution before the null case).

### GREEN Evidence

Focused command: `./gradlew test --tests '*OAuthRefreshTokenRepositoryTest' --tests '*PatientAccessTokenRepositoryTest'`

Result: exit 0, `BUILD SUCCESSFUL` in 5s; all focused tests passed.

Full command: `./gradlew test`

Result: exit 0, `BUILD SUCCESSFUL` in 1m 10s; 714 tests, 0 failures, 0 errors, 0 skipped.

### Files Changed

- `src/main/java/com/metabion/domain/OAuthRefreshToken.java`
- `src/main/java/com/metabion/domain/PatientAccessToken.java`
- `src/test/java/com/metabion/repository/OAuthRefreshTokenRepositoryTest.java`
- `src/test/java/com/metabion/repository/PatientAccessTokenRepositoryTest.java`
- `.superpowers/sdd/task-2-refresh-report.md`

### Commit

- Subject: `Harden OAuth refresh token persistence`

### Concerns

- No new concerns beyond the existing lack of a dedicated PostgreSQL migration integration test.
