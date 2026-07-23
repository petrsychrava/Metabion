# Assignment Management REST API Design

Date: 2026-07-23

## Goal

Add a session-authenticated REST API that mirrors the full operation surface of the existing assignment-management web workspace: cohort CRUD and archival, patient cohort membership management, cohort staff assignment management, direct patient expert assignment management, scoped list reads, and candidate lookups.

The API serves same-origin, session-authenticated staff clients (an SPA or staff tooling served from the same origin) using the existing session cookie and CSRF policy. The session cookie is `SameSite=Strict`; no CORS or cross-origin support is added. Bearer/OAuth token access is not added in this slice.

The API adds zero new authorization logic. All writes and scoped reads delegate to `AssignmentManagementService`, exactly as the web controller does.

## Existing Context

The web slice (`docs/superpowers/specs/2026-07-18-cohort-patient-expert-assignment-management-design.md`) is implemented and explicitly deferred REST APIs:

- `AssignmentManagementService` (`src/main/java/com/metabion/service/AssignmentManagementService.java`) is the single application boundary for assignment writes. Every method takes `Authentication`, enforces actor role and scope through `AccessControlService`, and throws `ResponseStatusException` with REST-friendly statuses (400, 401, 403, 404, 409).
- `GlobalExceptionHandler` (`@RestControllerAdvice` over `com.metabion.controller.api`) maps `ResponseStatusException` to JSON error bodies (`forbidden`, `not_found`, `unauthorized`, `conflict`, `request_failed`), Bean Validation failures to `400 validation_failed` with a field map, and optimistic-locking failures to `409 conflict`. It does not yet handle malformed request bodies, missing parameters, or type mismatches.
- Read models in `src/main/java/com/metabion/dto/assignment/AssignmentManagementView.java` include lean records (`CohortItem`, `StaffOption`, `ExpertAccess`, `PatientRow`) plus web page bundles (`CohortPage`, `DirectPage`) that embed per-row candidate lists.
- Request records exist in `src/main/java/com/metabion/dto/assignment/AssignmentManagementForms.java` (`CohortForm`, `SelectionForm`).
- `SecurityConfig` restricts `/app/assignment-management/**` to `COORDINATOR` and `ADMIN` and authenticates all other `/api/**` paths generically. Security-layer rejections currently bypass the JSON error contract: the entry point is a bare `HttpStatusEntryPoint` (empty 401) and there is no API-specific `AccessDeniedHandler` (role denial and CSRF rejection produce a bare 403). CSRF is enabled for session-authenticated API mutations; the token cookie is generated lazily and no endpoint currently materializes it for API clients.
- No existing API path uses `/api/cohorts`, `/api/patients`, or `/api/staff`.

## Chosen Approach

A thin `@RestController` in `com.metabion.controller.api` with resource-oriented paths, delegating every operation to `AssignmentManagementService`.

Resource-oriented paths were chosen over mirroring the web routes (`POST .../edit`, `POST .../end`) because idiomatic REST (`PUT`, `DELETE`, sub-resources) is cleaner for API clients. The service layer already speaks HTTP statuses, so the controller only maps requests, calls the service, and shapes responses.

No schema migration is required; the persistence model from the web slice is unchanged.

### Alternatives Considered

#### Mirror Web Routes Under `/api/assignment-management/**`

Copy the web paths 1:1 with `POST`-only mutations. Simplest mental mapping to the web controller, but verb-in-path URLs are unidiomatic and make the API harder to evolve.

#### Return Web Page Bundles

Expose `CohortPage`/`DirectPage` directly. Fastest, but couples API clients to view-oriented bundles with embedded per-row candidate lists (an O(patients × staff) payload), and leaks web concerns into the API contract.

## Endpoints

All endpoints require an authenticated session. Non-GET requests require a CSRF token under the existing cookie-based policy. Path-level security restricts every path below to `COORDINATOR` and `ADMIN` — except `GET /api/csrf`, a generic bootstrap endpoint that any authenticated session may call; it exposes no assignment data and is explicitly outside the assignment role matrix. Service-level scope checks remain authoritative.

### CSRF Bootstrap

| Method | Path | Description | Success |
|---|---|---|---|
| GET | `/api/csrf` | Materializes the lazily generated `XSRF-TOKEN` cookie and returns the token value and header name so API clients can complete their first mutation | 200 `CsrfTokenResponse` |

