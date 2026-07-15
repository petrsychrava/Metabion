# Clinical Daily Check-In Review Design

## Goal

Replace the expert-facing legacy diet-log review with a clinical daily-check-in review that reflects the unified patient workflow. Clinical users must be able to review diet, measurements, symptoms, and flare state for each accessible patient day.

## Scope

- Replace the `Diet log review` menu item with `Daily check-in review`.
- Replace `/app/clinical/diet-logs` with `/app/clinical/daily-check-ins`.
- Provide a filterable daily-check-in list and a read-only daily-check-in detail page.
- Remove the legacy clinical diet-log controller mappings, templates, labels, and tests.
- Do not add redirects or other compatibility behavior because the application is not yet in use.

Patient entry and history pages, REST diet-log endpoints, persistence models, and the existing clinical trends page remain in scope only where their links must point to the new clinical detail route.

## Architecture

Add a focused clinical daily-check-in read service and response DTOs. The service will assemble the existing daily diet log and symptom check-in for the same patient profile and date. It will reuse the current clinical role checks, assigned-patient access rules, response assemblers, and measurement/photo access behavior rather than duplicating authorization or mapping in the web controller.

This is an evolution of the existing clinical diet-log review, not a parallel implementation or rewrite. Retain and adapt its patient/date filtering, all-accessible-patients behavior, diet-log assembly, detail rendering, localization, and test fixtures. Add only the symptom-side join, unified DTO fields, daily-check-in terminology, and new route identity; delete code only when the unified replacement makes the legacy entry point or name obsolete.

The daily check-in is identified in web routes by patient profile id and date because it spans two persisted records with separate database ids. The detail route will be:

`/app/clinical/daily-check-ins/{patientProfileId}/{date}`

The list route will be:

`/app/clinical/daily-check-ins?patientProfileId=&from=&to=`

The list may include all patients accessible to the current expert when no patient is selected, matching the existing clinical review behavior. Results are ordered by date descending, then patient email, then patient profile id.

## List Page

The list keeps the current patient, from-date, and to-date filters. Each row represents one unified daily check-in and shows:

- date and patient
- diet adherence and appetite
- meal, deviation, and measurement counts
- symptom score and flare state
- a link to the unified detail page

New patient submissions create diet and symptom records atomically. The read service will nevertheless tolerate one missing side so historical or inconsistent data remains inspectable: missing values render as `Not provided`, and a day is included when either record exists.

## Detail Page

The detail page is read-only and contains:

- patient identity and date
- adherence, appetite, and diet notes
- meals with associated deviations and attached photos
- glucose and ketone measurements
- symptom score, flare state, symptom notes, and every recorded answer with its questionnaire label and display value

The existing photo-content URLs remain protected by their current authorization checks.

## Authorization And Errors

Nutrition specialists, physicians, coordinators, and administrators retain clinical read eligibility. Non-admin clinical users may read only actively assigned or cohort-accessible patients, using `AccessControlService`. Administrators retain broad clinical access.

Invalid date ranges produce the existing bad-request behavior. Inaccessible patients produce `403`, and a requested patient/date with neither a diet log nor a symptom check-in produces `404`. MVC exceptions continue through `WebExceptionHandler`.

## Integration Changes

- Update `AppMenuCatalog` and both message bundles to use daily-check-in terminology and the new route.
- Replace the clinical methods in `WebDietLogController` with a dedicated clinical daily-check-in controller; keep patient diet-log history behavior intact.
- Replace the two legacy clinical Thymeleaf templates with daily-check-in list/detail templates.
- Update clinical-trend row links to use patient profile id and date instead of a diet-log id. A day with symptom data but no diet log must still link to the unified detail.
- Keep existing API contracts and database schema unchanged.

## Testing

Use test-driven development with focused regression coverage:

- service tests for joining diet and symptom data, including diet-only, symptom-only, all-accessible-patients ordering, forbidden access, and missing detail
- MVC tests for the new list/detail routes, rendered diet and symptom content, filters, localization, and web error handling
- menu tests proving experts receive only the new daily-check-in review item
- clinical trends tests proving every inspectable day links to the new detail route
- security tests where route policy coverage needs updating

Run focused tests during implementation, then run `./gradlew test` before completion.
