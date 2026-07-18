# Cohort, Patient, and Expert Assignment Management Design

Date: 2026-07-18

## Goal

Add a secure, server-rendered assignment-management workspace for coordinators and administrators.

The feature must support:

- cohort creation and metadata editing
- admin-only cohort archival
- patient membership in one or more active cohorts
- direct patient assignment to physicians and nutrition specialists
- cohort assignment to physicians, nutrition specialists, and coordinators
- historical assignment retention
- strict separation between coordinator operational access and expert clinical access

The design builds on the existing RBAC assignment persistence rather than introducing a parallel authorization model.

## Existing Context

Metabion already has:

- `Cohort`, `PatientCohortMembership`, `PatientExpertAssignment`, and `CohortStaffAssignment` entities
- repositories for those entities and active-assignment predicates
- partial unique indexes that prevent duplicate active rows for the same relationship
- `AccessControlService` checks for direct and cohort-derived patient access
- assignment attribution through `assignedBy` and `assignedAt`
- historical ending through `endedAt`
- a planned cohort-management menu item

The missing pieces are a management application service, write workflows, scoped list queries, web controllers, forms, an active menu route, and complete authorization tests.

The current authorization model also treats `COORDINATOR` as a clinical role in patient-data checks. That is broader than the approved product policy. A coordinator can create a cohort and add patients, so cohort membership must not grant the coordinator access to detailed clinical data.

## Chosen Approach

Use a cohort-centric workspace with a dedicated direct-assignment view.

The primary screen lists cohorts. Selecting a cohort opens its metadata, patient memberships, and care-team assignments. Each patient row exposes a care-team panel that clearly separates direct assignments from access inherited through cohorts.

A second `Direct assignments` tab lists:

- for a coordinator, the deduplicated patients belonging to any cohort actively assigned to that coordinator
- for an administrator, every active patient, including patients with no cohort membership

This preserves cohort context for coordinator actions while keeping direct patient-to-expert assignment usable when the expert is not assigned to the patient's cohort.

No REST assignment API is added in this slice. The application service remains independent of MVC so an API can be added later without duplicating authorization rules.

## Alternatives Considered

### Separate Workflow Pages

Use independent pages for cohort membership, direct patient assignments, and cohort staff assignments.

Each page would be simple, but users would repeatedly switch pages and reconstruct patient/cohort context. It also makes coordinator scope less visible during sensitive writes.

### Bulk Assignment Matrix

Use a spreadsheet-like matrix of patients, cohorts, and staff.

This is efficient for large bulk operations but is dense, difficult on smaller screens, and makes accidental relationship changes more likely. Pilot-scale use does not justify that complexity.

## Authorization Model

### Administrator

An `ADMIN` user may:

- view and manage every cohort
- create and edit cohorts
- archive cohorts
- add or end any patient cohort membership
- assign or unassign physicians, nutrition specialists, and coordinators from cohorts
- create or end direct patient assignments for any patient

Administrator clinical-data behavior remains unchanged by this feature.

### Coordinator

A `COORDINATOR` user may:

- create a cohort and become automatically assigned to it
- view and edit cohorts actively assigned to them
- add any active patient to an assigned cohort
- end patient memberships inside an assigned cohort
- assign or unassign physicians and nutrition specialists inside an assigned cohort
- create or end direct physician/nutrition-specialist assignments for patients belonging to at least one cohort actively assigned to the coordinator

A coordinator may not:

- archive a cohort
- assign or unassign coordinators
- manage a cohort not assigned to them
- directly assign a patient outside their active cohort scope
- view detailed clinical data solely because of coordinator role or cohort assignment

Creating a cohort creates an active `CohortStaffAssignment` for the coordinator in the same transaction. An administrator-created cohort does not require an automatic staff assignment because administrators have global management scope.

### Physician and Nutrition Specialist

