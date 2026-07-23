# Assignment Management REST API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the full assignment-management operation surface (cohorts, memberships, staff assignments, direct expert assignments, candidates) as a session-authenticated JSON REST API reusing `AssignmentManagementService`.

**Architecture:** A thin `AssignmentManagementApiController` under `/api/cohorts` and `/api/patients` delegates every call to the existing `AssignmentManagementService`, which gains lean read methods and identifier-returning writes. `SecurityConfig` gains a role rule plus JSON 401/403 handlers for `/api/**`; a `GET /api/csrf` endpoint bootstraps CSRF for API clients.

**Tech Stack:** Java 25, Spring Boot 4.0.6 (Web MVC, Security, Data JPA), JUnit 5 + Mockito, MockMvc with `springSecurity()`, Testcontainers PostgreSQL for ITs, Gradle wrapper.

**Spec:** `docs/superpowers/specs/2026-07-23-assignment-management-rest-api-design.md` — read it before starting.

## Global Constraints

- Use the Gradle wrapper for all verification: `./gradlew test --tests '...'`.
- No new dependencies. No Flyway migration; the schema is unchanged.
- Authorization stays inside `AssignmentManagementService`; controllers contain no authorization or repository logic.
- Service errors are `ResponseStatusException` only; `GlobalExceptionHandler` maps them (`forbidden`, `not_found`, `unauthorized`, `conflict`, `request_failed`).
- 4-space indentation, constructor injection, Java records for DTOs, packages under `com.metabion`.
- CSRF remains enabled for every non-GET endpoint added here (no new CSRF exemptions).
- The web controller (`AssignmentManagementWebController`) must keep compiling and behaving unchanged; it ignores the new service return values.
- Reuse existing test helpers in `AssignmentManagementServiceTest` (`auth`, `user`, `enabledUser`, `staffProfile`, `patientProfile`, `membership`, `cohortAssignment`, `directAssignment`, `cohort`, `assertStatus`) — do not duplicate them.
- Commit after every task with a focused, imperative message.

---

### Task 1: API DTO records and identifier-returning service writes

**Files:**
- Create: `src/main/java/com/metabion/dto/assignment/AssignmentManagementApi.java`
- Modify: `src/main/java/com/metabion/service/AssignmentManagementService.java` (methods `addPatientToCohort`:142-165, `assignCohortStaff`:185-213, `assignDirectExpert`:237-261, `updateCohort`:104-115)
- Test: `src/test/java/com/metabion/service/AssignmentManagementServiceTest.java`

**Interfaces:**
- Consumes: existing entities and repositories; existing helpers in the test class.
- Produces (used by Tasks 2 and 5):
  - `AssignmentManagementApi.AddPatientRequest(@NotNull Long patientProfileId)`
  - `AssignmentManagementApi.AssignStaffRequest(@NotNull Long staffProfileId)`
  - `AssignmentManagementApi.MembershipResponse(Long membershipId)`
  - `AssignmentManagementApi.AssignmentResponse(Long assignmentId)`
  - `MembershipResponse addPatientToCohort(Authentication, Long cohortId, Long patientProfileId)`
  - `AssignmentResponse assignCohortStaff(Authentication, Long cohortId, Long staffProfileId)`
  - `AssignmentResponse assignDirectExpert(Authentication, Long patientProfileId, Long staffProfileId)`
  - `CohortItem updateCohort(Authentication, Long cohortId, CohortForm)` (was `void`)

- [ ] **Step 1: Write the failing tests**

Append these tests to `AssignmentManagementServiceTest` (imports already present in the file cover every type used):

```java
@Test
void addPatientToCohortReturnsCreatedMembershipId() {
    var admin = enabledUser(1L, "admin@example.com", RoleName.ADMIN);
    var cohort = cohort(10L, "Pilot", admin);
    var patient = patientProfile(20L, enabledUser(2L, "patient@example.com", RoleName.PATIENT));
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
    when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
    when(patientProfiles.lockById(20L)).thenReturn(Optional.of(patient));
    when(memberships.existsActiveMembership(20L, 10L)).thenReturn(false);
    when(memberships.save(any(PatientCohortMembership.class))).thenAnswer(invocation -> {
        PatientCohortMembership saved = invocation.getArgument(0);
        saved.setId(55L);
        return saved;
    });

    var response = service.addPatientToCohort(auth("admin@example.com"), 10L, 20L);

    assertThat(response.membershipId()).isEqualTo(55L);
}

@Test
void assignCohortStaffReturnsCreatedAssignmentId() {
    var admin = enabledUser(1L, "admin@example.com", RoleName.ADMIN);
    var cohort = cohort(10L, "Pilot", admin);
    var physician = staffProfile(30L, enabledUser(3L, "physician@example.com", RoleName.PHYSICIAN));
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
    when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));
    when(staffProfiles.lockById(30L)).thenReturn(Optional.of(physician));
    when(accessControl.canManageCohortStaff(any(), eq(10L), eq(30L))).thenReturn(true);
    when(cohortStaffAssignments.existsActiveAssignment(10L, 30L)).thenReturn(false);
    when(cohortStaffAssignments.save(any(CohortStaffAssignment.class))).thenAnswer(invocation -> {
        CohortStaffAssignment saved = invocation.getArgument(0);
        saved.setId(56L);
        return saved;
    });

    var response = service.assignCohortStaff(auth("admin@example.com"), 10L, 30L);

    assertThat(response.assignmentId()).isEqualTo(56L);
}

@Test
void assignDirectExpertReturnsCreatedAssignmentId() {
    var admin = enabledUser(1L, "admin@example.com", RoleName.ADMIN);
    var patient = patientProfile(20L, enabledUser(2L, "patient@example.com", RoleName.PATIENT));
    var nutritionist = staffProfile(31L,
            enabledUser(4L, "nutrition@example.com", RoleName.NUTRITION_SPECIALIST));
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
    when(patientProfiles.lockById(20L)).thenReturn(Optional.of(patient));
    when(accessControl.canManageDirectExpertAssignments(any(), eq(20L))).thenReturn(true);
    when(staffProfiles.lockById(31L)).thenReturn(Optional.of(nutritionist));
    when(directAssignments.existsActiveAssignment(20L, 31L)).thenReturn(false);
    when(directAssignments.save(any(PatientExpertAssignment.class))).thenAnswer(invocation -> {
        PatientExpertAssignment saved = invocation.getArgument(0);
        saved.setId(57L);
        return saved;
    });

    var response = service.assignDirectExpert(auth("admin@example.com"), 20L, 31L);

    assertThat(response.assignmentId()).isEqualTo(57L);
}

@Test
void updateCohortReturnsUpdatedCohortItem() {
    var admin = enabledUser(1L, "admin@example.com", RoleName.ADMIN);
    var cohort = cohort(10L, "Old name", admin);
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
    when(cohorts.lockById(10L)).thenReturn(Optional.of(cohort));

    var updated = service.updateCohort(
            auth("admin@example.com"), 10L, new CohortForm("New name", "Notes"));

    assertThat(updated.id()).isEqualTo(10L);
    assertThat(updated.name()).isEqualTo("New name");
    assertThat(updated.description()).isEqualTo("Notes");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.metabion.service.AssignmentManagementServiceTest'`
Expected: compilation failure — `void` methods cannot be assigned to `var`, and `MembershipResponse`/`AssignmentResponse` do not exist.

- [ ] **Step 3: Create the API DTO records**

Create `src/main/java/com/metabion/dto/assignment/AssignmentManagementApi.java`:

```java
package com.metabion.dto.assignment;

import jakarta.validation.constraints.NotNull;

public final class AssignmentManagementApi {
    private AssignmentManagementApi() {}

    public record AddPatientRequest(@NotNull Long patientProfileId) {}

    public record AssignStaffRequest(@NotNull Long staffProfileId) {}

    public record MembershipResponse(Long membershipId) {}

    public record AssignmentResponse(Long assignmentId) {}
}
```

- [ ] **Step 4: Change the four service methods to return identifiers**

In `AssignmentManagementService.java`, add the import:

```java
import com.metabion.dto.assignment.AssignmentManagementApi.AssignmentResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.MembershipResponse;
```

Change `updateCohort` to return the fresh item (last two lines of the method):

```java
    @Transactional
    public CohortItem updateCohort(Authentication authentication, Long cohortId, CohortForm form) {
        var actor = requireAssignmentManager(authentication);
        var cohort = lockedCohort(cohortId);
        if (!actor.hasRole(RoleName.ADMIN)
                && !accessControl.canManageCohort(authentication, cohortId)) {
            throw notFound("Cohort not found");
        }
        requireActiveCohort(cohort, "Archived cohort cannot be edited");
        requireForm(form);
        cohort.edit(normalizeName(form.name()), normalizeDescription(form.description()));
        return cohortItem(cohort);
    }
```

Change the signature of `addPatientToCohort` to return `MembershipResponse` and replace its `try` block:

```java
        try {
            var membership = memberships.save(new PatientCohortMembership(patient, cohort, actor));
            memberships.flush();
            return new MembershipResponse(membership.getId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Patient is already assigned to cohort");
        }
```

Change the signature of `assignCohortStaff` to return `AssignmentResponse` and replace its `try` block:

```java
        try {
            var assignment = cohortStaffAssignments.save(new CohortStaffAssignment(cohort, target, actor));
            cohortStaffAssignments.flush();
            return new AssignmentResponse(assignment.getId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Staff member is already assigned to cohort");
        }
```

Change the signature of `assignDirectExpert` to return `AssignmentResponse` and replace its `try` block:

```java
        try {
            var assignment = directAssignments.save(new PatientExpertAssignment(patient, target, actor));
            directAssignments.flush();
            return new AssignmentResponse(assignment.getId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Expert is already directly assigned to patient");
        }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.metabion.service.AssignmentManagementServiceTest'`
Expected: PASS (all existing plus 4 new tests).

- [ ] **Step 6: Verify the web layer still compiles and passes**

Run: `./gradlew test --tests 'com.metabion.controller.web.AssignmentManagementWebControllerTest'`
Expected: PASS — the web controller ignores the new return values.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/metabion/dto/assignment/AssignmentManagementApi.java \
        src/main/java/com/metabion/service/AssignmentManagementService.java \
        src/test/java/com/metabion/service/AssignmentManagementServiceTest.java
git commit -m "Return created identifiers from assignment writes"
```

---

### Task 2: Lean read methods on AssignmentManagementService

**Files:**
- Modify: `src/main/java/com/metabion/dto/assignment/AssignmentManagementApi.java` (add records)
- Modify: `src/main/java/com/metabion/service/AssignmentManagementService.java` (add read methods; overload `patientPage`:419-425)
- Test: `src/test/java/com/metabion/service/AssignmentManagementServiceTest.java`

**Interfaces:**
- Consumes: Task 1's `AssignmentManagementApi` file; existing view records `CohortItem`, `PatientRow`, `ExpertAccess`, `StaffOption` from `AssignmentManagementView`; `com.metabion.dto.PatientOptionResponse(Long id, String email)`.
- Produces (used by Task 5):
  - `AssignmentManagementApi.CohortDetailResponse(CohortItem cohort, List<PatientRow> patients, List<ExpertAccess> careTeam)`
  - `AssignmentManagementApi.PatientAssignmentRow(Long patientProfileId, String email, List<CohortItem> cohorts, List<ExpertAccess> direct, List<ExpertAccess> inherited)`
  - `AssignmentManagementApi.PatientsPageResponse(List<PatientAssignmentRow> patients, List<StaffOption> staffCandidates, int pageIndex, int size, int totalPages, long totalPatients)`
  - `CohortDetailResponse cohortDetail(Authentication, Long cohortId)`
  - `PatientsPageResponse scopedPatients(Authentication, int page, int size)` — clamps `page < 0` to 0, oversized page to last page, `size < 1` to 50, `size > 200` to 200
  - `List<PatientOptionResponse> patientCandidates(Authentication, Long cohortId)` — 404 for invisible cohort, 409 for archived
  - `List<StaffOption> staffCandidates(Authentication, Long cohortId)` — same validation, role-filtered, excludes assigned staff

- [ ] **Step 1: Write the failing tests**

Append to `AssignmentManagementServiceTest`. First add these imports at the top of the file (`PageImpl`, `PageRequest`, and `CohortForm` are already imported; these three are not):

```java
import com.metabion.dto.PatientOptionResponse;
import com.metabion.dto.assignment.AssignmentManagementView.StaffOption;
import org.springframework.data.domain.Page;
```

```java
@Test
void cohortDetailReturnsActiveCohortForAssignedCoordinator() {
    var coordinator = enabledUser(1L, "coordinator@example.com", RoleName.COORDINATOR);
    var coordinatorProfile = staffProfile(11L, coordinator);
    var cohort = cohort(10L, "Pilot", coordinator);
    var patient = patientProfile(20L, enabledUser(2L, "patient@example.com", RoleName.PATIENT));
    var membership = membership(40L, patient, cohort, coordinator);
    var physician = staffProfile(30L, enabledUser(3L, "physician@example.com", RoleName.PHYSICIAN));
    var staffAssignment = cohortAssignment(50L, cohort, physician, coordinator);
    when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
    when(staffProfiles.findByUserId(coordinator.getId())).thenReturn(Optional.of(coordinatorProfile));
    when(cohorts.findActiveForStaff(11L)).thenReturn(List.of(cohort));
    when(memberships.findActiveByCohortId(10L)).thenReturn(List.of(membership));
    when(cohortStaffAssignments.findActiveByCohortId(10L)).thenReturn(List.of(staffAssignment));
    when(directAssignments.findActiveByPatientProfileIdIn(List.of(20L))).thenReturn(List.of());
    when(memberships.findActiveByPatientProfileIdIn(List.of(20L))).thenReturn(List.of(membership));
    when(cohortStaffAssignments.findActiveByCohortIdIn(any())).thenReturn(List.of(staffAssignment));

    var detail = service.cohortDetail(auth("coordinator@example.com"), 10L);

    assertThat(detail.cohort().name()).isEqualTo("Pilot");
    assertThat(detail.patients()).hasSize(1);
    assertThat(detail.patients().getFirst().membershipId()).isEqualTo(40L);
    assertThat(detail.careTeam()).hasSize(1);
}

@Test
void cohortDetailIsNotFoundOutsideCoordinatorScope() {
    var coordinator = enabledUser(1L, "coordinator@example.com", RoleName.COORDINATOR);
    var coordinatorProfile = staffProfile(11L, coordinator);
    when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
    when(staffProfiles.findByUserId(coordinator.getId())).thenReturn(Optional.of(coordinatorProfile));
    when(cohorts.findActiveForStaff(11L)).thenReturn(List.of());

    assertStatus(() -> service.cohortDetail(auth("coordinator@example.com"), 10L),
            HttpStatus.NOT_FOUND);
}

