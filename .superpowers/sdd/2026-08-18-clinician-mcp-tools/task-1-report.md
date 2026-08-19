# Task 1 Implementation Report

Commit: `b490a005f782f21523fcf8481f5249c8ad5e6ff3..119df4d2c1fe3c4c5073d77adc36cde71789a739`

Files changed:

- `src/main/java/com/metabion/domain/McpTokenSubject.java`
- `src/main/java/com/metabion/domain/ClinicalAccessTokenScope.java`
- `src/main/java/com/metabion/domain/ClinicalAccessTokenScopeGrant.java`
- `src/main/java/com/metabion/service/McpScopeCatalog.java`
- `src/main/java/com/metabion/service/McpTokenCodec.java`
- `src/main/java/com/metabion/service/McpTokenEligibility.java`
- `src/main/java/com/metabion/service/PatientAccessTokenService.java`
- `src/test/java/com/metabion/domain/ClinicalAccessTokenScopeTest.java`
- `src/test/java/com/metabion/service/McpScopeCatalogTest.java`
- `src/test/java/com/metabion/service/McpTokenCodecTest.java`
- `src/test/java/com/metabion/service/McpTokenEligibilityTest.java`

RED:

Command:

```bash
./gradlew test --tests 'com.metabion.service.McpScopeCatalogTest' --tests 'com.metabion.service.McpTokenCodecTest'
```

Evidence:

- `compileTestJava` failed before tests ran because the new shared primitives did not exist yet.
- Representative compiler errors were `cannot find symbol` for `McpTokenSubject`, `McpScopeCatalog`, `McpTokenCodec`, and `McpTokenEligibility`.

GREEN:

Command:

```bash
./gradlew test --tests 'com.metabion.service.McpScopeCatalogTest' --tests 'com.metabion.service.McpTokenCodecTest' --tests 'com.metabion.service.McpTokenEligibilityTest' --tests 'com.metabion.domain.ClinicalAccessTokenScopeTest'
```

Result:

- `BUILD SUCCESSFUL`
- `5 actionable tasks: 3 executed, 2 up-to-date`

Design decisions:

- Kept patient and clinician token families separate in code by introducing `McpTokenSubject`, a new clinical scope enum, and a dedicated clinical grant type.
- Implemented `McpScopeCatalog` as the single cross-family validator so mixed families are rejected in one place.
- Made `supportedAuthorities()` return the sorted union of both families.
- Kept `PatientAccessTokenService.sha256Hex(String)` as a compatibility delegate to `McpTokenCodec.sha256Hex(String)`.
- Left existing patient token generation in `PatientAccessTokenService` unchanged so legacy unprefixed patient tokens remain compatible.
- Enforced clinician eligibility as enabled + unlocked + physician/nutrition specialist, with admin and coordinator excluded.

Concerns:

- The new primitives are implemented and tested, but later tasks still need to wire them into the actual clinical token issuance and authentication flows.
- Legacy patient token generation intentionally remains unprefixed in the existing service until the later integration task replaces the raw-token path.

## Fix Addendum

Reviewer gaps addressed:

- Added the unknown-prefix routing case to `McpTokenCodecTest`.
- Added disabled-user rejection, admin-exclusion, and nutrition-specialist acceptance coverage to `McpTokenEligibilityTest`.
- Expanded `ClinicalAccessTokenScopeTest` to verify the enum exposes exactly the full clinician scope set.

Focused verification command:

```bash
./gradlew test --tests 'com.metabion.service.McpScopeCatalogTest' --tests 'com.metabion.service.McpTokenCodecTest' --tests 'com.metabion.service.McpTokenEligibilityTest' --tests 'com.metabion.domain.ClinicalAccessTokenScopeTest'
```

Output:

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses
> Task :test
> Task :jacocoTestReport

BUILD SUCCESSFUL in 1s
5 actionable tasks: 3 executed, 2 up-to-date
```