`PHYSICIAN` and `NUTRITION_SPECIALIST` users may receive direct or cohort assignments. An active assignment grants clinical access to the applicable patients. These roles cannot manage cohort membership or staff assignments by default.

### Multi-Role Staff

Roles remain additive. A coordinator who also has `PHYSICIAN` or `NUTRITION_SPECIALIST` may receive clinical access through that expert role and an applicable direct or cohort assignment. Coordinator role alone never grants clinical access.

### Patient

Patients continue to access only their own data. Cohort membership does not expose other cohort participants.

## Coordinator Operational Data

The coordinator role is permitted to access only the following operational patient data:

- patient profile identifier and email
- active and historical cohort memberships
- assigned physicians and nutrition specialists
- summary participant status, consent status, screening outcome, and completeness indicators

This assignment-management slice renders only patient identifier/email, cohort membership, and assigned-expert metadata already available in the current model. Participant lifecycle, consent tracking, screening summaries, and completeness indicators require separate future features; this policy permits only their summary operational states, not their underlying clinical answers.

Coordinator role alone must not expose:

- detailed onboarding responses
- diet logs, meals, deviations, or photos
- symptom answers, daily check-ins, or patient trends
- laboratory values or laboratory trends
- clinical notes or other detailed clinical records

## Access-Control Refactoring

`AccessControlService` currently uses a broad patient-access predicate for clinical and operational callers. The implementation must introduce purpose-specific decisions so a cohort-management permission cannot be reused as clinical authorization.

The intended capabilities are:

- `canViewPatientClinicalData(Authentication, patientProfileId)`
- `canAccessCohort(Authentication, cohortId)`
- `canManageCohort(Authentication, cohortId)`
- `canManageCohortMemberships(Authentication, cohortId)`
- `canManageCohortStaff(Authentication, cohortId, targetStaffProfileId)`
- `canManageDirectExpertAssignments(Authentication, patientProfileId)`

`canViewPatientClinicalData` allows:

1. a patient accessing their own profile
2. an administrator under the current global-access policy
3. a physician or nutrition specialist with an active direct assignment
4. a physician or nutrition specialist assigned to an active cohort containing the patient

Coordinator role is intentionally absent from that clinical predicate.

Existing clinical services, controllers, directories, and menu items must be audited so `COORDINATOR` alone does not grant access to onboarding details, diet logs/photos, symptoms, laboratory results, trends, or clinical notes. Coordinator-facing navigation must emphasize operational workflows rather than linking to those clinical pages.

## Data Model

### Existing Assignment Tables

Continue using:

- `patient_cohort_memberships`
- `patient_expert_assignments`
- `cohort_staff_assignments`

An active relationship has `ended_at IS NULL`. Ending a relationship updates the active row; it never deletes it. A later reassignment creates a new row, preserving the previous interval.

The existing uniqueness rule remains relationship-specific:

- one active row for each patient/cohort pair
- one active row for each patient/staff pair
- one active row for each cohort/staff pair

There is no global one-cohort-per-patient restriction. A patient may belong to multiple active cohorts because cohorts may represent sites, pilots, phases, study arms, or operational groupings. Mutually exclusive study arms require a future study/arm model rather than a global cohort constraint.

### Cohort Archival

Add nullable archival metadata to `cohorts`:

- `archived_at`
- `archived_by_user_id`

Add required creation attribution to `cohorts`:

- `created_by_user_id` as a non-null foreign key to `users(id)`

The application has not been used and there are no legacy cohorts to preserve or backfill. Every cohort must therefore record its creator at both the database and application boundaries.

An archived cohort:

- remains queryable for history
- cannot be edited or receive new patient/staff assignments
- is omitted from active assignment choices
- has no active patient memberships or cohort staff assignments

Archival sets `archived_at`, records the administrator, and ends all active patient memberships and cohort staff assignments in one transaction. Direct patient assignments are independent and remain active.

### Ending Attribution

