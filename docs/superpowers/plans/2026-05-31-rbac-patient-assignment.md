# RBAC Patient Assignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement role-based access control and patient/staff/cohort assignment foundations for clinical-study authorization.

**Architecture:** Keep `users` as the authentication account table and keep `user_roles` as the persisted role set. Add patient/staff profile tables and assignment tables as the clinical authorization domain. Add a focused `AccessControlService` that answers access questions from the authenticated principal and repository predicates.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring Security sessions, Spring Data JPA, Flyway, H2 slice tests, Testcontainers PostgreSQL for Postgres-specific partial indexes, JUnit 5, AssertJ, Mockito.

---

## File Structure

Create:

- `src/main/java/com/metabion/domain/RoleName.java` - supported role enum and Spring authority conversion.
- `src/main/java/com/metabion/domain/PatientProfile.java` - patient domain profile linked to `User`.
- `src/main/java/com/metabion/domain/StaffProfile.java` - staff domain profile linked to `User`.
- `src/main/java/com/metabion/domain/Cohort.java` - simple patient grouping entity.
- `src/main/java/com/metabion/domain/PatientCohortMembership.java` - patient-to-cohort membership with active/ended state.
- `src/main/java/com/metabion/domain/PatientExpertAssignment.java` - direct patient-to-staff assignment with active/ended state.
- `src/main/java/com/metabion/domain/CohortStaffAssignment.java` - staff-to-cohort assignment with active/ended state.
- `src/main/java/com/metabion/repository/PatientProfileRepository.java` - patient profile lookups.
- `src/main/java/com/metabion/repository/StaffProfileRepository.java` - staff profile lookups.
- `src/main/java/com/metabion/repository/CohortRepository.java` - cohort persistence.
- `src/main/java/com/metabion/repository/PatientCohortMembershipRepository.java` - active patient/cohort membership predicates.
- `src/main/java/com/metabion/repository/PatientExpertAssignmentRepository.java` - active direct patient/staff assignment predicates.
- `src/main/java/com/metabion/repository/CohortStaffAssignmentRepository.java` - active cohort/staff assignment predicates.
- `src/main/java/com/metabion/service/AccessControlService.java` - authorization decision service.
- `src/main/resources/db/migration/V4__rbac_assignment_model.sql` - Flyway schema for profiles, cohorts, assignments, role checks, and active uniqueness.
- `src/test/java/com/metabion/repository/RbacAssignmentRepositoryTest.java` - persistence and predicate tests.
- `src/test/java/com/metabion/service/AccessControlServiceTest.java` - access matrix unit tests.

Modify:

- `src/main/java/com/metabion/domain/User.java` - role validation helpers and multi-role predicates.
- `src/main/java/com/metabion/domain/UserRole.java` - constructor overload using `RoleName`.
- `src/main/java/com/metabion/config/SecurityConfig.java` - use `RoleName.authority()` when building authorities.
- `src/main/java/com/metabion/service/UserService.java` - create patient profile during registration.
- `src/main/java/com/metabion/controller/AuthController.java` - return roles from `/api/auth/me`.
- `src/main/java/com/metabion/controller/WebAuthController.java` - display real roles on `/app`.
- Existing tests that construct users with `"USER"` must use a supported role, usually `RoleName.PATIENT.name()`.

---

### Task 1: Role Enum and User Role Validation

**Files:**

- Create: `src/main/java/com/metabion/domain/RoleName.java`
- Modify: `src/main/java/com/metabion/domain/User.java`
- Modify: `src/main/java/com/metabion/domain/UserRole.java`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`
- Modify tests that call `user.addRole("USER")`
- Test: `src/test/java/com/metabion/repository/UserRepositoryTest.java`

- [ ] **Step 1: Write failing tests for supported, multiple, and rejected roles**

Edit `src/test/java/com/metabion/repository/UserRepositoryTest.java`:

```java
package com.metabion.repository;

