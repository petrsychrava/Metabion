# Cohort, Patient, and Expert Assignment Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a cohort-centric assignment workspace for coordinators and administrators while preventing coordinator role or cohort enrollment from granting clinical-data access.

**Architecture:** Extend the existing cohort and assignment entities with lifecycle attribution, then put all writes behind a transactional `AssignmentManagementService`. Split clinical authorization from operational cohort management in `AccessControlService`, expose the feature through CSRF-protected Thymeleaf MVC routes, and keep direct and cohort-inherited expert access visually and structurally distinct.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Thymeleaf, Spring Security sessions and CSRF, Spring Data JPA, Flyway, PostgreSQL 16 Testcontainers, JUnit 5, Mockito, AssertJ, Gradle wrapper.

## Global Constraints

- Do not add dependencies; use the existing Gradle and Spring stack.
- Use Flyway migration `V20__cohort_assignment_management.sql`; live repository state currently ends at V19.
- Every cohort must have a non-null creator; there is no legacy cohort backfill.
- Preserve multiple active cohorts per patient and one active row per relationship pair.
- New application-driven endings set `ended_at` plus `ended_by_user_id`; never delete history. Keep the actor nullable at schema level for historical/system-ended rows.
- Only `ADMIN` may archive cohorts or manage coordinator-to-cohort assignments.
- `COORDINATOR` may manage assigned cohorts but must never receive clinical-data access from that role.
- Clinical access is limited to patient ownership, the current admin policy, or an assigned `PHYSICIAN`/`NUTRITION_SPECIALIST` role.
- Keep session authentication and CSRF enabled; do not add JWTs or broaden exclusions.
- Add no REST assignment API in this feature.
- Keep English and Czech message keys aligned.
- Use focused tests during each task and finish with `./gradlew test`.

## File Structure

Create:

- `src/main/resources/db/migration/V20__cohort_assignment_management.sql` — cohort creator/archive fields and end-actor fields/constraints.
- `src/main/java/com/metabion/dto/assignment/AssignmentManagementForms.java` — validated MVC form records.
- `src/main/java/com/metabion/dto/assignment/AssignmentManagementView.java` — immutable cohort/direct-assignment read models.
- `src/main/java/com/metabion/service/AssignmentManagementService.java` — transactional authorization and orchestration boundary.
- `src/main/java/com/metabion/controller/web/AssignmentManagementWebController.java` — thin Thymeleaf controller.
- `src/main/resources/templates/assignment-management.html` — cohort-centric and direct-assignment workspace.
- `src/test/java/com/metabion/service/AssignmentManagementServiceTest.java` — service role/scope/lifecycle tests.
- `src/test/java/com/metabion/controller/web/AssignmentManagementWebControllerTest.java` — MVC, CSRF, role, form, and localization tests.
- `src/test/java/com/metabion/integration/AssignmentManagementIT.java` — PostgreSQL end-to-end assignment and access regression tests.

Modify:

- `src/main/java/com/metabion/domain/Cohort.java` — creator and archive lifecycle.
- `src/main/java/com/metabion/domain/PatientCohortMembership.java` — end actor and `end(User, Instant)` transition.
- `src/main/java/com/metabion/domain/PatientExpertAssignment.java` — end actor and `end(User, Instant)` transition.
- `src/main/java/com/metabion/domain/CohortStaffAssignment.java` — end actor and `end(User, Instant)` transition.
- `src/main/java/com/metabion/domain/RoleName.java` — distinguish clinical experts from all staff.
- `src/main/java/com/metabion/repository/CohortRepository.java` — active/scoped/locked cohort queries.
- `src/main/java/com/metabion/repository/PatientProfileRepository.java` — enabled and coordinator-scoped patient options.
- `src/main/java/com/metabion/repository/StaffProfileRepository.java` — enabled staff with roles.
- `src/main/java/com/metabion/repository/PatientCohortMembershipRepository.java` — active/history/locked membership queries.
- `src/main/java/com/metabion/repository/PatientExpertAssignmentRepository.java` — active/history/locked direct queries.
- `src/main/java/com/metabion/repository/CohortStaffAssignmentRepository.java` — active/history/locked cohort-staff queries.
- `src/main/java/com/metabion/service/AccessControlService.java` — purpose-specific clinical and management decisions.
- `src/main/java/com/metabion/service/ClinicalPatientDirectoryService.java` — expert-only clinical directory.
- `src/main/java/com/metabion/service/OnboardingService.java` — expert-only clinical review.
- `src/main/java/com/metabion/service/DietLogService.java` — expert-only clinical reads.
- `src/main/java/com/metabion/service/DietLogPhotoService.java` — expert-only clinical photo reads.
- `src/main/java/com/metabion/service/SymptomTrackingService.java` — expert-only clinical reads.
- `src/main/java/com/metabion/service/DailyTrendService.java` — expert-only clinical trends.
- `src/main/java/com/metabion/service/LabResultService.java` — expert-only clinical lab access.
- `src/main/java/com/metabion/service/LabTrendService.java` — expert-only clinical lab trends.
- `src/main/java/com/metabion/controller/web/AppMenuCatalog.java` — separate expert and coordinator navigation.
- `src/main/java/com/metabion/config/SecurityConfig.java` — coordinator/admin assignment route gate.
- `src/main/java/com/metabion/controller/web/WebExceptionHandler.java` — assignment-specific conflict destination/copy.
- `src/main/resources/messages.properties` — English assignment copy.
- `src/main/resources/messages_cs.properties` — Czech assignment copy.
- `src/main/resources/static/css/app.css` — responsive cohort workspace styles.
- `src/test/java/com/metabion/repository/RbacAssignmentRepositoryTest.java` — migration, mapping, history, and scoped-query tests.
- `src/test/java/com/metabion/domain/RoleNameTest.java` — clinical-expert classification.
- `src/test/java/com/metabion/service/AccessControlServiceTest.java` — least-privilege access matrix.
- Existing clinical service tests that mock `canAccessPatientProfile(Authentication, Long)` — rename mocks to `canViewPatientClinicalData(Authentication, Long)` and add coordinator denial cases.
- `src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java` — operational coordinator menu and active assignment route.
- `src/test/java/com/metabion/config/SecurityConfigTest.java` — assignment route roles and CSRF.

---

### Task 1: Persist Cohort and Assignment Lifecycle Attribution

**Files:**
- Create: `src/main/resources/db/migration/V20__cohort_assignment_management.sql`
- Modify: `src/main/java/com/metabion/domain/Cohort.java:1-84`
- Modify: `src/main/java/com/metabion/domain/PatientCohortMembership.java:1-101`
- Modify: `src/main/java/com/metabion/domain/PatientExpertAssignment.java:1-101`
- Modify: `src/main/java/com/metabion/domain/CohortStaffAssignment.java:1-101`
- Test: `src/test/java/com/metabion/repository/RbacAssignmentRepositoryTest.java`

**Interfaces:**
- Consumes: existing `User`, `Cohort`, and assignment relationships.
- Produces: `new Cohort(String name, String description, User createdBy)`, `Cohort.edit(String, String)`, `Cohort.archive(User, Instant)`, and `end(User, Instant)` on all three relationship entities.

- [ ] **Step 1: Write failing PostgreSQL mapping and lifecycle tests**

Update every existing one-argument `new Cohort(String)` test call to pass the existing `assignedBy` user through `new Cohort(String, String, User)`. Add:

```java
@Test
void cohortRequiresCreatorAndRecordsArchiveActor() {
    var creator = createUser("cohort-creator@example.com", RoleName.ADMIN);
    var cohort = cohorts.saveAndFlush(new Cohort("Pilot", "First pilot", creator));

    cohort.archive(creator, Instant.parse("2026-07-18T10:00:00Z"));
    cohorts.saveAndFlush(cohort);

    assertThat(cohort.getCreatedBy()).isEqualTo(creator);
    assertThat(cohort.getArchivedBy()).isEqualTo(creator);
    assertThat(cohort.isArchived()).isTrue();
    assertThatThrownBy(() -> jdbc.update(
            "insert into cohorts (name, created_by_user_id) values (?, null)", "Invalid"))
            .isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void endingRelationshipRecordsActor() {
    var patient = createPatientProfile("ended-actor-patient@example.com");
    var staff = createStaffProfile("ended-actor-staff@example.com");
    var actor = createUser("ended-actor-admin@example.com", RoleName.ADMIN);
    var cohort = cohorts.saveAndFlush(new Cohort("End actor", null, actor));
    var membership = patientCohortMemberships.saveAndFlush(
            new PatientCohortMembership(patient, cohort, actor));
    var direct = patientExpertAssignments.saveAndFlush(
            new PatientExpertAssignment(patient, staff, actor));
    var cohortStaff = cohortStaffAssignments.saveAndFlush(
            new CohortStaffAssignment(cohort, staff, actor));
    var endedAt = Instant.parse("2026-07-18T11:00:00Z");

    membership.end(actor, endedAt);
    direct.end(actor, endedAt);
    cohortStaff.end(actor, endedAt);
    patientCohortMemberships.saveAndFlush(membership);
    patientExpertAssignments.saveAndFlush(direct);
    cohortStaffAssignments.saveAndFlush(cohortStaff);

    assertThat(membership.getEndedAt()).isEqualTo(endedAt);
    assertThat(membership.getEndedBy()).isEqualTo(actor);
    assertThat(direct.getEndedAt()).isEqualTo(endedAt);
    assertThat(direct.getEndedBy()).isEqualTo(actor);
    assertThat(cohortStaff.getEndedAt()).isEqualTo(endedAt);
    assertThat(cohortStaff.getEndedBy()).isEqualTo(actor);
}
```

- [ ] **Step 2: Run the repository test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.repository.RbacAssignmentRepositoryTest'`

Expected: FAIL because the new constructor, archive/end actors, and V20 columns do not exist.

- [ ] **Step 3: Add the migration and lifecycle methods**