Add nullable `ended_by_user_id` to each assignment table. New application-driven ending operations must record the actor alongside `ended_at`. Legacy or system-ended rows may have a null actor.

The next migration from live repository state is `V20__cohort_assignment_management.sql`.

## Components

### `AssignmentManagementService`

This transactional service is the only application boundary for assignment writes. It depends on user, patient, staff, cohort, and assignment repositories plus the access-control service.

Responsibilities:

- resolve and validate the current actor
- create and edit cohorts
- archive cohorts atomically
- list cohorts visible to the actor
- list patient and staff candidates with role-appropriate scope
- create and end patient memberships
- create and end cohort staff assignments
- create and end direct patient expert assignments
- distinguish direct from cohort-inherited access in read models
- enforce active-user, target-role, duplicate, scope, and archive rules

Controllers must not write assignment repositories directly.

### Repositories

Extend existing repositories with focused methods for:

- active cohorts visible to a coordinator
- archived cohort history for administrators
- active and historical cohort memberships
- active and historical cohort staff assignments
- active and historical direct patient assignments
- deduplicated patients belonging to a coordinator's active cohorts
- active patient candidates
- active physician, nutrition-specialist, and coordinator candidates as permitted by the actor
- locked lookups used by write operations

Database uniqueness remains the final concurrency guard.

### Web Layer

Add a thin MVC controller under `/app/assignment-management` and server-rendered Thymeleaf templates/view fragments.

The controller exposes these MVC routes:

- `GET /app/assignment-management`
- `GET /app/assignment-management/cohorts/{cohortId}`
- `GET /app/assignment-management/direct`
- `POST /app/assignment-management/cohorts`
- `POST /app/assignment-management/cohorts/{cohortId}/edit`
- `POST /app/assignment-management/cohorts/{cohortId}/archive`
- `POST /app/assignment-management/cohorts/{cohortId}/patients`
- `POST /app/assignment-management/cohorts/{cohortId}/memberships/{membershipId}/end`
- `POST /app/assignment-management/cohorts/{cohortId}/staff`
- `POST /app/assignment-management/cohorts/{cohortId}/staff-assignments/{assignmentId}/end`
- `POST /app/assignment-management/patients/{patientProfileId}/direct-assignments`
- `POST /app/assignment-management/patients/{patientProfileId}/direct-assignments/{assignmentId}/end`

All writes use POST, include CSRF tokens, and redirect after success. Forms use validated request records and localized messages.

### Menu

Replace the planned cohort-management item with an active `Assignment management` route for coordinators. Add the same route to administrator utilities. Physicians and nutrition specialists do not receive assignment-management navigation.

Coordinator navigation must not expose existing clinical review pages solely because `COORDINATOR` is currently classified as clinical staff.

## User Workflows

### Create a Cohort

1. Resolve an authenticated coordinator or administrator.
2. Validate the cohort name and optional description.
3. Save the cohort with creation attribution.
4. If the creator is a coordinator, create their active cohort assignment in the same transaction.
5. Redirect to the new cohort workspace.

### Add a Patient to a Cohort

1. Resolve and lock the active cohort.
2. Require administrator scope or an active coordinator assignment to that cohort.
3. Resolve an active patient profile. Coordinators may select any active patient for enrollment.
4. Reject an already active membership for the same patient/cohort pair.
5. Create a membership with actor and timestamp attribution.

The patient may already belong to other cohorts.

### Assign Cohort Staff

1. Resolve and lock the active cohort.
2. Validate actor scope.
3. Resolve an active staff profile.
4. For a coordinator actor, allow only physician or nutrition-specialist targets.
5. For an administrator actor, also allow coordinator targets.
6. Reject an already active cohort/staff assignment.
7. Create the assignment with actor and timestamp attribution.

### Create a Direct Patient Assignment

