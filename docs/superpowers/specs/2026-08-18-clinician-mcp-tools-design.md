# Clinician MCP Tools Design

Date: 2026-08-18
Status: Approved for implementation planning

## Goal

Expose a clinician-facing MCP surface for physicians and nutrition specialists
through the existing Metabion MCP endpoint. The surface must support clinical
oversight of currently assigned patients, preserve the existing patient MCP
contract, and reuse the current clinical REST services and authorization rules.

## Decisions

| Decision | Result |
|---|---|
| First-release scope | Clinical oversight only |
| Allowed roles | `PHYSICIAN` and `NUTRITION_SPECIALIST` |
| Administrators | Excluded from clinician MCP |
| Coordinators | Excluded from clinician MCP and clinical data |
| Patient set | Active direct or cohort assignments only |
| Writes | Clinical laboratory create/update/removal and onboarding review only |
| Authentication | Existing OAuth authorization-code + PKCE flow |
| MCP transport | Existing `/api/mcp` Streamable HTTP endpoint |
| Token architecture | Separate patient and clinical access-token tables with shared OAuth protocol tables |
| Tool registration | Separate patient and clinician tool beans with subject-specific guards |
| Assignments | Checked on every patient-data call through existing clinical services |
| Admin/assignment operations | Out of scope |

## Goals

- Let a physician or nutrition specialist authenticate an MCP client through
  the existing OAuth browser consent flow.
- Expose a bounded directory and overview of the clinician's assigned patients.
- Expose clinical daily check-ins, symptoms, trends, diet photos, laboratory
  results, red flags, and onboarding review data.
- Permit only the existing bounded clinical writes: laboratory result-set
  mutations and onboarding review decisions.
- Keep patient MCP tool names, patient scopes, token behavior, and REST
  contracts compatible.
- Apply role and assignment checks server-side for every tool invocation.
- Preserve refresh rotation, resource binding, revocation, audit, and MCP
  asynchronous security-context behavior.

## Non-Goals

- No patient profile editing, diet-log mutation, symptom-check-in mutation, or
  patient onboarding submission through clinician MCP.
- No cohort, membership, direct-assignment, staff-invitation, user, or staff
  administration tools. Self-service MCP token list/revoke is included only as
  credential lifecycle support.
- No education authoring or lifecycle-management tools.
- No administrator or coordinator clinician tokens.
- No unrestricted patient search or bulk export.
- No new clinical business rules or parallel repository queries in MCP tools.
- No new clinician SPA or staff UI in this delivery.

## Existing Context

The patient MCP is implemented by `PatientMcpTools` and currently assumes
`PatientAccessTokenAuthentication`, `PatientAccessTokenScope`, and a
patient-only OAuth/token issuance path. The bearer filter authenticates tokens
only for `/api/mcp`, and patient tools delegate to `PatientAppFacade`.

The clinical REST surface already provides the required business operations:

- `ClinicalPatientDirectoryService` resolves accessible patient identities.
- `ClinicalOverviewService` returns a bounded monitored-patient overview.
- `ClinicalDailyCheckInService` joins diet and symptom records by patient/date.
- `SymptomTrackingService` and `DailyTrendService` expose clinical symptom and
  trend reads.
- `DietLogPhotoService` protects attached diet-photo content with clinical
  assignment checks.
- `LabResultService`, `LabTrendService`, and `LabCatalogService` expose
  clinical laboratory reads and mutations.
- `RedFlagEventQueryService` exposes clinical current/history projections.
- `OnboardingService` exposes reviewable submissions and review mutations.
- `AccessControlService` enforces direct and cohort-derived patient access.

The clinician MCP must call these services with the authenticated
`Authentication`; it must not duplicate their role, assignment, validation, or
transaction logic.

## Architecture

```text
MCP client
  -> OAuth discovery, registration, authorization, PKCE, and refresh
  -> /api/mcp Streamable HTTP endpoint
  -> common MCP bearer authentication
  -> PatientMcpTools or ClinicianMcpTools
  -> subject and scope guard
  -> PatientAppFacade or ClinicalMcpFacade
  -> existing application services
  -> AccessControlService and domain repositories
```