Create the migration with exact constraints. The three `ended_by_user_id`
columns deliberately remain nullable and have no equality check with
`ended_at`, preserving compatibility with historical/system-ended rows while
application methods always supply an actor:

```sql
ALTER TABLE cohorts
    ADD COLUMN created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN archived_by_user_id BIGINT REFERENCES users(id),
    ADD CONSTRAINT chk_cohorts_archive_actor
        CHECK ((archived_at IS NULL) = (archived_by_user_id IS NULL));

CREATE INDEX idx_cohorts_created_by_user_id ON cohorts(created_by_user_id);
CREATE INDEX idx_cohorts_archived_by_user_id ON cohorts(archived_by_user_id);

ALTER TABLE patient_cohort_memberships
    ADD COLUMN ended_by_user_id BIGINT REFERENCES users(id);
ALTER TABLE patient_expert_assignments
    ADD COLUMN ended_by_user_id BIGINT REFERENCES users(id);
ALTER TABLE cohort_staff_assignments
    ADD COLUMN ended_by_user_id BIGINT REFERENCES users(id);

CREATE INDEX idx_pcm_ended_by_user_id ON patient_cohort_memberships(ended_by_user_id);
CREATE INDEX idx_pea_ended_by_user_id ON patient_expert_assignments(ended_by_user_id);
CREATE INDEX idx_csa_ended_by_user_id ON cohort_staff_assignments(ended_by_user_id);
```

Implement the cohort lifecycle:

```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "created_by_user_id", nullable = false, updatable = false)
private User createdBy;

@Column(name = "archived_at")
private Instant archivedAt;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "archived_by_user_id")
private User archivedBy;

public Cohort(String name, String description, User createdBy) {
    this.name = name;
    this.description = description;
    this.createdBy = java.util.Objects.requireNonNull(createdBy, "createdBy");
}

public void edit(String name, String description) {
    if (isArchived()) throw new IllegalStateException("Archived cohort cannot be edited");
    this.name = name;
    this.description = description;
}

public void archive(User actor, Instant at) {
    if (isArchived()) throw new IllegalStateException("Cohort is already archived");
    archivedBy = java.util.Objects.requireNonNull(actor, "actor");
    archivedAt = java.util.Objects.requireNonNull(at, "at");
}

public boolean isArchived() { return archivedAt != null; }
```

Add this relationship lifecycle to all three assignment entities:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "ended_by_user_id")
private User endedBy;

public void end(User actor, Instant at) {
    if (!isActive()) throw new IllegalStateException("Assignment is already ended");
    endedBy = java.util.Objects.requireNonNull(actor, "actor");
    endedAt = java.util.Objects.requireNonNull(at, "at");
}
```

Add getters for all new fields. Keep setters only where existing persistence tests need to construct an invalid interval; update normal tests to call `end(actor, at)`.

- [ ] **Step 4: Run the repository test to verify it passes**

Run: `./gradlew test --tests 'com.metabion.repository.RbacAssignmentRepositoryTest'`

Expected: PASS, including Flyway validation on PostgreSQL.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V20__cohort_assignment_management.sql src/main/java/com/metabion/domain/Cohort.java src/main/java/com/metabion/domain/PatientCohortMembership.java src/main/java/com/metabion/domain/PatientExpertAssignment.java src/main/java/com/metabion/domain/CohortStaffAssignment.java src/test/java/com/metabion/repository/RbacAssignmentRepositoryTest.java
git commit -m "Add assignment lifecycle attribution"
```

---

### Task 2: Separate Clinical Expert Access from Coordinator Operations

**Files:**
- Modify: `src/main/java/com/metabion/domain/RoleName.java:44-49`
- Modify: `src/main/java/com/metabion/service/AccessControlService.java:39-125`
- Modify: the eight clinical services listed in File Structure.
- Modify: `src/main/java/com/metabion/service/ClinicalPatientDirectoryService.java:33-43`
- Test: `src/test/java/com/metabion/domain/RoleNameTest.java`
- Test: `src/test/java/com/metabion/service/AccessControlServiceTest.java`
- Test: existing clinical service tests that mock the renamed access method.

**Interfaces:**
- Consumes: existing active direct/cohort repository predicates.
- Produces: `RoleName.isClinicalExpert()` and the six capability methods approved in the design, led by `canViewPatientClinicalData(Authentication, Long)`.

- [ ] **Step 1: Write failing least-privilege tests**

Add to `AccessControlServiceTest`:

```java
@Test
void coordinatorCannotViewClinicalDataThroughAssignedCohort() {
    var coordinator = user(20L, "coordinator@example.com", RoleName.COORDINATOR);
    var staff = staffProfile(200L, coordinator);
    when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
    when(staffProfiles.findByUserId(20L)).thenReturn(Optional.of(staff));
    when(cohortStaffAssignments.existsActiveAssignmentForPatient(10L, 200L)).thenReturn(true);

    assertThat(accessControlService.canViewPatientClinicalData(auth("coordinator@example.com"), 10L)).isFalse();
}

@Test
void coordinatorPhysicianCanViewAssignedClinicalData() {
    var user = user(21L, "dual@example.com", RoleName.COORDINATOR);
    user.addRole(RoleName.PHYSICIAN);
    var staff = staffProfile(210L, user);
    when(users.findByEmail("dual@example.com")).thenReturn(Optional.of(user));
    when(staffProfiles.findByUserId(21L)).thenReturn(Optional.of(staff));
    when(cohortStaffAssignments.existsActiveAssignmentForPatient(10L, 210L)).thenReturn(true);

    assertThat(accessControlService.canViewPatientClinicalData(auth("dual@example.com"), 10L)).isTrue();
}
```

Add coordinator-denial tests to `ClinicalPatientDirectoryServiceTest`, `OnboardingServiceTest`, `DietLogServiceTest`, `DietLogPhotoServiceTest`, `SymptomTrackingServiceTest`, `DailyTrendServiceTest`, `LabResultServiceTest`, and `LabTrendServiceTest` using each service's existing authenticated-user fixtures.

- [ ] **Step 2: Run focused tests to verify they fail**

Run:

```bash
./gradlew test --tests 'com.metabion.domain.RoleNameTest' --tests 'com.metabion.service.AccessControlServiceTest' --tests 'com.metabion.service.ClinicalPatientDirectoryServiceTest' --tests 'com.metabion.service.OnboardingServiceTest' --tests 'com.metabion.service.DietLogServiceTest' --tests 'com.metabion.service.DietLogPhotoServiceTest' --tests 'com.metabion.service.SymptomTrackingServiceTest' --tests 'com.metabion.service.DailyTrendServiceTest' --tests 'com.metabion.service.LabResultServiceTest' --tests 'com.metabion.service.LabTrendServiceTest'
```

Expected: FAIL because coordinators are still treated as clinical readers and the renamed method is absent.

- [ ] **Step 3: Implement explicit capabilities and update callers**

Add:

```java
public boolean isClinicalExpert() {
    return this == NUTRITION_SPECIALIST || this == PHYSICIAN;
}
```

Replace `canAccessPatientProfile` with:

```java
public boolean canViewPatientClinicalData(Authentication authentication, Long patientProfileId) {
    return currentUser(authentication)
            .map(user -> canViewPatientClinicalData(user, patientProfileId))
            .orElse(false);
}

private boolean canViewPatientClinicalData(User user, Long patientProfileId) {
    if (user.hasRole(RoleName.ADMIN)) return true;
    if (user.hasRole(RoleName.PATIENT) && ownsPatientProfile(user, patientProfileId)) return true;
    if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN)) return false;
    return staffProfiles.findByUserId(user.getId())
            .map(staff -> hasPatientAssignment(patientProfileId, staff))
            .orElse(false);
}

public boolean canAccessCohort(Authentication authentication, Long cohortId) {
    return currentUser(authentication).map(actor -> {
        if (actor.hasRole(RoleName.ADMIN)) return cohorts.existsById(cohortId);
        if (!actor.hasAnyRole(RoleName.COORDINATOR, RoleName.PHYSICIAN,
                RoleName.NUTRITION_SPECIALIST)) return false;
        return cohorts.findById(cohortId)
                .filter(cohort -> !cohort.isArchived())
                .flatMap(cohort -> staffProfiles.findByUserId(actor.getId()))
                .map(staff -> cohortStaffAssignments.existsActiveAssignment(
                        cohortId, staff.getId()))
                .orElse(false);
    }).orElse(false);
}

public boolean canManageCohort(Authentication authentication, Long cohortId) {
    return currentUser(authentication).map(actor -> cohorts.findById(cohortId)
            .filter(cohort -> !cohort.isArchived())
            .map(cohort -> {
                if (actor.hasRole(RoleName.ADMIN)) return true;
                if (!actor.hasRole(RoleName.COORDINATOR)) return false;
                return staffProfiles.findByUserId(actor.getId())
                        .map(staff -> cohortStaffAssignments.existsActiveAssignment(
                                cohortId, staff.getId()))
                        .orElse(false);
            }).orElse(false)).orElse(false);
}

public boolean canManageCohortMemberships(Authentication authentication, Long cohortId) {
    return canManageCohort(authentication, cohortId);
}

public boolean canManageCohortStaff(Authentication authentication, Long cohortId, Long targetStaffProfileId) {
    return currentUser(authentication).map(actor -> {
        if (actor.hasRole(RoleName.ADMIN)) return true;
        if (!actor.hasRole(RoleName.COORDINATOR)
                || !canManageCohort(authentication, cohortId)) return false;
        return staffProfiles.findById(targetStaffProfileId)
                .map(StaffProfile::getUser)
                .map(target -> !target.hasRole(RoleName.COORDINATOR)
                        && target.hasAnyRole(RoleName.PHYSICIAN, RoleName.NUTRITION_SPECIALIST))
                .orElse(false);
    }).orElse(false);
}

public boolean canManageDirectExpertAssignments(Authentication authentication, Long patientProfileId) {
    return currentUser(authentication).map(actor -> {
        if (actor.hasRole(RoleName.ADMIN)) return true;
        if (!actor.hasRole(RoleName.COORDINATOR)) return false;
        return staffProfiles.findByUserId(actor.getId())
                .map(staff -> cohortStaffAssignments.existsActiveAssignmentForPatient(
                        patientProfileId, staff.getId()))
                .orElse(false);
}).orElse(false);
}
```