1. Resolve the patient and target staff profile.
2. Require the target to have `PHYSICIAN` or `NUTRITION_SPECIALIST`.
3. For a coordinator actor, require the patient to belong to at least one cohort actively assigned to that coordinator.
4. For an administrator actor, allow any active patient.
5. Reject an already active patient/staff assignment.
6. Create the direct assignment with actor and timestamp attribution.

Direct assignments remain active independently of cohort membership.

### End an Assignment or Membership

1. Resolve the active row and its patient/cohort scope.
2. Recheck actor permission against current state.
3. Set `ended_at` and `ended_by_user_id`.
4. Redirect to refreshed state.

Cohort-inherited access cannot be ended from the direct-assignment panel. It must be managed through the relevant cohort.

### Archive a Cohort

1. Require an administrator.
2. Lock and recheck the active cohort.
3. Set archive actor and timestamp.
4. End every active patient membership and cohort staff assignment with the same actor and operation time.
5. Leave direct patient assignments untouched.
6. Redirect to archived cohort history.

## Interface Design

The workspace has two primary views.

### Cohorts

A cohort list and selected-cohort detail are shown together. The selected cohort contains:

- name, description, active/archive status, and allowed metadata actions
- `Patients` tab with active memberships and an add-patient action
- `Care team` tab with active staff assignments and allowed add/end actions
- patient rows that summarize direct and cohort-inherited experts
- a `Manage care team` action opening the patient's assignment panel

### Direct Assignments

The patient list is scope-aware:

- coordinator: patients from the coordinator's active cohorts
- administrator: all active patients, including those without a cohort

The patient care-team panel displays two separate sections:

- direct physician/nutrition-specialist assignments, with allowed add/end actions
- informational cohort-inherited access, with links or guidance to manage it from the cohort view

Removing a patient from a cohort ends inherited access from that cohort but does not end direct assignments. The interface states this behavior before confirmation.

## Validation and Error Handling

Validation rules include:

- cohort names are trimmed, contain 1-255 characters, and are not required to be globally unique
- cohort descriptions contain at most 4,000 characters
- active patient and staff accounts for new assignments
- eligible target roles
- non-archived cohort for every mutation other than archival history reads
- no duplicate active relationship

Failure behavior:

- unauthenticated requests follow existing session authentication behavior
- explicit role violations return `403 Forbidden`
- missing or out-of-scope objects use a generic not-found result where revealing existence would leak scoped data
- duplicate or stale state returns a localized conflict message
- validation errors rerender the current workspace without losing safe form input
- database uniqueness violations caused by concurrent writes are translated into the same conflict outcome

If another actor changes state after the page renders, the service rejects the stale action and the redirect reloads current state.

## Security and Privacy

- Server-side authorization is authoritative; menu visibility is not a security boundary.
- Coordinator cohort enrollment does not grant coordinator clinical-data access.
- Clinical access requires patient ownership, current administrator policy, or an applicable physician/nutrition-specialist assignment.
- Candidate searches expose only the minimum identity and role information needed for the operation.
- Direct and inherited access are labeled separately to prevent administrators from ending the wrong relationship.
- Cohort archival immediately ends cohort-inherited access.
- Assignment and archival writes record actor and timestamp metadata.
- No credentials, tokens, session identifiers, uploaded content, or clinical details are logged.
- Existing CSRF and session policies remain unchanged.

## Testing Strategy

### Service Tests

Cover the role and scope matrix:

- coordinator creates a cohort and receives an automatic cohort assignment
- administrator creates a cohort without requiring an assignment
- coordinator edits only assigned active cohorts
- administrator edits any active cohort
- coordinator adds any active patient to an assigned cohort
- patient may hold multiple active cohort memberships
- coordinator assigns physicians and nutrition specialists but not coordinators
- administrator may assign coordinators
- coordinator directly assigns an expert only to an in-scope patient
- administrator directly assigns an expert to any active patient
- physician and nutrition specialist cannot manage assignments
- only administrator can archive
- ending operations retain history and actor attribution
- archival ends memberships and cohort staff assignments but preserves direct assignments
- archived cohorts reject mutations
- duplicate and stale operations produce controlled conflicts