Login (`POST /api/auth/login`) is CSRF-exempt, so the token is only needed after authentication; requiring an authenticated session for this endpoint is correct. The full client flow — login, `GET /api/csrf`, then mutations with the `X-XSRF-TOKEN` header — is covered by an integration test.

### Cohorts

| Method | Path | Description | Success |
|---|---|---|---|
| GET | `/api/cohorts` | Cohorts visible to the actor (coordinator: assigned cohorts; admin: all, including archived) | 200 `List<CohortItem>` |
| POST | `/api/cohorts` | Create a cohort; a coordinator creator is auto-assigned | 201 `CohortItem` |
| GET | `/api/cohorts/{cohortId}` | Cohort detail: metadata, patients, care team | 200 `CohortDetailResponse` |
| PUT | `/api/cohorts/{cohortId}` | Replace cohort name/description | 200 `CohortItem` |
| POST | `/api/cohorts/{cohortId}/archive` | Archive a cohort (admin only); ends memberships and cohort staff assignments | 204 |
| POST | `/api/cohorts/{cohortId}/memberships` | Add a patient membership | 201 `MembershipResponse` |
| DELETE | `/api/cohorts/{cohortId}/memberships/{membershipId}` | End a membership | 204 |
| POST | `/api/cohorts/{cohortId}/staff-assignments` | Assign staff to the cohort | 201 `AssignmentResponse` |
| DELETE | `/api/cohorts/{cohortId}/staff-assignments/{assignmentId}` | End a cohort staff assignment | 204 |
| GET | `/api/cohorts/{cohortId}/patient-candidates` | Active patients not yet enrolled in this cohort | 200 `List<PatientOptionResponse>` |
| GET | `/api/cohorts/{cohortId}/staff-candidates` | Staff permitted for the actor and not yet assigned to this cohort (coordinator: physicians and nutrition specialists; admin: also coordinators) | 200 `List<StaffOption>` |

`PUT` is used for cohort editing because the reused `CohortForm` requires `name` and the service replaces both fields — the operation is full replacement, not a partial update.

### Patients and Direct Expert Assignments

| Method | Path | Description | Success |
|---|---|---|---|
| GET | `/api/patients?page=&size=` | Scoped patient list with cohort memberships and direct/inherited experts (coordinator: patients in own active cohorts; admin: all active patients) | 200 `PatientsPageResponse` |
| POST | `/api/patients/{patientProfileId}/expert-assignments` | Create a direct expert assignment | 201 `AssignmentResponse` |
| DELETE | `/api/patients/{patientProfileId}/expert-assignments/{assignmentId}` | End a direct expert assignment | 204 |

Notes:

- Candidate endpoints are cohort-scoped, mirroring the web workspace: the service validates the cohort is active and manageable by the actor and excludes already-enrolled patients / already-assigned staff. For direct expert assignments (not cohort-bound), clients use the page-level `staffCandidates` in `GET /api/patients`, which contains only physicians and nutrition specialists. Cohort staff candidates are never reused for direct assignments: for administrators that list includes coordinators, which `assignDirectExpert` rejects.
- Ending operations use `DELETE` even though rows are retained with `ended_at`; the delete targets the active relationship, which is the resource the client sees.
- 201 responses carry the created identifier in the body. `Location` headers are not added; they are not used anywhere in the existing API.

### Pagination

`GET /api/patients` accepts `page` (default 0) and `size` (default 50, maximum 200). Clamping, matching the existing service behavior:

- `page < 0` clamps to 0.
- `page` beyond the last page clamps to the last page.
- `size < 1` clamps to 50; `size > 200` clamps to 200.
- Empty results return 200 with an empty list, `pageIndex` 0, and `totalPages` 0.

## DTOs and Data Flow

### Reused Records

Responses reuse the lean records from `AssignmentManagementView`: `CohortItem`, `StaffOption`, `ExpertAccess`, and the existing `PatientOptionResponse`. The web-only `DirectPatient` record (which embeds `staffCandidates` per row) is not reused; embedding the expert candidate directory in every patient row would make the page multiplicatively large.

`CohortForm` is reused as the request body for cohort create and replace.

### New Records (`com.metabion.dto.assignment`)