Add `CohortRepository cohorts` to the service constructor and field list for
the active/archive checks above. Replace the old private cohort-access and
cohort-management helpers with the implementations above. Remove the
now-redundant `canManageAssignments(Authentication, Long)` alias after updating
any callers to the purpose-specific methods.

In every clinical service, replace `canAccessPatientProfile(Authentication, Long)` with `canViewPatientClinicalData(Authentication, Long)` and replace clinical role lists with:

```java
if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.ADMIN)) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot access clinical data");
}
```

In `DietLogPhotoService`, allow attached clinical photo reads only for physician/nutrition-specialist users passing `canViewPatientClinicalData`. In `ClinicalPatientDirectoryService`, reject coordinator-only users before executing any patient query. Do not change coordinator eligibility in staff invitations or education content management.

- [ ] **Step 4: Run focused tests to verify they pass**

Run the command from Step 2.

Expected: PASS; coordinator-only users are denied and dual-role assigned experts remain allowed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/domain/RoleName.java src/main/java/com/metabion/service/AccessControlService.java src/main/java/com/metabion/service/ClinicalPatientDirectoryService.java src/main/java/com/metabion/service/OnboardingService.java src/main/java/com/metabion/service/DietLogService.java src/main/java/com/metabion/service/DietLogPhotoService.java src/main/java/com/metabion/service/SymptomTrackingService.java src/main/java/com/metabion/service/DailyTrendService.java src/main/java/com/metabion/service/LabResultService.java src/main/java/com/metabion/service/LabTrendService.java src/test/java/com/metabion/domain/RoleNameTest.java src/test/java/com/metabion/service/AccessControlServiceTest.java src/test/java/com/metabion/service/ClinicalPatientDirectoryServiceTest.java src/test/java/com/metabion/service/OnboardingServiceTest.java src/test/java/com/metabion/service/DietLogServiceTest.java src/test/java/com/metabion/service/DietLogPhotoServiceTest.java src/test/java/com/metabion/service/SymptomTrackingServiceTest.java src/test/java/com/metabion/service/DailyTrendServiceTest.java src/test/java/com/metabion/service/LabResultServiceTest.java src/test/java/com/metabion/service/LabTrendServiceTest.java
git commit -m "Separate coordinator and clinical access"
```

---

### Task 3: Add Scoped Queries and Assignment Read Models

**Files:**
- Create: `src/main/java/com/metabion/dto/assignment/AssignmentManagementForms.java`
- Create: `src/main/java/com/metabion/dto/assignment/AssignmentManagementView.java`
- Modify: all six repositories listed in File Structure.
- Test: `src/test/java/com/metabion/repository/RbacAssignmentRepositoryTest.java`

**Interfaces:**
- Consumes: lifecycle fields from Task 1.
- Produces: validated `CohortForm`, `SelectionForm`, view records, locked entity lookups, active/history lists, enabled candidate lists, and coordinator-scoped patients.

- [ ] **Step 1: Write failing scoped-query tests**

Add repository tests proving disabled users are excluded, an assigned coordinator sees patients from all their active cohorts once, archived cohorts are excluded, and active/history lists are ordered deterministically. Use this core assertion:

```java
assertThat(patientProfiles.findEnabledPatientOptionsForStaff(coordinatorProfile.getId()))
        .extracting(PatientOptionResponse::email)
        .containsExactly("a@example.com", "b@example.com");
assertThat(cohorts.findActiveForStaff(coordinatorProfile.getId()))
        .extracting(Cohort::getName)
        .containsExactly("Alpha", "Beta");
```

Update the test's existing `createUser` helper to call `user.setEnabled(true)`
before its first save. Add a separate `createDisabledUser` helper for the
disabled-candidate assertions so all pre-existing fixtures retain their intended
active semantics.

- [ ] **Step 2: Run the repository test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.repository.RbacAssignmentRepositoryTest'`

Expected: FAIL because the scoped queries and view/form types do not exist.

- [ ] **Step 3: Implement forms, views, and repository methods**

Create `AssignmentManagementForms`:

```java
package com.metabion.dto.assignment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AssignmentManagementForms {
    private AssignmentManagementForms() {}

    public record CohortForm(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 4000) String description) {}

    public record SelectionForm(@NotNull Long targetId) {}
}
```

Create `AssignmentManagementView` with these exact nested types:

```java
package com.metabion.dto.assignment;

import java.time.Instant;
import java.util.List;

public final class AssignmentManagementView {
    private AssignmentManagementView() {}

    public enum AccessSource { DIRECT, COHORT }

    public record CohortItem(Long id, String name, String description, boolean archived,
                             String createdByEmail, Instant createdAt) {}
    public record StaffOption(Long staffProfileId, String email, List<String> roles) {}
    public record ExpertAccess(Long assignmentId, Long staffProfileId, String email,
                               List<String> roles, AccessSource source,
                               Long cohortId, String cohortName) {}
    public record PatientRow(Long membershipId, Long patientProfileId, String email,
                             List<ExpertAccess> direct, List<ExpertAccess> inherited) {}
    public record CohortPage(List<CohortItem> cohorts, CohortItem selected,
                             List<PatientRow> patients, List<ExpertAccess> careTeam,
                             List<com.metabion.dto.PatientOptionResponse> patientCandidates,
                             List<StaffOption> staffCandidates) {}
    public record DirectPatient(Long patientProfileId, String email, List<CohortItem> cohorts,
                                List<ExpertAccess> direct, List<ExpertAccess> inherited) {}
    public record DirectPage(List<DirectPatient> patients, List<StaffOption> staffCandidates) {}
}
```

Add these exact repository contracts (with the shown annotations/queries and the
corresponding imports). The critical patient query is:

```java
@Query("""
        select distinct new com.metabion.dto.PatientOptionResponse(profile.id, user.email)
        from PatientProfile profile
        join profile.user user
        join PatientCohortMembership membership on membership.patientProfile = profile
        join CohortStaffAssignment assignment on assignment.cohort = membership.cohort
        where assignment.staffProfile.id = :staffProfileId
          and user.enabled = true
          and membership.endedAt is null
          and assignment.endedAt is null
          and membership.cohort.archivedAt is null
        order by user.email asc, profile.id asc
        """)
List<PatientOptionResponse> findEnabledPatientOptionsForStaff(
        @Param("staffProfileId") Long staffProfileId);
```

`CohortRepository`:

```java
@Query("""
        select cohort from Cohort cohort
        where cohort.archivedAt is null
        order by lower(cohort.name), cohort.id
        """)
List<Cohort> findAllActive();

@Query("""
        select cohort from Cohort cohort
        order by case when cohort.archivedAt is null then 0 else 1 end,
                 lower(cohort.name), cohort.id
        """)
List<Cohort> findAllForAdministration();

@Query("""
        select cohort from Cohort cohort
        join CohortStaffAssignment assignment on assignment.cohort = cohort
        where assignment.staffProfile.id = :staffProfileId
          and assignment.endedAt is null
          and cohort.archivedAt is null
        order by lower(cohort.name), cohort.id
        """)
List<Cohort> findActiveForStaff(@Param("staffProfileId") Long staffProfileId);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select cohort from Cohort cohort where cohort.id = :id")
Optional<Cohort> lockById(@Param("id") Long id);
```

`PatientProfileRepository`:

```java
@Query("""
        select new com.metabion.dto.PatientOptionResponse(profile.id, user.email)
        from PatientProfile profile
        join profile.user user
        where user.enabled = true
        order by user.email asc, profile.id asc
        """)
List<PatientOptionResponse> findAllEnabledPatientOptions();
```

Keep the existing `lockById`. Add `user.enabled = true` and an archived-cohort
predicate to `findAccessiblePatientOptionsForStaff`, because that query remains
the clinical expert directory. Do not replace it with the operational coordinator
query.

`StaffProfileRepository`:

```java
@EntityGraph(attributePaths = {"user", "user.roles"})
@Query("""
        select distinct profile from StaffProfile profile
        join profile.user user
        where user.enabled = true
        order by user.email asc, profile.id asc
        """)
List<StaffProfile> findAllEnabledWithRoles();

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select profile from StaffProfile profile where profile.id = :id")
Optional<StaffProfile> lockById(@Param("id") Long id);
```

`PatientCohortMembershipRepository`:

```java
@EntityGraph(attributePaths = {"patientProfile", "patientProfile.user"})
@Query("""
        select membership from PatientCohortMembership membership
        where membership.cohort.id = :cohortId and membership.endedAt is null
        order by membership.patientProfile.user.email, membership.id
        """)
List<PatientCohortMembership> findActiveByCohortId(@Param("cohortId") Long cohortId);

@EntityGraph(attributePaths = {"patientProfile", "patientProfile.user", "endedBy"})
@Query("""
        select membership from PatientCohortMembership membership
        where membership.cohort.id = :cohortId
        order by membership.assignedAt desc, membership.id desc
        """)
List<PatientCohortMembership> findHistoryByCohortId(@Param("cohortId") Long cohortId);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
        select membership from PatientCohortMembership membership
        where membership.id = :id and membership.endedAt is null
        """)
Optional<PatientCohortMembership> findActiveById(@Param("id") Long id);
```

`PatientExpertAssignmentRepository`:

```java
@EntityGraph(attributePaths = {"staffProfile", "staffProfile.user", "staffProfile.user.roles"})
@Query("""
        select assignment from PatientExpertAssignment assignment
        where assignment.patientProfile.id = :patientProfileId
          and assignment.endedAt is null
        order by assignment.staffProfile.user.email, assignment.id
        """)
List<PatientExpertAssignment> findActiveByPatientProfileId(
        @Param("patientProfileId") Long patientProfileId);

@Query("""
        select assignment from PatientExpertAssignment assignment
        where assignment.patientProfile.id = :patientProfileId
        order by assignment.assignedAt desc, assignment.id desc
        """)
List<PatientExpertAssignment> findHistoryByPatientProfileId(
        @Param("patientProfileId") Long patientProfileId);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
        select assignment from PatientExpertAssignment assignment
        where assignment.id = :id and assignment.endedAt is null
        """)
Optional<PatientExpertAssignment> findActiveById(@Param("id") Long id);
```

`CohortStaffAssignmentRepository`:

```java
@EntityGraph(attributePaths = {"staffProfile", "staffProfile.user", "staffProfile.user.roles"})
@Query("""
        select assignment from CohortStaffAssignment assignment
        where assignment.cohort.id = :cohortId and assignment.endedAt is null
        order by assignment.staffProfile.user.email, assignment.id
        """)
List<CohortStaffAssignment> findActiveByCohortId(@Param("cohortId") Long cohortId);

@Query("""
        select assignment from CohortStaffAssignment assignment
        where assignment.cohort.id = :cohortId
        order by assignment.assignedAt desc, assignment.id desc
        """)
List<CohortStaffAssignment> findHistoryByCohortId(@Param("cohortId") Long cohortId);

@EntityGraph(attributePaths = {
        "cohort", "staffProfile", "staffProfile.user", "staffProfile.user.roles"
})
@Query("""
        select assignment from CohortStaffAssignment assignment
        join PatientCohortMembership membership on membership.cohort = assignment.cohort
        where membership.patientProfile.id = :patientProfileId
          and membership.endedAt is null
          and assignment.endedAt is null
          and assignment.cohort.archivedAt is null
        order by assignment.staffProfile.user.email, assignment.cohort.name, assignment.id
        """)
List<CohortStaffAssignment> findActiveAssignmentsForPatient(
        @Param("patientProfileId") Long patientProfileId);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
        select assignment from CohortStaffAssignment assignment
        where assignment.id = :id and assignment.endedAt is null
        """)
Optional<CohortStaffAssignment> findActiveById(@Param("id") Long id);
```

Retain the existing pair-existence methods, but add
`and assignment.cohort.archivedAt is null` to
`CohortStaffAssignmentRepository.existsActiveAssignmentForPatient(...)` so an
archived cohort can never grant operational or clinical scope. Every query used
by a rendered list must keep its explicit ordering.

- [ ] **Step 4: Run repository tests to verify they pass**

Run: `./gradlew test --tests 'com.metabion.repository.RbacAssignmentRepositoryTest'`

Expected: PASS with deterministic, enabled-only, archive-aware results.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/dto/assignment/AssignmentManagementForms.java src/main/java/com/metabion/dto/assignment/AssignmentManagementView.java src/main/java/com/metabion/repository/CohortRepository.java src/main/java/com/metabion/repository/PatientProfileRepository.java src/main/java/com/metabion/repository/StaffProfileRepository.java src/main/java/com/metabion/repository/PatientCohortMembershipRepository.java src/main/java/com/metabion/repository/PatientExpertAssignmentRepository.java src/main/java/com/metabion/repository/CohortStaffAssignmentRepository.java src/test/java/com/metabion/repository/RbacAssignmentRepositoryTest.java
git commit -m "Add scoped assignment queries"
```

---

### Task 4: Implement Cohort Creation, Editing, and Archival

**Files:**
- Create: `src/main/java/com/metabion/service/AssignmentManagementService.java`
- Create: `src/test/java/com/metabion/service/AssignmentManagementServiceTest.java`

**Interfaces:**
- Consumes: Task 1 lifecycle methods, Task 2 capabilities, Task 3 repositories/forms/views.
- Produces: `listCohorts`, `createCohort`, `updateCohort`, and `archiveCohort` transactional methods.

- [ ] **Step 1: Write failing cohort lifecycle service tests**

Cover coordinator auto-assignment, admin creation without auto-assignment, assigned-only coordinator editing, admin-only archival, atomic ending, and archived mutation rejection. Include:

```java
@Test
void coordinatorCreationAssignsCreatorToCohort() {
    when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
    when(staffProfiles.findByUserId(coordinator.getId())).thenReturn(Optional.of(coordinatorProfile));
    when(cohorts.save(any(Cohort.class))).thenAnswer(invocation -> invocation.getArgument(0));

    service.createCohort(auth("coordinator@example.com"), new CohortForm("Pilot", "2026 pilot"));

    verify(cohortStaffAssignments).save(argThat(assignment ->
            assignment.getStaffProfile().equals(coordinatorProfile)
                    && assignment.getAssignedBy().equals(coordinator)));
}

@Test
void archiveEndsCohortRelationshipsButNotDirectAssignments() {
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
    when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
    when(memberships.findActiveByCohortId(10L)).thenReturn(List.of(membership));
    when(cohortStaffAssignments.findActiveByCohortId(10L)).thenReturn(List.of(staffAssignment));

    service.archiveCohort(auth("admin@example.com"), 10L);

    assertThat(cohort.isArchived()).isTrue();
    assertThat(membership.getEndedBy()).isEqualTo(admin);
    assertThat(staffAssignment.getEndedBy()).isEqualTo(admin);
    verifyNoInteractions(directAssignments);
}
```

- [ ] **Step 2: Run the service test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.service.AssignmentManagementServiceTest'`

Expected: FAIL because `AssignmentManagementService` does not exist.

- [ ] **Step 3: Implement minimal cohort lifecycle service**

Use constructor injection for all repositories, `AccessControlService`, and `Clock`. Implement signatures:

```java
public List<CohortItem> listCohorts(Authentication authentication)
public CohortItem createCohort(Authentication authentication, CohortForm form)
public void updateCohort(Authentication authentication, Long cohortId, CohortForm form)
public void archiveCohort(Authentication authentication, Long cohortId)
```

Core creation and archival code:

```java
@Transactional
public CohortItem createCohort(Authentication authentication, CohortForm form) {
    var actor = requireAssignmentManager(authentication);
    var cohort = cohorts.save(new Cohort(normalizeName(form.name()), trimToNull(form.description()), actor));
    if (!actor.hasRole(RoleName.ADMIN) && actor.hasRole(RoleName.COORDINATOR)) {
        var staff = staffProfiles.findByUserId(actor.getId())
                .orElseThrow(() -> forbidden("Coordinator staff profile not found"));
        cohortStaffAssignments.save(new CohortStaffAssignment(cohort, staff, actor));
    }
    return cohortItem(cohort);
}

@Transactional
public void archiveCohort(Authentication authentication, Long cohortId) {
    var actor = currentUser(authentication);
    if (!actor.hasRole(RoleName.ADMIN)) throw forbidden("Only administrators can archive cohorts");
    var cohort = activeLockedCohort(cohortId);
    var now = clock.instant();
    cohort.archive(actor, now);
    memberships.findActiveByCohortId(cohortId).forEach(row -> row.end(actor, now));
    cohortStaffAssignments.findActiveByCohortId(cohortId).forEach(row -> row.end(actor, now));
}
```

Normalize email through `UserService.normalize`, trim cohort text, throw `NOT_FOUND` for missing/out-of-scope cohorts, `FORBIDDEN` for explicit role failures, and `CONFLICT` for archived/stale transitions.
`listCohorts` uses `findAllForAdministration()` for administrators so archived
cohorts remain queryable, and `findActiveForStaff(...)` for coordinators. An
archived cohort can be selected by an administrator for metadata/history display,
but `cohortPage` returns empty mutation-candidate lists for it and every write
method rejects it.

- [ ] **Step 4: Run service tests to verify they pass**

Run: `./gradlew test --tests 'com.metabion.service.AssignmentManagementServiceTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/service/AssignmentManagementService.java src/test/java/com/metabion/service/AssignmentManagementServiceTest.java
git commit -m "Add cohort lifecycle management"
```

---

### Task 5: Implement Patient Membership and Cohort Care-Team Writes

**Files:**
- Modify: `src/main/java/com/metabion/service/AssignmentManagementService.java`
- Modify: `src/test/java/com/metabion/service/AssignmentManagementServiceTest.java`

**Interfaces:**
- Consumes: Task 4 service and Task 2 cohort-management capabilities.
- Produces: add/end membership and add/end cohort staff methods.

- [ ] **Step 1: Write failing relationship-management tests**

Cover coordinator adding any enabled patient, multiple cohorts, duplicate conflicts, disabled patient rejection, coordinator physician/nutrition targets, coordinator and coordinator-plus-expert target rejection, admin coordinator target, mismatched relationship IDs, and end attribution.

```java
@Test
void coordinatorMayAssignExpertButNotAnotherCoordinator() {
    when(accessControl.canManageCohortStaff(auth, 10L, physicianProfile.getId())).thenReturn(true);
    when(accessControl.canManageCohortStaff(auth, 10L, coordinatorTarget.getId())).thenReturn(false);

    service.assignCohortStaff(auth, 10L, physicianProfile.getId());
    assertThatThrownBy(() -> service.assignCohortStaff(auth, 10L, coordinatorTarget.getId()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403 FORBIDDEN");
}
```

- [ ] **Step 2: Run the service test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.service.AssignmentManagementServiceTest'`

Expected: FAIL because relationship write methods are absent.

- [ ] **Step 3: Implement membership and cohort staff methods**

Add exact signatures:

```java
public void addPatientToCohort(Authentication authentication, Long cohortId, Long patientProfileId)
public void endMembership(Authentication authentication, Long cohortId, Long membershipId)
public void assignCohortStaff(Authentication authentication, Long cohortId, Long staffProfileId)
public void endCohortStaffAssignment(Authentication authentication, Long cohortId, Long assignmentId)
```

