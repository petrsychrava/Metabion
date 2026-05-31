# Role-Based Access Control and Patient Assignment Model Design

Date: 2026-05-31

## Goal

Add a durable authorization foundation for Metabion's clinical-study workflows.

The system must model patient, nutrition specialist, physician, coordinator, and administrator roles. It must support patient-to-expert assignment and cohort-based assignment so patients can access only their own data, while experts and coordinators can access only assigned patients or assigned cohorts. Administrators can access and manage all patients, cohorts, roles, and assignments.

This design is intentionally limited to RBAC and assignment foundations. It does not add food diary, lab-result, study protocol, visit schedule, or clinical-data endpoints.

## Existing Context

Metabion already has session-based authentication, `users`, `user_roles`, registration, verification, login/logout, password recovery, and an MFA extension point.

The current role model stores role names as strings in `user_roles`. Spring Security maps those strings into `ROLE_*` authorities during authentication. Patient registration currently assigns the `PATIENT` role.

The new design should preserve this authentication model and add fine-grained authorization as a separate domain slice. `users` remains the account and authentication identity for everyone. Patient and staff profile tables provide domain identities for authorization and future clinical data.

## Chosen Approach

Use structured RBAC plus assignment tables, with one service responsible for access decisions.

The role model remains based on `users` and `user_roles`, but supported values become constrained in application code and database checks:

- `PATIENT`
- `NUTRITION_SPECIALIST`
- `PHYSICIAN`
- `COORDINATOR`
- `ADMIN`

Assignments are modeled separately:

- direct patient-to-staff assignment
- cohort patient membership
- cohort staff assignment

The design treats cohorts as patient grouping units, not care-team aggregates. Staff assignments are separate rows that grant access to patients in the cohort. Assignment tables reference `patient_profiles` and `staff_profiles`, not raw `users` ids, so clinical authorization does not depend directly on the authentication table. This keeps the model useful for clinical-study groupings such as study arms, sites, pilots, or operational cohorts without forcing future study-management concepts into the cohort table too early.

## Alternatives Considered

### Method Security First

Enable Spring method security and annotate endpoints or services with expressions such as `@PreAuthorize("@accessControl.canAccessPatientProfile(authentication, #patientProfileId)")`.

This is idiomatic and can be added later, but it still needs the same assignment model underneath. Starting with a plain service keeps the first implementation easier to test and avoids spreading expression logic before real patient-data services exist.

### Repository-Scoped Queries

Push authorization into repository methods by querying only resources visible to the current user.

This provides strong data-access guardrails when implemented consistently, but it is easy to miss on future queries and is premature before patient diary, lab, and study modules exist. Scoped queries remain a good pattern for future high-risk endpoints.

## Data Model

### `patient_profiles`

One row per patient user.

Expected fields:

- `id`
- `user_id` unique foreign key to `users(id)`
- `created_at`
- `updated_at`

The profile gives patient-specific identity without overloading the generic `users` table. Registration should create a `PatientProfile` when it creates a patient account so access checks do not have to handle registered patients without profiles.

### `staff_profiles`

One row per clinical staff user.

Expected fields:

- `id`
- `user_id` unique foreign key to `users(id)`
- `created_at`
- `updated_at`

The profile gives staff-specific domain identity without turning `users` into a mixed authentication and clinical-staff table. Users with `NUTRITION_SPECIALIST`, `PHYSICIAN`, or `COORDINATOR` should have a `StaffProfile`. `ADMIN` users only need a staff profile when they also participate as clinical staff.

### `cohorts`

Simple grouping units for patients.

Expected fields:

- `id`
- `name`
- optional `description`
- `created_at`
- `updated_at`

The first implementation does not need study, site, protocol, or arm tables. Those can be added later and linked to cohorts if the product needs richer clinical-study modeling.

### `patient_cohort_memberships`

Links patients to cohorts.

Expected fields:

- `id`
- `patient_profile_id`
- `cohort_id`
- `assigned_by_user_id`, nullable only for system/bootstrap-created rows
- `assigned_at`
- `ended_at`

An active membership has `ended_at IS NULL`. Unassignment should end a row instead of deleting it.

The database should prevent more than one active membership for the same patient/cohort pair, while allowing historical ended rows.

### `patient_expert_assignments`

Grants direct staff access to a patient.

Expected fields:

- `id`
- `patient_profile_id`
- `staff_profile_id`
- `assigned_by_user_id`, nullable only for system/bootstrap-created rows
- `assigned_at`
- `ended_at`

The staff profile's user must have one of `NUTRITION_SPECIALIST`, `PHYSICIAN`, or `COORDINATOR`. Administrators do not need assignment rows because they have global access.

The database should prevent more than one active direct assignment for the same patient profile/staff profile pair, while allowing historical ended rows.

### `cohort_staff_assignments`

Grants staff access to patients in a cohort.

Expected fields:

- `id`
- `cohort_id`
- `staff_profile_id`
- `assigned_by_user_id`, nullable only for system/bootstrap-created rows
- `assigned_at`
- `ended_at`

The staff profile's user must have one of `NUTRITION_SPECIALIST`, `PHYSICIAN`, or `COORDINATOR`. A coordinator assigned to a cohort can manage assignments within that cohort, but cannot grant access outside their own scope.

The database should prevent more than one active staff assignment for the same cohort/staff profile pair, while allowing historical ended rows.

## Access Rules

### Patient Access

Given an authenticated user and a target patient profile:

1. If the user has `ADMIN`, allow.
2. If the user has `PATIENT`, allow only when the target profile belongs to the same user.
3. If the user has `NUTRITION_SPECIALIST`, `PHYSICIAN`, or `COORDINATOR`, allow when an active direct assignment exists.
4. If the user has `NUTRITION_SPECIALIST`, `PHYSICIAN`, or `COORDINATOR`, allow when the user's staff profile has an active assignment to a cohort where the patient has an active membership.
5. Otherwise deny.

Patients do not receive broad cohort access from cohort membership. Patient access remains self-only.

### Cohort Access

Given an authenticated user and a target cohort:

1. If the user has `ADMIN`, allow.
2. If the user has `COORDINATOR`, `NUTRITION_SPECIALIST`, or `PHYSICIAN`, allow when the user's staff profile has an active assignment to the cohort.
3. Otherwise deny.

### Cohort and Assignment Management

Management permissions are narrower than read access:

- `ADMIN` can create, update, and end all assignments and manage all cohorts.
- `COORDINATOR` can manage assignments inside cohorts assigned to them.
- `NUTRITION_SPECIALIST` and `PHYSICIAN` can read assigned patients and cohorts but cannot manage assignments by default.
- `PATIENT` cannot manage assignments.

Coordinator-created assignments must stay within the coordinator's own scope. A coordinator assigned to cohort A cannot assign staff to cohort B or assign patients unrelated to cohort A.

## Components

### `RoleName`

Add an enum for supported role values.

The enum should centralize role strings and Spring Security authority conversion. `User.addRole(...)` should reject unsupported values, and role checks should use the enum except at framework boundaries that require strings.

### Domain Entities

Add entities for:

- `PatientProfile`
- `StaffProfile`
- `Cohort`
- `PatientCohortMembership`
- `PatientExpertAssignment`
- `CohortStaffAssignment`

Entities should follow existing JPA conventions, use constructor injection in services, and keep Flyway as the schema owner.

### Repositories

Add repositories with focused existence checks for access decisions:

- find patient profile by user id
- find staff profile by user id
- determine active direct assignment
- determine active cohort-based assignment
- determine active cohort staff assignment
- determine active patient cohort membership

Repository methods should express the access predicates directly instead of forcing callers to load broad object graphs.

### `AccessControlService`

Add a service with focused methods such as:

- `canAccessPatientProfile(Authentication authentication, Long patientProfileId)`
- `canAccessCohort(Authentication authentication, Long cohortId)`
- `canManageCohort(Authentication authentication, Long cohortId)`
- `canManageAssignments(Authentication authentication, Long cohortId)`

`canAccessPatientProfile(...)` is the canonical patient access check. Callers that only have a patient user's `users.id` should first resolve the corresponding `PatientProfile`, or a clearly named convenience method can be added later if implementation shows repeated need.

The service resolves the authenticated principal to a `User`, resolves the corresponding patient or staff profile when needed, and applies the access rules above. It should return booleans and avoid deciding endpoint response shape. Controllers and future application services can translate denial into `403` or scoped `404` policies.

If user resolution starts duplicating across services, add a small `CurrentUserService` helper. Do not add it unless it removes real duplication.

### Existing Controllers

Update `/api/auth/me` to return roles in addition to email.

Update `/app` to display actual roles instead of an empty list. This is a small integration point proving that role information is available to web clients, not a new application dashboard.

## Error Handling and Security

Unauthenticated users continue to receive `401 Unauthorized` through Spring Security.

Authenticated users outside their scope should receive generic denial. Future resource endpoints can choose between:

- `403 Forbidden` after a failed access check
- scoped repository lookup returning `404 Not Found` when the resource is not visible

The foundation service should not reveal whether a patient profile or cohort exists beyond its boolean result.

No assignment operation should log passwords, tokens, session identifiers, or credentials. Assignment audit fields may store user ids, timestamps, and ended status, but not secrets.

Session-based authentication, CSRF policy, login timing equalization, generic login failures, token hashing, and password-reset behavior remain unchanged.

## Testing Strategy

Repository and persistence tests should cover:

- supported role values persist correctly
- unsupported role values are rejected at the domain or service boundary
- patient profile is tied to a patient user
- staff profile is tied to a staff-role user
- direct assignment grants access
- ended direct assignment does not grant access
- cohort membership plus staff assignment grants access
- ended cohort membership does not grant access
- ended staff assignment does not grant access
- duplicate active memberships and assignments are rejected, while historical ended rows can coexist

Service tests should cover:

- patient can access their own patient profile
- patient cannot access another patient
- nutrition specialist can access directly assigned patient
- physician can access directly assigned patient
- coordinator can access directly assigned patient
- staff can access patients through an assigned cohort
- staff cannot access unassigned patients
- admin can access all patients and cohorts
- patient cannot access cohorts broadly
- coordinator can manage assigned cohort
- nutrition specialist and physician cannot manage assignments by default

Web and integration tests should cover:

- `/api/auth/me` returns roles
- `/app` displays actual roles
- existing registration, login, logout, password recovery, rate limiting, CSRF, and session tests still pass

Full implementation verification should end with:

```bash
./gradlew test
```

## Scope Boundaries

In scope:

- five role values
- patient profile creation for registered patients
- staff profile support for nutrition specialists, physicians, and coordinators
- direct patient-to-staff assignment
- cohort membership
- cohort staff assignment
- centralized access-decision service
- tests for the access matrix
- role visibility in current-user responses

Out of scope:

- clinical data endpoints
- food diary and lab-result authorization
- study protocol, site, visit, and arm modeling
- administrator UI for managing assignments
- full audit event log
- MFA policy changes for expert/admin roles
- JWT or token-based authentication

## Acceptance Criteria Mapping

Patients only access their own data:

- enforced by `AccessControlService` patient checks, where `PATIENT` users match only their own `PatientProfile`.

Experts and coordinators only access assigned patients or cohorts:

- direct patient profile to staff profile assignment grants patient access
- shared active cohort membership plus active staff profile assignment grants patient access
- staff cohort access requires active cohort staff profile assignment

Roles for patient, nutrition specialist, physician, coordinator, and admin are modeled:

- `RoleName` defines all supported roles
- `user_roles` remains the persisted role table
- database and application validation restrict values to the supported set
