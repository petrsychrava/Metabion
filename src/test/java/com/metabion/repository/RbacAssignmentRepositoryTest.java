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

        assignment.end(assignedBy, Instant.now());
        patientExpertAssignments.saveAndFlush(assignment);

        assertThat(patientExpertAssignments.existsActiveAssignment(patient.getId(), staff.getId()))
                .isFalse();
    }

    @Test
    void activeCohortAccessRequiresActiveMembershipAndActiveStaffAssignment() {
        var patient = createPatientProfile("cohort-patient@example.com");
        var staff = createStaffProfile("cohort-staff@example.com");
        var assignedBy = createUser("cohort-admin@example.com", RoleName.ADMIN);
        var cohort = cohorts.saveAndFlush(new Cohort("Spring cohort", null, assignedBy));
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

        membership.end(assignedBy, Instant.now());
        patientCohortMemberships.saveAndFlush(membership);

        assertThat(patientCohortMemberships.existsActiveMembership(patient.getId(), cohort.getId()))
                .isFalse();
        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(patient.getId(), staff.getId()))
                .isFalse();

        patientCohortMemberships.saveAndFlush(
                new PatientCohortMembership(patient, cohort, assignedBy));
        staffAssignment.end(assignedBy, Instant.now());
        cohortStaffAssignments.saveAndFlush(staffAssignment);

        assertThat(cohortStaffAssignments.existsActiveAssignment(cohort.getId(), staff.getId()))
                .isFalse();
        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(patient.getId(), staff.getId()))
                .isFalse();
    }

    @Test
    void archivedCohortDoesNotGrantPatientAccessThroughOpenRelationships() {
        var patient = createPatientProfile("archived-cohort-patient@example.com");
        var staff = createStaffProfile("archived-cohort-staff@example.com");
        var assignedBy = createUser("archived-cohort-admin@example.com", RoleName.ADMIN);
        var cohort = cohorts.saveAndFlush(new Cohort("Archived cohort", null, assignedBy));
        patientCohortMemberships.saveAndFlush(new PatientCohortMembership(patient, cohort, assignedBy));
        cohortStaffAssignments.saveAndFlush(new CohortStaffAssignment(cohort, staff, assignedBy));

        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(patient.getId(), staff.getId()))
                .isTrue();
        assertThat(patientProfiles.findAccessiblePatientOptionsForStaff(staff.getId()))
                .extracting(option -> option.id())
                .contains(patient.getId());

        cohort.archive(assignedBy, Instant.parse("2026-07-18T12:00:00Z"));
        cohorts.saveAndFlush(cohort);
        entityManager.clear();

        assertThat(cohortStaffAssignments.existsActiveAssignmentForPatient(patient.getId(), staff.getId()))
                .isFalse();
        assertThat(patientProfiles.findAccessiblePatientOptionsForStaff(staff.getId()))
                .extracting(option -> option.id())
                .doesNotContain(patient.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duplicateActiveAssignmentsAreRejected() {
        var patient = createPatientProfile("duplicate-patient@example.com");
        var staff = createStaffProfile("duplicate-staff@example.com");
        var assignedBy = createUser("duplicate-admin@example.com", RoleName.ADMIN);
        var cohort = cohorts.saveAndFlush(new Cohort("Duplicate cohort", null, assignedBy));

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
        var cohort = cohorts.saveAndFlush(new Cohort("Reassignment cohort", null, assignedBy));

        var directAssignment = patientExpertAssignments.saveAndFlush(
                new PatientExpertAssignment(patient, staff, assignedBy));
        directAssignment.end(assignedBy, Instant.now());
        patientExpertAssignments.saveAndFlush(directAssignment);

        assertThat(patientExpertAssignments.saveAndFlush(
                new PatientExpertAssignment(patient, staff, assignedBy)).isActive())
                .isTrue();

        var membership = patientCohortMemberships.saveAndFlush(
                new PatientCohortMembership(patient, cohort, assignedBy));
        membership.end(assignedBy, Instant.now());
        patientCohortMemberships.saveAndFlush(membership);

        assertThat(patientCohortMemberships.saveAndFlush(
                new PatientCohortMembership(patient, cohort, assignedBy)).isActive())
                .isTrue();

        var staffAssignment = cohortStaffAssignments.saveAndFlush(
                new CohortStaffAssignment(cohort, staff, assignedBy));
        staffAssignment.end(assignedBy, Instant.now());
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
        var cohort = cohorts.saveAndFlush(new Cohort("Interval cohort", null, assignedBy));
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
        var assignedAt = Instant.parse("2026-07-18T10:00:00Z");
        var membership = new PatientCohortMembership(patient, cohort, actor);
        var direct = new PatientExpertAssignment(patient, staff, actor);
        var cohortStaff = new CohortStaffAssignment(cohort, staff, actor);
        membership.setAssignedAt(assignedAt);
        direct.setAssignedAt(assignedAt);
        cohortStaff.setAssignedAt(assignedAt);
        patientCohortMemberships.saveAndFlush(membership);
        patientExpertAssignments.saveAndFlush(direct);
        cohortStaffAssignments.saveAndFlush(cohortStaff);
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

    @Test
    void enabledCandidateQueriesExcludeDisabledUsersAndOrderByEmail() {
        var enabledPatientB = createPatientProfile("b@example.com");
        var enabledPatientA = createPatientProfile("a@example.com");
        var disabledPatient = patientProfiles.saveAndFlush(
                new PatientProfile(createDisabledUser("disabled-patient@example.com", RoleName.PATIENT)));
        var enabledStaffB = createStaffProfile("staff-b@example.com", RoleName.PHYSICIAN);
        var enabledStaffA = createStaffProfile("staff-a@example.com", RoleName.NUTRITION_SPECIALIST);
        var disabledStaff = staffProfiles.saveAndFlush(
                new StaffProfile(createDisabledUser("disabled-staff@example.com", RoleName.PHYSICIAN)));
        var assignedBy = createUser("candidate-admin@example.com", RoleName.ADMIN);
        patientExpertAssignments.saveAndFlush(
                new PatientExpertAssignment(enabledPatientB, enabledStaffB, assignedBy));
        patientExpertAssignments.saveAndFlush(
                new PatientExpertAssignment(enabledPatientA, enabledStaffB, assignedBy));
        patientExpertAssignments.saveAndFlush(
                new PatientExpertAssignment(disabledPatient, enabledStaffB, assignedBy));
        entityManager.clear();

        assertThat(patientProfiles.findAllEnabledPatientOptions())
                .filteredOn(option -> option.id().equals(enabledPatientA.getId())
                        || option.id().equals(enabledPatientB.getId())
                        || option.id().equals(disabledPatient.getId()))
                .extracting(option -> option.email())
                .containsExactly("a@example.com", "b@example.com");
        assertThat(patientProfiles.findAccessiblePatientOptionsForStaff(enabledStaffB.getId()))
                .extracting(option -> option.email())
                .containsExactly("a@example.com", "b@example.com");
        assertThat(staffProfiles.findAllEnabledWithRoles())
                .filteredOn(profile -> profile.getId().equals(enabledStaffA.getId())
                        || profile.getId().equals(enabledStaffB.getId())
                        || profile.getId().equals(disabledStaff.getId()))
                .extracting(profile -> profile.getUser().getEmail())
                .containsExactly("staff-a@example.com", "staff-b@example.com");
        assertThat(staffProfiles.findAllEnabledWithRoles())
                .allSatisfy(profile -> assertThat(profile.getUser().getRoles()).isNotEmpty());
        assertThat(staffProfiles.lockById(enabledStaffA.getId())).isPresent();
        assertThat(staffProfiles.lockById(disabledStaff.getId())).isPresent();
    }

    @Test
    void coordinatorScopedQueriesDeduplicateAcrossActiveCohortsAndExcludeArchivedData() {
        var coordinatorProfile = createStaffProfile("coordinator@example.com", RoleName.COORDINATOR);
        var assignedBy = createUser("scope-admin@example.com", RoleName.ADMIN);
        var alpha = cohorts.saveAndFlush(new Cohort("Alpha", null, assignedBy));
        var beta = cohorts.saveAndFlush(new Cohort("Beta", null, assignedBy));
        var archived = cohorts.saveAndFlush(new Cohort("Archived", null, assignedBy));
        var unassigned = cohorts.saveAndFlush(new Cohort("Gamma", null, assignedBy));
        var patientA = createPatientProfile("a@example.com");
        var patientB = createPatientProfile("b@example.com");
        var archivedPatient = createPatientProfile("archived@example.com");
        var disabledPatient = patientProfiles.saveAndFlush(
                new PatientProfile(createDisabledUser("disabled@example.com", RoleName.PATIENT)));
        cohortStaffAssignments.saveAndFlush(new CohortStaffAssignment(alpha, coordinatorProfile, assignedBy));
        cohortStaffAssignments.saveAndFlush(new CohortStaffAssignment(beta, coordinatorProfile, assignedBy));
        cohortStaffAssignments.saveAndFlush(new CohortStaffAssignment(archived, coordinatorProfile, assignedBy));
        patientCohortMemberships.saveAndFlush(new PatientCohortMembership(patientA, alpha, assignedBy));
        patientCohortMemberships.saveAndFlush(new PatientCohortMembership(patientA, beta, assignedBy));
        patientCohortMemberships.saveAndFlush(new PatientCohortMembership(patientB, beta, assignedBy));
        patientCohortMemberships.saveAndFlush(new PatientCohortMembership(archivedPatient, archived, assignedBy));
        patientCohortMemberships.saveAndFlush(new PatientCohortMembership(disabledPatient, alpha, assignedBy));
        archived.archive(assignedBy, Instant.parse("2026-07-18T12:00:00Z"));
        cohorts.saveAndFlush(archived);
        entityManager.clear();

        assertThat(patientProfiles.findEnabledPatientOptionsForStaff(coordinatorProfile.getId()))
                .extracting(option -> option.email())
                .containsExactly("a@example.com", "b@example.com");
        assertThat(cohorts.findActiveForStaff(coordinatorProfile.getId()))
                .extracting(Cohort::getName)
                .containsExactly("Alpha", "Beta");
        assertThat(cohorts.findAllActive())
                .filteredOn(cohort -> cohort.getId().equals(alpha.getId())
                        || cohort.getId().equals(beta.getId())
                        || cohort.getId().equals(archived.getId())
                        || cohort.getId().equals(unassigned.getId()))
                .extracting(Cohort::getName)
                .containsExactly("Alpha", "Beta", "Gamma");
        assertThat(cohorts.findAllForAdministration())
                .filteredOn(cohort -> cohort.getId().equals(alpha.getId())
                        || cohort.getId().equals(beta.getId())
                        || cohort.getId().equals(archived.getId())
                        || cohort.getId().equals(unassigned.getId()))
                .extracting(Cohort::getName)
                .containsExactly("Alpha", "Beta", "Gamma", "Archived");
        assertThat(cohorts.lockById(alpha.getId())).isPresent();
        assertThat(unassigned.getId()).isNotNull();
    }

    @Test
    void activeHistoryAndLockedRelationshipQueriesAreDeterministic() {
        var assignedBy = createUser("history-admin@example.com", RoleName.ADMIN);
        var cohort = cohorts.saveAndFlush(new Cohort("History", null, assignedBy));
        var patientA = createPatientProfile("history-a@example.com");
        var patientB = createPatientProfile("history-b@example.com");
        var staffA = createStaffProfile("history-staff-a@example.com", RoleName.PHYSICIAN);
        var staffB = createStaffProfile("history-staff-b@example.com", RoleName.NUTRITION_SPECIALIST);
        var earliest = Instant.parse("2026-07-18T09:00:00Z");
        var middle = Instant.parse("2026-07-18T10:00:00Z");
        var latest = Instant.parse("2026-07-18T11:00:00Z");

        var endedMembership = new PatientCohortMembership(patientB, cohort, assignedBy);
        endedMembership.setAssignedAt(earliest);
        endedMembership.end(assignedBy, middle);
        endedMembership = patientCohortMemberships.saveAndFlush(endedMembership);
        var activeMembershipB = new PatientCohortMembership(patientB, cohort, assignedBy);
        activeMembershipB.setAssignedAt(middle);
        activeMembershipB = patientCohortMemberships.saveAndFlush(activeMembershipB);
        var activeMembershipA = new PatientCohortMembership(patientA, cohort, assignedBy);
        activeMembershipA.setAssignedAt(latest);
        activeMembershipA = patientCohortMemberships.saveAndFlush(activeMembershipA);

        var endedDirect = new PatientExpertAssignment(patientA, staffB, assignedBy);
        endedDirect.setAssignedAt(earliest);
        endedDirect.end(assignedBy, middle);
        endedDirect = patientExpertAssignments.saveAndFlush(endedDirect);
        var activeDirectB = new PatientExpertAssignment(patientA, staffB, assignedBy);
        activeDirectB.setAssignedAt(middle);
        activeDirectB = patientExpertAssignments.saveAndFlush(activeDirectB);
        var activeDirectA = new PatientExpertAssignment(patientA, staffA, assignedBy);
        activeDirectA.setAssignedAt(latest);
        activeDirectA = patientExpertAssignments.saveAndFlush(activeDirectA);

        var endedCohortStaff = new CohortStaffAssignment(cohort, staffB, assignedBy);
        endedCohortStaff.setAssignedAt(earliest);
        endedCohortStaff.end(assignedBy, middle);
        endedCohortStaff = cohortStaffAssignments.saveAndFlush(endedCohortStaff);
        var activeCohortStaffB = new CohortStaffAssignment(cohort, staffB, assignedBy);
        activeCohortStaffB.setAssignedAt(middle);
        activeCohortStaffB = cohortStaffAssignments.saveAndFlush(activeCohortStaffB);
        var activeCohortStaffA = new CohortStaffAssignment(cohort, staffA, assignedBy);
        activeCohortStaffA.setAssignedAt(latest);
        activeCohortStaffA = cohortStaffAssignments.saveAndFlush(activeCohortStaffA);
        entityManager.clear();

        assertThat(patientCohortMemberships.findActiveByCohortId(cohort.getId()))
                .extracting(membership -> membership.getPatientProfile().getUser().getEmail())
                .containsExactly("history-a@example.com", "history-b@example.com");
        assertThat(patientCohortMemberships.findHistoryByCohortId(cohort.getId()))
                .extracting(PatientCohortMembership::getId)
                .containsExactly(activeMembershipA.getId(), activeMembershipB.getId(), endedMembership.getId());
        assertThat(patientCohortMemberships.findActiveById(activeMembershipA.getId())).isPresent();
        assertThat(patientCohortMemberships.findActiveById(endedMembership.getId())).isEmpty();

        assertThat(patientExpertAssignments.findActiveByPatientProfileId(patientA.getId()))
                .extracting(assignment -> assignment.getStaffProfile().getUser().getEmail())
                .containsExactly("history-staff-a@example.com", "history-staff-b@example.com");
        assertThat(patientExpertAssignments.findHistoryByPatientProfileId(patientA.getId()))
                .extracting(PatientExpertAssignment::getId)
                .containsExactly(activeDirectA.getId(), activeDirectB.getId(), endedDirect.getId());
        assertThat(patientExpertAssignments.findActiveById(activeDirectA.getId())).isPresent();
        assertThat(patientExpertAssignments.findActiveById(endedDirect.getId())).isEmpty();

        assertThat(cohortStaffAssignments.findActiveByCohortId(cohort.getId()))
                .extracting(assignment -> assignment.getStaffProfile().getUser().getEmail())
                .containsExactly("history-staff-a@example.com", "history-staff-b@example.com");
        assertThat(cohortStaffAssignments.findHistoryByCohortId(cohort.getId()))
                .extracting(CohortStaffAssignment::getId)
                .containsExactly(activeCohortStaffA.getId(), activeCohortStaffB.getId(), endedCohortStaff.getId());
        assertThat(cohortStaffAssignments.findActiveById(activeCohortStaffA.getId())).isPresent();
        assertThat(cohortStaffAssignments.findActiveById(endedCohortStaff.getId())).isEmpty();
    }

    @Test
    void inheritedAssignmentQueryOrdersResultsAndExcludesArchivedCohorts() {
        var assignedBy = createUser("inherited-admin@example.com", RoleName.ADMIN);
        var patient = createPatientProfile("inherited-patient@example.com");
        var staffA = createStaffProfile("inherited-a@example.com", RoleName.PHYSICIAN);
        var staffB = createStaffProfile("inherited-b@example.com", RoleName.NUTRITION_SPECIALIST);
        var alpha = cohorts.saveAndFlush(new Cohort("Alpha inherited", null, assignedBy));
        var beta = cohorts.saveAndFlush(new Cohort("Beta inherited", null, assignedBy));
        var archived = cohorts.saveAndFlush(new Cohort("Archived inherited", null, assignedBy));
        patientCohortMemberships.saveAndFlush(new PatientCohortMembership(patient, beta, assignedBy));
        patientCohortMemberships.saveAndFlush(new PatientCohortMembership(patient, alpha, assignedBy));
        patientCohortMemberships.saveAndFlush(new PatientCohortMembership(patient, archived, assignedBy));
        var betaAssignment = cohortStaffAssignments.saveAndFlush(
                new CohortStaffAssignment(beta, staffA, assignedBy));
        var alphaAssignment = cohortStaffAssignments.saveAndFlush(
                new CohortStaffAssignment(alpha, staffA, assignedBy));
        cohortStaffAssignments.saveAndFlush(new CohortStaffAssignment(archived, staffA, assignedBy));
        var staffBAssignment = cohortStaffAssignments.saveAndFlush(
                new CohortStaffAssignment(alpha, staffB, assignedBy));
        archived.archive(assignedBy, Instant.parse("2026-07-18T12:00:00Z"));
        cohorts.saveAndFlush(archived);
        entityManager.clear();

        assertThat(cohortStaffAssignments.findActiveAssignmentsForPatient(patient.getId()))
                .extracting(CohortStaffAssignment::getId)
                .containsExactly(alphaAssignment.getId(), betaAssignment.getId(), staffBAssignment.getId());
    }

    private PatientProfile createPatientProfile(String email) {
        return patientProfiles.saveAndFlush(new PatientProfile(createUser(email, RoleName.PATIENT)));
    }

    private StaffProfile createStaffProfile(String email) {
        return createStaffProfile(email, RoleName.PHYSICIAN);
    }

    private StaffProfile createStaffProfile(String email, RoleName role) {
        return staffProfiles.saveAndFlush(new StaffProfile(createUser(email, role)));
    }

    private User createUser(String email, RoleName role) {
        var user = new User(email, "hash");
        user.setEnabled(true);
        user = users.saveAndFlush(user);
        user.addRole(role);
        return users.saveAndFlush(user);
    }

    private User createDisabledUser(String email, RoleName role) {
        var user = users.saveAndFlush(new User(email, "hash"));
        user.addRole(role);
        return users.saveAndFlush(user);
    }

    private Long countRoles(Long userId) {
        return jdbc.queryForObject("select count(*) from user_roles where user_id = ?", Long.class, userId);
    }
}