### Subject-specific access-token tables

Keep patient and clinical bearer credentials in separate physical tables and
entities:

- `patient_access_tokens` and `patient_access_token_scopes` remain the patient
  token store;
- `clinical_access_tokens` and `clinical_access_token_scopes` store physician
  and nutrition-specialist tokens.

Both access-token tables contain the common lifecycle fields: owning `User`,
token hash, client type/label, resource, expiry, last-used timestamp,
revocation state, and refresh-family ID. The clinical table also has a foreign
key to `oauth_refresh_token_families` when the token is OAuth refresh-capable.

Patient-facing issuance remains in `PatientAccessTokenService`. Add a separate
`ClinicalAccessTokenService` for OAuth clinician issuance, while sharing token
generation, hashing, common validation helpers, and response mapping. Manual
clinician token issuance is not added.

Use distinct plaintext token prefixes, such as `pat_` and `clin_`, so the
bearer filter can route directly to the correct repository and the two tables
share one unambiguous token namespace. Only hashes are persisted; the prefix
is part of the hashed value and is not itself a trust decision.

Patient tokens issued before this change are unprefixed because the current
issuer generates a raw random value. They remain valid through a legacy
patient-only lookup path until expiry or revocation; they are never routed to
the clinical token table. New patient issuance uses `pat_`, and clinician
issuance uses `clin_`. Existing hashes cannot be rewritten to add a prefix,
so no token-hash migration is attempted. Existing OAuth refresh-token values
remain valid, and a subsequent patient refresh issues a new `pat_` access
token.

Scope-family separation is enforced by the table and Java scope type:
patient scope grants can only be persisted in the patient scope table, and
clinician scope grants can only be persisted in the clinical scope table. A
single access token cannot mix patient and clinician scopes.

The OAuth protocol artifacts remain shared. Authorization codes and refresh
tokens carry an explicit `subject_type` (`PATIENT` or `CLINICIAN`) so the
shared OAuth flow knows which access-token service to call. This keeps OAuth
validation and refresh rotation common without mixing bearer-token rows.

### OAuth authorization and refresh

The existing OAuth authorization service is generalized as follows:

1. Authorization requests parse a common scope set and classify it as patient
   or clinician based on the scope family.
2. The consent request stores the subject type with the authorization code.
3. Consent requires an enabled, unlocked user with the required role:
   `PATIENT` for patient scopes, or `PHYSICIAN`/`NUTRITION_SPECIALIST` for
   clinician scopes.
4. Token exchange revalidates the code, client, redirect URI, resource, PKCE,
   scope family, subject type, and current user eligibility before consuming
   the code and dispatching to the patient or clinical access-token service.
5. Refresh rotation revalidates the same subject eligibility and scope family
   before issuing a replacement access token in the matching table.
6. Existing family-wide reuse revocation and resource binding remain intact;
   family revocation dispatches to the patient or clinical access-token
   repository based on the refresh row's subject type.

Dynamic and configured OAuth clients may advertise clinician scopes in the same
way they advertise patient scopes. An existing client registration that does
not include clinician scopes must be registered or authorized again with the
new scopes. Existing patient clients do not gain clinician access implicitly.

The consent view must distinguish clinical scopes from patient scopes and must
not describe clinician access as patient-owned access.

### Bearer authentication

Replace the patient-only bearer assumption with a common MCP bearer
authentication/filter that:

- runs only for `/api/mcp` requests;
- routes by the `pat_`/`clin_` prefix to the patient or clinical token
  repository;
- treats an unprefixed bearer value as a legacy patient token and checks only
  the patient repository;
- rejects an unknown explicit prefix without probing the other token domain;
- validates the token hash, resource, expiry, revocation, and user status;
- verifies that the current user still has the role required by the selected
  token domain;
- exposes current role authorities and `SCOPE_...` authorities;
- saves the authenticated context to the existing request-attribute MCP
  security-context repository;
- retains the current `401` challenge, `403` insufficient-scope behavior, and
  metadata-only authentication audit.

