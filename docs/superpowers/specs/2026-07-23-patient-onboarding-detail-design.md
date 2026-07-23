# Patient Onboarding Submission Detail — Design

Date: 2026-07-23

## Problem

After submitting the onboarding baseline, a patient can only see version, review
status, and submitted date (`onboarding.html` "Latest baseline" panel and
`onboarding-history.html`). The actual data they filled in (diagnosis,
medications, labs) is not viewable anywhere in the patient UI. Submissions are
immutable versioned rows, and that model is preserved.

## Goals

- Patient can open a read-only detail view of any of their own onboarding
  submissions from the history page and from the "Latest baseline" panel.
- The REST API gains a matching patient endpoint, keeping web/API parity.
- Reviewer internals (`reviewNotes`, `reviewedByEmail`, `reviewedAt`) are never
  exposed to patients. This also fixes an existing leak:
  `OnboardingService.getLatestForCurrentPatient` (`OnboardingService.java:68`)
  currently returns the full `OnboardingSubmissionResponse` — including those
  fields — via `GET /api/onboarding/submissions/latest` and the MCP facade
  (`PatientAppFacade.java:142`).

## Non-goals

- Editing or prefilling submissions (immutable-version model stays).
- Exposing reviewer notes or reviewer identity to patients.
- Changes to entities, repositories, Flyway migrations, or `SecurityConfig`.

## Service & Access Control

New method in `OnboardingService`:

```java
public OnboardingSubmissionResponse getOwnSubmissionById(Authentication authentication, long submissionId)
```

- Resolves the caller via the existing `currentPatientProfile(authentication)`
  (already enforces `RoleName.PATIENT`).
- Loads the submission by id using the existing repository `findById`.
- If the submission does not exist, or its `patientProfileId` does not match the
  caller's profile, throws `ResponseStatusException(HttpStatus.NOT_FOUND,
  "Onboarding submission not found")` — identical to the existing not-found
  behavior, so foreign submission ids are indistinguishable from missing ones
  (no enumeration).
- Returns the patient-view DTO (below).

Ownership enforcement stays in the service layer, per project convention
(`SecurityConfig` only requires authentication for `/app/**` and `/api/**`).

## DTO: Patient View

Add a patient-safe projection method `toPatientView()` to
`OnboardingSubmissionResponse`:

```java
public OnboardingSubmissionResponse toPatientView()
```

Returns the same record with `reviewNotes`, `reviewedByEmail`, and `reviewedAt`
set to `null`; `reviewStatus` is kept (already shown in the patient UI). All
submitted field values and profile fields pass through unchanged.

Apply the patient view to **all** patient-facing paths:

- new `getOwnSubmissionById`
- existing `getLatestForCurrentPatient`
- existing patient `submit` response (`OnboardingService.java:60-66`)

Clinical paths (`getReviewable`, `review`, `listReviewable`) keep the full DTO.

The MCP path (`PatientAppFacade` → `OnboardingService`) inherits the patient
view automatically since it delegates to the same service methods.

## Endpoints

- Web: `GET /app/onboarding/{id}` in `WebOnboardingController` — calls
  `getOwnSubmissionById`, renders `onboarding-detail.html`.
- API: `GET /api/onboarding/submissions/{id}` in `OnboardingController` —
  returns the patient-view DTO. No route conflict: clinical routes live under
  `/api/clinical/onboarding/...`.

Navigation:

- Rows in `onboarding-history.html` link to `/app/onboarding/{id}`.
- The "Latest baseline" panel in `onboarding.html` links to the latest
  submission's detail page.

## Template

New `src/main/resources/templates/onboarding-detail.html`:

- Read-only rendering of the patient's own submission, modeled on the field
  layout of `clinical-onboarding-detail.html` but without the review form,
  review notes, reviewer identity, or patient email.
- Header: version, onboarding context, submitted date, review status.
- Sections mirror the form: condition, medications, labs.
- Localized via `messages.properties` and `messages_cs.properties`; reuse
  existing label keys from the clinical detail template where present, add new
  keys only where missing (kept aligned across both bundles).
- "Back to history" link to `/app/onboarding/history`.

## Error Handling

- Wrong id or another patient's id → 404 from the service; web layer renders the
  standard error view through `WebExceptionHandler`, API through
  `GlobalExceptionHandler`.
- No new exception types.

## Testing

- Service tests (`OnboardingService`):
  - owner retrieves own submission with review internals nulled and
    `reviewStatus` intact;
  - another patient's id → 404; nonexistent id → 404; non-patient role rejected;
  - `getLatestForCurrentPatient` and patient `submit` response no longer contain
    review internals; clinical `getReviewable` still returns the full DTO.
- MVC test for `GET /app/onboarding/{id}`: renders submitted field values, hides
  review notes; 404 for foreign id.
- API test for `GET /api/onboarding/submissions/{id}`: 200 for own, 404 for
  foreign/missing, 401/redirect for unauthenticated per existing security tests.
- MCP: one assertion that the facade's onboarding responses carry nulled review
  internals.

Verification: `./gradlew test`, focused tests during iteration
(`./gradlew test --tests 'com.metabion.service.OnboardingServiceTest'` etc.).