@Test
void archivedCohortDetailReturnsHistoricalRows() {
    var admin = enabledUser(1L, "admin@example.com", RoleName.ADMIN);
    var cohort = cohort(10L, "Pilot", admin);
    cohort.archive(admin, NOW);
    var patient = patientProfile(20L, enabledUser(2L, "patient@example.com", RoleName.PATIENT));
    var membership = membership(40L, patient, cohort, admin);
    membership.end(admin, NOW);
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
    when(cohorts.findAllForAdministration()).thenReturn(List.of(cohort));
    when(memberships.findHistoryByCohortId(10L)).thenReturn(List.of(membership));
    when(cohortStaffAssignments.findHistoryByCohortId(10L)).thenReturn(List.of());

    var detail = service.cohortDetail(auth("admin@example.com"), 10L);

    assertThat(detail.cohort().archived()).isTrue();
    assertThat(detail.patients()).hasSize(1);
    assertThat(detail.patients().getFirst().direct()).isEmpty();
    assertThat(detail.patients().getFirst().inherited()).isEmpty();
    assertThat(detail.patients().getFirst().endedAt()).isEqualTo(NOW);
}

@Test
void scopedPatientsClampsSizeAndReturnsPageLevelCandidates() {
    var admin = enabledUser(1L, "admin@example.com", RoleName.ADMIN);
    var patientOption = new PatientOptionResponse(20L, "patient@example.com");
    var physician = staffProfile(30L, enabledUser(3L, "physician@example.com", RoleName.PHYSICIAN));
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
    when(patientProfiles.findAllEnabledPatientOptions(PageRequest.of(0, 200)))
            .thenReturn(new PageImpl<>(List.of(patientOption), PageRequest.of(0, 200), 1));
    when(cohorts.findAllForAdministration()).thenReturn(List.of());
    when(directAssignments.findActiveByPatientProfileIdIn(List.of(20L))).thenReturn(List.of());
    when(memberships.findActiveByPatientProfileIdIn(List.of(20L))).thenReturn(List.of());
    when(staffProfiles.findAllEnabledWithRoles()).thenReturn(List.of(physician));

    var page = service.scopedPatients(auth("admin@example.com"), 0, 500);

    assertThat(page.size()).isEqualTo(200);
    assertThat(page.patients()).hasSize(1);
    assertThat(page.patients().getFirst().patientProfileId()).isEqualTo(20L);
    assertThat(page.staffCandidates())
            .extracting(StaffOption::staffProfileId).containsExactly(30L);
}

@Test
void scopedPatientsEmptyResultReturnsZeroPages() {
    var admin = enabledUser(1L, "admin@example.com", RoleName.ADMIN);
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
    when(patientProfiles.findAllEnabledPatientOptions(PageRequest.of(0, 50)))
            .thenReturn(Page.empty(PageRequest.of(0, 50)));
    when(cohorts.findAllForAdministration()).thenReturn(List.of());
    when(staffProfiles.findAllEnabledWithRoles()).thenReturn(List.of());

    var page = service.scopedPatients(auth("admin@example.com"), -5, 0);

    assertThat(page.pageIndex()).isEqualTo(0);
    assertThat(page.size()).isEqualTo(50);
    assertThat(page.totalPages()).isEqualTo(0);
    assertThat(page.totalPatients()).isEqualTo(0);
    assertThat(page.patients()).isEmpty();
}

@Test
void patientCandidatesExcludeEnrolledPatients() {
    var admin = enabledUser(1L, "admin@example.com", RoleName.ADMIN);
    var cohort = cohort(10L, "Pilot", admin);
    var enrolled = patientProfile(20L, enabledUser(2L, "enrolled@example.com", RoleName.PATIENT));
    var membership = membership(40L, enrolled, cohort, admin);
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
    when(cohorts.findAllForAdministration()).thenReturn(List.of(cohort));
    when(memberships.findActiveByCohortId(10L)).thenReturn(List.of(membership));
    when(patientProfiles.findAllEnabledPatientOptions()).thenReturn(List.of(
            new PatientOptionResponse(20L, "enrolled@example.com"),
            new PatientOptionResponse(21L, "free@example.com")));

    var candidates = service.patientCandidates(auth("admin@example.com"), 10L);

    assertThat(candidates).extracting(PatientOptionResponse::id).containsExactly(21L);
}

@Test
void coordinatorStaffCandidatesExcludeCoordinators() {
    var coordinator = enabledUser(1L, "coordinator@example.com", RoleName.COORDINATOR);
    var coordinatorProfile = staffProfile(11L, coordinator);
    var cohort = cohort(10L, "Pilot", coordinator);
    var physician = staffProfile(30L, enabledUser(3L, "physician@example.com", RoleName.PHYSICIAN));
    var otherCoordinator = staffProfile(31L,
            enabledUser(4L, "other@example.com", RoleName.COORDINATOR));
    when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(coordinator));
    when(staffProfiles.findByUserId(coordinator.getId())).thenReturn(Optional.of(coordinatorProfile));
    when(cohorts.findActiveForStaff(11L)).thenReturn(List.of(cohort));
    when(cohortStaffAssignments.findActiveByCohortId(10L)).thenReturn(List.of());
    when(staffProfiles.findAllEnabledWithRoles()).thenReturn(List.of(physician, otherCoordinator));

    var candidates = service.staffCandidates(auth("coordinator@example.com"), 10L);

    assertThat(candidates).extracting(StaffOption::staffProfileId).containsExactly(30L);
}