### Clinical Authorization Regression Tests

Cover the least-privilege correction:

- coordinator assigned to a cohort cannot read that cohort's patient clinical data
- coordinator cannot open clinical onboarding, diet, symptom, photo, laboratory, or trend endpoints/pages
- coordinator-plus-physician is allowed only with an applicable expert assignment
- coordinator-plus-nutrition-specialist is allowed only with an applicable expert assignment
- direct physician/nutrition assignment grants clinical access
- cohort physician/nutrition assignment grants clinical access
- ending membership or cohort expert assignment removes inherited clinical access
- direct access remains after cohort membership ends

### Repository Tests

Cover:

- archive and ending-attribution mappings
- required cohort-creator mapping and constraint
- active, archived, and scoped list queries
- multiple cohort membership for one patient
- partial unique index behavior
- ending and later reassignment
- atomic archival state on PostgreSQL
- concurrent duplicate-assignment attempts on PostgreSQL where appropriate

### MVC and Security Tests

Cover:

- coordinator and administrator workspace visibility
- physician, nutrition-specialist, patient, and anonymous rejection
- coordinator/admin-specific action visibility
- server-side rejection even when a forbidden action is posted manually
- CSRF on every mutation
- validation rerendering
- localized English and Czech labels, confirmations, successes, and errors
- active menu routes and removal of coordinator-only clinical links

### End-to-End Integration

Cover these representative flows:

1. Coordinator creates a cohort, adds a patient, and assigns a physician; the physician can read the patient while the coordinator cannot read clinical data.
2. Coordinator assigns both a physician and nutrition specialist directly to an in-scope patient; both receive access.
3. Ending cohort membership removes cohort-inherited access while direct access remains.
4. Administrator archives a cohort; all active cohort-derived access ends and historical rows remain.

Final verification:

```bash
./gradlew test
```

## Scope Boundaries

In scope:

- cohort-centric server-rendered management workspace
- cohort creation and metadata editing
- admin-only cohort archival
- patient cohort membership management
- cohort staff assignment management
- direct patient expert assignment management
- assignment ending and attribution
- coordinator operational/clinical permission separation
- menu, localization, validation, and security tests

Out of scope:

- REST assignment APIs
- bulk matrix editing
- study, site, protocol, or mutually exclusive arm modeling
- participant lifecycle implementation
- consent and screening workflow implementation
- data-completeness implementation
- research export implementation
- generic tamper-resistant audit-event subsystem
- notifications when assignments change
- redesign of administrator clinical-data policy

## Acceptance Criteria

1. Coordinators and administrators have an active assignment-management workspace.
2. A coordinator can create a cohort and is automatically assigned to it.
3. A coordinator can add any active patient to an assigned cohort.
4. A patient can belong to multiple active cohorts without duplicate membership in the same cohort.
5. Coordinators can assign physicians and nutrition specialists to their cohorts and in-scope patients.
6. Administrators can manage all assignments and assign coordinators to cohorts.
7. Only administrators can archive cohorts.
8. Archival ends cohort-derived access while retaining history and independent direct assignments.
9. Ending any relationship records actor and timestamp without deleting the row.
10. Coordinator role or coordinator cohort assignment never grants detailed clinical-data access.
11. Multi-role coordinators receive clinical access only through an applicable physician or nutrition-specialist role and assignment.
12. Physicians and nutrition specialists can access patients through active direct or cohort assignments but cannot manage assignments.
13. All mutation routes enforce CSRF and server-side scope checks.
14. English and Czech interfaces provide clear assignment source, confirmation, success, and error messaging.
15. Every cohort records its creator; a null creator is rejected.
16. The full Gradle test suite passes.