- `CohortDetailResponse(CohortItem cohort, List<PatientRow> patients, List<ExpertAccess> careTeam)`
- `PatientAssignmentRow(Long patientProfileId, String email, List<CohortItem> cohorts, List<ExpertAccess> direct, List<ExpertAccess> inherited)` — the REST patient row, without embedded candidates
- `PatientsPageResponse(List<PatientAssignmentRow> patients, List<StaffOption> staffCandidates, int pageIndex, int size, int totalPages, long totalPatients)` — direct-expert candidates returned once at page level, not per row
- `AddPatientRequest(@NotNull Long patientProfileId)`
- `AssignStaffRequest(@NotNull Long staffProfileId)`
- `MembershipResponse(Long membershipId)` — returned when a membership is created
- `AssignmentResponse(Long assignmentId)` — returned when a cohort staff or direct expert assignment is created

`CsrfTokenResponse(String token, String headerName)` is a cross-cutting API DTO in
`com.metabion.dto`, not an assignment-management DTO.

Explicit request field names (`patientProfileId`, `staffProfileId`) replace the opaque `SelectionForm.targetId` at the API boundary.

### Archived Cohort Detail Semantics

`GET /api/cohorts/{cohortId}` on an archived cohort returns a historical view, matching the web workspace: `patients` contains historical membership rows (with `endedAt`/`endedBy` attribution and empty direct/inherited expert lists) and `careTeam` contains historical cohort staff assignments. Current direct expert assignments for those patients are independent of the cohort and are not shown here; they remain visible through `GET /api/patients`. This distinction prevents a historical relationship from being presented as a current one.

### Service Changes (`AssignmentManagementService`)

- Change the return types of `addPatientToCohort`, `assignCohortStaff`, and `assignDirectExpert` from `void` to the created row's identifier (surfaced as `MembershipResponse`/`AssignmentResponse`). The web controller ignores return values, so this is a safe signature change.
- `updateCohort` gains a return of the updated `CohortItem` so `PUT` can return 200 with the fresh state.
- Add lean read methods so the API never touches the web page bundles:
  - `CohortDetailResponse cohortDetail(Authentication, Long cohortId)`
  - `PatientsPageResponse scopedPatients(Authentication, int page, int size)` — builds rows without per-row candidate lists and attaches the direct-expert candidate list once at page level
  - `List<PatientOptionResponse> patientCandidates(Authentication, Long cohortId)` — validates the cohort is active and manageable, excludes enrolled patients
  - `List<StaffOption> staffCandidates(Authentication, Long cohortId)` — validates the cohort is active and manageable, filters by actor role, excludes assigned staff
- The existing `listCohorts(Authentication)` and `createCohort(Authentication, CohortForm)` are reused as-is.

All authorization, scoping, duplicate detection, archival rules, and enumeration-safe 404 behavior stay inside the service. The controller performs no repository access and no authorization decisions.

## Error Handling

The JSON error contract applies at two layers.

### Controller/Service Layer (existing, plus small extensions)

`GlobalExceptionHandler` already covers service-thrown statuses; it gains handlers for request-decoding failures so all controller-layer errors share the JSON shape:

- `400 validation_failed` with a field map for Bean Validation failures on request records
- `400 request_failed` for malformed JSON bodies (`HttpMessageNotReadableException`), missing required parameters (`MissingServletRequestParameterException`), and path/query type mismatches (`MethodArgumentTypeMismatchException`)
- `403 forbidden` for in-scope role violations surfaced by the service (e.g., coordinator attempting archival)
- `404 not_found` for missing or out-of-scope cohorts, patients, memberships, and assignments — preserved to avoid leaking scoped data
- `409 conflict` for duplicate active relationships and mutations against archived cohorts, including concurrent duplicate inserts translated from database uniqueness violations

### Security Layer (new)

Security-generated rejections currently bypass the JSON contract (bare 401 entry point, no API access-denied handler). `SecurityConfig` gains JSON handlers scoped to `/api/**` only, leaving the `/app` login redirect and MCP bearer challenge untouched:

- unauthenticated API requests receive `401 {"error":"unauthorized"}`
- authenticated-but-denied API requests (wrong role, and CSRF token rejection, which surfaces through the access-denied handler) receive `403 {"error":"forbidden"}`

Note the ordering consequence: an anonymous mutation without a CSRF token is rejected by the CSRF filter before authentication and returns 403, not 401. Clients must treat both as authentication/CSRF bootstrap failures.

## Security