`PatientMcpTools` accepts only a patient authentication. `ClinicianMcpTools`
accepts only a clinical authentication and then requires the appropriate
clinician scope. Using a token from the wrong table/domain never reaches a
business service.

### Component boundaries

Add `ClinicianMcpTools` as the annotation boundary and `ClinicalMcpFacade` as
the MCP-facing application boundary. Add a common token generator/auth
adapter, but keep patient and clinical token issuers and repositories separate.
The tool class is responsible only for:

- MCP names, descriptions, and parameters;
- token-domain and scope checks;
- safe base64 photo adaptation;
- MCP-specific write-result wrappers;
- success/failure audit calls.

`ClinicalMcpFacade` delegates to existing clinical services. It does not query
repositories or implement new assignment logic.

Generalize `PatientAccessAuditService` into a role-neutral MCP audit boundary,
or provide a compatible common implementation used by both patient and
clinician tools. Existing patient audit fields and operation names remain
available.

## Clinician Tool Surface

Both allowed roles receive the same tool set. Tool availability is controlled
by scopes, not by a different physician/nutrition-specialist catalog.

| Tool | Required scope | Delegated operation |
|---|---|---|
| `metabion_clinician_me` | subject check only | Current MCP user, roles, client label, token ID, and scopes |
| `metabion_list_assigned_patients` | `clinician:patients:read` | `ClinicalPatientDirectoryService.listAccessible` |
| `metabion_get_clinical_overview` | `clinician:overview:read` | `ClinicalOverviewService.overview` |
| `metabion_get_clinical_patient` | `clinician:patients:read` | `ClinicalPatientDirectoryService.getAccessible` |
| `metabion_list_clinical_daily_check_ins` | `clinician:check-ins:read` | `ClinicalDailyCheckInService.list` |
| `metabion_get_clinical_daily_check_in` | `clinician:check-ins:read` | `ClinicalDailyCheckInService.get` |
| `metabion_list_clinical_symptom_check_ins` | `clinician:symptoms:read` | `SymptomTrackingService.listClinicalCheckIns` |
| `metabion_get_clinical_daily_trends` | `clinician:trends:read` | `DailyTrendService.clinicalTrend` |
| `metabion_get_clinical_diet_photo_content` | `clinician:photos:read` | `DietLogPhotoService.readContent` |
| `metabion_list_clinical_lab_tests` | `clinician:labs:read` | `LabCatalogService.listActive` |
| `metabion_list_clinical_lab_result_sets` | `clinician:labs:read` | `LabResultService.listForClinicalPatient` |
| `metabion_get_clinical_lab_result_set` | `clinician:labs:read` | `LabResultService.getForClinicalPatient` |
| `metabion_get_clinical_lab_trend` | `clinician:labs:read` | `LabTrendService.clinicalTrend` |
| `metabion_save_clinical_lab_result_set` | `clinician:labs:write` | `LabResultService` clinical create/update flow |
| `metabion_remove_clinical_lab_result_set` | `clinician:labs:write` | `LabResultService.removeForClinicalPatient` |
| `metabion_get_clinical_current_red_flags` | `clinician:red-flags:read` | `RedFlagEventQueryService.currentForClinicalPatient` |
| `metabion_list_clinical_red_flag_history` | `clinician:red-flags:read` | `RedFlagEventQueryService.historyForClinicalPatient` |
| `metabion_list_clinical_onboarding_submissions` | `clinician:onboarding:read` | `OnboardingService.listReviewable` |
| `metabion_get_clinical_onboarding_submission` | `clinician:onboarding:read` | `OnboardingService.getReviewable` |
| `metabion_review_clinical_onboarding_submission` | `clinician:onboarding:write` | `OnboardingService.review` |

The tool inputs use explicit `patientProfileId` values for patient-specific
operations. Date ranges, cursors, page sizes, lab result IDs, optimistic-lock
versions, review statuses, and review notes reuse the existing DTO and
validation contracts.

The `metabion_get_clinical_daily_check_in` response remains the unified
patient/date view, including diet, measurements, symptoms, and safe photo
references. Photo bytes are a separate base64 response and require the photo
scope.

The clinician scope set is:

- `clinician:patients:read`
- `clinician:overview:read`
- `clinician:check-ins:read`
- `clinician:symptoms:read`
- `clinician:trends:read`
- `clinician:photos:read`
- `clinician:labs:read`
- `clinician:labs:write`
- `clinician:red-flags:read`
- `clinician:onboarding:read`
- `clinician:onboarding:write`

## Bounded Write Semantics

### Laboratory writes

The save tool accepts the existing `LabResultSetRequest`. The request's
optional result-set ID selects create versus update, and the existing version
field is used for optimistic locking. The remove tool accepts the existing
`LabResultRemovalRequest`.

Clinical MCP write responses use MCP-specific wrappers containing:

- the saved result or removal status;
- the exact red-flag evaluation outcome from the same transaction;
- clinical red-flag event details, including rule version and matched inputs;
- cleared rule keys where the write supersedes a previous evaluation.

The internal lab mutation flow returns its domain evaluation outcome for MCP
while preserving the existing clinical REST response shape. No second
evaluation or follow-up query is used to construct the MCP result.

### Onboarding review

The review tool accepts `OnboardingReviewRequest` and delegates to the existing
review service. It returns the updated submission response. The current
assignment and reviewer-role checks remain authoritative.

No other patient-owned clinical records are mutable through this surface.

## Authorization and Privacy

- Directory and overview operations are bounded to active direct/cohort
  assignments; no unrestricted search is added.
- Every patient-specific operation rechecks assignment access through the
  existing clinical service and `AccessControlService`.
- Ending an assignment immediately removes access on the next request.
- Coordinators remain operational users and do not gain clinical access from a
  cohort assignment.
- Administrators are intentionally excluded even though some clinical REST
  services support administrator access.
- Patient and clinician tokens cannot invoke each other's tool families.
- Tool output never includes plaintext access tokens, refresh tokens,
  authorization codes, session identifiers, password data, or internal storage
  keys.
- Clinical red-flag responses may include rule-version and matched-input
  evidence because the caller is an assigned clinical expert.
- Photo content is returned only for attached photos belonging to an assigned
  patient and only with the dedicated photo scope.

## Errors and Audit

Authentication and authorization behavior remains:

- invalid, expired, revoked, or wrong-resource token: `401` with the existing
  protected-resource challenge;
- missing scope: `403` with `insufficient_scope` and the required scope;
- wrong subject, role, or assignment: safe `403`/`404` according to the
  existing clinical service behavior;
- validation failure: existing `400` validation response;
- optimistic-lock conflict: existing `409` conflict response;
- unexpected failure: safe agent-facing error with server-side diagnostics only.

MCP tool descriptions must state that red flags are clinical data and that an
MCP host must not invent medical guidance. Tool errors must not expose stack
traces, SQL, credential state, or request payloads.

Audit records contain:

- actor user ID/email and current role;
- MCP token ID and client display label;
- tool/operation name;
- target patient profile ID when applicable;
- success or failure;
- coarse failure reason;
- timestamp and request path where available.

Audit records never contain token values, refresh tokens, authorization codes,
session IDs, photo bytes, or complete clinical request bodies.

## Persistence and Migration

Add `V22__clinical_mcp_token_storage.sql` for both PostgreSQL and Oracle. The
migration must:

1. Create `clinical_access_tokens` with the same lifecycle columns as
   `patient_access_tokens`, including resource binding and nullable
   `refresh_family_id`.
2. Create `clinical_access_token_scopes` with a foreign key to the clinical
   token table and constraints appropriate for clinician scope values.
3. Add a non-null `subject_type` to authorization-code and refresh-token
   records, defaulting existing rows to `PATIENT`.
4. Preserve existing patient token hashes, patient scope rows, refresh
   families, indexes, foreign keys, and revocation state.
5. Keep shared OAuth scope storage compatible with existing patient scope
   values while allowing the new clinician scope values.
6. Add clinical indexes for token hash, user ownership, active-token lookup,
   and refresh-family lookup.

Because the application is not yet used, create the clinical tables directly;
there is no production rename or data migration to justify collapsing the two
access-token stores. The clinical token table references the shared
`oauth_refresh_token_families` table in the same way as the patient token
table. The migration layout test and portable JPA mapping test must cover both
database variants.