import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class UserRepositoryTest {

    @Autowired
    UserRepository users;

    @Test
    void uniqueEmailConstraintRejectsDuplicateEmail() {
        users.save(buildUser("a@x.com"));

        assertThatThrownBy(() -> users.saveAndFlush(buildUser("a@x.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByEmailReturnsUserWithRoles() {
        var user = buildUser("b@x.com");
        user.addRole(RoleName.PATIENT);
        users.save(user);

        var loaded = users.findByEmail("b@x.com").orElseThrow();

        assertThat(loaded.roleNames()).containsExactly("PATIENT");
        assertThat(loaded.hasRole(RoleName.PATIENT)).isTrue();
    }

    @Test
    void userCanHaveMultipleSupportedRoles() {
        var user = buildUser("multi@x.com");
        user.addRole(RoleName.PHYSICIAN);
        user.addRole(RoleName.COORDINATOR);
        users.saveAndFlush(user);

        var loaded = users.findByEmail("multi@x.com").orElseThrow();

        assertThat(loaded.roleNames()).containsExactly("COORDINATOR", "PHYSICIAN");
        assertThat(loaded.hasAnyRole(RoleName.PHYSICIAN, RoleName.COORDINATOR)).isTrue();
        assertThat(loaded.hasAnyRole(RoleName.PATIENT, RoleName.ADMIN)).isFalse();
    }

    @Test
    void unsupportedRoleIsRejected() {
        var user = buildUser("bad-role@x.com");

        assertThatThrownBy(() -> user.addRole("USER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported role: USER");
    }

    @Test
    void emailIsNormalizedBeforePersisting() {
        users.saveAndFlush(buildUser("  C@X.COM  "));

        assertThat(users.findByEmail("c@x.com")).isPresent();
    }

    private static User buildUser(String email) {
        return new User(email, "hash");
    }
}
```

- [ ] **Step 2: Run role tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.repository.UserRepositoryTest
```

Expected: compilation fails because `RoleName`, `User.hasRole(...)`, and `User.hasAnyRole(...)` do not exist.

- [ ] **Step 3: Add `RoleName`**

Create `src/main/java/com/metabion/domain/RoleName.java`:

```java
package com.metabion.domain;

import java.util.Arrays;

public enum RoleName {
    PATIENT,
    NUTRITION_SPECIALIST,
    PHYSICIAN,
    COORDINATOR,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }

    public static RoleName from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Unsupported role: null");
        }
        return Arrays.stream(values())
                .filter(role -> role.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported role: " + value));
    }

    public boolean isClinicalStaff() {
        return this == NUTRITION_SPECIALIST || this == PHYSICIAN || this == COORDINATOR;
    }
}
```

- [ ] **Step 4: Update `User` role helpers**

In `src/main/java/com/metabion/domain/User.java`, add the `RoleName`-based methods and keep the string method for compatibility:

```java
public void addRole(RoleName role) {
    this.roles.add(new UserRole(this, role));
}

public void addRole(String role) {
    addRole(RoleName.from(role));
}

public boolean hasRole(RoleName role) {
    return roles.stream().anyMatch(userRole -> role.name().equals(userRole.getRole()));
}

public boolean hasAnyRole(RoleName... roleNames) {
    for (var roleName : roleNames) {
        if (hasRole(roleName)) {
            return true;
        }
    }
    return false;
}
```

Keep the existing `roleNames()` method unchanged.

- [ ] **Step 5: Update `UserRole` constructor**

In `src/main/java/com/metabion/domain/UserRole.java`, replace the string constructor with this pair:

```java
public UserRole(User user, RoleName role) {
    this.user = user;
    this.id = new UserRoleKey(user.getId(), role.name());
}

public UserRole(User user, String role) {
    this(user, RoleName.from(role));
}
```

Add the import:

```java
import com.metabion.domain.RoleName;
```

Because this class is in the same package, the import is optional; omit it if the IDE flags it as redundant.

- [ ] **Step 6: Update Spring Security authority mapping**

In `src/main/java/com/metabion/config/SecurityConfig.java`, replace:

```java
.map(role -> new SimpleGrantedAuthority("ROLE_" + role))
```

with:

```java
.map(role -> new SimpleGrantedAuthority(com.metabion.domain.RoleName.from(role).authority()))
```

- [ ] **Step 7: Replace unsupported test role literals**

In tests such as `src/test/java/com/metabion/service/SecurityServiceTest.java`, replace:

```java
testUser.addRole("USER");
```

with:

```java
testUser.addRole("PATIENT");
```

Use `rg 'addRole\\("USER"\\)' src/test/java src/main/java` to find all occurrences.

- [ ] **Step 8: Run role tests and full compile**

Run:

```bash
./gradlew test --tests com.metabion.repository.UserRepositoryTest
./gradlew test --tests com.metabion.service.SecurityServiceTest
```

Expected: both commands pass.

- [ ] **Step 9: Commit role foundation**

```bash
git add src/main/java/com/metabion/domain/RoleName.java \
        src/main/java/com/metabion/domain/User.java \
        src/main/java/com/metabion/domain/UserRole.java \
        src/main/java/com/metabion/config/SecurityConfig.java \
        src/test/java/com/metabion/repository/UserRepositoryTest.java \
        src/test/java/com/metabion/service/SecurityServiceTest.java
git commit -m "Add supported RBAC roles"
```

---

### Task 2: RBAC Assignment Schema and Domain Entities

**Files:**

- Create: `src/main/resources/db/migration/V4__rbac_assignment_model.sql`
- Create: `src/main/java/com/metabion/domain/PatientProfile.java`
- Create: `src/main/java/com/metabion/domain/StaffProfile.java`
- Create: `src/main/java/com/metabion/domain/Cohort.java`
- Create: `src/main/java/com/metabion/domain/PatientCohortMembership.java`
- Create: `src/main/java/com/metabion/domain/PatientExpertAssignment.java`
- Create: `src/main/java/com/metabion/domain/CohortStaffAssignment.java`
- Create: `src/main/java/com/metabion/repository/PatientProfileRepository.java`
- Create: `src/main/java/com/metabion/repository/StaffProfileRepository.java`
- Create: `src/main/java/com/metabion/repository/CohortRepository.java`
- Create: `src/main/java/com/metabion/repository/PatientCohortMembershipRepository.java`
- Create: `src/main/java/com/metabion/repository/PatientExpertAssignmentRepository.java`
- Create: `src/main/java/com/metabion/repository/CohortStaffAssignmentRepository.java`
- Test: `src/test/java/com/metabion/repository/RbacAssignmentRepositoryTest.java`

- [ ] **Step 1: Write failing repository tests**

Create `src/test/java/com/metabion/repository/RbacAssignmentRepositoryTest.java`:

```java
package com.metabion.repository;

import com.metabion.domain.Cohort;
import com.metabion.domain.CohortStaffAssignment;
import com.metabion.domain.PatientCohortMembership;
import com.metabion.domain.PatientExpertAssignment;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RbacAssignmentRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired UserRepository users;
    @Autowired PatientProfileRepository patientProfiles;
    @Autowired StaffProfileRepository staffProfiles;
    @Autowired CohortRepository cohorts;
    @Autowired PatientCohortMembershipRepository memberships;
    @Autowired PatientExpertAssignmentRepository directAssignments;
    @Autowired CohortStaffAssignmentRepository cohortStaffAssignments;

    @Test
    void profileRowsAreFoundByUserId() {
        var patient = patient("patient@example.com");
        var staff = staff("staff@example.com", RoleName.PHYSICIAN);

        assertThat(patientProfiles.findByUserId(patient.getUser().getId())).contains(patient);
        assertThat(staffProfiles.findByUserId(staff.getUser().getId())).contains(staff);
    }

    @Test
    void activeDirectAssignmentPredicateHonorsEndedAt() {
        var patient = patient("patient-direct@example.com");
        var staff = staff("physician-direct@example.com", RoleName.PHYSICIAN);

        var assignment = new PatientExpertAssignment(patient, staff, null);
        directAssignments.saveAndFlush(assignment);

        assertThat(directAssignments.existsActiveAssignment(patient.getId(), staff.getId())).isTrue();

        assignment.setEndedAt(Instant.now());
        directAssignments.saveAndFlush(assignment);

        assertThat(directAssignments.existsActiveAssignment(patient.getId(), staff.getId())).isFalse();
    }

    @Test
    void activeCohortAccessRequiresActiveMembershipAndActiveStaffAssignment() {
        var patient = patient("patient-cohort@example.com");
        var staff = staff("coordinator-cohort@example.com", RoleName.COORDINATOR);
        var cohort = cohorts.saveAndFlush(new Cohort("Study A"));

        var membership = memberships.saveAndFlush(new PatientCohortMembership(patient, cohort, null));
        var staffAssignment = cohortStaffAssignments.saveAndFlush(new CohortStaffAssignment(cohort, staff, null));

        assertThat(memberships.existsActiveMembership(patient.getId(), cohort.getId())).isTrue();
        assertThat(cohortStaffAssignments.existsActiveAssignment(cohort.getId(), staff.getId())).isTrue();
        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(patient.getId(), staff.getId())).isTrue();

        membership.setEndedAt(Instant.now());
        memberships.saveAndFlush(membership);

        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(patient.getId(), staff.getId())).isFalse();

        membership.setEndedAt(null);
        memberships.saveAndFlush(membership);
        staffAssignment.setEndedAt(Instant.now());
        cohortStaffAssignments.saveAndFlush(staffAssignment);

        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(patient.getId(), staff.getId())).isFalse();
    }

    @Test
    void duplicateActiveAssignmentsAreRejected() {
        var patient = patient("patient-unique@example.com");
        var staff = staff("staff-unique@example.com", RoleName.NUTRITION_SPECIALIST);
        var cohort = cohorts.saveAndFlush(new Cohort("Unique cohort"));

        directAssignments.saveAndFlush(new PatientExpertAssignment(patient, staff, null));
        assertThatThrownBy(() -> directAssignments.saveAndFlush(new PatientExpertAssignment(patient, staff, null)))
                .isInstanceOf(DataIntegrityViolationException.class);

        memberships.saveAndFlush(new PatientCohortMembership(patient, cohort, null));
        assertThatThrownBy(() -> memberships.saveAndFlush(new PatientCohortMembership(patient, cohort, null)))
                .isInstanceOf(DataIntegrityViolationException.class);

        cohortStaffAssignments.saveAndFlush(new CohortStaffAssignment(cohort, staff, null));
        assertThatThrownBy(() -> cohortStaffAssignments.saveAndFlush(new CohortStaffAssignment(cohort, staff, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private PatientProfile patient(String email) {
        var user = new User(email, "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        users.saveAndFlush(user);
        return patientProfiles.saveAndFlush(new PatientProfile(user));
    }

    private StaffProfile staff(String email, RoleName role) {
        var user = new User(email, "hash");
        user.setEnabled(true);
        user.addRole(role);
        users.saveAndFlush(user);
        return staffProfiles.saveAndFlush(new StaffProfile(user));
    }
}
```

- [ ] **Step 2: Run repository tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.repository.RbacAssignmentRepositoryTest
```

Expected: compilation fails because the new entities and repositories do not exist.

- [ ] **Step 3: Add Flyway migration**

Create `src/main/resources/db/migration/V4__rbac_assignment_model.sql`:

```sql
ALTER TABLE user_roles
    ADD CONSTRAINT chk_user_roles_role
        CHECK (role IN ('PATIENT', 'NUTRITION_SPECIALIST', 'PHYSICIAN', 'COORDINATOR', 'ADMIN'));

CREATE TABLE patient_profiles (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE staff_profiles (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE cohorts (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE patient_cohort_memberships (
    id                  BIGSERIAL PRIMARY KEY,
    patient_profile_id  BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    cohort_id           BIGINT NOT NULL REFERENCES cohorts(id) ON DELETE CASCADE,
    assigned_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    assigned_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ended_at            TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX ux_patient_cohort_memberships_active
    ON patient_cohort_memberships(patient_profile_id, cohort_id)
    WHERE ended_at IS NULL;

CREATE TABLE patient_expert_assignments (
    id                  BIGSERIAL PRIMARY KEY,
    patient_profile_id  BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    staff_profile_id    BIGINT NOT NULL REFERENCES staff_profiles(id) ON DELETE CASCADE,
    assigned_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    assigned_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ended_at            TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX ux_patient_expert_assignments_active
    ON patient_expert_assignments(patient_profile_id, staff_profile_id)
    WHERE ended_at IS NULL;

CREATE TABLE cohort_staff_assignments (
    id                  BIGSERIAL PRIMARY KEY,
    cohort_id           BIGINT NOT NULL REFERENCES cohorts(id) ON DELETE CASCADE,
    staff_profile_id    BIGINT NOT NULL REFERENCES staff_profiles(id) ON DELETE CASCADE,
    assigned_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    assigned_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ended_at            TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX ux_cohort_staff_assignments_active
    ON cohort_staff_assignments(cohort_id, staff_profile_id)
    WHERE ended_at IS NULL;

CREATE INDEX ix_patient_cohort_memberships_patient
    ON patient_cohort_memberships(patient_profile_id);

CREATE INDEX ix_patient_cohort_memberships_cohort
    ON patient_cohort_memberships(cohort_id);

CREATE INDEX ix_patient_expert_assignments_patient
    ON patient_expert_assignments(patient_profile_id);

CREATE INDEX ix_patient_expert_assignments_staff
    ON patient_expert_assignments(staff_profile_id);

CREATE INDEX ix_cohort_staff_assignments_cohort
    ON cohort_staff_assignments(cohort_id);

CREATE INDEX ix_cohort_staff_assignments_staff
    ON cohort_staff_assignments(staff_profile_id);
```

This test uses Flyway and Testcontainers PostgreSQL because the active-assignment uniqueness rules use Postgres partial unique indexes.

- [ ] **Step 4: Add profile and cohort entities**

Create `src/main/java/com/metabion/domain/PatientProfile.java`:

```java
package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "patient_profiles")
public class PatientProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public PatientProfile() {}

    public PatientProfile(User user) {
        this.user = user;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

Create `src/main/java/com/metabion/domain/StaffProfile.java`:

```java
package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "staff_profiles")
public class StaffProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public StaffProfile() {}

    public StaffProfile(User user) {
        this.user = user;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

Create `src/main/java/com/metabion/domain/Cohort.java`:

```java
package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "cohorts")
public class Cohort {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Cohort() {}

    public Cohort(String name) {
        this.name = name;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 5: Add assignment entities**

Create `PatientCohortMembership`, `PatientExpertAssignment`, and `CohortStaffAssignment` with `assignedAt = Instant.now()`, nullable `endedAt`, and lazy many-to-one associations.

Use this exact pattern for `PatientExpertAssignment`:

```java
package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "patient_expert_assignments")
public class PatientExpertAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_profile_id", nullable = false)
    private StaffProfile staffProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_user_id")
    private User assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    public PatientExpertAssignment() {}

    public PatientExpertAssignment(PatientProfile patientProfile, StaffProfile staffProfile, User assignedBy) {
        this.patientProfile = patientProfile;
        this.staffProfile = staffProfile;
        this.assignedBy = assignedBy;
    }

    public boolean isActive() { return endedAt == null; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public PatientProfile getPatientProfile() { return patientProfile; }
    public void setPatientProfile(PatientProfile patientProfile) { this.patientProfile = patientProfile; }
    public StaffProfile getStaffProfile() { return staffProfile; }
    public void setStaffProfile(StaffProfile staffProfile) { this.staffProfile = staffProfile; }
    public User getAssignedBy() { return assignedBy; }
    public void setAssignedBy(User assignedBy) { this.assignedBy = assignedBy; }
    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
}
```

Create `src/main/java/com/metabion/domain/PatientCohortMembership.java`:

```java
package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "patient_cohort_memberships")
public class PatientCohortMembership {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cohort_id", nullable = false)
    private Cohort cohort;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_user_id")
    private User assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    public PatientCohortMembership() {}

    public PatientCohortMembership(PatientProfile patientProfile, Cohort cohort, User assignedBy) {
        this.patientProfile = patientProfile;
        this.cohort = cohort;
        this.assignedBy = assignedBy;
    }

    public boolean isActive() { return endedAt == null; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public PatientProfile getPatientProfile() { return patientProfile; }
    public void setPatientProfile(PatientProfile patientProfile) { this.patientProfile = patientProfile; }
    public Cohort getCohort() { return cohort; }
    public void setCohort(Cohort cohort) { this.cohort = cohort; }
    public User getAssignedBy() { return assignedBy; }
    public void setAssignedBy(User assignedBy) { this.assignedBy = assignedBy; }
    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
}
```

Create `src/main/java/com/metabion/domain/CohortStaffAssignment.java`:

```java
package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "cohort_staff_assignments")
public class CohortStaffAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cohort_id", nullable = false)
    private Cohort cohort;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_profile_id", nullable = false)
    private StaffProfile staffProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_user_id")
    private User assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    public CohortStaffAssignment() {}

    public CohortStaffAssignment(Cohort cohort, StaffProfile staffProfile, User assignedBy) {
        this.cohort = cohort;
        this.staffProfile = staffProfile;
        this.assignedBy = assignedBy;
    }

    public boolean isActive() { return endedAt == null; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Cohort getCohort() { return cohort; }
    public void setCohort(Cohort cohort) { this.cohort = cohort; }
    public StaffProfile getStaffProfile() { return staffProfile; }
    public void setStaffProfile(StaffProfile staffProfile) { this.staffProfile = staffProfile; }
    public User getAssignedBy() { return assignedBy; }
    public void setAssignedBy(User assignedBy) { this.assignedBy = assignedBy; }
    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
}
```

- [ ] **Step 6: Add repositories**

Create repository interfaces:

```java
package com.metabion.repository;

import com.metabion.domain.PatientProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PatientProfileRepository extends JpaRepository<PatientProfile, Long> {
    Optional<PatientProfile> findByUserId(Long userId);
}
```

```java
package com.metabion.repository;

import com.metabion.domain.StaffProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, Long> {
    Optional<StaffProfile> findByUserId(Long userId);
}
```

```java
package com.metabion.repository;

import com.metabion.domain.Cohort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CohortRepository extends JpaRepository<Cohort, Long> {}
```

For active predicates, create explicit JPQL queries. Example for direct assignments:

```java
package com.metabion.repository;

import com.metabion.domain.PatientExpertAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientExpertAssignmentRepository extends JpaRepository<PatientExpertAssignment, Long> {
    @Query("""
            select count(a) > 0
            from PatientExpertAssignment a
            where a.patientProfile.id = :patientProfileId
              and a.staffProfile.id = :staffProfileId
              and a.endedAt is null
            """)
    boolean existsActiveAssignment(@Param("patientProfileId") Long patientProfileId,
                                   @Param("staffProfileId") Long staffProfileId);
}
```

Create `src/main/java/com/metabion/repository/PatientCohortMembershipRepository.java`:

```java
package com.metabion.repository;

import com.metabion.domain.PatientCohortMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientCohortMembershipRepository extends JpaRepository<PatientCohortMembership, Long> {
    @Query("""
            select count(m) > 0
            from PatientCohortMembership m
            where m.patientProfile.id = :patientProfileId
              and m.cohort.id = :cohortId
              and m.endedAt is null
            """)
    boolean existsActiveMembership(@Param("patientProfileId") Long patientProfileId,
                                   @Param("cohortId") Long cohortId);
}
```

Create `src/main/java/com/metabion/repository/CohortStaffAssignmentRepository.java`:

```java
package com.metabion.repository;

import com.metabion.domain.CohortStaffAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CohortStaffAssignmentRepository extends JpaRepository<CohortStaffAssignment, Long> {
    @Query("""
            select count(a) > 0
            from CohortStaffAssignment a
            where a.cohort.id = :cohortId
              and a.staffProfile.id = :staffProfileId
              and a.endedAt is null
            """)
    boolean existsActiveAssignment(@Param("cohortId") Long cohortId,
                                   @Param("staffProfileId") Long staffProfileId);

    @Query("""
            select count(csa) > 0
            from CohortStaffAssignment csa
            join PatientCohortMembership pcm on pcm.cohort = csa.cohort
            where pcm.patientProfile.id = :patientProfileId
              and csa.staffProfile.id = :staffProfileId
              and pcm.endedAt is null
              and csa.endedAt is null
            """)
    boolean existsActiveAssignmentForPatient(@Param("patientProfileId") Long patientProfileId,
                                             @Param("staffProfileId") Long staffProfileId);
}
```

- [ ] **Step 7: Run repository tests**

Run:

```bash
./gradlew test --tests com.metabion.repository.RbacAssignmentRepositoryTest
```

Expected: tests pass against Testcontainers PostgreSQL with Flyway migrations applied, including duplicate-active rejection from partial unique indexes.

- [ ] **Step 8: Commit schema and domain model**

```bash
git add src/main/resources/db/migration/V4__rbac_assignment_model.sql \
        src/main/java/com/metabion/domain/PatientProfile.java \
        src/main/java/com/metabion/domain/StaffProfile.java \
        src/main/java/com/metabion/domain/Cohort.java \
        src/main/java/com/metabion/domain/PatientCohortMembership.java \
        src/main/java/com/metabion/domain/PatientExpertAssignment.java \
        src/main/java/com/metabion/domain/CohortStaffAssignment.java \
        src/main/java/com/metabion/repository/PatientProfileRepository.java \
        src/main/java/com/metabion/repository/StaffProfileRepository.java \
        src/main/java/com/metabion/repository/CohortRepository.java \
        src/main/java/com/metabion/repository/PatientCohortMembershipRepository.java \
        src/main/java/com/metabion/repository/PatientExpertAssignmentRepository.java \
        src/main/java/com/metabion/repository/CohortStaffAssignmentRepository.java \
        src/test/java/com/metabion/repository/RbacAssignmentRepositoryTest.java
git commit -m "Add RBAC assignment persistence"
```

---

### Task 3: Patient Profile Creation During Registration

**Files:**

- Modify: `src/main/java/com/metabion/service/UserService.java`
- Test: `src/test/java/com/metabion/service/UserServiceTest.java`

- [ ] **Step 1: Add failing registration test**

In `src/test/java/com/metabion/service/UserServiceTest.java`, add imports:

```java
import com.metabion.domain.RoleName;
import com.metabion.repository.PatientProfileRepository;
```

Add this field next to the existing repository mocks:

```java
@Mock
private PatientProfileRepository patientProfiles;
```

Add this test method:

```java
@Test
void registerCreatesPatientProfileForNewPatient() {
    var req = new RegisterRequest("new@example.com", "correct horse battery staple");
    when(users.existsByEmail("new@example.com")).thenReturn(false);
    when(passwordEncoder.encode(req.password())).thenReturn("encoded");
    when(users.save(any(User.class))).thenAnswer(invocation -> {
        var user = invocation.<User>getArgument(0);
        user.setId(10L);
        return user;
    });

    userService.register(req);

    verify(patientProfiles).save(argThat(profile ->
            profile.getUser() != null
                    && "new@example.com".equals(profile.getUser().getEmail())
                    && profile.getUser().hasRole(RoleName.PATIENT)
    ));
}
```

- [ ] **Step 2: Run the focused service test and verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.UserServiceTest
```

Expected: compilation or injection failure because `UserService` does not accept `PatientProfileRepository`.

- [ ] **Step 3: Inject `PatientProfileRepository` into `UserService`**

Modify `src/main/java/com/metabion/service/UserService.java`:

```java
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.repository.PatientProfileRepository;
```

Add field:

```java
private final PatientProfileRepository patientProfiles;
```

Update constructor:

```java
public UserService(UserRepository users,
                   VerificationTokenRepository verifTokens,
                   PasswordResetRepository resetTokens,
                   EmailService emailService,
                   PasswordEncoder passwordEncoder,
                   FindByIndexNameSessionRepository<? extends Session> sessions,
                   PatientProfileRepository patientProfiles) {
    this.users = users;
    this.verifTokens = verifTokens;
    this.resetTokens = resetTokens;
    this.emailService = emailService;
    this.passwordEncoder = passwordEncoder;
    this.sessions = sessions;
    this.patientProfiles = patientProfiles;
}
```

Replace:

```java
private static final String DEFAULT_USER_ROLE = "PATIENT";
```

with:

```java
private static final RoleName DEFAULT_USER_ROLE = RoleName.PATIENT;
```

After `users.save(user);`, add:

```java
patientProfiles.save(new PatientProfile(user));
```

- [ ] **Step 4: Update tests for constructor injection**

Any `new UserService(...)` calls in tests must pass `patientProfiles` as the last constructor argument. Classes using `@InjectMocks` should work after adding the mock.

- [ ] **Step 5: Run registration tests**

Run:

```bash
./gradlew test --tests com.metabion.service.UserServiceTest
./gradlew test --tests com.metabion.integration.AuthFlowIT
```

Expected: both pass.

- [ ] **Step 6: Commit patient profile registration**

```bash
git add src/main/java/com/metabion/service/UserService.java \
        src/test/java/com/metabion/service/UserServiceTest.java
git commit -m "Create patient profiles on registration"
```

---

### Task 4: Access Control Service

**Files:**

- Create: `src/main/java/com/metabion/service/AccessControlService.java`
- Test: `src/test/java/com/metabion/service/AccessControlServiceTest.java`

- [ ] **Step 1: Write failing access matrix tests**

Create `src/test/java/com/metabion/service/AccessControlServiceTest.java`:

```java
package com.metabion.service;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.repository.CohortStaffAssignmentRepository;
import com.metabion.repository.PatientExpertAssignmentRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessControlServiceTest {

    @Mock UserRepository users;
    @Mock PatientProfileRepository patientProfiles;
    @Mock StaffProfileRepository staffProfiles;
    @Mock PatientExpertAssignmentRepository directAssignments;
    @Mock CohortStaffAssignmentRepository cohortStaffAssignments;

    private AccessControlService access;

    @BeforeEach
    void setUp() {
        access = new AccessControlService(users, patientProfiles, staffProfiles, directAssignments, cohortStaffAssignments);
    }

    @Test
    void patientCanAccessOwnProfileOnly() {
        var patient = user(1L, "patient@example.com", RoleName.PATIENT);
        var ownProfile = patientProfile(100L, patient);
        var otherProfile = patientProfile(101L, user(2L, "other@example.com", RoleName.PATIENT));
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        when(patientProfiles.findById(100L)).thenReturn(Optional.of(ownProfile));
        when(patientProfiles.findById(101L)).thenReturn(Optional.of(otherProfile));

        assertThat(access.canAccessPatientProfile(auth("patient@example.com"), 100L)).isTrue();
        assertThat(access.canAccessPatientProfile(auth("patient@example.com"), 101L)).isFalse();
    }

    @Test
    void staffCanAccessDirectlyAssignedPatient() {
        var staffUser = user(3L, "physician@example.com", RoleName.PHYSICIAN);
        var staffProfile = staffProfile(30L, staffUser);
        when(users.findByEmail("physician@example.com")).thenReturn(Optional.of(staffUser));
        when(staffProfiles.findByUserId(3L)).thenReturn(Optional.of(staffProfile));
        when(directAssignments.existsActiveAssignment(100L, 30L)).thenReturn(true);

        assertThat(access.canAccessPatientProfile(auth("physician@example.com"), 100L)).isTrue();
    }

    @Test
    void staffCanAccessPatientThroughAssignedCohort() {
        var staffUser = user(4L, "coordinator@example.com", RoleName.COORDINATOR);
        var staffProfile = staffProfile(40L, staffUser);
        when(users.findByEmail("coordinator@example.com")).thenReturn(Optional.of(staffUser));
        when(staffProfiles.findByUserId(4L)).thenReturn(Optional.of(staffProfile));
        when(directAssignments.existsActiveAssignment(100L, 40L)).thenReturn(false);
        when(cohortStaffAssignments.existsActiveAssignmentForPatient(100L, 40L)).thenReturn(true);

        assertThat(access.canAccessPatientProfile(auth("coordinator@example.com"), 100L)).isTrue();
    }

    @Test
    void staffCannotAccessUnassignedPatient() {
        var staffUser = user(5L, "nutrition@example.com", RoleName.NUTRITION_SPECIALIST);
        var staffProfile = staffProfile(50L, staffUser);
        when(users.findByEmail("nutrition@example.com")).thenReturn(Optional.of(staffUser));
        when(staffProfiles.findByUserId(5L)).thenReturn(Optional.of(staffProfile));
        when(directAssignments.existsActiveAssignment(100L, 50L)).thenReturn(false);
        when(cohortStaffAssignments.existsActiveAssignmentForPatient(100L, 50L)).thenReturn(false);

        assertThat(access.canAccessPatientProfile(auth("nutrition@example.com"), 100L)).isFalse();
    }

    @Test
    void adminCanAccessAllPatientsAndCohorts() {
        var admin = user(6L, "admin@example.com", RoleName.ADMIN);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));

        assertThat(access.canAccessPatientProfile(auth("admin@example.com"), 999L)).isTrue();
        assertThat(access.canAccessCohort(auth("admin@example.com"), 888L)).isTrue();
        assertThat(access.canManageCohort(auth("admin@example.com"), 888L)).isTrue();
        assertThat(access.canManageAssignments(auth("admin@example.com"), 888L)).isTrue();
    }

    @Test
    void coordinatorCanManageAssignedCohortButPhysicianCannot() {
        var coordinator = user(7L, "coord@example.com", RoleName.COORDINATOR);
        var physician = user(8L, "doc@example.com", RoleName.PHYSICIAN);
        var coordinatorProfile = staffProfile(70L, coordinator);
        var physicianProfile = staffProfile(80L, physician);
        when(users.findByEmail("coord@example.com")).thenReturn(Optional.of(coordinator));
        when(users.findByEmail("doc@example.com")).thenReturn(Optional.of(physician));
        when(staffProfiles.findByUserId(7L)).thenReturn(Optional.of(coordinatorProfile));
        when(staffProfiles.findByUserId(8L)).thenReturn(Optional.of(physicianProfile));
        when(cohortStaffAssignments.existsActiveAssignment(200L, 70L)).thenReturn(true);
        when(cohortStaffAssignments.existsActiveAssignment(200L, 80L)).thenReturn(true);

        assertThat(access.canAccessCohort(auth("coord@example.com"), 200L)).isTrue();
        assertThat(access.canManageCohort(auth("coord@example.com"), 200L)).isTrue();
        assertThat(access.canManageAssignments(auth("coord@example.com"), 200L)).isTrue();
        assertThat(access.canAccessCohort(auth("doc@example.com"), 200L)).isTrue();
        assertThat(access.canManageCohort(auth("doc@example.com"), 200L)).isFalse();
        assertThat(access.canManageAssignments(auth("doc@example.com"), 200L)).isFalse();
    }

    private Authentication auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, "n/a");
    }

    private User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        user.setId(id);
        user.setEnabled(true);
        user.addRole(role);
        return user;
    }

    private PatientProfile patientProfile(Long id, User user) {
        var profile = new PatientProfile(user);
        profile.setId(id);
        return profile;
    }

    private StaffProfile staffProfile(Long id, User user) {
        var profile = new StaffProfile(user);
        profile.setId(id);
        return profile;
    }
}
```

- [ ] **Step 2: Run access tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.service.AccessControlServiceTest
```

Expected: compilation fails because `AccessControlService` does not exist.

- [ ] **Step 3: Implement `AccessControlService`**

Create `src/main/java/com/metabion/service/AccessControlService.java`:

```java
package com.metabion.service;

import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.repository.CohortStaffAssignmentRepository;
import com.metabion.repository.PatientExpertAssignmentRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class AccessControlService {

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final StaffProfileRepository staffProfiles;
    private final PatientExpertAssignmentRepository directAssignments;
    private final CohortStaffAssignmentRepository cohortStaffAssignments;

    public AccessControlService(UserRepository users,
                                PatientProfileRepository patientProfiles,
                                StaffProfileRepository staffProfiles,
                                PatientExpertAssignmentRepository directAssignments,
                                CohortStaffAssignmentRepository cohortStaffAssignments) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.staffProfiles = staffProfiles;
        this.directAssignments = directAssignments;
        this.cohortStaffAssignments = cohortStaffAssignments;
    }

    public boolean canAccessPatientProfile(Authentication authentication, Long patientProfileId) {
        return currentUser(authentication)
                .map(user -> canAccessPatientProfile(user, patientProfileId))
                .orElse(false);
    }

    public boolean canAccessCohort(Authentication authentication, Long cohortId) {
        return currentUser(authentication)
                .map(user -> user.hasRole(RoleName.ADMIN) || activeStaffCohortAssignment(user, cohortId))
                .orElse(false);
    }

    public boolean canManageCohort(Authentication authentication, Long cohortId) {
        return currentUser(authentication)
                .map(user -> user.hasRole(RoleName.ADMIN)
                        || (user.hasRole(RoleName.COORDINATOR) && activeStaffCohortAssignment(user, cohortId)))
                .orElse(false);
    }

    public boolean canManageAssignments(Authentication authentication, Long cohortId) {
        return canManageCohort(authentication, cohortId);
    }

    private boolean canAccessPatientProfile(User user, Long patientProfileId) {
        if (user.hasRole(RoleName.ADMIN)) {
            return true;
        }

        if (user.hasRole(RoleName.PATIENT) && ownsPatientProfile(user, patientProfileId)) {
            return true;
        }

        if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.COORDINATOR)) {
            return false;
        }

        return staffProfiles.findByUserId(user.getId())
                .map(staffProfile -> directAssignments.existsActiveAssignment(patientProfileId, staffProfile.getId())
                        || cohortStaffAssignments.existsActiveAssignmentForPatient(patientProfileId, staffProfile.getId()))
                .orElse(false);
    }

    private boolean ownsPatientProfile(User user, Long patientProfileId) {
        return patientProfiles.findById(patientProfileId)
                .map(profile -> profile.getUser().getId().equals(user.getId()))
                .orElse(false);
    }

    private boolean activeStaffCohortAssignment(User user, Long cohortId) {
        if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.COORDINATOR)) {
            return false;
        }
        return staffProfiles.findByUserId(user.getId())
                .map(staffProfile -> cohortStaffAssignments.existsActiveAssignment(cohortId, staffProfile.getId()))
                .orElse(false);
    }

    private Optional<User> currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return users.findByEmail(authentication.getName());
    }
}
```

- [ ] **Step 4: Run access service tests**

Run:

```bash
./gradlew test --tests com.metabion.service.AccessControlServiceTest
```

Expected: pass.

- [ ] **Step 5: Commit access service**

```bash
git add src/main/java/com/metabion/service/AccessControlService.java \
        src/test/java/com/metabion/service/AccessControlServiceTest.java
git commit -m "Add RBAC access control service"
```

---

### Task 5: Expose Roles in Current User Responses

**Files:**

- Modify: `src/main/java/com/metabion/controller/AuthController.java`
- Modify: `src/main/java/com/metabion/controller/WebAuthController.java`
- Test: `src/test/java/com/metabion/controller/AuthControllerTest.java`
- Test: `src/test/java/com/metabion/controller/WebAuthControllerTest.java`

- [ ] **Step 1: Add failing current-user role assertions**

In `AuthControllerTest`, add or update a `/api/auth/me` test so authenticated output includes roles:

```java
mockMvc.perform(get("/api/auth/me")
                .with(user("patient@example.com").roles("PATIENT")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("patient@example.com"))
        .andExpect(jsonPath("$.roles[0]").value("PATIENT"));
```

In `WebAuthControllerTest`, add or update the `/app` test so the model contains roles:

```java
mockMvc.perform(get("/app")
                .with(user("staff@example.com").roles("PHYSICIAN", "COORDINATOR")))
        .andExpect(status().isOk())
        .andExpect(model().attribute("email", "staff@example.com"))
        .andExpect(model().attribute("roles", java.util.List.of("COORDINATOR", "PHYSICIAN")));
```

- [ ] **Step 2: Run controller tests and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.AuthControllerTest --tests com.metabion.controller.WebAuthControllerTest
```

Expected: role assertions fail because roles are not returned or rendered yet.

- [ ] **Step 3: Update `/api/auth/me`**

In `src/main/java/com/metabion/controller/AuthController.java`, replace the current `me(...)` method with:

```java
@GetMapping("/me")
public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal org.springframework.security.core.userdetails.User principal) {
    if (principal == null) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    var roles = principal.getAuthorities().stream()
            .map(org.springframework.security.core.GrantedAuthority::getAuthority)
            .filter(authority -> authority.startsWith("ROLE_"))
            .map(authority -> authority.substring("ROLE_".length()))
            .sorted()
            .toList();
    return ResponseEntity.ok(Map.of("email", principal.getUsername(), "roles", roles));
}
```

- [ ] **Step 4: Update `/app` role model**

In `src/main/java/com/metabion/controller/WebAuthController.java`, replace:

```java
model.addAttribute("roles", List.of());
```

with:

```java
var roles = authentication.getAuthorities().stream()
        .map(org.springframework.security.core.GrantedAuthority::getAuthority)
        .filter(authority -> authority.startsWith("ROLE_"))
        .map(authority -> authority.substring("ROLE_".length()))
        .sorted()
        .toList();
model.addAttribute("roles", roles);
```

Keep the existing `java.util.List` import if still used elsewhere; remove it if the IDE marks it unused.

- [ ] **Step 5: Run controller tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.AuthControllerTest --tests com.metabion.controller.WebAuthControllerTest
```

Expected: pass.

- [ ] **Step 6: Commit role exposure**

```bash
git add src/main/java/com/metabion/controller/AuthController.java \
        src/main/java/com/metabion/controller/WebAuthController.java \
        src/test/java/com/metabion/controller/AuthControllerTest.java \
        src/test/java/com/metabion/controller/WebAuthControllerTest.java
git commit -m "Expose current user roles"
```

---

### Task 6: Full Verification and Migration Check

**Files:**

- Verify: all production files, tests, and migrations changed by Tasks 1-5.

- [ ] **Step 1: Run full test suite**

Run:

```bash
./gradlew test
```

Expected: all tests pass and Jacoco report is generated.

- [ ] **Step 2: Run project build through IDEA MCP if available**

Use IDEA MCP `build_project` for `/home/petr/IdeaProjects/Metabion`.

Expected: build completes without errors.

- [ ] **Step 3: Inspect final diff**

Run:

```bash
git status --short
git diff --stat HEAD
```

Expected: no uncommitted production/test changes except intentionally untracked local files such as `.superpowers/`.

- [ ] **Step 4: Commit any final fixes**

If Step 1 or Step 2 required fixes, commit them:

```bash
git add src/main/java src/test/java src/main/resources/db/migration
git commit -m "Verify RBAC assignment model"
```

If no fixes were needed, do not create an empty commit.