Core validation pattern:

```java
if (!accessControl.canManageCohortMemberships(authentication, cohortId)) {
    throw notFound("Cohort not found");
}
var actor = requireAssignmentManager(authentication);
var cohort = activeLockedCohort(cohortId);
var patient = patientProfiles.lockById(patientProfileId)
        .filter(profile -> profile.getUser().isEnabled())
        .orElseThrow(() -> notFound("Patient profile not found"));
if (memberships.existsActiveMembership(patientProfileId, cohortId)) {
    throw conflict("Patient is already assigned to cohort");
}
memberships.save(new PatientCohortMembership(patient, cohort, actor));
```

For end operations, load the active row with a write lock, verify its cohort ID equals the path cohort ID, recheck current actor scope, then call `end(actor, clock.instant())`. Catch `DataIntegrityViolationException` around new relationship saves and translate it to the same `409 CONFLICT` used for the precheck.

For `assignCohortStaff`, first load the target through
`staffProfiles.lockById(staffProfileId)`, require an enabled user, and require at
least one of `PHYSICIAN`, `NUTRITION_SPECIALIST`, or `COORDINATOR`. Then call
`canManageCohortStaff(authentication, cohortId, staffProfileId)`, reject an
existing active pair with `409`, and save `new CohortStaffAssignment(cohort,
target, actor)`. This ordering ensures an administrator can assign all three
staff roles while a coordinator is limited by the capability method to physician
and nutrition-specialist targets. When ending a cohort staff row, pass the row's
target staff-profile ID back through the same capability check; this is what
prevents a coordinator from ending another coordinator's cohort assignment.

- [ ] **Step 4: Run service tests to verify they pass**

Run: `./gradlew test --tests 'com.metabion.service.AssignmentManagementServiceTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/service/AssignmentManagementService.java src/test/java/com/metabion/service/AssignmentManagementServiceTest.java
git commit -m "Manage cohort memberships and care teams"
```

---

### Task 6: Implement Direct Assignments and Workspace Read Models

**Files:**
- Modify: `src/main/java/com/metabion/service/AssignmentManagementService.java`
- Modify: `src/test/java/com/metabion/service/AssignmentManagementServiceTest.java`

**Interfaces:**
- Consumes: Task 3 view records and Task 5 relationship writes.
- Produces: `assignDirectExpert`, `endDirectExpertAssignment`, `cohortPage`, and `directPage`.

- [ ] **Step 1: Write failing direct/read-model tests**

Cover coordinator in-scope restriction, admin any-patient behavior, expert-role target validation, duplicate direct conflict, direct-plus-inherited coexistence, direct persistence after membership ending, enabled candidate filtering, and separate access sources.

```java
@Test
void directAndInheritedAccessAreReturnedSeparately() {
    when(directAssignments.findActiveByPatientProfileId(20L)).thenReturn(List.of(direct));
    when(cohortStaffAssignments.findActiveAssignmentsForPatient(20L)).thenReturn(List.of(inherited));

    var page = service.directPage(adminAuth);

    assertThat(page.patients().getFirst().direct())
            .extracting(ExpertAccess::source).containsOnly(AccessSource.DIRECT);
    assertThat(page.patients().getFirst().inherited())
            .extracting(ExpertAccess::source).containsOnly(AccessSource.COHORT);
}
```

- [ ] **Step 2: Run the service test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.service.AssignmentManagementServiceTest'`

Expected: FAIL because direct operations and page assemblers are absent.

- [ ] **Step 3: Implement direct writes and page assemblers**

Add signatures:

```java
public void assignDirectExpert(Authentication authentication, Long patientProfileId, Long staffProfileId)
public void endDirectExpertAssignment(Authentication authentication, Long patientProfileId, Long assignmentId)
@Transactional(readOnly = true) public CohortPage cohortPage(Authentication authentication, Long cohortId)
@Transactional(readOnly = true) public DirectPage directPage(Authentication authentication)
```

Direct assignment validation:

```java
if (!accessControl.canManageDirectExpertAssignments(authentication, patientProfileId)) {
    throw notFound("Patient profile not found");
}
var actor = requireAssignmentManager(authentication);
var patient = patientProfiles.lockById(patientProfileId)
        .filter(profile -> profile.getUser().isEnabled())
        .orElseThrow(() -> notFound("Patient profile not found"));
var target = staffProfiles.lockById(staffProfileId)
        .filter(profile -> profile.getUser().isEnabled())
        .filter(profile -> profile.getUser().hasAnyRole(
                RoleName.PHYSICIAN, RoleName.NUTRITION_SPECIALIST))
        .orElseThrow(() -> badRequest("Target must be an active physician or nutrition specialist"));
if (directAssignments.existsActiveAssignment(patientProfileId, staffProfileId)) {
    throw conflict("Expert is already directly assigned to patient");
}
directAssignments.save(new PatientExpertAssignment(patient, target, actor));
```

For coordinator pages, resolve the coordinator `StaffProfile` and use scoped cohort/patient queries. For admins, use all active cohorts/patients. Build immutable lists with direct access marked `DIRECT` and cohort-derived access marked `COHORT`; do not merge the two relationships even when they reference the same expert.

For `endDirectExpertAssignment`, lock the active row, verify its patient-profile
ID equals the path ID, re-run `canManageDirectExpertAssignments` for that
patient, then call `end(actor, clock.instant())`. Translate a missing, ended, or
mismatched row to `404` so relationship IDs cannot reveal out-of-scope data.

Candidate/read-model rules are exact: an administrator's cohort list includes
active and archived cohorts, but archived pages have no candidates; a
coordinator sees only actively assigned, non-archived cohorts. Cohort staff
candidates are enabled physician/nutrition/coordinator profiles for admins and
enabled physician/nutrition profiles without the coordinator role for
coordinators, excluding active pairs.
Patient candidates for any active cohort come from
`findAllEnabledPatientOptions()` for both roles, excluding patients already
active in that cohort; this is what allows a coordinator to enroll any active
patient.
Direct-assignment candidates are enabled physicians/nutrition specialists only,
excluding active direct pairs. The administrator direct page uses
`findAllEnabledPatientOptions()`; the coordinator page uses
`findEnabledPatientOptionsForStaff(...)`.
Do not add onboarding answers, diet/symptom/laboratory values, clinical notes,
or any other clinical field to these assignment read models.

- [ ] **Step 4: Run service tests to verify they pass**

Run: `./gradlew test --tests 'com.metabion.service.AssignmentManagementServiceTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/service/AssignmentManagementService.java src/test/java/com/metabion/service/AssignmentManagementServiceTest.java
git commit -m "Add direct expert assignment workflows"
```

---

### Task 7: Add MVC Routes, Security Rules, and Role-Specific Navigation

**Files:**
- Create: `src/main/java/com/metabion/controller/web/AssignmentManagementWebController.java`
- Create: `src/test/java/com/metabion/controller/web/AssignmentManagementWebControllerTest.java`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java:150-190`
- Modify: `src/main/java/com/metabion/controller/web/AppMenuCatalog.java:20-180`
- Modify: `src/main/java/com/metabion/controller/web/WebExceptionHandler.java:40-81`
- Test: `src/test/java/com/metabion/config/SecurityConfigTest.java`
- Test: `src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java`

**Interfaces:**
- Consumes: all Task 4-6 service methods.
- Produces: the exact MVC routes from the design and coordinator/admin navigation.

- [ ] **Step 1: Write failing MVC, CSRF, and menu tests**

Add tests that coordinator/admin GET succeeds, physician/patient GET is forbidden, every POST requires CSRF, coordinator menu excludes clinical links, physician menu excludes assignment management, and admin/coordinator menus link `/app/assignment-management`.

```java
@Test
void coordinatorMenuContainsOperationsButNoClinicalPages() {
    var auth = auth("coordinator@example.com", RoleName.COORDINATOR.authority());

    assertThat(catalog.sidebarItems(auth)).extracting(AppMenuItem::route)
            .contains("/app/assignment-management")
            .doesNotContain("/app/clinical/onboarding", "/app/clinical/daily-check-ins",
                    "/app/clinical/trends", "/app/clinical/labs");
}
```

- [ ] **Step 2: Run focused web tests to verify they fail**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.AssignmentManagementWebControllerTest' --tests 'com.metabion.controller.web.AppMenuCatalogTest' --tests 'com.metabion.config.SecurityConfigTest'
```

Expected: FAIL because routes/controller/menu rules are absent.

- [ ] **Step 3: Implement the controller and security/menu split**

Add before the generic `/app/**` matcher:

```java
.requestMatchers("/app/assignment-management", "/app/assignment-management/**")
    .hasAnyRole("COORDINATOR", "ADMIN")
```

Split `AppMenuCatalog` into expert and coordinator items. Use
`RoleName::isClinicalExpert` for clinical pages, remove the planned
`menu.cohortManagement` entry from `clinicalItems()`, and add:

```java
if (roles.contains(RoleName.COORDINATOR)) {
    if (!roles.contains(RoleName.ADMIN)
            && roles.stream().noneMatch(RoleName::isClinicalExpert)) {
        items.add(contentManagement());
    }
    items.add(assignmentManagement());
}
if (roles.contains(RoleName.ADMIN) && items.stream()
        .noneMatch(item -> item.route().equals("/app/assignment-management"))) {
    items.add(assignmentManagement());
}

private AppMenuItem assignmentManagement() {
    return item("menu.assignmentManagement", "/app/assignment-management", false, true,
            "menu.assignmentManagement.description");
}
```

Inject only `AssignmentManagementService`, `AppMenuCatalog`,
`UserPreferenceService`, and `MessageSource`. Implement these exact route methods;
`renderCohorts` adds `cohortPage`, `cohortForm`, `patientSelection`,
`staffSelection`, and app-shell attributes, while `renderDirect` adds
`directPage`, `staffSelection`, and the same app-shell attributes:

```java
@GetMapping("/app/assignment-management")
public String cohorts(Authentication authentication, Model model) {
    return renderCohorts(authentication, null, model);
}

@GetMapping("/app/assignment-management/cohorts/{cohortId}")
public String cohort(@PathVariable Long cohortId,
                     Authentication authentication,
                     Model model) {
    return renderCohorts(authentication, cohortId, model);
}

@GetMapping("/app/assignment-management/direct")
public String direct(Authentication authentication, Model model) {
    return renderDirect(authentication, model);
}

@PostMapping("/app/assignment-management/cohorts")
public String createCohort(@Valid @ModelAttribute("cohortForm") CohortForm form,
                           BindingResult bindingResult,
                           Authentication authentication,
                           Model model,
                           RedirectAttributes redirect) {
    if (bindingResult.hasErrors()) return renderCohorts(authentication, null, model);
    var created = assignments.createCohort(authentication, form);
    success(redirect, "assignment.success.cohortCreated");
    return cohortRedirect(created.id());
}

@PostMapping("/app/assignment-management/cohorts/{cohortId}/edit")
public String editCohort(@PathVariable Long cohortId,
                         @Valid @ModelAttribute("cohortForm") CohortForm form,
                         BindingResult bindingResult,
                         Authentication authentication,
                         Model model,
                         RedirectAttributes redirect) {
    if (bindingResult.hasErrors()) return renderCohorts(authentication, cohortId, model);
    assignments.updateCohort(authentication, cohortId, form);
    success(redirect, "assignment.success.cohortUpdated");
    return cohortRedirect(cohortId);
}

@PostMapping("/app/assignment-management/cohorts/{cohortId}/archive")
public String archiveCohort(@PathVariable Long cohortId,
                            Authentication authentication,
                            RedirectAttributes redirect) {
    assignments.archiveCohort(authentication, cohortId);
    success(redirect, "assignment.success.cohortArchived");
    return "redirect:/app/assignment-management";
}

@PostMapping("/app/assignment-management/cohorts/{cohortId}/patients")
public String addPatient(@PathVariable Long cohortId,
                         @Valid @ModelAttribute("patientSelection") SelectionForm form,
                         Authentication authentication,
                         RedirectAttributes redirect) {
    assignments.addPatientToCohort(authentication, cohortId, form.targetId());
    success(redirect, "assignment.success.patientAdded");
    return cohortRedirect(cohortId);
}

@PostMapping("/app/assignment-management/cohorts/{cohortId}/memberships/{membershipId}/end")
public String endMembership(@PathVariable Long cohortId,
                            @PathVariable Long membershipId,
                            Authentication authentication,
                            RedirectAttributes redirect) {
    assignments.endMembership(authentication, cohortId, membershipId);
    success(redirect, "assignment.success.membershipEnded");
    return cohortRedirect(cohortId);
}

@PostMapping("/app/assignment-management/cohorts/{cohortId}/staff")
public String addCohortStaff(@PathVariable Long cohortId,
                             @Valid @ModelAttribute("staffSelection") SelectionForm form,
                             Authentication authentication,
                             RedirectAttributes redirect) {
    assignments.assignCohortStaff(authentication, cohortId, form.targetId());
    success(redirect, "assignment.success.staffAdded");
    return cohortRedirect(cohortId);
}

@PostMapping("/app/assignment-management/cohorts/{cohortId}/staff-assignments/{assignmentId}/end")
public String endCohortStaff(@PathVariable Long cohortId,
                             @PathVariable Long assignmentId,
                             Authentication authentication,
                             RedirectAttributes redirect) {
    assignments.endCohortStaffAssignment(authentication, cohortId, assignmentId);
    success(redirect, "assignment.success.staffEnded");
    return cohortRedirect(cohortId);
}

@PostMapping("/app/assignment-management/patients/{patientProfileId}/direct-assignments")
public String addDirectExpert(@PathVariable Long patientProfileId,
                              @Valid @ModelAttribute("staffSelection") SelectionForm form,
                              Authentication authentication,
                              RedirectAttributes redirect) {
    assignments.assignDirectExpert(authentication, patientProfileId, form.targetId());
    success(redirect, "assignment.success.directAdded");
    return directRedirect();
}

@PostMapping("/app/assignment-management/patients/{patientProfileId}/direct-assignments/{assignmentId}/end")
public String endDirectExpert(@PathVariable Long patientProfileId,
                              @PathVariable Long assignmentId,
                              Authentication authentication,
                              RedirectAttributes redirect) {
    assignments.endDirectExpertAssignment(authentication, patientProfileId, assignmentId);
    success(redirect, "assignment.success.directEnded");
    return directRedirect();
}
```

Use these helpers and localized flash values:

```java
private String renderCohorts(Authentication authentication, Long cohortId, Model model) {
    model.addAttribute("cohortPage", assignments.cohortPage(authentication, cohortId));
    model.addAttribute("cohortForm", new CohortForm("", ""));
    model.addAttribute("patientSelection", new SelectionForm(null));
    model.addAttribute("staffSelection", new SelectionForm(null));
    model.addAttribute("isAdmin", hasAuthority(authentication, "ROLE_ADMIN"));
    addAppShell(model, authentication, "/app/assignment-management");
    return "assignment-management";
}

private String renderDirect(Authentication authentication, Model model) {
    model.addAttribute("directPage", assignments.directPage(authentication));
    model.addAttribute("staffSelection", new SelectionForm(null));
    model.addAttribute("isAdmin", hasAuthority(authentication, "ROLE_ADMIN"));
    addAppShell(model, authentication, "/app/assignment-management");
    return "assignment-management";
}

private void success(RedirectAttributes redirect, String key) {
    redirect.addFlashAttribute("success", messages.getMessage(
            key, null, LocaleContextHolder.getLocale()));
}

private boolean hasAuthority(Authentication authentication, String authority) {
    return authentication.getAuthorities().stream()
            .anyMatch(granted -> granted.getAuthority().equals(authority));
}

private String cohortRedirect(Long cohortId) {
    return "redirect:/app/assignment-management/cohorts/" + cohortId;
}

private String directRedirect() {
    return "redirect:/app/assignment-management/direct";
}
```

The service's `cohortPage(authentication, null)` returns the scoped cohort list
with either the first active cohort selected or `selected == null` when none
exist. For an explicit out-of-scope ID it returns `404`. Every successful POST
delegates exactly once to `AssignmentManagementService`; invalid cohort forms
delegate only to the page assembler. The controller must not inject repositories.
Add app-shell model attributes using the same pattern as
`StaffInvitationWebController`.

Update `WebExceptionHandler.conflict(Model, HttpServletRequest)` so request paths beginning `/app/assignment-management` use `assignment.error.conflict`, link back to `/app/assignment-management`, and do not reuse laboratory copy.

- [ ] **Step 4: Run focused web tests to verify they pass**

Run the command from Step 2.

Expected: PASS, including CSRF denial and role-specific menus.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/controller/web/AssignmentManagementWebController.java src/main/java/com/metabion/config/SecurityConfig.java src/main/java/com/metabion/controller/web/AppMenuCatalog.java src/main/java/com/metabion/controller/web/WebExceptionHandler.java src/test/java/com/metabion/controller/web/AssignmentManagementWebControllerTest.java src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java src/test/java/com/metabion/config/SecurityConfigTest.java
git commit -m "Add assignment management routes"
```

---

### Task 8: Build the Localized Cohort-Centric Workspace

**Files:**
- Create: `src/main/resources/templates/assignment-management.html`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/test/java/com/metabion/controller/web/AssignmentManagementWebControllerTest.java`

**Interfaces:**
- Consumes: `CohortPage`, `DirectPage`, forms, and controller model attributes.
- Produces: responsive cohort/direct views with source labels and named confirmations.

- [ ] **Step 1: Write failing rendered-content and localization tests**

Assert the page includes cohort/patient/care-team sections, direct and inherited labels, real CSRF fields, role-appropriate controls, named confirmation text, and Czech copy when `METABION_LOCALE=cs`.

```java
mvc.perform(get("/app/assignment-management/direct")
        .with(user("admin@example.com").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Direct assignments")))
        .andExpect(content().string(containsString("Access through cohort")))
        .andExpect(content().string(containsString("name=\"_csrf\"")));
```

- [ ] **Step 2: Run the controller test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.controller.web.AssignmentManagementWebControllerTest'`

Expected: FAIL because the template and message keys do not exist.

- [ ] **Step 3: Implement template, messages, and responsive styles**

