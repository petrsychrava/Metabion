# Task 8 Report: Verify clinician MCP assignment boundaries

## Changed Files

- `src/test/java/com/metabion/service/ClinicalPatientDirectoryServiceTest.java`
- `src/test/java/com/metabion/service/ClinicalOverviewServiceTest.java`
- `src/test/java/com/metabion/service/ClinicalDailyCheckInServiceTest.java`
- `src/test/java/com/metabion/service/LabResultServiceTest.java`
- `src/test/java/com/metabion/service/LabTrendServiceTest.java`
- `src/test/java/com/metabion/service/OnboardingServiceTest.java`
- `src/test/java/com/metabion/service/redflag/RedFlagEventQueryServiceTest.java`
- `src/test/java/com/metabion/integration/ClinicianMcpToolsIT.java`
- `src/test/java/com/metabion/integration/McpOAuthFlowIT.java`
- `src/test/java/com/metabion/service/SymptomTrackingServiceTest.java`
- `src/test/java/com/metabion/service/DietLogPhotoServiceTest.java`

The Task 8 production changes in the administrator clinical-read services were reverted back to base commit
`529208b`. MCP clinician eligibility is enforced separately at the common bearer/token layer and
`McpTokenEligibility`; existing administrator REST/service authorization contracts must remain unchanged.

- `src/main/java/com/metabion/service/ClinicalPatientDirectoryService.java`
- `src/main/java/com/metabion/service/ClinicalOverviewService.java`
- `src/main/java/com/metabion/service/LabResultService.java`
- `src/main/java/com/metabion/service/LabTrendService.java`
- `src/main/java/com/metabion/service/OnboardingService.java`
- `src/main/java/com/metabion/service/redflag/RedFlagEventQueryService.java`

No SDD progress ledger changes were made.

## Coverage Added

- Service-boundary regression tests for assigned physician and nutrition-specialist reads, coordinator/admin rejection, unassigned rejection before patient/result lookup where the service contract supports it, ended-assignment denial on the next request, and cross-patient isolation for directory, overview, daily check-ins, labs, trends, onboarding, and red flags.
- Administrator REST/service contract tests restored for directory, overview, labs, trends, onboarding, and red flags, matching the pre-Task-8 behavior while leaving clinician MCP token eligibility restricted to physician and nutrition-specialist users.
- Symptom boundary tests added for assigned physician/nutrition-specialist reads, unassigned rejection before check-in repository lookup, ended-assignment denial, and cross-patient rejection for `listClinicalCheckIns`.
- Diet-log photo boundary tests added for assigned physician/nutrition-specialist reads and unassigned, ended-assignment, and cross-patient denial before storage content reads.
- Clinical MCP integration coverage using real OAuth-issued bearer tokens and `/api/mcp` through the common bearer filter.
- Clinical token prefix and `clinical_access_tokens` storage isolation.
- Patient token compatibility and `patient_access_tokens` storage isolation.
- Patient/clinician MCP tool-family separation by rejecting wrong-subject tool calls.
- Assignment lifecycle denial after ending an assignment.
- Disabled, locked, and role-removed clinician token rejection.
- Subject-specific refresh rotation for patient and clinician token families.

## TDD / Results

Red run:

```bash
./gradlew test --tests 'com.metabion.service.ClinicalPatientDirectoryServiceTest' --tests 'com.metabion.service.ClinicalOverviewServiceTest' --tests 'com.metabion.service.LabResultServiceTest' --tests 'com.metabion.service.LabTrendServiceTest' --tests 'com.metabion.service.OnboardingServiceTest' --tests 'com.metabion.service.redflag.RedFlagEventQueryServiceTest' --tests 'com.metabion.service.SymptomTrackingServiceTest' --tests 'com.metabion.service.DietLogPhotoServiceTest'
```

Result: failed with 6 admin-contract failures after restoring the admin assertions and before reverting production: directory, lab result, lab trend, onboarding list/read, and red-flag admin reads were still returning `403 FORBIDDEN`.

Green focused suite:

```bash
./gradlew test --tests 'com.metabion.service.ClinicalPatientDirectoryServiceTest' --tests 'com.metabion.service.ClinicalOverviewServiceTest' --tests 'com.metabion.service.ClinicalDailyCheckInServiceTest' --tests 'com.metabion.service.LabResultServiceTest' --tests 'com.metabion.service.LabTrendServiceTest' --tests 'com.metabion.service.OnboardingServiceTest' --tests 'com.metabion.service.redflag.RedFlagEventQueryServiceTest' --tests 'com.metabion.integration.ClinicianMcpToolsIT' --tests 'com.metabion.service.SymptomTrackingServiceTest' --tests 'com.metabion.service.DietLogPhotoServiceTest' --tests 'com.metabion.integration.McpOAuthFlowIT'
```

Result: `BUILD SUCCESSFUL`.

Additional service-only red/green check:

```bash
./gradlew test --tests 'com.metabion.service.ClinicalPatientDirectoryServiceTest' --tests 'com.metabion.service.ClinicalOverviewServiceTest' --tests 'com.metabion.service.LabResultServiceTest' --tests 'com.metabion.service.LabTrendServiceTest' --tests 'com.metabion.service.OnboardingServiceTest' --tests 'com.metabion.service.redflag.RedFlagEventQueryServiceTest' --tests 'com.metabion.service.SymptomTrackingServiceTest' --tests 'com.metabion.service.DietLogPhotoServiceTest'
```

Result: `BUILD SUCCESSFUL`.

## Environment Limitations

No Docker/Testcontainers initialization failure occurred in this focused slice. The integration tests ran against the existing H2-based test configuration.