- Add a `SecurityConfig` rule restricting `/api/cohorts/**` and `/api/patients/**` to `hasAnyRole("COORDINATOR", "ADMIN")`, ordered before the generic `/api/**` authenticated rule. This is defense in depth; `AssignmentManagementService` authorization remains authoritative.
- CSRF remains enabled for all mutations, consistent with existing session-authenticated API controllers, with `GET /api/csrf` as the bootstrap for API clients.
- Service-level rules are unchanged: coordinator cohort enrollment never grants clinical-data access; candidate searches expose only minimum identity and role data; direct and inherited access stay labeled separately.
- Concurrent-write semantics are limited to what the persistence layer guarantees: partial unique indexes turn duplicate active relationships into `409 conflict`. There is no entity versioning (`@Version`) on cohorts or assignments, so no stale-update detection, ETag, or `If-Match` support is promised; the later writer wins for non-conflicting edits. Adding optimistic concurrency would require persistence changes and is out of scope.
- No credentials, tokens, session identifiers, or clinical details are logged. CSRF token responses are excluded from logging like other credentials.

## Testing Strategy

Follow the existing API test conventions in `src/test/java/com/metabion/`.

### Controller and Security Tests

- Role matrix: coordinator and admin reach the endpoints; physician, nutrition specialist, patient, and anonymous callers are rejected.
- Security-layer JSON contract: anonymous API request → `401 {"error":"unauthorized"}`; authenticated wrong-role request → `403 {"error":"forbidden"}`; CSRF rejection → 403 JSON.
- CSRF bootstrap flow: login → `GET /api/csrf` → successful mutation with the token header; mutation without the token is rejected.
- CSRF required on every non-GET endpoint.
- Bean Validation failures return `400 validation_failed` with the field map; malformed JSON, missing parameters, and type mismatches return `400 request_failed`.
- Duplicate and archived-cohort operations return `409 conflict`.
- Out-of-scope resources return `404 not_found`, not 403.
- Success shapes: 201 bodies contain the created identifiers; `PUT` returns the updated cohort; ends return 204.
- Pagination clamping exactly as specified: negative page, oversized page, zero/negative/oversized size, and empty results.

### Service Tests (New Read Methods and Return Values)

- `cohortDetail` scope behavior for coordinator vs admin, including the archived historical view (historical rows, no current expert lists).
- `scopedPatients` deduplication for coordinators and full listing for admins, with page-level (not per-row) candidates.
- `patientCandidates`/`staffCandidates` reject unmanageable or archived cohorts and exclude enrolled/assigned entries; coordinator never sees coordinator candidates.
- Write methods return the created membership/assignment identifiers.

### Integration Flows

Mirror the web slice's representative flows over HTTP: coordinator cohort lifecycle with enrollment and expert assignment, direct assignment lifecycle, and admin archival — verifying the same authorization outcomes as the web UI.

Final verification:

```bash
./gradlew test
```

## Scope Boundaries

In scope:

- REST endpoints mirroring the full web assignment-management operation surface
- Lean REST DTOs and cohort-scoped candidate endpoints
- `GET /api/csrf` bootstrap endpoint
- JSON error handlers for security-layer rejections on `/api/**`
- `GlobalExceptionHandler` handlers for malformed/binding errors
- `AssignmentManagementService` read methods and write return values
- `SecurityConfig` path rule for the new API paths
- Controller, security, service, and integration tests

Out of scope:

- Bearer/OAuth token access to these endpoints
- CORS or any cross-origin support
- Optimistic concurrency control (entity versioning, ETag, `If-Match`)
- `Location` headers on 201 responses
- Changes to authorization rules, the persistence model, or any Flyway migration
- Bulk assignment operations
- Notifications when assignments change
- Pagination of cohort, membership, or staff lists beyond the scoped patient list

## Acceptance Criteria

1. Every web workspace operation has a REST equivalent reachable by coordinators and administrators with a session and CSRF token.
2. The API reuses `AssignmentManagementService` for all writes and scoped reads; no controller contains authorization or repository logic.
3. Coordinator scope rules hold identically over HTTP: own cohorts only, expert targets only, in-scope patients only, no archival.
4. All documented client errors — controller-layer and security-layer alike — use the JSON error contract with correct statuses (400/401/403/404/409), and out-of-scope resources remain indistinguishable from missing ones.
5. API clients can complete the full CSRF flow: login, `GET /api/csrf`, then mutations.
6. Created memberships and assignments return their identifiers; ending operations return 204 while retaining history.
7. The scoped patient list is paginated with the specified clamping behavior and carries candidates once at page level.
8. Archived cohort detail returns historical rows without presenting ended relationships as current.
9. Physician, nutrition-specialist, patient, and anonymous callers are rejected from every assignment-management endpoint; `GET /api/csrf` remains reachable by any authenticated session as the generic bootstrap.
10. The full Gradle test suite passes.