@Test
void archivedCohortRejectsCandidateQueries() {
    var admin = enabledUser(1L, "admin@example.com", RoleName.ADMIN);
    var cohort = cohort(10L, "Pilot", admin);
    cohort.archive(admin, NOW);
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
    when(cohorts.findAllForAdministration()).thenReturn(List.of(cohort));

    assertStatus(() -> service.patientCandidates(auth("admin@example.com"), 10L),
            HttpStatus.CONFLICT);
    assertStatus(() -> service.staffCandidates(auth("admin@example.com"), 10L),
            HttpStatus.CONFLICT);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.metabion.service.AssignmentManagementServiceTest'`
Expected: compilation failure — the new records and service methods do not exist.

- [ ] **Step 3: Add the read-model records**

In `AssignmentManagementApi.java`, add imports and records:

```java
import com.metabion.dto.assignment.AssignmentManagementView.CohortItem;
import com.metabion.dto.assignment.AssignmentManagementView.ExpertAccess;
import com.metabion.dto.assignment.AssignmentManagementView.PatientRow;
import com.metabion.dto.assignment.AssignmentManagementView.StaffOption;

import java.util.List;
```

```java
    public record CohortDetailResponse(CohortItem cohort, List<PatientRow> patients,
                                       List<ExpertAccess> careTeam) {}

    public record PatientAssignmentRow(Long patientProfileId, String email,
                                       List<CohortItem> cohorts,
                                       List<ExpertAccess> direct,
                                       List<ExpertAccess> inherited) {}

    public record PatientsPageResponse(List<PatientAssignmentRow> patients,
                                       List<StaffOption> staffCandidates,
                                       int pageIndex, int size, int totalPages,
                                       long totalPatients) {}
```

- [ ] **Step 4: Add the service read methods**

In `AssignmentManagementService.java`, add imports:

```java
import com.metabion.dto.PatientOptionResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.CohortDetailResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.PatientAssignmentRow;
import com.metabion.dto.assignment.AssignmentManagementApi.PatientsPageResponse;
```

Add constants next to `DIRECT_PAGE_SIZE`:

```java
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
```

Add the new public methods (place them after `directPage`):

```java
    @Transactional(readOnly = true)
    public CohortDetailResponse cohortDetail(Authentication authentication, Long cohortId) {
        var actor = requireAssignmentManager(authentication);
        var visibleCohorts = visibleCohorts(actor);
        var selected = visibleCohorts.stream()
                .filter(cohort -> cohort.getId().equals(cohortId))
                .findFirst()
                .orElseThrow(() -> notFound("Cohort not found"));
        var selectedId = selected.getId();
        var cohortMemberships = selected.isArchived()
                ? memberships.findHistoryByCohortId(selectedId)
                : memberships.findActiveByCohortId(selectedId);
        var staffAssignments = selected.isArchived()
                ? cohortStaffAssignments.findHistoryByCohortId(selectedId)
                : cohortStaffAssignments.findActiveByCohortId(selectedId);
        if (selected.isArchived()) {
            var patients = cohortMemberships.stream().map(this::historicalPatientRow).toList();
            var careTeam = staffAssignments.stream()
                    .map(assignment -> cohortAccess(assignment, true, true))
                    .toList();
            return new CohortDetailResponse(cohortItem(selected), patients, careTeam);
        }
        var patientIds = cohortMemberships.stream()
                .map(row -> row.getPatientProfile().getId())
                .toList();
        var manageableCohortIds = visibleCohorts.stream()
                .filter(cohort -> !cohort.isArchived())
                .map(Cohort::getId)
                .collect(Collectors.toUnmodifiableSet());
        var access = accessForPatients(patientIds, manageableCohortIds);
        var patients = cohortMemberships.stream()
                .map(membership -> patientRow(membership, access))
                .toList();
        var careTeam = staffAssignments.stream()
                .map(assignment -> cohortAccess(assignment, true, false))
                .toList();
        return new CohortDetailResponse(cohortItem(selected), patients, careTeam);
    }

    @Transactional(readOnly = true)
    public PatientsPageResponse scopedPatients(Authentication authentication,
                                               int requestedPage,
                                               int requestedSize) {
        var actor = requireAssignmentManager(authentication);
        Long coordinatorProfileId = actor.hasRole(RoleName.ADMIN)
                ? null
                : requireCoordinatorProfileId(actor);
        var size = clampPageSize(requestedSize);
        var pageIndex = Math.max(0, requestedPage);
        var patientPage = patientPage(actor, coordinatorProfileId, pageIndex, size);
        if (patientPage.getTotalPages() == 0) {
            pageIndex = 0;
        } else if (pageIndex >= patientPage.getTotalPages()) {
            pageIndex = patientPage.getTotalPages() - 1;
            patientPage = patientPage(actor, coordinatorProfileId, pageIndex, size);
        }
        var patientOptions = patientPage.getContent();
        var visibleCohorts = actor.hasRole(RoleName.ADMIN)
                ? cohorts.findAllForAdministration()
                : cohorts.findActiveForStaff(coordinatorProfileId);
        var manageableCohortIds = visibleCohorts.stream()
                .filter(cohort -> !cohort.isArchived())
                .map(Cohort::getId)
                .collect(Collectors.toUnmodifiableSet());
        var patientIds = patientOptions.stream().map(PatientOptionResponse::id).toList();
        var access = accessForPatients(patientIds, manageableCohortIds);
        var staffCandidates = staffProfiles.findAllEnabledWithRoles().stream()
                .filter(this::eligibleDirectExpert)
                .map(this::staffOption)
                .toList();
        var patients = patientOptions.stream()
                .map(patient -> new PatientAssignmentRow(
                        patient.id(), patient.email(),
                        access.cohortsByPatient().getOrDefault(patient.id(), List.of()),
                        access.directByPatient().getOrDefault(patient.id(), List.of()),
                        access.inheritedByPatient().getOrDefault(patient.id(), List.of())))
                .toList();
        return new PatientsPageResponse(
                patients, staffCandidates, pageIndex, size,
                patientPage.getTotalPages(), patientPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<PatientOptionResponse> patientCandidates(Authentication authentication,
                                                         Long cohortId) {
        var actor = requireAssignmentManager(authentication);
        var cohort = requireVisibleActiveCohort(actor, cohortId);
        var activePatientIds = memberships.findActiveByCohortId(cohort.getId()).stream()
                .map(row -> row.getPatientProfile().getId())
                .collect(Collectors.toSet());
        return patientProfiles.findAllEnabledPatientOptions().stream()
                .filter(candidate -> !activePatientIds.contains(candidate.id()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StaffOption> staffCandidates(Authentication authentication, Long cohortId) {
        var actor = requireAssignmentManager(authentication);
        var cohort = requireVisibleActiveCohort(actor, cohortId);
        var activeStaffIds = cohortStaffAssignments.findActiveByCohortId(cohort.getId()).stream()
                .map(row -> row.getStaffProfile().getId())
                .collect(Collectors.toSet());
        return staffProfiles.findAllEnabledWithRoles().stream()
                .filter(profile -> eligibleCohortStaff(actor, profile))
                .filter(profile -> !activeStaffIds.contains(profile.getId()))
                .map(this::staffOption)
                .toList();
    }
```

Add the private helpers:

```java
    private Cohort requireVisibleActiveCohort(User actor, Long cohortId) {
        var cohort = visibleCohorts(actor).stream()
                .filter(candidate -> candidate.getId().equals(cohortId))
                .findFirst()
                .orElseThrow(() -> notFound("Cohort not found"));
        requireActiveCohort(cohort);
        return cohort;
    }

    private static int clampPageSize(int requestedSize) {
        if (requestedSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requestedSize, MAX_PAGE_SIZE);
    }
```

Overload `patientPage` (keep the existing 3-arg method delegating for `directPage`):

```java
    private Page<PatientOptionResponse> patientPage(
            User actor, Long coordinatorProfileId, int pageIndex) {
        return patientPage(actor, coordinatorProfileId, pageIndex, DIRECT_PAGE_SIZE);
    }

    private Page<PatientOptionResponse> patientPage(
            User actor, Long coordinatorProfileId, int pageIndex, int size) {
        var pageable = PageRequest.of(pageIndex, size);
        return actor.hasRole(RoleName.ADMIN)
                ? patientProfiles.findAllEnabledPatientOptions(pageable)
                : patientProfiles.findEnabledPatientOptionsForStaff(coordinatorProfileId, pageable);
    }
```

Also replace the fully qualified `com.metabion.dto.PatientOptionResponse` usages in `directPage` and the old `patientPage` with the new import.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.metabion.service.AssignmentManagementServiceTest'`
Expected: PASS (all Task 1 and Task 2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/dto/assignment/AssignmentManagementApi.java \
        src/main/java/com/metabion/service/AssignmentManagementService.java \
        src/test/java/com/metabion/service/AssignmentManagementServiceTest.java
git commit -m "Add lean assignment read models for the REST API"
```

---

### Task 3: JSON security-layer errors and the API role rule

**Files:**
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java` (filterChain:119-208; add helper + imports)
- Test: `src/test/java/com/metabion/config/SecurityConfigTest.java`

**Interfaces:**
- Consumes: existing `PathPatternRequestMatcher`, `DelegatingAuthenticationEntryPoint` setup at `SecurityConfig.java:125-141`.
- Produces: for any `/api/**` request, unauthenticated → `401 {"error":"unauthorized"}`; access denied (wrong role or CSRF rejection) → `403 {"error":"forbidden"}`. `/app/**` and `/api/mcp/**` behavior unchanged. Path rule: `/api/cohorts`, `/api/cohorts/**`, `/api/patients`, `/api/patients/**` require `COORDINATOR` or `ADMIN`.

- [ ] **Step 1: Write the failing tests**

Append to `SecurityConfigTest` (it already mocks `AssignmentManagementService` and wires MockMvc with `springSecurity()`; add the one missing import — `jsonPath` is not imported yet):

```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
```

```java
@Test
void anonymousAssignmentApiRequestReturnsJsonUnauthorized() throws Exception {
    mvc.perform(get("/api/cohorts"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("unauthorized"));
}

@Test
void nonManagerRolesGetJsonForbiddenOnAssignmentApi() throws Exception {
    for (var role : List.of(RoleName.PATIENT, RoleName.PHYSICIAN, RoleName.NUTRITION_SPECIALIST)) {
        mvc.perform(get("/api/cohorts")
                        .with(user(role.name().toLowerCase() + "@example.com").roles(role.name())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }
}

@Test
void assignmentApiMutationWithoutCsrfReturnsJsonForbidden() throws Exception {
    mvc.perform(post("/api/cohorts")
                    .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Pilot\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("forbidden"));
}

@Test
void anonymousMutationWithoutCsrfReturnsJsonForbidden() throws Exception {
    mvc.perform(post("/api/cohorts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Pilot\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("forbidden"));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.metabion.config.SecurityConfigTest'`
Expected: FAIL — `jsonPath("$.error")` finds no JSON body (bare 401/403 responses), and the role rule does not exist yet (a `PHYSICIAN` gets past the path rule; with no controller mapped the response is not the expected 403 JSON).

- [ ] **Step 3: Add JSON handlers and the role rule to SecurityConfig**

Add imports at the top of `SecurityConfig.java`:

```java
import org.springframework.http.MediaType;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.DelegatingAccessDeniedHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
```

In `filterChain`, immediately after the existing `var unauthorizedEntryPoint = ...` line (SecurityConfig.java:126), add:

```java
        var apiRequestMatcher = PathPatternRequestMatcher.pathPattern("/api/**");
        var apiUnauthorizedEntryPoint =
                (org.springframework.security.web.AuthenticationEntryPoint) (request, response, ex) ->
                        writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
        var apiAccessDeniedHandler = (AccessDeniedHandler) (request, response, ex) ->
                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "forbidden");
```

In the `DelegatingAuthenticationEntryPoint` builder, add one entry **after** the two MCP entries and **before** `.defaultEntryPoint(...)` (matcher order matters — the MCP bearer challenge must stay first):

```java
                .addEntryPointFor(apiUnauthorizedEntryPoint, apiRequestMatcher)
```

Replace the `exceptionHandling` block with:

```java
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(new DelegatingAccessDeniedHandler(
                                new LinkedHashMap<>(Map.of(apiRequestMatcher, apiAccessDeniedHandler)),
                                new AccessDeniedHandlerImpl())))
```

In `authorizeHttpRequests`, add the role rule **immediately before** `.requestMatchers("/api/**").authenticated()`:

```java
                        .requestMatchers("/api/cohorts", "/api/cohorts/**",
                                "/api/patients", "/api/patients/**")
                            .hasAnyRole("COORDINATOR", "ADMIN")
```

Add the helper method at the bottom of the class (next to `bearerChallenge`):

```java
    private static void writeJsonError(HttpServletResponse response, int status, String error)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.metabion.config.SecurityConfigTest'`
Expected: PASS, including all pre-existing tests (MCP entry point and `/app` login redirect behavior unchanged).

- [ ] **Step 5: Run the full API controller test package to catch regressions**

Run: `./gradlew test --tests 'com.metabion.controller.api.*'`
Expected: PASS — existing 401/403 status assertions still hold; only the response bodies changed.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/config/SecurityConfig.java \
        src/test/java/com/metabion/config/SecurityConfigTest.java
git commit -m "Return JSON security errors and guard assignment API paths"
```

---

### Task 4: CSRF bootstrap endpoint

**Files:**
- Modify: `src/main/java/com/metabion/dto/assignment/AssignmentManagementApi.java` (add record)
- Create: `src/main/java/com/metabion/controller/api/CsrfController.java`
- Test: `src/test/java/com/metabion/controller/api/CsrfControllerTest.java` (create)

**Interfaces:**
- Consumes: `AssignmentManagementApi` from Task 1; Spring Security's `CsrfToken` handler-method argument resolution.
- Produces: `AssignmentManagementApi.CsrfTokenResponse(String token, String headerName)`; `GET /api/csrf` → 200 `CsrfTokenResponse` for any authenticated session, plus the `XSRF-TOKEN` cookie.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/metabion/controller/api/CsrfControllerTest.java` (note the unique H2 database name — every test class uses its own):

```java
package com.metabion.controller.api;

import com.metabion.domain.RoleName;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:csrf_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class CsrfControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(springSecurity())
                .build();
    }

    @Test
    void authenticatedUserReceivesCsrfTokenAndCookie() throws Exception {
        mvc.perform(get("/api/csrf")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void anonymousRequestReturnsJsonUnauthorized() throws Exception {
        mvc.perform(get("/api/csrf"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.controller.api.CsrfControllerTest'`
Expected: FAIL — `/api/csrf` is unmapped (no 200 with token body).

- [ ] **Step 3: Add the record and the controller**

Add to `AssignmentManagementApi.java`:

```java
    public record CsrfTokenResponse(String token, String headerName) {}
```

Create `src/main/java/com/metabion/controller/api/CsrfController.java`:

```java
package com.metabion.controller.api;

import com.metabion.dto.assignment.AssignmentManagementApi.CsrfTokenResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {

    @GetMapping("/api/csrf")
    public CsrfTokenResponse csrf(CsrfToken token) {
        return new CsrfTokenResponse(token.getToken(), token.getHeaderName());
    }
}
```

Resolving the `CsrfToken` argument forces the deferred token to be generated and saved, which writes the `XSRF-TOKEN` cookie — no extra code is needed.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.metabion.controller.api.CsrfControllerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/dto/assignment/AssignmentManagementApi.java \
        src/main/java/com/metabion/controller/api/CsrfController.java \
        src/test/java/com/metabion/controller/api/CsrfControllerTest.java
git commit -m "Add CSRF bootstrap endpoint for API clients"
```

---

### Task 5: AssignmentManagementApiController and malformed-request error mapping

**Files:**
- Create: `src/main/java/com/metabion/controller/api/AssignmentManagementApiController.java`
- Modify: `src/main/java/com/metabion/controller/api/GlobalExceptionHandler.java` (add one handler + imports)
- Test: `src/test/java/com/metabion/controller/api/AssignmentManagementApiControllerTest.java` (create)

**Interfaces:**
- Consumes: all service methods from Tasks 1-2 (`listCohorts`, `createCohort`, `cohortDetail`, `updateCohort`, `archiveCohort`, `addPatientToCohort`, `endMembership`, `assignCohortStaff`, `endCohortStaffAssignment`, `patientCandidates`, `staffCandidates`, `scopedPatients`, `assignDirectExpert`, `endDirectExpertAssignment`), the DTO records from Tasks 1-2, `CohortForm`, `PatientOptionResponse`.
- Produces: the complete REST surface from the spec — `GET/POST /api/cohorts`, `GET/PUT /api/cohorts/{cohortId}`, `POST /api/cohorts/{cohortId}/archive`, `POST/DELETE /api/cohorts/{cohortId}/memberships[/{membershipId}]`, `POST/DELETE /api/cohorts/{cohortId}/staff-assignments[/{assignmentId}]`, `GET /api/cohorts/{cohortId}/patient-candidates`, `GET /api/cohorts/{cohortId}/staff-candidates`, `GET /api/patients`, `POST/DELETE /api/patients/{patientProfileId}/expert-assignments[/{assignmentId}]`; malformed JSON / type mismatch → `400 {"error":"request_failed"}`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/metabion/controller/api/AssignmentManagementApiControllerTest.java`:

```java
package com.metabion.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.domain.RoleName;
import com.metabion.dto.assignment.AssignmentManagementApi.AddPatientRequest;
import com.metabion.dto.assignment.AssignmentManagementApi.AssignStaffRequest;
import com.metabion.dto.assignment.AssignmentManagementApi.AssignmentResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.MembershipResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.PatientsPageResponse;
import com.metabion.dto.assignment.AssignmentManagementForms.CohortForm;
import com.metabion.dto.assignment.AssignmentManagementView.CohortItem;
import com.metabion.service.AssignmentManagementService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:assignment_management_api_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class AssignmentManagementApiControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    AssignmentManagementService assignments;

    private MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(springSecurity())
                .build();
    }

    @Test
    void listCohortsReturnsServiceResult() throws Exception {
        var item = new CohortItem(10L, "Pilot", "Notes", false, "admin@example.com", Instant.EPOCH);
        when(assignments.listCohorts(any())).thenReturn(List.of(item));

        mvc.perform(get("/api/cohorts")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].name").value("Pilot"));
    }

    @Test
    void createCohortReturnsCreated() throws Exception {
        var item = new CohortItem(10L, "Pilot", null, false, "admin@example.com", Instant.EPOCH);
        when(assignments.createCohort(any(), any())).thenReturn(item);

        mvc.perform(post("/api/cohorts")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CohortForm("Pilot", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10));

        verify(assignments).createCohort(any(), eq(new CohortForm("Pilot", null)));
    }

    @Test
    void blankCohortNameReturnsValidationFailure() throws Exception {
        mvc.perform(post("/api/cohorts")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CohortForm("", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.name").exists());

        verifyNoInteractions(assignments);
    }

    @Test
    void malformedJsonReturnsRequestFailed() throws Exception {
        mvc.perform(post("/api/cohorts")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("request_failed"));
    }

    @Test
    void nonNumericCohortIdReturnsRequestFailed() throws Exception {
        mvc.perform(get("/api/cohorts/abc")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("request_failed"));
    }

    @Test
    void updateCohortReturnsUpdatedCohort() throws Exception {
        var item = new CohortItem(10L, "Renamed", "Notes", false, "admin@example.com", Instant.EPOCH);
        when(assignments.updateCohort(any(), eq(10L), any())).thenReturn(item);

        mvc.perform(put("/api/cohorts/10")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CohortForm("Renamed", "Notes"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    void archiveConflictMapsToConflictError() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT))
                .when(assignments).archiveCohort(any(), eq(10L));

        mvc.perform(post("/api/cohorts/10/archive")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
    }

    @Test
    void outOfScopeCohortMapsToNotFound() throws Exception {
        when(assignments.cohortDetail(any(), eq(10L)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(get("/api/cohorts/10")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void membershipLifecycleReturnsCreatedIdThenNoContent() throws Exception {
        when(assignments.addPatientToCohort(any(), eq(10L), eq(20L)))
                .thenReturn(new MembershipResponse(55L));

        mvc.perform(post("/api/cohorts/10/memberships")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddPatientRequest(20L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.membershipId").value(55));

        mvc.perform(delete("/api/cohorts/10/memberships/55")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(assignments).endMembership(any(), eq(10L), eq(55L));
    }

    @Test
    void nullPatientIdReturnsValidationFailure() throws Exception {
        mvc.perform(post("/api/cohorts/10/memberships")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddPatientRequest(null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.patientProfileId").exists());

        verifyNoInteractions(assignments);
    }

    @Test
    void staffAssignmentLifecycleDelegates() throws Exception {
        when(assignments.assignCohortStaff(any(), eq(10L), eq(30L)))
                .thenReturn(new AssignmentResponse(60L));

        mvc.perform(post("/api/cohorts/10/staff-assignments")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignStaffRequest(30L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignmentId").value(60));

        mvc.perform(delete("/api/cohorts/10/staff-assignments/60")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name()))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(assignments).endCohortStaffAssignment(any(), eq(10L), eq(60L));
    }

    @Test
    void candidateEndpointsDelegateWithCohortScope() throws Exception {
        when(assignments.patientCandidates(any(), eq(10L))).thenReturn(List.of());
        when(assignments.staffCandidates(any(), eq(10L))).thenReturn(List.of());

        mvc.perform(get("/api/cohorts/10/patient-candidates")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/cohorts/10/staff-candidates")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name())))
                .andExpect(status().isOk());

        verify(assignments).patientCandidates(any(), eq(10L));
        verify(assignments).staffCandidates(any(), eq(10L));
    }

    @Test
    void patientsListPassesPaginationToService() throws Exception {
        var page = new PatientsPageResponse(List.of(), List.of(), 2, 25, 0, 0);
        when(assignments.scopedPatients(any(), eq(2), eq(25))).thenReturn(page);

        mvc.perform(get("/api/patients?page=2&size=25")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageIndex").value(2))
                .andExpect(jsonPath("$.size").value(25));

        verify(assignments).scopedPatients(any(), eq(2), eq(25));
    }

    @Test
    void patientsListUsesDefaultPagination() throws Exception {
        var page = new PatientsPageResponse(List.of(), List.of(), 0, 50, 0, 0);
        when(assignments.scopedPatients(any(), eq(0), eq(50))).thenReturn(page);

        mvc.perform(get("/api/patients")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name())))
                .andExpect(status().isOk());

        verify(assignments).scopedPatients(any(), eq(0), eq(50));
    }

    @Test
    void directExpertAssignmentLifecycleDelegates() throws Exception {
        when(assignments.assignDirectExpert(any(), eq(20L), eq(30L)))
                .thenReturn(new AssignmentResponse(61L));

        mvc.perform(post("/api/patients/20/expert-assignments")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignStaffRequest(30L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignmentId").value(61));

        mvc.perform(delete("/api/patients/20/expert-assignments/61")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name()))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(assignments).endDirectExpertAssignment(any(), eq(20L), eq(61L));
    }

    @Test
    void mutationWithoutCsrfReturnsJsonForbidden() throws Exception {
        mvc.perform(post("/api/cohorts")
                        .with(user("admin@example.com").roles(RoleName.ADMIN.name()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CohortForm("Pilot", null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        verifyNoInteractions(assignments);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.metabion.controller.api.AssignmentManagementApiControllerTest'`
Expected: FAIL — the controller does not exist (404s) and malformed JSON/type mismatch are not mapped to `request_failed`.

- [ ] **Step 3: Create the controller**

Create `src/main/java/com/metabion/controller/api/AssignmentManagementApiController.java`:

```java
package com.metabion.controller.api;

import com.metabion.dto.PatientOptionResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.AddPatientRequest;
import com.metabion.dto.assignment.AssignmentManagementApi.AssignStaffRequest;
import com.metabion.dto.assignment.AssignmentManagementApi.AssignmentResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.CohortDetailResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.MembershipResponse;
import com.metabion.dto.assignment.AssignmentManagementApi.PatientsPageResponse;
import com.metabion.dto.assignment.AssignmentManagementForms.CohortForm;
import com.metabion.dto.assignment.AssignmentManagementView.CohortItem;
import com.metabion.dto.assignment.AssignmentManagementView.StaffOption;
import com.metabion.service.AssignmentManagementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AssignmentManagementApiController {

    private final AssignmentManagementService assignments;

    public AssignmentManagementApiController(AssignmentManagementService assignments) {
        this.assignments = assignments;
    }

    @GetMapping("/api/cohorts")
    public List<CohortItem> listCohorts(Authentication authentication) {
        return assignments.listCohorts(authentication);
    }

    @PostMapping("/api/cohorts")
    @ResponseStatus(HttpStatus.CREATED)
    public CohortItem createCohort(Authentication authentication,
                                   @Valid @RequestBody CohortForm form) {
        return assignments.createCohort(authentication, form);
    }

    @GetMapping("/api/cohorts/{cohortId}")
    public CohortDetailResponse cohortDetail(Authentication authentication,
                                             @PathVariable Long cohortId) {
        return assignments.cohortDetail(authentication, cohortId);
    }

    @PutMapping("/api/cohorts/{cohortId}")
    public CohortItem updateCohort(Authentication authentication,
                                   @PathVariable Long cohortId,
                                   @Valid @RequestBody CohortForm form) {
        return assignments.updateCohort(authentication, cohortId, form);
    }

    @PostMapping("/api/cohorts/{cohortId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveCohort(Authentication authentication, @PathVariable Long cohortId) {
        assignments.archiveCohort(authentication, cohortId);
    }

    @PostMapping("/api/cohorts/{cohortId}/memberships")
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipResponse addPatient(Authentication authentication,
                                         @PathVariable Long cohortId,
                                         @Valid @RequestBody AddPatientRequest request) {
        return assignments.addPatientToCohort(authentication, cohortId, request.patientProfileId());
    }

    @DeleteMapping("/api/cohorts/{cohortId}/memberships/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endMembership(Authentication authentication,
                              @PathVariable Long cohortId,
                              @PathVariable Long membershipId) {
        assignments.endMembership(authentication, cohortId, membershipId);
    }

    @PostMapping("/api/cohorts/{cohortId}/staff-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentResponse assignStaff(Authentication authentication,
                                          @PathVariable Long cohortId,
                                          @Valid @RequestBody AssignStaffRequest request) {
        return assignments.assignCohortStaff(authentication, cohortId, request.staffProfileId());
    }

    @DeleteMapping("/api/cohorts/{cohortId}/staff-assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endStaffAssignment(Authentication authentication,
                                   @PathVariable Long cohortId,
                                   @PathVariable Long assignmentId) {
        assignments.endCohortStaffAssignment(authentication, cohortId, assignmentId);
    }

    @GetMapping("/api/cohorts/{cohortId}/patient-candidates")
    public List<PatientOptionResponse> patientCandidates(Authentication authentication,
                                                         @PathVariable Long cohortId) {
        return assignments.patientCandidates(authentication, cohortId);
    }

    @GetMapping("/api/cohorts/{cohortId}/staff-candidates")
    public List<StaffOption> staffCandidates(Authentication authentication,
                                             @PathVariable Long cohortId) {
        return assignments.staffCandidates(authentication, cohortId);
    }

    @GetMapping("/api/patients")
    public PatientsPageResponse patients(Authentication authentication,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        return assignments.scopedPatients(authentication, page, size);
    }

    @PostMapping("/api/patients/{patientProfileId}/expert-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentResponse assignDirectExpert(Authentication authentication,
                                                 @PathVariable Long patientProfileId,
                                                 @Valid @RequestBody AssignStaffRequest request) {
        return assignments.assignDirectExpert(
                authentication, patientProfileId, request.staffProfileId());
    }

    @DeleteMapping("/api/patients/{patientProfileId}/expert-assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endDirectExpertAssignment(Authentication authentication,
                                          @PathVariable Long patientProfileId,
                                          @PathVariable Long assignmentId) {
        assignments.endDirectExpertAssignment(authentication, patientProfileId, assignmentId);
    }
}
```

- [ ] **Step 4: Extend GlobalExceptionHandler**

In `GlobalExceptionHandler.java`, add imports:

```java
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
```

Add the handler after the existing `validation(ValidationException)` method:

```java
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> unreadableRequest(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "request_failed"));
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.metabion.controller.api.AssignmentManagementApiControllerTest'`
Expected: PASS.

- [ ] **Step 6: Run neighboring suites for regressions**

Run: `./gradlew test --tests 'com.metabion.controller.api.*' --tests 'com.metabion.config.SecurityConfigTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/metabion/controller/api/AssignmentManagementApiController.java \
        src/main/java/com/metabion/controller/api/GlobalExceptionHandler.java \
        src/test/java/com/metabion/controller/api/AssignmentManagementApiControllerTest.java
git commit -m "Add assignment management REST controller"
```

---

### Task 6: End-to-end HTTP integration tests

**Files:**
- Modify: `src/test/java/com/metabion/integration/AbstractAuthIT.java` (add `putWithHeaders`/`deleteWithHeaders` to `TestClient`, after `postWithHeaders`:191-193)
- Modify: `AGENTS.md` (one-line mention of the new API in the product-areas list)
- Test: `src/test/java/com/metabion/integration/AssignmentManagementApiIT.java` (create)

**Interfaces:**
- Consumes: the complete API from Tasks 1-5; `AbstractAuthIT` helpers (`newClient`, `login`, `json`, `users`, `passwordEncoder`, `jdbc`); real repositories.
- Produces: `TestClient.putWithHeaders(String path, Object body, Map<String,String> headers)` and `TestClient.deleteWithHeaders(String path, Map<String,String> headers)` for reuse by other ITs.

- [ ] **Step 1: Write the failing integration test**

Create `src/test/java/com/metabion/integration/AssignmentManagementApiIT.java`:

```java
package com.metabion.integration;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.dto.assignment.AssignmentManagementForms.CohortForm;
import com.metabion.repository.CohortStaffAssignmentRepository;
import com.metabion.repository.PatientCohortMembershipRepository;
import com.metabion.repository.PatientExpertAssignmentRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.service.AssignmentManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentManagementApiIT extends AbstractAuthIT {

    private static final String PASSWORD = "Integration1!";

    @Autowired
    AssignmentManagementService assignmentManagement;
    @Autowired
    PatientCohortMembershipRepository memberships;
    @Autowired
    CohortStaffAssignmentRepository cohortStaffAssignments;
    @Autowired
    PatientExpertAssignmentRepository directAssignments;
    @Autowired
    PatientProfileRepository patientProfiles;
    @Autowired
    StaffProfileRepository staffProfiles;

    @Test
    void coordinatorManagesCohortLifecycleOverHttpWithCsrf() throws Exception {
        enabledStaff("coord-api@example.com", RoleName.COORDINATOR);
        var patientProfile = patient("patient-api@example.com");
        var physician = enabledStaff("phys-api@example.com", RoleName.PHYSICIAN);
        var physicianProfileId = staffProfiles.findByUserId(physician.getId()).orElseThrow().getId();

        var client = newClient();
        assertThat(login(client, "coord-api@example.com", PASSWORD).getStatusCode().value())
                .isEqualTo(200);
        var token = json(client.get("/api/csrf")).get("token").asText();

        var created = client.postWithHeaders("/api/cohorts",
                Map.of("name", "API Pilot", "description", "created over HTTP"),
                Map.of("X-XSRF-TOKEN", token));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        var cohortId = json(created).get("id").asLong();

        var membership = client.postWithHeaders("/api/cohorts/" + cohortId + "/memberships",
                Map.of("patientProfileId", patientProfile.getId()),
                Map.of("X-XSRF-TOKEN", token));
        assertThat(membership.getStatusCode().value()).isEqualTo(201);
        assertThat(memberships.existsActiveMembership(patientProfile.getId(), cohortId)).isTrue();

        var staffAssignment = client.postWithHeaders("/api/cohorts/" + cohortId + "/staff-assignments",
                Map.of("staffProfileId", physicianProfileId),
                Map.of("X-XSRF-TOKEN", token));
        assertThat(staffAssignment.getStatusCode().value()).isEqualTo(201);

        var detail = json(client.get("/api/cohorts/" + cohortId));
        assertThat(detail.get("patients").size()).isEqualTo(1);
        assertThat(detail.get("careTeam").size()).isEqualTo(1);

        var noCsrf = client.postWithHeaders("/api/cohorts/" + cohortId + "/memberships",
                Map.of("patientProfileId", patientProfile.getId()), Map.of());
        assertThat(noCsrf.getStatusCode().value()).isEqualTo(403);
        assertThat(json(noCsrf).get("error").asText()).isEqualTo("forbidden");
    }

    @Test
    void coordinatorAssignsAndEndsDirectExpertOverHttp() throws Exception {
        var coordinatorAuth = serviceAuth("coord-direct@example.com");
        enabledStaff("coord-direct@example.com", RoleName.COORDINATOR);
        var patientProfile = patient("patient-direct@example.com");
        var nutritionist = enabledStaff("nutri-direct@example.com", RoleName.NUTRITION_SPECIALIST);
        var nutritionistProfileId = staffProfiles.findByUserId(nutritionist.getId())
                .orElseThrow().getId();
        var cohortId = assignmentManagement.createCohort(
                coordinatorAuth, new CohortForm("Direct scope", null)).id();
        assignmentManagement.addPatientToCohort(coordinatorAuth, cohortId, patientProfile.getId());

        var client = newClient();
        login(client, "coord-direct@example.com", PASSWORD);
        var token = json(client.get("/api/csrf")).get("token").asText();

        var assigned = client.postWithHeaders(
                "/api/patients/" + patientProfile.getId() + "/expert-assignments",
                Map.of("staffProfileId", nutritionistProfileId),
                Map.of("X-XSRF-TOKEN", token));
        assertThat(assigned.getStatusCode().value()).isEqualTo(201);
        var assignmentId = json(assigned).get("assignmentId").asLong();
        assertThat(directAssignments.existsActiveAssignment(
                patientProfile.getId(), nutritionistProfileId)).isTrue();

        var page = json(client.get("/api/patients"));
        assertThat(page.get("patients").get(0).get("direct").get(0).get("assignmentId").asLong())
                .isEqualTo(assignmentId);

        var ended = client.deleteWithHeaders(
                "/api/patients/" + patientProfile.getId() + "/expert-assignments/" + assignmentId,
                Map.of("X-XSRF-TOKEN", token));
        assertThat(ended.getStatusCode().value()).isEqualTo(204);
        assertThat(directAssignments.existsActiveAssignment(
                patientProfile.getId(), nutritionistProfileId)).isFalse();
    }

    @Test
    void adminEditsAndArchivesCohortOverHttp() throws Exception {
        enabledStaff("admin-api@example.com", RoleName.ADMIN);
        var adminAuth = serviceAuth("admin-api@example.com");
        var patientProfile = patient("patient-archive@example.com");
        var cohortId = assignmentManagement.createCohort(
                adminAuth, new CohortForm("Archive me", null)).id();
        assignmentManagement.addPatientToCohort(adminAuth, cohortId, patientProfile.getId());

        var client = newClient();
        login(client, "admin-api@example.com", PASSWORD);
        var token = json(client.get("/api/csrf")).get("token").asText();

        var edited = client.putWithHeaders("/api/cohorts/" + cohortId,
                Map.of("name", "Renamed cohort", "description", "edited"),
                Map.of("X-XSRF-TOKEN", token));
        assertThat(edited.getStatusCode().value()).isEqualTo(200);
        assertThat(json(edited).get("name").asText()).isEqualTo("Renamed cohort");

        var archived = client.postWithHeaders("/api/cohorts/" + cohortId + "/archive", null,
                Map.of("X-XSRF-TOKEN", token));
        assertThat(archived.getStatusCode().value()).isEqualTo(204);

        var detail = json(client.get("/api/cohorts/" + cohortId));
        assertThat(detail.get("cohort").get("archived").asBoolean()).isTrue();
        assertThat(detail.get("patients").get(0).get("endedAt").isTextual()).isTrue();

        var conflicted = client.postWithHeaders("/api/cohorts/" + cohortId + "/memberships",
                Map.of("patientProfileId", patientProfile.getId()),
                Map.of("X-XSRF-TOKEN", token));
        assertThat(conflicted.getStatusCode().value()).isEqualTo(409);
        assertThat(json(conflicted).get("error").asText()).isEqualTo("conflict");
    }

    @Test
    void patientRoleIsRejectedButCsrfBootstrapStaysOpen() throws Exception {
        createEnabledUser("patient-role@example.com", PASSWORD);

        var client = newClient();
        login(client, "patient-role@example.com", PASSWORD);

        assertThat(client.get("/api/cohorts").getStatusCode().value()).isEqualTo(403);
        assertThat(client.get("/api/patients").getStatusCode().value()).isEqualTo(403);
        assertThat(client.get("/api/csrf").getStatusCode().value()).isEqualTo(200);
    }

    private User enabledStaff(String email, RoleName role) {
        var user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setEnabled(true);
        user.addRole(role);
        var saved = users.saveAndFlush(user);
        staffProfiles.saveAndFlush(new StaffProfile(saved));
        return saved;
    }

    private PatientProfile patient(String email) {
        var user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        return patientProfiles.saveAndFlush(new PatientProfile(users.saveAndFlush(user)));
    }

    private Authentication serviceAuth(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(
                email, "n/a", AuthorityUtils.NO_AUTHORITIES);
    }
}
```

- [ ] **Step 2: Add PUT/DELETE support to TestClient**

In `AbstractAuthIT.java`, add these methods to `TestClient` immediately after `postWithHeaders`:

```java
        ResponseEntity<String> putWithHeaders(String path, Object body, Map<String, String> extraHeaders) {
            return exchange(path, "PUT", body, extraHeaders);
        }

        ResponseEntity<String> deleteWithHeaders(String path, Map<String, String> extraHeaders) {
            return exchange(path, "DELETE", null, extraHeaders);
        }
```

- [ ] **Step 3: Run the integration test to verify it fails, then passes**

Run: `./gradlew test --tests 'com.metabion.integration.AssignmentManagementApiIT'`
Expected: PASS once Tasks 1-5 are complete (the IT exercises the whole stack; if run standalone before earlier tasks it fails at compile time on missing methods). If any assertion fails, fix the underlying behavior in the API, not the test.

- [ ] **Step 4: Update AGENTS.md**

In `AGENTS.md`, in the "The implemented product areas are:" list, change the cohorts bullet to mention the API:

```markdown
- Staff invitations, patient onboarding submissions, clinical review, cohorts, and staff/patient assignments, exposed through the Thymeleaf workspace and a session-authenticated REST API (`/api/cohorts`, `/api/patients`, `GET /api/csrf`).
```

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS — the spec's final verification.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/metabion/integration/AbstractAuthIT.java \
        src/test/java/com/metabion/integration/AssignmentManagementApiIT.java \
        AGENTS.md
git commit -m "Cover assignment REST API with HTTP integration tests"
```
