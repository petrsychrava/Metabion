# Patient Onboarding and Baseline Profile Forms Design

Date: 2026-05-31

## Goal

Implement structured onboarding forms for patient profile, baseline IBD status, medication context, and optional recent laboratory values.

The feature must support both existing application surfaces:

- REST endpoints for authenticated API clients.
- MVC pages under the authenticated `/app` area.

Onboarding data is saved with timestamps, versioned per patient/context, and reviewable by assigned clinical staff through the existing RBAC patient assignment model.

## Existing Context

The application already has session-based authentication, REST auth endpoints, MVC auth pages, and role-based access. The RBAC patient assignment work adds:

- `RoleName` with `PATIENT`, `NUTRITION_SPECIALIST`, `PHYSICIAN`, `COORDINATOR`, and `ADMIN`.
- `PatientProfile` linked one-to-one to `User`.
- `StaffProfile` linked one-to-one to clinical staff users.
- Direct patient-to-staff assignments and cohort-based staff access.
- `AccessControlService` for patient-profile and cohort authorization decisions.

Onboarding should build on these boundaries. It must not create a parallel expert role model or duplicate assignment queries.

## Chosen Approach

Use versioned structured onboarding submissions tied to `PatientProfile`.

Each patient/context pair can have multiple submissions. The latest version is the current baseline; older versions remain reviewable for audit and future study/program use. A lightweight `onboardingContext` string scopes submissions without introducing a full study/program domain.

Alternatives considered:

- Single editable profile rows: simpler, but overwrites historical baselines and weakens review auditability.
- JSON blob submissions: flexible, but makes validation, querying, reporting, and schema review less explicit.

The chosen approach is more work than a single editable row, but it keeps clinical baseline data trustworthy and supports later multi-study onboarding without replacing the model.

## Data Model

Add an onboarding domain centered on `OnboardingSubmission`.

`OnboardingSubmission` references `patient_profile_id`, not `user_id`. It stores `onboarding_context` and a monotonically increasing `version` within each patient/context pair.

Recommended columns:

- Metadata: `id`, `patient_profile_id`, `onboarding_context`, `version`, `created_at`, `submitted_at`.
- Patient profile: `date_of_birth`, `sex`, `country_region`, `timezone`.
- IBD status: `diagnosis_type`, `diagnosis_year`, `disease_location`, `disease_behavior`, `activity_estimate`.
- Medication context: `current_medications`, `steroid_use`, `advanced_therapy_exposure`, `medication_notes`.
- Optional recent labs: `labs_collected_at`, `crp_mg_l`, `fecal_calprotectin_ug_g`, `hemoglobin_g_dl`, `albumin_g_dl`, `lab_notes`.
- Review: `review_status`, `reviewed_by_user_id`, `reviewed_at`, `review_notes`.

Use enums for stable constrained values:

- `Sex`
- `IbdDiagnosisType`
- `DiseaseActivityEstimate`
- `SteroidUse`
- `AdvancedTherapyExposure`
- `OnboardingReviewStatus`

Keep `disease_location` and `disease_behavior` as nullable, length-limited strings in the first version. Strict Montreal classification can be added later if needed.

Database constraints:

- Unique `(patient_profile_id, onboarding_context, version)`.
- `version` must be positive.
- `review_status` defaults to `PENDING_REVIEW`.
- `reviewed_by_user_id` references `users(id)` and uses `ON DELETE SET NULL`.
- Text fields are length-limited.
- Lab values are nullable numeric columns.

## Service Boundary

Add `OnboardingService` as the single business boundary for REST and MVC.

Patient-facing methods:

- `submitForCurrentPatient(authentication, request)`: resolves the current user to `PatientProfile`, normalizes context, computes the next version, validates, saves a new submission, and returns a response DTO.
- `getLatestForCurrentPatient(authentication, context)`: returns the latest submission for the current patient/context.
- `listHistoryForCurrentPatient(authentication, context)`: returns versions newest-first.

Staff/admin-facing methods:

- `listReviewable(authentication, filters)`: returns submissions visible to the current clinical staff/admin user, filtered by context and review status. The initial implementation may use repository queries for admin listings and assignment-aware repository queries or service filtering for clinical staff listings, but every returned row must satisfy `AccessControlService.canAccessPatientProfile(...)`.
- `getReviewable(authentication, submissionId)`: loads one submission and checks access.
- `review(authentication, submissionId, request)`: checks access and sets review metadata.

The service should resolve the current user from `Authentication.getName()` using `UserRepository`. Patient self-access is based on `PatientProfileRepository.findByUserId(currentUser.id)`. Staff/admin review access goes through `AccessControlService.canAccessPatientProfile(authentication, patientProfileId)`.

## Authorization

Authorization rules:

- `PATIENT`: can create and read only their own onboarding submissions.
- `NUTRITION_SPECIALIST`, `PHYSICIAN`, `COORDINATOR`: can read and review submissions for patients they can access through direct or cohort assignment.
- `ADMIN`: can read and review all submissions.
- Patients cannot review submissions, including their own.
- Clinical staff cannot create onboarding submissions for patients in this phase.

The feature relies on the existing RBAC assignment model. It should not add onboarding-specific assignment tables.

## REST API

Patient endpoints:

- `POST /api/onboarding/submissions`
  Creates a new onboarding version for the authenticated patient.