## Token Management

Keep the current patient account access-token service for patient credentials
and add a clinical counterpart so an authenticated physician or nutrition
specialist can list and revoke their own clinical MCP tokens through the
existing session-authenticated account API. This is an API capability only; no
staff account page is added here. Revocation remains owner-bound and
CSRF-protected. OAuth refresh-family revocation dispatches to the matching
patient or clinical token repository and continues to revoke all related
access tokens.

## Testing Strategy

### Domain and service tests

- subject-type and scope-family invariants;
- patient and clinical token repository/table isolation;
- legacy unprefixed patient tokens remain usable and cannot enter the clinical
  token path;
- existing patient token issuance, authentication, refresh, and revocation;
- clinician token issuance only for physician/nutrition-specialist users;
- rejection of patients, coordinators, administrators, disabled users, locked
  users, and users without the required role;
- role removal invalidating clinician authentication/refresh;
- direct assignment and cohort assignment access;
- access removal after assignment ending;
- cross-patient access rejection;
- overview limited to monitored assigned patients;
- clinician token list/revoke ownership behavior.

### OAuth and security tests

- clinician consent view and authorization-code persistence;
- PKCE exchange and resource binding for clinician scopes;
- mixed patient/clinician scope rejection;
- client scope allow-list enforcement;
- clinician refresh rotation and refresh-token reuse family revocation;
- invalid, expired, revoked, wrong-subject, and wrong-resource bearer tokens;
- `pat_` and `clin_` bearer-token routing to the matching token repository;
- missing-scope challenge and audit behavior;
- MCP request-attribute security context across asynchronous/error dispatches;
- existing patient OAuth/MCP integration tests remaining green.

### MCP tool tests

Add focused tests for `ClinicianMcpTools` and `ClinicalMcpFacade` covering:

- identity, directory, and overview tools;
- daily check-in list/detail;
- symptom and trend reads;
- photo content ownership and scope checks;
- laboratory reads, trends, writes, removal, and version conflicts;
- clinical red-flag current/history projections;
- onboarding list/detail/review;
- success and failure audit calls;
- every clinician scope gate with representative missing-scope cases;
- patient token and clinician token cannot cross tool families.

### Integration and migration verification

Run focused tests for OAuth, bearer authentication, MCP security context,
clinical authorization, and MCP tools, then run:

```text
./gradlew test
```

Migration tests must verify PostgreSQL and Oracle schemas, existing patient
token rows, new clinician scope values, refresh-family foreign keys, and
Hibernate validation.

## Rollout

- Register `ClinicianMcpTools` under the existing MCP enablement guard.
- Add a clinician-specific enablement property if staged activation is needed;
  default it to disabled until OAuth scope metadata and migration deployment
  are complete.
- Existing patient tokens remain patient-subject tokens and continue to work.
- Existing unprefixed patient test tokens continue to work through the legacy
  patient-only lookup path until they expire or are revoked.
- New patient tokens use `pat_`; new clinician tokens use `clin_`.
- Existing patient clients do not receive clinician scopes automatically.
- A client must obtain fresh consent for clinician scopes.
- Monitor clinician authentication failures, missing-scope failures, and
  assignment-denial audit events during activation.
- Keep clinician tools disabled if the migration or focused security suite does
  not pass.

## Acceptance Criteria

- A physician or nutrition specialist can complete the existing OAuth/PKCE
  flow and receive a clinician-scoped MCP token.
- The same MCP endpoint exposes clinician tools without exposing patient tools
  to a clinician token or clinician tools to a patient token.
- Only actively assigned patients appear in directory/overview results or can
  be targeted by patient-specific tools.
- Clinical lab writes and onboarding reviews work through existing service
  rules and return safe, agent-friendly results.
- Red-flag outcomes from clinical lab writes are returned atomically with the
  write result.
- Administrators and coordinators cannot issue or use clinician tokens.
- Patient MCP behavior and existing OAuth refresh/revocation behavior remain
  compatible.
- No sensitive credential or clinical payload is written to logs.
- Focused tests and the full Gradle suite pass.