Use the app shell and this page structure:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: appShell(#{assignment.pageTitle}, '/app/assignment-management', ~{::content})}">
<th:block th:fragment="content">
    <header class="app-header">
        <div><p class="eyebrow">Metabion</p><h1 th:text="#{assignment.title}">Assignment management</h1></div>
    </header>
    <nav class="assignment-tabs" aria-label="Assignment views">
        <a th:href="@{/app/assignment-management}" th:text="#{assignment.cohorts}">Cohorts</a>
        <a th:href="@{/app/assignment-management/direct}" th:text="#{assignment.direct}">Direct assignments</a>
    </nav>
    <p class="success" th:if="${success}" th:text="${success}">Saved</p>
    <section th:if="${cohortPage != null}" class="assignment-workspace">
        <aside class="panel assignment-cohorts">
            <a th:each="cohort : ${cohortPage.cohorts()}"
               th:href="@{/app/assignment-management/cohorts/{id}(id=${cohort.id()})}"
               th:text="${cohort.name()}">Cohort</a>
        </aside>
        <div class="assignment-detail">
            <section class="panel app-panel" th:if="${cohortPage.selected() != null}">
                <h2 th:text="${cohortPage.selected().name()}">Selected cohort</h2>
                <span class="status-badge"
                      th:text="${cohortPage.selected().archived()} ? #{assignment.archived} : #{assignment.active}">
                    Active
                </span>
                <div class="assignment-columns">
                    <div><h3 th:text="#{assignment.patients}">Patients</h3></div>
                    <div><h3 th:text="#{assignment.careTeam}">Care team</h3></div>
                </div>
            </section>
        </div>
    </section>
    <section th:if="${directPage != null}" class="panel app-panel">
        <article th:each="patient : ${directPage.patients()}" class="assignment-patient">
            <h2 th:text="${patient.email()}">patient@example.com</h2>
            <h3 th:text="#{assignment.direct}">Direct assignments</h3>
            <div th:each="access : ${patient.direct()}" th:text="${access.email()}">expert@example.com</div>
            <h3 th:text="#{assignment.inherited}">Access through cohort</h3>
            <a th:each="access : ${patient.inherited()}"
               th:href="@{/app/assignment-management/cohorts/{id}(id=${access.cohortId()})}"
               th:text="${access.email() + ' · ' + access.cohortName()}">Expert · Cohort</a>
        </article>
    </section>
</th:block>
</html>
```

Add these concrete forms inside the cohort section. Keep the create form in the
cohort sidebar and the remaining forms below the selected cohort heading:

```html
<form th:action="@{/app/assignment-management/cohorts}" th:object="${cohortForm}" method="post">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
    <label th:text="#{assignment.cohortName}" for="new-cohort-name">Cohort name</label>
    <input id="new-cohort-name" th:field="*{name}" required maxlength="255">
    <label th:text="#{assignment.description}" for="new-cohort-description">Description</label>
    <textarea id="new-cohort-description" th:field="*{description}" maxlength="4000"></textarea>
    <button type="submit" th:text="#{assignment.create}">Create cohort</button>
</form>

<form th:if="${cohortPage.selected() != null and !cohortPage.selected().archived()}"
      th:action="@{/app/assignment-management/cohorts/{id}/edit(id=${cohortPage.selected().id()})}"
      method="post">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
    <label th:text="#{assignment.cohortName}" for="edit-cohort-name">Cohort name</label>
    <input id="edit-cohort-name" name="name" th:value="${cohortPage.selected().name()}" required maxlength="255">
    <label th:text="#{assignment.description}" for="edit-cohort-description">Description</label>
    <textarea id="edit-cohort-description" name="description" maxlength="4000"
              th:text="${cohortPage.selected().description()}"></textarea>
    <button type="submit" th:text="#{assignment.save}">Save changes</button>
</form>

<form th:if="${isAdmin and cohortPage.selected() != null and !cohortPage.selected().archived()}"
      th:action="@{/app/assignment-management/cohorts/{id}/archive(id=${cohortPage.selected().id()})}"
      method="post" onsubmit="return confirm(this.dataset.confirm)"
      th:attr="data-confirm=#{assignment.confirm.archive(${cohortPage.selected().name()})}">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
    <button type="submit" class="button-danger" th:text="#{assignment.archive}">Archive cohort</button>
</form>

<form th:if="${cohortPage.selected() != null and !cohortPage.selected().archived()}"
      th:action="@{/app/assignment-management/cohorts/{id}/patients(id=${cohortPage.selected().id()})}"
      method="post">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
    <label th:text="#{assignment.addPatient}" for="patient-target">Add patient</label>
    <select id="patient-target" name="targetId" required>
        <option value="" th:text="#{assignment.choosePatient}">Choose patient</option>
        <option th:each="candidate : ${cohortPage.patientCandidates()}"
                th:value="${candidate.id()}" th:text="${candidate.email()}">patient@example.com</option>
    </select>
    <button type="submit" th:text="#{assignment.add}">Add</button>
</form>

<article th:if="${!cohortPage.selected().archived()}"
         th:each="patient : ${cohortPage.patients()}" class="assignment-patient">
    <span th:text="${patient.email()}">patient@example.com</span>
    <div>
        <strong th:text="#{assignment.direct}">Direct assignments</strong>
        <span th:each="access : ${patient.direct()}" th:text="${access.email()}">expert@example.com</span>
    </div>
    <div>
        <strong th:text="#{assignment.inherited}">Access through cohort</strong>
        <span th:each="access : ${patient.inherited()}"
              th:text="${access.email() + ' · ' + access.cohortName()}">expert@example.com · Cohort</span>
    </div>
    <a th:href="@{/app/assignment-management/direct}"
       th:text="#{assignment.manageCareTeam}">Manage care team</a>
    <form th:action="@{/app/assignment-management/cohorts/{cohortId}/memberships/{membershipId}/end(
              cohortId=${cohortPage.selected().id()},membershipId=${patient.membershipId()})}"
          method="post" onsubmit="return confirm(this.dataset.confirm)"
          th:attr="data-confirm=#{assignment.confirm.endMembership(${patient.email()},${cohortPage.selected().name()})}">
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
        <button type="submit" th:text="#{assignment.endMembership}">End membership</button>
    </form>
</article>

<form th:if="${cohortPage.selected() != null and !cohortPage.selected().archived()}"
      th:action="@{/app/assignment-management/cohorts/{id}/staff(id=${cohortPage.selected().id()})}"
      method="post">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
    <label th:text="#{assignment.addStaff}" for="cohort-staff-target">Add staff member</label>
    <select id="cohort-staff-target" name="targetId" required>
        <option value="" th:text="#{assignment.chooseStaff}">Choose staff member</option>
        <option th:each="candidate : ${cohortPage.staffCandidates()}"
                th:value="${candidate.staffProfileId()}" th:text="${candidate.email()}">expert@example.com</option>
    </select>
    <button type="submit" th:text="#{assignment.add}">Add</button>
</form>

<article th:if="${!cohortPage.selected().archived()}"
         th:each="access : ${cohortPage.careTeam()}" class="assignment-staff">
    <span th:text="${access.email()}">expert@example.com</span>
    <form th:if="${isAdmin or !access.roles().contains('COORDINATOR')}"
          th:action="@{/app/assignment-management/cohorts/{cohortId}/staff-assignments/{assignmentId}/end(
              cohortId=${cohortPage.selected().id()},assignmentId=${access.assignmentId()})}"
          method="post" onsubmit="return confirm(this.dataset.confirm)"
          th:attr="data-confirm=#{assignment.confirm.endStaff(${access.email()},${cohortPage.selected().name()})}">
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
        <button type="submit" th:text="#{assignment.endAssignment}">End assignment</button>
    </form>
</article>
```

Add these controls inside each `directPage.patients()` article, after rendering
the direct and inherited access lists. The inherited rows intentionally have no
end button; they are managed from their cohort:

```html
<form th:action="@{/app/assignment-management/patients/{patientId}/direct-assignments(
          patientId=${patient.patientProfileId()})}" method="post">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
    <label th:text="#{assignment.addDirectExpert}" th:for="${'direct-target-' + patient.patientProfileId()}">
        Add direct expert
    </label>
    <select th:id="${'direct-target-' + patient.patientProfileId()}" name="targetId" required>
        <option value="" th:text="#{assignment.chooseExpert}">Choose expert</option>
        <option th:each="candidate : ${directPage.staffCandidates()}"
                th:value="${candidate.staffProfileId()}" th:text="${candidate.email()}">expert@example.com</option>
    </select>
    <button type="submit" th:text="#{assignment.add}">Add</button>
</form>

<form th:each="access : ${patient.direct()}"
      th:action="@{/app/assignment-management/patients/{patientId}/direct-assignments/{assignmentId}/end(
          patientId=${patient.patientProfileId()},assignmentId=${access.assignmentId()})}"
      method="post" onsubmit="return confirm(this.dataset.confirm)"
      th:attr="data-confirm=#{assignment.confirm.endDirect(${access.email()},${patient.email()})}">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
    <button type="submit" th:text="#{assignment.endAssignment}">End assignment</button>
