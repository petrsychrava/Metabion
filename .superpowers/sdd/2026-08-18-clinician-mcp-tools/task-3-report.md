# Task 3 Report

## Changed files

- `src/main/java/com/metabion/service/PatientAccessTokenService.java`
- `src/main/java/com/metabion/service/ClinicalAccessTokenService.java`
- `src/main/java/com/metabion/dto/oauth/IssuedMcpAccessToken.java`
- `src/main/java/com/metabion/dto/ClinicalAccessTokenSummaryResponse.java`
- `src/main/java/com/metabion/controller/api/ClinicalAccessTokenController.java`
- `src/main/java/com/metabion/service/oauth/OAuthTokenFamilyRevocationService.java`
- `src/test/java/com/metabion/service/ClinicalAccessTokenServiceTest.java`
- `src/test/java/com/metabion/service/PatientAccessTokenServiceTest.java`
- `src/test/java/com/metabion/controller/api/ClinicalAccessTokenControllerTest.java`
- `src/test/java/com/metabion/service/oauth/OAuthAccountTokenFamilyRevocationIntegrationTest.java`

## RED evidence

- Added the new clinical service test and patient compatibility assertions first.
- Ran:
  `./gradlew test --tests 'com.metabion.service.ClinicalAccessTokenServiceTest' --tests 'com.metabion.service.PatientAccessTokenServiceTest'`
- Result: `:compileTestJava FAILED` because `ClinicalAccessTokenService` and `IssuedMcpAccessToken` did not exist yet.

## GREEN evidence

- Ran:
  `./gradlew test --tests 'com.metabion.service.ClinicalAccessTokenServiceTest' --tests 'com.metabion.service.PatientAccessTokenServiceTest'`
- Result: `BUILD SUCCESSFUL`

- Ran:
  `./gradlew test --tests 'com.metabion.service.ClinicalAccessTokenServiceTest' --tests 'com.metabion.service.PatientAccessTokenServiceTest' --tests 'com.metabion.controller.api.ClinicalAccessTokenControllerTest' --tests 'com.metabion.service.oauth.OAuthAccountTokenFamilyRevocationIntegrationTest'`
- First rerun result: `:compileTestJava FAILED` in `ClinicalAccessTokenControllerTest` because the bearer-auth test accidentally wrapped a `ClinicalAccessToken` in `PatientAccessTokenAuthentication`.
- Fixed the test harness and reran the same focused command.
- Final result: `BUILD SUCCESSFUL`

## Compatibility decisions

- Preserved legacy patient authentication by keeping `authenticate(...)` hash-based; old unprefixed patient rows still resolve by their original SHA-256 hash.
- Switched only new patient issuance to `McpTokenCodec.generate(PATIENT)`, so new raw patient tokens start with `pat_`.
- Added a separate `ClinicalAccessTokenService` with clinician-only eligibility checks; only enabled, unlocked `PHYSICIAN` and `NUTRITION_SPECIALIST` users can issue or use clinical tokens.
- Kept manual account operations session-only for clinicians with the same owner-bound and CSRF-protected semantics as the patient account flow.
- Added no clinical manual-issuance POST endpoint; the controller only exposes `GET` and `DELETE` on `/api/account/clinical-access-tokens`.
- Tightened family revocation to derive subject routing from persisted refresh rows and revoke only the matching access-token repository.

## Concerns

- The focused brief suite passes, but the existing OAuth authorization-code exchange / refresh-issuance path still uses the earlier patient-only adapter outside this task’s edited file list. The new clinician token service is ready for that routing, but that internal OAuth path was not rewired in this slice.
- Docker-backed integration coverage was not exercised here; the green evidence comes from the focused Gradle suite above, including the H2-backed account family revocation integration test.
