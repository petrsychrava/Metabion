package com.metabion.repository;

import com.metabion.domain.Cohort;
import com.metabion.domain.CohortStaffAssignment;
import com.metabion.domain.PatientCohortMembership;
import com.metabion.domain.PatientExpertAssignment;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RbacAssignmentRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    UserRepository users;

    @Autowired
    PatientProfileRepository patientProfiles;

    @Autowired
    StaffProfileRepository staffProfiles;

    @Autowired
    CohortRepository cohorts;

    @Autowired
    PatientExpertAssignmentRepository patientExpertAssignments;

    @Autowired
    PatientCohortMembershipRepository patientCohortMemberships;

    @Autowired
    CohortStaffAssignmentRepository cohortStaffAssignments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void profileRowsAreFoundByUserId() {
        var patientUser = createUser("patient@example.com", RoleName.PATIENT);
        var staffUser = createUser("staff@example.com", RoleName.PHYSICIAN);
        var patientProfile = patientProfiles.saveAndFlush(new PatientProfile(patientUser));
        var staffProfile = staffProfiles.saveAndFlush(new StaffProfile(staffUser));

        assertThat(patientProfiles.findByUserId(patientUser.getId()))
                .contains(patientProfile);
        assertThat(staffProfiles.findByUserId(staffUser.getId()))
                .contains(staffProfile);
    }

    @Test
    void patientProfileRejectsUserWithoutPatientRole() {
        var staffUser = createUser("patient-profile-staff@example.com", RoleName.PHYSICIAN);
        var profile = new PatientProfile();

        assertThatThrownBy(() -> new PatientProfile(staffUser))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile.setUser(staffUser))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile.setUser(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void staffProfileRejectsUserWithoutClinicalStaffRole() {
        var patientUser = createUser("staff-profile-patient@example.com", RoleName.PATIENT);
        var adminUser = createUser("staff-profile-admin@example.com", RoleName.ADMIN);
        var profile = new StaffProfile();

        assertThatThrownBy(() -> new StaffProfile(patientUser))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile.setUser(adminUser))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile.setUser(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void databaseRejectsProfileRowsForUsersWithoutRequiredRole() {
        var patientOnly = createUser("db-profile-patient@example.com", RoleName.PATIENT);
        var staffOnly = createUser("db-profile-staff@example.com", RoleName.PHYSICIAN);
        var adminOnly = createUser("db-profile-admin@example.com", RoleName.ADMIN);

        assertThatThrownBy(() -> jdbc.update(
                "insert into patient_profiles (user_id) values (?)", staffOnly.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into staff_profiles (user_id) values (?)", patientOnly.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into staff_profiles (user_id) values (?)", adminOnly.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void databaseRejectsProfileUpdatesThatWouldUseInvalidRole() {
        var patient = createUser("db-profile-update-patient@example.com", RoleName.PATIENT);
        var staff = createUser("db-profile-update-staff@example.com", RoleName.PHYSICIAN);
        var admin = createUser("db-profile-update-admin@example.com", RoleName.ADMIN);

        jdbc.update("insert into patient_profiles (user_id) values (?)", patient.getId());
        jdbc.update("insert into staff_profiles (user_id) values (?)", staff.getId());

        assertThatThrownBy(() -> jdbc.update(
                "update patient_profiles set user_id = ? where user_id = ?", staff.getId(), patient.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update staff_profiles set user_id = ? where user_id = ?", admin.getId(), staff.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void databaseRejectsRoleChangesThatWouldInvalidateExistingProfiles() {
        var patient = createUser("db-role-guard-patient@example.com", RoleName.PATIENT);
        var staff = createUser("db-role-guard-staff@example.com", RoleName.PHYSICIAN);

        jdbc.update("insert into patient_profiles (user_id) values (?)", patient.getId());
        jdbc.update("insert into staff_profiles (user_id) values (?)", staff.getId());

        assertThatThrownBy(() -> jdbc.update(
                "delete from user_roles where user_id = ? and role = ?", patient.getId(), RoleName.PATIENT.name()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update user_roles set role = ? where user_id = ? and role = ?",
                RoleName.ADMIN.name(), staff.getId(), RoleName.PHYSICIAN.name()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void roleAssignmentsReferenceSeededRoleRows() {
        assertThat(jdbc.queryForObject("select count(*) from roles", Long.class))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "select clinical_staff from roles where code = ?",
                Boolean.class,
                RoleName.PHYSICIAN.name()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "select clinical_staff from roles where code = ?",
                Boolean.class,
                RoleName.ADMIN.name()))
                .isFalse();

        var user = createUser("unknown-role@example.com", RoleName.PATIENT);

        assertThatThrownBy(() -> jdbc.update(
                "insert into user_roles (user_id, role) values (?, ?)",
                user.getId(),
                "UNKNOWN_ROLE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staffProfileRoleValidationUsesRolesMetadata() {
        jdbc.update("""
                insert into roles (code, clinical_staff)
                values (?, true)
                """, "DIETITIAN");
        var userId = jdbc.queryForObject(
                "insert into users (email, password_hash, enabled) values (?, ?, true) returning id",
                Long.class,
                "dietitian@example.com",
                "hash");
        jdbc.update("insert into user_roles (user_id, role) values (?, ?)", userId, "DIETITIAN");

        jdbc.update("insert into staff_profiles (user_id) values (?)", userId);

        assertThat(jdbc.queryForObject(
                "select count(*) from staff_profiles where user_id = ?",
                Long.class,
                userId))
                .isOne();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletingPatientUserCascadesProfileAndRoles() {
        var patient = createUser("delete-patient-profile@example.com", RoleName.PATIENT);
        var patientId = patient.getId();
        patientProfiles.saveAndFlush(new PatientProfile(patient));
        entityManager.clear();

        users.deleteById(patientId);
        users.flush();

        assertThat(users.findById(patientId)).isEmpty();
        assertThat(patientProfiles.findByUserId(patientId)).isEmpty();
        assertThat(countRoles(patientId)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletingStaffUserCascadesProfileAndRoles() {
        var staff = createUser("delete-staff-profile@example.com", RoleName.PHYSICIAN);
        var staffId = staff.getId();
        staffProfiles.saveAndFlush(new StaffProfile(staff));
        entityManager.clear();

        users.deleteById(staffId);
        users.flush();

        assertThat(users.findById(staffId)).isEmpty();
        assertThat(staffProfiles.findByUserId(staffId)).isEmpty();
        assertThat(countRoles(staffId)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletingUserWithLoadedRolesCascadesProfileAndRoles() {
        var patient = createUser("delete-loaded-patient-profile@example.com", RoleName.PATIENT);
        var patientId = patient.getId();
        patientProfiles.saveAndFlush(new PatientProfile(patient));
        entityManager.clear();

        var loaded = users.findByEmail("delete-loaded-patient-profile@example.com").orElseThrow();
        assertThat(loaded.roleNames()).containsExactly(RoleName.PATIENT.name());

        users.delete(loaded);

        assertThat(users.findById(patientId)).isEmpty();
        assertThat(patientProfiles.findByUserId(patientId)).isEmpty();
        assertThat(countRoles(patientId)).isZero();
    }

    @Test
    void activeDirectAssignmentPredicateHonorsEndedAt() {
        var patient = createPatientProfile("direct-patient@example.com");
        var staff = createStaffProfile("direct-staff@example.com");
        var assignedBy = createUser("direct-admin@example.com", RoleName.ADMIN);
        var assignment = patientExpertAssignments.saveAndFlush(
                new PatientExpertAssignment(patient, staff, assignedBy));

        assertThat(patientExpertAssignments.existsActiveAssignment(patient.getId(), staff.getId()))
                .isTrue();

        assignment.setEndedAt(Instant.now());
        patientExpertAssignments.saveAndFlush(assignment);

        assertThat(patientExpertAssignments.existsActiveAssignment(patient.getId(), staff.getId()))
                .isFalse();
    }

    @Test
    void activeCohortAccessRequiresActiveMembershipAndActiveStaffAssignment() {
        var patient = createPatientProfile("cohort-patient@example.com");
        var staff = createStaffProfile("cohort-staff@example.com");
        var assignedBy = createUser("cohort-admin@example.com", RoleName.ADMIN);
        var cohort = cohorts.saveAndFlush(new Cohort("Spring cohort"));
        var membership = patientCohortMemberships.saveAndFlush(
                new PatientCohortMembership(patient, cohort, assignedBy));
        var staffAssignment = cohortStaffAssignments.saveAndFlush(
                new CohortStaffAssignment(cohort, staff, assignedBy));

        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(patient.getId(), staff.getId()))
                .isTrue();
        assertThat(patientCohortMemberships.existsActiveMembership(patient.getId(), cohort.getId()))
                .isTrue();
        assertThat(cohortStaffAssignments.existsActiveAssignment(cohort.getId(), staff.getId()))
                .isTrue();

        membership.setEndedAt(Instant.now());
        patientCohortMemberships.saveAndFlush(membership);

        assertThat(patientCohortMemberships.existsActiveMembership(patient.getId(), cohort.getId()))
                .isFalse();
        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(patient.getId(), staff.getId()))
                .isFalse();

        membership.setEndedAt(null);
        patientCohortMemberships.saveAndFlush(membership);
        staffAssignment.setEndedAt(Instant.now());
        cohortStaffAssignments.saveAndFlush(staffAssignment);

        assertThat(cohortStaffAssignments.existsActiveAssignment(cohort.getId(), staff.getId()))
                .isFalse();
        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(patient.getId(), staff.getId()))
                .isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duplicateActiveAssignmentsAreRejected() {
        var patient = createPatientProfile("duplicate-patient@example.com");
        var staff = createStaffProfile("duplicate-staff@example.com");
        var assignedBy = createUser("duplicate-admin@example.com", RoleName.ADMIN);
        var cohort = cohorts.saveAndFlush(new Cohort("Duplicate cohort"));

        patientExpertAssignments.saveAndFlush(new PatientExpertAssignment(patient, staff, assignedBy));
        assertThatThrownBy(() -> patientExpertAssignments.saveAndFlush(
                new PatientExpertAssignment(patient, staff, assignedBy)))
                .isInstanceOf(DataIntegrityViolationException.class);

        patientCohortMemberships.saveAndFlush(new PatientCohortMembership(patient, cohort, assignedBy));
        assertThatThrownBy(() -> patientCohortMemberships.saveAndFlush(
                new PatientCohortMembership(patient, cohort, assignedBy)))
                .isInstanceOf(DataIntegrityViolationException.class);

        cohortStaffAssignments.saveAndFlush(new CohortStaffAssignment(cohort, staff, assignedBy));
        assertThatThrownBy(() -> cohortStaffAssignments.saveAndFlush(
                new CohortStaffAssignment(cohort, staff, assignedBy)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void endedAssignmentsAllowNewActiveRowsForSameScope() {
        var patient = createPatientProfile("reassign-patient@example.com");
        var staff = createStaffProfile("reassign-staff@example.com");
        var assignedBy = createUser("reassign-admin@example.com", RoleName.ADMIN);
        var cohort = cohorts.saveAndFlush(new Cohort("Reassignment cohort"));

        var directAssignment = patientExpertAssignments.saveAndFlush(
                new PatientExpertAssignment(patient, staff, assignedBy));
        directAssignment.setEndedAt(Instant.now());
        patientExpertAssignments.saveAndFlush(directAssignment);

        assertThat(patientExpertAssignments.saveAndFlush(
                new PatientExpertAssignment(patient, staff, assignedBy)).isActive())
                .isTrue();

        var membership = patientCohortMemberships.saveAndFlush(
                new PatientCohortMembership(patient, cohort, assignedBy));
        membership.setEndedAt(Instant.now());
        patientCohortMemberships.saveAndFlush(membership);

        assertThat(patientCohortMemberships.saveAndFlush(
                new PatientCohortMembership(patient, cohort, assignedBy)).isActive())
                .isTrue();

        var staffAssignment = cohortStaffAssignments.saveAndFlush(
                new CohortStaffAssignment(cohort, staff, assignedBy));
        staffAssignment.setEndedAt(Instant.now());
        cohortStaffAssignments.saveAndFlush(staffAssignment);

        assertThat(cohortStaffAssignments.saveAndFlush(
                new CohortStaffAssignment(cohort, staff, assignedBy)).isActive())
                .isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void assignmentIntervalsRejectEndingBeforeAssignment() {
        var patient = createPatientProfile("interval-patient@example.com");
        var staff = createStaffProfile("interval-staff@example.com");
        var assignedBy = createUser("interval-admin@example.com", RoleName.ADMIN);
        var cohort = cohorts.saveAndFlush(new Cohort("Interval cohort"));
        var assignedAt = Instant.parse("2026-05-31T10:00:00Z");
        var endedAt = assignedAt.minusSeconds(1);

        var directAssignment = new PatientExpertAssignment(patient, staff, assignedBy);
        directAssignment.setAssignedAt(assignedAt);
        directAssignment.setEndedAt(endedAt);
        assertThatThrownBy(() -> patientExpertAssignments.saveAndFlush(directAssignment))
                .isInstanceOf(DataIntegrityViolationException.class);

        var membership = new PatientCohortMembership(patient, cohort, assignedBy);
        membership.setAssignedAt(assignedAt);
        membership.setEndedAt(endedAt);
        assertThatThrownBy(() -> patientCohortMemberships.saveAndFlush(membership))
                .isInstanceOf(DataIntegrityViolationException.class);

        var staffAssignment = new CohortStaffAssignment(cohort, staff, assignedBy);
        staffAssignment.setAssignedAt(assignedAt);
        staffAssignment.setEndedAt(endedAt);
        assertThatThrownBy(() -> cohortStaffAssignments.saveAndFlush(staffAssignment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private PatientProfile createPatientProfile(String email) {
        return patientProfiles.saveAndFlush(new PatientProfile(createUser(email, RoleName.PATIENT)));
    }

    private StaffProfile createStaffProfile(String email) {
        return staffProfiles.saveAndFlush(new StaffProfile(createUser(email, RoleName.PHYSICIAN)));
    }

    private User createUser(String email, RoleName role) {
        var user = users.saveAndFlush(new User(email, "hash"));
        user.addRole(role);
        return users.saveAndFlush(user);
    }

    private Long countRoles(Long userId) {
        return jdbc.queryForObject("select count(*) from user_roles where user_id = ?", Long.class, userId);
    }
}