- `GET /api/onboarding/submissions/latest?context=default`
  Returns the current patient's latest submission for a context.
- `GET /api/onboarding/submissions?context=default`
  Returns the current patient's submission history.

Clinical review endpoints:

- `GET /api/clinical/onboarding/submissions?context=default&status=PENDING_REVIEW`
  Lists visible submissions for assigned patients. Admin sees all.
- `GET /api/clinical/onboarding/submissions/{id}`
  Shows one visible submission.
- `POST /api/clinical/onboarding/submissions/{id}/review`
  Marks a visible submission as reviewed or needing follow-up.

DTOs:

- `OnboardingSubmissionRequest`
- `OnboardingSubmissionResponse`
- `OnboardingSubmissionSummaryResponse`
- `OnboardingReviewRequest`

REST controllers should stay thin and delegate validation, versioning, and authorization decisions to `OnboardingService`.

## MVC Pages

Patient pages:

- `/app/onboarding`
  Shows the latest submission and a form to submit a new version.
- `/app/onboarding/history`
  Shows prior versions for the selected context.

Clinical pages:

- `/app/clinical/onboarding`
  Shows reviewable submissions for assigned patients.
- `/app/clinical/onboarding/{id}`
  Shows a read-only submission detail page with a review action.

MVC controllers should use the same service and DTO validation path as REST wherever practical. Route-level security remains a broad authenticated gate; service-level checks enforce ownership and assignment.

## Validation

Submission validation:

- `onboardingContext`: optional at the request boundary, normalized to `default` when omitted, trimmed, required after normalization, max length 100.
- `dateOfBirth`: required and must be in the past.
- `sex`: required enum.
- `countryRegion`: required, trimmed, length-limited.
- `timezone`: required and must be a valid `ZoneId`.
- `diagnosisType`: required enum.
- `diagnosisYear`: optional, but if supplied must be between 1900 and the current year.
- `activityEstimate`: required enum.
- `diseaseLocation`, `diseaseBehavior`: optional, trimmed, length-limited.
- `currentMedications`, `medicationNotes`, `labNotes`: optional, trimmed, length-limited.
- `steroidUse`, `advancedTherapyExposure`: required enums.
- Lab values: optional, nonnegative, and within broad clinical sanity ranges: CRP up to 500 mg/L, fecal calprotectin up to 10000 ug/g, hemoglobin up to 25 g/dL, and albumin up to 10 g/dL.
- `labsCollectedAt`: required if any lab value is supplied.

Review validation:

- Review status can be set to `REVIEWED` or `NEEDS_FOLLOW_UP`.
- Review notes are optional and length-limited.
- Review action sets `reviewedBy`, `reviewedAt`, `reviewStatus`, and `reviewNotes`.
- Review action does not modify submitted baseline content.

## Review States

Supported review states:

- `PENDING_REVIEW`: default for every new submission.
- `REVIEWED`: staff/admin has accepted the submission.
- `NEEDS_FOLLOW_UP`: staff/admin has flagged the submission for clarification or follow-up.

Creating a new version starts a fresh review state. Older versions keep their existing review metadata.

## Error Handling

Expected error behavior:

- Unauthenticated requests return `401`.
- Patient requests without a patient profile return `403`, because the authenticated account is not authorized to use patient onboarding until a `PatientProfile` exists.
- Staff/admin requests for inaccessible submissions return `403`.
- Invalid DTO input returns the existing validation error shape.
- Missing submissions return `404`.

No passwords, tokens, session identifiers, or sensitive clinical field values should be logged in controller or service errors.

## Testing Strategy

Repository tests:

- Version uniqueness per `(patient_profile_id, onboarding_context, version)`.
- Latest lookup by patient/context.
- History ordering newest-first.
- Flyway schema validation for onboarding migration.

Service tests:

- Patient submission creates version 1, then version 2 for the same context.
- Different contexts maintain independent version sequences.
- Context normalization defaults empty input to `default`.
- Patient history is limited to the authenticated patient's profile.
- Staff/admin review uses `AccessControlService`.
- Unassigned staff cannot read or review.
- Review action updates only review metadata.

Controller and MVC tests:

- REST patient submit/latest/history endpoints require authentication.
- Clinical REST endpoints allow assigned clinical staff and admin.
- Clinical REST endpoints reject unassigned clinical staff and patients.
- MVC patient form renders, validates errors, submits successfully, and shows latest data.
- MVC clinical review list/detail enforce assignment access.

Integration coverage:

- A clinical staff user assigned through direct or cohort RBAC can review a patient's onboarding submission.
- An unassigned clinical staff user cannot review the same submission.

## Scope Exclusions

This phase does not include:

- A full study/program entity model.
- File uploads or lab report attachments.
- Clinician comment threads.
- Staff-created onboarding submissions on behalf of patients.
- Strict Montreal classification enforcement.
- Patient notifications when review status changes.
- A workflow engine or multi-step draft state.

## Acceptance Criteria Mapping

- Onboarding captures baseline patient profile, IBD status, medication context, and optional recent labs.
  - Covered by structured `OnboardingSubmission` columns and DTO validation.
- Data is saved with timestamps.
  - Covered by `created_at`, `submitted_at`, `reviewed_at`, and version history.
- Data can be reviewed by assigned experts.
  - Covered by clinical review endpoints/pages using `AccessControlService` and existing direct/cohort assignment rules.