</form>
```

Add this exact English key set to `messages.properties`:

```properties
menu.assignmentManagement=Assignment management
menu.assignmentManagement.description=Manage cohorts and patient care assignments
assignment.pageTitle=Assignment management | Metabion
assignment.title=Assignment management
assignment.cohorts=Cohorts
assignment.patients=Patients
assignment.careTeam=Care team
assignment.direct=Direct assignments
assignment.inherited=Access through cohort
assignment.active=Active
assignment.archived=Archived
assignment.cohortName=Cohort name
assignment.description=Description
assignment.create=Create cohort
assignment.save=Save changes
assignment.archive=Archive cohort
assignment.add=Add
assignment.addPatient=Add patient
assignment.addStaff=Add staff member
assignment.addDirectExpert=Add direct expert
assignment.manageCareTeam=Manage care team
assignment.choosePatient=Choose patient
assignment.chooseStaff=Choose staff member
assignment.chooseExpert=Choose physician or nutrition specialist
assignment.endMembership=End membership
assignment.endAssignment=End assignment
assignment.success.cohortCreated=Cohort created.
assignment.success.cohortUpdated=Cohort updated.
assignment.success.cohortArchived=Cohort archived.
assignment.success.patientAdded=Patient added to cohort.
assignment.success.membershipEnded=Patient membership ended.
assignment.success.staffAdded=Staff member assigned to cohort.
assignment.success.staffEnded=Cohort staff assignment ended.
assignment.success.directAdded=Direct expert assignment created.
assignment.success.directEnded=Direct expert assignment ended.
assignment.validation.review=Review the highlighted assignment details.
assignment.error.conflict=This assignment changed or is already active. Refresh the page and try again.
assignment.confirm.archive=Archive cohort “{0}” and end all of its active memberships and staff assignments?
assignment.confirm.endMembership=End {0}’s membership in cohort “{1}”?
assignment.confirm.endStaff=End {0}’s assignment to cohort “{1}”?
assignment.confirm.endDirect=End {0}’s direct assignment to patient {1}?
```

Add the same keys in the same order to `messages_cs.properties`:

```properties
menu.assignmentManagement=Správa přiřazení
menu.assignmentManagement.description=Správa kohort a přiřazení péče o pacienty
assignment.pageTitle=Správa přiřazení | Metabion
assignment.title=Správa přiřazení
assignment.cohorts=Kohorty
assignment.patients=Pacienti
assignment.careTeam=Pečující tým
assignment.direct=Přímá přiřazení
assignment.inherited=Přístup přes kohortu
assignment.active=Aktivní
assignment.archived=Archivováno
assignment.cohortName=Název kohorty
assignment.description=Popis
assignment.create=Vytvořit kohortu
assignment.save=Uložit změny
assignment.archive=Archivovat kohortu
assignment.add=Přidat
assignment.addPatient=Přidat pacienta
assignment.addStaff=Přidat člena personálu
assignment.addDirectExpert=Přidat přímého odborníka
assignment.manageCareTeam=Spravovat pečující tým
assignment.choosePatient=Vyberte pacienta
assignment.chooseStaff=Vyberte člena personálu
assignment.chooseExpert=Vyberte lékaře nebo nutričního specialistu
assignment.endMembership=Ukončit členství
assignment.endAssignment=Ukončit přiřazení
assignment.success.cohortCreated=Kohorta byla vytvořena.
assignment.success.cohortUpdated=Kohorta byla aktualizována.
assignment.success.cohortArchived=Kohorta byla archivována.
assignment.success.patientAdded=Pacient byl přidán do kohorty.
assignment.success.membershipEnded=Členství pacienta bylo ukončeno.
assignment.success.staffAdded=Člen personálu byl přiřazen ke kohortě.
assignment.success.staffEnded=Přiřazení člena personálu ke kohortě bylo ukončeno.
assignment.success.directAdded=Přímé přiřazení odborníka bylo vytvořeno.
assignment.success.directEnded=Přímé přiřazení odborníka bylo ukončeno.
assignment.validation.review=Zkontrolujte zvýrazněné údaje přiřazení.
assignment.error.conflict=Toto přiřazení se změnilo nebo je již aktivní. Obnovte stránku a zkuste to znovu.
assignment.confirm.archive=Archivovat kohortu „{0}“ a ukončit všechna její aktivní členství a přiřazení personálu?
assignment.confirm.endMembership=Ukončit členství pacienta {0} v kohortě „{1}“?
assignment.confirm.endStaff=Ukončit přiřazení uživatele {0} ke kohortě „{1}“?
assignment.confirm.endDirect=Ukončit přímé přiřazení odborníka {0} k pacientovi {1}?
```

Append these styles, using the existing CSS variables already defined in
`app.css`:

```css
.assignment-tabs {
    display: flex;
    gap: 0.75rem;
    margin-bottom: 1rem;
}

.assignment-workspace {
    display: grid;
    grid-template-columns: minmax(15rem, 0.8fr) minmax(0, 2.2fr);
    gap: 1rem;
    align-items: start;
}

.assignment-cohorts,
.assignment-detail,
.assignment-patient,
.assignment-staff {
    display: grid;
    gap: 0.75rem;
}

.assignment-cohorts a {
    padding: 0.75rem;
    border-radius: 0.5rem;
    color: var(--text);
    background: var(--panel);
}

.assignment-columns {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 1rem;
}

.assignment-patient,
.assignment-staff {
    padding: 0.875rem 0;
    border-bottom: 1px solid var(--border);
}

@media (max-width: 800px) {
    .assignment-workspace,
    .assignment-columns {
        grid-template-columns: 1fr;
    }
}
```

- [ ] **Step 4: Run controller and menu tests to verify they pass**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.AssignmentManagementWebControllerTest' --tests 'com.metabion.controller.web.AppMenuCatalogTest'
```

Expected: PASS in English and Czech.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/assignment-management.html src/main/resources/messages.properties src/main/resources/messages_cs.properties src/main/resources/static/css/app.css src/test/java/com/metabion/controller/web/AssignmentManagementWebControllerTest.java
git commit -m "Build assignment management workspace"
```

---

### Task 9: Add PostgreSQL End-to-End Coverage and Run Full Verification

**Files:**
- Create: `src/test/java/com/metabion/integration/AssignmentManagementIT.java`
- Modify: any production/test file only when a failing integration assertion exposes a defect in the approved behavior.

**Interfaces:**
- Consumes: complete assignment-management and clinical-access feature.
- Produces: cross-layer PostgreSQL proof for coordinator, expert, direct, cohort, and archive behavior.

- [ ] **Step 1: Write failing end-to-end integration tests**

Extend `AbstractAuthIT`, inject the service/repositories, and create enabled users with explicit roles/profiles. Cover these flows:

```java
@Test
void coordinatorEnrollsPatientAndPhysicianGetsClinicalAccessButCoordinatorDoesNot() {
    var coordinatorAuth = auth("coordinator@example.com");
    var cohort = assignmentManagement.createCohort(
            coordinatorAuth, new CohortForm("Pilot", "Secure pilot"));
    assignmentManagement.addPatientToCohort(coordinatorAuth, cohort.id(), patient.getId());
    assignmentManagement.assignCohortStaff(coordinatorAuth, cohort.id(), physician.getId());

    assertThat(accessControl.canViewPatientClinicalData(coordinatorAuth, patient.getId())).isFalse();
    assertThat(accessControl.canViewPatientClinicalData(auth("physician@example.com"), patient.getId())).isTrue();
}

@Test
void archiveEndsInheritedAccessAndPreservesDirectAccess() {
    assignmentManagement.assignDirectExpert(adminAuth, patient.getId(), directPhysician.getId());
    assignmentManagement.archiveCohort(adminAuth, cohort.getId());

    assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(
            patient.getId(), cohortPhysician.getId())).isFalse();
    assertThat(patientExpertAssignments.existsActiveAssignment(
            patient.getId(), directPhysician.getId())).isTrue();
    assertThat(memberships.findHistoryByCohortId(cohort.getId()).getFirst().getEndedBy())
            .isEqualTo(admin);
}
```

Also cover multiple active cohorts, direct-plus-inherited access for the same expert, coordinator-plus-physician access only when assigned, and database rejection of duplicate active rows.

Inject `PatientProfileRepository` and `StaffProfileRepository` in addition to the
service/access/assignment repositories. Define these helpers in the integration
test instead of using `AbstractAuthIT.createEnabledUser`, because that inherited
helper always creates a patient:

```java
private Authentication auth(String email) {
    return UsernamePasswordAuthenticationToken.authenticated(
            email, "n/a", AuthorityUtils.NO_AUTHORITIES);
}

private User enabledUser(String email, RoleName... roles) {
    var user = new User(email, passwordEncoder.encode("Integration1!"));
    user.setEnabled(true);
    Arrays.stream(roles).forEach(user::addRole);
    return users.saveAndFlush(user);
}

private PatientProfile patient(String email) {
    return patientProfiles.saveAndFlush(
            new PatientProfile(enabledUser(email, RoleName.PATIENT)));
}

private StaffProfile staff(String email, RoleName... roles) {
    return staffProfiles.saveAndFlush(new StaffProfile(enabledUser(email, roles)));
}
```

Use a distinct email per fixture and keep all fixture users enabled so candidate
and access assertions exercise the intended rule rather than disabled-user
filtering.

- [ ] **Step 2: Run the integration test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.integration.AssignmentManagementIT'`

Expected: FAIL on any missing cross-layer wiring or lifecycle rule; if all earlier tasks are correct, the new test may pass immediately, which is acceptable because it adds a new integration safety net.

- [ ] **Step 3: Fix only integration defects and audit coordinator references**

Run:

```bash
rg -n "RoleName\.COORDINATOR|canAccessPatientProfile" src/main/java/com/metabion
```

Expected remaining `COORDINATOR` references: staff provisioning, education permissions, operational menu/assignment management, and `isClinicalStaff`. Expected `canAccessPatientProfile` references: none. Remove any coordinator reference still authorizing onboarding details, diet logs/photos, symptoms, labs, trends, or the clinical directory.

If integration exposed a defect, add a focused regression assertion before the smallest production correction; do not broaden scope.

- [ ] **Step 4: Run focused assignment and security suites**

Run:

```bash
./gradlew test --tests 'com.metabion.repository.RbacAssignmentRepositoryTest' --tests 'com.metabion.service.AccessControlServiceTest' --tests 'com.metabion.service.AssignmentManagementServiceTest' --tests 'com.metabion.controller.web.AssignmentManagementWebControllerTest' --tests 'com.metabion.controller.web.AppMenuCatalogTest' --tests 'com.metabion.config.SecurityConfigTest' --tests 'com.metabion.integration.AssignmentManagementIT'
```

Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL and Jacoco report generation completes.

- [ ] **Step 6: Review the final diff for security and scope**

Run:

```bash
git diff --check
git status --short
git diff --stat
```

Expected: no whitespace errors; only assignment-management, clinical-access, localization, tests, and V20 migration files are changed. Preserve unrelated `.idea`, `.superpowers`, `var`, and existing plan changes.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/com/metabion/integration/AssignmentManagementIT.java
git commit -m "Verify assignment management workflows"
```

If Step 3 required a regression fix, stage only the exact production file and
focused regression-test file shown by `git diff --name-only`; never stage an
entire source directory because unrelated user changes may be present.

## Final Review Checklist

- [ ] Every approved design acceptance criterion maps to Tasks 1-9.
- [ ] Every cohort has a non-null creator.
- [ ] Relationship endings and archival retain actor/timestamp history.
- [ ] Coordinator-only users cannot access any detailed clinical workflow.
- [ ] Dual-role coordinators need an expert role plus an applicable assignment.
- [ ] Coordinator and admin assignment scopes differ exactly as designed.
- [ ] Direct and inherited access remain independent in persistence and UI.
- [ ] Archived cohorts grant no inherited access and accept no mutation.
- [ ] All write forms carry CSRF tokens.
- [ ] English and Czech message keys remain aligned.
- [ ] No new dependency or REST assignment API was introduced.
- [ ] `./gradlew test` passes.
